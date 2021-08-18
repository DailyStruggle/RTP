package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

public class OnChunkUnload implements Listener {
    private final Cache cache;

    public OnChunkUnload(Cache cache) {
        this.cache = cache;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        HashableChunk hashableChunk = new HashableChunk(event.getChunk());
        if(cache.keepChunks.containsKey(hashableChunk)) {
            event.getChunk().setForceLoaded(true);
            cache.forceLoadedChunks.put(hashableChunk,Long.valueOf(0));
        }
    }
}
