package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.GenerationResult;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.SupportLogger;
import io.github.dailystruggle.rtp.common.selection.region.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class TeleportPipelineTask extends RTPRunnable {
  public enum Phase {
    SETUP,
    LOAD,
    TELEPORT,
    CLEANUP
  }

  private final java.util.concurrent.atomic.AtomicBoolean handledInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
  public static final List<Consumer<TeleportPipelineTask>> setupPreActions = new ArrayList<>();
  public static final List<BiConsumer<TeleportPipelineTask, Boolean>> setupPostActions =
      new ArrayList<>();
  public static final List<Consumer<TeleportPipelineTask>> loadPreActions = new ArrayList<>();
  public static final List<Consumer<TeleportPipelineTask>> loadPostActions = new ArrayList<>();
  public static final List<Consumer<TeleportPipelineTask>> teleportPreActions = new ArrayList<>();
  public static final List<Consumer<TeleportPipelineTask>> teleportPostActions = new ArrayList<>();
  public static final List<Consumer<TeleportPipelineTask>> cleanupPreActions = new ArrayList<>();
  public static final List<Consumer<TeleportPipelineTask>> cleanupPostActions = new ArrayList<>();

  public static class ConfigCache {
    public static String unsafe = "";
    public static String teleportMessage = "";
    public static long viewDistanceTeleport = 0;

    static {
      RTP.configs.onReload(ConfigCache::reload);
      if (!RTP.reloading.get()) reload();
    }

    public static void reload() {
      ConfigParser<MessagesKeys> langParser =
          (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      unsafe = langParser.getConfigValue(MessagesKeys.unsafe, "").toString();
      teleportMessage = langParser.getConfigValue(MessagesKeys.teleportMessage, "").toString();

      ConfigParser<PerformanceKeys> perf =
          (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
      viewDistanceTeleport = perf.getNumber(PerformanceKeys.viewDistanceTeleport, 0L).longValue();
    }
  }

  static {
    setupPreActions.add(task -> task.isRunning.set(true));
    setupPostActions.add((task, success) -> { if (!success) task.isRunning.set(false); });
    loadPreActions.add(task -> task.isRunning.set(true));
    teleportPostActions.add(task -> task.isRunning.set(false));
    cleanupPostActions.add(task -> task.isRunning.set(false));
  }

  private Phase currentPhase = Phase.SETUP;
  private final GenerationContext context;

  private Region region;
  private RTPCoords coords;
  private TeleportData teleportData;
  private ChunkReservation reservation;

  public TeleportPipelineTask(GenerationContext context) {
    this.context = context;
  }

  public TeleportPipelineTask(GenerationContext context, Region region) {
    this.context = context;
    this.region = region;
  }

  public TeleportPipelineTask(GenerationContext context, Region region, RTPCoords preSelectedCoords) {
    this.context = context;
    this.region = region;
    this.coords = preSelectedCoords;
    this.currentPhase = Phase.LOAD;
  }

  public TeleportPipelineTask(GenerationContext context, Region region, RTPCoords preSelectedCoords, ChunkReservation reservation) {
    this.context = context;
    this.region = region;
    this.coords = preSelectedCoords;
    this.reservation = reservation;
    this.currentPhase = Phase.LOAD;
  }

  public Phase getPhase() {
    return this.currentPhase;
  }

  public void setPhase(Phase phase) {
    this.currentPhase = phase;
  }

  @Override
  public void run() {
    RTP.serverAccessor.sendMessage(context.player().uuid(),"RUN");

    if (isCancelled()) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }
    switch (currentPhase) {
      case SETUP:
        runSetup();
        break;
      case LOAD:
        runLoad();
        break;
      case TELEPORT:
        runTeleport();
        break;
      case CLEANUP:
        runCleanup();
        break;
    }
  }

  private void runSetup() {
    RTP.serverAccessor.sendMessage(context.player().uuid(),"SETUP");
    setupPreActions.forEach(consumer -> consumer.accept(this));
    if (isCancelled()) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }

    RTPPlayer player = context.player();
    if (player == null) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }
    UUID playerId = player.uuid();

    if (region == null) {
      region = RTP.selectionAPI.getRegion(player);
    }

    boolean success = false;
    try {
      teleportData = RTP.getInstance().latestTeleportData.get(playerId);
      if (teleportData == null) {
        teleportData = new TeleportData();
        io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(teleportData, "TeleportData-" + playerId.toString(), 120000L);
        teleportData.sender = (context.sender() != null) ? context.sender() : player;
        teleportData.completed = false;
        teleportData.time = System.currentTimeMillis();
        teleportData.delay = teleportData.sender.delay();
        teleportData.targetRegion = region;
        teleportData.originalCoords =
                new RTPCoords(
                        player.getLocation().world().name(),
                        player.getLocation().x(),
                        player.getLocation().y(),
                        player.getLocation().z());
        RTP.getInstance().latestTeleportData.put(playerId, teleportData);
      }
      teleportData.nextTask = this;
      teleportData.targetRegion = region;

      GenerationResult res = region.getLocation(context);
      if (res == null) {
        // If the player was successfully queued, gracefully halt this thread.
        // Region.execute() will spawn a new pipeline task when a location is ready.
        if (RTP.getInstance().processingPlayers.contains(playerId)) {
          if (region != null && handledInFlight.compareAndSet(false, true)) {
            region.inFlightCalculations.getAndDecrement();
          }
          return;
        }

        // Otherwise, generation failed entirely (e.g., custom biome search failed).
        currentPhase = Phase.CLEANUP;
        runCleanup();
        return;
      }

      coords = res.coords();
      long attempts = res.attempts();

      if (coords == null) {
        teleportData.attempts = attempts;
        RTP.serverAccessor.sendMessage(sender().uuid(), player.uuid(), ConfigCache.unsafe);
        RTPTeleportCancel.refund(player.uuid());
        currentPhase = Phase.CLEANUP;
        runCleanup();
        return;
      }

      teleportData.selectedCoords = coords;
      teleportData.attempts = attempts;
      success = true;
    } catch (Exception e) {
      SupportLogger.logException(Level.WARNING, "Error in runSetup", e);
      new RTPTeleportCancel(player.uuid()).run();
      currentPhase = Phase.CLEANUP;
      runCleanup();
    } finally {
      boolean finalSuccess = success;
      setupPostActions.forEach(consumer -> consumer.accept(this, finalSuccess));
      if (success) {
        currentPhase = Phase.LOAD;

        long radius2 = ConfigCache.viewDistanceTeleport;
        long max = (radius2 * radius2 * 4) + (4 * radius2) + 1;
        ChunkSet chunkSet = this.region.chunkManager.chunks(coords, radius2);
        if (max > chunkSet.chunks().size()) {
          RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
          if (world == null) world = region.getWorld();
          RTP.serverAccessor.getChunkManager().keep(chunkSet, false, world);
          chunkSet = region.chunkManager.chunks(coords, radius2);
          RTP.serverAccessor.getChunkManager().keep(chunkSet, true, world);
        }

        if (chunkSet.complete().isDone()) {
          this.run();
        } else {
          RTP.scheduler.runTaskAsynchronously(this);
        }
      }
    }
  }

  private void runLoad() {
    RTP.serverAccessor.sendMessage(context.player().uuid(),"LOAD");
    try {
      loadPreActions.forEach(consumer -> consumer.accept(this));
      if (isCancelled()) {
        currentPhase = Phase.CLEANUP;
        runCleanup();
        return;
      }

      if (teleportData == null) {
        RTPPlayer player = context.player();
        if (player == null) {
          currentPhase = Phase.CLEANUP;
          runCleanup();
          return;
        }
        UUID playerId = player.uuid();
        teleportData = RTP.getInstance().latestTeleportData.get(playerId);
        if (teleportData == null) {
          // ... [Standard teleport data initialization]
          teleportData = new TeleportData();
          io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(teleportData, "TeleportData-" + playerId.toString(), 120000L);
          teleportData.sender = (context.sender() != null) ? context.sender() : player;
          teleportData.completed = false;
          teleportData.time = System.currentTimeMillis();
          teleportData.delay = teleportData.sender.delay();
          teleportData.targetRegion = region;
          teleportData.originalCoords =
                  new RTPCoords(
                          player.getLocation().world().name(),
                          player.getLocation().x(),
                          player.getLocation().y(),
                          player.getLocation().z());
          RTP.getInstance().latestTeleportData.put(playerId, teleportData);
        }
        teleportData.nextTask = this;
        teleportData.targetRegion = region;
        teleportData.selectedCoords = coords;
      }

      if (coords == null && teleportData.selectedCoords != null) {
        coords = teleportData.selectedCoords;
      }

      if (region == null || coords == null) {
        currentPhase = Phase.CLEANUP;
        runCleanup();
        return;
      }

      ChunkSet chunkSet = this.region.chunkManager.getChunkSet(coords);
      if (chunkSet == null) {
        // Fallback to coordinates-based lookup at the region level if available.
        // RTPChunkManager no longer supports direct coordinate-based lookups.
        chunkSet = null;
      }
      if (chunkSet == null) {
        currentPhase = Phase.TELEPORT;
        RTP.scheduler.runTask(this);
        return;
      }

      RTP.serverAccessor.sendMessage(player().uuid(), MessagesKeys.chunkLoading);

      RTP.serverAccessor.getChunkManager().whenComplete(chunkSet, aBoolean -> {
                if (isCancelled()) {
                  currentPhase = Phase.CLEANUP;
                  this.run();
                  return;
                }

                if (aBoolean == null || !aBoolean) {
                  currentPhase = Phase.CLEANUP;
                  this.run();
                  return;
                }

                long start = System.currentTimeMillis();
                long lastTime = teleportData.time;
                long delay = sender().delay();
                long dT = (start - lastTime);
                long remainingTime = delay - dT;
                long toTicks = remainingTime / 50;
                if (toTicks < 0) toTicks = 0;

                loadPostActions.forEach(consumer -> consumer.accept(this));
                currentPhase = Phase.TELEPORT;
                if (toTicks <= 0 && RTP.serverAccessor.isPrimaryThread()) {
                  this.run();
                } else {
                  RTP.scheduler.scheduleTeleport(player(), this, toTicks);
                }
              });
    } catch (Exception e) {
      SupportLogger.logException(Level.SEVERE, "Error in runLoad", e);
      currentPhase = Phase.CLEANUP;
      runCleanup();
    }
  }

  private void runTeleport() {
    RTP.serverAccessor.sendMessage(context.player().uuid(),"TELEPORT");
    teleportPreActions.forEach(consumer -> consumer.accept(this));
    if (isCancelled()) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }

    RTPPlayer player = player();
    if (player == null) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }
    UUID playerId = player.uuid();

    try (ChunkReservation reservation = this.reservation) {
      if (reservation != null) {
        reservation.transferOwnership();
      }

      RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
      if (world == null) world = region.getWorld();
      RTPLocation location = new RTPLocation(world, coords.x(), coords.y(), coords.z());
      location.world().platform(location);
      RTP.getInstance().invulnerablePlayers.put(playerId, System.currentTimeMillis());

      teleportData.completed = true;
      long processingTime = System.currentTimeMillis() - teleportData.time;
      teleportData.processingTime = processingTime;
      RTP.getInstance().processingPlayers.remove(playerId);

      CompletableFuture<Boolean> setLocation = player.setLocation(location);
      RTP.getInstance().databaseAccessor.cacheValue(teleportData);

      setLocation.whenComplete(
          (aBoolean, throwable) -> {
            if (aBoolean != null && aBoolean) {
              RTP.serverAccessor.sendMessage(playerId, ConfigCache.teleportMessage);
            } else {
              RTP.serverAccessor.sendMessage(playerId, ConfigCache.unsafe);
            }

            teleportPostActions.forEach(consumer -> consumer.accept(this));

            currentPhase = Phase.CLEANUP;
            // Execute inline to satisfy synchronous test assertions and close the race condition
            this.run();
          });

    } catch (Exception e) {
      SupportLogger.logException(Level.SEVERE, "Error in runTeleport", e);
      currentPhase = Phase.CLEANUP;
      runCleanup();
    }
  }

  private void runCleanup() {
    RTP.serverAccessor.sendMessage(context.player().uuid(),"CLEANUP");
    cleanupPreActions.forEach(consumer -> consumer.accept(this));
    try {
      // Explicitly untrack the data to prevent false positive leak alerts
      if (this.teleportData != null) {
        io.github.dailystruggle.rtp.common.tools.MemoryTracker.untrack(this.teleportData);
      }

      // Add untracking for the pipeline task itself
      if (this.trackingId != null) {
        io.github.dailystruggle.rtp.common.tools.MemoryTracker.untrack(this.trackingId);
      }

      if (player() != null) {
        UUID pid = player().uuid();
        TeleportData data = RTP.getInstance().latestTeleportData.get(pid);

        // Strict reference verification prevents overwriting subsequent requests
        if (data == this.teleportData && !data.completed) {
          RTP.getInstance().latestTeleportData.remove(pid);
        }
        RTP.getInstance().invulnerablePlayers.remove(pid);
      }
      if (region == null || coords == null) return;

      // Absolute world resolution prevents multi-dimension cross-leakage
      RTPWorld<?> rtpWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
      if (rtpWorld == null) rtpWorld = region.getWorld();

      if (reservation != null) {
        reservation.close();
      }
      ChunkSet chunkSet = region.chunkManager.getChunkSet(coords);
      if (chunkSet != null) {
        RTP.serverAccessor.getChunkManager().keep(chunkSet, false, rtpWorld);
      }
      region.chunkManager.removeChunks(coords);
      cleanupPostActions.forEach(consumer -> consumer.accept(this));
    } finally {
      // Atomic gating prevents double-decrements on concurrent thread crashes
      if (region != null && handledInFlight.compareAndSet(false, true)) {
        region.inFlightCalculations.getAndDecrement();
      }
    }
  }

  public RTPPlayer player() {
    return context.player();
  }

  public RTPCommandSender sender() {
    return context.sender();
  }

  public Region region() {
    return region;
  }

  public RTPCoords coords() {
    return coords;
  }
}
