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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

public class LoadChunks extends BukkitRunnable {
    private final RTP plugin;
    private final Config config;
    private final Player player;
    private final Cache cache;
    private final Integer totalTime;
    private final Location location;
    private List<CompletableFuture<Chunk>> chunks = null;

    public LoadChunks(RTP plugin, Config config, Player player, Cache cache, Integer totalTime, Location location) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.cache = cache;
        this.totalTime = totalTime;
        this.location = location;
    }

    @Override
    public void run() {
        this.cache.locAssChunks.putIfAbsent(location, new ArrayList<>());
        chunks = this.cache.locAssChunks.get(location);

        Long startTime = System.currentTimeMillis();
        for (int i = 0; i < chunks.size(); i++) {
            try {
                chunks.get(i).get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
        long finTime = System.currentTimeMillis();
        long remTime = totalTime - 20*(finTime - startTime)/1000;
        if(remTime < 0) remTime = 0;

        this.cache.doTeleports.put(this.player.getName(),new DoTeleport(plugin,config,player,location,cache).runTaskLater(plugin,remTime));
    }

    @Override
    public void cancel() {
        cache.locationQueue.putIfAbsent(location.getWorld().getName(),new ConcurrentLinkedQueue<>());
        cache.locationQueue.get(location.getWorld().getName()).offer(location);
        cache.locAssChunks.putIfAbsent(location,chunks);
        super.cancel();
    }
}
