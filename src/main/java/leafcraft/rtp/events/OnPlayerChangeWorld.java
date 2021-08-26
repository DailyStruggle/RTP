package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.HashMap;

public final class OnPlayerChangeWorld implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerChangeWorld(RTP plugin, Configs configs,
                               Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void OnPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        //if currently teleporting, stop that and clean up
        if (this.cache.todoTP.containsKey(player.getUniqueId())) {
            stopTeleport(event);
        }

        //if has this perm, go again
        if (player.hasPermission("rtp.onEvent.changeWorld")) {
            //skip if already going
            SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
            LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
            if (setupTeleport != null && setupTeleport.isNoDelay()) return;
            if (loadChunks != null && loadChunks.isNoDelay()) return;
            if (doTeleport != null && doTeleport.isNoDelay()) return;

            //run command as console
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "rtp player:" + player.getName() + " world:" + player.getWorld().getName());
        }

        if(player.hasPermission("rtp.personalQueue")) {
            World fromWorld = event.getFrom();
            String fromWorldName = fromWorld.getName();
            if (!player.hasPermission("rtp.worlds." + fromWorldName) && (Boolean) configs.worlds.getWorldSetting(fromWorldName, "requirePermission", true)) {
                fromWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(fromWorldName, "override", "world"));
            }
            RandomSelectParams fromParams = new RandomSelectParams(fromWorld, new HashMap<>(), configs);
            if (cache.permRegions.containsKey(fromParams)) {
                cache.permRegions.get(fromParams).recyclePlayerLocation(player);
            }

            World toWorld = event.getFrom();
            String toWorldName = toWorld.getName();
            if (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) configs.worlds.getWorldSetting(toWorldName, "requirePermission", true)) {
                toWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(toWorldName, "override", "world"));
            }
            RandomSelectParams toParams = new RandomSelectParams(toWorld, new HashMap<>(), configs);
            if (cache.permRegions.containsKey(toParams)) {
                QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(toParams), player, cache);
                cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
                queueLocation.runTaskLaterAsynchronously(plugin, 1);
            }
        }
    }

    private void stopTeleport(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        //don't stop teleporting if there isn't supposed to be a delay
        SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
        LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
        DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
        if (setupTeleport != null && setupTeleport.isNoDelay()) return;
        if (loadChunks != null && loadChunks.isNoDelay()) return;
        if (doTeleport != null && doTeleport.isNoDelay()) return;

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

        player.sendMessage(configs.lang.getLog("teleportCancel"));
    }
}
