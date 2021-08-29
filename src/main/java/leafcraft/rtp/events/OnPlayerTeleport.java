package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;

public final class OnPlayerTeleport implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerTeleport(RTP plugin, Configs configs,
                            Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void OnPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        //if currently teleporting, stop that and clean up
        if (cache.todoTP.containsKey(player.getUniqueId())) {
            cache.todoTP.containsKey(player.getUniqueId());
            stopTeleport(event);
        }

        //if has this perm, go again
        if (player.hasPermission("rtp.onEvent.teleport")) {
            //skip if already going
            SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
            LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
            if (setupTeleport != null && setupTeleport.isNoDelay()) return;
            if (loadChunks != null && loadChunks.isNoDelay()) return;
            if (doTeleport != null && doTeleport.isNoDelay()) return;

            //run command
            if (setupTeleport == null && loadChunks == null && doTeleport == null) {
                setupTeleport = new SetupTeleport(plugin, player, player, configs, cache, new RandomSelectParams(player.getWorld(), new HashMap<>(), configs));
                this.cache.setupTeleports.put(player.getUniqueId(), setupTeleport);
                setupTeleport.runTaskAsynchronously(plugin);
            }
        }
    }

    private void stopTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        //don't stop teleporting if there isn't supposed to be a delay
        SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
        LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
        DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
        if (setupTeleport != null && setupTeleport.isNoDelay()) return;
        if (loadChunks != null && loadChunks.isNoDelay()) return;
        if (doTeleport != null && doTeleport.isNoDelay()) return;

        Location location = this.cache.playerFromLocations.getOrDefault(player.getUniqueId(), player.getLocation());
        if (location.distance(event.getTo()) < configs.config.cancelDistance) return;

        if (setupTeleport != null) {
            setupTeleport.cancel();
            cache.setupTeleports.remove(player.getUniqueId());
        }
        if (loadChunks != null) {
            loadChunks.cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }
        if (doTeleport != null) {
            doTeleport.cancel();
            cache.doTeleports.remove(player.getUniqueId());
        }

        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if (cache.permRegions.containsKey(rsParams)) {
            Location randomLocation = cache.todoTP.get(player.getUniqueId());
            QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(rsParams), randomLocation, cache);
            cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
            queueLocation.runTaskLaterAsynchronously(plugin, 1);
        } else cache.tempRegions.remove(rsParams);
        cache.regionKeys.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        cache.playerFromLocations.remove(player.getUniqueId());
    }
}