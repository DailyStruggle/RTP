package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPBlock;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.spigot.world.BukkitRTPBlock;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

public final class FoliaRTPChunk extends RTPChunk<Chunk> {
    public FoliaRTPChunk( Chunk chunk ) {
        super( chunk );
    }

    @Override
    public int x() {
        return chunk.getX();
    }

    @Override
    public int z() {
        return chunk.getZ();
    }

    @Override
    public RTPWorld<?> getWorld() {
        return new FoliaRTPWorld(chunk.getWorld());
    }

    @Override
    public boolean isGenerated() {
        return chunk.getWorld().isChunkGenerated( chunk.getX(), chunk.getZ() );
    }

    @Override
    public void keep( boolean keep ) {
        chunk.getWorld().setChunkForceLoaded( chunk.getX(), chunk.getZ(), keep );
    }

    @Override
    public RTPBlock<?> getBlockAt( int x, int y, int z ) {
        return new BukkitRTPBlock( chunk.getBlock( x & 0xF, y, z & 0xF ) );
    }

    @Override
    public void unload() {
        Bukkit.getGlobalRegionScheduler().run(Bukkit.getPluginManager().getPlugin("RTP"), scheduledTask -> chunk.unload(false));
    }
}
