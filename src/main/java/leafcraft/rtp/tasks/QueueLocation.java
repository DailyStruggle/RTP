package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ConcurrentLinkedQueue;

//TODO
public class QueueLocation extends BukkitRunnable {
    private Config config;
    private Cache cache;
    private World world;

    public QueueLocation(Config config, Cache cache, World world) {
        this.config = config;
        this.cache = cache;
        this.world = world;
    }

    @Override
    public void run() {
        Integer queueLen = config.getQueueLen(world);
        if(cache.locationQueue.get(world.getName()).size() >= queueLen) {
            return;
        }

        //get location and queue up the chunks
        Location res = config.getRandomLocation(world,false);
        if(cache.numTeleportAttempts.get(res) > (Integer)config.getConfigValue("maxAttempts",100)) {
            return;
        }

        res.setY(res.getBlockY()+1);
        cache.addLocation(res);
    }
}
