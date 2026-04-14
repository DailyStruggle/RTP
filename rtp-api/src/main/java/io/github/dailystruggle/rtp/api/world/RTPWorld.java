package io.github.dailystruggle.rtp.api.world;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Class representing a world in the server */
public abstract class RTPWorld<T> {
  protected final T world;

  public final AtomicLong activeChunkTickets = new AtomicLong(0);
  public final AtomicLong totalChunkLoads = new AtomicLong(0);
  protected final Map<Long, AtomicInteger> chunkTickets = new ConcurrentHashMap<>();

  protected RTPWorld(T world) {
    this.world = world;
  }

  public T world() {
    return world;
  }

  /**
   * Get the name of the world
   *
   * @return the name
   */
  public abstract String name();

  /**
   * Get the UUID of the world
   *
   * @return the UUID
   */
  public abstract UUID id();

  /**
   * Get the chunk at the specified coordinates asynchronously
   *
   * @param chunkX the x coordinate of the chunk
   * @param chunkZ the z coordinate of the chunk
   * @return a future that completes with the chunk key
   */
  public abstract CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ);

  /**
   * Get the chunk at the specified coordinates asynchronously
   *
   * @param cx the x coordinate of the chunk
   * @param cz the z coordinate of the chunk
   * @return a future that completes with the chunk set
   */
  public abstract CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz);

  /**
   * Set the force-loaded state of a chunk
   *
   * @param cx the x coordinate of the chunk
   * @param cz the z coordinate of the chunk
   * @param forceLoad true to force-load, false otherwise
   */
  public final void setForceLoaded(int cx, int cz, boolean forceLoad) {
    long key = ((long) cx & 0xffffffffL | ((long) cz << 32));
    if (forceLoad) {
      activeChunkTickets.incrementAndGet();
      chunkTickets.compute(key, (k, v) -> {
        if (v == null) {
          setForceLoadedImpl(cx, cz, true);
          return new AtomicInteger(1);
        }
        v.incrementAndGet();
        return v;
      });
    } else {
      chunkTickets.compute(key, (k, v) -> {
        if (v == null) return null;
        activeChunkTickets.decrementAndGet();
        if (v.decrementAndGet() <= 0) {
          setForceLoadedImpl(cx, cz, false);
          return null;
        }
        return v;
      });
    }
  }

  /**
   * Internal implementation for setting force-loaded state
   *
   * @param cx the x coordinate of the chunk
   * @param cz the z coordinate of the chunk
   * @param forceLoad true to force-load, false otherwise
   */
  protected abstract void setForceLoadedImpl(int cx, int cz, boolean forceLoad);

  /**
   * Get the number of chunks currently force-loaded by the server
   *
   * @return the number of force-loaded chunks
   */
  public abstract long getServerForceLoadedCount();

  /**
   * Get a cached chunk by its key
   *
   * @param key the chunk key
   * @return the chunk, or null if not cached
   */
  public abstract RTPChunk<?> getCachedChunk(long key);

  /**
   * Get the number of chunks currently force-loaded by the plugin
   *
   * @return the number of force-loaded chunks
   */
  public final long numForceLoaded() {
    return chunkTickets.size();
  }

  /**
   * Keep a chunk loaded
   *
   * @param chunkX the x coordinate of the chunk
   * @param chunkZ the z coordinate of the chunk
   */
  public abstract void keepChunkAt(int chunkX, int chunkZ);

  /**
   * Forget a chunk and allow it to be unloaded
   *
   * @param chunkX the x coordinate of the chunk
   * @param chunkZ the z coordinate of the chunk
   */
  public abstract void forgetChunkAt(int chunkX, int chunkZ);

  /** Forget all chunks and allow them to be unloaded */
  public abstract void forgetChunks();

  /**
   * Get the name of the biome at the specified coordinates
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   * @return the biome name
   */
  public abstract String getBiome(int x, int y, int z);

  /**
   * Create a platform at the specified location if necessary
   *
   * @param location the location
   */
  public abstract void platform(RTPLocation location);

  /**
   * Check if the world is inactive
   *
   * @return true if inactive, false otherwise
   */
  public abstract boolean isInactive();

  /**
   * Check if the world is active
   *
   * @return true if active, false otherwise
   */
  public boolean isActive() {
    return !isInactive();
  }

    /** Save world data */
  public abstract void save();

  /**
   * Get the maximum height of the world
   *
   * @return the maximum height
   */
  public abstract int getMaxHeight();

  /**
   * Get the minimum height of the world
   *
   * @return the minimum height
   */
  public abstract int getMinHeight();

  /**
   * Get the number of chunks currently held in the cache
   *
   * @return the cache size
   */
  public abstract int getCacheSize();

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    RTPWorld<?> that = (RTPWorld<?>) obj;
    return Objects.equals(this.world, that.world);
  }

  @Override
  public int hashCode() {
    return Objects.hash(world);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + "world=" + world + ']';
  }
}
