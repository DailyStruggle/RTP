package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Opportunistic on-load biome harvest (PerformanceKeys.checkOnChunkLoads).
 *
 * <p>When a chunk is loaded by the server for any reason, the already-resident
 * chunk's biome is registered into the spatial memory of every configured
 * region whose range contains it, via
 * {@link io.github.dailystruggle.rtp.common.selection.SelectionAPI#observeChunkLoad}.
 * The core method gates O(1) on the config flag and reads no chunk I/O (the
 * chunk is already loaded), so this listener stays cheap even on
 * chunk-load-heavy servers. On Folia the event fires on the region thread that
 * owns the chunk, so the resident-chunk biome read is on the correct thread.
 */
public class OnChunkLoad implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (chunk == null) return;
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(chunk.getWorld().getUID());
        if (world == null) return;
        RTP.selectionAPI.observeChunkLoad(world, chunk.getX(), chunk.getZ());
    }
}
