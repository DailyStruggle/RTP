package leafcraft.rtp.common.substitutions;

import leafcraft.rtp.common.selection.region.selectors.shapes.Shape;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface RTPWorld {
    String name();
    UUID id();

    /**
     * @param location - target location, not chunk location
     * @return chunk at location
     */
    RTPChunk getChunkAtNow(int[] location);
    CompletableFuture<RTPChunk> getChunkAt(long chunkX, long chunkZ);

    String getBiome(int x, int y, int z);
}
