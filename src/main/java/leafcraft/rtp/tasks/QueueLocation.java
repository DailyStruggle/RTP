package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

//BukkitTask container for queueing asynchronously
public class QueueLocation extends BukkitRunnable {
    private static long counter = 0L;
    public final long idx;
    private final TeleportRegion region;
    private Player player = null;
    private Location location = null;
    private final Cache cache;

    public QueueLocation(TeleportRegion region, Cache cache) {
        this.region = region;
        counter++;
        this.idx = counter;
        this.cache = cache;
    }

    public QueueLocation(TeleportRegion region, Location location, Cache cache) {
        this.region = region;
        this.location = location;
        counter++;
        this.idx = counter;
        this.cache = cache;
    }

    public QueueLocation(TeleportRegion region, Player player, Cache cache) {
        this.region = region;
        this.player = player;
        counter++;
        this.idx = counter;
        this.cache = cache;
    }

    @Override
    public void run() {
        if(player == null || !player.isOnline()) {
            if (location == null)
                region.queueRandomLocation();
            else
                region.queueLocation(location);
        }
        else {
            region.queueRandomLocation(player);
        }
        this.cache.queueLocationTasks.remove(idx);
    }
}
