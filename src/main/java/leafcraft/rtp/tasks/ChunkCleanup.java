package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.configuration.Configs;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

public class ChunkCleanup extends BukkitRunnable {
    private final Configs configs;
    private final Location location;
    private final Cache cache;

    public ChunkCleanup(Configs configs, Location location, Cache cache) {
        this.configs = configs;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        chunkCleanupNow();
    }

    public void chunkCleanupNow() {
        //cleanup chunks after teleporting
        int vd = configs.config.vd;
        int cx = location.getChunk().getX();
        int cz = location.getChunk().getZ();
        World world = location.getWorld();
        for (int i = -vd; i < vd; i++) {
            for (int j = -vd; j < vd; j++) {
                if (!Objects.requireNonNull(world).isChunkForceLoaded(cx + i, cz + j)) continue;
                HashableChunk hashableChunk = new HashableChunk(world, cx + i, cz + j);
                if (cache.forceLoadedChunks.containsKey(hashableChunk)) {
                    world.setChunkForceLoaded(cx + i, cz + j, false);
                    cache.forceLoadedChunks.remove(hashableChunk);
                }
            }
        }
    }
}
