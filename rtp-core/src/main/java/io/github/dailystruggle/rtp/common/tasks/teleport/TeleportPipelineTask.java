package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.GenerationResult;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
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

  public Phase getPhase() {
    return this.currentPhase;
  }

  public void setPhase(Phase phase) {
    this.currentPhase = phase;
  }

  @Override
  public void run() {
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
      if (region != null) region.inFlightCalculations.decrementAndGet();
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
        region.inFlightCalculations.decrementAndGet();
        return;
      }

      coords = res.coords();
      long attempts = res.attempts();

      if (coords == null) {
        teleportData.attempts = attempts;
        RTP.serverAccessor.sendMessage(sender().uuid(), player.uuid(), ConfigCache.unsafe);
        region.inFlightCalculations.decrementAndGet();
        RTPTeleportCancel.refund(player.uuid());
        return;
      }

      teleportData.selectedCoords = coords;
      teleportData.attempts = attempts;
      success = true;
    } catch (Exception e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
      region.inFlightCalculations.decrementAndGet();
      new RTPTeleportCancel(player.uuid()).run();
    } finally {
      boolean finalSuccess = success;
      setupPostActions.forEach(consumer -> consumer.accept(this, finalSuccess));
      if (success) {
        currentPhase = Phase.LOAD;

        long radius2 = ConfigCache.viewDistanceTeleport;
        long max = (radius2 * radius2 * 4) + (4 * radius2) + 1;
        ChunkSet chunkSet = this.region.chunkManager.chunks(coords, radius2);
        if (max > chunkSet.chunks.size()) {
          RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
          chunkSet.keep(false, world);
          chunkSet = region.chunkManager.chunks(coords, radius2);
          chunkSet.keep(true, world);
        }

        RTP.scheduler.runTaskAsynchronously(this);
      }
    }
  }

  private void runLoad() {
    loadPreActions.forEach(consumer -> consumer.accept(this));
    if (isCancelled()) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }

    if (teleportData == null) {
      RTPPlayer player = context.player();
      if (player == null) {
        if (region != null) region.inFlightCalculations.decrementAndGet();
        return;
      }
      UUID playerId = player.uuid();
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
      teleportData.selectedCoords = coords;
    }

    if (region == null || coords == null) {
      if (region != null) region.inFlightCalculations.decrementAndGet();
      return;
    }

    ChunkSet chunkSet = this.region.chunkManager.getChunkSet(coords);
    if (chunkSet == null) {
      currentPhase = Phase.TELEPORT;
      RTP.scheduler.runTask(this);
      return;
    }

    chunkSet.complete.thenRun(
        () -> {
          if (isCancelled()) {
            region.inFlightCalculations.decrementAndGet();
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
          RTP.scheduler.scheduleTeleport(player(), this, toTicks);
        });
  }

  private void runTeleport() {
    teleportPreActions.forEach(consumer -> consumer.accept(this));
    if (isCancelled()) {
      currentPhase = Phase.CLEANUP;
      runCleanup();
      return;
    }

    RTPPlayer player = player();
    if (player == null) {
      runCleanup();
      return;
    }
    UUID playerId = player.uuid();

    try {
      RTPWorld<?> world = region.getWorld();
      RTPLocation location = new RTPLocation(world, coords.x(), coords.y(), coords.z());
      location.world().platform(location);
      RTP.getInstance().invulnerablePlayers.put(playerId, System.currentTimeMillis());

      teleportData.completed = true;
      long processingTime = System.currentTimeMillis() - teleportData.time;
      teleportData.processingTime = processingTime;
      RTP.getInstance().processingPlayers.remove(playerId);

      CompletableFuture<Boolean> setLocation = player.setLocation(location);

      RTP.getInstance().databaseAccessor.cacheValue(teleportData);
      region.inFlightCalculations.decrementAndGet();

      setLocation.whenComplete(
          (aBoolean, throwable) -> {
            if (aBoolean != null && aBoolean) {
              RTP.serverAccessor.sendMessage(playerId, ConfigCache.teleportMessage);
            } else {
              RTP.serverAccessor.sendMessage(playerId, ConfigCache.unsafe);
            }

            currentPhase = Phase.CLEANUP;
            RTP.scheduler.runTask(this);
          });

      teleportPostActions.forEach(consumer -> consumer.accept(this));
    } catch (Exception e) {
      RTP.log(Level.SEVERE, "Error in runTeleport", e);
      currentPhase = Phase.CLEANUP;
      RTP.scheduler.runTask(this);
    }
  }

  private void runCleanup() {
    cleanupPreActions.forEach(consumer -> consumer.accept(this));
    try {
      if (region == null || coords == null) return;
      ChunkSet chunkSet = region.chunkManager.getChunkSet(coords);
      if (chunkSet == null) return;
      RTPWorld<?> rtpWorld = region.getWorld();

      chunkSet.keep(false, rtpWorld);
      region.chunkManager.removeChunks(coords);
      cleanupPostActions.forEach(consumer -> consumer.accept(this));
    } finally {
      if (region != null) region.inFlightCalculations.getAndDecrement();
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
