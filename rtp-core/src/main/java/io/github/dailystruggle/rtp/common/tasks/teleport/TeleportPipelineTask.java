package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

  @Override
  public void run() {
    if (isCancelled()) {
      if (region != null) region.inFlightCalculations.decrementAndGet();
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
      if (region != null) region.inFlightCalculations.decrementAndGet();
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

      Map.Entry<RTPCoords, Long> pair = region.getLocation(context);
      if (pair == null) {
        region.inFlightCalculations.decrementAndGet();
        return;
      }

      coords = pair.getKey();
      long attempts = pair.getValue();

      if (coords == null) {
        teleportData.attempts = attempts;
        ConfigParser<MessagesKeys> langParser =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = langParser.getConfigValue(MessagesKeys.unsafe, "").toString();
        RTP.serverAccessor.sendMessage(sender().uuid(), player.uuid(), msg);
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

        ConfigParser<PerformanceKeys> perf =
            (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        long radius2 = perf.getNumber(PerformanceKeys.viewDistanceTeleport, 0L).longValue();
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
      region.inFlightCalculations.decrementAndGet();
      return;
    }

    if (region == null || coords == null) {
      region.inFlightCalculations.decrementAndGet();
      return;
    }

    ChunkSet chunkSet = this.region.chunkManager.locAssChunks.get(coords);
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
      region.inFlightCalculations.decrementAndGet();
      return;
    }

    RTPPlayer player = player();
    if (player == null) {
      region.inFlightCalculations.decrementAndGet();
      return;
    }
    UUID playerId = player.uuid();

    try {
      RTPWorld<?> world = region.getWorld();
      RTPLocation location = new RTPLocation(world, coords.x(), coords.y(), coords.z());
      location.world().platform(location);
      RTP.getInstance().invulnerablePlayers.put(playerId, System.currentTimeMillis());

      teleportData.completed = true;
      RTP.getInstance().processingPlayers.remove(playerId);

      CompletableFuture<Boolean> setLocation = player.setLocation(location);

      Map<String, Object> dataMap = DatabaseAccessor.toColumns(teleportData);
      dataMap.put("playerName", player.name());
      Map<String, Object> saveMap = new HashMap<>();
      if (RTP.getInstance().databaseAccessor instanceof YamlFileDatabase) {
        saveMap.put(playerId.toString(), dataMap);
      } else {
        saveMap.put("UUID", playerId.toString());
        saveMap.putAll(dataMap);
      }
      RTP.getInstance().databaseAccessor.cacheValue("teleportData", saveMap);
      region.inFlightCalculations.decrementAndGet();

      setLocation.thenAccept(
          aBoolean -> {
            RTP.serverAccessor.sendMessage(playerId, MessagesKeys.teleportMessage);
          });

      teleportPostActions.forEach(consumer -> consumer.accept(this));
    } finally {
      currentPhase = Phase.CLEANUP;
      RTP.scheduler.runTask(this);
    }
  }

  private void runCleanup() {
    cleanupPreActions.forEach(consumer -> consumer.accept(this));
    if (region == null || coords == null) return;
    ChunkSet chunkSet = region.chunkManager.locAssChunks.get(coords);
    if (chunkSet == null) return;
    RTPWorld<?> rtpWorld = region.getWorld();

    Consumer<Boolean> cleanupAction =
        (success) -> {
          chunkSet.keep(false, rtpWorld);
          chunkSet.chunks.forEach(
              cf -> {
                if (cf.isDone()) {
                  Long key = cf.getNow(null);
                  if (key != null) {
                    RTPChunk<?> chunk = rtpWorld.getCachedChunk(key);
                    if (chunk != null) chunk.unload();
                  }
                }
              });
          region.chunkManager.removeChunks(coords);
          cleanupPostActions.forEach(consumer -> consumer.accept(this));
        };

    if (chunkSet.complete.isDone()) {
      cleanupAction.accept(chunkSet.complete.getNow(false));
    } else {
      chunkSet.complete.thenAccept(cleanupAction);
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
