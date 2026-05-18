package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class OnPlayerMove implements Listener {

    @EventHandler(priority = EventPriority.LOW)
  public void onPlayerMove(PlayerMoveEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    if (RTP.getInstance().queuedPlayers.contains(id)) return;

    org.bukkit.Location from = event.getFrom();
    org.bukkit.Location to = event.getTo();
    if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) {
      return;
    }

    TeleportData data = RTP.getInstance().latestTeleportData.get(id);
    if (data == null || data.completed) return;

    double cancelDistanceSquared = Math.pow(RTP.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.cancelDistance, 2).doubleValue(), 2);

    RTPCoords originalCoords = data.originalCoords;
    if (originalCoords == null) {
      return;
    }

    double distanceSquared;
    if (to.getWorld() == null || !originalCoords.worldName().equals(to.getWorld().getName())) {
      distanceSquared = Double.MAX_VALUE;
    } else {
      double dx = originalCoords.x() - to.getX();
      double dy = originalCoords.y() - to.getY();
      double dz = originalCoords.z() - to.getZ();
      distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
    }

    if (distanceSquared < cancelDistanceSquared) return;

    new RTPTeleportCancel(id).run();
  }
}
