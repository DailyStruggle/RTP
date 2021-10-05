package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.API.customEvents.TeleportCancelEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Set;

public final class OnPlayerTeleport implements Listener {
    private final Configs configs;
    private final Cache cache;

    public OnPlayerTeleport() {
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        //if currently teleporting, stop that and clean up
        if (cache.todoTP.containsKey(player.getUniqueId())) {
            stopTeleport(event);
        }
    }

    private void stopTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        //don't stop teleporting if there isn't supposed to be a delay
        Location location = this.cache.playerFromLocations.getOrDefault(player.getUniqueId(), player.getLocation());
        double distance = (event.getTo().getWorld().getUID().equals(event.getFrom().getWorld().getUID()))
                ? location.distance(event.getTo()) : Double.MAX_VALUE;
        if (distance < (double) configs.config.cancelDistance) return;

        CommandSender sender = cache.commandSenderLookup.get(player.getUniqueId());
        if(sender == null) return;

        Location to = cache.todoTP.get(player.getUniqueId());
        if(to == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,player,to,event.isAsynchronous());
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);
    }
}