package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

import java.util.Objects;

public final class BukkitRTPChunk implements RTPChunk {
    private final Chunk chunk;

    public BukkitRTPChunk( Chunk chunk ) {
        this.chunk = chunk;
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
    public RTPBlock getBlockAt( int x, int y, int z ) {
        x = x % 16;
        z = z % 16;
        if ( x < 0 ) x += 16;
        if ( z < 0 ) z += 16;
        return new BukkitRTPBlock( chunk.getBlock( x, y, z) );
    }

    @Override
    public RTPBlock getBlockAt( RTPLocation location ) {
        return new BukkitRTPBlock( chunk.getBlock( location.x() % 16, location.y(), location.z() % 16) );
    }

    @Override
    public RTPWorld getWorld() {
        return new BukkitRTPWorld( chunk.getWorld() );
    }

    @Override
    public void keep( boolean keep ) {
        RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld( chunk.getWorld().getUID() );

        if ( keep ) {
            rtpWorld.keepChunkAt( x(), z() );
        } else {
            rtpWorld.forgetChunkAt( x(), z() );
        }
    }

    @Override
    public void unload() {
        if ( Bukkit.isPrimaryThread() ) chunk.unload( false );
        else Bukkit.getScheduler().runTask( RTPBukkitPlugin.getInstance(), () -> chunk.unload( false) );
    }

    public Chunk chunk() {
        return chunk;
    }

    @Override
    public boolean equals( Object obj ) {
        if ( obj == this ) return true;
        if ( obj == null || obj.getClass() != this.getClass() ) return false;
        BukkitRTPChunk that = ( BukkitRTPChunk ) obj;
        return Objects.equals( this.chunk, that.chunk );
    }

    @Override
    public int hashCode() {
        return Objects.hash( chunk );
    }

    @Override
    public String toString() {
        return "BukkitRTPChunk[" +
                "chunk=" + chunk + ']';
    }

}
