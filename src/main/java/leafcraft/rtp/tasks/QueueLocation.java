package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
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
        Integer queueLen = (Integer) config.getWorldSetting(world.getName(),"queueLen",10);
        if(cache.locationQueue.get(world.getUID()).size() >= queueLen) {
            return;
        }

        //get location and queue up the chunks
        RandomSelectParams rsParams = new RandomSelectParams(world,new HashMap<>(),config);
        Location res = config.getRandomLocation(rsParams,false);
        if(res == null) {
            return;
        }

        res.setY(res.getBlockY()+1);
        cache.addLocation(res);
    }
}
