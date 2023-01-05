package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;

public final class OnPlayerTeleport implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        TeleportData data = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
        if (data == null || data.completed) return;

        //if currently teleporting, stop that and clean up
        if (RTP.getInstance().latestTeleportData.containsKey(player.getUniqueId())) {
            TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
            if (!teleportData.completed)
                stopTeleport(event);
        }
    }

    private void stopTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
        if (teleportData == null) return;

        Location eventTo = event.getTo();
        if (eventTo == null) return;

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.configs.configParserMap.get(ConfigKeys.class);

        RTPLocation location = teleportData.selectedLocation;
        if (location == null) return;

        RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(eventTo.getWorld().getUID());
        if (rtpWorld == null) return;

        RTPCommandSender sender = teleportData.sender;
        if (sender == null) return;

        RTPLocation to = new RTPLocation(rtpWorld, eventTo.getBlockX(), eventTo.getBlockY(), eventTo.getBlockZ());
        double distanceSquared = (Objects.requireNonNull(Objects.requireNonNull(eventTo).getWorld()).getUID()
                .equals(Objects.requireNonNull(event.getFrom().getWorld()).getUID()))
                ? location.distanceSquared(to) : Double.MAX_VALUE;
        if (distanceSquared < Math.pow(configParser.getNumber(ConfigKeys.cancelDistance, Float.MAX_VALUE).doubleValue(), 2))
            return;

        new RTPTeleportCancel(player.getUniqueId()).run();
    }
}