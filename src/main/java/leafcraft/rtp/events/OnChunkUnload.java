package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

public class OnChunkUnload implements Listener {
    private Cache cache;

    OnChunkUnload(Cache cache) {
        this.cache = cache;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if(cache.keepChunks.containsKey(event.getChunk())) {
            Bukkit.getLogger().warning("keeping chunk: " + event.getChunk().getX() + ","+ event.getChunk().getZ());
            event.getChunk().setForceLoaded(true);
            HashableChunk hashableChunk = new HashableChunk(event.getChunk());
            cache.forceLoadedChunks.put(hashableChunk,Long.valueOf(0));
        }
    }
}
