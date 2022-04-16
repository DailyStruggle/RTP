package leafcraft.rtp.api.substitutions;

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
    CompletableFuture<RTPChunk> getChunkAt(int[] location);
}
