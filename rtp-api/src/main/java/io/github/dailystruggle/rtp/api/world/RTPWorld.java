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
   * Lifetime count of orphaned tickets caught by {@link #releaseOrphanedTickets(Set)} —
   * tickets held without a matching kept-cache / per-player-queue / in-flight entry.
   * Numerator of the {@code leakRate} placeholder; denominator is
   * {@link #lifetimeTicketsIssued}.
   */
  public final AtomicLong lifetimeOrphanedTicketsScanned = new AtomicLong(0);
  /**
   * Lifetime count of ticket acquires via {@link #setForceLoaded(int, int, boolean)} with
   * {@code forceLoad=true} — including ref-counted increments on already-ticketed chunks.
   * Correct denominator for {@code leakRate}; distinct from {@link #totalChunkLoads},
   * which counts only live loads and would undercount ref-counted acquires.
   */
  public final AtomicLong lifetimeTicketsIssued = new AtomicLong(0);
  /**
   * Per-origin breakdown of chunk-load requests routed through
   * {@link #getOrLoadChunk(int, int, String)}. Each entry is incremented once per call
   * regardless of whether the load hits cache, anvil, or live generation; sum across all
   * origins is an upper bound on (and typically equal to) the orchestration-layer load
   * volume. Use to attribute climbs in {@link #totalChunkLoads} to a specific call site
   * (e.g. {@code "QueueTask.runFullLoadAndResolve"}, {@code "ScanTask"},
   * {@code "Region.processBacklog"}). The 2-arg {@link #getOrLoadChunk(int, int)} accrues
   * under {@code "unknown"}.
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
   * Adapter hook for {@link #setForceLoaded}. The returned future MUST complete only after
   * the native ticket call has executed; if the call is scheduled onto another thread,
   * complete the future from inside that lambda (ADR-015).
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
   * Tagged variant of {@link #getOrLoadChunk(int, int)}: records the call site under
   * {@link #chunkLoadsByOrigin} for diagnostic attribution, then delegates to the 2-arg
   * implementation. Callers should pass a short stable tag identifying the source
   * (e.g. {@code "QueueTask.runFullLoadAndResolve"}, {@code "PregenTask.neighbourGrid"},
   * {@code "ScanTask"}, {@code "Region.processBacklog"},
   * {@code "RegionCacheTask.observational"}).
   *
   * @param cx     chunk X
   * @param cz     chunk Z
   * @param origin short stable call-site tag (non-null; use {@code "unknown"} if no tag)
   * @return same future contract as {@link #getOrLoadChunk(int, int)}
   */
  public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz, String origin) {
    // Record the origin ONLY for calls that actually fall through to a live load —
    // cache hits and probe-cache hits do not increment totalChunkLoads, so recording
    // them here would inflate chunkLoadsByOrigin past the real total. Mirrors the
    // ADR-016 §13.1 precedence used by the 2-arg getOrLoadChunk: cached → anvil probe → live.
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
   * (ADR-016 §13.1 precedence). Default composes the primitives; adapters MAY override
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
      // chunkLoadsByOrigin always sums to totalChunkLoads — see the 3-arg overload
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
   * Vanilla-generator exemption flag (ADR-016 §13.3). When {@code true}, the selection
   * pipeline may use the seed-synthesised {@code world.getBiome(x,y,z)} for ungenerated
   * chunks; otherwise it must defer to the post-load biome read (§13.1 chain:
   * loaded chunk → AnvilChunkView → live getter). Default {@code false} (conservative —
   * Iris/Terra/datapack worlds do not match the seed answer).
   */
  public boolean isVanilla() {
    return false;
  }

  /**
   * The destination world's environment as a platform-neutral string (e.g.
   * {@code "NORMAL"}, {@code "NETHER"}, {@code "THE_END"}, or a custom-dimension
   * name), or {@code null} when the platform cannot supply one. Used purely as a
   * display hint so a menu can pick a representative surface block locally.
   *
   * <p>Default {@code null}; adapters that can resolve their world's environment
   * ({@code BukkitRTPWorld}, {@code FoliaRTPWorld}) override this.
   *
   * @return the environment string, or {@code null} when unknown
   */
  public String environment() {
    return null;
  }

  /**
   * Non-blocking "is this chunk on disk?" check (ADR-016 §13.3). Must not trigger
   * generation or block. Gates the seed-synthesised biome pre-check off for already-generated
   * chunks even on vanilla worlds — Mojang's biome source drifts across MC versions, so the
   * persisted {@code .mca} palette is authoritative. Default {@code true} (conservative);
   * adapters SHOULD override with the native lookup ({@code World#isChunkGenerated}).
   */
  public boolean isChunkGenerated(int cx, int cz) {
    return true;
  }

  /**
   * Probe-first fast path for {@code PregenTask}: a lean center-column probe over
   * world-Y {@code [minY, maxY]}, or {@code null} when the adapter has no probe (caller
   * falls back to the ADR-016 §13.1 chain). Adapters with {@code .mca}-backed stores
   * SHOULD override.
   */
  public CompletableFuture<ChunkColumnProbe> probeChunkColumn(
      int cx, int cz, int minY, int maxY) {
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Bulk biome read over a single anvil region file ({@code r.<rcx>.<rcz>.mca}),
   * used by {@code RegionBiomesResolver} to paint a biome chart without doing
   * per-pixel chunk loads.
   *
   * <p>Returns a map keyed by chunk-pos-packed
   * ({@code ((long)cx << 32) | (cz & 0xFFFF_FFFFL)}) -> uppercase biome name as
   * stored by {@code MemoryShape.addBiomeLocation} (vanilla {@code MINECRAFT:}
   * prefix stripped). Missing / ungenerated chunks within the region file are
   * simply absent from the map.
   *
   * <p>Read at world-Y {@code y}; biomes vary by 3D position in modern MC, so
   * the caller picks a sampling y (typically a surface anchor such as 64).
   *
   * <p><b>Blocking, off-tick-thread only.</b> Implementations perform
   * synchronous {@code .mca} I/O on the calling thread; callers shall invoke
   * from an async / background context such as {@code RTP.scheduler}'s async
   * executor or the {@code MapDispatch.paint} dispatch path. Calling from a
   * Folia region thread or the Bukkit main tick thread is a defect (S-005).
   * The caller's resolver is forbidden from blocking on
   * {@code CompletableFuture#get / #join} by REQ-RTP-MAP-006, but a
   * direct synchronous read off the tick thread does not violate that rule.
   *
   * <p>Default returns an empty map. Adapters with {@code .mca}-backed stores
   * ({@code BukkitRTPWorld}, {@code FoliaRTPWorld}, {@code FabricRTPWorld})
   * SHOULD override using {@code AnvilPrefilter.regionFileFor} +
   * {@code AnvilRegionByteCache.get} + {@code AnvilReader.readChunkView} +
   * {@code AnvilChunkView.getBiomeAt}.
   *
   * <p>S-005: implementations shall NOT perform live chunk I/O; the contract
   * is on-disk anvil-only.
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
   * The region-schematic paster active for this platform (ADR-058). Used by {@code rtp-core}
   * at teleport commit to load (off-thread) and paste (on the region-owning thread) a
   * region-specific arrival structure when one is present on disk.
   *
   * <p>The default returns the platform-neutral
   * {@link io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster} (never {@code null},
   * S-006), so a platform with no paster installed simply reports
   * {@link io.github.dailystruggle.rtp.api.schematic.PasteResult#SKIPPED_UNSUPPORTED} and the
   * teleport proceeds unchanged. Adapters that ship a paster
   * ({@code BukkitRTPWorld}, {@code FoliaRTPWorld}, {@code FabricRTPWorld}) override this to
   * return their swappable static paster (mirroring the {@code setBiomeGetter} idiom).
   *
   * @return the active paster; never {@code null}
   */
  public io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster() {
    return io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster.INSTANCE;
  }

  /**
   * Writes a list of recorded original blocks back into this world, reversing an emergency
   * landing platform built by {@link #platform(RTPLocation)}. See ADR-060.
   *
   * <p>MUST be invoked on the thread that owns the footprint region (Folia region thread;
   * Bukkit/Paper main thread) - the caller schedules it through {@code RTP.scheduler}. The
   * implementation performs only block writes and never loads chunks: the caller invokes it
   * only when the footprint chunk is already loaded (S-005).
   *
   * <p>The default implementation is a no-op returning {@code false} (the platform does not
   * support block restoration); {@code .schem}-free adapters with a serializable block-state
   * token ({@code BukkitRTPWorld}, {@code FoliaRTPWorld}, Fabric peer) override it.
   *
   * @param blocks the recorded original blocks to write back; never {@code null}
   * @return {@code true} when the blocks were applied; {@code false} when unsupported or failed
   */
  public boolean restoreBlocks(java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    return false;
  }

  /**
   * Best-effort native bulk block write (ADR-058). Writes each entry of a block-location map
   * - a {@link io.github.dailystruggle.rtp.api.platform.BlockDelta} carrying world block
   * coordinates plus the adapter's canonical block-state token - into this world, and returns
   * the number of blocks actually placed.
   *
   * <p>This is the platform-neutral primitive the region-schematic paster
   * ({@code WorldBlockSchematicPaster}) drives: core plans the schematic into placements off the
   * region thread, then calls this on the region-owning thread (Folia region thread; Bukkit/Paper
   * main thread; Fabric server thread) to write the blocks. The caller invokes it only when the
   * footprint chunk is already loaded, so the implementation performs block writes only and never
   * loads chunks (S-005).
   *
   * <p>A single undecodable token must be counted as a skip and audited, never thrown (S-004); the
   * paste is best-effort and never aborts the teleport. The default implementation is a no-op
   * returning {@code 0} (the platform does not support native block writes); adapters with a
   * serializable block-state token override it.
   *
   * @param blocks the block-location map to write; never {@code null}
   * @return the count of blocks successfully placed ({@code 0} when unsupported or none applied)
   */
  public int setBlocks(java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    return 0;
  }

  /**
   * Best-effort native restore of block-entity payloads (chest/container contents, ...) for a
   * schematic paste (ADR-058). Called by the region-schematic paster
   * ({@code WorldBlockSchematicPaster}) after {@link #setBlocks(java.util.List)} has written the
   * block states, on the region-owning thread, with each block entity already resolved to its
   * absolute world coordinates by {@code SchematicPlacementPlanner#planBlockEntities}.
   *
   * <p>This is the second half of the platform-neutral paster's symmetric two-primitive contract:
   * {@link #setBlocks} writes block states and this restores their inhabited payloads, so a single
   * shared {@code SchematicPaster} can serve every platform without a per-platform paster subclass.
   * The implementation performs writes against blocks that are already loaded and placed; it never
   * loads chunks (S-005) and a per-entity failure is counted and audited, never thrown (S-004).
   *
   * <p>The default implementation is a no-op returning {@code 0} (the platform restores block
   * states only); platforms with a native container API (e.g. Bukkit/Paper/Folia) override it.
   *
   * @param entities the planned block entities to restore; never {@code null}
   * @return the count of block entities (containers) successfully restored
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
