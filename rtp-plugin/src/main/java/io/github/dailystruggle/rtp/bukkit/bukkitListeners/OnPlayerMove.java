package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.MoveCancelGrace;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
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

    org.bukkit.Location from = event.getFrom();
    org.bukkit.Location to = event.getTo();
    if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) {
      return;
    }

    // Bridge to RTPAPI.playerMoveEvents for watched players (ADR-075 / ADR-093)
    if (RTPAPI.playerMoveEvents != null
        && RTPAPI.playerMoveEvents.hasWatchers()
        && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ())) {
      String worldName = (to.getWorld() != null) ? to.getWorld().getName() : "";
      RTPAPI.playerMoveEvents.fire(new io.github.dailystruggle.rtp.api.event.PlayerMoveEvent(
          id,
          worldName,
          from.getBlockX(),
          from.getBlockY(),
          from.getBlockZ(),
          to.getBlockX(),
          to.getBlockY(),
          to.getBlockZ()));
    }

    RTP rtp = RTP.getInstance();
    if (rtp == null) return;

    if (TeleportSessions.current().queuedPlayers.contains(id)) return;

    TeleportData data = TeleportSessions.current().latestTeleportData.get(id);
    if (data == null || data.completed) return;

    double cancelDistanceSquared = Math.pow(ActiveConfigs.get().getParser(ConfigKeys.class).getNumber(ConfigKeys.cancelDistance, 2).doubleValue(), 2);

    RTPCoords originalCoords = data.originalCoords;
    if (originalCoords == null) {
      return;
    }

    String worldName = (to.getWorld() == null) ? null : to.getWorld().getName();
    double distanceSquared = movedSquared(originalCoords, worldName, to.getX(), to.getY(), to.getZ(),
        MoveCancelGrace.isActive(id));

    if (distanceSquared < cancelDistanceSquared) return;

    new RTPTeleportCancel(id).run();
  }

  /**
   * Squared distance from the teleport origin; {@link Double#MAX_VALUE} in another world. With
   * {@code horizontalOnly} (an effect such as the Rift fall moves the player, RTP-71) the Y axis
   * is ignored, so falling does not cancel but walking away still does.
   */
  static double movedSquared(
      RTPCoords origin, String worldName, double x, double y, double z, boolean horizontalOnly) {
    if (worldName == null || !origin.worldName().equals(worldName)) return Double.MAX_VALUE;
    double dx = origin.x() - x;
    double dy = horizontalOnly ? 0 : origin.y() - y;
    double dz = origin.z() - z;
    return (dx * dx) + (dy * dy) + (dz * dz);
  }
}
