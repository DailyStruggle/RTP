package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
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
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask.ConfigCache;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
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
import org.jetbrains.annotations.Nullable;

public class Region extends FactoryValue<RegionKeys> {
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePush = new ArrayList<>();
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePop = new ArrayList<>();
  private final AtomicBoolean isScanningCache = new AtomicBoolean(false);

  public RegionQueueManager queueManager = new RegionQueueManager(this);
  public AtomicInteger inFlightCalculations =
      new AtomicInteger(0);

  public RTPTaskPipe cachePipeline;
  public RTPTaskPipe miscPipeline;
  protected RTPWorld<?> savedWorld = null;
  private long lastValidationTime = 0;

  private RegionSettings settings;
  public Shape<?> shape;

  public Region(String name, RegionSettings settings) {
    super(RegionKeys.class, name);
    this.name = name;
    this.settings = settings;
    this.set(RegionKeys.spatialResolution, settings.spatialResolution());
    this.cachePipeline = (RTPTaskPipe) RTP.serverAccessor.createCachePipe();
    this.miscPipeline = (RTPTaskPipe) RTP.serverAccessor.createTaskPipe();

    this.shape = settings.shape();
    if (this.shape == null) {
      this.shape = (Shape<?>) RTP.selectionAPI.shapeFactory.get("SQUARE");
      RTP.log(Level.WARNING, "Shape for region " + name + " was invalid. Falling back to SQUARE.");
      this.settings = new RegionSettings(
          settings.name(),
          settings.world(),
          this.shape,
          settings.vert(),
          settings.worldBorderOverride(),
          settings.requirePermission(),
          settings.cacheCap(),
          settings.activeChunkCap(),
          settings.price(),
          settings.spatialResolution(),
          settings.override(),
          settings.detailedRegionInit()
      );
    }

    if (this.shape != null && this.shape instanceof MemoryShape<?> memoryShape) memoryShape.spatialResolution = settings.spatialResolution();

    if (this.shape instanceof MemoryShape<?>) {
      long[] progress = ScanTask.loadProgress(name);
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

    // Hydrate locations from database
    if (RTP.getInstance().databaseAccessor != null) {
      List<DatabaseAccessor.StoredLocation> storedLocations = RTP.getInstance().databaseAccessor.loadCachedLocations(name);
      if (storedLocations.size() > 0) {
        hydrateCacheFromDatabase(storedLocations);
        ConfigParser<MessagesKeys> messages = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = messages.getConfigValue(MessagesKeys.locationLoaded, "").toString();
        if (!msg.isEmpty()) {
          msg = msg.replace("[amount]", String.valueOf(storedLocations.size()));
          msg = msg.replace("[region]", name);
          RTP.log(Level.INFO, msg);
        }
      }
    }
  }

  public RegionSettings getSettings() {
    return settings;
  }

  public void setSettings(RegionSettings settings) {
    this.settings = settings;
    this.shape = settings.shape();
    this.set(RegionKeys.spatialResolution, settings.spatialResolution());
    if (this.shape != null && this.shape instanceof MemoryShape<?> memoryShape) memoryShape.spatialResolution = settings.spatialResolution();
  }

  public void hydrateCacheFromDatabase(List<DatabaseAccessor.StoredLocation> storedLocations) {
    long currentSeed = getWorld().getSeed();
    // The database may return rows in insertion order. That order is meaningless for our
    // caching strategy — we want players landing in different areas on reboot, not a
    // deterministic rush to whichever location happened to be saved first. Shuffle in-place.
    // (A defensive copy-then-shuffle is avoided because the caller does not reuse the list.)
    if (storedLocations.size() > 1) {
      java.util.Collections.shuffle(storedLocations);
    }
    for (DatabaseAccessor.StoredLocation stored : storedLocations) {
      if (stored.getSeed() != 0L && stored.getSeed() != currentSeed) {
        // The world was wiped and repopulated with a different seed.
        // This location is no longer safe. Delete it and skip.
        RTP.getInstance().databaseAccessor.removeCachedLocation(stored.getId());
        continue;
      }

      RTPCoords coords = new RTPCoords(stored.getWorldName(), stored.getX(), stored.getY(), stored.getZ());
      // Reconstruct as an unkept location stub (null reservation).
      // Every row in the DB was validated before it was saved, and the seed check above
      // confirms the world terrain is unchanged, so the deficit loop just needs to re-reserve
      // the chunk — the consumer path in LocationGenerator already treats any queue-polled
      // candidate as validated (only freshly generated candidates run the full safety grid).
      RTPLocation recoveredLoc = new RTPLocation(coords, stored.getAttempts(), null);

      // Feed into the queues for the region execution loop to handle
      if (stored.getPlayerId() == null) {
        if (this.queueManager.unkeptLocations.size() < settings.cacheCap()) {
          this.queueManager.unkeptLocations.offer(recoveredLoc);
        } else {
          RTP.getInstance().databaseAccessor.removeCachedLocation(stored.getId());
        }
      } else {
        this.queueManager.perPlayerLocationQueue.computeIfAbsent(stored.getPlayerId(), k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(recoveredLoc);
      }
    }
  }


  /**
   * execute - localized task for pre-generating locations
   *
   * @param availableTime available time in nanoseconds
   */
  public void execute(long availableTime) {
    long now = System.currentTimeMillis();
    if (now - lastValidationTime > 60000) {
      this.queueManager.validateTickets(getWorld());
      lastValidationTime = now;
    }

    long start = System.nanoTime();
    long currentAvailable = availableTime;

//    System.out.println("[RTP-DEBUG] Region '" + name + "' execute() STARTED. Initial budget: " + availableTime + "ns");

    // Region.java - inside execute()
    long activeCap = settings.activeChunkCap();
    long currentHot = queueManager.keptLocations.size();
    long deficit = activeCap - (currentHot + inFlightCalculations.get());

    for (int i = 0; i < deficit; i++) {
      RTPLocation coldLoc = queueManager.unkeptLocations.poll();
      if (coldLoc == null) break;

      inFlightCalculations.incrementAndGet();
      int cx = coldLoc.coords().x() >> 4;
      int cz = coldLoc.coords().z() >> 4;

      getWorld().getChunkAtAsync(cx, cz).thenAccept(chunkSet -> {
        chunkSet.complete().whenComplete((success, throwable) -> {
          try {
            if (success != null && success) {
              ChunkReservation reservation = new ChunkReservation(chunkSet, getWorld());

              boolean added = queueManager.keptLocations.offer(
                      new RTPLocation(coldLoc.coords(), coldLoc.attempts(), reservation)
              );

              if (!added) {
                reservation.close();
                queueManager.unkeptLocations.offer(coldLoc);
              }
            } else {
              queueManager.unkeptLocations.offer(coldLoc);
            }
          } finally {
            inFlightCalculations.decrementAndGet();
          }
        });
      }).exceptionally(throwable -> {
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
      // DatabaseAccessor.deleteCachedLocation keys on (regionName, worldName, x, y, z) only —
      // there is no per-player UUID column — so the same call serves both branches and a
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
      // TeleportPipelineTask.processGenerationResult() — REQ-RTP-S-002 is preserved because
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
              chunks.add(rtpWorld.getChunkAt(cx + dx, cz + dz));
            }
          }
          ChunkSet synthesized = new ChunkSet(rtpWorld, cx, cz, chunks, new CompletableFuture<>());
          reservationForTask = new ChunkReservation(synthesized, rtpWorld);
        }
      }

      TeleportPipelineTask pipelineTask = new TeleportPipelineTask(new GenerationContext(sender, player, null), this, pair.coords(), reservationForTask);
      teleportData.nextTask = pipelineTask;
      pipelineTask.setPhase(TeleportPipelineTask.Phase.LOAD);
      RTP.scheduler.runTaskAsynchronously(pipelineTask);

      RTP.getInstance().latestTeleportData.put(playerId, teleportData);
      inFlightCalculations.incrementAndGet();
      for (int i = 0; i < onPlayerQueuePop.size(); i++) {
        onPlayerQueuePop.get(i).accept(this, playerId);
      }

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
          data.delay = sender.delay();
          data.targetRegion = this;
          data.originalCoords =
                  new RTPCoords(
                          player.getLocation().world().name(),
                          player.getLocation().x(),
                          player.getLocation().y(),
                          player.getLocation().z());
          RTP.getInstance().latestTeleportData.put(id, data);
        }
        data.queueLocation = i;
        RTP.serverAccessor.sendMessage(id, MessagesKeys.queueUpdate);
      }
    }

    miscPipeline.execute(availableTime - (System.nanoTime() - start));

//    long totalCap = Math.max(settings.cacheCap(), queueManager.playerQueue.size());
    long cacheCap = settings.cacheCap();
    long totalCap = Math.max(cacheCap + activeCap, queueManager.playerQueue.size());

    if (!isScanningCache.compareAndSet(false, true)) {
//      System.out.println("[RTP-DEBUG] Region '" + name + "' ABORT 2: isScanningCache lock is currently held by another thread.");
      return;
    }

    try {
      deficit = totalCap - (cachePipeline.size() + queueManager.keptLocations.size() + queueManager.unkeptLocations.size() + inFlightCalculations.get());
//      System.out.println("[RTP-DEBUG] Region '" + name + "' caching phase. Deficit: " + deficit + " | inFlight: " + inFlightCalculations.get() + " | cachePipeSize: " + cachePipeline.size());

      for (long i = 0; i < deficit; i++) {
        cachePipeline.add(new RegionCacheTask(this, availableTime - (System.nanoTime() - start)));
      }

//      System.out.println("[RTP-DEBUG] Region '" + name + "' executing cachePipeline with budget: " + currentAvailable + "ns");
      cachePipeline.execute(availableTime - (System.nanoTime() - start));
    } finally {
      isScanningCache.set(false);
    }
  }

  /**
   * hasLocation - check if this region has a location ready for a player
   *
   * @param uuid player uuid
   * @return true if location is ready
   */
  public boolean hasLocation(@Nullable UUID uuid) {
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
  public CompletableFuture<GenerationResult> getLocation(@Nullable Set<String> biomeNames) {
    return RTP.serverAccessor.getLocationGenerator().generateLocation(this, new GenerationContext(null, null, biomeNames));
  }

  /** shutDown - save and clear data */
  public void shutDown() {
    Shape<?> shape = getShape();
    if (shape == null) return;

    RTPWorld world = getWorld();
    if (world == null) return;

    if (shape instanceof MemoryShape<?>) {
      ((MemoryShape<?>) shape).flushAndRebuild(((MemoryShape<?>) shape).spatialResolution);
      ((MemoryShape<?>) shape).save(this.name + "_" + world.getSeed() + ".bin", world.name());
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
    res.put("world", settings.world().name());
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
   * queue - add a player to the queue for this region
   *
   * @param id player uuid
   */
  public void queue(UUID id) {
    queueManager.queue(id);
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

    if (wbo) {
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

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Region)) return false;
    Region region = (Region) other;

    if (!getShape().equals(region.getShape())) return false;
    if (!getVert().equals(region.getVert())) return false;
    if (!getWorld().equals(region.getWorld())) return false;
    return region.settings.worldBorderOverride() == settings.worldBorderOverride();
  }

  public static int maxBiomeChecksPerGen = 100;
}
