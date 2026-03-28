package io.github.dailystruggle.rtp.spigot.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPBlock;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

public final class BukkitRTPChunk extends RTPChunk<Chunk> {
    public BukkitRTPChunk( Chunk chunk ) {
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
        return new BukkitRTPWorld(chunk.getWorld());
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
        if ( Bukkit.isPrimaryThread() ) chunk.unload( false );
        else {
            Bukkit.getScheduler().runTask( Bukkit.getPluginManager().getPlugin("RTP"), () -> chunk.unload( false) );
        }
    }
}
