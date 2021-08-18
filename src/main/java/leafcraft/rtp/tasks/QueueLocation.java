package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

//BukkitTask container for queueing asynchronously
public class QueueLocation extends BukkitRunnable {
    private final TeleportRegion region;
    private Player player = null;
    private Location location = null;

    public QueueLocation(TeleportRegion region) {
        this.region = region;
    }

    public QueueLocation(TeleportRegion region, Location location) {
        this.region = region;
        this.location = location;
    }

    public QueueLocation(TeleportRegion region, Player player) {
        this.region = region;
        this.player = player;
    }

    @Override
    public void run() {
        if(player == null) {
            if (location != null)
                region.queueLocation(location);
            else
                region.queueRandomLocation();
        }
        else {
            region.queueRandomLocation(player);
        }
    }
}
