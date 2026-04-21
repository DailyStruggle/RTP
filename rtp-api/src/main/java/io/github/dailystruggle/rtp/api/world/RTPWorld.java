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
   * Resolve an {@link RTPChunk} for {@code (cx, cz)}, loading or probing the chunk
   * on demand when it is not already cached.
   *
   * <p>Contract (ADR-016 §13.1 follow-up, 2026-04-20):</p>
   * <ol>
   *   <li>If an anvil-backed or live-backed entry is already cached for this key,
   *       return it without I/O.</li>
   *   <li>Otherwise run the probe-first path ({@link #getChunkAt(int, int)}) to
   *       populate the anvil cache; on success, return an anvil-backed chunk.</li>
   *   <li>Otherwise fall back to a live chunk load via
   *       {@link #getChunkAtAsync(int, int)} and return a live-backed chunk.</li>
   * </ol>
   *
   * <p>The default implementation composes the existing primitives and works on
   * every adapter. Platform adapters MAY override to skip redundant work.</p>
   *
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   * @return a future that completes with an {@link RTPChunk}, or {@code null} on
   *         unrecoverable load failure
   */
  public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz) {
    final long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
    RTPChunk<?> cached = getCachedChunk(key);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }
    // Probe-first: populate anvil cache when applicable.
    return getChunkAt(cx, cz).thenCompose(probeKey -> {
      RTPChunk<?> afterProbe = (probeKey != null) ? getCachedChunk(probeKey) : null;
      if (afterProbe != null) {
        return CompletableFuture.completedFuture(afterProbe);
      }
      // Fall back to live load.
      return getChunkAtAsync(cx, cz).thenApply(chunkSet -> {
        if (chunkSet == null) return null;
        return getCachedChunk(key);
      });
    });
  }

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
   * Reports whether this world is generated by the unmodified vanilla Minecraft generator
   * and hosts only vanilla-namespace biomes.
   *
   * <p>ADR-016 §13.3 — the "vanilla-generator exemption" that gates whether the selection
   * pipeline may fall back to the live {@code world.getBiome(x, y, z)} getter (synthesised
   * from the world seed) for ungenerated chunks. On non-vanilla worlds (Iris, Terra,
   * datapack presets, mod-installed generators) the seed-based answer does not match the
   * palette the player will actually see once the chunk is populated, and falling back
   * to it causes the biome allow-list to produce false positives/negatives. Returning
   * {@code false} instructs {@code LocationGenerator} to skip its pre-chunk-load biome
   * pre-check and defer to the post-load biome read, which is routed through the
   * §13.1 chunk-data precedence chain (loaded chunk → AnvilChunkView → live getter).</p>
   *
   * <p>The default is {@code false} (conservative — assume non-vanilla unless an adapter
   * can positively attest to vanilla generation).</p>
   *
   * @return {@code true} if and only if this world uses the vanilla generator and biome
   *     source; {@code false} otherwise (including when detection is unavailable).
   */
  public boolean isVanilla() {
    return false;
  }

  /**
   * Non-blocking check for whether the chunk at the specified coordinates has already
   * been generated and persisted to disk (i.e. an {@code .mca} entry exists for it).
   * Implementations MUST NOT trigger generation or block the calling thread.
   *
   * <p>ADR-016 §13.3 — the "vanilla-generator exemption" originally allowed the
   * pre-chunk-load biome check to fall back to the seed-synthesised
   * {@code world.getBiome(x,y,z)} on vanilla worlds. That is still wrong when the
   * chunk has already been generated by a previous (possibly older-MC-version) session,
   * because Mojang's biome source can drift across version upgrades and the persisted
   * {@code .mca} palette is the source of truth. The pre-check is therefore additionally
   * gated on {@code !isChunkGenerated(cx,cz)} — even on vanilla worlds, generated chunks
   * defer to the §13.1 chunk-data precedence chain (loaded chunk → AnvilChunkView →
   * live getter) via the post-load biome read.</p>
   *
   * <p>The default returns {@code true} (conservative — assume generated, skip the
   * pre-check) to preserve correctness on adapters that cannot answer the question.
   * Platform adapters SHOULD override to delegate to the native non-blocking lookup
   * (e.g. {@code org.bukkit.World#isChunkGenerated(int,int)}).</p>
   *
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   * @return {@code true} if the chunk has been generated (or if detection is unavailable),
   *     {@code false} only when the adapter can positively attest that the chunk is
   *     ungenerated.
   */
  public boolean isChunkGenerated(int cx, int cz) {
    return true;
  }

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
