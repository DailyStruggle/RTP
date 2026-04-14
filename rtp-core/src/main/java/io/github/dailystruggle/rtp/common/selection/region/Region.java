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
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
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
  private final AtomicBoolean isRefillingCache = new AtomicBoolean(false);

  public RegionQueueManager queueManager = new RegionQueueManager(this);
  public AtomicInteger inFlightCalculations =
      new AtomicInteger(0);

  public RTPTaskPipe cachePipeline;
  public RTPTaskPipe miscPipeline;
  protected RTPWorld<?> savedWorld = null;

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
      long[] progress = FillTask.loadProgress(name);
      if (progress != null) {
        long iter = progress[0];
        if (iter > 0 && iter < Double.valueOf(((MemoryShape<?>) this.shape).getRange()).longValue()) {
          FillTask task = new FillTask(this, iter);
          RTP.getInstance().fillTasks.put(name, task);
          RTP.scheduler.runTaskAsynchronously(task);
        }
      }
    }

    // Hydrate locations from database
    if (RTP.getInstance().databaseAccessor != null) {
      List<DatabaseAccessor.StoredLocation> storedLocations = RTP.getInstance().databaseAccessor.loadCachedLocations(name);
      hydrateCacheFromDatabase(storedLocations);
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

  /**
   * Hydrates the cache by asynchronously loading chunks and applying force-load tickets.
   * This ensures compliance with thread rules (especially on Folia) during startup.
   *
   * @param storedLocations the list of locations to hydrate from the database
   */
  public void hydrateCacheFromDatabase(List<DatabaseAccessor.StoredLocation> storedLocations) {
    RTPWorld<?> world = getWorld();
    if (world == null) return;
    for (DatabaseAccessor.StoredLocation stored : storedLocations) {
      int cx = stored.getX() >> 4;
      int cz = stored.getZ() >> 4;

      // 1. Asynchronously fetch the chunk to bring it back into server memory
      world.getChunkAtAsync(cx, cz).thenAccept(chunkSet -> {
        try {
          // 2. Recreate the reservation using your new RTPWorld wrapper
          ChunkReservation reservation = new ChunkReservation(chunkSet, world);

          // 3. Rebuild the RTPLocation object
          RTPCoords coords = new RTPCoords(stored.getWorldName(), stored.getX(), stored.getY(), stored.getZ());
          RTPLocation recoveredLoc = new RTPLocation(coords, stored.getAttempts(), reservation);

          // 4. Push it directly back into the LockFreeLocationBuffer (or player queue)
          boolean enqueued;
          UUID playerUuid = stored.getPlayerId();
          if (playerUuid == null) {
            enqueued = this.queueManager.keptLocations.offer(recoveredLoc);
          } else {
            // Push directly to player queue to avoid database re-save during hydration
            this.queueManager.perPlayerLocationQueue.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>()).add(recoveredLoc);
            enqueued = true;
          }

          if (enqueued) {
            // 5. Re-apply the secure Global Region force-load ticket!
            reservation.keep(true);
          } else {
            // If the cache is somehow full, drop the ticket and delete from DB
            reservation.close();
            RTP.getInstance().databaseAccessor.removeCachedLocation(stored.getId());
          }
        } catch (Exception e) {
          RTP.log(Level.WARNING, "Failed to hydrate cached location: " + e.getMessage(), e);
        }
      });
    }
  }


  /**
   * execute - localized task for pre-generating locations
   *
   * @param availableTime available time in nanoseconds
   */
  public void execute(long availableTime) {
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
      queueManager.playerQueue.poll();

      teleportData.attempts = pair.attempts();
      teleportData.selectedCoords = pair.coords();

      RTPCommandSender sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
      TeleportPipelineTask pipelineTask = new TeleportPipelineTask(new GenerationContext(sender, player, null), this, pair.coords(), pair.reservation());
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

    if (!isRefillingCache.compareAndSet(false, true)) {
//      System.out.println("[RTP-DEBUG] Region '" + name + "' ABORT 2: isRefillingCache lock is currently held by another thread.");
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
      isRefillingCache.set(false);
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
      ((MemoryShape<?>) shape).save(this.name + ".bin", world.name());
      ((MemoryShape<?>) shape).exportDebugJson(this.name, world.name());
    }

    cachePipeline.stop();
    cachePipeline.clear();

    RTPLocation pair;
    while ((pair = queueManager.keptLocations.poll()) != null) {
      if (pair.reservation() != null) pair.reservation().close();
    }
    queueManager.keptLocations.clear();

    while ((pair = queueManager.unkeptLocations.poll()) != null) {
      if (pair.reservation() != null) pair.reservation().close();
    }
    queueManager.unkeptLocations.clear();

    for (java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> queue :
        queueManager.perPlayerLocationQueue.values()) {
      while ((pair = queue.poll()) != null) {
        if (pair.reservation() != null) pair.reservation().close();
      }
    }
    queueManager.perPlayerLocationQueue.clear();
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
