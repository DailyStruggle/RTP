package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.RandomSelect;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
        long area = (long)(vd*vd*4+0.5d);
        for (long i = 0; i < area; i++) {
            Chunk chunk = location.getWorld().getChunkAt((int)cx,(int)cz);
            HashableChunk hashableChunk = new HashableChunk(chunk);
            if (cache.forceLoadedChunks.containsKey(hashableChunk)) {
                chunk.setForceLoaded(false);
                cache.forceLoadedChunks.remove(chunk);
            }
        }
    }
}
