package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LoadChunks extends BukkitRunnable {
    private final RTP plugin;
    private final Config config;
    private final Player player;
    private final Cache cache;
    private final Integer totalTime;
    private Integer remainingTime;
    private final Location location;

    public LoadChunks(RTP plugin, Config config, Player player, Cache cache, Integer totalTime, Integer remainingTime, Location location) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.cache = cache;
        this.totalTime = totalTime;
        this.remainingTime = remainingTime;
        this.location = location;
    }

    @Override
    public void run() {
        remainingTime--;

        if(!this.cache.playerAssChunks.containsKey(player.getName()))
            this.cache.playerAssChunks.put(player.getName(), new ArrayList<>());
        List<CompletableFuture<Chunk>> res = this.cache.playerAssChunks.get(this.player.getName());

        Integer r = Bukkit.getViewDistance();

        final double area = Math.PI*r*r;
        double currArea = area * (totalTime - remainingTime);

        for(int i = 0; i < currArea; i++) {
            Double radius = Math.sqrt(area/Math.PI);
            Integer distance = radius.intValue();
            Double rotation = (radius - distance)*2*Math.PI;
            Double x = distance * Cache.cos(rotation);
            Double z = distance * Cache.sin(rotation);
            res.add(PaperLib.getChunkAtAsync(location.getWorld(),location.getBlockX()/16+x.intValue(), location.getBlockZ()/16+z.intValue(),true));
        }

        if(remainingTime>0) this.cache.addLoadChunks(this.player,new LoadChunks(this.plugin,this.config,this.player,this.cache,this.totalTime,this.remainingTime-1,this.location).runTaskLaterAsynchronously(this.plugin,1));
        else this.cache.addDoTeleport(this.player, new DoTeleport(this.config, this.player, location, this.cache).runTaskLater(this.plugin, 1));
    }
}
