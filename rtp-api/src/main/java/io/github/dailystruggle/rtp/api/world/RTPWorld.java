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
 * Platform-agnostic world wrapper. Wraps a native world object {@code T}, delegates
 * world operations to it, and ref-counts chunk tickets for async pipelines.
 *
 * @param <T> underlying platform world type
 */
public abstract class RTPWorld<T> {
  protected final T world;

  public final AtomicLong activeChunkTickets = new AtomicLong(0);
  /**
   * Lifetime count of live chunk-load attempts dispatched to the native chunk system.
   * Incremented once per live-load entry ({@code getChunkAt}, {@code getChunkAtAsync},
   * {@link #getOrLoadChunk(int, int)}). Excludes ADR-016 anvil probes, probe-cache hits,
   * and kept-cache replays. Surfaced as {@code [loads]} / {@code infoTotalLoads} in
   * {@code /rtp info}.
   */
  public final AtomicLong totalChunkLoads = new AtomicLong(0);
  /**
   * Lifetime count of orphaned tickets caught by {@link #releaseOrphanedTickets(Set)} -
   * tickets held without a matching kept-cache / per-player-queue / in-flight entry.
   * Numerator of the {@code leakRate} placeholder; denominator is
   * {@link #lifetimeTicketsIssued}.
   */
  public final AtomicLong lifetimeOrphanedTicketsScanned = new AtomicLong(0);
  /**
   * Lifetime count of ticket acquires via {@link #setForceLoaded(int, int, boolean)} with
   * {@code forceLoad=true} - including ref-counted increments on already-ticketed chunks.
   * Correct denominator for {@code leakRate}; distinct from {@link #totalChunkLoads},
   * which counts only live loads and would undercount ref-counted acquires.
   */
  public final AtomicLong lifetimeTicketsIssued = new AtomicLong(0);
  /**
   * Per-origin breakdown of chunk-load requests routed through
   * {@link #getOrLoadChunk(int, int, String)}. Incremented once per call to attribute
   * climbs in {@link #totalChunkLoads} to a call site. The 2-arg
   * {@link #getOrLoadChunk(int, int)} accrues under {@code "unknown"}.
   */
  public final Map<String, AtomicLong> chunkLoadsByOrigin = new ConcurrentHashMap<>();
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
   * Non-blocking stale-chunk guard (ADR-015): is this chunk currently loaded? Must not
   * trigger a load or block. Default returns {@code true} ("assume loaded") for adapter
   * back-compat; platform adapters SHOULD override with the native non-loading lookup
   * (e.g. {@code World#isChunkLoaded}).
   */
  public boolean isChunkLoaded(int cx, int cz) {
    return true;
  }

  /**
   * Per-chunk in-flight ticket-apply future, so ref-counted no-op callers wait for the
   * actual {@code addPluginChunkTicket} to land instead of seeing a premature "ready".
   * Closes the Paper chunk-system-v2 race that REQ-RTP-S-005 / ADR-015 detect.
   */
  protected final Map<Long, CompletableFuture<Void>> ticketApplyFutures = new ConcurrentHashMap<>();

  /**
   * Ref-counted force-load toggle. The returned future completes when the native
   * {@code addPluginChunkTicket} / {@code removePluginChunkTicket} actually lands; off-thread
   * callers MUST await it before relying on {@code isChunkLoaded} or treating the chunk as
   * pinned. Ref-counted no-ops return the original in-flight apply future, so a second caller
   * cannot race past a still-pending first application.
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
   * Adapter hook for {@link #setForceLoaded}. The returned future must complete only after
   * the native ticket call has executed (ADR-015).
   *
   * @param cx        the chunk's X coordinate
   * @param cz        the chunk's Z coordinate
   * @param forceLoad {@code true} to force-load, {@code false} to un-force-load
   * @return future completed when the platform call has been applied
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
   * Tagged variant of {@link #getOrLoadChunk(int, int)}: records the call site under
   * {@link #chunkLoadsByOrigin} for diagnostic attribution, then delegates to the 2-arg
   * implementation.
   *
   * @param cx     chunk X
   * @param cz     chunk Z
   * @param origin short stable call-site tag (non-null; use {@code "unknown"} if no tag)
   * @return same future contract as {@link #getOrLoadChunk(int, int)}
   */
  public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz, String origin) {
    // Record the origin ONLY for calls that actually fall through to a live load -
    // cache hits and probe-cache hits do not increment totalChunkLoads, so recording
    // them here would inflate chunkLoadsByOrigin past the real total. Mirrors the
    // ADR-016 section 13.1 precedence used by the 2-arg getOrLoadChunk: cached → anvil probe → live.
    final long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
    RTPChunk<?> cached = getCachedChunk(key);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }
    return getChunkAt(cx, cz).thenCompose(probeKey -> {
      RTPChunk<?> afterProbe = (probeKey != null) ? getCachedChunk(probeKey) : null;
      if (afterProbe != null) {
        return CompletableFuture.completedFuture(afterProbe);
      }
      // Live-load fallthrough: this is the path that increments totalChunkLoads,
      // so attribute the origin here.
      recordChunkLoadOrigin(origin);
      return getChunkAtAsync(cx, cz).thenApply(chunkSet -> {
        if (chunkSet == null) return null;
        return getCachedChunk(key);
      });
    });
  }

  /**
   * Records a chunk-load request under the given origin tag. Safe to call from any
   * thread; {@code null} tags are normalised to {@code "unknown"}.
   */
  public final void recordChunkLoadOrigin(String origin) {
    String tag = (origin == null) ? "unknown" : origin;
    chunkLoadsByOrigin.computeIfAbsent(tag, k -> new AtomicLong(0)).incrementAndGet();
  }

  /**
   * Resolve an {@link RTPChunk} for {@code (cx, cz)}: cached → anvil probe → live load
   * (ADR-016 section 13.1 precedence). Default composes the primitives; adapters MAY override
   * to skip redundant work. Returns {@code null} on unrecoverable load failure.
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
      // Fall back to live load. Untagged callers still attribute to "unknown" so
      // chunkLoadsByOrigin always sums to totalChunkLoads - see the 3-arg overload
      // for the recommended tagging convention.
      recordChunkLoadOrigin("unknown");
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
   * Vanilla-generator exemption flag (ADR-016 section 13.3). When {@code true}, the selection
   * pipeline may use the seed-synthesised {@code world.getBiome(x,y,z)} for ungenerated
   * chunks; otherwise it must defer to the post-load biome read (section 13.1 chain:
   * loaded chunk → AnvilChunkView → live getter). Default {@code false} (conservative -
   * Iris/Terra/datapack worlds do not match the seed answer).
   */
  public boolean isVanilla() {
    return false;
  }

  /**
   * Destination world environment as a platform-neutral string (e.g. {@code "NORMAL"},
   * {@code "NETHER"}, {@code "THE_END"}), or {@code null} when unknown. Used as a display hint.
   *
   * @return environment string, or {@code null} when unknown
   */
  public String environment() {
    return null;
  }

  /**
   * Non-blocking "is this chunk on disk?" check (ADR-016 section 13.3). Must not trigger
   * generation or block. Gates the seed-synthesised biome pre-check off for already-generated
   * chunks even on vanilla worlds - Mojang's biome source drifts across MC versions, so the
   * persisted {@code .mca} palette is authoritative. Default {@code true} (conservative);
   * adapters SHOULD override with the native lookup ({@code World#isChunkGenerated}).
   */
  public boolean isChunkGenerated(int cx, int cz) {
    return true;
  }

  /**
   * Probe-first fast path for {@code PregenTask}: a lean center-column probe over
   * world-Y {@code [minY, maxY]}, or {@code null} when the adapter has no probe (caller
   * falls back to the ADR-016 section 13.1 chain). Adapters with {@code .mca}-backed stores
   * SHOULD override.
   */
  public CompletableFuture<ChunkColumnProbe> probeChunkColumn(
      int cx, int cz, int minY, int maxY) {
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Bulk biome read over a single anvil region file ({@code r.<rcx>.<rcz>.mca}) for biome charts.
   *
   * <p>Blocking, off-tick-thread only (S-005). Never performs live chunk I/O; on-disk anvil only.
   * Returns chunk-pos-packed ({@code ((long)cx << 32) | (cz & 0xFFFF_FFFFL)}) to uppercase biome name.
   *
   * @param rcx region-file X coord ({@code cx >> 5})
   * @param rcz region-file Z coord ({@code cz >> 5})
   * @param y   world-Y at which to sample the biome
   * @return chunk-pos-packed -> biome-name map (never null; may be empty)
   */
  public Map<Long, String> readBiomesInRegionFile(int rcx, int rcz, int y) {
    return java.util.Collections.emptyMap();
  }

  /**
   * Creates a platform at the specified location if necessary to ensure it is safe.
   *
   * @param location the location to create a platform at
   */
  public abstract void platform(RTPLocation location);

  /**
   * Region-schematic paster for this platform (ADR-058).
   * Returns {@link io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster} by default (never null, S-006).
   *
   * @return active paster; never null
   */
  public io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster() {
    return io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster.INSTANCE;
  }

  /**
   * Reverses an emergency landing platform built by {@link #platform(RTPLocation)} (ADR-060).
   * Must be called on region-owning thread with loaded chunks (S-005).
   *
   * @param blocks original blocks to restore; never null
   * @return {@code true} if applied, {@code false} if unsupported or failed
   */
  public boolean restoreBlocks(java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    return false;
  }

  /**
   * Best-effort native bulk block write (ADR-058).
   * Must be called on region-owning thread with loaded chunks (S-005). Unparseable tokens are skipped (S-004).
   *
   * @param blocks block-location map to write; never null
   * @return count of blocks placed (0 if unsupported or none)
   */
  public int setBlocks(java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    return 0;
  }

  /**
   * Best-effort native restore of block-entity payloads (chest contents, etc.) for a schematic paste (ADR-058).
   * Called on region-owning thread with loaded chunks (S-005); failures are audited and never thrown (S-004).
   *
   * @param entities planned block entities to restore; never null
   * @return count of containers/entities restored
   */
  public int restoreBlockEntities(
      java.util.List<io.github.dailystruggle.rtp.api.schematic.PlacedBlockEntity> entities) {
    return 0;
  }

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
   * Release chunk tickets whose keys are absent from {@code keepAliveKeys}.
   * Called by MemoryTracker GC sweep to reclaim abandoned tickets.
   *
   * @param keepAliveKeys set of chunk keys that must not be released
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
