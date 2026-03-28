package io.github.dailystruggle.rtp.folia_v1_21_R1.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

public class FoliaRTPChunkManager implements RTPChunkManager {
    @Override
    public CompletableFuture<RTPChunk<?>> getChunkAtAsync( RTPWorld<?> world, int x, int z ) {
        if ( !(world instanceof FoliaRTPWorld) ) {
            return CompletableFuture.failedFuture( new IllegalArgumentException("World is not a FoliaRTPWorld") );
        }

        World bukkitWorld = ((FoliaRTPWorld) world).world();
        
        return bukkitWorld.getChunkAtAsync( x, z, true ).thenApply( FoliaRTPChunk::new );
    }
}
