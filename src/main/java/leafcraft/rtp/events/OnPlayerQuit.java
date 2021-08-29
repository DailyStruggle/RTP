package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.UUID;

public final class OnPlayerQuit implements Listener {
    private final Cache cache;

    public OnPlayerQuit(Cache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void OnPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        //if currently teleporting, stop that and clean up
        if (!this.cache.todoTP.containsKey(playerId)) return;
        cache.todoTP.remove(playerId);

        if (cache.setupTeleports.containsKey(playerId)) {
            cache.setupTeleports.get(playerId).cancel();
            cache.setupTeleports.remove(playerId);
        }
        if (cache.loadChunks.containsKey(playerId)) {
            cache.loadChunks.get(playerId).cancel();
            cache.loadChunks.remove(playerId);
        }
        if (cache.doTeleports.containsKey(playerId)) {
            cache.doTeleports.get(playerId).cancel();
            cache.doTeleports.remove(playerId);
        }

        RandomSelectParams rsParams = cache.regionKeys.get(playerId);
        if (cache.permRegions.containsKey(rsParams)) {
            Location randomLocation = cache.todoTP.get(playerId);
            cache.permRegions.get(cache.regionKeys.get(playerId)).queueLocation(randomLocation);
        } else cache.tempRegions.remove(rsParams);
        cache.regionKeys.remove(playerId);
        cache.todoTP.remove(playerId);
        cache.playerFromLocations.remove(playerId);
    }
}
