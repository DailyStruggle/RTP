package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask.ConfigCache;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;

public class Region extends FactoryValue<RegionKeys> {
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePush = new ArrayList<>();
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePop = new ArrayList<>();
  private final AtomicBoolean isScanningCache = new AtomicBoolean(false);

  // Constructed in the Region(...) constructor body, after this.settings is
  // assigned. A field initializer here would run before this.settings is set,
  // making RegionQueueManager observe settings==null and fall into its
  // fallback branch - which (per ADR-028) leaves backlogLocations null,
  // permanently disabling the backlog cache regardless of backlogCacheCap.
  public RegionQueueManager queueManager;
  public AtomicInteger inFlightCalculations =
      new AtomicInteger(0);
  /**
   * Cold->hot promotions holding a ticket while waiting for the chunk to become resident. Counts
   * toward kept/total capacity but not {@link #inFlightCalculations}, so the wait does not stall
   * {@link RegionCacheTask}'s single-flight fill gate.
   */
  public final AtomicInteger pendingKeptPromotions = new AtomicInteger(0);
  /**
   * Dedicated single-flight gate for public cache fill ({@link RegionCacheTask}). Ensures only one
   * speculative fill train runs per region, decoupled from {@link #inFlightCalculations} so concurrent
   * player teleports and promotions do not starve background cache refill.
   */
  public final AtomicBoolean inFlightCacheFill = new AtomicBoolean(false);

  public RTPTaskPipe cachePipeline;
  public RTPTaskPipe miscPipeline;
  /**
   * Cold->hot chunk loads started per pulse. Each completion costs tick-thread time outside any
   * RTP budget (chunk promotion), so a large hot deficit refills over several pulses instead of
   * landing activeChunkCap loads on one tick.
   */
  static final int MAX_PROMOTIONS_PER_PULSE = 8;
  protected RTPWorld<?> savedWorld = null;
  private long lastValidationTime = 0;

  private RegionSettings settings;
  public Shape<?> shape;

  /**
   * True when this region was instantiated before its configured world was loaded, so the
   * resolved {@link RTPWorld} is a server-primary fallback rather than the configured world.
   * Regions in this state skip destructive startup actions (e.g. DB-cache hydrate) until
   * {@link #rebindWorld(RegionSettings)} swaps them onto the real world.
   */
  public volatile boolean worldFallbackBound = false;

  /**
   * The raw world name read from the region config, preserved so a later {@code WorldLoadEvent}
   * can match and rebind this region when its true world becomes available. Null when no
   * fallback occurred.
   */
  public volatile String configuredWorldName = null;

  /**
   * Set true once this region's {@link ScanTask} has finished its full-load
   * verification pass. Used to gate backlog drain.
   */
  public volatile boolean scanCompleted = false;

  /**
   * Hysteresis latch for the backlog refill loop.
   */
  private volatile boolean backlogRefillActive = true;

  /** Share of the remaining pulse budget the backlog refill leaves for later stages and other plugins. */
  static final double BACKLOG_HEADROOM = 0.2d;

  /** Pacer for backlog pick operations. */
  private final BudgetPacer backlogPacer = BudgetPacer.ofTaskPipe(0L);


  /** Batched backlog bin verification; per instance (reset by {@link #clone()}). */
  private BacklogBinVerifier binVerifier = new BacklogBinVerifier();

  /** Records a backlog entry the region-file pre-filter denied and teaches its bad-chunk table. */
  void onBacklogReject(BacklogLocationBuffer.BacklogEntry e) {
    RtpOutcomeStats.GLOBAL.recordFailure(LocationGenerator.FailTypes.prefilterBlock);
    if (this.shape instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape ms) {
      long loc1D = ms.xzToLocation(e.location().coords().x() >> 4, e.location().coords().z() >> 4);
      if (loc1D >= 0) {
        ms.addBadChunk(loc1D, LocationGenerator.FailTypes.prefilterBlock);
      }
    }
  }

  /**
   * Lazily-created, cached shared {@link CandidateValidator} for this region. The validator is
   * stateless apart from an immutable back-reference to this region (it re-reads world/vert/config
   * on each call, so it stays correct across {@code rebindWorld}/world-border swaps), so a single
   * instance is reused for every candidate rather than allocating one per {@link #candidateValidator()}
   * call - avoiding needless GC pressure on the per-candidate hot path.
   */
  @SuppressWarnings("java:S3077") // Volatile publication of stateless cached CandidateValidator instance
  private volatile CandidateValidator candidateValidator;

  public Region(String name, RegionSettings settings) {
    this(name, settings, false, null);
  }

  public Region(String name, RegionSettings settings, boolean worldFallbackBound, String configuredWorldName) {
    super(RegionKeys.class, name);
    this.name = name;
    this.settings = settings;
    this.worldFallbackBound = worldFallbackBound;
    this.configuredWorldName = configuredWorldName;
    // Construct queueManager AFTER settings is assigned so RegionQueueManager
    // sees the real cacheCap/backlogCacheCap/activeChunkCap and allocates
    // backlogLocations when backlogCacheCap > 0 (ADR-028).
    this.queueManager = new RegionQueueManager(this);
    this.set(RegionKeys.spatialResolution, settings.spatialResolution());
    this.cachePipeline = (RTPTaskPipe) RTP.serverAccessor.createCachePipe();
    this.miscPipeline = (RTPTaskPipe) RTP.serverAccessor.createTaskPipe();

    this.shape = settings.shape();
    VerticalAdjustor<?> vert = settings.vert();

    // Self-heal a missing shape/vert so a misconfigured (or stale, pre-ADR-073)
    // config can never leave the region with a null shape/vert. A null shape was
    // already recovered to SQUARE here; a null vert previously slipped through and
    // surfaced downstream as a repeated "invalid state, null vert"
    // IllegalStateException in PregenState.build (and a region that could never
    // produce a location). Recover both, in one settings rebuild.
    boolean shapeRecovered = false;
    boolean vertRecovered = false;
    if (this.shape == null) {
      Factory<Shape<?>> sf = (RTP.selectionAPI != null) ? RTP.selectionAPI.getShapeFactory() : null;
      if (sf == null && RTP.factoryMap != null) {
        @SuppressWarnings("unchecked")
        Factory<Shape<?>> casted = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        sf = casted;
      }
      if (sf != null) {
        this.shape = (Shape<?>) sf.get("SQUARE");
      }
      if (this.shape == null) {
        this.shape = new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
      }
      RTP.log(Level.WARNING, "Shape for region " + name + " was invalid. Falling back to SQUARE.");
      shapeRecovered = true;
    }
    if (vert == null) {
      Factory<VerticalAdjustor<?>> vf = null;
      if (RTP.factoryMap != null) {
        @SuppressWarnings("unchecked")
        Factory<VerticalAdjustor<?>> casted = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        vf = casted;
      }
      if (vf != null) {
        vert = (VerticalAdjustor<?>) vf.get("LINEAR");
      }
      if (vert == null) {
        vert = new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
      }
      RTP.log(Level.WARNING, "Vert for region " + name + " was invalid. Falling back to LINEAR.");
      vertRecovered = true;
    }
    if (shapeRecovered || vertRecovered) {
      this.settings = new RegionSettings(
          settings.name(),
          settings.world(),
          this.shape,
          vert,
          settings.worldBorderOverride(),
          settings.requirePermission(),
          settings.cacheCap(),
          settings.backlogCacheCap(),
          settings.networkReserveSize(),
          settings.activeChunkCap(),
          settings.price(),
          settings.spatialResolution(),
          settings.override(),
          settings.detailedRegionInit()
      );
      settings = this.settings;
    }

    if (this.shape != null && this.shape instanceof MemoryShape<?> memoryShape) memoryShape.setSpatialResolution(settings.spatialResolution());

    // Lobby backends never serve teleports to local coords -
    // peers see regions=[] / acceptingRequests=false (BukkitBackendStateSampler)
    // and the no-arg /rtp path is intercepted by BukkitNetworkCommandHook to
    // dispatch to a peer. Skip the per-region ScanTask pre-fill crawler and
    // the DB hydrate entirely; both are pure waste on a lobby. Region objects
    // are still constructed so /rtp info, config menus, and tab-completion
    // keep working.
    if (RTP.lobbyMode) {
      return;
    }

    // Skip the MemoryShape scan-task bootstrap for dormant regions: the shape's data file
    // was not loaded (world not available yet) so getLoadFuture() has no backing load. The
    // scan-task wiring happens implicitly when rebindWorld(...) swaps in freshly-loaded
    // settings produced by RegionConfigLoader.load against the now-loaded world.
    if (!this.worldFallbackBound && this.shape instanceof MemoryShape<?>) {
      long[] progress = ScanTask.loadProgress(name, cacheKey());
      if (progress != null) {
        long iter = progress[0];
        if (iter > 0 && iter < ((MemoryShape<?>) this.shape).getRange()) {
          MemoryShape<?> ms = (MemoryShape<?>) this.shape;
          ScanTask task = new ScanTask(this, iter);
          RTP.getInstance().scanTasks.put(name, task);
          ms.getLoadFuture().whenComplete((v, t) -> RTP.scheduler.runTaskAsynchronously(task));
        }
      }
    }

    // Hydrate locations from database asynchronously. Skip for fallback-bound regions
    // until rebindWorld() is called.
    if (!this.worldFallbackBound) {
      RTP.scheduler.runTaskAsynchronously(this::hydrateFromDatabaseIfAvailable);
    }
  }

  /**
   * Reads cached locations for this region from the database (if any) and feeds them into the
   * local queues.
   */
  private void hydrateFromDatabaseIfAvailable() {
    if (RTP.getInstance().databaseAccessor == null) return;
    List<DatabaseAccessor.StoredLocation> storedLocations =
        RTP.getInstance().databaseAccessor.loadCachedLocations(name);
    if (storedLocations.isEmpty()) return;
    hydrateCacheFromDatabase(storedLocations);
    String msg = RTP.configs.getConfigValue(SystemMessages.locationLoaded, "").toString();
    if (!msg.isEmpty()) {
      msg = msg.replace("[amount]", String.valueOf(storedLocations.size()));
      msg = msg.replace("[region]", name);
      RTP.log(Level.INFO, msg);
    }
  }

  /**
   * Replaces this region's {@link RegionSettings} with settings resolved against a now-loaded
   * configured world, then hydrates cached locations from the database.
   *
   * @param newSettings settings produced after the configured world was loaded
   */
  public void rebindWorld(RegionSettings newSettings) {
    // Purge any queued locations bound to the fallback world. None should have actually been
    // generated yet (hydrate was skipped), but the generator and scan tasks may have produced
    // some during the short window between construction and world load.
    io.github.dailystruggle.rtp.common.selection.region.RTPLocation stale;
    while ((stale = queueManager.keptLocations.poll()) != null) {
      if (stale.reservation() != null) stale.reservation().close();
    }
    queueManager.perPlayerLocationQueue.forEach((uuid, queue) -> {
      io.github.dailystruggle.rtp.common.selection.region.RTPLocation pStale;
      while ((pStale = queue.poll()) != null) {
        if (pStale.reservation() != null) pStale.reservation().close();
      }
    });
    queueManager.perPlayerLocationQueue.clear();
    queueManager.unkeptLocations.clear();

    setSettings(newSettings);
    this.worldFallbackBound = false;
    this.configuredWorldName = null;

    // Lobby backends skip local region processing - no scan-task
    // bootstrap, no DB hydrate. Same rationale as the constructor gate above.
    if (RTP.lobbyMode) {
      return;
    }

    // Re-run the MemoryShape scan-task bootstrap that was skipped at construction because
    // the region was dormant. The new shape (from newSettings) has been loaded against the
    // now-available world, so getLoadFuture() has a real backing load.
    if (this.shape instanceof MemoryShape<?>) {
      long[] progress = ScanTask.loadProgress(name, cacheKey());
      if (progress != null) {
        long iter = progress[0];
        if (iter > 0 && iter < ((MemoryShape<?>) this.shape).getRange()) {
          MemoryShape<?> ms = (MemoryShape<?>) this.shape;
          ScanTask task = new ScanTask(this, iter);
          RTP.getInstance().scanTasks.put(name, task);
          ms.getLoadFuture().whenComplete((v, t) -> RTP.scheduler.runTaskAsynchronously(task));
        }
      }
    }

    // See note in the constructor: hydration is async to keep DB I/O off the calling thread.
    RTP.scheduler.runTaskAsynchronously(this::hydrateFromDatabaseIfAvailable);
  }

  public RegionSettings getSettings() {
    return settings;
  }

  @Override
  public void set(RegionKeys key, Object value) {
    super.set(key, value);
    if (key == RegionKeys.shape && value instanceof Shape<?> newShape) {
      this.shape = newShape;
      if (this.settings != null) {
        this.settings = new RegionSettings(
            settings.name(),
            settings.world(),
            newShape,
            settings.vert(),
            settings.worldBorderOverride(),
            settings.requirePermission(),
            settings.cacheCap(),
            settings.backlogCacheCap(),
            settings.networkReserveSize(),
            settings.activeChunkCap(),
            settings.price(),
            settings.spatialResolution(),
            settings.override(),
            settings.detailedRegionInit()
        );
      }
    }
  }

  public void setSettings(RegionSettings settings) {
    // Capture the old cache key BEFORE we replace shape/settings - this is what the
    // (about-to-be-orphaned) on-disk .bin and .scan files are keyed by.
    String oldCacheKey = cacheKey();

    this.settings = settings;
    this.shape = settings.shape();
    this.set(RegionKeys.spatialResolution, settings.spatialResolution());
    if (this.shape != null && this.shape instanceof MemoryShape<?> memoryShape) memoryShape.setSpatialResolution(settings.spatialResolution());

    String newCacheKey = cacheKey();
    if (!oldCacheKey.equals(newCacheKey)) {
      // The shape or vertical adjustor changed in a way that invalidates the spiral
      // 1D->2D mapping or the validity predicate. The old persisted shape data and scan
      // progress are stale - drop them so a fresh ScanTask cycle starts on the new geometry.
      // ADR-022 calls for safety-first invalidation here; the alternative (silently
      // overwriting the in-memory MemoryShape with stale flags) is the latent bug we
      // are closing.
      RTPWorld<?> world = getWorld();
      if (world != null) {
        try {
          java.io.File pluginDir = RTP.serverAccessor.getPluginDirectory();
          java.io.File regionDataDir = new java.io.File(pluginDir,
              "database" + java.io.File.separator + "regionData");
          // Stale .bin under the old key. The new shape is a fresh MemoryShape; nothing
          // currently references the old file, so deleting it is safe.
          java.io.File staleBin = new java.io.File(regionDataDir, name + "_" + oldCacheKey + ".bin");
          if (staleBin.exists() && !staleBin.delete()) {
            RTP.log(Level.WARNING, "[Region:" + name + "] could not delete stale shape cache "
                + staleBin.getName() + " after config change");
          }
          // Stale .scan progress under the old key.
          java.io.File staleScan = new java.io.File(regionDataDir, name + "_" + oldCacheKey + ".scan");
          if (staleScan.exists() && !staleScan.delete()) {
            RTP.log(Level.WARNING, "[Region:" + name + "] could not delete stale scan progress "
                + staleScan.getName() + " after config change");
          }
        } catch (RuntimeException e) {
          RTP.log(Level.WARNING, "[Region:" + name + "] failed to clean stale cache files: "
              + e.getMessage(), e);
        }
      }
      RTP.log(Level.INFO, "[Region:" + name + "] cache key changed (" + oldCacheKey
          + " -> " + newCacheKey + "); shape data invalidated, fresh scan required.");
      if (this.queueManager != null) {
        this.queueManager.purgeOutsideBounds();
      }
    }
  }

  /**
   * Purges pre-cached locations outside current shape/vert boundaries from this region's queues.
   *
   * @return the number of purged locations
   */
  public int purgeOutsideBounds() {
    if (this.queueManager != null) {
      return this.queueManager.purgeOutsideBounds();
    }
    return 0;
  }

  /**
   * Compute on-disk cache key suffix for this region (format {@code "<seed>_<12hex>"}).
   */
  public String cacheKey() {
    RTPWorld<?> world = getWorld();
    return RegionCacheKey.cacheKey(world, this.shape, getVert());
  }

  /**
   * Same key folded to a {@code long} for the legacy {@code rtp_cached_locations.seed} column.
   */
  public long cacheKeyLong() {
    RTPWorld<?> world = getWorld();
    return RegionCacheKey.cacheKeyLong(world, this.shape, getVert());
  }

  public void hydrateCacheFromDatabase(List<DatabaseAccessor.StoredLocation> storedLocations) {
    long currentCacheKey = cacheKeyLong();
    if (storedLocations.size() > 1) {
      java.util.Collections.shuffle(storedLocations);
    }

    this.queueManager.keptLocations.setCallbacks(null, null);
    this.queueManager.unkeptLocations.setCallbacks(null, null);
    try {
      DatabaseAccessor db = RTP.getInstance().databaseAccessor;
      for (DatabaseAccessor.StoredLocation stored : storedLocations) {
        if ((stored.getSeed() != 0L && stored.getSeed() != currentCacheKey)
            || (getShape() != null && !getShape().contains(stored.getX() >> 4, stored.getZ() >> 4))) {
          if (db != null) db.removeCachedLocation(stored.getId());
          continue;
        }

        RTPCoords coords = new RTPCoords(stored.getWorldName(), stored.getX(), stored.getY(), stored.getZ());
        RTPLocation recoveredLoc = new RTPLocation(coords, stored.getAttempts(), null);

        if (stored.getPlayerId() == null) {
          this.queueManager.unkeptLocations.offer(recoveredLoc);
          if (db != null) db.removeCachedLocation(stored.getId());
        } else {
          this.queueManager.perPlayerLocationQueue.computeIfAbsent(stored.getPlayerId(), k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(recoveredLoc);
          if (db != null) db.removeCachedLocation(stored.getId());
        }
        RtpOutcomeStats.GLOBAL.recordSuccess();
      }
    } finally {
      this.queueManager.installDatabaseCallbacks();
    }
  }

  /**
   * Diagnostic for cold->hot promotion drop path.
   */
  private void logPromotionDropDiag(
      RTPLocation coldLoc,
      io.github.dailystruggle.rtp.api.world.RTPChunk<?> rtpChunk,
      int cx, int cz) {
    try {
      RTPCoords coords = (coldLoc != null) ? coldLoc.coords() : null;
      int lx = (coords != null) ? (coords.x() & 15) : 7;
      int lz = (coords != null) ? (coords.z() & 15) : 7;
      int chosenY = (coords != null) ? coords.y() : 64;

      if (rtpChunk == null) {
        // Transient null: returned to unkept queue for retry.
        RTP.log(Level.FINE,
            "[RTP][PROMOTE_DIAG] region=" + name + " cold=(" + (coords != null ? coords.x() : ((cx << 4) + lx)) + ","
                + chosenY + "," + (coords != null ? coords.z() : ((cz << 4) + lz)) + ") chunk=(" + cx + "," + cz
                + ") col=(" + lx + "," + lz + ")"
                + " transient: getCachedChunk returned null "
                + "(chunk not retained through the verify window; returned to unkept for retry).");
        return;
      }

      // Derive highest non-air block on column (lx, lz) using block checks (top-down),
      // never using heightmaps (which are not used for placement and may be unpopulated).
      io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = (rtpChunk.getWorld() != null) ? rtpChunk.getWorld() : getWorld();
      int worldMax = (world != null) ? world.getMaxHeight() : 320;
      int worldMin = (world != null) ? world.getMinHeight() : -64;
      int columnSurfaceY = Integer.MIN_VALUE;
      try {
        for (int y = worldMax - 1; y >= worldMin; y--) {
          if (!rtpChunk.isAir(lx, y, lz)) {
            columnSurfaceY = y;
            break;
          }
        }
      } catch (Throwable t) {
        // block read failure on column scan
      }

      String biome;
      try {
        biome = rtpChunk.getBiome(lx, chosenY, lz);
      } catch (Throwable t) {
        biome = "<biome-read-threw:" + t.getClass().getSimpleName() + ">";
      }

      boolean airBelow;
      boolean airAt;
      boolean airAbove;
      try {
        airBelow = rtpChunk.isAir(lx, chosenY - 1, lz);
        airAt = rtpChunk.isAir(lx, chosenY, lz);
        airAbove = rtpChunk.isAir(lx, chosenY + 1, lz);
      } catch (IllegalArgumentException yOutOfRange) {
        RTP.log(Level.FINE,
            "[RTP][PROMOTE_DIAG] region=" + name + " chunk=(" + cx + "," + cz
                + ") col=(" + lx + "," + lz + ") chosenY=" + chosenY
                + " DROPPED vert.adjust=null; chosen Y is out of range: "
                + yOutOfRange.getMessage());
        return;
      } catch (Throwable t) {
        RTP.log(Level.INFO,
            "[RTP][PROMOTE_DIAG] region=" + name + " chunk=(" + cx + "," + cz
                + ") col=(" + lx + "," + lz + ") chosenY=" + chosenY
                + " DROPPED vert.adjust=null but isAir read THREW "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
                + " (block reads are broken on this platform chunk).");
        return;
      }

      int skyLight = -1;
      try {
        skyLight = rtpChunk.getSkyLight(lx, chosenY, lz);
      } catch (Throwable ignored) {
        // best-effort
      }

      RTP.log(Level.FINE,
          "[RTP][PROMOTE_DIAG] region=" + name + " chunk=(" + cx + "," + cz
              + ") cold=(" + (coords != null ? coords.x() : ((cx << 4) + lx)) + ","
              + chosenY + "," + (coords != null ? coords.z() : ((cz << 4) + lz)) + ")"
              + " col=(" + lx + "," + lz + ")"
              + " DROPPED vert.adjust=null"
              + " generated=" + rtpChunk.isGenerated()
              + " loaded=" + rtpChunk.isLoaded()
              + " anvilBacked=" + rtpChunk.isSelfContained()
              + " chosenY=" + chosenY
              + " columnSurfaceY=" + (columnSurfaceY == Integer.MIN_VALUE ? "all-air" : columnSurfaceY)
              + " biome=" + biome
              + " isAir[y-1/y/y+1]=" + airBelow + "/" + airAt + "/" + airAbove
              + (skyLight >= 0 ? " skyLight=" + skyLight : "")
              + " (live+generated -> vertical adjustor found no safe standing column;"
              + " anvilBacked or columnSurfaceY==all-air -> ungenerated/empty chunk).");
    } catch (Throwable diagEx) {
      RTP.log(Level.INFO,
          "[RTP][PROMOTE_DIAG] diagnostic itself failed for region=" + name
              + " chunk=(" + cx + "," + cz + "): "
              + diagEx.getClass().getSimpleName() + ": " + diagEx.getMessage());
    }
  }

  /**
   * Release retained hot-cache chunk tickets under JVM heap pressure.
   */
  private void shedHotCacheUnderPressure() {
    final int reserve = 8;
    final int maxPerPulse = 32;
    int surplus = queueManager.keptLocations.size() - reserve;
    if (surplus <= 0) return;
    int budget = Math.min(surplus, maxPerPulse);
    int shed = 0;
    for (int i = 0; i < budget; i++) {
      RTPLocation hot = queueManager.keptLocations.pollSilently();
      if (hot == null) break;
      RTPLocation cold = hot;
      if (hot.reservation() != null) {
        hot.reservation().close();
        cold = new RTPLocation(hot.coords(), hot.attempts());
      }
      queueManager.unkeptLocations.offerSilently(cold);
      shed++;
    }
    if (shed > 0) {
      RTP.log(Level.FINE,
          "[Region:" + name + "] shed " + shed + " hot chunk ticket(s) under heap pressure; kept="
              + queueManager.keptLocations.size());
    }
  }

  /** Upper bound on waiting for a promoted chunk's ticket and native load before giving up. */
  static final long KEPT_READY_TIMEOUT_MS = 5_000L;

  /**
   * Offer a promoted location to {@code keptLocations} only once its ticket has applied and the
   * chunk is resident. Consumers take kept entries the moment they appear, so offering on ticket
   * request let teleports race the platform's async load and pay it in the teleport wait.
   * Timeout, failure or a non-resident chunk closes the reservation (S-002) and returns the
   * location to {@code unkeptLocations}. Held in {@link #pendingKeptPromotions} until settled on
   * every path. Waits on a copy: the apply future is shared by ref-counted callers of the same
   * chunk.
   */
  @SuppressWarnings("java:S2093") // ownership transfers to keptLocations on a successful offer
  void offerKeptWhenResident(
      RTPLocation coldLoc, RTPCoords resolved, ChunkReservation reservation, int cx, int cz) {
    pendingKeptPromotions.incrementAndGet();
    CompletableFuture<Void> ready;
    try {
      ready = reservation.readyFuture().copy()
          .orTimeout(KEPT_READY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (Throwable t) {
      ready = CompletableFuture.failedFuture(t);
    }
    ready.whenComplete((v, ex) -> {
      try {
        RTPWorld<?> world = getWorld();
        boolean resident = ex == null && world != null && world.isChunkLoaded(cx, cz);
        if (!resident) {
          reservation.close();
          queueManager.unkeptLocations.offer(coldLoc);
          RTP.log(Level.FINE,
              "[Region:" + name + "] kept promotion chunk=(" + cx + "," + cz + ") not resident ("
                  + (ex != null ? ex.getClass().getSimpleName() : "unloaded")
                  + "); returned to unkept");
          return;
        }
        boolean added = queueManager.keptLocations.offer(
            new RTPLocation(resolved, coldLoc.attempts(), reservation));
        RTP.log(Level.FINER,
            "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
                + ") keptLocations.offer added=" + added
                + " keptSize=" + queueManager.keptLocations.size());
        if (!added) {
          reservation.close();
          queueManager.unkeptLocations.offer(coldLoc);
          RTP.log(Level.FINE,
              "[Region:" + name + "] PushQueue rejected (cache full); returned to unkept");
        } else {
          // Diagram 02 (PushQueue): cache successfully grew.
          RTP.log(Level.FINE,
              "[Region:" + name + "] PushQueue: kept-cache size="
                  + queueManager.keptLocations.size());
        }
      } catch (Throwable t) {
        reservation.close();
        RTP.log(Level.WARNING, "[Region:" + name + "] kept promotion failed: " + t, t);
      } finally {
        pendingKeptPromotions.decrementAndGet();
      }
    });
  }

  /**
   * Drops a cold location that failed promotion and records the cause. pollSilently skipped the
   * delete callback; offer+poll fires it so the DB row is purged.
   */
  private void dropColdLocation(RTPLocation coldLoc, LocationGenerator.FailTypes cause) {
    RtpOutcomeStats.GLOBAL.recordFailure(cause);
    queueManager.unkeptLocations.offer(coldLoc);
    queueManager.unkeptLocations.poll();
  }

  /**
   * Promotion checks after the vertical adjustor placed {@code resolved} on the loaded
   * {@code center} chunk: world border, biome, safety scan and claims (S-001, S-003). Cold holds
   * backlog entries the region-file pre-filter only screened for unsafe surface blocks, and
   * stored locations, so this is their full check. Safety neighbours resolve from resident
   * chunks or region files first and load natively only on a miss; claims run async (S-005).
   *
   * @return {@code null} on pass, else the failing check
   */
  CompletableFuture<LocationGenerator.FailTypes> promotionChecks(
      RTPCoords resolved, io.github.dailystruggle.rtp.api.world.RTPChunk<?> center) {
    RTPWorld<?> world = getWorld();
    if (world == null) return CompletableFuture.completedFuture(LocationGenerator.FailTypes.nullChunk);

    Object border = RTP.serverAccessor.getWorldBorder(world.name());
    if (border instanceof io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder wb
        && !wb.isInside().apply(new io.github.dailystruggle.rtp.api.world.RTPLocation(
            world, resolved.x(), resolved.y(), resolved.z()))) {
      return CompletableFuture.completedFuture(LocationGenerator.FailTypes.worldBorder);
    }

    Object wl = RTP.configs.getConfigValue(
        io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys.biomeWhitelist, false);
    boolean whitelist = (wl instanceof Boolean b) ? b : Boolean.parseBoolean(String.valueOf(wl));
    Set<String> biomes = new HashSet<>();
    if (RTP.configs.getConfigValue(
        io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys.biomes, null) instanceof List<?> list) {
      for (Object o : list) if (o != null) biomes.add(o.toString().toUpperCase());
    }
    String biome = center.getBiome(resolved.x(), resolved.y(), resolved.z());
    if (BiomeNames.matches(biomes, (biome == null) ? "" : biome.toUpperCase()) != whitelist) {
      return CompletableFuture.completedFuture(LocationGenerator.FailTypes.biome);
    }

    int safe = 0;
    Set<String> unsafe = new HashSet<>();
    @SuppressWarnings("unchecked")
    io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys> safety =
        (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys>)
            RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class);
    if (safety != null) {
      safe = Math.max(0, safety.getNumber(
          io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.safetyRadius, 0).intValue());
    }
    if (RTP.configs.getConfigValue(
        io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys.unsafeBlocks,
        new ArrayList<>()) instanceof java.util.Collection<?> c) {
      for (Object o : c) if (o != null) unsafe.add(o.toString().toUpperCase());
    }

    // Neighbour chunks the block radius reaches, indexed as SafetyScan expects.
    final int r = safe;
    final int L = 2 * r + 1;
    final int ccx = center.x();
    final int ccz = center.z();
    final io.github.dailystruggle.rtp.api.world.RTPChunk<?>[] grid =
        new io.github.dailystruggle.rtp.api.world.RTPChunk<?>[L * L];
    grid[r * L + r] = center;
    List<CompletableFuture<?>> pending = new ArrayList<>();
    for (int ncx = (resolved.x() - r) >> 4; ncx <= (resolved.x() + r) >> 4; ncx++) {
      for (int ncz = (resolved.z() - r) >> 4; ncz <= (resolved.z() + r) >> 4; ncz++) {
        if (ncx == ccx && ncz == ccz) continue;
        final int idx = (ncx - ccx + r) * L + (ncz - ccz + r);
        final int fx = ncx;
        final int fz = ncz;
        pending.add(world.getOrReadChunk(fx, fz).thenCompose(read -> {
          if (read != null) return CompletableFuture.completedFuture(read);
          world.recordChunkLoadOrigin("Region.promotionSafety");
          return world.getChunkAt(fx, fz).thenApply(k -> (k != null) ? world.getCachedChunk(k) : null);
        }).thenAccept(chunk -> grid[idx] = chunk));
      }
    }
    final Set<String> unsafeBlocks = unsafe;
    java.util.function.Supplier<CompletableFuture<LocationGenerator.FailTypes>> scanThenClaims = () -> {
      if (!SafetyScan.isColumnSafe(resolved, world, grid, L, ccx, ccz, r, unsafeBlocks)) {
        return CompletableFuture.completedFuture(LocationGenerator.FailTypes.safety);
      }
      CompletableFuture<Boolean> claims = GlobalRegionVerifiers.checkGlobalRegionVerifiers(resolved);
      if (claims == null) return CompletableFuture.completedFuture(LocationGenerator.FailTypes.safetyExternal);
      return claims.handle((ok, ex) ->
          (ex == null && Boolean.TRUE.equals(ok)) ? null : LocationGenerator.FailTypes.safetyExternal);
    };
    // Same chunk only: still on the caller's (region) thread.
    if (pending.isEmpty()) return scanThenClaims.get();
    return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
        .orTimeout(KEPT_READY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .thenCompose(v -> {
          if (!isFoliaPlatform()) return scanThenClaims.get();
          // Folia: live block reads belong on the chunk's region thread.
          CompletableFuture<LocationGenerator.FailTypes> out = new CompletableFuture<>();
          RTP.scheduler.runTask(world, ccx, ccz, () -> scanThenClaims.get().whenComplete((f, e) -> {
            if (e != null) out.completeExceptionally(e);
            else out.complete(f);
          }));
          return out.orTimeout(KEPT_READY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
  }

  /**
   * Loads the chunk of cold candidate {@code coldLoc}, re-verifies its column and runs the
   * promotion checks; the candidate ends in kept, back in cold, or dropped. Holds one
   * {@code inFlightCalculations} slot until the outcome is known.
   */
  private void promoteCold(RTPLocation coldLoc, int cx, int cz) {
    inFlightCalculations.incrementAndGet();
    RTP.log(Level.FINER,
        "[Region:" + name + "] SpawnWorker for cold (" + coldLoc.coords().x()
            + "," + coldLoc.coords().y() + "," + coldLoc.coords().z() + ")");

    getWorld().recordChunkLoadOrigin("Region.coldPromote");
    io.github.dailystruggle.rtp.common.tools.CfDiag.regionDeficitDispatch.increment();
    RTP.log(Level.FINER,
        "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
            + ") getChunkAtAsync DISPATCHED inFlight=" + inFlightCalculations.get());
    getWorld().getChunkAtAsync(cx, cz).thenAccept(chunkSet -> {
      RTP.log(Level.FINER,
          "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
              + ") thenAccept FIRED chunkSet=" + (chunkSet == null ? "null" : "present")
              + " complete()=" + (chunkSet == null || chunkSet.complete() == null
                  ? "null" : "done=" + chunkSet.complete().isDone()));
      chunkSet.complete().whenComplete((success, throwable) -> {
        RTP.log(Level.FINER,
            "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
                + ") complete.whenComplete success=" + success
                + (throwable == null ? "" : " throwable=" + throwable.getClass().getSimpleName()
                    + ":" + throwable.getMessage()));
        if (success == null || !success) {
          try {
            RTP.log(Level.FINER,
                "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
                    + ") DROPPED success-false; returned to unkept");
            queueManager.unkeptLocations.offer(coldLoc);
          } finally {
            inFlightCalculations.decrementAndGet();
          }
          return;
        }
        // Second-pass safety verification on Folia region thread or inline.
        // Re-runs vertical adjustor against loaded chunk to ensure valid ground placement.
        @SuppressWarnings("java:S2093") // ChunkReservation ownership is transferred on successful offer; closing in try-with-resources would invalidate cached chunks
        Runnable verify = () -> {
          boolean handedOff = false;
          try {
            RTP.log(Level.FINER,
                "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
                    + ") verify ENTER");
            RTPCoords resolved = null;
            io.github.dailystruggle.rtp.api.world.RTPChunk<?> rtpChunk = null;
            try {
              long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
              rtpChunk = getWorld().getCachedChunk(key);
              VerticalAdjustor<?> v = getVert();
              RTP.log(Level.FINER,
                  "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
                      + ") verify cachedChunk=" + (rtpChunk == null ? "null" : "present")
                      + " vert=" + (v == null ? "null" : v.getClass().getSimpleName()));
              if (rtpChunk != null && v != null) {
                RTPCoords storedCoords = coldLoc.coords();
                resolved = v.adjustColumn(rtpChunk, storedCoords.x() & 15, storedCoords.z() & 15);
                if (resolved == null) {
                  resolved = v.adjust(rtpChunk);
                }
              }
              RTP.log(Level.FINER,
                  "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
                      + ") verify adjust resolved=" + (resolved == null ? "null" : "present"));
            } catch (Throwable verifyEx) {
              resolved = null;
              RTP.log(
                  Level.FINE,
                  "[Region] unkept→kept safety re-verification failed: "
                      + verifyEx.getClass().getSimpleName() + ": " + verifyEx.getMessage());
            }

            if (rtpChunk == null) {
              // Transient verify failure: chunk not retained through window; re-queue cold.
              logPromotionDropDiag(coldLoc, null, cx, cz);
              queueManager.unkeptLocations.offer(coldLoc);
              return;
            }

            if (resolved == null) {
              // [PROMOTE_DIAG] Cold->hot promotion rejected this candidate
              // because the vertical adjustor found no safe standing column in
              // the (re)loaded chunk. This is the silent drop that leaves the hot stage
              // (keptLocations) empty when every promotion fails. Log the
              // chunk's backing mode + a sample of its block/biome reads so a
              // broken platform chunk read (everything air / wrong block ids /
              // ungenerated anvil view) is distinguishable from a genuinely
              // unsafe column.
              logPromotionDropDiag(coldLoc, rtpChunk, cx, cz);
              dropColdLocation(coldLoc, LocationGenerator.FailTypes.vert);
              return;
            }

            ChunkReservation reservation = new ChunkReservation(chunkSet, getWorld());
            final RTPCoords checked = resolved;
            CompletableFuture<LocationGenerator.FailTypes> checks;
            try {
              checks = promotionChecks(checked, rtpChunk);
            } catch (Throwable t) {
              checks = CompletableFuture.failedFuture(t);
            }
            handedOff = true;
            checks.whenComplete((cause, ex) -> {
              try {
                if (ex == null && cause == null) {
                  // Moves the slot to pendingKeptPromotions before the in-flight release below.
                  offerKeptWhenResident(coldLoc, checked, reservation, cx, cz);
                  return;
                }
                reservation.close();
                RTP.log(Level.FINE, "[Region:" + name + "] promotion chunk=(" + cx + "," + cz
                    + ") dropped: " + (ex != null ? ex.toString() : cause.name()));
                dropColdLocation(coldLoc, (ex != null) ? LocationGenerator.FailTypes.misc : cause);
              } catch (Throwable t) {
                reservation.close();
                RTP.log(Level.WARNING, "[Region:" + name + "] promotion check failed: " + t, t);
              } finally {
                inFlightCalculations.decrementAndGet();
              }
            });
          } finally {
            if (!handedOff) inFlightCalculations.decrementAndGet();
          }
        };
        if (isFoliaPlatform()) {
          RTP.scheduler.runTask(getWorld(), cx, cz, verify);
        } else if (isOnTickThread()) {
          // Paper completes chunk futures on the main thread: queue the column re-check and
          // promotion checks on the budgeted sync pipe instead of running them in the callback.
          RTP.getInstance().miscSyncTasks.add(verify);
        } else {
          verify.run();
        }
      });
    }).exceptionally(throwable -> {
      RTP.log(Level.FINER,
          "[RTP][PROMOTE_TRACE] region=" + name + " chunk=(" + cx + "," + cz
              + ") getChunkAtAsync EXCEPTIONALLY "
              + (throwable == null ? "null" : throwable.getClass().getSimpleName()
                  + ":" + throwable.getMessage()) + "; returned to unkept");
      inFlightCalculations.decrementAndGet();
      queueManager.unkeptLocations.offer(coldLoc);
      return null;
    });
  }

  public void execute(long availableTime) {
    io.github.dailystruggle.rtp.common.tools.CfDiag.regionExecute.increment();
    // Lobby backends advertise regions=[]/acceptingRequests=false
    // and route every /rtp to a peer (BukkitNetworkCommandHook). The local
    // pulse - cold->hot promotion, backlog drain, ticket validation, chunk
    // I/O - is pure waste on a lobby. Region objects are still constructed so
    // /rtp info, config menus, and tab-completion keep working, but their
    // queues stay empty.
    if (RTP.lobbyMode) return;
    // Dormant regions (configured world not yet loaded) must not attempt chunk I/O,
    // ticket validation, or cache generation - they activate via rebindWorld once
    // WorldLoadEvent delivers the configured world.
    if (getWorld() == null) return;

    long now = System.currentTimeMillis();
    if (now - lastValidationTime > 60000) {
      this.queueManager.validateTickets(getWorld());
      lastValidationTime = now;
    }

    long start = System.nanoTime();

//    System.out.println("[RTP-DEBUG] Region '" + name + "' execute() STARTED. Initial budget: " + availableTime + "ns");

    // minTPS / maxMSPT gate: an unhealthy tick pauses refill work (backlog picks, cold->hot
    // loads, cold fill); serving waiting players below continues regardless.
    boolean tickUnhealthy = io.github.dailystruggle.rtp.common.tools.ServerHealthGate.shouldPause();

    // ADR-028 - backlog cache pulse: refill with shape-only picks within the pulse
    // budget, pre-filter region-file bins (no chunk loads, S-005), and drain validated
    // entries into cold. UNKNOWN entries are taken by the cold fill below
    // (RegionCacheTask). Skipped when backlogCacheCap=0.
    processBacklog(tickUnhealthy ? 0L : availableTime, start);

    // Region.java - inside execute()
    long activeCap = settings.activeChunkCap();
    long currentHot = queueManager.keptLocations.size();
    long pendingKept = pendingKeptPromotions.get();
    long deficit = activeCap - (currentHot + inFlightCalculations.get() + pendingKept);

    // Diagram 02 (ExecuteRegion -> SpawnWorker): per-region budget enforcement.
    if (deficit > 0) {
      RTP.log(Level.FINE,
          "[Region:" + name + "] hot deficit=" + deficit + " (activeCap=" + activeCap
              + ", kept=" + currentHot + ", inFlight=" + inFlightCalculations.get()
              + ", pendingKept=" + pendingKept + ")");
    }

    // Heap-pressure gate: each cold->hot promotion below loads (and retains via
    // a chunk ticket) a chunk. On a small-heap server an unbounded fill grows
    // the retained set until the JVM thrashes GC and the server appears to hang
    // with no console error. Skip the fill while the heap is above
    // PerformanceKeys.maxHeapPercent; serving already-cached locations to
    // waiting players (the playerQueue drain below) continues regardless.
    boolean heapUnderPressure =
        io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor.underPressure();
    long fillDeficit = (heapUnderPressure || tickUnhealthy) ? 0 : Math.min(deficit, MAX_PROMOTIONS_PER_PULSE);
    if ((heapUnderPressure || tickUnhealthy) && deficit > 0) {
      RTP.log(Level.FINE,
          "[Region:" + name + "] hot-cache fill paused ("
              + (heapUnderPressure ? "heap pressure" : "tick health") + "); deficit=" + deficit);
    }

    // Active shed: under heap pressure, release kept chunk tickets back to unkeptLocations.
    if (heapUnderPressure) {
      shedHotCacheUnderPressure();
    }

    for (int i = 0; i < fillDeficit; i++) {
      boolean outOfTime = i > 0 && System.nanoTime() - start >= availableTime;
      // Silent poll: this location is about to be re-offered to keptLocations under an
      // identical DB composite key (region:world:x:y:z). Firing the delete callback here
      // and the save callback on the kept offer races inside DatabaseAccessor's
      // writeQueue/deleteQueue and can net out to a row loss across restarts (symptom:
      // `/rtp info` showing cacheCap+activeCap-1 instead of the expected total). Keeping
      // the source poll silent preserves the DB row through the promotion; the kept
      // offer's save callback then acts as an idempotent upsert on the same key.
      RTPLocation coldLoc = outOfTime ? null : queueManager.unkeptLocations.pollSilently();
      if (coldLoc == null) break;

      int cx = coldLoc.coords().x() >> 4;
      int cz = coldLoc.coords().z() >> 4;
      // ADR-087 regional circuit breaker: a backlogged or slow region keeps its candidate cold;
      // the next candidate (usually another region) takes this slot, so load is divided, not stacked.
      if (RTP.scheduler != null && RTP.scheduler.isRegionSaturated(getWorld(), cx, cz)) {
        queueManager.unkeptLocations.offerSilently(coldLoc); // silent: the poll kept its DB row
      } else {
        promoteCold(coldLoc, cx, cz);
      }
    }

    while (!queueManager.playerQueue.isEmpty()) {
      UUID playerId = queueManager.playerQueue.peek();
      if (playerId == null) break;

      // Hoist the offline check here to purge dead requests instantly
      RTPPlayer player = RTP.serverAccessor.getPlayer(playerId);
      if (player == null) {
        queueManager.playerQueue.poll();
        RTP.getInstance().processingPlayers.remove(playerId);
        RTP.getInstance().queuedPlayers.remove(playerId);
        RTP.getInstance().invulnerablePlayers.remove(playerId);

        TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
        if (data != null && !data.completed && data.nextTask instanceof TeleportPipelineTask task) {
          task.setCancelled(true);
          if (task.coords() != null) {
            RTP.scheduler.runTask(
                    task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
          } else {
            RTP.scheduler.runTask(task);
          }
        }

        RTP.scheduler.runTask(new RTPTeleportCancel(playerId));
        continue;
      }

      ConcurrentLinkedQueue<RTPLocation> privateQueue = queueManager.getPerPlayerQueue(playerId);
      RTPLocation pair = null;
      boolean isPrivate = false;

      // Prioritize private queue (biome searches/specific requests) over public queue
      if (privateQueue != null && !privateQueue.isEmpty()) {
        pair = privateQueue.peek();
        isPrivate = true;
      } else if (!queueManager.keptLocations.isEmpty()) {
        pair = queueManager.keptLocations.peek();
      }

      if (pair == null) {
        // Break if neither private nor public locations are ready
        break;
      }

      // If the location has no reservation, it's a stub that needs hydration.
      // Move it to unkeptLocations so the deficit loop picks it up.
      if (pair.reservation() == null) {
        if (isPrivate) privateQueue.poll();
        else queueManager.keptLocations.poll();

        queueManager.unkeptLocations.offer(pair);
        continue;
      }

      ChunkSet chunkSet = (pair.reservation() != null) ? pair.reservation().getChunkSet() : null;
      if (chunkSet == null || chunkSet.complete() == null || !chunkSet.complete().isDone()) {
        // Location is still in the backlog or actively loading.
        break;
      }

      if (chunkSet.complete().isCompletedExceptionally()) {
        // Failsafe: Chunk failed to load.
        if (isPrivate) privateQueue.poll();
        else queueManager.keptLocations.poll();
        if (pair.reservation() != null) pair.reservation().close();
        continue;
      }

      TeleportData teleportData = RTP.getInstance().latestTeleportData.get(playerId);
      if (teleportData == null || teleportData.completed) {
        queueManager.playerQueue.poll(); // Discard invalid player
        RTP.getInstance().processingPlayers.remove(playerId);
        RTP.getInstance().queuedPlayers.remove(playerId);
        RTP.getInstance().invulnerablePlayers.remove(playerId);

        TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
        if (data != null && !data.completed && data.nextTask instanceof TeleportPipelineTask task) {
          task.setCancelled(true);
          if (task.coords() != null) {
            RTP.scheduler.runTask(
                    task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
          } else {
            RTP.scheduler.runTask(task);
          }
        }

        new RTPTeleportCancel(playerId).run();
        continue;
      }

      // 3. Both are ready. Poll them to finalize the pairing.
      if (isPrivate) privateQueue.poll();
      else queueManager.keptLocations.poll();
      // DatabaseAccessor.deleteCachedLocation keys on (regionName, worldName, x, y, z) only -
      // there is no per-player UUID column - so the same call serves both branches and a
      // single hoisted invocation cannot over-delete a private row from the public branch.
      // Cache the accessor in a local to avoid a second RTP.getInstance() and the TOCTOU
      // null-window between the null check and the deleteCachedLocation call.
      DatabaseAccessor databaseAccessor = RTP.getInstance().databaseAccessor;
      if (databaseAccessor != null) {
        databaseAccessor.deleteCachedLocation(name, pair);
      }
      queueManager.playerQueue.poll();

      teleportData.attempts = pair.attempts();
      teleportData.selectedCoords = pair.coords();

      RTPCommandSender sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);

      // Kept-queue entries generated asynchronously (e.g. FoliaLocationGenerator) are always
      // constructed with a null ChunkReservation, and the DB-rehydrated path never carries one.
      // Synthesize a reservation here so `TeleportPipelineTask.runTeleport` has live chunks to
      // consult for its unsafe-landing check, and so the chunks stay loaded long enough to
      // perform the teleport. Mirrors the synthesis in
      // TeleportPipelineTask.processGenerationResult() - REQ-RTP-S-002 is preserved because
      // runCleanup still closes whichever reservation ends up attached to the task.
      ChunkReservation reservationForTask = pair.reservation();
      if (reservationForTask == null) {
        RTPCoords pairCoords = pair.coords();
        RTPWorld<?> rtpWorld =
            (pairCoords != null) ? RTP.serverAccessor.getRTPWorld(pairCoords.worldName()) : null;
        if (rtpWorld == null) rtpWorld = getWorld();
        if (rtpWorld != null && pairCoords != null) {
          int radius = (int) ConfigCache.viewDistanceTeleport;
          int cx = pairCoords.x() >> 4;
          int cz = pairCoords.z() >> 4;
          java.util.List<CompletableFuture<Long>> chunks = new java.util.ArrayList<>();
          for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
              rtpWorld.recordChunkLoadOrigin("Region.synthesizeReservation");
              chunks.add(rtpWorld.getChunkAt(cx + dx, cz + dz));
            }
          }
          io.github.dailystruggle.rtp.common.tools.CfDiag.chunkSetRegion.increment();
          ChunkSet synthesized = new ChunkSet(rtpWorld, cx, cz, chunks, new CompletableFuture<>());
          reservationForTask = new ChunkReservation(synthesized, rtpWorld);
        }
      }

      TeleportPipelineTask pipelineTask = new TeleportPipelineTask(new GenerationContext(sender, player, null), this, pair.coords(), reservationForTask);
      teleportData.nextTask = pipelineTask;
      pipelineTask.setPhase(TeleportPipelineTask.Phase.LOAD);
      // Diagram 02 (WakePlayer): public/private queue produced a location and
      // the waiting player's pipeline is dispatched.
      RTP.log(Level.FINE,
          "[Region:" + name + "] WakePlayer uuid=" + playerId
              + " private=" + isPrivate
              + " coords=(" + pair.coords().x() + "," + pair.coords().y()
              + "," + pair.coords().z() + ")");
      RTP.scheduler.runTaskAsynchronously(pipelineTask);

      RTP.getInstance().latestTeleportData.put(playerId, teleportData);
      inFlightCalculations.incrementAndGet();
      for (int i = 0; i < onPlayerQueuePop.size(); i++) {
        onPlayerQueuePop.get(i).accept(this, playerId);
      }
    }

    // Broadcast queue-update to waiting players once per pulse if their position changed.
    {
      Iterator<UUID> iterator = queueManager.playerQueue.iterator();
      int i = 0;
      while (iterator.hasNext()) {
        UUID id = iterator.next();
        ++i;
        TeleportData data = RTP.getInstance().latestTeleportData.get(id);
        RTP.getInstance().processingPlayers.add(id);
        if (data == null) {
          data = new TeleportData();
          io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(data, "TeleportData-" + id.toString(), 120000L);
          data.completed = false;
          data.sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
          data.time = System.currentTimeMillis();
          data.delay = 0;
          data.targetRegion = this;
          RTP.getInstance().latestTeleportData.put(id, data);
        }
        long previousSpot = data.queueLocation;
        data.queueLocation = i;
        if (previousSpot != i) {
          RTP.serverAccessor.sendMessage(id, PlayerMessages.queueUpdate);
        }
      }
    }

    // Floor of 1 ns keeps the one-task-per-pulse progress guarantee on this async worker.
    miscPipeline.execute(Math.max(1L, availableTime - (System.nanoTime() - start)));

    if (tickUnhealthy) return;

    long cacheCap = settings.cacheCap();
    long totalCap = Math.max(cacheCap + activeCap, queueManager.playerQueue.size());

    if (!isScanningCache.compareAndSet(false, true)) {
      return;
    }

    try {
      // Compute cache deficit before scheduling observational task.
      deficit = totalCap - (cachePipeline.size() + queueManager.keptLocations.size() + queueManager.unkeptLocations.size() + inFlightCalculations.get() + pendingKeptPromotions.get());

      for (long i = 0; i < deficit; i++) {
        cachePipeline.add(new RegionCacheTask(this, availableTime - (System.nanoTime() - start)));
      }

      // Schedule at most one observational RegionCacheTask when visitor mode is enabled.
      if (isObservationalModeEnabled()) {
        cachePipeline.add(RegionCacheTask.observe(this));
      }

      cachePipeline.execute(Math.max(1L, availableTime - (System.nanoTime() - start)));
    } finally {
      isScanningCache.set(false);
    }
  }

  /**
   * Backlog cache pulse (ADR-028).
   * Refills unverified picks within the pulse budget, pre-filters region-file bins, drops rejected
   * entries and drains validated ones into cold without a chunk load. UNKNOWN entries (no on-disk
   * data) wait for the cold fill, which takes them one at a time and checks them with a chunk load.
   *
   * @param availableTime original pulse budget (ns)
   * @param startNanos    {@code System.nanoTime()} captured at pulse start
   */
  private void processBacklog(long availableTime, long startNanos) {
    BacklogLocationBuffer backlog = queueManager.backlogLocations;
    if (backlog == null) return;
    RTPWorld<?> world = getWorld();
    if (world == null) return;

    Shape<?> currentShape = this.shape;
    int verticalY;
    {
      // Placeholder Y clamped to adjustor min/world bounds; the real Y comes from the chunk check.
      VerticalAdjustor<?> v = getVert();
      if (v != null) {
        int wMin = world.getMinHeight();
        int wMax = world.getMaxHeight();
        int floor = v.minY();
        if (floor < wMin) floor = wMin;
        if (floor > wMax - 1) floor = wMax - 1;
        verticalY = floor;
      } else {
        verticalY = 64;
      }
    }
    String worldName = world.name();
    WorldBacklogBinIndex binIndex = RegionQueueManager.binIndexFor(worldName);

    // Hysteresis gate: refill runs in bursts until full, then pauses until below threshold.
    final int currentBacklogSize = backlog.size();
    final int backlogCapacity = backlog.capacity();
    if (backlogRefillActive) {
      if (currentBacklogSize >= backlogCapacity) {
        backlogRefillActive = false;
      }
    } else {
      double threshold = readBacklogRefillThreshold();
      if (currentBacklogSize < (long) Math.floor(threshold * backlogCapacity)) {
        backlogRefillActive = true;
      }
    }

    boolean heapUnderPressure =
        io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor.underPressure();
    if (currentShape != null && backlogRefillActive && !heapUnderPressure) {
      // Denied entries (possibly by another region's verifier on a shared bin) free capacity first.
      backlog.removeInvalidated();
      // Bounded rejection sampling: cap consecutive pregenPref rejections per pulse.
      final int maxConsecutivePregenRejects = Math.max(16, backlog.capacity());
      int consecutivePregenRejects = 0;
      boolean binnedStrategy = "BINNED_AMORTIZED".equalsIgnoreCase(readBacklogGenerationStrategy());

      // Unverified picks are shape math only (no chunk I/O, ~100 B each): fill toward the cap within
      // the pulse budget. Stop when the predicted next pick would cross the headroom line.
      final long deadline = refillDeadline(availableTime, startNanos, System.nanoTime());
      long pickStart = 0L;
      boolean more = true;
      while (more) {
        long now = System.nanoTime();
        if (pickStart != 0L) recordPickCost(now - pickStart);
        pickStart = now;
        boolean budgetLeft = backlog.size() < backlog.capacity()
            && now + backlogPacer.predictedCostNanos() < deadline;
        int[] sel = budgetLeft ? pickBacklogCell(currentShape, binnedStrategy) : NO_CELL;
        if (sel.length < 2) {
          more = false;
        } else if (pregenPrefRejects(world, sel[0], sel[1])) {
          more = ++consecutivePregenRejects < maxConsecutivePregenRejects;
        } else {
          consecutivePregenRejects = 0;
          RTPCoords coords = new RTPCoords(worldName, (sel[0] << 4) + 7, verticalY, (sel[1] << 4) + 7);
          more = stageUnverified(backlog, binIndex, coords);
        }
      }
    }

    // Verify region-file bins oldest-first: one classifyBatch per bin, results applied on this
    // thread; with a provider, batches run on AnvilIoPool and land on a later pulse.
    io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider provider = null;
    try {
      io.github.dailystruggle.rtp.api.hooks.RTPHooks hooks =
          io.github.dailystruggle.rtp.api.RTPAPI.hooks();
      if (hooks != null) provider = hooks.anvilPrefilter().current();
    } catch (Throwable t) {
      provider = null;
    }
    // Entries carry their owner's sink; this one only covers entries staged without one.
    binVerifier.pulse(world, backlog, binIndex, provider, this::onBacklogReject);

    // Rejected entries are dropped this pulse so their capacity goes to new picks.
    backlog.removeInvalidated();

    // Validated entries need no chunk load: cold takes them as-is up to cacheCap; promotion to hot
    // runs the rest of the check on the chunk it loads. UNKNOWN entries stay until the cold fill
    // takes them (RegionCacheTask -> PregenTask).
    drainValidatedToCold(backlog);
  }

  private static final int[] NO_CELL = new int[0];

  /** Next backlog chunk cell {@code {cx, cz}}; {@link #NO_CELL} when the shape yields none or throws. */
  private static int[] pickBacklogCell(Shape<?> currentShape, boolean binnedStrategy) {
    int[] sel;
    try {
      if (binnedStrategy
          && currentShape instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.AbstractDualLayerShape ds) {
        long cand = ds.selectL3Candidate();
        if (cand < 0) {
          sel = currentShape.select();
        } else {
          io.github.dailystruggle.rtp.api.world.MutableRTPCoords mc =
              new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(0, 0);
          ds.locationToXZ(cand, mc);
          sel = new int[] {mc.x, mc.z};
        }
      } else {
        // Default FLAT_STRIDE: uses identical selection method as uncached/live selection
        sel = currentShape.select();
      }
    } catch (Throwable t) {
      return NO_CELL;
    }
    return (sel == null || sel.length < 2) ? NO_CELL : sel;
  }

  /** Offers one unverified pick and indexes it by region file; false on capacity rejection. */
  private boolean stageUnverified(
      BacklogLocationBuffer backlog, WorldBacklogBinIndex binIndex, RTPCoords coords) {
    RTPLocation loc = new RTPLocation(coords, 0L);
    BacklogLocationBuffer.BacklogEntry entry = backlog.offerUnverified(loc);
    // If rejected due to capacity, attempt heuristic cleaning and retry once
    if (entry == null && backlog.cleanIfHeuristicMet() > 0) {
      entry = backlog.offerUnverified(loc);
    }
    if (entry == null) return false;
    entry.setOwnerOnReject(this::onBacklogReject);
    binIndex.insert(RegionFileCoord.of(coords), entry);
    return true;
  }

  /** Moves validated backlog entries into cold up to {@code cacheCap}; FIFO head or random per strategy. */
  private void drainValidatedToCold(BacklogLocationBuffer backlog) {
    long room = settings.cacheCap() - queueManager.unkeptLocations.size();
    if (room <= 0L) return;
    int n = (int) Math.min(Integer.MAX_VALUE, room);
    List<BacklogLocationBuffer.BacklogEntry> out =
        "BINNED_AMORTIZED".equalsIgnoreCase(readBacklogGenerationStrategy())
            ? backlog.pollRandomValidated(n)
            : backlog.pollContiguousValidatedHead(n);
    for (BacklogLocationBuffer.BacklogEntry e : out) {
      if (!queueManager.unkeptLocations.offer(e.location())) break;
      RtpOutcomeStats.GLOBAL.recordSuccess();
    }
  }

  /**
   * Refill deadline: {@code now} plus the remaining pulse budget less {@link #BACKLOG_HEADROOM}.
   * The pulse budget is {@code asyncAllottedTime} from performance.yml.
   */
  static long refillDeadline(long availableTime, long startNanos, long now) {
    long remaining = availableTime - (now - startNanos);
    if (remaining <= 0L) return now;
    return now + (long) (remaining * (1.0d - BACKLOG_HEADROOM));
  }

  /** Folds one pick's cost into the backlog pacer (7/8 decay, as in TimeBoundTaskPipe). */
  private void recordPickCost(long cost) {
    backlogPacer.recordDuration(cost);
  }

  /**
   * Rejection gate for pregeneratedPreference setting (S-005).
   * Returns true if chunk is ungenerated and rejected by probability draw.
   */
  private double readBacklogRefillThreshold() {
    try {
      @SuppressWarnings("unchecked")
      io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf =
          (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>)
              RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
      if (perf == null) return 0.5d;
      Object o = perf.getConfigValue(
          io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.backlogRefillThreshold,
          0.5d);
      double v = (o instanceof Number n) ? n.doubleValue() : Double.parseDouble(o.toString());
      if (v < 0.0d) return 0.0d;
      if (v > 1.0d) return 1.0d;
      return v;
    } catch (Throwable t) {
      return 0.5d;
    }
  }

  private String readBacklogGenerationStrategy() {
    try {
      @SuppressWarnings("unchecked")
      io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf =
          (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>)
              RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
      if (perf == null) return "FLAT_STRIDE";
      Object o = perf.getConfigValue(
          io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.backlogGenerationStrategy,
          "FLAT_STRIDE");
      return (o != null) ? o.toString() : "FLAT_STRIDE";
    } catch (Throwable t) {
      return "FLAT_STRIDE";
    }
  }

  private boolean pregenPrefRejects(RTPWorld<?> world, int cx, int cz) {
    if (world == null) return false;
    double pref;
    try {
      @SuppressWarnings("unchecked")
      io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf =
          (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>)
              RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
      if (perf == null) return false;
      Object o = perf.getConfigValue(
          io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.pregeneratedPreference,
          0.0d);
      pref = (o instanceof Number n) ? n.doubleValue() : Double.parseDouble(o.toString());
    } catch (Throwable t) {
      return false;
    }
    if (pref <= 0.0d) return false;
    boolean generated;
    try {
      generated = world.isChunkGenerated(cx, cz);
    } catch (Throwable t) {
      return false;
    }
    if (generated) return false;
    double clamped = Math.min(1.0d, pref);
    return clamped >= 1.0d
        || java.util.concurrent.ThreadLocalRandom.current().nextDouble() < clamped;
  }

  /**
   * Check if observational cache-fill mode is enabled.
   */
  private boolean isObservationalModeEnabled() {
    io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf;
    try {
      @SuppressWarnings("unchecked")
      io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> p =
          (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>)
              RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
      perf = p;
    } catch (Throwable t) {
      return false;
    }
    if (perf == null) return false;
    return Boolean.parseBoolean(
        perf.getConfigValue(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.visitorEnabled, false).toString());
  }

  /**
   * hasLocation - check if this region has a location ready for a player
   *
   * @param uuid player uuid
   * @return true if location is ready
   */
  public boolean hasLocation(UUID uuid) {
    boolean res = !queueManager.keptLocations.isEmpty();
    res |= (uuid != null) && (queueManager.perPlayerLocationQueue.containsKey(uuid));
    return res;
  }


  /**
   * getLocation - generate a location with biome requirements
   *
   * @param biomeNames set of biomes to filter by
   * @return location and number of attempts
   */
  public CompletableFuture<GenerationResult> getLocation(Set<String> biomeNames) {
    return RTP.serverAccessor.getLocationGenerator().generateLocation(this, new GenerationContext(null, null, biomeNames));
  }

  /** shutDown - save and clear data */
  public void shutDown() {
    binVerifier.close();
    Shape<?> currentShape = getShape();
    if (currentShape == null) return;

    RTPWorld world = getWorld();
    if (world == null) return;

    if (currentShape instanceof MemoryShape<?>) {
      ((MemoryShape<?>) currentShape).flushAndRebuild(((MemoryShape<?>) currentShape).spatialResolution());
      ((MemoryShape<?>) currentShape).save(this.name + "_" + cacheKey() + ".bin", world.name());
      ((MemoryShape<?>) currentShape).exportDebugJson(this.name, world.name());
    }

    cachePipeline.stop();
    cachePipeline.clear();

    queueManager.shutDown();
  }

  @Override
  public Region clone() {
    Region clone = (Region) super.clone();
    clone.settings = settings;
    clone.binVerifier = new BacklogBinVerifier();
    return clone;
  }

  /**
   * params - get current selection parameters
   *
   * @return map of parameters
   */
  public Map<String, String> params() {
    Map<String, String> res = new ConcurrentHashMap<>();
    res.put("world", settings.world() != null ? settings.world().name() : String.valueOf(configuredWorldName));
    if (settings.shape() == null) return res;
    res.put("shape", settings.shape().name);
    for (Map.Entry<? extends Enum<?>, ?> entry : settings.shape().getData().entrySet()) {
      res.put(entry.getKey().name(), entry.getValue().toString());
    }
    res.put("vert", settings.vert().name);
    for (Map.Entry<? extends Enum<?>, ?> entry : settings.vert().getData().entrySet()) {
      res.put(entry.getKey().name(), entry.getValue().toString());
    }
    res.put("worldBorderOverride", String.valueOf(settings.worldBorderOverride()));
    res.put("requirePermission", String.valueOf(settings.requirePermission()));
    res.put("cacheCap", String.valueOf(settings.cacheCap()));
    res.put("activeChunkCap", String.valueOf(settings.activeChunkCap()));
    res.put("price", String.valueOf(settings.price()));
    res.put("spatialResolution", String.valueOf(settings.spatialResolution()));
    res.put("override", settings.override());
    return res;
  }


  /**
   * fastQueue - get a location as fast as possible for a player
   *
   * @param id player uuid
   * @return future location and number of attempts
   */
  public CompletableFuture<RTPLocation> fastQueue(UUID id) {
    return queueManager.fastQueue(id);
  }

  /**
   * Open a personal coordinate bucket for {@code id} in this region (ADR-043).
   * Bucket-only opt-in: schedules a push-on-open pregen fill but does NOT
   * enroll {@code id} in the teleport waitlist.
   *
   * @param id player uuid
   */
  public void openPersonalQueue(UUID id) {
    queueManager.openPersonalQueue(id);
  }

  /**
   * Close the personal coordinate bucket for {@code id} in this region
   * (ADR-043). Returns banked coordinates to {@code unkeptLocations} and
   * clears the per-uuid push-on-open guard.
   *
   * @param id player uuid
   */
  public void closePersonalQueue(UUID id) {
    queueManager.closePersonalQueue(id);
  }

  /**
   * Enroll {@code id} on this region's teleport waitlist (ADR-043). The
   * caller must already have populated {@code latestTeleportData}; stale
   * entries are purged by {@link #execute(long)}.
   *
   * @param id player uuid
   */
  public void requestTeleport(UUID id) {
    queueManager.requestTeleport(id);
  }

  /**
   * getTotalQueueLength - get combined length of public and private queues
   *
   * @param uuid player uuid
   * @return combined queue length
   */
  public long getTotalQueueLength(UUID uuid) {
    return queueManager.getTotalQueueLength(uuid);
  }

  /**
   * getPublicQueueLength - get number of locations available to everyone
   *
   * @return public queue length
   */
  public long getPublicQueueLength() {
    return queueManager.getPublicQueueLength();
  }

  /**
   * getPersonalQueueLength - get number of locations reserved for a specific player
   *
   * @param uuid player uuid
   * @return personal queue length
   */
  public long getPersonalQueueLength(UUID uuid) {
    return queueManager.getPersonalQueueLength(uuid);
  }

  public Shape<?> getShape() {
    boolean wbo = settings.worldBorderOverride();
    RTPWorld<?> world = getWorld();
    Shape<?> current = this.shape;

    if (wbo && world != null) {
      Shape<?> worldShape = ((WorldBorder) RTP.serverAccessor.getWorldBorder(world.name())).getShape().get();
      if (!worldShape.equals(current)) {
        this.shape = worldShape;
        current = worldShape;
        settings = new RegionSettings(
            settings.name(),
            settings.world(),
            current,
            settings.vert(),
            settings.worldBorderOverride(),
            settings.requirePermission(),
            settings.cacheCap(),
            settings.backlogCacheCap(),
            settings.networkReserveSize(),
            settings.activeChunkCap(),
            settings.price(),
            settings.spatialResolution(),
            settings.override(),
            settings.detailedRegionInit()
        );
        for (Map.Entry<UUID, java.util.concurrent.ConcurrentLinkedQueue<RTPLocation>>
            entry : queueManager.perPlayerLocationQueue.entrySet()) {
          java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> value =
              entry.getValue();
          RTPLocation pair;
          while ((pair = value.poll()) != null) {
            if (pair.reservation() != null) pair.reservation().close();
          }
          value.clear();
        }
        queueManager.perPlayerLocationQueue.clear();
        RTPLocation pair;
        while ((pair = queueManager.keptLocations.poll()) != null) {
          if (pair.reservation() != null) pair.reservation().close();
        }
        queueManager.keptLocations.clear();
      }
    }
    return current;
  }

  public VerticalAdjustor<?> getVert() {
    return settings.vert();
  }

  public RTPWorld<?> getWorld() {
    return settings.world();
  }

  /**
   * Returns the shared per-candidate {@link CandidateValidator} for this region.
   *
   * <p>This is the single reusable "turn a column into a verified, standable, claim-safe location"
   * primitive (S-001/S-003/S-005): it chains this region's {@code VerticalAdjustor}, the shared
   * {@link SafetyScan} block-clearance verdict, and {@code GlobalRegionVerifiers}. Multi-target
   * consumers (the {@code SubspaceShape} group path and future addons) should use this rather than
   * re-deriving safety logic. Must be invoked off-tick (it blocks on async chunk loads).
   *
   * @return a region-backed candidate validator (never {@code null})
   */
  @SuppressWarnings("PMD.PreferNonLockingExecution") // ADR-094: lazy-init singleton validator for region
  public CandidateValidator candidateValidator() {
    CandidateValidator local = candidateValidator;
    if (local == null) {
      synchronized (this) {
        local = candidateValidator;
        if (local == null) {
          local = new RegionCandidateValidator(this);
          candidateValidator = local;
        }
      }
    }
    return local;
  }

  /**
   * Operator-configured display name for region, falling back to name.
   *
   * @return configured display name or region name
   */
  @SuppressWarnings("unchecked") // multiConfigParserMap keyed by RegionKeys.class guarantees the parser's E
  public String displayName() {
    try {
      MultiConfigParser<RegionKeys> regions =
          (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
      if (regions != null) {
        ConfigParser<RegionKeys> parser = regions.getParser(name);
        if (parser != null) {
          Object v = parser.getConfigValue(RegionKeys.displayName, null);
          if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
          }
        }
      }
    } catch (Throwable ignored) {
      // Cosmetic only - never let a label lookup break a caller.
    }
    return name;
  }

  private static boolean isOnTickThread() {
    try {
      return RTP.serverAccessor != null && RTP.serverAccessor.isPrimaryThread()
          && RTP.getInstance() != null && RTP.getInstance().miscSyncTasks != null;
    } catch (Throwable t) {
      return false;
    }
  }

  private static boolean isFoliaPlatform() {
    try {
      return "Folia".equals(RTP.serverAccessor.getPlatform());
    } catch (Throwable ignored) {
      return false;
    }
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Region)) return false;
    Region region = (Region) other;

    if (!java.util.Objects.equals(getShape(), region.getShape())) return false;
    if (!java.util.Objects.equals(getVert(), region.getVert())) return false;
    if (!java.util.Objects.equals(getWorld(), region.getWorld())) return false;
    return region.settings.worldBorderOverride() == settings.worldBorderOverride();
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(getShape(), getVert(), getWorld(), settings.worldBorderOverride());
  }

}
