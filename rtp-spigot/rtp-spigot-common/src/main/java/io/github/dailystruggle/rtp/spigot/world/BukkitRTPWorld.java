package io.github.dailystruggle.rtp.spigot.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;

public class BukkitRTPWorld extends RTPWorld<World> {
  private static final AtomicBoolean biomeException = new AtomicBoolean(false);
  private static Function<Location, String> getBiome =
      location -> {
        if (biomeException.get()) return Biome.PLAINS.name();
        try {
          World world = Objects.requireNonNull(location.getWorld());
          int x = location.getBlockX();
          int y = location.getBlockY();
          int z = location.getBlockZ();
          return world.getBiome(x, y, z).name();
        } catch (Throwable t) {
          return Biome.PLAINS.name();
        }
      };

  private static @NotNull Function<RTPWorld<?>, Set<String>> getBiomes =
      (rtpWorld) ->
          Arrays.stream(Biome.values())
              .map(biome -> biome.name().toUpperCase())
              .collect(Collectors.toSet());

  private final UUID id;
  private final String name;

  private final ConcurrentHashMap<Long, WeakReference<Chunk>> chunkCache = new ConcurrentHashMap<>();

  /**
   * ADR-016 — per-world cache of Anvil-backed chunk views keyed by the same
   * {@code (cx, cz) -> long} packing used by {@link #chunkCache}. The cache +
   * FIFO-eviction lifecycle is owned by {@link io.github.dailystruggle.rtp.anvil.AnvilProbeSupport},
   * which is shared by every adapter that wires the ADR-016 pipeline (currently
   * Bukkit/Spigot and Folia). The live {@link #chunkCache} takes precedence:
   * once a candidate is promoted to a real load (e.g. at teleport-commit time),
   * the live entry is the source of truth and the Anvil entry is evicted.
   */
  private final io.github.dailystruggle.rtp.anvil.AnvilProbeSupport anvilProbeSupport =
      new io.github.dailystruggle.rtp.anvil.AnvilProbeSupport();

  /**
   * Reflective handle to {@code World#getChunkAtAsync(int, int)} — an overload that returns
   * {@link CompletableFuture}&lt;{@link Chunk}&gt;. The Bukkit API shipped with
   * {@code spigot-api:1.20.1-R0.1-SNAPSHOT} only declares the {@code Consumer}-based async
   * overloads, so we cannot bind to this method at compile time, but Paper, Folia, and modern
   * Spigot forks all expose it at runtime. Cached as a static field (nullable) to keep the
   * per-call hot path to a single {@code Method.invoke} call.
   */
  private static final java.lang.reflect.Method CHUNK_AT_ASYNC_FUTURE;

  static {
    java.lang.reflect.Method m = null;
    try {
      m = World.class.getMethod("getChunkAtAsync", int.class, int.class);
      if (!CompletableFuture.class.isAssignableFrom(m.getReturnType())) {
        // Some very old API stubs declared the same name with a Consumer-based signature but
        // never with this exact (int,int) pair; defensive check in case that ever changes.
        m = null;
      }
    } catch (Throwable ignored) {
      // No such method on the running server — fall back to synchronous loading.
    }
    CHUNK_AT_ASYNC_FUTURE = m;
  }

  public BukkitRTPWorld(World world) {
    super(world);
    if (world == null) {
      this.id = null;
      this.name = null;
    } else {
      this.id = world.getUID();
      this.name = world.getName();
    }
  }

  public static void setBiomeGetter(@NotNull Function<Location, String> getBiome) {
    BukkitRTPWorld.getBiome = getBiome;
  }

  public static void setBiomesGetter(@NotNull Function<RTPWorld<?>, Set<String>> getBiomes) {
    BukkitRTPWorld.getBiomes = getBiomes;
  }

  public static Set<String> getBiomes(RTPWorld<?> world) {
    // BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md §4 step 6 — the `AnvilRegionScanner.scanBiomes`
    // union has been retired from the runtime getter. The authoritative runtime enumeration
    // for the biome-allow-list inversion is now `MemoryShape#getObservedBiomes()`
    // (consulted inside `LocationGenerator`); the platform enumeration returned here is
    // a fallback used only before the region has observed its first candidate. The
    // scanner remains available in `rtp-anvil` as a diagnostic tool for admin commands.
    Set<String> pre = getBiomes.apply(world);
    return (pre == null) ? new java.util.HashSet<>() : new java.util.HashSet<>(pre);
  }

  public void cacheChunk(int x, int z, org.bukkit.Chunk chunk) {
    long key = ((long) x & 0xffffffffL | ((long) z << 32));
    chunkCache.put(key, new WeakReference<>(chunk));
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public CompletableFuture<Long> getChunkAt(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    final long key = ((long) cx & 0xffffffffL | ((long) cz << 32));

    // ADR-016 — Anvil read-only data source (no longer a gate).
    //
    // For candidate chunks that are not currently loaded and the world uses the
    // vanilla chunk generator, we probe the persisted r.X.Z.mca region file
    // off-thread. The prefilter is a *data source*, not a
    // short-circuit: whenever the probe yields a decoded AnvilChunkView
    // (regardless of the advisory ACCEPT/REJECT verdict) we publish that view
    // into the Anvil cache and return the same chunk key the live path would
    // use, so the immediately-following getCachedChunk(key) call in
    // LocationGenerator receives an Anvil-backed BukkitRTPChunk and lets the
    // vert adjustor scan across the whole decoded Y range. An advisory REJECT
    // (surface unsafe) no longer drops the candidate — the vert adjustor may
    // still find a safe air pocket below the surface (e.g. sub-lava caverns
    // in the nether).
    //
    // Only when the probe returns no view at all (UNKNOWN: no region file /
    // unsupported DataVersion / decode error / no emitted sections) do we
    // fall through to the live-load path. The live chunk.isSafe(...) re-check
    // at teleport commit (ADR-016 §4) remains the authoritative arbiter.
    //
    // ADR-016 §13.2 — every Bukkit-family adapter in scope (Spigot, Paper,
    // Folia) goes through this pre-filter. Paper inherits it verbatim via
    // this class (its `PaperRTPWorld` subclass deliberately adds no
    // `getChunkAt` override). Folia re-implements the same probe-and-publish
    // orchestration in `FoliaRTPWorld#getChunkAt` because it extends
    // `RTPWorld<World>` directly, not `BukkitRTPWorld`.
    if (shouldPrefilter(cx, cz)) {
      java.util.Set<String> rawUnsafe = currentUnsafeBlocks();
      // AnvilPrefilter is platform-neutral and takes a Path + dimension subpath
      // plus a UnaryOperator<String> reconciler. The Material-aware reconciler lives in
      // the spigot-side PaletteNormalizer.
      java.nio.file.Path worldFolder = world.getWorldFolder().toPath();
      String dim = dimensionRegionSubpath(world);
      return anvilProbeSupport
          .probeAndPublish(worldFolder, dim, cx, cz, key, rawUnsafe,
              io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer::reconcile)
          .thenCompose(result -> {
            io.github.dailystruggle.rtp.anvil.AnvilChunkView view = result.view();
            if (view != null) {
              // FINE breadcrumb so operators can still see how often the surface
              // heightmap would have been rejected under the old gate semantics;
              // the verdict remains meaningful as telemetry.
              if (result.verdict() == io.github.dailystruggle.rtp.anvil.Verdict.REJECT) {
                RTP.log(java.util.logging.Level.FINE,
                    "[RTP] Anvil surface-unsafe (advisory) world=" + name
                        + " chunk=(" + cx + "," + cz + ") — handing view to vert adjustor");
              }
              return CompletableFuture.completedFuture(key);
            }
            // No view available (UNKNOWN) → live load is authoritative.
            return loadChunkFuture(cx, cz, key);
          });
    }

    return loadChunkFuture(cx, cz, key);
  }

  /**
   * Public entry point for a live chunk load that bypasses the ADR-016 anvil
   * prefilter short-circuit. Shared by {@link #getChunkAt}'s UNKNOWN fall-through
   * and {@code TestChunkProbePerfCmd} so the "full" timing of the calibration
   * command reflects an actual live chunk load (the legacy "always load to
   * evaluate" cost), not the prefilter's cached anvil view re-publish.
   */
  public CompletableFuture<Long> loadLiveChunk(int cx, int cz) {
    final long key = ((long) cx & 0xffffffffL | ((long) cz << 32));
    return loadChunkFuture(cx, cz, key);
  }

  /**
   * BIOME_LOOKUP_PERF_PLAN.md PR-3b — fast-path probe that reads a single chunk's
   * center column from the persisted {@code r.X.Z.mca} region file and hands
   * {@code PregenTask} a {@link io.github.dailystruggle.rtp.api.world.ChunkColumnProbe}
   * that {@code VerticalAdjustor.adjustFromProbe} can evaluate without triggering
   * a full chunk decode or live load.
   *
   * <p>Gated on the same applicability check as the ADR-016 full-chunk prefilter
   * ({@link #shouldPrefilter}): off when the chunk is already loaded (live state
   * is authoritative) or when {@code SafetyKeys.anvilPrefilterEnabled} is false.
   * A null/empty return signals UNKNOWN — the caller falls back to the full
   * {@link #getChunkAt} pipeline, which remains the authoritative path.</p>
   *
   * <p>S-005: file I/O is dispatched to {@link io.github.dailystruggle.rtp.anvil.AnvilIoPool}
   * (dedicated blocking-I/O executor, sized for disk parallelism) to stay off the tick
   * thread and avoid starving {@code ForkJoinPool.commonPool} with blocking reads. The
   * future never resolves on a live-region thread.</p>
   */
  @Override
  public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe>
      probeChunkColumn(int cx, int cz, int minY, int maxY) {
    if (minY > maxY) return CompletableFuture.completedFuture(null);
    if (!shouldPrefilter(cx, cz)) return CompletableFuture.completedFuture(null);
    if (world == null) return CompletableFuture.completedFuture(null);
    final java.nio.file.Path worldFolder = world.getWorldFolder().toPath();
    final String dim = dimensionRegionSubpath(world);
    final int finalMinY = minY;
    final int finalMaxY = maxY;
    // BIOME_LOOKUP_PERF_PLAN.md PR-9: revert PR-8's inline dispatch. The concurrency
    // gauge in ScanTask showed peak in-flight at 11–12 vs cap 50 — the driver loop
    // was serializing ~7ms of probe I/O onto its single thread. Dispatch back onto
    // AnvilIoPool (dedicated blocking-I/O executor, sized for disk parallelism) so
    // the driver can saturate its semaphore and the AnvilIoPool runs probes in
    // parallel. Scheduler handoff overhead (~tens of µs) is vastly cheaper than
    // serializing 7ms onto the driver. S-005 preserved: AnvilIoPool threads are
    // daemons with no region-thread affinity.
    return CompletableFuture.supplyAsync(() -> {
      try {
        java.nio.file.Path regionFile =
            io.github.dailystruggle.rtp.anvil.AnvilPrefilter.regionFileFor(worldFolder, dim, cx, cz);
        // BIOME_LOOKUP_PERF_PLAN.md PR-10: share raw region bytes across sibling-chunk
        // probes in the same r.X.Z.mca via a 4-entry LRU, with mtime invalidation.
        byte[] regionBytes = io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache.get(regionFile);
        if (regionBytes == null) return null;
        int rx = Math.floorMod(cx, 32);
        int rz = Math.floorMod(cz, 32);
        io.github.dailystruggle.rtp.anvil.ColumnProbe probe =
            io.github.dailystruggle.rtp.anvil.AnvilReader.readColumnProbe(
                regionBytes, rx, rz, finalMinY, finalMaxY);
        if (probe == null) return null;
        return new io.github.dailystruggle.rtp.spigot.anvil.probe.AnvilColumnProbeAdapter(probe, cx, cz);
      } catch (Throwable t) {
        RTP.log(java.util.logging.Level.FINE,
            "[RTP] probeChunkColumn failed for world=" + name
                + " chunk=(" + cx + "," + cz + "): "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        return null;
      }
    }, io.github.dailystruggle.rtp.anvil.AnvilIoPool.get());
  }

  /**
   * Returns {@code true} when the applicability gates for the ADR-016 Anvil pre-filter
   * are all satisfied for the chunk at absolute coords {@code (cx, cz)}:
   * <ul>
   *   <li>{@code SafetyKeys.anvilPrefilterEnabled} is truthy (default {@code true}),</li>
   *   <li>the chunk is not currently loaded (avoids corrupted/desync reads against live writes).</li>
   * </ul>
   *
   * <p>The custom-{@link org.bukkit.generator.ChunkGenerator} short-circuit that previously
   * appeared here has been intentionally removed (ADR-016 §1 trust-model revision): for
   * chunks that are already populated on disk, the {@code .mca} palette is a strictly
   * more accurate data source than the Bukkit {@link org.bukkit.Material} / {@link
   * org.bukkit.block.Biome} enum views (which silently collapse modded and Iris-native
   * identifiers to their nearest vanilla cousin). For chunks that are not yet populated,
   * {@code AnvilPrefilter.probeDetailed} returns {@code UNKNOWN} and the caller falls
   * through to the authoritative live load, which is exactly the safety net required when
   * a custom generator still has work to do. Downstream {@code chunk.isSafe(...)} corroborates
   * every {@code REJECT}, so any disk-vs-live divergence under a custom generator is
   * bounded to "extra retries", never "unsafe teleport".
   */
  private boolean shouldPrefilter(int cx, int cz) {
    if (world == null) return false;
    try {
      if (world.isChunkLoaded(cx, cz)) {
        logGateSkip("chunk-already-loaded", cx, cz);
        return false;
      }
    } catch (Throwable ignored) {
      logGateSkip("isChunkLoaded-threw", cx, cz);
      return false;
    }
    try {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return true; // Config not available yet — default-on.
      Object raw = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
      boolean enabled;
      if (raw instanceof Boolean b) enabled = b;
      else if (raw != null) enabled = Boolean.parseBoolean(raw.toString());
      else enabled = true;
      if (!enabled) {
        logGateSkip("config-disabled(anvilPrefilterEnabled=false)", cx, cz);
      }
      return enabled;
    } catch (Throwable ignored) {
      return true;
    }
  }

  /**
   * Rate-limited diagnostic (first 20 per reason at INFO, then FINE) for the
   * two adapter-level early-exits from {@link #shouldPrefilter}. Complements the
   * {@code rtp-anvil/AnvilPrefilter} probe-level diagLog which cannot see the
   * adapter's pre-probe gate. Together they give operators a complete "why
   * anvil-hits=0" chain: gate skip → probe UNKNOWN reason → view published.
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
      GATE_SKIP_COUNTERS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final int GATE_SKIP_BUDGET_PER_REASON = 200;

  private void logGateSkip(String reason, int cx, int cz) {
    // "chunk-already-loaded" is the steady-state outcome on Paper chunk-system-v2
    // and Folia (ADR-016 §1.1): the call-site probe in LocationGenerator runs after
    // candidate selection, by which time ambient server load or prior-attempt residue
    // has typically already ticket-pinned the candidate chunk. The message carries no
    // diagnostic signal in that regime and would otherwise spam the log. Suppressed
    // entirely; the counter is retained below so `rtp test/anvil-prefilter` (and any
    // future metric surface) can still observe the rate.
    java.util.concurrent.atomic.AtomicInteger counter =
        GATE_SKIP_COUNTERS.computeIfAbsent(reason,
            k -> new java.util.concurrent.atomic.AtomicInteger());
    int n = counter.incrementAndGet();
    if ("chunk-already-loaded".equals(reason)) {
      return;
    }
    // Other reasons (dimension-unsupported, world-save-disabled, config-disabled(...))
    // remain operator-actionable: INFO within budget, FINE afterward.
    java.util.logging.Level level = (n <= GATE_SKIP_BUDGET_PER_REASON)
        ? java.util.logging.Level.INFO
        : java.util.logging.Level.FINE;
    RTP.log(level,
        "[RTP] Anvil gate skipped reason=" + reason + " world=" + name
            + " chunk=(" + cx + "," + cz + ")"
            + (level == java.util.logging.Level.FINE
                ? " (further occurrences suppressed to FINE)" : ""));
  }

  /**
   * Map a Bukkit {@link World#getEnvironment()} to the on-disk region subdirectory used
   * by vanilla. Inlined here (rather than in the platform-neutral {@code AnvilPrefilter})
   * because it is the single place where {@code rtp-anvil} would otherwise need to know
   * about Bukkit's {@link org.bukkit.World.Environment} enum.
   */
  private static String dimensionRegionSubpath(World world) {
    if (world == null) return "";
    try {
      switch (world.getEnvironment()) {
        case NETHER:
          return "DIM-1";
        case THE_END:
          return "DIM1";
        case NORMAL:
        default:
          return "";
      }
    } catch (Throwable ignored) {
      return "";
    }
  }

  /**
   * Snapshot the current {@code SafetyKeys.unsafeBlocks} list as a plain {@code Set<String>}.
   * Returns an empty set on any lookup failure — the pre-filter treats an empty set as
   * "never reject", which is the safe default.
   */
  @SuppressWarnings("unchecked")
  private static java.util.Set<String> currentUnsafeBlocks() {
    try {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return java.util.Collections.emptySet();
      Object raw = safety.getConfigValue(SafetyKeys.unsafeBlocks, new java.util.ArrayList<>());
      if (raw instanceof java.util.Collection<?> c) {
        java.util.Set<String> out = new java.util.HashSet<>(c.size());
        for (Object o : c) if (o != null) out.add(o.toString());
        return out;
      }
    } catch (Throwable ignored) {
      // Fall through to empty.
    }
    return java.util.Collections.emptySet();
  }

  /**
   * Perform the actual chunk load (reflective async → sync-on-primary-thread fallback).
   *
   * <p>When the reflective async path is unavailable, returns {@code null} (async I/O
   * failed), or resolves to a {@code null} {@link Chunk} (some Paper/Folia builds
   * return null when a chunk cannot be provided off-tick without generation), this
   * method falls back to {@link World#getChunkAt(int, int)} on the primary thread
   * — the only Bukkit API guaranteed to generate a missing chunk. Each failure edge
   * is logged at {@code WARNING} so operators can isolate problems in the pregen
   * summary's {@code nullChunk} bucket (REQ-RTP-S-004).
   */
  private CompletableFuture<Long> loadChunkFuture(int cx, int cz, long key) {
    // Prefer Bukkit's async chunk API (present in Spigot since 1.13). This avoids
    // hopping work back onto the primary thread for a synchronous world.getChunkAt()
    // call and keeps the location pipeline off the tick thread wherever the server
    // fork implements true async generation (Paper/Folia). On vanilla Spigot the
    // scheduling still resolves on the main thread internally, but the caller is
    // not blocked — a strict improvement over the previous runTask+getChunkAt path.
    if (CHUNK_AT_ASYNC_FUTURE != null) {
      try {
        @SuppressWarnings("unchecked")
        CompletableFuture<org.bukkit.Chunk> asyncFuture =
            (CompletableFuture<org.bukkit.Chunk>) CHUNK_AT_ASYNC_FUTURE.invoke(world, cx, cz);
        if (asyncFuture != null) {
          CompletableFuture<Long> out = new CompletableFuture<>();
          asyncFuture.whenComplete((chunk, ex) -> {
            if (ex != null) {
              RTP.log(java.util.logging.Level.WARNING,
                  "[RTP] Async chunk load threw for world=" + name + " chunk=(" + cx + "," + cz
                      + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                      + " — falling back to Bukkit synchronous getChunkAt");
              completeViaSyncGenerate(cx, cz, key, out);
              return;
            }
            if (chunk == null) {
              RTP.log(java.util.logging.Level.WARNING,
                  "[RTP] Async chunk load returned null for world=" + name + " chunk=(" + cx
                      + "," + cz + ") — falling back to Bukkit synchronous getChunkAt to"
                      + " trigger generation");
              completeViaSyncGenerate(cx, cz, key, out);
              return;
            }
            cacheChunk(cx, cz, chunk);
            out.complete(key);
          });
          return out;
        }
      } catch (Throwable t) {
        // Some test doubles (e.g. MockBukkit builds) or very old forks may not
        // honor the async API — fall through to the synchronous generation path.
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP] Reflective async chunk API failed for world=" + name + " chunk=(" + cx
                + "," + cz + "): " + t.getClass().getSimpleName() + ": " + t.getMessage()
                + " — falling back to Bukkit synchronous getChunkAt");
      }
    }

    CompletableFuture<Long> future = new CompletableFuture<>();
    completeViaSyncGenerate(cx, cz, key, future);
    return future;
  }

  /**
   * Synchronous generation fallback. Runs {@link World#getChunkAt(int, int)} on the
   * primary thread (generating the chunk if needed) and completes {@code out} with
   * {@code key} on success or {@code null} on failure. Each failure edge is logged
   * at {@code WARNING} so operators can isolate which candidates are being dropped
   * and why.
   */
  private void completeViaSyncGenerate(int cx, int cz, long key, CompletableFuture<Long> out) {
    Runnable loadChunkTask = () -> {
      try {
        org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
        if (chunk == null) {
          RTP.log(java.util.logging.Level.WARNING,
              "[RTP] Synchronous world.getChunkAt returned null for world=" + name
                  + " chunk=(" + cx + "," + cz + ")");
          out.complete(null);
          return;
        }
        cacheChunk(cx, cz, chunk);
        out.complete(key);
      } catch (Throwable t) {
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP] Synchronous chunk load threw for world=" + name + " chunk=(" + cx + ","
                + cz + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        out.complete(null);
      }
    };

    if (org.bukkit.Bukkit.isPrimaryThread()) {
      loadChunkTask.run();
      return;
    }
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin != null) {
      org.bukkit.Bukkit.getScheduler().runTask(plugin, loadChunkTask);
    } else {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP] Cannot schedule synchronous chunk load — RTP plugin is not registered"
              + " with Bukkit plugin manager (world=" + name + " chunk=(" + cx + "," + cz
              + ")) — dropping candidate");
      out.complete(null);
    }
  }

  @Override
  public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
    return getChunkAt(cx, cz).thenApply(key -> {
      return new ChunkSet(this, cx, cz, Collections.singletonList(CompletableFuture.completedFuture(key)), new CompletableFuture<>());
    });
  }

  /**
   * Non-blocking lookup — delegates to Bukkit's {@code World#isChunkLoaded(int,int)}, which is
   * documented as a pure state query and does NOT trigger a chunk load. Used by the stale-chunk
   * guard (ADR-015 / REQ-RTP-S-005) to abort block evaluation when the chunk was GC'd while the
   * Count-Bound pipe was backlogged.
   */
  @Override
  public boolean isChunkLoaded(int cx, int cz) {
    try {
      return world.isChunkLoaded(cx, cz);
    } catch (Throwable t) {
      // Defensive: if the underlying world view is gone, treat as unloaded so callers abort
      // safely rather than proceeding into a synchronous load path.
      return false;
    }
  }

  @Override
  protected java.util.concurrent.CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    if (org.bukkit.Bukkit.isPrimaryThread()) {
      if (forceLoad) {
        if (!world.getPluginChunkTickets(cx, cz).contains(plugin)) {
          world.addPluginChunkTicket(cx, cz, plugin);
        }
      } else {
        world.removePluginChunkTicket(cx, cz, plugin);
      }
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    // ADR-015 Paper chunk-system-v2 follow-up (ticket-application race):
    // the raw addPluginChunkTicket call is main-thread-only on Bukkit/Paper.
    // Off-thread callers schedule the application via runTask and MUST await
    // the returned future before relying on the chunk being pinned; the
    // previous void return allowed the location generator to hit the stale-
    // chunk guard before the scheduled task had actually run.
    java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
      try {
        if (forceLoad) {
          if (!world.getPluginChunkTickets(cx, cz).contains(plugin)) {
            world.addPluginChunkTicket(cx, cz, plugin);
          }
        } else {
          world.removePluginChunkTicket(cx, cz, plugin);
        }
        future.complete(null);
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });
    return future;
  }

  @Override
  public java.util.concurrent.CompletableFuture<Integer> getServerForceLoadedCount() {
    java.util.concurrent.CompletableFuture<Integer> future = new java.util.concurrent.CompletableFuture<>();
    io.github.dailystruggle.rtp.common.RTP.serverAccessor.getScheduler().runTask(() -> {
      org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
      if (plugin == null) {
        future.complete(0);
        return;
      }

      int count = 0;
      for (org.bukkit.Chunk chunk : world.getForceLoadedChunks()) {
        if (chunk.getPluginChunkTickets().contains(plugin)) {
          count++;
        }
      }
      future.complete(count);
    });
    return future;
  }

  @Override
  public RTPChunk<?> getCachedChunk(long key) {
    // Live chunk takes precedence over any Anvil snapshot — the live path is
    // authoritative once a real chunk has been loaded.
    WeakReference<org.bukkit.Chunk> ref = chunkCache.get(key);
    if (ref != null) {
      org.bukkit.Chunk chunk = ref.get();
      if (chunk != null && chunk.isLoaded()) {
        // Drop any stale Anvil entry now that the live chunk exists.
        anvilProbeSupport.evict(key);
        return new BukkitRTPChunk(chunk);
      }
      chunkCache.remove(key); // Cleanup stale reference
    }

    // ADR-016 fallback: no live chunk cached, but the Phase 3a probe may
    // have produced an Anvil-backed view earlier in this candidate's evaluation.
    io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
    if (view != null) {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >> 32);
      java.util.Set<String> reconciled =
          io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer.reconcileAll(
              currentUnsafeBlocks());
      return new BukkitRTPChunk(view, cx, cz, id, reconciled);
    }
    return null;
  }



  @Override
  public void keepChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      chunkCache.put(((long) cx & 0xffffffffL | ((long) cz << 32)), new WeakReference<>(world.getChunkAt(cx, cz)));
      setForceLoaded(cx, cz, true);
    });
  }

  @Override
  public void forgetChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      setForceLoaded(cx, cz, false);
      chunkCache.remove(((long) cx & 0xffffffffL | ((long) cz << 32)));
    });
  }

  @Override
  public void forgetChunks() {
    // Explicitly un-force-load everything we know about before clearing
    chunkTickets.forEach((key, count) -> {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >> 32);
      while (count.get() > 0) {
        setForceLoaded(cx, cz, false);
      }
    });
    chunkCache.clear();
    anvilProbeSupport.clear();
  }

  @Override
  public String getBiome(int x, int y, int z) {
    // ADR-016 / ADR-016 (biome) §6 — in-place amendment. Anvil-first:
    // if the Phase-3a pre-filter already decoded this chunk and its view
    // is still cached (no I/O on this path — O(1) ConcurrentHashMap read),
    // return the raw namespaced biome identifier from the on-disk palette.
    // Only fall through to the pre-existing getter when:
    //   - no Anvil view is cached for this chunk key (unpopulated / live-loaded), OR
    //   - the view has no biome container for this Y / (x,z) cell (pre-1.18 chunk,
    //     malformed section, or outside the vertical window).
    // The fallback chain is unchanged: in vanilla it is `world.getBiome().name()`;
    // when the Iris addon is installed, the addon's setBiomeGetter has already
    // replaced the static function with its engine-backed override, so Anvil-first
    // layers on top of whatever the last-registered setter installed. Biome reads
    // never gate safety (plan §5), so a null from the Anvil branch is always a
    // quiet fall-through.
    // Reason-keyed metric + rate-limited log so operators can tell *why* a
    // live-tier read happened (ADR-016 §13.1 observability, audit option A+C).
    String reason;
    int cx = x >> 4;
    int cz = z >> 4;
    try {
      long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
      io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
      if (view != null) {
        String fromAnvil = view.getBiomeAt(x, y, z);
        if (fromAnvil != null) {
          io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(
              io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.ANVIL_HIT);
          return fromAnvil;
        }
        reason = io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.VIEW_MISSING_BIOME;
      } else {
        reason = io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.NO_VIEW_CACHED;
      }
    } catch (Throwable ignored) {
      reason = io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.ANVIL_THROW;
    }
    io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(reason);
    logBiomeFallthrough(reason, cx, cz);
    return getBiome.apply(new Location(world, x, y, z));
  }

  /**
   * Rate-limited diagnostic (first {@link #BIOME_LOG_BUDGET_PER_REASON} per
   * reason at INFO, then FINE) for each live-tier fallthrough path in
   * {@link #getBiome(int,int,int)}. Closes the ADR-016 §13.1 observability
   * gap alongside the {@code BiomeSourceMetrics} reason breakdown.
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
      BIOME_FALLTHROUGH_COUNTERS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final int BIOME_LOG_BUDGET_PER_REASON = 200;

  private void logBiomeFallthrough(String reason, int cx, int cz) {
    java.util.concurrent.atomic.AtomicInteger counter =
        BIOME_FALLTHROUGH_COUNTERS.computeIfAbsent(reason,
            k -> new java.util.concurrent.atomic.AtomicInteger());
    int n = counter.incrementAndGet();
    java.util.logging.Level level = (n <= BIOME_LOG_BUDGET_PER_REASON)
        ? java.util.logging.Level.INFO
        : java.util.logging.Level.FINE;
    RTP.log(level,
        "[RTP] Anvil biome fallthrough reason=" + reason + " world=" + name
            + " chunk=(" + cx + "," + cz + ")"
            + (n > BIOME_LOG_BUDGET_PER_REASON
                ? " (further occurrences suppressed to FINE)" : ""));
  }

  /**
   * ADR-016 §13.3 vanilla-generator detection.
   *
   * <p>Treats a world as vanilla iff the running server reports no custom
   * {@link org.bukkit.generator.ChunkGenerator} and no custom
   * {@link org.bukkit.generator.BiomeProvider} for it. This catches the two
   * public Bukkit hooks that Iris, Terra, Chunky, and the datapack world-preset
   * bootstrapper use to install a non-vanilla generator. It cannot positively
   * detect mods that replace generation via NMS mixins, so any {@link Throwable}
   * collapses to {@code false} — "assume non-vanilla" is the safe default that
   * keeps the §13.3 exemption from over-firing.</p>
   */
  @Override
  public boolean isVanilla() {
    if (world == null) return false;
    try {
      return world.getGenerator() == null && world.getBiomeProvider() == null;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * ADR-016 §13.3 upgrade-drift gate — non-blocking delegation to
   * {@link org.bukkit.World#isChunkGenerated(int, int)}. A {@code true} answer
   * means the chunk is already on disk, so the seed-synthesised biome fallback
   * must NOT be used even on vanilla worlds (the persisted palette wins). Any
   * {@link Throwable} collapses to {@code true} — "assume generated, skip the
   * pre-check" is the conservative default that preserves correctness.
   */
  @Override
  public boolean isChunkGenerated(int cx, int cz) {
    if (world == null) return true;
    try {
      return world.isChunkGenerated(cx, cz);
    } catch (Throwable ignored) {
      return true;
    }
  }

  @Override
  public void platform(RTPLocation location) {
    try {
      ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      int radius = safety.getNumber(SafetyKeys.platformRadius, 0).intValue();
      // Honour the documented "disable platforms" contract from safety.yml (platformRadius: -1).
      // Skip even reading the rest of the config when the operator has opted out entirely.
      if (radius < 0) return;
      int airHeight = safety.getNumber(SafetyKeys.platformAirHeight, 0).intValue();
      int depth = safety.getNumber(SafetyKeys.platformDepth, 0).intValue();
      Material material;
      try {
        material = Material.valueOf(safety.getConfigValue(SafetyKeys.platformMaterial, "GLASS").toString().toUpperCase());
      } catch (IllegalArgumentException e) {
        material = Material.GLASS;
      }

      int lx = location.getBlockX();
      int ly = location.getBlockY();
      int lz = location.getBlockZ();

      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          for (int dy = -depth; dy < 0; dy++) {
            world.getBlockAt(lx + dx, ly + dy, lz + dz).setType(material);
          }
          for (int dy = 0; dy < airHeight; dy++) {
            world.getBlockAt(lx + dx, ly + dy, lz + dz).setType(Material.AIR);
          }
        }
      }
    } finally {
      if (location.getReservation() != null) location.getReservation().close();
    }
  }

  @Override
  public boolean isInactive() {
    return Bukkit.getWorld(id) == null;
  }

  public boolean isForceLoaded(int cx, int cz) {
    return world.isChunkForceLoaded(cx, cz);
  }

  @Override
  public void save() {
    // World.save() is main-thread only on Spigot/Paper (AsyncCatcher will throw otherwise).
    // ScanTask.wrapUpBatch invokes us from an async worker; RTP.scheduler.runTask
    // executes inline when already on the primary thread, otherwise schedules to it.
    RTP.scheduler.runTask(() -> world.save());
  }

  @Override
  public int getMaxHeight() {
    return world.getMaxHeight();
  }

  @Override
  public int getMinHeight() {
    return world.getMinHeight();
  }

  @Override
  public int getCacheSize() {
    return chunkCache.size();
  }

  @Override
  public long getSeed() {
    return world.getSeed();
  }
}
