package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.Translate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class TeleportCleanup extends BukkitRunnable {
    Player player;
    Location location;
    Cache cache;

    TeleportCleanup(Player player, Location location, Cache cache) {
        this.player = player;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        int vd = Bukkit.getViewDistance();
        int cx = location.getChunk().getX();
        int cz = location.getChunk().getZ();
        long area = (long)(vd*vd*4+0.5d);
        for (long i = 0; i < area; i++) {
            int[] xz = Translate.squareLocationToXZ(0,cx,cz,area);
            Chunk chunk = location.getWorld().getChunkAt(xz[0],xz[1]);
            HashableChunk hashableChunk = new HashableChunk(chunk);
            if (cache.forceLoadedChunks.containsKey(hashableChunk)) {
                chunk.setForceLoaded(false);
                cache.forceLoadedChunks.remove(chunk);
            }
        }

        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if(cache.permRegions.containsKey(rsParams))
            cache.permRegions.get(rsParams).removeChunks(location);
    }
}
