package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DoTeleport extends BukkitRunnable {
    private RTP plugin;
    private Configs configs;
    private Player player;
    private Location location;
    private Cache cache;

    public DoTeleport(RTP plugin, Configs configs, Player player, Location location, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.player = player;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        //cleanup cache first to avoid cancel() issues
        cache.playerFromLocations.remove(player.getUniqueId());
        cache.doTeleports.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        if (cache.loadChunks.containsKey(player.getUniqueId())) {
            cache.loadChunks.get(player.getUniqueId()).cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }

        List<CompletableFuture<Chunk>> chunks;
        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if(cache.permRegions.containsKey(rsParams))
            chunks = cache.permRegions.get(rsParams).getChunks(location);
        else chunks = cache.tempRegions.get(rsParams).getChunks(location);

        for(CompletableFuture<Chunk> cfChunk : chunks) {
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

        if(this.isCancelled()) return;
        PaperLib.teleportAsync(player,location);
        this.player.sendMessage(configs.lang.getLog("teleportMessage", this.cache.numTeleportAttempts.getOrDefault(location,0).toString()));
        new TeleportCleanup(player,location,cache).runTask(plugin);
    }
}
