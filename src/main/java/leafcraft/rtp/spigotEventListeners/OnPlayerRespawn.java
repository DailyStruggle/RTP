package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.API.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Objects;

//get queued location
public final class OnPlayerRespawn implements Listener {
    private final RTP plugin;
    private final Configs Configs;
    private final Cache cache;

    public OnPlayerRespawn() {
        this.plugin = RTP.getInstance();
        this.Configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World toWorld = player.getWorld();
        String toWorldName = toWorld.getName();
        if (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) Configs.worlds.getWorldSetting(toWorldName, "requirePermission", true)) {
            toWorld = Bukkit.getWorld((String) Configs.worlds.getWorldSetting(toWorldName, "override", "world"));
        }
        if(player.hasPermission("rtp.personalQueue")) {
            RandomSelectParams toParams = new RandomSelectParams(Objects.requireNonNull(toWorld), null);
            if (cache.permRegions.containsKey(toParams)) {
                QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(toParams), player, cache);
                queueLocation.runTaskLaterAsynchronously(plugin, 1);
                cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
            }
        }
    }
}
