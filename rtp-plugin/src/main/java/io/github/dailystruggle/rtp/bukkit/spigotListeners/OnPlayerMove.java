package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class OnPlayerMove implements Listener {
  private double cancelDistanceSquared = 2;
  private long lastUpdateTime = 0;

  @EventHandler(priority = EventPriority.LOW)
  public void onPlayerMove(PlayerMoveEvent event) {
    UUID id = event.getPlayer().getUniqueId();

    TeleportData data = RTP.getInstance().latestTeleportData.get(id);
    if (data == null || data.completed) return;

    long t = System.nanoTime();
    if (t < lastUpdateTime || ((t - lastUpdateTime) > TimeUnit.SECONDS.toNanos(5))) {
      ConfigParser<?> parser = RTP.configs.configParserMap.get(ConfigKeys.class);
      ConfigParser<ConfigKeys> configParser;
      if (parser.myClass.equals(ConfigKeys.class))
        //noinspection unchecked
        configParser = (ConfigParser<ConfigKeys>) parser;
      else {
        new IllegalStateException("ConfigParser is not using ConfigKeys").printStackTrace();
        return;
      }

      cancelDistanceSquared =
          Math.pow(configParser.getNumber(ConfigKeys.cancelDistance, 0).doubleValue(), 2);
      lastUpdateTime = t;
    }

    RTPPlayer player = RTP.serverAccessor.getPlayer(id);

    RTPCoords originalCoords = data.originalCoords;
    if (originalCoords == null) {
      return;
    }

    RTPLocation playerLocation = player.getLocation();
    double distanceSquared;
    if (!originalCoords.worldName().equals(playerLocation.world().name())) {
      distanceSquared = Double.MAX_VALUE;
    } else {
      distanceSquared =
          Math.pow(originalCoords.x() - playerLocation.x(), 2)
              + Math.pow(originalCoords.y() - playerLocation.y(), 2)
              + Math.pow(originalCoords.z() - playerLocation.z(), 2);
    }

    if (distanceSquared < cancelDistanceSquared) return;

    new RTPTeleportCancel(id).run();
  }
}
