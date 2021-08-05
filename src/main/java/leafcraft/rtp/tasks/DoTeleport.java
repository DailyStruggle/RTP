package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.selection.RandomSelect;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DoTeleport extends BukkitRunnable {
    private RTP plugin;
    private Config config;
    private Player player;
    private Location location;
    private Cache cache;

    public DoTeleport(RTP plugin, Config config, Player player, Location location, Cache cache) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        //cleanup cache first to avoid cancel() issues
        cache.playerFromLocations.remove(player.getName());
        cache.doTeleports.remove(player.getName());
        cache.todoTP.remove(player.getName());
        if (cache.loadChunks.containsKey(player.getName())) {
            cache.loadChunks.get(player.getName()).cancel();
            cache.loadChunks.remove(player.getName());
        }

        for(CompletableFuture<Chunk> cfChunk : cache.locAssChunks.get(location)) {
            Chunk chunk = null;
            try {
                chunk = cfChunk.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            if(cache.forceLoadedChunks.containsKey(chunk)) {
                Bukkit.getLogger().warning("UN-keeping chunk: " + chunk.getX() + "," + chunk.getZ());
                chunk.setForceLoaded(false);
            }
        }

        player.teleport(location);
        this.player.sendMessage(this.config.getLog("teleportMessage", this.cache.numTeleportAttempts.getOrDefault(location,0).toString()));
        new TeleportCleanup(location,cache).runTaskAsynchronously(plugin);
    }
}
