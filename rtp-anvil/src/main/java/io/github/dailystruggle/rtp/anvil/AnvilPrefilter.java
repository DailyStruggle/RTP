package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only Anvil pre-filter entry point (ADR-016 Phase 3a).
 *
 * <p>Given a world and a region-local chunk coordinate pair, attempts to reject clearly
 * unsafe candidates off the tick thread by parsing the persisted {@code r.X.Z.mca}
 * region file directly. A candidate is rejected ({@link Verdict#REJECT}) only when the
 * on-disk block stack at the heightmap-reported surface contains an identifier from the
 * caller-supplied unsafe set. All other outcomes — unsupported format, missing region
 * file, decode error, or simply a clean column — yield {@link Verdict#ACCEPT} or
 * {@link Verdict#UNKNOWN}, both of which <strong>fall through to the live chunk load</strong>
 * in {@code BukkitRTPWorld.getChunkAt}. The live {@code RTPChunk.isSafe(...)} re-check
 * remains the authoritative source of truth; the pre-filter only short-circuits the
 * common-case rejection.
 *
 * <h3>Thread placement</h3>
 *
 * <p>{@link #probe(World, int, int, Set)} schedules the I/O + NBT walk on
 * {@link ForkJoinPool#commonPool()} and returns a {@link CompletableFuture}. Callers
 * (currently only {@code BukkitRTPWorld.getChunkAt}) must not block the returned future
 * on the primary thread. The probe never touches any Bukkit state beyond
 * {@link World#getWorldFolder()}, {@link World#getEnvironment()}, and
 * {@link World#getGenerator()} — all of which are documented as safe from any thread.
 *
 * <h3>Applicability gating</h3>
 *
 * <p>Callers are expected to gate the probe behind the four checks from
 * {@code ADR-016 §3} (config flag, chunk not loaded, no custom
 * generator, structural cache miss). The probe itself enforces only the
 * {@link DataVersionSupport#isSupported(int)} format-support gate internally — any
 * out-of-whitelist {@code DataVersion} yields {@link Verdict#UNKNOWN}.
 *
 * <h3>Heightmap interpretation</h3>
 *
 * <p>{@code MOTION_BLOCKING_NO_LEAVES} is stored as a packed {@code long[]} with
 * {@code bitsPerEntry = 9} in 1.18+ worlds (range {@code [0, 384]} covering
 * {@code minHeight..minHeight+height}). Values in the array are column heights relative
 * to the world's minimum build height — a value of {@code 0} means "bedrock or no
 * blocks present at all". Blocks above that height are guaranteed non-motion-blocking
 * (air, leaves excluded, etc.); the block <em>at</em> {@code (height - 1) + minHeight}
 * is the first motion-blocking block scanning downward. That is the block the pre-filter
 * samples as the teleport ground. The feet (surface) and head (surface+1) blocks are
 * also sampled and must not be in the unsafe set.
 */
public final class AnvilPrefilter {

  /**
   * Logger used for diagnostic output from the Anvil pre-filter. Local to the
   * {@code rtp-anvil} module to preserve its zero-dependency contract (no
   * Bukkit, no rtp-core imports — see {@code AnvilPackageBoundaryArchTest}).
   * Routed to the JDK root logger, which the Bukkit/Paper/Folia server forwards
   * to its own log; the platform adapter's log formatter handles the rest.
   */
  private static final Logger LOG = Logger.getLogger(AnvilPrefilter.class.getName());

  /**
   * Number of bits per entry in the 1.18+ {@code MOTION_BLOCKING_NO_LEAVES} packed long
   * array. Derived from {@code ceil(log2(worldHeight + 1))}; for a 384-block overworld
   * (min {@code -64}, max {@code 319}) this is {@code 9} bits per entry, yielding
   * {@code 37} packed longs for 256 columns ({@code ceil(256 / (64/9))}).
   */
  private static final int MOTION_BLOCKING_NO_LEAVES_BITS = 9;

  /**
   * Default palette identifier reconciler used when callers do not supply one.
   *
   * <p>Strips a {@code namespace:} prefix and uppercases the remainder via
   * {@link Locale#ROOT}. Equivalent to {@code rtp-api}'s
   * {@code PaletteIdentifierNormalizer#normalize} for vanilla identifiers, but
   * inlined here so that {@code rtp-anvil} keeps zero RTP and zero platform
   * dependencies (per ADR-016). Platform adapters layer a registry-aware
   * reconciler on top via the {@code UnaryOperator<String>} overloads.
   */
  public static final UnaryOperator<String> DEFAULT_RECONCILER = raw -> {
    if (raw == null) return null;
    int colon = raw.indexOf(':');
    String stripped = (colon >= 0) ? raw.substring(colon + 1) : raw;
    return stripped.toUpperCase(Locale.ROOT);
  };

  private AnvilPrefilter() {
    // Utility class.
  }

  /**
   * Rate-limit budget for each distinct UNKNOWN/verdict-diagnostic log reason
   * (per JVM). The probe is called once per candidate chunk, so unbounded
   * INFO-level logging would drown the console on a cold server. A small cap
   * is enough to surface "0 Anvil hits" root causes to operators without
   * sustained spam; once the cap is reached further emissions drop to FINE.
   */
  private static final int DIAG_LOG_BUDGET_PER_REASON = 5;

  private static final ConcurrentHashMap<String, AtomicInteger> DIAG_LOG_COUNTERS =
      new ConcurrentHashMap<>();

  /**
   * Emit a rate-limited diagnostic line explaining why a probe returned
   * {@link Verdict#UNKNOWN} (or, for positive breadcrumbs, why it decoded a
   * view). Operators triaging a stuck {@code anvil-hits=0} metric from
   * {@code rtp test biome-source} / {@code rtp test anvil-prefilter} can use
   * these lines to tell apart the five possible UNKNOWN causes:
   * {@code no-region-file}, {@code empty-location-entry},
   * {@code unsupported-dataversion}, {@code missing-heightmap},
   * {@code no-sections}. The {@code first-N} lines per reason are emitted at
   * {@link Level#INFO} so they appear in standard server logs; once the budget
   * is exhausted the logger falls back to {@link Level#FINE}.
   */
  private static void diagLog(String reason, Path worldFolder, String dim,
                              int cx, int cz) {
    AtomicInteger counter =
        DIAG_LOG_COUNTERS.computeIfAbsent(reason, k -> new AtomicInteger());
    int n = counter.incrementAndGet();
    Level level = (n <= DIAG_LOG_BUDGET_PER_REASON) ? Level.INFO : Level.FINE;
    if (LOG.isLoggable(level)) {
      LOG.log(level,
          "[RTP] Anvil probe " + reason + " world=" + worldFolder
              + " dim=\"" + dim + "\" chunk=(" + cx + "," + cz + ")"
              + (n > DIAG_LOG_BUDGET_PER_REASON
                  ? " (further occurrences suppressed to FINE)"
                  : ""));
    }
  }

  /**
   * Result of a detailed probe: the {@link Verdict} and the decoded
   * {@link AnvilChunkView} that the verdict was computed from.
   *
   * <p>Under ADR-016, the view is attached on both {@link Verdict#ACCEPT} and
   * {@link Verdict#REJECT} — the verdict becomes an advisory telemetry signal
   * and the view is the actual load-free source for a subsequent Anvil-backed
   * {@code BukkitRTPChunk} (ADR-016). The view is {@code null} only when no
   * decode was possible (missing region file, unsupported DataVersion, parse
   * error, no emitted sections), i.e. when {@code verdict == UNKNOWN}. Callers
   * fall through to the live-load path on {@code view == null}.
   *
   * @param verdict the verdict (never {@code null}); purely advisory under ADR-016
   * @param view    the decoded view; {@code null} only on {@link Verdict#UNKNOWN}
   */
  public record ProbeResult(Verdict verdict, AnvilChunkView view) {}

  /**
   * ADR-016 Phase 3b: asynchronously probe a chunk and return both the verdict and the
   * decoded {@link AnvilChunkView} (on ACCEPT). Callers that want only the verdict
   * use {@link #probeSync(Path, String, int, int, Set)}; callers that want the
   * load-free chunk source use this entry point.
   *
   * <p>On {@link Verdict#REJECT} or {@link Verdict#UNKNOWN}, {@link ProbeResult#view()}
   * is {@code null} and the caller falls through to the live-load path. On
   * {@link Verdict#ACCEPT}, the view is the same one used to compute the verdict —
   * reusing it avoids a second region-file read.
   */
  public static CompletableFuture<ProbeResult> probeDetailed(
      Path worldFolder, String dimensionSubpath, int cx, int cz,
      Set<String> rawUnsafeBlocks, UnaryOperator<String> reconciler) {
    if (worldFolder == null) {
      return CompletableFuture.completedFuture(new ProbeResult(Verdict.UNKNOWN, null));
    }
    final String dim = (dimensionSubpath == null) ? "" : dimensionSubpath;
    final UnaryOperator<String> r = (reconciler == null) ? DEFAULT_RECONCILER : reconciler;
    return CompletableFuture.supplyAsync(
        () -> probeSyncDetailed(worldFolder, dim, cx, cz, rawUnsafeBlocks, r),
        ForkJoinPool.commonPool());
  }

  /**
   * Synchronous probe entry point. Exposed for tests that want deterministic dispatch
   * without the {@link ForkJoinPool} hop. Not intended for production callers.
   *
   * <p>Accepts the raw config-side unsafe list and reconciles it internally via
   * {@link PaletteNormalizer#reconcileAll} so that tests and production callers
   * follow the same canonical-form pipeline on both sides of the comparison.</p>
   */
  public static Verdict probeSync(
      Path worldFolder, String dimensionSubpath, int cx, int cz, Set<String> rawUnsafeBlocks) {
    return probeSyncDetailed(
        worldFolder, dimensionSubpath, cx, cz, rawUnsafeBlocks, DEFAULT_RECONCILER)
        .verdict();
  }

  /**
   * Synchronous detailed-probe entry point. Returns both the verdict and, on ACCEPT,
   * the decoded {@link AnvilChunkView}. Exposed for tests and for
   * {@link #probeDetailed(Path, String, int, int, Set, UnaryOperator)}.
   *
   * <p>This three-arg overload uses the {@link #DEFAULT_RECONCILER}; platform adapters
   * that need a registry-aware reconciler (e.g. {@code Material.matchMaterial} on
   * Spigot) call the six-arg overload directly.
   */
  public static ProbeResult probeSyncDetailed(
      Path worldFolder, String dimensionSubpath, int cx, int cz, Set<String> rawUnsafeBlocks) {
    return probeSyncDetailed(
        worldFolder, dimensionSubpath, cx, cz, rawUnsafeBlocks, DEFAULT_RECONCILER);
  }

  /**
   * Reconciler-aware synchronous detailed-probe entry point. The reconciler is applied
   * to both the {@code rawUnsafeBlocks} set and to every palette identifier read from
   * the region file, so platform-side normalisation (e.g. Bukkit {@code Material} lookup)
   * stays consistent on both sides of the comparison.
   */
  public static ProbeResult probeSyncDetailed(
      Path worldFolder, String dimensionSubpath, int cx, int cz,
      Set<String> rawUnsafeBlocks, UnaryOperator<String> reconciler) {
    final UnaryOperator<String> r = (reconciler == null) ? DEFAULT_RECONCILER : reconciler;
    final Set<String> reconciledUnsafe = reconcileAll(rawUnsafeBlocks, r);
    try {
      Path regionFile = regionFileFor(worldFolder, dimensionSubpath, cx, cz);
      if (!Files.isRegularFile(regionFile)) {
        // Chunk has never been generated and persisted; the live load path will generate
        // it if needed. The pre-filter cannot reject what does not exist on disk.
        diagLog("UNKNOWN:no-region-file(" + regionFile + ")",
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }
      byte[] regionBytes = Files.readAllBytes(regionFile);
      int rx = Math.floorMod(cx, 32);
      int rz = Math.floorMod(cz, 32);

      AnvilReader.ChunkEntry entry = AnvilReader.readChunk(regionBytes, rx, rz);
      if (entry == null) {
        // Location entry is zeroed — chunk slot unused in this region file.
        diagLog("UNKNOWN:empty-location-entry",
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }

      int dataVersion = AnvilReader.getDataVersion(entry.root);
      if (!DataVersionSupport.isSupported(dataVersion)) {
        diagLog("UNKNOWN:unsupported-dataversion=" + dataVersion,
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }

      long[] packedHeightmap = AnvilReader.getMotionBlockingNoLeaves(entry.root);
      if (packedHeightmap == null) {
        diagLog("UNKNOWN:missing-heightmap(MOTION_BLOCKING_NO_LEAVES)",
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }

      AnvilChunkView view = AnvilReader.toView(entry.root);
      if (view.sections().isEmpty()) {
        diagLog("UNKNOWN:no-sections",
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }
      // 2026-04-20 housekeeping: VIEW-DECODED is the happy-path confirmation
      // that a chunk decoded cleanly. Log at FINE only — the probe outcome
      // (PUBLISH) already logs at FINE in AnvilProbeSupport, and the
      // `rtp test biome-source` counters (`anvil-hit`) are the authoritative
      // steady-state signal. The UNKNOWN:* diag lines above remain at INFO
      // (rate-limited) because those flag real decode problems.
      if (LOG.isLoggable(Level.FINE)) {
        LOG.log(Level.FINE,
            "[RTP] Anvil probe VIEW-DECODED:dataVersion=" + dataVersion
                + ",sections=" + view.sections().size()
                + " world=" + worldFolder
                + " dim=\"" + dimensionSubpath + "\""
                + " chunk=(" + cx + "," + cz + ")");
      }

      // The heightmap is stored relative to the world's minimum build height. We don't
      // know that minimum from the NBT root alone (it's implicit from the section Y
      // range). Use the lowest emitted section as the floor.
      int minHeight = view.minHeight();

      // Sample every (x, z) column in the chunk (256 columns). Reject on the first
      // unsafe surface. This is bounded: 256 column lookups, 3 blocks each = 768
      // palette resolves in the worst case, all off-thread.
      if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) {
        return new ProbeResult(Verdict.ACCEPT, view);
      }
      for (int lx = 0; lx < 16; lx++) {
        for (int lz = 0; lz < 16; lz++) {
          int rawHeight = readHeightmapEntry(packedHeightmap, lx, lz);
          if (rawHeight <= 0) continue; // Empty column — nothing to sample.
          int groundY = minHeight + rawHeight - 1;
          if (isUnsafe(view, lx, groundY, lz, reconciledUnsafe, r)
              || isUnsafe(view, lx, groundY + 1, lz, reconciledUnsafe, r)
              || isUnsafe(view, lx, groundY + 2, lz, reconciledUnsafe, r)) {
            // ADR-016: REJECT is now advisory — the view is still returned so the
            // caller (BukkitRTPWorld.getChunkAt) can mint an Anvil-backed RTPChunk
            // and let the vert adjustor scan below the unsafe surface for a safe Y.
            // The live chunk.isSafe(...) re-check at teleport commit (ADR-016 §4)
            // remains authoritative.
            return new ProbeResult(Verdict.REJECT, view);
          }
        }
      }
      return new ProbeResult(Verdict.ACCEPT, view);
    } catch (CorruptRegionEntryException e) {
      // Known failure type: a single chunk entry in the region file is corrupt or
      // truncated (e.g. location-table pointer spans past EOF, implausible declared
      // length, region buffer shorter than header). The live load path handles this
      // transparently, so log it at INFO via the same rate-limited diag channel used
      // for other UNKNOWN:* outcomes — no stacktrace needed.
      diagLog("UNKNOWN:corrupt-region-entry(" + e.getMessage() + ")",
          worldFolder, dimensionSubpath, cx, cz);
      return new ProbeResult(Verdict.UNKNOWN, null);
    } catch (IOException | RuntimeException e) {
      // Any other decode failure falls through to the live load path. Intentional: we
      // never want the pre-filter to be load-bearing for correctness. Logged at WARNING
      // with stacktrace so operators can tell a real mca read failure apart from a
      // legitimate "chunk not yet persisted" UNKNOWN (see rtp-anvil/AGENTS notes on
      // S-004 spirit).
      LOG.log(Level.WARNING,
          "[RTP] Anvil probe failed for world=" + worldFolder
              + " dim=\"" + dimensionSubpath + "\" chunk=(" + cx + "," + cz
              + ") — falling through to live chunk load",
          e);
      return new ProbeResult(Verdict.UNKNOWN, null);
    }
  }

  /**
   * Resolve the {@code r.X.Z.mca} path for the chunk at absolute coords {@code (cx, cz)}.
   * Vanilla dimensions store region files at:
   * <ul>
   *   <li>Overworld: {@code <world>/region/r.X.Z.mca}</li>
   *   <li>Nether: {@code <world>/DIM-1/region/r.X.Z.mca}</li>
   *   <li>End: {@code <world>/DIM1/region/r.X.Z.mca}</li>
   * </ul>
   */
  public static Path regionFileFor(Path worldFolder, String dimensionSubpath, int cx, int cz) {
    int regionX = cx >> 5;
    int regionZ = cz >> 5;
    String filename = "r." + regionX + "." + regionZ + ".mca";
    if (dimensionSubpath.isEmpty()) {
      return worldFolder.resolve("region").resolve(filename);
    }
    return worldFolder.resolve(dimensionSubpath).resolve("region").resolve(filename);
  }

  /**
   * Reconcile every non-null entry of {@code raw} via {@code reconciler} into an
   * insertion-ordered, immutable {@link Set}. {@code null} or empty results are
   * silently dropped; duplicates after reconciliation are coalesced.
   *
   * <p>Inlined here rather than depending on a Spigot-side {@code PaletteNormalizer}
   * helper so {@code rtp-anvil} can remain platform-neutral (ADR-016).
   */
  private static Set<String> reconcileAll(
      Set<String> raw, UnaryOperator<String> reconciler) {
    if (raw == null || raw.isEmpty()) return Set.of();
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      String n = reconciler.apply(s);
      if (n != null && !n.isEmpty()) out.add(n);
    }
    return java.util.Collections.unmodifiableSet(out);
  }

  /**
   * Read a single {@code (x, z)} column height from a packed {@code long[]} heightmap
   * using the 1.18+ no-cross-long bit layout ({@code bitsPerEntry = 9}). The column
   * index within the chunk is {@code z * 16 + x}.
   */
  static int readHeightmapEntry(long[] packed, int x, int z) {
    if (packed == null || packed.length == 0) return -1;
    int bitsPerEntry = MOTION_BLOCKING_NO_LEAVES_BITS;
    int entriesPerLong = 64 / bitsPerEntry;
    int columnIndex = z * 16 + x;
    int longIndex = columnIndex / entriesPerLong;
    if (longIndex >= packed.length) return -1;
    int bitOffset = (columnIndex % entriesPerLong) * bitsPerEntry;
    long mask = (1L << bitsPerEntry) - 1L;
    return (int) ((packed[longIndex] >>> bitOffset) & mask);
  }

  private static boolean isUnsafe(
      AnvilChunkView view, int x, int worldY, int z,
      Set<String> reconciledUnsafe, UnaryOperator<String> reconciler) {
    String blockId = view.blockIdAt(x, worldY, z);
    if (blockId == null) return false;
    if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return false;
    String n = reconciler.apply(blockId);
    return n != null && !n.isEmpty() && reconciledUnsafe.contains(n);
  }
}
