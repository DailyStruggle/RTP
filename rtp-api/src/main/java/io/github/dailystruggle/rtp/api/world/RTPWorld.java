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
  /**
   * Lifetime count of live chunk-load attempts this world has dispatched to the
   * native chunk system. Each call into the platform adapter's <em>live-load</em>
   * path increments this counter exactly once, regardless of whether the caller
   * entered via {@code getChunkAt}, {@code getChunkAtAsync}, or
   * {@link #getOrLoadChunk(int, int)} (which composes both).
   *
   * <p><strong>Excluded</strong>:</p>
   * <ul>
   *   <li>The ADR-016 anvil pre-filter probe path — it reads the on-disk region
   *       file and never asks the chunk system to load anything.</li>
   *   <li>Probe-cache hits in {@code getOrLoadChunk}, which return the cached
   *       anvil-backed view without a live load.</li>
   *   <li>Kept-cache replays — chunks already pinned by a previous load are
   *       reused, not re-loaded.</li>
   * </ul>
   *
   * <p>This is the value surfaced by the {@code [loads]} placeholder /
   * {@code infoTotalLoads} message in {@code /rtp info}, where operators
   * expect "the chunk system loaded N chunks for us" semantics.</p>
   */
  public final AtomicLong totalChunkLoads = new AtomicLong(0);
  /**
   * Lifetime count of chunk tickets observed by the {@link #releaseOrphanedTickets(Set)} GC
   * sweep that were not present in the supplied keep-alive set. Each such observation
   * indicates a ticket the plugin was holding without a matching kept-cache / per-player-
   * queue / in-flight-teleport entry — i.e. a defensively-detected leak. Used by the
   * {@code leakRate} placeholder to report the cumulative leak ratio against
   * {@link #lifetimeTicketsIssued} (the number of chunk tickets we have ever issued).
   */
  public final AtomicLong lifetimeOrphanedTicketsScanned = new AtomicLong(0);
  /**
   * Lifetime count of chunk tickets ever issued by this world via
   * {@link #setForceLoaded(int, int, boolean)} with {@code forceLoad=true}. Counts every
   * acquire (including ref-counted increments on an already-ticketed chunk), so this
   * represents the cumulative number of chunks-with-tickets the plugin has produced —
   * the correct divisor for the {@code leakRate} placeholder, distinct from
   * {@link #totalChunkLoads} which counts only live chunk-load attempts (and would
   * undercount tickets ref-counted onto already-loaded chunks).
   */
  public final AtomicLong lifetimeTicketsIssued = new AtomicLong(0);
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
   * Tracks the latest {@code setForceLoadedImpl(true)} application future per chunk key so
   * ref-counted no-op callers can still await the actual ticket application rather than
   * seeing a stale "ready" signal.
   *
   * <p>Motivation (ADR-015 Paper chunk-system-v2 follow-up): on the Bukkit/Paper adapter the
   * raw {@code addPluginChunkTicket} call is scheduled onto the primary thread via
   * {@code runTask} when invoked off-thread. Callers running on
   * {@code Craft Scheduler Thread - 1 - RTP} (the pregen / location-generator path) would
   * otherwise return from {@code setForceLoaded(true)} before the ticket was actually applied,
   * re-opening the exact race the stale-chunk guard (REQ-RTP-S-005) exists to detect and
   * causing every candidate to be falsely rejected on Paper chunk-system-v2.</p>
   */
  protected final Map<Long, CompletableFuture<Void>> ticketApplyFutures = new ConcurrentHashMap<>();

  /**
   * Sets the force-loaded state of a chunk using a reference-counting system.
   *
   * <p>Returns a {@link CompletableFuture} that completes when the underlying platform call
   * ({@code addPluginChunkTicket} / {@code removePluginChunkTicket}) has actually been applied.
   * Callers on off-thread contexts (e.g. the location generator running on an async scheduler
   * thread) must await this future before relying on {@code isChunkLoaded} or treating the
   * chunk as pinned.</p>
   *
   * <p>Ref-counted no-op invocations (i.e. {@code setForceLoaded(true)} on a chunk whose
   * count is already &gt;0) return the in-flight apply future for the original call, so a
   * second caller cannot bypass the ticket-application wait by incrementing past a still-
   * pending first application.</p>
   *
   * @param cx        the chunk's X coordinate
   * @param cz        the chunk's Z coordinate
   * @param forceLoad {@code true} to increment the force-load count, {@code false} to decrement
   * @return a future that completes when the platform call has actually been applied
   */
  public final CompletableFuture<Void> setForceLoaded(int cx, int cz, boolean forceLoad) {
    long key = ((long) cx & 0xffffffffL | ((long) cz << 32));
    final CompletableFuture<Void>[] captured = new CompletableFuture[]{null};
    if (forceLoad) {
      activeChunkTickets.incrementAndGet();
      lifetimeTicketsIssued.incrementAndGet();
      chunkTickets.compute(key, (k, v) -> {
        if (v == null) {
          CompletableFuture<Void> f = setForceLoadedImpl(cx, cz, true);
          if (f == null) f = CompletableFuture.completedFuture(null);
          ticketApplyFutures.put(key, f);
          captured[0] = f;
          return new AtomicInteger(1);
        }
        v.incrementAndGet();
        // Return the in-flight apply future so subsequent callers wait for the
        // original addPluginChunkTicket to actually land before proceeding.
        CompletableFuture<Void> f = ticketApplyFutures.get(key);
        captured[0] = (f != null) ? f : CompletableFuture.completedFuture(null);
        return v;
      });
    } else {
      chunkTickets.compute(key, (k, v) -> {
        if (v == null) {
          captured[0] = CompletableFuture.completedFuture(null);
          return null;
        }
        activeChunkTickets.decrementAndGet();
        if (v.decrementAndGet() <= 0) {
          CompletableFuture<Void> f = setForceLoadedImpl(cx, cz, false);
          ticketApplyFutures.remove(key);
          captured[0] = (f != null) ? f : CompletableFuture.completedFuture(null);
          return null;
        }
        captured[0] = CompletableFuture.completedFuture(null);
        return v;
      });
    }
    return captured[0];
  }

  /**
   * The platform-specific implementation for setting the force-loaded state of a chunk.
   *
   * <p>Implementations MUST return a {@link CompletableFuture} that completes only after the
   * native {@code addPluginChunkTicket} / {@code removePluginChunkTicket} call has executed
   * on the appropriate scheduler. On platforms where the call is synchronous on the current
   * thread, return a completed future; on platforms where the call is scheduled onto the
   * primary/region thread, complete the future inside the scheduled lambda (ADR-015 Paper
   * follow-up: ticket-application race).</p>
   *
   * @param cx        the chunk's X coordinate
   * @param cz        the chunk's Z coordinate
   * @param forceLoad {@code true} to force-load, {@code false} to un-force-load
   * @return a future that completes when the platform call has been applied
   */
  protected abstract CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad);

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
   * Asynchronously produces a lean {@link ChunkColumnProbe} for the center column of
   * chunk {@code (cx, cz)} over the world-Y window {@code [minY, maxY]}, used by
   * {@code PregenTask} as a probe-first fast path before falling back to the
   * authoritative full-chunk load.
   *
   * <p>The default returns {@code completedFuture(null)} — "no probe available",
   * which instructs callers to skip the fast path and resolve the chunk the normal
   * way (ADR-016 §13.1 precedence chain). Platform adapters that can cheaply
   * answer a center-column probe (currently: Bukkit-family worlds with an
   * {@code .mca}-backed chunk store) SHOULD override to return a real probe.</p>
   *
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   * @param minY inclusive minimum world-Y the caller cares about
   * @param maxY inclusive maximum world-Y the caller cares about
   * @return a future completing with a probe, or {@code null} if no fast path is
   *     available (caller falls back to the authoritative path).
   */
  public CompletableFuture<ChunkColumnProbe> probeChunkColumn(
      int cx, int cz, int minY, int maxY) {
    return CompletableFuture.completedFuture(null);
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
    if (!orphaned.isEmpty()) {
      lifetimeOrphanedTicketsScanned.addAndGet(orphaned.size());
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
