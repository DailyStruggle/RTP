package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.ChunkSet;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.selection.Translate;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ChunkCleanup extends BukkitRunnable {
    private final Location location;
    private final Cache cache;
    private final TeleportRegion region;

    public ChunkCleanup(Location location, Cache cache, TeleportRegion region) {
        this.location = location;
        this.cache = cache;
        this.region = region;
    }

    @Override
    public void run() {
        chunkCleanupNow();
    }

    public void chunkCleanupNow() {
        //cleanup chunks after teleporting
        World world = Objects.requireNonNull(region.world);

        ChunkSet chunkSet = region.getChunks(location);
        region.removeChunks(location);
        for(HashableChunk hashableChunk : chunkSet.hashableChunks) {
            if(!cache.forceLoadedChunks.containsKey(hashableChunk)) continue;
            cache.forceLoadedChunks.remove(hashableChunk);
            world.setChunkForceLoaded(hashableChunk.x,hashableChunk.z,false);
        }
    }
}
