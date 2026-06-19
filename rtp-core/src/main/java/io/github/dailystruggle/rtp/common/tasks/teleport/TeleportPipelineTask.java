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
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.SupportLogger;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
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
  /** Wall-clock start (nanos) for {@code avgPipelineMs} histogram, recorded once on cleanup. */
  private final long pipelineStartNanos = System.nanoTime();
  /**
   * ADR-053 §2a: whether this teleport was served immediately (unqueued) rather than from the
   * at-rate public wait queue. {@code true} for the {@code QueueTask.unqueuedFast} / custom /
   * on-event immediate paths; {@code false} only for the queue-drain serve path (the 4-arg
   * constructor used exclusively by {@code Region.execute}). The slow-teleport latency audit
   * is gated to immediate teleports because a queued task's elapsed window would otherwise be
   * contaminated by at-rate queue-wait time and false-positive on every queued teleport.
   */
  private final boolean immediateTeleport;
  private final java.util.concurrent.atomic.AtomicBoolean pipelineHistogramRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);
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
    public static boolean postTeleportQueueing = false;
    /** BetterRTP SetAsRespawn parity: anchor the player's respawn to the landed location. */
    public static boolean setRespawnOnTeleport = false;
    /** BetterRTP LockAfter parity: usage cap (0 disables). */
    public static long lockAfterUses = 0;
    /** BetterRTP LockAfter parity: rolling window length in milliseconds (0 = never resets). */
    public static long lockAfterResetMillis = 0;

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
      postTeleportQueueing = Boolean.parseBoolean(
          perf.getConfigValue(PerformanceKeys.postTeleportQueueing, false).toString());

      ConfigParser<ConfigKeys> cfg =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      if (cfg != null) {
        setRespawnOnTeleport = Boolean.parseBoolean(
            cfg.getConfigValue(ConfigKeys.setRespawnOnTeleport, false).toString());
        lockAfterUses = cfg.getNumber(ConfigKeys.lockAfterUses, 0L).longValue();
        lockAfterResetMillis =
            cfg.getNumber(ConfigKeys.lockAfterResetSeconds, 0L).longValue() * 1000L;
      }
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

  /**
   * ADR-058 — region-specific schematic paste. When a {@code schematics/<region>.schem} file
   * is present, {@link #runLoad} (off the region thread) kicks off the decode into this future
   * and {@link #runTeleport} (on the region thread) pastes the result in place of the emergency
   * platform. Both stay {@code null} when no schematic file exists for the region.
   */
  private java.util.concurrent.CompletableFuture<
      io.github.dailystruggle.rtp.api.schematic.LoadedSchematic> schematicLoad;
  private io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster;
  /**
   * ADR-058 / S-004 — the schematic source resolved for this teleport, kept so {@link #runTeleport}
   * can emit an operator-visible WARNING if a region had a schematic file on disk but it never
   * reached a successful paste (decode failure, no paster, or zero placed blocks). Stays
   * {@code null} when no {@code schematics/<region>.schem} file exists (the silent, expected case).
   */
  private io.github.dailystruggle.rtp.api.schematic.SchematicSource schematicSource;

  /**
   * ADR-058 / S-004 — regions for which a <em>platform-level</em> schematic fallback reason
   * (no schematic paster installed, or the installed paster does not support the file's format)
   * has already been logged. Both reasons are static for a given platform + region, so emitting
   * them on every single teleport is pure log spam (observed on Fabric, which ships
   * {@code NoOpSchematicPaster}). This guard collapses them to one WARNING per region per reason
   * while still satisfying S-004 (the operator is told once, not silently). Keyed
   * {@code <regionName>|<reason>}.
   */
  private static final java.util.Set<String> loggedSchematicPlatformFallback =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * ADR-026 / ADR-058 — addon-bound arrival platform creator (the {@code RTPHooks#platformCreator()}
   * registry). Resolved once in {@link #runLoad} (off the region thread) so its two-phase
   * contract can run its blocking {@link io.github.dailystruggle.rtp.api.platform.PlatformCreator#prepare}
   * phase off-thread; the resulting handle is consumed by
   * {@link io.github.dailystruggle.rtp.api.platform.PlatformCreator#createPlatform(RTPLocation, Object)}
   * on the region thread in {@link #buildArrivalPlatform}. Both stay {@code null} when no creator
   * is bound. This is the same two-phase shape the region schematic uses — a {@code SchematicPaster}
   * is no longer special-cased at the dispatch site.
   */
  private io.github.dailystruggle.rtp.api.platform.PlatformCreator platformCreator;
  private java.util.concurrent.CompletableFuture<?> platformPrepare;

  public TeleportPipelineTask(GenerationContext context) {
    this.context = context;
    this.immediateTeleport = true;
  }

  public TeleportPipelineTask(GenerationContext context, Region region) {
    this.context = context;
    this.region = region;
    this.immediateTeleport = true;
  }

  public TeleportPipelineTask(GenerationContext context, Region region, RTPCoords preSelectedCoords) {
    this.context = context;
    this.region = region;
    this.coords = preSelectedCoords;
    this.currentPhase = Phase.LOAD;
    this.immediateTeleport = true;
  }

  public TeleportPipelineTask(GenerationContext context, Region region, RTPCoords preSelectedCoords, ChunkReservation reservation) {
    this.context = context;
    this.region = region;
    this.coords = preSelectedCoords;
    this.reservation = reservation;
    this.currentPhase = Phase.LOAD;
    // Queue-drain serve path (Region.execute): this is the at-rate public-queue path, so the
    // slow-teleport latency audit (ADR-053 §2a) must NOT apply. Backpressure on this path is
    // covered separately by the queue-growth audit (§2b).
    this.immediateTeleport = false;
  }

  /** Spark-profiler frame tag (diagram 01 / 08). See {@link RTPRunnable#sparkFrameName()}. */
  @Override
  protected String sparkFrameName() { return "rtp_pipeline_attempt"; }

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
                rtpWorld.recordChunkLoadOrigin("TeleportPipelineTask.viewDistance");
                chunks.add(rtpWorld.getChunkAt(cx + x, cz + z));
              }
            }
            io.github.dailystruggle.rtp.common.tools.CfDiag.chunkSetPipeline.increment();
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

      // ADR-058 — region-specific schematic. The presence of <pluginDir>/schematics/<region>.schem
      // is the knob (no config key). The file read + decode is blocking I/O, so it is started here
      // on the load (non-region) thread; the resulting blocks are written on the region thread in
      // runTeleport. Resolution / decode failure is best-effort and never aborts the teleport
      // (S-004): a missing file leaves schematicLoad null and the default platform path runs.
      if (schematicLoad == null) {
        try {
          RTPWorld<?> schemWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
          if (schemWorld == null) schemWorld = region.getWorld();
          io.github.dailystruggle.rtp.api.schematic.SchematicSource src =
              (schemWorld != null) ? RegionSchematicService.resolveSource(region.name) : null;
          if (src != null) {
            // A schematic file exists for this region: from here on, any failure to actually
            // paste it is operator-relevant and must be logged (S-004), not silently dropped.
            schematicSource = src;
            io.github.dailystruggle.rtp.api.schematic.SchematicPaster paster =
                schemWorld.schematicPaster();
            if (paster == null
                || paster instanceof io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster) {
              // S-004 once-per-region: "no paster installed" is a static platform property, so it
              // is logged a single time per region rather than on every teleport (Fabric spam).
              if (loggedSchematicPlatformFallback.add(region.name + "|nopaster")) {
                RTP.log(Level.WARNING, "[RTP] region schematic '" + region.name + "' resolved to '"
                    + src.path() + "' but no schematic paster is installed on this platform "
                    + "(" + (paster == null ? "null" : paster.getClass().getSimpleName())
                    + "); falling back to default platform (S-004).");
              }
            } else if (!paster.supports(src)) {
              // S-004 once-per-region: format support is static for a platform + file, so log once.
              if (loggedSchematicPlatformFallback.add(
                  region.name + "|unsupported:" + src.formatHint())) {
                RTP.log(Level.WARNING, "[RTP] region schematic '" + region.name + "' file '"
                    + src.path() + "' (format '" + src.formatHint() + "') is not supported by "
                    + paster.getClass().getSimpleName()
                    + "; falling back to default platform (S-004).");
              }
            } else {
              schematicPaster = paster;
              schematicLoad = paster.load(src);
              // load() never throws (it returns a null-completed future on decode failure),
              // so detect that here and surface it; the underlying cause is logged by the
              // paster's load() implementation.
              if (schematicLoad != null && schematicLoad.isDone()
                  && schematicLoad.join() == null) {
                RTP.log(Level.WARNING, "[RTP] region schematic '" + region.name + "' file '"
                    + src.path() + "' failed to decode; falling back to default platform "
                    + "(S-004). See the preceding schematic-decode log line for the cause.");
              } else {
                RTP.log(Level.FINE, "[PIPELINE_TRACE] runLoad resolved region schematic '"
                    + region.name + "' from '" + src.path() + "'");
              }
            }
          }
        } catch (Exception e) {
          SupportLogger.logException(Level.WARNING,
              "region schematic load dispatch failed for region '" + region.name + "'", e);
        }
      }

      // ADR-026 — addon-bound arrival platform creator. Resolve it here, on the load (non-region)
      // thread, and kick off its blocking prepare() phase off-thread so the region-thread paste in
      // buildArrivalPlatform only has to write blocks (S-005). A bound SchematicPaster is driven
      // through this same two-phase path — it is no longer special-cased at the dispatch site; its
      // default prepare()/createPlatform() decline gracefully when no source applies.
      if (platformCreator == null) {
        try {
          platformCreator = io.github.dailystruggle.rtp.api.RTPAPI.hooks().platformCreator().current();
        } catch (IllegalStateException ignored) {
          // Hooks facade not loaded yet (REQ-RTP-S-006): no creator, use the default platform.
        } catch (Exception e) {
          SupportLogger.logException(Level.WARNING, "platform creator resolution failed", e);
        }
        if (platformCreator != null) {
          try {
            RTPWorld<?> platWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
            if (platWorld == null) platWorld = region.getWorld();
            RTPLocation platAt =
                new RTPLocation(platWorld, coords.x(), coords.y(), coords.z());
            platformPrepare = platformCreator.prepare(platAt);
          } catch (Exception e) {
            SupportLogger.logException(Level.WARNING, "platform creator '"
                + platformCreator.creatorName() + "' prepare() dispatch failed; "
                + "falling back to default platform (S-004)", e);
            platformPrepare = null;
          }
        }
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
                  RTP.scheduler.runTaskForPlayer(player(), this, toTicks);
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

    // --- Optional PvP / combat-tag execution prefilter (ADR-055) -------------
    // Re-consult the combat authority immediately before the chosen destination
    // is applied. A player who entered combat after the request was enrolled (or
    // who was waiting in a queue) must not be teleported out of a fight, so the
    // pre-dispatch check in RTPCmd is not sufficient on its own. No-op when the
    // gate is disabled (pvpCheckEnabled=false, the default) or the player is not
    // in combat. At this surface there is an in-progress teleport, so any
    // non-ALLOW action (DENY / DELAY / CANCEL) collapses to "do not apply this
    // destination": abort to CLEANUP, surface the configurable pvpInCombat
    // message, and audit at WARNING (REQ-RTP-S-004: never a silent discard).
    // The gate fails open (PvPGate guards its own provider call), and the call
    // site is guarded too, so a broken integration never blocks a teleport.
    io.github.dailystruggle.rtp.api.hooks.PvPCombatAction pvpAction;
    try {
      pvpAction = io.github.dailystruggle.rtp.common.pvp.PvPGate.evaluate(playerId);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] PvP gate evaluation threw at execution prefilter; allowing teleport", t);
      pvpAction = io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW;
    }
    if (pvpAction != io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW) {
      RTP.serverAccessor.sendMessage(playerId, MessagesKeys.pvpInCombat);
      // S-004: the abort is never silent.
      RTP.log(Level.WARNING, "[RTP] PvP gate aborted teleport at execution prefilter for "
          + playerId + " (action=" + pvpAction + ", in combat)");
      RTP.getInstance().processingPlayers.remove(playerId);
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    } else if (io.github.dailystruggle.rtp.common.pvp.PvPGate.isEnabled()
        && io.github.dailystruggle.rtp.common.pvp.PvPGate.isInCombat(playerId)) {
      // ALLOW-and-audit: operator wants visibility without changing behaviour.
      RTP.log(Level.INFO, "[RTP] PvP gate: " + playerId
          + " is in combat at execution but pvpOnCombat=ALLOW; permitting teleport");
    }
    // --- end PvP execution prefilter -----------------------------------------

    try {
      RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
      if (world == null) world = region.getWorld();
      RTPLocation location = new RTPLocation(world, coords.x(), coords.y(), coords.z());
      location.setReservation(reservation);
      boolean buildPlatform = shouldBuildPlatform(world, coords);
      RTP.log(Level.FINER, "[PIPELINE_TRACE] runTeleport platformDecision=" + buildPlatform
              + " worldNull=" + (world == null));

      // ADR-058 — region-specific schematic paste (region thread). When the region has a
      // schematic file, paste it at the arrival location instead of the emergency platform:
      // the schematic provides the footing the platform would have. The paste is best-effort
      // (S-004) — any skip/failure is audited and the default platform path still runs so the
      // player never lands without footing.
      boolean pastedSchematic = false;
      io.github.dailystruggle.rtp.api.schematic.LoadedSchematic loadedSchematic =
          (schematicLoad != null && schematicLoad.isDone()) ? schematicLoad.join() : null;
      // S-004 — a region with a schematic file on disk that nonetheless reaches this point
      // with nothing loaded must not fall through to the platform silently: tell the operator
      // why the island/structure they configured did not appear. The {@code schematicLoad == null}
      // sub-case (no paster installed / unsupported format) is deliberately NOT re-logged here:
      // runLoad already audited it once per region via loggedSchematicPlatformFallback, so
      // repeating it on every teleport would be the exact duplicate-spam this guard removes.
      if (loadedSchematic == null && schematicSource != null && schematicLoad != null) {
        RTP.log(Level.WARNING, "[RTP] region schematic '" + region.name + "' file '"
            + schematicSource.path() + "' was present but produced no loaded schematic ("
            + (!schematicLoad.isDone() ? "decode still pending at paste time"
                : "decode returned no data")
            + "); falling back to default platform (S-004).");
      }
      if (loadedSchematic != null && schematicPaster != null) {
        // Folia threading rule (and ADR-058): schematic block writes must run on the region
        // that owns the *destination* chunk(s), not on whatever scheduler thread invoked
        // runTeleport (typically the player's current entity-region thread). Pasting directly
        // here throws Folia's "Cannot read world asynchronously" TickThread check whenever the
        // destination region differs from the player's current region. Bounce the paste — and
        // its platform fallback — to the destination region via RTP.scheduler.runTask(location).
        // On non-Folia platforms this dispatches to the main thread / runs inline.
        final io.github.dailystruggle.rtp.api.schematic.LoadedSchematic loadedFinal =
            loadedSchematic;
        final RTPLocation locFinal = location;
        final boolean buildPlatformFinal = buildPlatform;
        final String worldNameFinal = coords.worldName();
        pastedSchematic = true; // platform fallback is handled inside the region task below
        RTP.scheduler.runTask(locFinal, () -> {
          boolean pasted = false;
          // ADR-058 — anchor SURFACE_CENTER (not the BOTTOM_CENTER default): the schematic is
          // dropped so the player lands on a standable floor in its center column instead of
          // being buried under blocks pasted around their feet. The drop is computed from the
          // schematic data in one pass, so the arrival point is standable without re-pasting.
          io.github.dailystruggle.rtp.api.schematic.PasteOptions pasteOptions =
              new io.github.dailystruggle.rtp.api.schematic.PasteOptions(
                  io.github.dailystruggle.rtp.api.schematic.PasteAnchor.SURFACE_CENTER,
                  false, true);
          // ADR-058 / REQ-RTP-S-003 — footprint claim check. Before writing any block, verify
          // the schematic's horizontal footprint against the GlobalRegionVerifiers registry (the
          // sanctioned claim/protection mechanism — never an inline claim-plugin call). If any
          // footprint cell intersects protected land, the paste is suppressed and the default
          // platform path runs instead, so the paste never overwrites a claim.
          if (pasteOptions.claimAware()
              && !schematicFootprintClear(loadedFinal, locFinal, pasteOptions, worldNameFinal)) {
            RTP.log(Level.INFO, "[RTP] region schematic '" + region.name
                + "' footprint intersects a claim; paste suppressed (S-003), falling back to "
                + "default platform.");
          } else {
            try {
              io.github.dailystruggle.rtp.api.schematic.PasteResult pasteResult =
                  schematicPaster.paste(loadedFinal, locFinal, pasteOptions);
              if (pasteResult != null && pasteResult.placed()) {
                pasted = true;
                RTP.log(Level.FINE, "[PIPELINE_TRACE] runTeleport pasted region schematic '"
                    + region.name + "'");
              } else {
                RTP.log(Level.WARNING, "[RTP] region schematic '" + region.name
                    + "' not pasted (" + pasteResult + "); falling back to default platform "
                    + "(S-004). See the preceding schematic-paste log line for the per-block "
                    + "cause.");
              }
            } catch (Exception e) {
              SupportLogger.logException(Level.WARNING,
                  "region schematic paste failed for '" + region.name + "'", e);
            }
          }
          if (!pasted && buildPlatformFinal) {
            buildArrivalPlatform(locFinal);
          }
        });
      }

      if (!pastedSchematic && buildPlatform) {
        buildArrivalPlatform(location);
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

                // BetterRTP SetAsRespawn parity: anchor the player's respawn to the
                // landed location. Runs on the teleport completion thread (the owning
                // thread of the destination), so the native call is region-safe. S-004:
                // a failure is logged, never silently swallowed, and never aborts cleanup.
                if (ConfigCache.setRespawnOnTeleport) {
                  try {
                    player.setRespawnLocation(location);
                  } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                        "[RTP] setRespawnOnTeleport failed for " + playerId, t);
                  }
                }

                // BetterRTP LockAfter parity: count this successful use toward the
                // per-player cap. No-op while lockAfterUses <= 0.
                if (ConfigCache.lockAfterUses > 0) {
                  RTP.getInstance().teleportLimitStore.recordSuccess(playerId,
                      ConfigCache.lockAfterUses, ConfigCache.lockAfterResetMillis,
                      System.currentTimeMillis());
                }
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

              // postTeleportQueueing: opportunistically schedule one cache-refill
              // task on the region's cachePipeline after a successful teleport.
              // Mirrors the periodic deficit loop in Region (Region.java:720); the
              // task self-gates on cache fullness, so this is at worst a no-op
              // when the cache is already at cacheCap. Default off (PerformanceKeys).
              if (aBoolean != null && aBoolean
                  && ConfigCache.postTeleportQueueing
                  && region != null
                  && region.cachePipeline != null) {
                try {
                  region.cachePipeline.add(
                      new io.github.dailystruggle.rtp.common.selection.region.RegionCacheTask(
                          region, 1_000_000L));
                } catch (Throwable t) {
                  RTP.log(Level.WARNING,
                      "[RTP] postTeleportQueueing dispatch failed: " + t, t);
                }
              }

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

  /**
   * Build the arrival platform at {@code at}, preferring the addon-bound
   * {@link io.github.dailystruggle.rtp.api.platform.PlatformCreator} (ADR-026/ADR-058) resolved
   * in {@link #runLoad} over the built-in emergency block disc.
   *
   * <p>The creator is driven through its uniform two-phase contract: the blocking
   * {@link io.github.dailystruggle.rtp.api.platform.PlatformCreator#prepare prepare} phase
   * already ran off-thread in {@code runLoad} (its handle is {@link #platformPrepare}); here, on
   * the region-owning thread, only
   * {@link io.github.dailystruggle.rtp.api.platform.PlatformCreator#createPlatform(RTPLocation, Object)}
   * runs. A {@code SchematicPaster} is no longer special-cased — it is just a creator whose
   * default two-phase methods decline when no region source applies. Any failure is logged and
   * falls back to the default platform; the creator is never allowed to abort the teleport
   * (S-004).
   *
   * <p>Invoked on the region-owning thread, mirroring the platform write it replaces.
   */
  private void buildArrivalPlatform(RTPLocation at) {
    if (at == null || at.world() == null) return;
    if (platformCreator != null) {
      boolean built;
      try {
        // The prepare() future was kicked off off-thread in runLoad; join its handle only if it
        // has already completed so the region thread never blocks (S-005). A still-pending or
        // failed prepare yields a null handle, which a well-behaved creator treats as "decline".
        Object prepared =
            (platformPrepare != null && platformPrepare.isDone()) ? platformPrepare.join() : null;
        built = platformCreator.createPlatform(at, prepared);
      } catch (Exception e) {
        SupportLogger.logException(Level.WARNING,
            "platform creator '" + platformCreator.creatorName()
                + "' threw; falling back to default platform (S-004)", e);
        built = false;
      }
      if (built) {
        RTP.log(Level.FINE, "[PIPELINE_TRACE] arrival platform built by '"
            + platformCreator.creatorName() + "'");
        return;
      }
      RTP.log(Level.FINE, "[PIPELINE_TRACE] platform creator '" + platformCreator.creatorName()
          + "' declined; using default platform");
    }
    at.world().platform(at);
  }

  /**
   * ADR-058 / REQ-RTP-S-003 — footprint claim guard for region schematic pastes.
   *
   * <p>Walks the schematic's horizontal footprint (the same anchor math the
   * {@link io.github.dailystruggle.rtp.api.schematic.SchematicPlacementPlanner} applies) and
   * runs each cell through {@link GlobalRegionVerifiers}, the sanctioned claim/protection
   * registry (never an inline claim-plugin call). Returns {@code false} as soon as one cell is
   * reported inside a claim, so the caller can suppress the paste before any block is written.
   * The registered claim verifiers are synchronous, so the per-cell future is normally already
   * complete; an in-flight async verifier is awaited and any failure of the check itself is
   * treated as "protected" (fail-safe, S-003).
   *
   * @return {@code true} when the whole footprint is clear (or no verifiers are registered)
   */
  static boolean schematicFootprintClear(
      io.github.dailystruggle.rtp.api.schematic.LoadedSchematic schematic,
      RTPLocation at,
      io.github.dailystruggle.rtp.api.schematic.PasteOptions options,
      String fallbackWorldName) {
    int width = schematic.width();
    int length = schematic.length();
    if (width <= 0 || length <= 0) {
      return true;
    }
    int baseX;
    int baseZ;
    switch (options.anchor()) {
      case ORIGIN:
        baseX = at.x() + schematic.offsetX();
        baseZ = at.z() + schematic.offsetZ();
        break;
      case CENTER:
      case BOTTOM_CENTER:
      default:
        baseX = at.x() - (width - 1) / 2;
        baseZ = at.z() - (length - 1) / 2;
        break;
    }
    String worldName = (at.world() != null) ? at.world().name() : fallbackWorldName;
    int y = at.y();
    for (int dz = 0; dz < length; dz++) {
      for (int dx = 0; dx < width; dx++) {
        RTPCoords cell = new RTPCoords(worldName, baseX + dx, y, baseZ + dz);
        try {
          CompletableFuture<Boolean> check =
              GlobalRegionVerifiers.checkGlobalRegionVerifiers(cell);
          Boolean ok = check.join();
          if (ok != null && !ok) {
            return false;
          }
        } catch (Throwable t) {
          // Fail-safe (S-003): if the footprint claim check itself errors, treat the cell as
          // protected and suppress the paste rather than risk overwriting a claim.
          SupportLogger.logException(Level.FINE,
              "schematic footprint claim check failed; suppressing paste", t);
          return false;
        }
      }
    }
    return true;
  }

  private void runCleanup() {
    // Phase M1: record this attempt's wall-clock duration into the global pipeline
    // histogram exactly once. Cleanup is the unique terminal phase for every code
    // path (success, cancel, exception, GC sweep), so this is the right place. The
    // try/catch is defensive: a metrics record must never abort cleanup (S-004).
    if (pipelineHistogramRecorded.compareAndSet(false, true)) {
      try {
        long elapsedMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000L;
        RTP.metrics.pipelineHistogram().record(elapsedMs);
        // ADR-053 §2a (REQ-RTP-OBS-005): audit a slow latency only for immediate/unqueued
        // teleports. Queued (at-rate) teleports are excluded because their elapsed window
        // includes queue-wait time and would false-positive; their degradation signal is the
        // queue-growth audit (§2b). The counter/WARN live on CoreMetrics; the audit is opt-out
        // (slowPipelineThresholdMs <= 0 disables) and never aborts cleanup (S-004 posture).
        if (immediateTeleport
                && RTP.metrics instanceof io.github.dailystruggle.rtp.common.metrics.CoreMetrics) {
          UUID auditId = (context != null && context.player() != null)
                  ? context.player().uuid() : null;
          String auditCtx = "player=" + auditId
                  + " region=" + (region != null ? region.name : "null");
          ((io.github.dailystruggle.rtp.common.metrics.CoreMetrics) RTP.metrics)
                  .auditImmediateTeleport(elapsedMs, auditCtx);
        }
      } catch (Throwable ignored) {
        // intentionally swallowed: metrics must not interfere with teardown.
      }
    }
    RTP.log(Level.FINE, "[PIPELINE_TRACE] runCleanup ENTER playerId="
            + (context != null && context.player() != null ? context.player().uuid() : "null")
            + " reservationNull=" + (reservation == null)
            + " handledInFlight=" + handledInFlight.get()
            + " thread=" + Thread.currentThread().getName());
    cleanupPreActions.forEach(consumer -> consumer.accept(this));
    // Deliver the public-API completion outcome exactly once. Cleanup is the unique
    // terminal phase for every code path (success, cancel, exception, GC sweep), so
    // firing here guarantees an RTPAPI.teleport future never hangs (REQ-RTP-S-004).
    // No-op for teleports not initiated through the public API.
    if (this.teleportData != null) {
      this.teleportData.fireOnComplete();
    }
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
