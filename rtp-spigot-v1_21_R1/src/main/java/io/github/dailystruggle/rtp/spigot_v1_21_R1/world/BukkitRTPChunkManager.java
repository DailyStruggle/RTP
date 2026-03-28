package io.github.dailystruggle.rtp.spigot_v1_21_R1.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

public class BukkitRTPChunkManager implements RTPChunkManager {
    @Override
    public CompletableFuture<RTPChunk<?>> getChunkAtAsync( RTPWorld<?> world, int x, int z ) {
        if ( !(world instanceof BukkitRTPWorld) ) {
            return CompletableFuture.failedFuture( new IllegalArgumentException("World is not a BukkitRTPWorld") );
        }

        World bukkitWorld = ((BukkitRTPWorld) world).world();
        
        return PaperLib.getChunkAtAsync( bukkitWorld, x, z ).thenApply( BukkitRTPChunk::new );
    }
}
