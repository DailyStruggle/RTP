package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
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
      Configs.onReload(ConfigCache::reload);
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
  private ChunkSet chunkSet;

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
    RTP.log(Level.FINER, "[PIPELINE_TRACE] dispatch phase=" + currentPhase
            + " cancelled=" + isCancelled()
            + " thread=" + Thread.currentThread().getName());
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

      RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] TeleportPipelineTask.runSetup invoking getLocation playerId=" + player.uuid() + " region=" + (region != null ? region.name : "null") + " thread=" + Thread.currentThread().getName());
      CompletableFuture<GenerationResult> locationFuture = RTP.serverAccessor.getLocationGenerator().getLocation(region, context);
      if (locationFuture.isDone()) {
        processGenerationResult(locationFuture.join());
      } else {
        locationFuture.thenAccept(this::processGenerationResult).exceptionally(e -> {
          SupportLogger.logException(Level.WARNING, "Error in getLocationFuture", e);
          currentPhase = Phase.CLEANUP;
          runCleanup();
          return null;
        });
      }
    } catch (Exception e) {
      SupportLogger.logException(Level.WARNING, "Error in runSetup", e);
      new RTPTeleportCancel(player.uuid()).run();
      currentPhase = Phase.CLEANUP;
      runCleanup();
    }
  }

  private void processGenerationResult(GenerationResult res) {
    try {
      RTPPlayer player = context.player();
      if (player == null) {
        currentPhase = Phase.CLEANUP;
        runCleanup();
        return;
      }
      UUID playerId = player.uuid();
      RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] TeleportPipelineTask.processGenerationResult ENTER playerId=" + playerId
              + " resNull=" + (res == null)
              + " inProcessing=" + RTP.getInstance().processingPlayers.contains(playerId)
              + " inQueuedPlayers=" + RTP.getInstance().queuedPlayers.contains(playerId)
              + " thread=" + Thread.currentThread().getName());

      boolean success = false;
      try {
        if (res == null) {
          // If the player was successfully queued, gracefully halt this thread.
          // Region.execute() will spawn a new pipeline task when a location is ready.
          if (RTP.getInstance().processingPlayers.contains(playerId)) {
            RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] TeleportPipelineTask silent-halt (processingPlayers.contains==true) playerId=" + playerId);
            if (region != null && handledInFlight.compareAndSet(false, true)) {
              region.inFlightCalculations.getAndDecrement();
            }
            return;
          }

          // Otherwise, generation failed entirely (e.g., custom biome search failed).
          RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] TeleportPipelineTask null-result CLEANUP playerId=" + playerId);
          currentPhase = Phase.CLEANUP;
          runCleanup();
          return;
        }

        coords = res.coords();
        long attempts = res.attempts();
        this.reservation = res.reservation();

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

          RTPWorld<?> rtpWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
          if (rtpWorld == null) rtpWorld = region.getWorld();

          if (this.reservation == null) {
            long radius2 = ConfigCache.viewDistanceTeleport;
            int cx = coords.x() >> 4;
            int cz = coords.z() >> 4;
            int radius = (int) radius2;
            List<CompletableFuture<Long>> chunks = new ArrayList<>();
            for (int x = -radius; x <= radius; x++) {
              for (int z = -radius; z <= radius; z++) {
                chunks.add(rtpWorld.getChunkAt(cx + x, cz + z));
              }
            }
            this.chunkSet = new ChunkSet(rtpWorld, cx, cz, chunks, new CompletableFuture<>());
            this.reservation = new ChunkReservation(chunkSet, rtpWorld);
          } else {
            this.chunkSet = this.reservation.getChunkSet();
          }

          if (chunkSet.complete().isDone()) {
            RTP.scheduler.runTask(rtpWorld, coords.x() >> 4, coords.z() >> 4, this);
          } else {
            RTP.scheduler.runTaskAsynchronously(this);
          }
        }
      }
    } catch (Exception e) {
      SupportLogger.logException(Level.SEVERE, "Error in processGenerationResult", e);
      new RTPTeleportCancel(player().uuid()).run();
      currentPhase = Phase.CLEANUP;
      runCleanup();
      RTP.getInstance().processingPlayers.remove(player().uuid());
    }
  }

  private void runLoad() {
    try {
      RTP.log(Level.FINE, "[PIPELINE_TRACE] runLoad ENTER playerId="
              + (context != null && context.player() != null ? context.player().uuid() : "null")
              + " coordsNull=" + (coords == null)
              + " reservationNull=" + (reservation == null)
              + " thread=" + Thread.currentThread().getName());
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

      if (chunkSet == null && reservation != null) {
        chunkSet = reservation.getChunkSet();
      }
      // Fallback to coordinates-based lookup at the region level if available.
      // RTPChunkManager no longer supports direct coordinate-based lookups.
      if (chunkSet == null) {
        currentPhase = Phase.TELEPORT;
        RTP.scheduler.runTask(this);
        return;
      }

      if(!chunkSet.complete().isDone()) RTP.serverAccessor.sendMessage(player().uuid(), MessagesKeys.chunkLoading);

      RTP.log(Level.FINER, "[PIPELINE_TRACE] runLoad awaiting chunkSet alreadyDone="
              + chunkSet.complete().isDone() + " cx=" + (coords.x() >> 4) + " cz=" + (coords.z() >> 4));

      chunkSet.complete().thenAccept(aBoolean -> {
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

                RTP.log(Level.FINER, "[PIPELINE_TRACE] runLoad chunkSet completed result=" + aBoolean);

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
    RTP.log(Level.FINE, "[PIPELINE_TRACE] runTeleport ENTER playerId="
            + (context != null && context.player() != null ? context.player().uuid() : "null")
            + " coords=" + (coords != null ? (coords.worldName() + "@" + coords.x() + "," + coords.y() + "," + coords.z()) : "null")
            + " thread=" + Thread.currentThread().getName());
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

    try {
      RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
      if (world == null) world = region.getWorld();
      RTPLocation location = new RTPLocation(world, coords.x(), coords.y(), coords.z());
      location.setReservation(reservation);
      boolean buildPlatform = shouldBuildPlatform(world, coords);
      RTP.log(Level.FINER, "[PIPELINE_TRACE] runTeleport platformDecision=" + buildPlatform
              + " worldNull=" + (world == null));
      if (buildPlatform) {
        location.world().platform(location);
      }
      RTP.getInstance().invulnerablePlayers.put(playerId, System.currentTimeMillis());

      teleportData.completed = true;
      teleportData.processingTime = System.currentTimeMillis() - teleportData.time;
      RTP.getInstance().processingPlayers.remove(playerId);

      CompletableFuture<Boolean> setLocation = player.setLocation(location);
      RTP.log(Level.FINE, "[PIPELINE_TRACE] runTeleport setLocation dispatched playerId=" + playerId
              + " attempts=" + teleportData.attempts
              + " processingTime=" + teleportData.processingTime + "ms");
      RTP.getInstance().databaseAccessor.cacheValue(teleportData);

      setLocation.whenComplete(
          (aBoolean, throwable) -> {
            try {
              RTP.log(Level.FINER, "[PIPELINE_TRACE] runTeleport setLocation completed playerId=" + playerId
                      + " success=" + aBoolean
                      + " throwable=" + (throwable != null ? throwable.getClass().getSimpleName() : "none"));
              if (reservation != null) {
                reservation.close();
                this.reservation = null;
              }
              if (throwable != null) {
                SupportLogger.logException(Level.SEVERE, "Error in setLocation callback", throwable);
              }
              if (aBoolean != null && aBoolean) {
                RTP.serverAccessor.sendMessage(playerId, ConfigCache.teleportMessage);
              } else {
                RTP.serverAccessor.sendMessage(playerId, ConfigCache.unsafe);
              }

              // Null-safe fetch to prevent silent CompletableFuture crashes in test environments
              long duration = 0L;
              ConfigParser<SafetyKeys> safetyParser = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
              if (safetyParser != null) {
                Number num = safetyParser.getNumber(SafetyKeys.invulnerabilityTime, 0L);
                if (num != null) {
                  duration = num.longValue();
                }
              }

              if (duration > 0) {
                RTP.getInstance().invulnerablePlayers.put(playerId, System.currentTimeMillis());
                RTP.scheduler.runTaskLater(() -> {
                  RTP.getInstance().invulnerablePlayers.remove(playerId);
                }, duration * 20L);
              } else {
                RTP.getInstance().invulnerablePlayers.remove(playerId);
              }

              teleportPostActions.forEach(consumer -> {
                try {
                  consumer.accept(this);
                } catch (Exception e) {
                  SupportLogger.logException(Level.WARNING, "Error in teleportPostAction", e);
                }
              });

            } catch (Exception e) {
              SupportLogger.logException(Level.SEVERE, "Fatal error in teleport callback", e);
            } finally {
              currentPhase = Phase.CLEANUP;
              this.run();
            }
          });

    } catch (Exception e) {
      SupportLogger.logException(Level.SEVERE, "Error in runTeleport", e);
      currentPhase = Phase.CLEANUP;
      runCleanup();
    }
  }

  /**
   * Decide whether the emergency {@link RTPWorld#platform(RTPLocation)} call is warranted for
   * this teleport.
   *
   * <p>Historically this method was invoked unconditionally, and subsequently gated on
   * {@code reservation == null} — a heuristic that silently misfires for the kept-queue and
   * DB-rehydrated paths (which legitimately carry a null reservation) and ends up stamping a
   * glass block under every teleport. Gate instead on an actual look at the landing column:
   * build a platform only when the block below the landing Y is non-solid (air / liquid / unsafe)
   * OR the landing block itself is in {@link SafetyKeys#unsafeBlocks}.
   *
   * <p>The check is read-only against {@link RTPWorld#getCachedChunk(long)}, so no chunk load
   * is triggered (REQ-RTP-S-005). If the landing chunk is not cached — which can happen during
   * tests or if the reservation was dropped — we fall back to the safe-by-construction value
   * {@code false}: the authoritative {@code chunk.isSafe(...)} re-check in the selection loop
   * already ran, and the configurable {@code platformRadius: -1} escape hatch remains for
   * operators who want the mechanism fully disabled.
   */
  private static boolean shouldBuildPlatform(RTPWorld<?> world, RTPCoords coords) {
    if (world == null || coords == null) return false;
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      int radius = (safety == null) ? 0 : safety.getNumber(SafetyKeys.platformRadius, 0).intValue();
      // Honour the documented "disable platforms" contract in safety.yml up front, so we never
      // charge the cost of a safety check when the operator has opted out entirely.
      if (radius < 0) return false;

      int x = coords.x();
      int y = coords.y();
      int z = coords.z();
      int cx = x >> 4;
      int cz = z >> 4;
      long key = ((long) cx & 0xffffffffL) | (((long) cz & 0xffffffffL) << 32);
      io.github.dailystruggle.rtp.api.world.RTPChunk<?> chunk = world.getCachedChunk(key);
      if (chunk == null) return false;

      java.util.Set<String> unsafe = new java.util.HashSet<>();
      if (safety != null) {
        Object raw = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        if (raw instanceof java.util.Collection<?> col) {
          for (Object o : col) if (o != null) unsafe.add(o.toString().toUpperCase());
        }
      }

      int lx = x & 15;
      int lz = z & 15;
      int minY = world.getMinHeight();

      // Landing block itself unsafe (unsafe-material list covers lava/fire/magma/void_air/etc.)
      // — build platform to preserve S-001 semantics when the vert-adjustor placed us on top of
      // or inside an unsafe block.
      if (!chunk.isSafe(lx, y, lz, unsafe)) return true;

      // Block directly below is air or liquid — vert-adjustor gave us an airborne or swimming
      // landing; platform provides footing. Uses isAir for air/void_air and the unsafe set for
      // lava (which is the common water/lava case; pure water will fall through to `false`,
      // which is the prior behaviour for swim-spawn configs).
      int belowY = y - 1;
      if (belowY < minY) return true;
      if (chunk.isAir(lx, belowY, lz)) return true;
      if (!chunk.isSafe(lx, belowY, lz, unsafe)) return true;

      return false;
    } catch (Exception e) {
      // Never let the platform heuristic itself abort a teleport.
      SupportLogger.logException(Level.FINE, "shouldBuildPlatform evaluation failed", e);
      return false;
    }
  }

  private void runCleanup() {
    RTP.log(Level.FINE, "[PIPELINE_TRACE] runCleanup ENTER playerId="
            + (context != null && context.player() != null ? context.player().uuid() : "null")
            + " reservationNull=" + (reservation == null)
            + " handledInFlight=" + handledInFlight.get()
            + " thread=" + Thread.currentThread().getName());
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
      }
      if (region == null || coords == null) return;

      // Absolute world resolution prevents multi-dimension cross-leakage
      RTPWorld<?> rtpWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
      if (rtpWorld == null) rtpWorld = region.getWorld();

      if (reservation != null) {
        RTP.log(Level.FINER, "[PIPELINE_TRACE] runCleanup releasing reservation world=" + rtpWorld);
        reservation.close();
        reservation = null;
      }
      cleanupPostActions.forEach(consumer -> consumer.accept(this));
    } finally {
      // Atomic gating prevents double-decrements on concurrent thread crashes
      if (region != null && handledInFlight.compareAndSet(false, true)) {
        region.inFlightCalculations.getAndDecrement();
      }
      if (this.teleportData != null && this.teleportData.nextTask == this) {
        this.teleportData.nextTask = null;
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
