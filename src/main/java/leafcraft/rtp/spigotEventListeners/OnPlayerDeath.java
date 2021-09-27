package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.API.customEvents.TeleportCancelEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Set;

//set up a location for respawn event
public final class OnPlayerDeath implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerDeath() {
        this.plugin = RTP.getPlugin();
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void OnPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        //if currently teleporting, stop that and clean up
        if (cache.todoTP.containsKey(player.getUniqueId())) {
            stopTeleport(event);
        }

        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
            if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.respawn"))
                hasPerm = true;
        }
        if (hasPerm) {
            RandomSelectParams rsParams = new RandomSelectParams(event.getEntity().getWorld(), null);
            TeleportRegion region = cache.permRegions.get(rsParams);
            QueueLocation queueLocation = new QueueLocation(region, player, cache);
            cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
            queueLocation.runTaskLaterAsynchronously(plugin, 1);
        }
    }

    private void stopTeleport(PlayerDeathEvent event) {
        Player player = event.getEntity();

        CommandSender sender = cache.commandSenderLookup.get(player.getUniqueId());
        if(sender == null) return;

        Location to = cache.todoTP.get(player.getUniqueId());
        if(to == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,player,to,event.isAsynchronous());
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);

    }
}
