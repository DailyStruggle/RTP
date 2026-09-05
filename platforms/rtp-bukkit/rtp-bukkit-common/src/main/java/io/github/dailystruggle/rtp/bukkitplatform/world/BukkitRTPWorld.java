package io.github.dailystruggle.rtp.bukkitplatform.world;

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

  public static @NotNull Set<String> defaultBiomes() {
    Set<String> out = new java.util.HashSet<>();
    for (Biome b : Biome.values()) {
      out.add(b.name().toUpperCase());
      out.add("minecraft:" + b.name().toLowerCase(java.util.Locale.ROOT));
    }
    return out;
  }

  private static @NotNull Function<RTPWorld<?>, Set<String>> getBiomes =
      (rtpWorld) -> defaultBiomes();

  private final UUID id;
  private final String name;

  private final ConcurrentHashMap<Long, WeakReference<Chunk>> chunkCache = new ConcurrentHashMap<>();

  /**
   * ADR-016 - per-world cache of Anvil-backed chunk views keyed by the same
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
   * Reflective handle to {@code World#getChunkAtAsync(int, int)} - an overload that returns
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
      // No such method on the running server - fall back to synchronous loading.
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

  /**
   * ADR-058 - swappable region-schematic paster, mirroring the {@link #setBiomeGetter}
   * idiom. Defaults to {@link io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster}
   * (never {@code null}, S-006); the adapter installs a WorldEdit/FAWE-backed paster at
   * bootstrap when the soft-depend is present, and an addon may override it via
   * {@link #setSchematicPaster}.
   */
  private static @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster schematicPaster =
      io.github.dailystruggle.rtp.api.schematic.NoOpSchematicPaster.INSTANCE;

  public static void setBiomeGetter(@NotNull Function<Location, String> getBiome) {
    BukkitRTPWorld.getBiome = getBiome;
  }

  public static void setSchematicPaster(
      @NotNull io.github.dailystruggle.rtp.api.schematic.SchematicPaster paster) {
    BukkitRTPWorld.schematicPaster = java.util.Objects.requireNonNull(paster, "paster");
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
   * ADR-058 native block write. Delegates to the shared {@link BukkitBlockWriter} so the
   * platform-neutral {@code WorldBlockSchematicPaster} can paste block states without a
   * Bukkit-specific {@code SchematicPaster}. Invoked on the main thread by the caller; writes only
   * loaded blocks (S-005); per-block parse failures are audited, never thrown (S-004).
   */
  @Override
  public int setBlocks(
      java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    return BukkitBlockWriter.setBlocks(world, blocks);
  }

  /**
   * ADR-058 native block-entity restore (container inventories, ...). Delegates to the shared
   * {@link BukkitBlockWriter}; invoked on the main thread after {@link #setBlocks} (S-005-clean,
   * per-entity failures audited per S-004).
   */
  @Override
  public int restoreBlockEntities(
      java.util.List<io.github.dailystruggle.rtp.api.schematic.PlacedBlockEntity> entities) {
    return BukkitBlockWriter.restoreBlockEntities(world, entities);
  }

  public static void setBiomesGetter(@NotNull Function<RTPWorld<?>, Set<String>> getBiomes) {
    BukkitRTPWorld.getBiomes = getBiomes;
  }

  public static Set<String> getBiomes(RTPWorld<?> world) {
    // The `AnvilRegionScanner.scanBiomes` union is not used by the runtime
    // getter. The authoritative runtime enumeration
    // for the biome-allow-list inversion is now `MemoryShape#getObservedBiomes()`
    // (consulted inside `LocationGenerator`); the platform enumeration returned here is
    // a fallback used only before the region has observed its first candidate. The
    // scanner remains available in `rtp-anvil` as a diagnostic tool for admin commands.
    Set<String> pre = getBiomes.apply(world);
    return (pre == null || pre.isEmpty()) ? defaultBiomes() : new java.util.HashSet<>(pre);
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
    final long key = ((long) cx & 0xffffffffL | ((long) cz << 32));

    // ADR-016 - Anvil read-only data source (no longer a gate).
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
    // (surface unsafe) no longer drops the candidate - the vert adjustor may
    // still find a safe air pocket below the surface (e.g. sub-lava caverns
    // in the nether).
    //
    // Only when the probe returns no view at all (UNKNOWN: no region file /
    // unsupported DataVersion / decode error / no emitted sections) do we
    // fall through to the live-load path. The live chunk.isSafe(...) re-check
    // at teleport commit (ADR-016 section 4) remains the authoritative arbiter.
    //
    // ADR-016 section 13.2 - every Bukkit-family adapter in scope (Spigot, Paper,
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
              io.github.dailystruggle.rtp.bukkitplatform.anvil.PaletteNormalizer::reconcile)
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
   * Fast-path probe that reads a single chunk's center column from the
   * persisted {@code r.X.Z.mca} region file and hands
   * {@code PregenTask} a {@link io.github.dailystruggle.rtp.api.world.ChunkColumnProbe}
   * that {@code VerticalAdjustor.adjustFromProbe} can evaluate without triggering
   * a full chunk decode or live load.
   *
   * <p>Gated on the same applicability check as the ADR-016 full-chunk prefilter
   * ({@link #shouldPrefilter}): off when the chunk is already loaded (live state
   * is authoritative) or when {@code SafetyKeys.anvilPrefilterEnabled} is false.
   * A null/empty return signals UNKNOWN - the caller falls back to the full
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
    // Dispatch onto AnvilIoPool rather than inline: inline dispatch serialized
    // ~7ms of probe I/O onto the driver's single thread, holding peak in-flight
    // at 11-12 vs cap 50. AnvilIoPool (dedicated blocking-I/O executor, sized
    // for disk parallelism) lets the driver saturate its semaphore and run
    // probes in parallel; the scheduler handoff overhead is far cheaper than
    // serializing 7ms onto the driver. S-005 preserved: AnvilIoPool threads are
    // daemons with no region-thread affinity.
    return CompletableFuture.supplyAsync(() -> {
      try {
        java.nio.file.Path regionFile =
            io.github.dailystruggle.rtp.anvil.AnvilPrefilter.regionFileFor(worldFolder, dim, cx, cz);
        // Share raw region bytes across sibling-chunk probes in the same
        // r.X.Z.mca via a 4-entry LRU, with mtime invalidation.
        byte[] regionBytes = io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache.get(regionFile);
        if (regionBytes == null) return null;
        int rx = Math.floorMod(cx, 32);
        int rz = Math.floorMod(cz, 32);
        io.github.dailystruggle.rtp.anvil.ColumnProbe probe =
            io.github.dailystruggle.rtp.anvil.AnvilReader.readColumnProbe(
                regionBytes, rx, rz, finalMinY, finalMaxY);
        if (probe == null) return null;
        return new io.github.dailystruggle.rtp.bukkitplatform.anvil.probe.AnvilColumnProbeAdapter(probe, cx, cz);
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
   * <p>Reads {@code r.<rcx>.<rcz>.mca} once via {@link
   * io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache}, decodes each of the
   * up-to-1024 chunks via {@link io.github.dailystruggle.rtp.anvil.AnvilReader#readChunkView},
   * and samples the biome at chunk-local {@code (8, y, 8)} via
   * {@link io.github.dailystruggle.rtp.anvil.AnvilChunkView#getBiomeAt}.
   *
   * <p>Biome names are canonicalised to the same uppercase, {@code minecraft:}
   * -stripped form used by {@code MemoryShape.addBiomeLocation} so that
   * {@link io.github.dailystruggle.mapsapi.BiomeColorSource}'s dimension overrides
   * (keyed e.g. {@code "NETHER_WASTES"}) match.
   *
   * <p>S-005: dispatched onto {@link io.github.dailystruggle.rtp.anvil.AnvilIoPool};
   * no tick-thread chunk I/O.
   */
  @Override
  public java.util.Map<Long, String> readBiomesInRegionFile(
      int rcx, int rcz, int y) {
    if (world == null) return java.util.Collections.emptyMap();
    final java.nio.file.Path worldFolder = world.getWorldFolder().toPath();
    final String dim = dimensionRegionSubpath(world);
    try {
      // regionFileFor takes a chunk coord; rcx<<5, rcz<<5 is the NW corner.
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
            // chunk not present in region file or unreadable; skip silently.
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

  /**
   * Canonicalises an on-disk biome id ({@code "minecraft:plains"},
   * {@code "iris:volcanic_ash_plains"}) to the form stored by
   * {@code MemoryShape.addBiomeLocation}: uppercase, vanilla {@code MINECRAFT:}
   * prefix stripped, modded namespaces preserved verbatim.
   */
  private static String canonicaliseBiome(String name) {
    if (name == null) return null;
    String up = name.toUpperCase(java.util.Locale.ROOT);
    if (up.startsWith("MINECRAFT:")) return up.substring("MINECRAFT:".length());
    return up;
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
   * appeared here has been intentionally removed (ADR-016 section 1 trust-model revision): for
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
      if (safety == null) return true; // Config not available yet - default-on.
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
    // and Folia (ADR-016 section 1.1): the call-site probe in LocationGenerator runs after
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
   * Snapshot the current {@code BlocksKeys.unsafeBlocks} list as a plain {@code Set<String>}.
   * Returns an empty set on any lookup failure - the pre-filter treats an empty set as
   * "never reject", which is the safe default.
   */
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

  /**
   * Perform the actual chunk load (reflective async → sync-on-primary-thread fallback).
   *
   * <p>When the reflective async path is unavailable, returns {@code null} (async I/O
   * failed), or resolves to a {@code null} {@link Chunk} (some Paper/Folia builds
   * return null when a chunk cannot be provided off-tick without generation), this
   * method falls back to {@link World#getChunkAt(int, int)} on the primary thread
   * - the only Bukkit API guaranteed to generate a missing chunk. Each failure edge
   * is logged at {@code WARNING} so operators can isolate problems in the pregen
   * summary's {@code nullChunk} bucket (REQ-RTP-S-004).
   */
  private CompletableFuture<Long> loadChunkFuture(int cx, int cz, long key) {
    // Count only actual live chunk-load attempts dispatched to the chunk system.
    // Probe-only paths (ADR-016 anvil pre-filter) and probe-cache hits MUST NOT bump
    // this counter - see the Javadoc on RTPWorld.totalChunkLoads. This avoids the
    // double-count that previously occurred when RTPWorld.getOrLoadChunk called both
    // getChunkAt (probe) and getChunkAtAsync (live) for the same logical attempt.
    totalChunkLoads.incrementAndGet();
    // Stamp the start of this live-load attempt so ChunkLoadProfile can record the
    // wall-clock floor (smallest single-chunk load). Only genuine live loads reach
    // here; the ADR-016 anvil prefilter short-circuit republishes a cached view
    // upstream in getChunkAt and never calls this method, so the floor is not
    // polluted by non-load near-zero samples.
    final long chunkLoadStartNanos = System.nanoTime();
    // Classify the load as already-generated (loaded from disk) vs ungenerated
    // (this load triggers generation - the expensive path) BEFORE the load runs,
    // while isChunkGenerated still reflects on-disk state. ChunkLoadProfile keeps
    // the two floors / costs separate so operators can see how much of RTP's cost
    // is generation (removable by pre-generating the world) versus loading.
    final boolean chunkGenerated = isChunkGenerated(cx, cz);
    // Prefer Bukkit's async chunk API (present in Spigot since 1.13). This avoids
    // hopping work back onto the primary thread for a synchronous world.getChunkAt()
    // call and keeps the location pipeline off the tick thread wherever the server
    // fork implements true async generation (Paper/Folia). On vanilla Spigot the
    // scheduling still resolves on the main thread internally, but the caller is
    // not blocked - a strict improvement over the previous runTask+getChunkAt path.
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
          return profileChunkLoad(out, chunkLoadStartNanos, chunkGenerated);
        }
      } catch (Throwable t) {
        // Some test doubles (e.g. MockBukkit builds) or very old forks may not
        // honor the async API - fall through to the synchronous generation path.
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP] Reflective async chunk API failed for world=" + name + " chunk=(" + cx
                + "," + cz + "): " + t.getClass().getSimpleName() + ": " + t.getMessage()
                + " — falling back to Bukkit synchronous getChunkAt");
      }
    }

    CompletableFuture<Long> future = new CompletableFuture<>();
    completeViaSyncGenerate(cx, cz, key, future);
    return profileChunkLoad(future, chunkLoadStartNanos, chunkGenerated);
  }

  /**
   * Records a successful single-chunk live-load's wall-clock duration into the
   * process-global {@link io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile}.
   * Failed loads (null key or exceptional completion) are not counted - a failed
   * load is not a measurement of how fast a chunk can be loaded. Returns the same
   * future so call sites compose unchanged.
   */
  private static CompletableFuture<Long> profileChunkLoad(
      CompletableFuture<Long> future, long startNanos, boolean generated) {
    return future.whenComplete((k, ex) -> {
      if (ex == null && k != null) {
        io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL
            .record(generated, System.nanoTime() - startNanos);
      }
    });
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
    // Folia note: Bukkit.getScheduler().runTask(...) throws UnsupportedOperationException
    // on Folia, so route the synchronous load through RTP.scheduler (region-aware on
    // Folia, main-thread on Bukkit/Paper).
    io.github.dailystruggle.rtp.api.scheduling.RTPScheduler scheduler =
        RTP.serverAccessor == null ? null : RTP.serverAccessor.getScheduler();
    if (scheduler != null) {
      scheduler.runTask(this, cx, cz, loadChunkTask);
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
   * Non-blocking lookup - delegates to Bukkit's {@code World#isChunkLoaded(int,int)}, which is
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
    // the raw addPluginChunkTicket call is main-thread-only on Bukkit/Paper and
    // region-thread-only on Folia. Off-thread callers schedule the application via
    // the platform's region-aware scheduler and MUST await the returned future
    // before relying on the chunk being pinned; the previous void return allowed
    // the location generator to hit the stale-chunk guard before the scheduled
    // task had actually run.
    //
    // Folia note: Bukkit.getScheduler().runTask(...) throws
    // UnsupportedOperationException on Folia, so the ticket application is routed
    // through RTP.scheduler (FoliaAwareScheduler), which hops to the region that
    // owns (cx, cz) on Folia and to the main thread on Bukkit/Paper.
    java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
    Runnable applyTicket = () -> {
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
    };
    io.github.dailystruggle.rtp.api.scheduling.RTPScheduler scheduler =
        RTP.serverAccessor == null ? null : RTP.serverAccessor.getScheduler();
    if (scheduler != null) {
      scheduler.runTask(this, cx, cz, applyTicket);
    } else {
      org.bukkit.Bukkit.getScheduler().runTask(plugin, applyTicket);
    }
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
    // Live chunk takes precedence over any Anvil snapshot - the live path is
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

    // ADR-016 fallback: no live chunk cached, but the anvil prefilter probe may
    // have produced an Anvil-backed view earlier in this candidate's evaluation.
    io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
    if (view != null) {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >> 32);
      java.util.Set<String> reconciled =
          io.github.dailystruggle.rtp.bukkitplatform.anvil.PaletteNormalizer.reconcileAll(
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
    // ADR-016 (biome) section 6. Anvil-first:
    // if the anvil pre-filter already decoded this chunk and its view
    // is still cached (no I/O on this path - O(1) ConcurrentHashMap read),
    // return the raw namespaced biome identifier from the on-disk palette.
    // Only fall through to the pre-existing getter when:
    //   - no Anvil view is cached for this chunk key (unpopulated / live-loaded), OR
    //   - the view has no biome container for this Y / (x,z) cell (pre-1.18 chunk,
    //     malformed section, or outside the vertical window).
    // The fallback chain is unchanged: in vanilla it is `world.getBiome().name()`;
    // when the Iris addon is installed, the addon's setBiomeGetter has already
    // replaced the static function with its engine-backed override, so Anvil-first
    // layers on top of whatever the last-registered setter installed. Biome reads
    // never gate safety, so a null from the Anvil branch is always a quiet
    // fall-through.
    // Reason-keyed metric + rate-limited log so operators can tell *why* a
    // live-tier read happened (ADR-016 section 13.1 observability, audit option A+C).
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
   * {@link #getBiome(int,int,int)}. Closes the ADR-016 section 13.1 observability
   * gap alongside the {@code BiomeSourceMetrics} reason breakdown.
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
   * ADR-016 section 13.3 vanilla-generator detection.
   *
   * <p>Treats a world as vanilla iff the running server reports no custom
   * {@link org.bukkit.generator.ChunkGenerator} and no custom
   * {@link org.bukkit.generator.BiomeProvider} for it. This catches the two
   * public Bukkit hooks that Iris, Terra, Chunky, and the datapack world-preset
   * bootstrapper use to install a non-vanilla generator. It cannot positively
   * detect mods that replace generation via NMS mixins, so any {@link Throwable}
   * collapses to {@code false} - "assume non-vanilla" is the safe default that
   * keeps the section 13.3 exemption from over-firing.</p>
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
   * ADR-016 section 13.3 upgrade-drift gate - region-file backed probe.
   *
   * <p>Resolution order:</p>
   * <ol>
   *   <li>{@link World#isChunkLoaded(int, int)} - a loaded chunk is by
   *       definition generated; cheapest answer, safe to call off-tick
   *       (concurrent map lookup).</li>
   *   <li>{@link io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache#isOccupied}
   *       against the {@code r.X.Z.mca} file resolved via
   *       {@link io.github.dailystruggle.rtp.anvil.AnvilPrefilter#regionFileFor}.
   *       The cache amortises the 32x32 region-tile occupancy bitmap across
   *       all ~1024 sibling-chunk queries - crucial for {@code ScanTask}
   *       PRESCAN which sweeps adjacent chunks in spiral order.</li>
   *   <li>Any {@link Throwable} collapses to {@code true} - "assume
   *       generated, skip the perf fast path" preserves the ADR-016 section 13.3
   *       palette-drift correctness (false-positives only forfeit a fast
   *       path; false-negatives would risk the bug).</li>
   * </ol>
   *
   * <p>Replaces the previous direct {@code world.isChunkGenerated} delegation,
   * which on Paper/Spigot is a synchronous chunk-system / region-file index
   * walk and collapsed {@code ScanTask}'s {@code peakInFlight} from {@code cap=50}
   * to ~2-3 when called per-candidate. Mirrors the Fabric implementation
   * (FabricRTPWorld#isChunkGenerated) so the Fabric-only precheck gate in
   * ScanTask can be removed.</p>
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
  public void platform(RTPLocation location) {
    try {
      ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      int radius = safety.getNumber(SafetyKeys.platformRadius, 0).intValue();
      // Honour the documented "disable platforms" contract from safety.yml (platformRadius: -1).
      // Skip even reading the rest of the config when the operator has opted out entirely.
      if (radius < 0) return;
      int airHeight = safety.getNumber(SafetyKeys.platformAirHeight, 0).intValue();
      int depth = safety.getNumber(SafetyKeys.platformDepth, 0).intValue();
      // ADR-060: optional block-restoration timeout. -1 (default) keeps the platform permanent
      // and skips diff capture entirely (zero added cost).
      int restoreSeconds = safety.getNumber(SafetyKeys.platformRestoreSeconds, -1).intValue();
      Material material;
      try {
        material = Material.valueOf(safety.getConfigValue(SafetyKeys.platformMaterial, "GLASS").toString().toUpperCase());
      } catch (IllegalArgumentException e) {
        material = Material.GLASS;
      }

      int lx = location.getBlockX();
      int ly = location.getBlockY();
      int lz = location.getBlockZ();

      java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> diff =
          restoreSeconds >= 0
              ? new java.util.ArrayList<>()
              : null;

      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          for (int dy = -depth; dy < 0; dy++) {
            org.bukkit.block.Block b = world.getBlockAt(lx + dx, ly + dy, lz + dz);
            if (diff != null) {
              diff.add(new io.github.dailystruggle.rtp.api.platform.BlockDelta(
                  b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString()));
            }
            b.setType(material);
          }
          for (int dy = 0; dy < airHeight; dy++) {
            org.bukkit.block.Block b = world.getBlockAt(lx + dx, ly + dy, lz + dz);
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
                java.util.UUID.randomUUID(), name(), lx >> 4, lz >> 4, diff, restoreSeconds));
      }
    } finally {
      if (location.getReservation() != null) location.getReservation().close();
    }
  }

  @Override
  public boolean restoreBlocks(
      java.util.List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks) {
    if (world == null || blocks == null || blocks.isEmpty()) return false;
    for (io.github.dailystruggle.rtp.api.platform.BlockDelta delta : blocks) {
      try {
        org.bukkit.block.data.BlockData data = Bukkit.createBlockData(delta.token());
        // applyPhysics=false: restoration is a bulk world rewrite, not a player edit.
        world.getBlockAt(delta.x(), delta.y(), delta.z()).setBlockData(data, false);
      } catch (IllegalArgumentException e) {
        // A single undecodable token must not abort the whole restore (S-004: audited by caller).
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP] could not decode block-state token during platform restore: " + delta.token());
      }
    }
    return true;
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
