package io.github.dailystruggle.rtp.api.world;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Class representing a world in the server
 */
public abstract class RTPWorld<T> {
    protected final T world;

    protected RTPWorld( T world ) {
        this.world = world;
    }

    public T world() {
        return world;
    }

    /**
     * Get the name of the world
     * @return the name
     */
    public abstract String name();

    /**
     * Get the UUID of the world
     * @return the UUID
     */
    public abstract UUID id();

    /**
     * Get the chunk at the specified coordinates asynchronously
     * @param chunkX the x coordinate of the chunk
     * @param chunkZ the z coordinate of the chunk
     * @return a future that completes with the chunk key
     */
    public abstract CompletableFuture<Long> getChunkAt( int chunkX, int chunkZ );

    /**
     * Get a cached chunk by its key
     * @param key the chunk key
     * @return the chunk, or null if not cached
     */
    public abstract RTPChunk<?> getCachedChunk( long key );

    /**
     * Keep a chunk loaded
     * @param chunkX the x coordinate of the chunk
     * @param chunkZ the z coordinate of the chunk
     */
    public abstract void keepChunkAt( int chunkX, int chunkZ );

    /**
     * Forget a chunk and allow it to be unloaded
     * @param chunkX the x coordinate of the chunk
     * @param chunkZ the z coordinate of the chunk
     */
    public abstract void forgetChunkAt( int chunkX, int chunkZ );

    /**
     * Forget all chunks and allow them to be unloaded
     */
    public abstract void forgetChunks();

    /**
     * Get the name of the biome at the specified coordinates
     * @param x the x coordinate
     * @param y the y coordinate
     * @param z the z coordinate
     * @return the biome name
     */
    public abstract String getBiome( int x, int y, int z );

    /**
     * Create a platform at the specified location if necessary
     * @param location the location
     */
    public abstract void platform( RTPLocation location );

    /**
     * Check if the world is inactive
     * @return true if inactive, false otherwise
     */
    public abstract boolean isInactive();

    /**
     * Check if the world is active
     * @return true if active, false otherwise
     */
    public boolean isActive()
    {
        return !isInactive();
    }

    /**
     * Check if a chunk is force loaded
     * @param cx the x coordinate of the chunk
     * @param cz the z coordinate of the chunk
     * @return true if force loaded, false otherwise
     */
    public abstract boolean isForceLoaded( int cx, int cz );

    /**
     * Save world data
     */
    public abstract void save();

    /**
     * Get the maximum height of the world
     * @return the maximum height
     */
    public abstract int getMaxHeight();

    /**
     * Get the minimum height of the world
     * @return the minimum height
     */
    public abstract int getMinHeight();

    @Override
    public boolean equals( Object obj ) {
        if ( obj == this ) return true;
        if ( obj == null || obj.getClass() != this.getClass() ) return false;
        RTPWorld<?> that = ( RTPWorld<?> ) obj;
        return Objects.equals( this.world, that.world );
    }

    @Override
    public int hashCode() {
        return Objects.hash( world );
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" +
                "world=" + world + ']';
    }
}

