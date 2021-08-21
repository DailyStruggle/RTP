package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashMap;

//set up a location for respawn event
public record OnPlayerDeath(RTP plugin, Configs configs,
                            Cache cache) implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public void OnPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        //if currently teleporting, stop that and clean up
        if (cache.todoTP.containsKey(player.getUniqueId())) {
            stopTeleport(event);
        }

        if (player.hasPermission("rtp.onEvent.respawn")) {
            RandomSelectParams rsParams = new RandomSelectParams(event.getEntity().getWorld(), new HashMap<>(), configs);
            TeleportRegion region = cache.permRegions.get(rsParams);
            new QueueLocation(region, player).runTaskAsynchronously(plugin);
        }
    }

    private void stopTeleport(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (cache.setupTeleports.containsKey(player.getUniqueId())) {
            cache.setupTeleports.get(player.getUniqueId()).cancel();
            cache.setupTeleports.remove(player.getUniqueId());
        }
        if (cache.loadChunks.containsKey(player.getUniqueId())) {
            cache.loadChunks.get(player.getUniqueId()).cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }
        if (cache.doTeleports.containsKey(player.getUniqueId())) {
            cache.doTeleports.get(player.getUniqueId()).cancel();
            cache.doTeleports.remove(player.getUniqueId());
        }

        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if (cache.permRegions.containsKey(rsParams)) {
            Location randomLocation = cache.todoTP.get(player.getUniqueId());
            new QueueLocation(cache.permRegions.get(rsParams), randomLocation).runTaskLaterAsynchronously(plugin, 1);
        } else cache.tempRegions.remove(rsParams);
        cache.regionKeys.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        cache.playerFromLocations.remove(player.getUniqueId());
    }
}
