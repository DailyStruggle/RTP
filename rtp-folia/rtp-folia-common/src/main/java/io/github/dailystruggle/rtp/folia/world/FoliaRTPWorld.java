package io.github.dailystruggle.rtp.folia.world;

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
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;

public final class FoliaRTPWorld extends RTPWorld<World> {
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
   * ADR-016 §11 — per-world cache of Anvil-backed chunk views. Populated by
   * {@link #getChunkAt(int, int)} whenever the shared
   * {@link io.github.dailystruggle.rtp.anvil.AnvilProbeSupport#probeAndPublish} yields a
   * decoded view, and consumed by {@link #getCachedChunk(long)} when no live
   * chunk is cached. The live {@link #chunkCache} takes precedence: once a
   * candidate is promoted to a real load at teleport-commit time, the live
   * entry is authoritative and the Anvil entry is evicted.
   *
   * <p>Folia benefits from this mechanism for the same reason Spigot does: the
   * pre-filter lets the candidate-selection loop evaluate surface safety from a
   * persisted region file on {@code ForkJoinPool.commonPool()} without hopping
   * to the Region Thread for every unloaded chunk.</p>
   */
  private final io.github.dailystruggle.rtp.anvil.AnvilProbeSupport anvilProbeSupport =
      new io.github.dailystruggle.rtp.anvil.AnvilProbeSupport();

  @RegionThread
  public FoliaRTPWorld(World world) {
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
    FoliaRTPWorld.getBiome = getBiome;
  }

  public static void setBiomesGetter(@NotNull Function<RTPWorld<?>, Set<String>> getBiomes) {
    FoliaRTPWorld.getBiomes = getBiomes;
  }

  public static Set<String> getBiomes(RTPWorld<?> world) {
    // BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md §4 step 6 — the `AnvilRegionScanner.scanBiomes`
    // union has been retired from the runtime getter (parity with BukkitRTPWorld). The
    // biome filter in `LocationGenerator` now evaluates the whitelist/blacklist directly
    // without materialising a world-level biome enumeration, so this path is only used
    // by tab completion and diagnostics. The scanner remains available in `rtp-anvil`.
    Set<String> pre = getBiomes.apply(world);
    return (pre == null) ? new java.util.HashSet<>() : new java.util.HashSet<>(pre);
  }

  @RegionThread
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
  @RegionThread
  public CompletableFuture<Long> getChunkAt(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    final long key = ((long) cx & 0xffffffffL | ((long) cz << 32));

    // ADR-016 §11 — Anvil read-only data source, same probe-then-fall-through
    // semantics as BukkitRTPWorld. When the applicability gate passes and the
    // pre-filter returns a decoded view, publish it into the per-world cache
    // and resolve the future with the key directly — no region-thread hop,
    // no chunk ticket acquired. The live chunk.isSafe(...) re-check at
    // teleport-commit time (ADR-016 §4) remains the authoritative arbiter.
    // On UNKNOWN (no view) we fall through to Folia's native async load.
    if (shouldPrefilter(cx, cz)) {
      java.util.Set<String> rawUnsafe = currentUnsafeBlocks();
      java.nio.file.Path worldFolder = world.getWorldFolder().toPath();
      String dim = dimensionRegionSubpath(world);
      return anvilProbeSupport
          .probeAndPublish(worldFolder, dim, cx, cz, key, rawUnsafe,
              io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer::reconcile)
          .thenCompose(result -> {
            io.github.dailystruggle.rtp.anvil.AnvilChunkView view = result.view();
            if (view != null) {
              if (result.verdict() == io.github.dailystruggle.rtp.anvil.Verdict.REJECT) {
                RTP.log(java.util.logging.Level.FINE,
                    "[RTP] Anvil surface-unsafe (advisory) world=" + name
                        + " chunk=(" + cx + "," + cz + ") — handing view to vert adjustor");
              }
              return CompletableFuture.completedFuture(key);
            }
            // No view available (UNKNOWN) → Folia native async load is authoritative.
            return loadLiveChunk(cx, cz, key);
          });
    }

    return loadLiveChunk(cx, cz, key);
  }

  /**
   * Public entry point for a live Folia chunk load that bypasses the ADR-016
   * anvil prefilter short-circuit. Shared by {@link #getChunkAt}'s UNKNOWN
   * fall-through and {@code TestChunkProbePerfCmd} so the "full" timing of the
   * calibration command reflects an actual live chunk load (the legacy
   * "always load to evaluate" cost), not the prefilter's cached anvil-view
   * republish path.
   */
  public CompletableFuture<Long> loadLiveChunk(int cx, int cz) {
    final long key = ((long) cx & 0xffffffffL | ((long) cz << 32));
    return loadLiveChunk(cx, cz, key);
  }

  /**
   * BIOME_LOOKUP_PERF_PLAN.md PR-3b — fast-path center-column probe for Folia.
   * See {@code BukkitRTPWorld#probeChunkColumn} for the shared contract.
   *
   * <p>S-005 / Folia threading: all file I/O is dispatched to
   * {@link java.util.concurrent.ForkJoinPool#commonPool()}, never to a region
   * thread. The applicability gate ({@link #shouldPrefilter}) already tolerates
   * the region-thread-restricted {@code world.isChunkLoaded} call by treating
   * a thrown {@code ThreadAccessException} as "continue into the probe", so
   * this override is safe to invoke from either the async scheduler or a
   * region thread.</p>
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
    // BIOME_LOOKUP_PERF_PLAN.md PR-9: revert PR-8's inline dispatch. The ScanTask
    // concurrency gauge showed peak in-flight 11–12 vs cap 50 — the driver loop
    // was serializing ~7ms of probe I/O onto its single thread, capping throughput
    // at 1/7ms ≈ 140 cps instead of saturating the semaphore. Dispatch back onto
    // AnvilIoPool (dedicated blocking-I/O executor) so the driver hands off in
    // µs and AnvilIoPool runs probes in parallel. S-005 preserved: AnvilIoPool
    // threads are daemons with no region-thread affinity.
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
        return (io.github.dailystruggle.rtp.api.world.ChunkColumnProbe)
            new io.github.dailystruggle.rtp.spigot.anvil.probe.AnvilColumnProbeAdapter(probe, cx, cz);
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
   * Folia native async chunk load. Resolves to the packed chunk key on success,
   * or {@code null} when the native async path returns a null chunk.
   */
  @RegionThread
  private CompletableFuture<Long> loadLiveChunk(int cx, int cz, long key) {
    return world
        .getChunkAtAsync(cx, cz, true)
        .thenApply(
            chunk -> {
              if (chunk == null) {
                // Symmetrical with BukkitRTPWorld.loadChunkSync's null-guard log.
                // A null live chunk after a prefilter UNKNOWN is an attributable
                // load failure — surface it so operators can tell "Folia caching
                // feels slow" apart from genuine async-load failures.
                RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] Folia world.getChunkAtAsync returned null for world=" + name
                        + " chunk=(" + cx + "," + cz + ")");
                return null;
              }
              cacheChunk(cx, cz, chunk);
              return key;
            })
        .exceptionally(ex -> {
          RTP.log(java.util.logging.Level.WARNING,
              "[RTP] Folia world.getChunkAtAsync failed for world=" + name
                  + " chunk=(" + cx + "," + cz + ")",
              ex);
          return null;
        });
  }

  /**
   * ADR-016 §11 applicability gate — mirrors
   * {@code BukkitRTPWorld#shouldPrefilter}. Returns {@code true} when:
   * <ul>
   *   <li>{@code SafetyKeys.anvilPrefilterEnabled} is truthy (default true),</li>
   *   <li>the chunk is not currently loaded.</li>
   * </ul>
   *
   * <p>The custom-{@link org.bukkit.generator.ChunkGenerator} short-circuit that previously
   * appeared here has been intentionally removed (ADR-016 §1 trust-model revision). For
   * populated chunks the {@code .mca} palette is a strictly more accurate source than the
   * Bukkit enum view (modded/Iris-native IDs collapse to vanilla on {@code Material}/{@code
   * Biome} lookups but survive verbatim in the on-disk palette). For chunks the custom
   * generator has not yet populated, {@code AnvilPrefilter.probeDetailed} returns {@code
   * UNKNOWN} and we fall through to Folia's native async load — which is exactly when we
   * want the generator to run. {@code REJECT} remains advisory: downstream
   * {@code chunk.isSafe(...)} corroborates every rejection, so disk-vs-live divergence
   * under a custom generator is bounded to "extra retries", never "unsafe teleport".
   */
  private boolean shouldPrefilter(int cx, int cz) {
    if (world == null) return false;
    // Folia note: world.isChunkLoaded(cx,cz) is region-thread-restricted. The
    // ADR-016 call-site probe runs on the async scheduler thread (no owning
    // region), so a ThreadAccessException here is expected and non-diagnostic.
    // We must not fall through to loadLiveChunk on that exception — the whole
    // point of ADR-016 is that when the chunk is *not* loaded we read .mca
    // instead of forcing a live load. Treat "can't determine" as "not loaded"
    // and continue into the anvil probe; the worst case is one extra probe
    // against an already-loaded chunk, which AnvilPrefilter handles cleanly.
    try {
      if (world.isChunkLoaded(cx, cz)) {
        logGateSkip("chunk-already-loaded", cx, cz);
        return false;
      }
    } catch (Throwable ignored) {
      // Fall through to the anvil path — do NOT short-circuit, do NOT log
      // at INFO (would spam every candidate on Folia).
    }
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return true;
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
   * adapter-level short-circuits in {@link #shouldPrefilter}. Mirrors the
   * equivalent in {@code BukkitRTPWorld} so operators triaging a stuck
   * {@code anvil-hits=0} metric see the same gate-skip story regardless of
   * platform (Spigot / Paper inherit the Bukkit variant; Folia uses this one).
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
      GATE_SKIP_COUNTERS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final int GATE_SKIP_BUDGET_PER_REASON = 200;

  private void logGateSkip(String reason, int cx, int cz) {
    // "chunk-already-loaded" is the steady-state outcome on Folia's region
    // scheduler (ADR-016 §1.1): the call-site probe in LocationGenerator runs
    // after candidate selection, by which time the region's tick loop has
    // typically already ticket-pinned the candidate chunk. The message carries
    // no diagnostic signal in that regime and would otherwise spam the log.
    // Suppressed entirely; the counter is retained so `rtp test/anvil-prefilter`
    // (and any future metric surface) can still observe the rate.
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
   * Vanilla region-folder subpath for the Folia environment. Folia preserves
   * the vanilla layout: overworld in {@code region/}, nether in
   * {@code DIM-1/region/}, end in {@code DIM1/region/}.
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

  /** Snapshot the current {@code SafetyKeys.unsafeBlocks} list. */
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

  @Override
  @RegionThread
  public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    // Must pass gen=true to force generation of unexplored chunks — RTP's primary
    // use case. Without the generate flag Folia returns a null Chunk for coordinates
    // with no prior .mca data, causing PregenTask to attribute every candidate to
    // nullChunk/asyncLoadNull (parity with BukkitRTPWorld.loadChunkSync / loadLiveChunk).
    return world.getChunkAtAsync(cx, cz, true).thenApply(chunk -> {
      if (chunk == null) {
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP] Folia world.getChunkAtAsync returned null for world=" + name
                + " chunk=(" + cx + "," + cz + ")");
        return null;
      }
      cacheChunk(cx, cz, chunk);
      return new ChunkSet(this, cx, cz, Collections.singletonList(CompletableFuture.completedFuture(((long) cx & 0xffffffffL | ((long) cz << 32)))), new CompletableFuture<>());
    }).exceptionally(ex -> {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP] Folia world.getChunkAtAsync failed for world=" + name
              + " chunk=(" + cx + "," + cz + ")",
          ex);
      return null;
    });
  }

  /**
   * Folia-specific stale-chunk guard (ADR-015 / REQ-RTP-S-005). Uses Bukkit's
   * {@code World#isChunkLoaded(int,int)}, which is a non-loading status query and
   * does NOT dispatch to the Region Thread. This guards the race between
   * {@link #getChunkAtAsync(int,int)} future resolution and the subsequent
   * block-evaluation task executing on a backlogged Count-Bound pipe, during
   * which Folia's native chunk GC may have unloaded the chunk.
   *
   * <p>Never call this from a hot inner loop that expects pin semantics — this
   * is a best-effort status read, not a guarantee the chunk will remain loaded
   * on the following line.</p>
   */
  @Override
  public boolean isChunkLoaded(int cx, int cz) {
    // Folia ADR-015 follow-up: World#isChunkLoaded(int,int) is region-thread-
    // restricted on Folia — calling it from an async scheduler thread (which
    // owns no region) throws ThreadAccessException, which previously caused
    // the stale-chunk guard in PregenTask to reject every candidate with
    // reason=staleChunkBeforeVert fails=10. Trust our own liveness signal
    // first: getChunkAtAsync caches the live Chunk reference in chunkCache,
    // and Chunk#isLoaded() is a field read on the chunk object, safe off
    // the owning region thread. Fall back to the Bukkit query only when
    // we have no cached reference (and suppress its thread-access throw).
    long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
    WeakReference<Chunk> ref = chunkCache.get(key);
    if (ref != null) {
      Chunk chunk = ref.get();
      if (chunk != null) {
        try {
          if (chunk.isLoaded()) {
            return true;
          }
        } catch (Throwable ignored) {
          // fall through
        }
      }
    }
    try {
      return world.isChunkLoaded(cx, cz);
    } catch (Throwable t) {
      return false;
    }
  }

  @Override
  @GlobalRegionThread
  protected java.util.concurrent.CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    // ADR-015 Paper chunk-system-v2 follow-up (ticket-application race):
    // on Folia the ticket application runs on the Global Region Scheduler;
    // the returned future completes inside that scheduled task so off-thread
    // callers (e.g. LocationGenerator on an async scheduler thread) can
    // await the actual application before relying on the chunk being pinned.
    java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
    RTP.serverAccessor.getScheduler().runTask(() -> {
      try {
        if (forceLoad) {
          if (!world.getPluginChunkTickets(cx, cz).contains(plugin)) {
            world.addPluginChunkTicket(cx, cz, plugin);
          }
        } else {
          world.removePluginChunkTicket(cx, cz, plugin);
        }
        future.complete(null);
      } catch (Exception e) {
        // Silently catch exceptions from lingering async tasks attempting to fire after shutdown.
        // Complete the future exceptionally so callers attributing via awaitReady() can
        // treat this as a ticket-apply failure rather than hang indefinitely.
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  @GlobalRegionThread
  public java.util.concurrent.CompletableFuture<Integer> getServerForceLoadedCount() {
    java.util.concurrent.CompletableFuture<Integer> future = new java.util.concurrent.CompletableFuture<>();
    io.github.dailystruggle.rtp.common.RTP.serverAccessor.getScheduler().runTask(() -> {
      org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
      if (plugin == null) {
        future.complete(0);
        return;
      }

      try {
        int count = 0;
        for (org.bukkit.Chunk chunk : world.getForceLoadedChunks()) {
          if (chunk.getPluginChunkTickets().contains(plugin)) {
            count++;
          }
        }
        future.complete(count);
      } catch (Exception e) {
        future.complete(-1); // Fallback in case of unexpected global region failure
      }
    });
    return future;
  }

  @Override
  @RegionThread
  public RTPChunk<?> getCachedChunk(long key) {
    // Live chunk takes precedence over any Anvil snapshot — the live path is
    // authoritative once a real chunk has been loaded.
    WeakReference<Chunk> ref = chunkCache.get(key);
    if (ref != null) {
      org.bukkit.Chunk chunk = ref.get();
      if (chunk != null && chunk.isLoaded()) {
        anvilProbeSupport.evict(key); // Drop any stale Anvil entry.
        return new FoliaRTPChunk(chunk);
      }
      chunkCache.remove(key); // Cleanup stale reference
    }

    // ADR-016 §11 fallback: no live chunk cached, but the pre-filter may have
    // produced an Anvil-backed view earlier in this candidate's evaluation.
    io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
    if (view != null) {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >> 32);
      java.util.Set<String> reconciled =
          io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer.reconcileAll(
              currentUnsafeBlocks());
      return new FoliaRTPChunk(view, cx, cz, id, reconciled);
    }
    return null;
  }


  @Override
  @RegionThread
  public void keepChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      chunkCache.put(((long) cx & 0xffffffffL | ((long) cz << 32)), new WeakReference<>(world.getChunkAt(cx, cz)));
      setForceLoaded(cx, cz, true);
    });
  }

  @Override
  @RegionThread
  public void forgetChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      setForceLoaded(cx, cz, false);
      chunkCache.remove(((long) cx & 0xffffffffL | ((long) cz << 32)));
    });
  }

  @Override
  @RegionThread
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
  @RegionThread
  public String getBiome(int x, int y, int z) {
    // ADR-016 / ADR-016 (biome) §6 — Anvil-first in-place amendment (parity
    // with BukkitRTPWorld). Zero-I/O cache read; on miss or outside-window the
    // call falls through to the pre-existing static getter (vanilla enum or
    // Iris-addon override, depending on last-registered setter). Biome reads
    // never gate safety (plan §5), so a null from the Anvil branch is a quiet
    // fall-through. Catch-all guards the advisory path per ADR-016's
    // "malformed → UNKNOWN, never crash" posture.
    // Reason-keyed metric + rate-limited log (ADR-016 §13.1 observability,
    // audit options A+C). Mirrors BukkitRTPWorld#getBiome.
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
   * {@link #getBiome(int,int,int)} — parity with {@code BukkitRTPWorld}.
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
   * ADR-016 §13.3 vanilla-generator detection (parity with BukkitRTPWorld).
   *
   * <p>Folia inherits Bukkit's {@code World#getGenerator()} /
   * {@code World#getBiomeProvider()} API, so the same reflective-safe detection
   * applies. Any {@link Throwable} falls back to {@code false} ("assume
   * non-vanilla"), which keeps {@code LocationGenerator} on the safe
   * post-load-authoritative biome path.</p>
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
   * ADR-016 §13.3 upgrade-drift gate (parity with {@code BukkitRTPWorld}) —
   * non-blocking delegation to {@link org.bukkit.World#isChunkGenerated(int, int)}.
   * A {@code true} answer means the chunk is already on disk, so the seed-synthesised
   * biome fallback must NOT be used even on vanilla worlds (the persisted palette
   * wins). Any {@link Throwable} collapses to {@code true} ("assume generated, skip
   * the pre-check").
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
  @RegionThread
  public void platform(RTPLocation location) {
    // Folia threading rule: block writes must run on the region that owns the destination
    // chunk(s), not on whatever scheduler thread invoked runTeleport (typically the player's
    // entity-region thread). Bounce the work to the destination region(s) via the
    // RegionScheduler. See REQ-RTP-S-005 / Folia threading rules in `Project Guidelines`.
    try {
      ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      int radius = safety.getNumber(SafetyKeys.platformRadius, 0).intValue();
      // Honour the documented "disable platforms" contract from safety.yml (platformRadius: -1).
      // Skip dispatching per-chunk region tasks entirely when the operator has opted out.
      if (radius < 0) return;
      int airHeight = safety.getNumber(SafetyKeys.platformAirHeight, 0).intValue();
      int depth = safety.getNumber(SafetyKeys.platformDepth, 0).intValue();
      final Material materialFinal;
      Material material;
      try {
        material = Material.valueOf(safety.getConfigValue(SafetyKeys.platformMaterial, "GLASS").toString().toUpperCase());
      } catch (IllegalArgumentException e) {
        material = Material.GLASS;
      }
      materialFinal = material;

      final int lx = location.getBlockX();
      final int ly = location.getBlockY();
      final int lz = location.getBlockZ();
      final int radiusFinal = radius;
      final int airHeightFinal = airHeight;
      final int depthFinal = depth;

      // Group writes by owning chunk so each batch can be dispatched to its region thread.
      // Chunks are 16x16 in X/Z; Folia regions cover multiple chunks but the per-chunk
      // dispatch is always safe (RegionScheduler.run with the chunk coords picks the
      // correct region thread, or runs immediately when the current thread already owns it).
      int minCx = (lx - radiusFinal) >> 4;
      int maxCx = (lx + radiusFinal) >> 4;
      int minCz = (lz - radiusFinal) >> 4;
      int maxCz = (lz + radiusFinal) >> 4;

      for (int cx = minCx; cx <= maxCx; cx++) {
        for (int cz = minCz; cz <= maxCz; cz++) {
          final int chunkX = cx;
          final int chunkZ = cz;
          final int chunkMinX = Math.max(lx - radiusFinal, cx << 4);
          final int chunkMaxX = Math.min(lx + radiusFinal, (cx << 4) + 15);
          final int chunkMinZ = Math.max(lz - radiusFinal, cz << 4);
          final int chunkMaxZ = Math.min(lz + radiusFinal, (cz << 4) + 15);
          RTP.scheduler.runTask(this, chunkX, chunkZ, () -> {
            for (int x = chunkMinX; x <= chunkMaxX; x++) {
              for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                for (int dy = -depthFinal; dy < 0; dy++) {
                  world.getBlockAt(x, ly + dy, z).setType(materialFinal);
                }
                for (int dy = 0; dy < airHeightFinal; dy++) {
                  world.getBlockAt(x, ly + dy, z).setType(Material.AIR);
                }
              }
            }
          });
        }
      }
    } finally {
      if (location.getReservation() != null) location.getReservation().close();
    }
  }

  @Override
  @GlobalRegionThread
  public boolean isInactive() {
    return Bukkit.getWorld(id) == null;
  }

  @Override
  @GlobalRegionThread
  public void save() {
    // Intentional no-op on Folia. Folia's region threading model means a
    // synchronous full-world save from a non-region context risks
    // ThreadAccessException, and Folia/Paper chunk-system-v2 already
    // persists generated chunks via its own dirty-tracking + autosave
    // path (unlike Spigot, where Chunky-style pre-generated chunks may
    // never reach disk without a forced World.save() — see
    // helpers/PeriodicWorldSaver and docs/dev/LESSONS_LEARNED.md
    // "Pre-Generation & Shutdown"). The BukkitRTPWorld override remains
    // the only platform that actually flushes here.
  }

  @Override
  @RegionThread
  public int getMaxHeight() {
    return world.getMaxHeight();
  }

  @Override
  @RegionThread
  public int getMinHeight() {
    return world.getMinHeight();
  }

  @Override
  @RegionThread
  public int getCacheSize() {
    return chunkCache.size();
  }

  @Override
  @RegionThread
  public long getSeed() {
    return world.getSeed();
  }
}
