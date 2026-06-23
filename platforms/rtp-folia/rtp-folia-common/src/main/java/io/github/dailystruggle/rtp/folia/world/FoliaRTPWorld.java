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

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
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
      (rtpWorld) -> {
        // Emit both bare upper-cased enum names (`BADLANDS`) and the raw
        // namespaced ids (`minecraft:badlands`) so Brigadier-suggested
        // namespaced tab-completion ids pass /rtp's biome param validator.
        Set<String> out = new java.util.HashSet<>();
        for (Biome b : Biome.values()) {
          out.add(b.name().toUpperCase());
          out.add("minecraft:" + b.name().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
      };

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

  /**
   * ADR-058 — swappable region-schematic paster, mirroring {@link #setBiomeGetter}.
   * Defaults to {@link io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster}
   * (never {@code null}, S-006).
   */
  private static @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster =
      io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster.INSTANCE;

  public static void setBiomeGetter(@NotNull Function<Location, String> getBiome) {
    FoliaRTPWorld.getBiome = getBiome;
  }

  public static void setSchematicPaster(
      @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster paster) {
    FoliaRTPWorld.schematicPaster = java.util.Objects.requireNonNull(paster, "paster");
  }

  public static @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster
      getSchematicPaster() {
    return schematicPaster;
  }

  @Override
  public io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster() {
    return schematicPaster;
  }

  /**
   * ADR-058 native block write. Delegates to the shared
   * {@link io.github.dailystruggle.rtp.bukkitplatform.world.BukkitBlockWriter} so the
   * platform-neutral {@code WorldBlockSchematicPaster} can paste block states without a
   * Folia-specific {@code SchematicPaster}. Invoked on the region-owning thread by the caller;
   * writes only loaded blocks (S-005); per-block parse failures are audited, never thrown (S-004).
   */
  @Override
  @RegionThread
  public int setBlocks(
      java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    return io.github.dailystruggle.rtp.bukkitplatform.world.BukkitBlockWriter
        .setBlocks(world, blocks);
  }

  /**
   * ADR-058 native block-entity restore (container inventories, ...). Delegates to the shared
   * {@link io.github.dailystruggle.rtp.bukkitplatform.world.BukkitBlockWriter}; invoked on the
   * region-owning thread after {@link #setBlocks} (S-005-clean, per-entity failures audited per
   * S-004).
   */
  @Override
  @RegionThread
  public int restoreBlockEntities(
      java.util.List<io.github.dailystruggle.rtp.api.schematic.PlacedBlockEntity> entities) {
    return io.github.dailystruggle.rtp.bukkitplatform.world.BukkitBlockWriter
        .restoreBlockEntities(world, entities);
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
    // Probe-entry: do NOT bump totalChunkLoads here. The counter is incremented by
    // the live-load path (getChunkAtAsync) so RTPWorld.getOrLoadChunk's probe-then-live
    // composition counts each logical chunk-load attempt exactly once. See the Javadoc
    // on RTPWorld.totalChunkLoads.
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
              io.github.dailystruggle.rtp.bukkitplatform.anvil.PaletteNormalizer::reconcile)
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
            new io.github.dailystruggle.rtp.bukkitplatform.anvil.probe.AnvilColumnProbeAdapter(probe, cx, cz);
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
   * {@inheritDoc}
   *
   * <p>Folia mirror of {@code BukkitRTPWorld#readBiomesInRegionFile}: reads
   * {@code r.<rcx>.<rcz>.mca} once via {@link
   * io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache} on the
   * {@link io.github.dailystruggle.rtp.anvil.AnvilIoPool}, decodes every chunk
   * in the file, and samples the biome at chunk-local {@code (8, y, 8)}.
   * Canonicalises to the same uppercase, {@code minecraft:}-stripped form
   * {@code MemoryShape.addBiomeLocation} stores under, so
   * {@link io.github.dailystruggle.mapsapi.BiomeColorSource} dimension
   * overrides match. S-005: no region-thread chunk I/O.
   */
  @Override
  public java.util.Map<Long, String> readBiomesInRegionFile(
      int rcx, int rcz, int y) {
    if (world == null) return java.util.Collections.emptyMap();
    final java.nio.file.Path worldFolder = world.getWorldFolder().toPath();
    final String dim = dimensionRegionSubpath(world);
    try {
      java.nio.file.Path regionFile =
          io.github.dailystruggle.rtp.anvil.AnvilPrefilter.regionFileFor(
              worldFolder, dim, rcx << 5, rcz << 5);
      if (regionFile == null) return java.util.Collections.emptyMap();
      byte[] regionBytes =
          io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache.get(regionFile);
      if (regionBytes == null) return java.util.Collections.emptyMap();
      java.util.HashMap<Long, String> out = new java.util.HashMap<>(1024);
      for (int rx = 0; rx < 32; rx++) {
        for (int rz = 0; rz < 32; rz++) {
          try {
            io.github.dailystruggle.rtp.anvil.AnvilChunkView view =
                io.github.dailystruggle.rtp.anvil.AnvilReader.readChunkView(
                    regionBytes, rx, rz);
            if (view == null) continue;
            String raw = view.getBiomeAt(8, y, 8);
            if (raw == null) continue;
            String canonical = canonicaliseBiome(raw);
            if (canonical == null || canonical.isEmpty()) continue;
            int cx = (rcx << 5) | rx;
            int cz = (rcz << 5) | rz;
            long key = ((long) cx << 32) | (cz & 0xFFFF_FFFFL);
            out.put(key, canonical);
          } catch (Throwable ignored) {
            // chunk not present / unreadable; skip silently.
          }
        }
      }
      return out;
    } catch (Throwable t) {
      RTP.log(java.util.logging.Level.FINE,
          "[RTP] readBiomesInRegionFile failed for world=" + name
              + " region=(" + rcx + "," + rcz + "): "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
      return java.util.Collections.emptyMap();
    }
  }

  private static String canonicaliseBiome(String name) {
    if (name == null) return null;
    String up = name.toUpperCase(java.util.Locale.ROOT);
    if (up.startsWith("MINECRAFT:")) return up.substring("MINECRAFT:".length());
    return up;
  }

  /**
   * Folia native async chunk load. Resolves to the packed chunk key on success,
   * or {@code null} when the native async path returns a null chunk.
   */
  @RegionThread
  private CompletableFuture<Long> loadLiveChunk(int cx, int cz, long key) {
    // Count only actual live chunk-load attempts (post probe-cache miss). The probe
    // entry getChunkAt MUST NOT bump this; see RTPWorld.totalChunkLoads Javadoc.
    totalChunkLoads.incrementAndGet();
    // Stamp the start so ChunkLoadProfile can record the wall-clock floor (smallest
    // single-chunk load). Only genuine live loads reach here; the ADR-016 anvil
    // prefilter short-circuit republishes a cached view upstream in getChunkAt and
    // never calls this method, so the floor is not polluted by non-load samples.
    final long chunkLoadStartNanos = System.nanoTime();
    // Classify already-generated (loaded from disk) vs ungenerated (this load
    // triggers generation - the expensive path) BEFORE the load runs, while
    // isChunkGenerated still reflects on-disk state. ChunkLoadProfile keeps the
    // two floors / costs separate so operators can see how much of RTP's cost is
    // generation (removable by pre-generating the world) versus loading.
    final boolean chunkGenerated = isChunkGenerated(cx, cz);
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
        })
        .whenComplete((k, ex) -> {
          // Record only successful loads (non-null key, no throwable). A failed load
          // is not a measurement of how fast a chunk can be loaded.
          if (ex == null && k != null) {
            io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL
                .record(chunkGenerated, System.nanoTime() - chunkLoadStartNanos);
          }
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

  @Override
  public String environment() {
    try {
      World w = world();
      return (w == null) ? null : w.getEnvironment().name();
    } catch (Throwable ignored) {
      return null;
    }
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

  /** Snapshot the current {@code BlocksKeys.unsafeBlocks} list. */
  @SuppressWarnings("unchecked")
  private static java.util.Set<String> currentUnsafeBlocks() {
    try {
      if (RTP.configs == null) return java.util.Collections.emptySet();
      Object raw = RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new java.util.ArrayList<>());
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
          io.github.dailystruggle.rtp.bukkitplatform.anvil.PaletteNormalizer.reconcileAll(
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
    Throwable thrown = null;
    int cx = x >> 4;
    int cz = z >> 4;
    try {
      long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
      io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
      if (view != null) {
        String fromAnvil = view.getBiomeAt(x & 0xF, y, z & 0xF);
        if (fromAnvil != null) {
          io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(
              io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.ANVIL_HIT);
          return fromAnvil;
        }
        reason = io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.VIEW_MISSING_BIOME;
      } else {
        reason = io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.NO_VIEW_CACHED;
      }
    } catch (Throwable t) {
      reason = io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.ANVIL_THROW;
      thrown = t;
    }
    io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.record(reason);
    logBiomeFallthrough(reason, cx, cz, thrown);
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

  private void logBiomeFallthrough(String reason, int cx, int cz, Throwable thrown) {
    java.util.concurrent.atomic.AtomicInteger counter =
        BIOME_FALLTHROUGH_COUNTERS.computeIfAbsent(reason,
            k -> new java.util.concurrent.atomic.AtomicInteger());
    int n = counter.incrementAndGet();
    // `no-view-cached` is the common, fully-benign path: no Anvil view was
    // cached for this chunk, so the biome was read via the live getter. That is
    // correct for vanilla worlds (a custom-generator world would instead want a
    // load-to-check, handled elsewhere), carries no exception, and never gates
    // safety. Keep it at FINE so it cannot be mistaken for an error; other
    // reasons stay at INFO until the per-reason budget is exhausted.
    boolean benign =
        io.github.dailystruggle.rtp.anvil.BiomeSourceMetrics.Reasons.NO_VIEW_CACHED.equals(reason);
    java.util.logging.Level level = (!benign && n <= BIOME_LOG_BUDGET_PER_REASON)
        ? java.util.logging.Level.INFO
        : java.util.logging.Level.FINE;
    String msg = "[RTP] Anvil biome fallthrough reason=" + reason + " world=" + name
        + " chunk=(" + cx + "," + cz + ")"
        + (thrown != null ? " thrown=" + thrown : "")
        + (!benign && n > BIOME_LOG_BUDGET_PER_REASON
            ? " (further occurrences suppressed to FINE)" : "");
    if (thrown != null) {
      RTP.log(level, msg, thrown);
    } else {
      RTP.log(level, msg);
    }
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
   * ADR-016 §13.3 upgrade-drift gate — region-file backed probe (parity with
   * {@code BukkitRTPWorld#isChunkGenerated} and {@code FabricRTPWorld#isChunkGenerated}).
   *
   * <p>Resolution order:</p>
   * <ol>
   *   <li>{@link World#isChunkLoaded(int, int)} — a loaded chunk is by
   *       definition generated; cheapest answer, safe to call off-region on
   *       Folia (concurrent map lookup, no region affinity required).</li>
   *   <li>{@link io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache#isOccupied}
   *       against the {@code r.X.Z.mca} file resolved via
   *       {@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#regionFileFor}.
   *       The cache amortises the 32x32 region-tile occupancy bitmap across
   *       all ~1024 sibling-chunk queries — crucial for {@code ScanTask}
   *       PRESCAN which sweeps adjacent chunks in spiral order.</li>
   *   <li>Any {@link Throwable} collapses to {@code true} — "assume
   *       generated, skip the perf fast path" preserves the ADR-016 §13.3
   *       palette-drift correctness (false-positives only forfeit a fast
   *       path; false-negatives would risk the bug).</li>
   * </ol>
   *
   * <p>Replaces the previous direct {@code world.isChunkGenerated} delegation,
   * which is an off-region synchronous Bukkit chunk-system call and collapsed
   * {@code ScanTask}'s {@code peakInFlight} from {@code cap=50} to ~2-3 when
   * called per-candidate.</p>
   */
  @Override
  public boolean isChunkGenerated(int cx, int cz) {
    if (world == null) return true;
    try {
      if (world.isChunkLoaded(cx, cz)) return true;
    } catch (Throwable ignored) {
      // Fall through to the on-disk probe.
    }
    try {
      java.nio.file.Path worldFolder = world.getWorldFolder().toPath();
      String dim = dimensionRegionSubpath(world);
      java.nio.file.Path regionFile =
          io.github.dailystruggle.rtp.anvil.AnvilPrefilter.regionFileFor(worldFolder, dim, cx, cz);
      if (regionFile == null || !java.nio.file.Files.exists(regionFile)) {
        return false;
      }
      return io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache
          .isOccupied(regionFile, cx, cz);
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
      // ADR-060: optional block-restoration timeout (-1 disables, skips capture).
      final int restoreSeconds = safety.getNumber(SafetyKeys.platformRestoreSeconds, -1).intValue();
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
            java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> diff =
                restoreSeconds >= 0 ? new java.util.ArrayList<>() : null;
            for (int x = chunkMinX; x <= chunkMaxX; x++) {
              for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                for (int dy = -depthFinal; dy < 0; dy++) {
                  org.bukkit.block.Block b = world.getBlockAt(x, ly + dy, z);
                  if (diff != null) {
                    diff.add(new io.github.dailystruggle.rtp.api.platform.BlockDelta(
                        b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString()));
                  }
                  b.setType(materialFinal);
                }
                for (int dy = 0; dy < airHeightFinal; dy++) {
                  org.bukkit.block.Block b = world.getBlockAt(x, ly + dy, z);
                  if (diff != null) {
                    diff.add(new io.github.dailystruggle.rtp.api.platform.BlockDelta(
                        b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString()));
                  }
                  b.setType(Material.AIR);
                }
              }
            }
            if (diff != null && !diff.isEmpty()
                && io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager.instance != null) {
              io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager.instance.enroll(
                  new io.github.dailystruggle.rtp.api.platform.PendingPlatformRestore(
                      java.util.UUID.randomUUID(), name(), chunkX, chunkZ, diff, restoreSeconds));
            }
          });
        }
      }
    } finally {
      if (location.getReservation() != null) location.getReservation().close();
    }
  }

  @Override
  @RegionThread
  public boolean restoreBlocks(
      java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    if (world == null || blocks == null || blocks.isEmpty()) return false;
    for (io.github.dailystruggle.rtp.api.platform.BlockDelta delta : blocks) {
      try {
        org.bukkit.block.data.BlockData data = Bukkit.createBlockData(delta.token());
        world.getBlockAt(delta.x(), delta.y(), delta.z()).setBlockData(data, false);
      } catch (IllegalArgumentException e) {
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP] could not decode block-state token during platform restore: " + delta.token());
      }
    }
    return true;
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
