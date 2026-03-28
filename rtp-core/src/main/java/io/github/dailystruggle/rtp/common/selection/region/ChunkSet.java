package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.world.RTPChunk;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A set of chunks that are being loaded
 */
public final class ChunkSet {
    /**
     * List of futures for the chunk keys in the set
     */
    public final List<CompletableFuture<Long>> chunks;

    /**
     * Future that completes when all chunks are loaded
     */
    public final CompletableFuture<Boolean> complete;

    /**
     * Constructor for ChunkSet
     * @param chunks the list of futures for the chunks
     * @param complete the future that completes when all chunks are loaded
     */
    public ChunkSet( List<CompletableFuture<Long>> chunks, CompletableFuture<Boolean> complete ) {
        this.chunks = chunks;
        this.complete = complete;

        AtomicLong count = new AtomicLong();
        Semaphore countAccess = new Semaphore( 1 );
        chunks.forEach( rtpChunkCompletableFuture -> rtpChunkCompletableFuture.thenAccept( rtpChunk -> {
            try {
                countAccess.acquire();
                long i = count.incrementAndGet();
                if ( i == chunks.size() ) {
                    this.complete.complete( true );
                }
            } catch ( InterruptedException e ) {
                RTP.log( Level.WARNING, e.getMessage(), e );
            } finally {
                countAccess.release();
            }
        }) );
    }

    /**
     * Set whether the chunks in the set should be kept loaded
     * @param keep true to keep loaded, false otherwise
     */
    public void keep( boolean keep, RTPWorld<?> world ) {
        chunks.forEach( chunk -> {
            if ( chunk.isDone() ) {
                try {
                    Long key = chunk.get();
                    RTPChunk<?> rtpChunk = world.getCachedChunk( key );
                    if( rtpChunk != null ) rtpChunk.keep( keep );
                } catch ( InterruptedException | ExecutionException e ) {
                    RTP.log( Level.WARNING, e.getMessage(), e );
                }
            } else {
                chunk.thenAccept( key -> {
                    RTPChunk<?> rtpChunk = world.getCachedChunk( key );
                    if( rtpChunk != null ) rtpChunk.keep( keep );
                });
            }
        } );
    }

    /**
     * Perform an action when all chunks are loaded
     * @param consumer the action to perform
     */
    public void whenComplete( Consumer<Boolean> consumer ) {
        if ( complete.isDone() ) {
            try {
                consumer.accept( complete.get() );
            } catch ( InterruptedException | ExecutionException e ) {
                RTP.log( Level.WARNING, e.getMessage(), e );
            }
            return;
        }

        complete.thenAccept( consumer );
    }
}


