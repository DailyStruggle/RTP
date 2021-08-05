package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.selection.RandomSelect;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;

import java.math.BigDecimal;

public class TeleportCleanup extends BukkitRunnable {
    Location location;
    Cache cache;

    TeleportCleanup(Location location, Cache cache) {
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        long vd = Bukkit.getViewDistance();
        long cx = location.getChunk().getX();
        long cz = location.getChunk().getZ();
        long area = (long)(vd*vd*Math.PI+0.5d);
        for (long i = 0; i < area; i++) {
            long[] xz = RandomSelect.circleLocationToXZ(0,cx,cz, BigDecimal.valueOf(i));
            Chunk chunk = location.getWorld().getChunkAt((int)xz[0], (int)xz[1]);
            if (cache.forceLoadedChunks.containsKey(chunk)) {
                chunk.setForceLoaded(false);
                cache.forceLoadedChunks.remove(chunk);
            }
        }
    }
}
