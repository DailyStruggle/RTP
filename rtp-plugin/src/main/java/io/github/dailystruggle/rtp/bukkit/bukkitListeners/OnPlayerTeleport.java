package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * External-teleport interceptor for in-flight RTPs.
 *
 * @deprecated The race window this listener guards (between {@code RTPTeleportCancel}-able state
 *     and the actual {@code teleport} call inside the RTP runnable) is too narrow in practice for
 *     an external teleport to land in, and the RTP pipeline already pre-checks {@code TeleportData}
 *     before dispatching. No Fabric equivalent was ported (see {@code MULTI_PLATFORM_PLAN.md} Step
 *     E-tail). Slated for removal once a release cycle has confirmed no regressions; do not add new
 *     dependencies on this class.
 */
@Deprecated
public final class OnPlayerTeleport implements Listener {
  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlayerTeleport(PlayerTeleportEvent event) {
    Player player = event.getPlayer();

    TeleportData data = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
    if (data == null || data.completed) return;

    // if currently teleporting, stop that and clean up
    if (RTP.getInstance().latestTeleportData.containsKey(player.getUniqueId())) {
      TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
      if (!teleportData.completed) stopTeleport(event);
    }
  }

  private void stopTeleport(PlayerTeleportEvent event) {
    Player player = event.getPlayer();

    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
    if (teleportData == null) return;

    Location eventTo = event.getTo();
    if (eventTo == null) return;

    ConfigParser<?> parser = RTP.configs.configParserMap.get(ConfigKeys.class);
    ConfigParser<ConfigKeys> configParser;
    if (parser.myClass.equals(ConfigKeys.class))
      //noinspection unchecked
      configParser = (ConfigParser<ConfigKeys>) parser;
    else {
      new IllegalStateException("ConfigParser is not using ConfigKeys").printStackTrace();
      return;
    }

    RTPCoords selectedCoords = teleportData.selectedCoords;
    if (selectedCoords == null) return;

    RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(eventTo.getWorld().getUID());
    if (rtpWorld == null) return;

    RTPCommandSender sender = teleportData.sender;
    if (sender == null) return;

    double distanceSquared;
    if (!selectedCoords.worldName().equals(rtpWorld.name())) {
      distanceSquared = Double.MAX_VALUE;
    } else {
      distanceSquared =
          Math.pow(selectedCoords.x() - eventTo.getBlockX(), 2)
              + Math.pow(selectedCoords.y() - eventTo.getBlockY(), 2)
              + Math.pow(selectedCoords.z() - eventTo.getBlockZ(), 2);
    }
    if (distanceSquared
        < Math.pow(
            configParser.getNumber(ConfigKeys.cancelDistance, Float.MAX_VALUE).doubleValue(), 2))
      return;

    new RTPTeleportCancel(player.getUniqueId()).run();
  }
}
