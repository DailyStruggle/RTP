package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.ChunkSet;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

public class ChunkCleanup extends BukkitRunnable {
    private final Location location;
    private final Cache cache;
    private final ChunkSet chunkSet;

    public ChunkCleanup(Location location, Cache cache, ChunkSet chunkSet) {
        this.location = location;
        this.cache = cache;
        this.chunkSet = chunkSet;
    }

    @Override
    public void run() {
        chunkCleanupNow();
    }

    public void chunkCleanupNow() {
        //cleanup chunks after teleporting
        World world = Objects.requireNonNull(location.getWorld());
        try {
            for (HashableChunk hashableChunk : chunkSet.hashableChunks) {
                if (!cache.forceLoadedChunks.containsKey(hashableChunk)) continue;
                cache.forceLoadedChunks.remove(hashableChunk);
                world.setChunkForceLoaded(hashableChunk.x, hashableChunk.z, false);
            }
        }
        catch (NullPointerException exception) {
            for (HashableChunk hashableChunk : chunkSet.hashableChunks) {
                if (!cache.forceLoadedChunks.containsKey(hashableChunk)) continue;
                cache.forceLoadedChunks.remove(hashableChunk);
                world.setChunkForceLoaded(hashableChunk.x, hashableChunk.z, false);
            }
        }
    }
}
