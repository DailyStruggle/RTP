package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;

public final class OnPlayerJoin implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerJoin() {
        this.plugin = RTP.getPlugin();
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            if(player.hasPermission("rtp.personalQueue")) {
                World toWorld = event.getPlayer().getWorld();
                String toWorldName = toWorld.getName();
                if (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) configs.worlds.getWorldSetting(toWorldName, "requirePermission", true)) {
                    toWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(toWorldName, "override", "world"));
                }
                RandomSelectParams toParams = new RandomSelectParams(Objects.requireNonNull(toWorld), null);
                if (cache.permRegions.containsKey(toParams)) {
                    QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(toParams), player, cache);
                    cache.queueLocationTasks.put(queueLocation.idx, queueLocation);
                    queueLocation.runTaskAsynchronously(plugin);
                }
            }
        });
    }
}
