package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;

//get queued location
public final class OnPlayerRespawn implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerRespawn(RTP plugin, Configs configs,
                           Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void OnPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World toWorld = player.getWorld();
        String toWorldName = toWorld.getName();
        if (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) configs.worlds.getWorldSetting(toWorldName, "requirePermission", true)) {
            toWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(toWorldName, "override", "world"));
        }
        if (player.hasPermission("rtp.onEvent.respawn")) {
            RandomSelectParams rsParams = new RandomSelectParams(player.getWorld(), new HashMap<>(), configs);
            SetupTeleport setupTeleport = new SetupTeleport(plugin, Bukkit.getConsoleSender(), player, configs, cache, rsParams);
            setupTeleport.runTaskAsynchronously(plugin);
            cache.setupTeleports.put(player.getUniqueId(), setupTeleport);
        }

        if(player.hasPermission("rtp.personalQueue")) {
            RandomSelectParams toParams = new RandomSelectParams(toWorld, new HashMap<>(), configs);
            if (cache.permRegions.containsKey(toParams)) {
                QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(toParams), player, cache);
                cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
                queueLocation.runTaskLaterAsynchronously(plugin, 1);
            }
        }
    }
}
