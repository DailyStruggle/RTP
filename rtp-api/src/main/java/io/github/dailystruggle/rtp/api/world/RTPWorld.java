package io.github.dailystruggle.rtp.api.world;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Platform-agnostic representation of a world.
 *
 * <p>This abstract class provides a common interface for interacting with worlds
 * across different server implementations. It encapsulates a platform-specific
 * world object and delegates method calls to it. It also manages chunk tickets
 * to keep chunks loaded during asynchronous operations.
 *
 * @param <T> the type of the underlying platform-specific world object
 */
public abstract class RTPWorld<T> {
  protected final T world;

  public final AtomicLong activeChunkTickets = new AtomicLong(0);
  public final AtomicLong totalChunkLoads = new AtomicLong(0);
  protected final Map<Long, AtomicInteger> chunkTickets = new ConcurrentHashMap<>();

  protected RTPWorld(T world) {
    this.world = world;
  }

  /**
   * Returns the underlying platform-specific world object.
   *
   * @return the platform world object
   */
  public T world() {
    return world;
  }

  /**
   * Returns the name of this world.
   *
   * @return the world's name
   */
  public abstract String name();

  /**
   * Returns the unique identifier of this world.
   *
   * @return the world's UUID
   */
  public abstract UUID id();

  /**
   * Asynchronously retrieves the chunk at the specified coordinates.
   *
   * @param chunkX the chunk's X coordinate
   * @param chunkZ the chunk's Z coordinate
   * @return a {@link CompletableFuture} that completes with a long representing the chunk key
   */
  public abstract CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ);

  /**
   * Asynchronously retrieves a set of chunks centered at the specified coordinates.
   *
   * @param cx the center chunk's X coordinate
   * @param cz the center chunk's Z coordinate
   * @return a {@link CompletableFuture} that completes with a {@link ChunkSet}
   */
  public abstract CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz);

  /**
   * Non-blocking check for whether the chunk at the specified coordinates is currently loaded
   * on the native server. Implementations MUST NOT trigger a chunk load or block the calling
   * thread; this call is used as a stale-chunk guard between an async chunk-load future
   * resolution and the subsequent block-evaluation task being executed on a Count-Bound
   * task pipe (see ADR-015 — Stale-Chunk Guard for Count-Bound Pipes).
   *
   * <p>The default returns {@code true} to preserve legacy behavior on adapters that have
   * not yet overridden this contract; callers therefore treat "unknown" as "assume loaded".
   * Platform adapters (Folia, Paper, Spigot) SHOULD override to query the native
   * {@code World#isChunkLoaded(int,int)} (or equivalent non-loading lookup).</p>
   *
   * @param cx the x coordinate of the chunk
   * @param cz the z coordinate of the chunk
   * @return {@code true} if the chunk is currently loaded on the native server,
   *         {@code false} if it has been unloaded (e.g. by Folia native chunk GC)
   */
  public boolean isChunkLoaded(int cx, int cz) {
    return true;
  }

  /**
   * Sets the force-loaded state of a chunk using a reference-counting system.
   *
   * @param cx        the chunk's X coordinate
   * @param cz        the chunk's Z coordinate
   * @param forceLoad {@code true} to increment the force-load count, {@code false} to decrement
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
   * The platform-specific implementation for setting the force-loaded state of a chunk.
   *
   * @param cx        the chunk's X coordinate
   * @param cz        the chunk's Z coordinate
   * @param forceLoad {@code true} to force-load, {@code false} to un-force-load
   */
  protected abstract void setForceLoadedImpl(int cx, int cz, boolean forceLoad);

  /**
   * Re-applies the force-loaded state to a chunk without changing its ticket count.
   *
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   */
  public final void refreshForceLoaded(int cx, int cz) {
    setForceLoadedImpl(cx, cz, true);
  }

  /**
   * Asynchronously retrieves the number of chunks currently force-loaded by the server.
   *
   * @return a {@link CompletableFuture} that completes with the number of force-loaded chunks
   */
  public abstract CompletableFuture<Integer> getServerForceLoadedCount();

  /**
   * Retrieves a cached {@link RTPChunk} by its key.
   *
   * @param key the chunk key
   * @return the cached chunk, or {@code null} if not found
   */
  public abstract RTPChunk<?> getCachedChunk(long key);

  /**
   * Returns the number of chunks currently force-loaded by the plugin in this world.
   *
   * @return the number of force-loaded chunks
   */
  public final long numForceLoaded() {
    return chunkTickets.size();
  }

  /**
   * Keeps the chunk at the specified coordinates loaded.
   *
   * @param chunkX the chunk's X coordinate
   * @param chunkZ the chunk's Z coordinate
   */
  public abstract void keepChunkAt(int chunkX, int chunkZ);

  /**
   * Allows the chunk at the specified coordinates to be unloaded.
   *
   * @param chunkX the chunk's X coordinate
   * @param chunkZ the chunk's Z coordinate
   */
  public abstract void forgetChunkAt(int chunkX, int chunkZ);

  /**
   * Allows all chunks in this world that were kept loaded by the plugin to be unloaded.
   */
  public abstract void forgetChunks();

  /**
   * Returns the name of the biome at the specified block coordinates.
   *
   * @param x the block's X coordinate
   * @param y the block's Y coordinate
   * @param z the block's Z coordinate
   * @return the biome name
   */
  public abstract String getBiome(int x, int y, int z);

  /**
   * Creates a platform at the specified location if necessary to ensure it is safe.
   *
   * @param location the location to create a platform at
   */
  public abstract void platform(RTPLocation location);

  /**
   * Checks if this world is considered inactive (e.g., has no players).
   *
   * @return {@code true} if the world is inactive, {@code false} otherwise
   */
  public abstract boolean isInactive();

  /**
   * Checks if this world is considered active.
   *
   * @return {@code true} if the world is active, {@code false} otherwise
   */
  public boolean isActive() {
    return !isInactive();
  }

  /**
   * Saves this world's data.
   */
  public abstract void save();

  /**
   * Returns the maximum build height of this world.
   *
   * @return the maximum height
   */
  public abstract int getMaxHeight();

  /**
   * Returns the minimum build height of this world.
   *
   * @return the minimum height
   */
  public abstract int getMinHeight();

  /**
   * Release any chunk tickets whose keys are not present in the provided keep-alive set.
   *
   * <p>This is called by the MemoryTracker GC sweep to reclaim orphaned tickets that were
   * never released because their owning reservation was abandoned (e.g. a location was
   * evicted from the kept-locations cache without closing its ChunkReservation).
   *
   * @param keepAliveKeys the set of chunk keys that must NOT be released
   */
  public final void releaseOrphanedTickets(Set<Long> keepAliveKeys) {
    // Snapshot the keys to avoid ConcurrentModificationException during removal
    java.util.List<Long> orphaned = new java.util.ArrayList<>();
    for (Long key : chunkTickets.keySet()) {
      if (!keepAliveKeys.contains(key)) {
        orphaned.add(key);
      }
    }
    for (Long key : orphaned) {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >>> 32);
      // Drain all ticket counts for this key by calling setForceLoaded(false) until removed
      AtomicInteger count = chunkTickets.get(key);
      if (count != null) {
        int times = count.get();
        for (int i = 0; i < times; i++) {
          setForceLoaded(cx, cz, false);
        }
      }
    }
  }

  /**
   * Returns the number of chunks currently held in the cache.
   *
   * @return the cache size
   */
  public abstract int getCacheSize();

  /**
   * Returns the seed of this world.
   *
   * @return the world's seed
   */
  public abstract long getSeed();

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
