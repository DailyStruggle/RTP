package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class LoadChunks extends BukkitRunnable {
    private final RTP plugin;
    private final Configs configs;
    private final Player player;
    private final Cache cache;
    private final Integer totalTime;
    private final Location location;
    private List<CompletableFuture<Chunk>> chunks = null;

    public LoadChunks(RTP plugin, Configs configs, Player player, Cache cache, Integer totalTime, Location location) {
        this.plugin = plugin;
        this.configs = configs;
        this.player = player;
        this.cache = cache;
        this.totalTime = totalTime;
        this.location = location;
    }

    @Override
    public void run() {
        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if(cache.permRegions.containsKey(rsParams))
            chunks = cache.permRegions.get(rsParams).getChunks(location);
        else chunks = cache.tempRegions.get(rsParams).getChunks(location);

        Long startTime = System.currentTimeMillis();
        for (CompletableFuture<Chunk> chunk : chunks) {
            if(this.isCancelled()) break;
            try {
                chunk.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
        long finTime = System.currentTimeMillis();
        long remTime = totalTime - ((finTime - startTime)/50);
        if(remTime < 0) remTime = 0;

        if(this.isCancelled()) return;
        this.cache.doTeleports.put(this.player.getUniqueId(),new DoTeleport(plugin,configs,player,location,cache).runTaskLater(plugin,remTime));
    }
}
