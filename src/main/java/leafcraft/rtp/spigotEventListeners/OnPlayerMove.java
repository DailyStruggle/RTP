package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.API.customEvents.TeleportCancelEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;

public final class OnPlayerMove implements Listener {
    private final Configs configs;
    private final Cache cache;

    public OnPlayerMove() {
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        //if currently teleporting, stop that and clean up
        if (this.cache.todoTP.containsKey(event.getPlayer().getUniqueId())) {
            stopTeleport(event);
        }
    }

    private void stopTeleport(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        Location originalLocation = cache.playerFromLocations.getOrDefault(player.getUniqueId(), player.getLocation());
        if(originalLocation == null) return;
        if (originalLocation.distance(Objects.requireNonNull(event.getTo())) < configs.config.cancelDistance) return;

        CommandSender sender = cache.commandSenderLookup.get(player.getUniqueId());
        if(sender == null) return;

        Location to = cache.todoTP.get(player.getUniqueId());
        if(to == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,player,to,event.isAsynchronous());
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);
    }
}
