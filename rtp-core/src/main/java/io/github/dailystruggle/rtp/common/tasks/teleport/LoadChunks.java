package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class LoadChunks extends RTPRunnable {
  public static final List<Consumer<LoadChunks>> preActions = new ArrayList<>();
  public static final List<Consumer<LoadChunks>> postActions = new ArrayList<>();

  static {
    preActions.add(task -> task.isRunning.set(true));
    postActions.add(task -> task.isRunning.set(false));
  }

  private final RTPCommandSender sender;
  private final RTPPlayer player;
  private final RTPCoords coords;
  private final Region region;
  public boolean modified = false;

  public LoadChunks(RTPCommandSender sender, RTPPlayer player, RTPCoords coords, Region region) {
    this.sender = sender;
    this.player = player;
    this.coords = coords;
    this.region = region;

    ConfigParser<PerformanceKeys> perf =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    long radius2 = perf.getNumber(PerformanceKeys.viewDistanceTeleport, 0L).longValue();
    long max = (radius2 * radius2 * 4) + (4 * radius2) + 1;

    ChunkSet chunkSet = this.region.chunks(coords, radius2);

    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
    if (teleportData == null) {
      teleportData = new TeleportData();
      teleportData.sender = (sender != null) ? sender : player;
      teleportData.originalCoords =
          new RTPCoords(
              player.getLocation().world().name(),
              player.getLocation().x(),
              player.getLocation().y(),
              player.getLocation().z());
      teleportData.selectedCoords = coords;
      teleportData.time = System.currentTimeMillis();
      teleportData.nextTask = this;
      teleportData.targetRegion = region;
      teleportData.delay = sender.delay();
      RTP.getInstance().latestTeleportData.put(player.uuid(), teleportData);
    }

    if (max > chunkSet.chunks.size()) {
      RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
      chunkSet.keep(false, world);
      chunkSet = teleportData.targetRegion.chunks(coords, radius2);
      chunkSet.keep(true, world);
      modified = true;
    }
  }

  @Override
  public void run() {
    try {
      preActions.forEach(consumer -> consumer.accept(this));

      ChunkSet chunkSet = this.region.locAssChunks.get(coords);
      chunkSet.complete.thenRun(
          () -> {
            long start = System.currentTimeMillis();

            TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());

            DoTeleport doTeleport = new DoTeleport(sender, player, coords, region);
            teleportData.nextTask = doTeleport;

            long lastTime = teleportData.time;

            long delay = sender.delay();
            long dT = (start - lastTime);
            long remainingTime = delay - dT;
            long toTicks = remainingTime / 50;

            RTP.scheduler.scheduleTeleport(player, doTeleport, toTicks);
            postActions.forEach(consumer -> consumer.accept(this));
          });
    } catch (Throwable throwable) {
      throwable.printStackTrace();
      new RTPTeleportCancel(player.uuid()).run();
    }
  }

  public RTPCommandSender sender() {
    return sender;
  }

  public RTPPlayer player() {
    return player;
  }

  public RTPCoords coords() {
    return coords;
  }

  public Region region() {
    return region;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    LoadChunks that = (LoadChunks) obj;
    return Objects.equals(this.sender, that.sender)
        && Objects.equals(this.player, that.player)
        && Objects.equals(this.coords, that.coords)
        && Objects.equals(this.region, that.region);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sender, player, coords, region);
  }

  @Override
  public String toString() {
    return "LoadChunks["
        + "sender="
        + sender
        + ", "
        + "player="
        + player
        + ", "
        + "coords="
        + coords
        + ", "
        + "region="
        + region
        + ']';
  }
}
