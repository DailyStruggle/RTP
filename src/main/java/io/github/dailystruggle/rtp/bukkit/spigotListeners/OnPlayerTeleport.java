package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCancelEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;

public final class OnPlayerTeleport implements Listener {

    public OnPlayerTeleport() {

    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        //if currently teleporting, stop that and clean up
        if (RTP.getInstance().latestTeleportData.containsKey(player.getUniqueId())) {
            TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
            if (!teleportData.completed)
                stopTeleport(event);
        }
    }

    private void stopTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (!RTP.getInstance().latestTeleportData.containsKey(player.getUniqueId())) {
            return;
        }

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.getUniqueId());

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.getInstance().configs.configParserMap.get(ConfigKeys.class);

        Location eventTo = event.getTo();
        if(eventTo == null) return;
        RTPLocation location = teleportData.selectedLocation;
        RTPLocation to = new RTPLocation(new BukkitRTPWorld(eventTo.getWorld()), eventTo.getBlockX(), eventTo.getBlockY(), eventTo.getBlockZ());
        double distanceSquared = (Objects.requireNonNull(Objects.requireNonNull(eventTo).getWorld()).getUID()
                .equals(Objects.requireNonNull(event.getFrom().getWorld()).getUID()))
                ? location.distanceSquared(to) : Double.MAX_VALUE;
        if (distanceSquared < Math.pow(configParser.getNumber(ConfigKeys.cancelDistance,Double.MAX_VALUE).doubleValue(),2)) return;

        RTPCommandSender sender = teleportData.sender;
        if(sender == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,new BukkitRTPPlayer(player),to,event.isAsynchronous());
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);


    }
}