package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public class OnPlayerTeleport implements Listener {
    private RTP plugin;
    private Configs configs;
    private Cache cache;

    public OnPlayerTeleport(RTP plugin, Configs configs, Cache cache) {
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler
    public void OnPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if(!this.cache.todoTP.containsKey(player.getUniqueId())) return;
        Location location = this.cache.playerFromLocations.getOrDefault(player.getUniqueId(),player.getLocation());
        if(location.distance(event.getTo()) < (Integer)configs.config.getConfigValue("cancelDistance",2)) return;

        if(cache.loadChunks.containsKey(player.getUniqueId())) {
            cache.loadChunks.get(player.getUniqueId()).cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }
        if(cache.doTeleports.containsKey(player.getUniqueId())) {
            cache.doTeleports.get(player.getUniqueId()).cancel();
            cache.doTeleports.remove(player.getUniqueId());
        }

        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if(cache.permRegions.containsKey(rsParams)) {
            Location randomLocation = cache.todoTP.get(player.getUniqueId());
            cache.permRegions.get(rsParams).queueLocation(randomLocation);
        }
        else cache.tempRegions.remove(rsParams);
        cache.regionKeys.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        cache.playerFromLocations.remove(player.getUniqueId());

        player.sendMessage(configs.lang.getLog("teleportCancel"));
    }
}