package io.github.dailystruggle.rtp.paper_v1_20_R1.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

public class PaperRTPChunkManager implements RTPChunkManager {
    @Override
    public CompletableFuture<RTPChunk<?>> getChunkAtAsync( RTPWorld<?> world, int x, int z ) {
        if ( !(world instanceof BukkitRTPWorld) ) {
            return CompletableFuture.failedFuture( new IllegalArgumentException("World is not a BukkitRTPWorld") );
        }

        World bukkitWorld = ((BukkitRTPWorld) world).world();
        
        // Use Paper's getChunkAtAsync with gen=true
        return bukkitWorld.getChunkAtAsync( x, z, true ).thenApply( BukkitRTPChunk::new );
    }
}
