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
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
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

  public RTPTaskPipe cachePipeline;
  public RTPTaskPipe miscPipeline;
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

  /**
   * Lazily-created, cached shared {@link CandidateValidator} for this region. The validator is
   * stateless apart from an immutable back-reference to this region (it re-reads world/vert/config
   * on each call, so it stays correct across {@code rebindWorld}/world-border swaps), so a single
   * instance is reused for every candidate rather than allocating one per {@link #candidateValidator()}
   * call - avoiding needless GC pressure on the per-candidate hot path.
   */
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
      this.shape = (Shape<?>) RTP.selectionAPI.shapeFactory.get("SQUARE");
      RTP.log(Level.WARNING, "Shape for region " + name + " was invalid. Falling back to SQUARE.");
      shapeRecovered = true;
    }
    if (vert == null) {
      vert = (VerticalAdjustor<?>) RTP.factoryMap.get(RTP.factoryNames.vert).get("LINEAR");
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
        if (iter > 0 && iter < Double.valueOf(((MemoryShape<?>) this.shape).getRange()).longValue()) {
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
        if (iter > 0 && iter < Double.valueOf(((MemoryShape<?>) this.shape).getRange()).longValue()) {
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
    }
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
        if (stored.getSeed() != 0L && stored.getSeed() != currentCacheKey) {
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
      if (rtpChunk == null) {
        // Transient null: returned to unkept queue for retry.
        RTP.log(Level.FINE,
            "[RTP][PROMOTE_DIAG] region=" + name + " cold=(" + coldLoc.coords().x() + ","
                + coldLoc.coords().y() + "," + coldLoc.coords().z() + ") chunk=(" + cx + "," + cz
                + ") transient: getCachedChunk returned null "
                + "(chunk not retained through the verify window; returned to unkept for retry).");
        return;
      }
      int surfaceY = rtpChunk.getSurfaceHeight(8, 8);
      String biome;
      try {
        biome = rtpChunk.getBiome(8, surfaceY, 8);
      } catch (Throwable t) {
        biome = "<biome-read-threw:" + t.getClass().getSimpleName() + ">";
      }
      boolean airBelow;
      boolean airAt;
      boolean airAbove;
      try {
        airBelow = rtpChunk.isAir(8, surfaceY - 1, 8);
        airAt = rtpChunk.isAir(8, surfaceY, 8);
        airAbove = rtpChunk.isAir(8, surfaceY + 1, 8);
      } catch (IllegalArgumentException yOutOfRange) {
        RTP.log(Level.FINE,
            "[RTP][PROMOTE_DIAG] region=" + name + " chunk=(" + cx + "," + cz
                + ") DROPPED vert.adjust=null; surfaceY=" + surfaceY
                + " is a void/no-surface column (isAir sample Y out of range: "
                + yOutOfRange.getMessage() + ").");
        return;
      } catch (Throwable t) {
        RTP.log(Level.INFO,
            "[RTP][PROMOTE_DIAG] region=" + name + " chunk=(" + cx + "," + cz
                + ") DROPPED vert.adjust=null but isAir read THREW "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
                + " (block reads are broken on this platform chunk).");
        return;
      }
      RTP.log(Level.FINE,
          "[RTP][PROMOTE_DIAG] region=" + name + " chunk=(" + cx + "," + cz
              + ") DROPPED vert.adjust=null"
              + " generated=" + rtpChunk.isGenerated()
              + " loaded=" + rtpChunk.isLoaded()
              + " anvilBacked=" + rtpChunk.isSelfContained()
              + " surfaceY=" + surfaceY
              + " biome=" + biome
              + " isAir[y-1/y/y+1]=" + airBelow + "/" + airAt + "/" + airAbove
              + " (live+generated+sensible surfaceY -> vertical adjustor issue;"
              + " anvilBacked or surfaceY==minHeight+all-air -> ungenerated/empty chunk).");
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
    long currentAvailable = availableTime;

//    System.out.println("[RTP-DEBUG] Region '" + name + "' execute() STARTED. Initial budget: " + availableTime + "ns");

    // ADR-028 - backlog cache pulse. Three inline steps (no producer/consumer
    // split), in order: refill the per-region buffer with shape-only picks
    // (S-005 safe - no chunk I/O), verify exactly one Anvil-region-file bin via
    // the bound AnvilPrefilter provider (cross-RTP-region amortization through
    // the world-shared bin index), and drain the contiguous-VALIDATED head into
    // unkeptLocations subject to its capacity. Skipped when backlogCacheCap=0
    // (lite default; backlogLocations is null).
    processBacklog(availableTime, start);

    // Region.java - inside execute()
    long activeCap = settings.activeChunkCap();
    long currentHot = queueManager.keptLocations.size();
    long deficit = activeCap - (currentHot + inFlightCalculations.get());

    // Diagram 02 (ExecuteRegion -> SpawnWorker): per-region budget enforcement.
    if (deficit > 0) {
      RTP.log(Level.FINE,
          "[Region:" + name + "] hot deficit=" + deficit + " (activeCap=" + activeCap
              + ", kept=" + currentHot + ", inFlight=" + inFlightCalculations.get() + ")");
    }

    // Heap-pressure gate: each cold->hot promotion below loads (and retains via
    // a chunk ticket) a chunk. On a small-heap server an unbounded fill grows
    // the retained set until the JVM thrashes GC and the server appears to hang
    // with no console error. Skip the fill while the heap is above
    // PerformanceKeys.maxHeapPercent; serving already-cached locations to
    // waiting players (the playerQueue drain below) continues regardless.
    boolean heapUnderPressure =
        io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor.underPressure();
    long fillDeficit = heapUnderPressure ? 0 : deficit;
    if (heapUnderPressure && deficit > 0) {
      RTP.log(Level.FINE,
          "[Region:" + name + "] hot-cache fill paused (heap pressure); deficit=" + deficit);
    }

    // Active shed: under heap pressure, release kept chunk tickets back to unkeptLocations.
    if (heapUnderPressure) {
      shedHotCacheUnderPressure();
    }

    for (int i = 0; i < fillDeficit; i++) {
      // Silent poll: this location is about to be re-offered to keptLocations under an
      // identical DB composite key (region:world:x:y:z). Firing the delete callback here
      // and the save callback on the kept offer races inside DatabaseAccessor's
      // writeQueue/deleteQueue and can net out to a row loss across restarts (symptom:
      // `/rtp info` showing cacheCap+activeCap-1 instead of the expected total). Keeping
      // the source poll silent preserves the DB row through the promotion; the kept
      // offer's save callback then acts as an idempotent upsert on the same key.
      RTPLocation coldLoc = queueManager.unkeptLocations.pollSilently();
      if (coldLoc == null) break;

      inFlightCalculations.incrementAndGet();
      RTP.log(Level.FINER,
          "[Region:" + name + "] SpawnWorker for cold (" + coldLoc.coords().x()
              + "," + coldLoc.coords().y() + "," + coldLoc.coords().z() + ")");
      int cx = coldLoc.coords().x() >> 4;
      int cz = coldLoc.coords().z() >> 4;

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
          Runnable verify = () -> {
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
                // Drop the now-unsafe / placeholder-Y location. pollSilently
                // above skipped the delete callback; fire offer+poll so the
                // DB row is purged.
                queueManager.unkeptLocations.offer(coldLoc);
                queueManager.unkeptLocations.poll();
                return;
              }

              ChunkReservation reservation = new ChunkReservation(chunkSet, getWorld());
              boolean added = queueManager.keptLocations.offer(
                      new RTPLocation(resolved, coldLoc.attempts(), reservation)
              );
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
            } finally {
              inFlightCalculations.decrementAndGet();
            }
          };
          if (isFoliaPlatform()) {
            RTP.scheduler.runTask(getWorld(), cx, cz, verify);
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
        if (data != null && !data.completed) {
          if (data.nextTask instanceof TeleportPipelineTask task) {
            task.setCancelled(true);
            if (task.coords() != null) {
              RTP.scheduler.runTask(
                      task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
            } else {
              RTP.scheduler.runTask(task);
            }
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
        if (data != null && !data.completed) {
          if (data.nextTask instanceof TeleportPipelineTask task) {
            task.setCancelled(true);
            if (task.coords() != null) {
              RTP.scheduler.runTask(
                      task.region().getWorld(), task.coords().x() >> 4, task.coords().z() >> 4, task);
            } else {
              RTP.scheduler.runTask(task);
            }
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

    miscPipeline.execute(availableTime - (System.nanoTime() - start));

    long cacheCap = settings.cacheCap();
    long totalCap = Math.max(cacheCap + activeCap, queueManager.playerQueue.size());

    if (!isScanningCache.compareAndSet(false, true)) {
      return;
    }

    try {
      // Compute cache deficit before scheduling observational task.
      deficit = totalCap - (cachePipeline.size() + queueManager.keptLocations.size() + queueManager.unkeptLocations.size() + inFlightCalculations.get());

      for (long i = 0; i < deficit; i++) {
        cachePipeline.add(new RegionCacheTask(this, availableTime - (System.nanoTime() - start)));
      }

      // Schedule at most one observational RegionCacheTask when visitor mode is enabled.
      if (isObservationalModeEnabled()) {
        cachePipeline.add(RegionCacheTask.observe(this, availableTime - (System.nanoTime() - start)));
      }

      cachePipeline.execute(availableTime - (System.nanoTime() - start));
    } finally {
      isScanningCache.set(false);
    }
  }

  /**
   * Backlog cache pulse (ADR-028).
   * Refills unverified buffer, validates one anvil bin, and drains validated head to cold.
   *
   * @param availableTime original pulse budget (ns)
   * @param startNanos    {@code System.nanoTime()} captured at pulse start
   */
  private void processBacklog(long availableTime, long startNanos) {
    BacklogLocationBuffer backlog = queueManager.backlogLocations;
    if (backlog == null) return;
    RTPWorld<?> world = getWorld();
    if (world == null) return;

    // Refill (shape-only, time-sliced).
    final long refillBudget = Math.max(0L, availableTime / 4L);
    Shape<?> currentShape = this.shape;
    int verticalY;
    {
      // Placeholder Y clamped to adjustor min/world bounds until cold->hot promotion verifies ground.
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
      // Clean invalidated entries if heuristic is met before refilling.
      backlog.cleanIfHeuristicMet();
      // Bounded rejection sampling: cap consecutive pregenPref rejections per pulse.
      final int maxConsecutivePregenRejects = Math.max(16, backlog.capacity());
      int consecutivePregenRejects = 0;
      while (backlog.size() < backlog.capacity()
          && (System.nanoTime() - startNanos) < refillBudget) {
        int[] sel;
        try {
          sel = currentShape.select();
        } catch (Throwable t) {
          break;
        }
        if (sel == null || sel.length < 2) break;
        if (pregenPrefRejects(world, sel[0], sel[1])) {
          if (++consecutivePregenRejects >= maxConsecutivePregenRejects) {
            break;
          }
          continue;
        }
        consecutivePregenRejects = 0;
        int blockX = (sel[0] << 4) + 8;
        int blockZ = (sel[1] << 4) + 8;
        RTPCoords coords = new RTPCoords(worldName, blockX, verticalY, blockZ);
        RTPLocation loc = new RTPLocation(coords, 0L);
        BacklogLocationBuffer.BacklogEntry entry = backlog.offerUnverified(loc);
        if (entry == null) {
          // If rejected due to capacity, attempt heuristic cleaning and retry once
          if (backlog.cleanIfHeuristicMet() > 0) {
            entry = backlog.offerUnverified(loc);
          }
          if (entry == null) break; // capacity rejection
        }
        binIndex.insert(RegionFileCoord.of(coords), entry);
      }
    }

    // Verify one bin per pulse from oldest unverified entry.
    BacklogLocationBuffer.BacklogEntry oldest = backlog.peekOldestUnverified();
    if (oldest != null) {
      RegionFileCoord binKey = RegionFileCoord.of(oldest.location().coords());
      List<BacklogLocationBuffer.BacklogEntry> snapshot = binIndex.snapshot(binKey);
      io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider provider = null;
      try {
        io.github.dailystruggle.rtp.api.hooks.RTPHooks hooks =
            io.github.dailystruggle.rtp.api.RTPAPI.hooks();
        if (hooks != null) provider = hooks.anvilPrefilter().current();
      } catch (Throwable t) {
        provider = null;
      }
      for (BacklogLocationBuffer.BacklogEntry e : snapshot) {
        if (e.validity() != BacklogLocationBuffer.Validity.UNVERIFIED) continue;
        BacklogLocationBuffer.Validity next;
        if (provider == null) {
          next = BacklogLocationBuffer.Validity.VALIDATED;
        } else {
          int cx = e.location().coords().x() >> 4;
          int cz = e.location().coords().z() >> 4;
          io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision d;
          try {
            d = provider.classify(world, cx, cz);
          } catch (Throwable t) {
            d = io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision.UNKNOWN;
          }
          if (d == null) d =
              io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision.UNKNOWN;
          switch (d) {
            case REJECT -> {
              next = BacklogLocationBuffer.Validity.INVALIDATED;
              RtpOutcomeStats.GLOBAL.recordFailure(LocationGenerator.FailTypes.biome);
              if (this.shape instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape ms) {
                long loc1D = ms.xzToLocation(e.location().coords().x(), e.location().coords().z());
                if (loc1D >= 0) {
                  ms.addBadChunk(loc1D, LocationGenerator.FailTypes.biome);
                }
              }
            }
            case ACCEPT, UNKNOWN -> next = BacklogLocationBuffer.Validity.VALIDATED;
            default -> next = BacklogLocationBuffer.Validity.VALIDATED;
          }
        }
        e.setValidity(next);
      }
      // Clean invalidated entries if heuristic is met to free backlog capacity.
      backlog.cleanIfHeuristicMet();
    }

    // Drain validated head into unkeptLocations up to cold capacity.
    long coldCap = settings.cacheCap();
    long coldFree = Math.max(0L, coldCap - queueManager.unkeptLocations.size());
    if (coldFree > 0L) {
      List<BacklogLocationBuffer.BacklogEntry> drained =
          backlog.pollContiguousValidatedHead((int) Math.min(coldFree, Integer.MAX_VALUE));
      for (BacklogLocationBuffer.BacklogEntry e : drained) {
        if (!queueManager.unkeptLocations.offer(e.location())) {
          break;
        }
        RtpOutcomeStats.GLOBAL.recordSuccess();
      }
    }
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
    Shape<?> shape = getShape();
    if (shape == null) return;

    RTPWorld world = getWorld();
    if (world == null) return;

    if (shape instanceof MemoryShape<?>) {
      ((MemoryShape<?>) shape).flushAndRebuild(((MemoryShape<?>) shape).spatialResolution());
      ((MemoryShape<?>) shape).save(this.name + "_" + cacheKey() + ".bin", world.name());
      ((MemoryShape<?>) shape).exportDebugJson(this.name, world.name());
    }

    cachePipeline.stop();
    cachePipeline.clear();

    queueManager.shutDown();
  }

  @Override
  public Region clone() {
    Region clone = (Region) super.clone();
    clone.settings = settings;
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
    Shape<?> shape = this.shape;

    if (wbo && world != null) {
      Shape<?> worldShape = ((WorldBorder) RTP.serverAccessor.getWorldBorder(world.name())).getShape().get();
      if (!worldShape.equals(shape)) {
        this.shape = worldShape;
        shape = worldShape;
        settings = new RegionSettings(
            settings.name(),
            settings.world(),
            shape,
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
    return shape;
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

}
