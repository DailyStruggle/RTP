package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.CancellationCleanup;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class OnPlayerMove implements Listener {
    private RTP plugin;
    private Config config;
    private Cache cache;

    public OnPlayerMove(RTP plugin, Config config, Cache cache) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if(!this.cache.todoTP.containsKey(player.getName())) return;
        Location location = this.cache.playerFromLocations.getOrDefault(player.getName(),player.getLocation());
        if(location.distance(event.getTo()) < (Integer)this.config.getConfigValue("cancelDistance",2)) return;

        if(cache.loadChunks.containsKey(player.getName())) {
            cache.loadChunks.get(player.getName()).cancel();
            cache.loadChunks.remove(player.getName());
        }
        if(cache.doTeleports.containsKey(player.getName())) {
            cache.doTeleports.get(player.getName()).cancel();
            cache.doTeleports.remove(player.getName());
        }

        Location randomLocation = cache.todoTP.get(player.getName());
        cache.todoTP.remove(player.getName());
        cache.playerFromLocations.remove(player.getName());
        cache.locationQueue.putIfAbsent(randomLocation.getWorld().getUID(), new ConcurrentLinkedQueue<>());
        cache.locationQueue.get(randomLocation.getWorld().getUID()).offer(randomLocation);

        player.sendMessage(this.config.getLog("teleportCancel"));
    }

}
