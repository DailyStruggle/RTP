package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class OnPlayerJoin implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerJoin(RTP plugin, Configs configs,
                        Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void OnPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("rtp.onEvent.firstJoin") && !player.hasPlayedBefore()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "rtp player:" + player.getName() + " world:" + player.getWorld().getName());
        } else if (player.hasPermission("rtp.onEvent.join")) {
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
            World toWorld = event.getPlayer().getWorld();
            String toWorldName = toWorld.getName();
            if (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) configs.worlds.getWorldSetting(toWorldName, "requirePermission", true)) {
                toWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(toWorldName, "override", "world"));
            }
            RandomSelectParams toParams = new RandomSelectParams(toWorld, new HashMap<>(), configs);
            if (cache.permRegions.containsKey(toParams)) {
                QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(toParams), player, cache);
                cache.queueLocationTasks.put(queueLocation.idx, queueLocation);
                queueLocation.runTaskAsynchronously(plugin);
            }
        }
    }
}
