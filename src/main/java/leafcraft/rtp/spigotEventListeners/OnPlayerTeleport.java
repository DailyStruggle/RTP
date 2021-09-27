package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.API.customEvents.TeleportCancelEvent;
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

import java.util.HashMap;
import java.util.Set;

public final class OnPlayerTeleport implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerTeleport() {
        this.plugin = RTP.getPlugin();
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

        //if has this perm, go again
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
            if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.teleport"))
                hasPerm = true;
        }
        if (hasPerm) {
            //skip if already going
            SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
            LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
            if (setupTeleport != null && setupTeleport.isNoDelay()) return;
            if (loadChunks != null && loadChunks.isNoDelay()) return;
            if (doTeleport != null && doTeleport.isNoDelay()) return;

            //run command
            if (setupTeleport == null && loadChunks == null && doTeleport == null) {
                setupTeleport = new SetupTeleport(plugin, player, player, configs, cache, new RandomSelectParams(player.getWorld(), null));
                this.cache.setupTeleports.put(player.getUniqueId(), setupTeleport);
                setupTeleport.runTaskAsynchronously(plugin);
            }
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