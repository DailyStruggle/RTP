package leafcraft.rtp.events;

import leafcraft.rtp.tasks.CancellationCleanup;
import leafcraft.rtp.tools.Cache;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.CompletableFuture;

public class OnPlayerQuit implements Listener {
    private Cache cache;

    public OnPlayerQuit(Cache cache) {
        this.cache = cache;
    }

    @EventHandler
    public void OnPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if(!this.cache.todoTP.containsKey(player.getName())) return;

        if(cache.loadChunks.containsKey(player.getName())) {
            cache.loadChunks.get(player.getName()).cancel();
            cache.loadChunks.remove(player.getName());
        }
        if(cache.doTeleports.containsKey(player.getName())) {
            cache.doTeleports.get(player.getName()).cancel();
            cache.doTeleports.remove(player.getName());
        }

        Location randomLocation = cache.todoTP.get(player.getName());
        if(cache.locAssChunks.containsKey(randomLocation)) {
            for(CompletableFuture<Chunk> cfChunk : cache.locAssChunks.get(randomLocation)) {
                cfChunk.cancel(true);
            }
        }
        cache.todoTP.remove(player.getName());
        cache.playerFromLocations.remove(player.getName());
    }
}
