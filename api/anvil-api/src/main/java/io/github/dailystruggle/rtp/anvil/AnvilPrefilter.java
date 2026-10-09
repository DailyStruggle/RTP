package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only Anvil pre-filter (ADR-016). Off-tick parses {@code r.X.Z.mca}
 * to reject candidates whose on-disk surface stack hits the caller's unsafe set;
 * all other outcomes ({@link Verdict#ACCEPT} / {@link Verdict#UNKNOWN}) fall through
 * to the live chunk load, where {@code RTPChunk.isSafe(...)} remains authoritative.
 *
 * <p>The asynchronous {@code probeDetailed} entry point schedules I/O + NBT walk on
 * {@link ForkJoinPool#commonPool()}; do not block the returned future on a tick thread.
 * Only thread-safe platform accessors are touched (world folder, environment, generator).
 *
 * <p>Callers gate the probe behind the four checks from ADR-016 section 3 (config flag,
 * chunk not loaded, no custom generator, structural cache miss). Internally the only
 * additional gate is {@link DataVersionSupport#isSupported(int)}; out-of-whitelist
 * versions return {@link Verdict#UNKNOWN}.
 *
 * <p>Heightmap: {@code MOTION_BLOCKING_NO_LEAVES} is a packed {@code long[]} at
 * 9 bits/entry on 1.18+. The array stores column heights relative to {@code minHeight};
 * the first motion-blocking block (scanning down) is at {@code (height-1)+minHeight}.
 * The pre-filter samples that ground block plus feet (surface) and head (surface+1).
 */
public final class AnvilPrefilter {

  /** Module-local JDK logger (zero RTP/Bukkit deps; see {@code AnvilPackageBoundaryArchTest}). */
  private static final Logger LOG = Logger.getLogger(AnvilPrefilter.class.getName());

  /** 1.18+ {@code MOTION_BLOCKING_NO_LEAVES} packed-array bits/entry
   *  ({@code ceil(log2(worldHeight+1))}; 9 for a 384-block overworld). */
  private static final int MOTION_BLOCKING_NO_LEAVES_BITS = 9;

  /** Default reconciler: strip {@code namespace:}, uppercase via {@link Locale#ROOT}.
   *  Inlined (not delegated to {@code PaletteIdentifierNormalizer}) to keep this
   *  module zero-RTP-dep per ADR-016. */
  public static final UnaryOperator<String> DEFAULT_RECONCILER = raw -> {
    if (raw == null) return null;
    int colon = raw.indexOf(':');
    String stripped = (colon >= 0) ? raw.substring(colon + 1) : raw;
    return stripped.toUpperCase(Locale.ROOT);
  };

  private AnvilPrefilter() {
    // Utility class.
  }

  /** Per-JVM, per-reason cap on diagnostic INFO logs (drops to FINE thereafter). */
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
   * {@link Level#FINE} so they appear in standard server logs; once the budget
   * is exhausted the logger falls back to {@link Level#FINER}.
   */
  private static void diagLog(String reason, Path worldFolder, String dim,
                              int cx, int cz) {
    AtomicInteger counter =
        DIAG_LOG_COUNTERS.computeIfAbsent(reason, k -> new AtomicInteger());
    int n = counter.incrementAndGet();
    Level level = (n <= DIAG_LOG_BUDGET_PER_REASON) ? Level.FINE : Level.FINER;
    if (LOG.isLoggable(level)) {
      LOG.log(level,
          "[RTP] Anvil probe " + reason + " world=" + worldFolder
              + " dim=\"" + dim + "\" chunk=(" + cx + "," + cz + ")"
              + (n > DIAG_LOG_BUDGET_PER_REASON
                  ? " (further occurrences suppressed to FINER)"
                  : ""));
    }
  }

  /**
   * Result of a detailed probe: the {@link Verdict} and the decoded
   * {@link AnvilChunkView} that the verdict was computed from.
   *
   * <p>Under ADR-016, the view is attached on both {@link Verdict#ACCEPT} and
   * {@link Verdict#REJECT} - the verdict becomes an advisory telemetry signal
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
   * Asynchronously probe a chunk and return both the verdict and the
   * decoded {@link AnvilChunkView} (on ACCEPT). Callers that want only the verdict
   * use {@link #probeSync(Path, String, int, int, Set)}; callers that want the
   * load-free chunk source use this entry point.
   *
   * <p>On {@link Verdict#REJECT} or {@link Verdict#UNKNOWN}, {@link ProbeResult#view()}
   * is {@code null} and the caller falls through to the live-load path. On
   * {@link Verdict#ACCEPT}, the view is the same one used to compute the verdict -
   * reusing it avoids a second region-file read.
   *
   * <p>Dispatches onto {@link AnvilIoPool#get()} by default to prevent blocking I/O
   * starvation on {@link ForkJoinPool#commonPool()}.</p>
   */
  public static CompletableFuture<ProbeResult> probeDetailed(
      Path worldFolder, String dimensionSubpath, int cx, int cz,
      Set<String> rawUnsafeBlocks, UnaryOperator<String> reconciler) {
    return probeDetailed(worldFolder, dimensionSubpath, cx, cz, rawUnsafeBlocks, reconciler, AnvilIoPool.get());
  }

  /**
   * Asynchronously probe a chunk using the specified executor.
   */
  public static CompletableFuture<ProbeResult> probeDetailed(
      Path worldFolder, String dimensionSubpath, int cx, int cz,
      Set<String> rawUnsafeBlocks, UnaryOperator<String> reconciler, Executor executor) {
    if (worldFolder == null) {
      return CompletableFuture.completedFuture(new ProbeResult(Verdict.UNKNOWN, null));
    }
    final String dim = (dimensionSubpath == null) ? "" : dimensionSubpath;
    final UnaryOperator<String> r = (reconciler == null) ? DEFAULT_RECONCILER : reconciler;
    final Executor exec = (executor == null) ? AnvilIoPool.get() : executor;
    return CompletableFuture.supplyAsync(
        () -> probeSyncDetailed(worldFolder, dim, cx, cz, rawUnsafeBlocks, r),
        exec);
  }

  /**
   * Synchronous probe entry point. Exposed for tests that want deterministic dispatch
   * without the {@link ForkJoinPool} hop. Not intended for production callers.
   *
   * <p>Accepts the raw config-side unsafe list and reconciles it internally via the
   * platform reconciler so that tests and production callers follow the same
   * canonical-form pipeline on both sides of the comparison.</p>
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
    try (RegionProbeSession s = openSession(worldFolder, dimensionSubpath, rawUnsafeBlocks, reconciler)) {
      return s.probe(cx, cz);
    }
  }

  /**
   * Batched synchronous probe: one {@link RegionProbeSession} per region file, so every chunk
   * stored in the same {@code r.X.Z.mca} shares one channel open and one location table. Verdicts
   * are identical to {@link #probeSyncDetailed(Path, String, int, int, Set, UnaryOperator)} per
   * chunk; failures are isolated per chunk and fail closed to {@link Verdict#UNKNOWN} (S-004).
   * Blocking I/O: off the tick thread only (S-005).
   *
   * @return insertion-ordered verdict per distinct requested chunk (never {@code null})
   */
  public static Map<ChunkCoord, ProbeResult> probeBatchSyncDetailed(
      Path worldFolder, String dimensionSubpath, List<ChunkCoord> chunks,
      Set<String> rawUnsafeBlocks, UnaryOperator<String> reconciler) {
    Map<ChunkCoord, ProbeResult> out = new LinkedHashMap<>();
    if (chunks == null || chunks.isEmpty()) return out;
    Map<Long, List<ChunkCoord>> byRegion = new LinkedHashMap<>();
    for (ChunkCoord c : chunks) {
      if (c == null) continue;
      byRegion.computeIfAbsent(c.regionKey(), k -> new ArrayList<>()).add(c);
    }
    // Reconcile the unsafe set once for the whole batch.
    final UnaryOperator<String> r = (reconciler == null) ? DEFAULT_RECONCILER : reconciler;
    final Set<String> reconciledUnsafe = reconcileAll(rawUnsafeBlocks, r);
    for (List<ChunkCoord> group : byRegion.values()) {
      try (RegionProbeSession s = new RegionProbeSession(worldFolder, dimensionSubpath, reconciledUnsafe, r)) {
        for (ChunkCoord c : group) {
          if (!out.containsKey(c)) out.put(c, s.probe(c.x(), c.z()));
        }
      }
    }
    return out;
  }

  /**
   * Opens a probe session: chunks of the same region file probed through it share one channel open
   * (lazy, on the first chunk that needs the disk) and one byte-cache lease. Close it with
   * try-with-resources; nothing stays open past {@link RegionProbeSession#close()}. One thread.
   */
  public static RegionProbeSession openSession(
      Path worldFolder, String dimensionSubpath, Set<String> rawUnsafeBlocks, UnaryOperator<String> reconciler) {
    final UnaryOperator<String> r = (reconciler == null) ? DEFAULT_RECONCILER : reconciler;
    return new RegionProbeSession(worldFolder, dimensionSubpath, reconcileAll(rawUnsafeBlocks, r), r);
  }

  /** See {@link #openSession(Path, String, Set, UnaryOperator)}. */
  public static final class RegionProbeSession implements AutoCloseable {
    private final Path worldFolder;
    private final String dim;
    private final Set<String> reconciledUnsafe;
    private final UnaryOperator<String> reconciler;
    private long boundRegion;
    private boolean bound;
    private boolean resolveTried;
    private RegionFileResolver.ResolvedRegion resolved;
    private AnvilSectorReader.Session sectors;
    private AnvilRegionByteCache.Lease lease;
    private boolean leaseTried;

    private RegionProbeSession(Path worldFolder, String dim, Set<String> reconciledUnsafe,
                               UnaryOperator<String> reconciler) {
      this.worldFolder = worldFolder;
      this.dim = (dim == null) ? "" : dim;
      this.reconciledUnsafe = reconciledUnsafe;
      this.reconciler = reconciler;
    }

    /**
     * Probe absolute chunk {@code (cx, cz)}. The session binds to the first chunk's region file;
     * a chunk from another file is probed through a throwaway session (correct, not batched).
     */
    public ProbeResult probe(int cx, int cz) {
      long region = ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFF_FFFFL);
      if (bound && region != boundRegion) {
        try (RegionProbeSession other = new RegionProbeSession(worldFolder, dim, reconciledUnsafe, reconciler)) {
          return other.probe(cx, cz);
        }
      }
      if (!bound) {
        bound = true;
        boundRegion = region;
      }
      return probeIn(this, worldFolder, dim, cx, cz, reconciledUnsafe, reconciler);
    }

    /** Region file of the bound region, resolved once per session; {@code null} when absent. */
    private RegionFileResolver.ResolvedRegion resolve(int cx, int cz) {
      if (!resolveTried) {
        resolveTried = true;
        // Memoized: no existence syscalls inside the revalidation window.
        resolved = RegionFileResolver.resolveExisting(worldFolder, dim, cx, cz);
      }
      return resolved;
    }

    /** Decodes {@code (cx, cz)} through this session's channel or lease. */
    private AnvilReader.ChunkEntry readEntry(int cx, int cz) throws IOException {
      Path regionFile = resolved.path();
      int rx = Math.floorMod(cx, 32);
      int rz = Math.floorMod(cz, 32);
      if (resolved.reader() == AnvilReader.INSTANCE) {
        if (!leaseTried) {
          leaseTried = true;
          lease = residentLease(regionFile);
        }
        if (lease != null) {
          return AnvilReader.readChunkEntry(lease.buffer(), lease.length(), rx, rz);
        }
        if (sectors == null) sectors = AnvilSectorReader.openSession(regionFile);
        return sectors.readChunkEntry(cx, cz);
      }
      // Addon-registered formats: whole file under one lease for the session (layout is opaque).
      if (!leaseTried) {
        leaseTried = true;
        lease = AnvilRegionByteCache.acquire(regionFile);
      }
      if (lease == null) throw new RegionUnreadableException();
      return resolved.reader().readChunk(lease.buffer(), lease.length(), rx, rz);
    }

    @Override
    public void close() {
      AnvilRegionByteCache.Lease l = lease;
      lease = null;
      AnvilSectorReader.Session s = sectors;
      sectors = null;
      if (l != null) l.close();
      if (s != null) {
        try {
          s.close();
        } catch (IOException e) {
          LOG.log(Level.FINE, "[RTP] Anvil probe session close failed: " + s.regionFile(), e);
        }
      }
    }
  }

  private static ProbeResult probeIn(
      RegionProbeSession session, Path worldFolder, String dimensionSubpath, int cx, int cz,
      Set<String> reconciledUnsafe, UnaryOperator<String> r) {
    try {
      RegionFileResolver.ResolvedRegion resolved = session.resolve(cx, cz);
      if (resolved == null) {
        // Chunk has never been generated and persisted; the live load path will generate
        // it if needed. The pre-filter cannot reject what does not exist on disk.
        diagLog("UNKNOWN:no-region-file(" + RegionFileResolver.regionDirectoryFor(worldFolder, dimensionSubpath)
                .resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca") + ")",
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }
      AnvilReader.ChunkEntry entry;
      try {
        entry = session.readEntry(cx, cz);
      } catch (RegionUnreadableException e) {
        // File vanished or could not be read as a whole. Fall through to the live load path.
        diagLog("UNKNOWN:region-read-failed",
            worldFolder, dimensionSubpath, cx, cz);
        return new ProbeResult(Verdict.UNKNOWN, null);
      }
      if (entry == null) {
        // Location entry is zeroed - chunk slot unused in this region file.
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
      if (packedHeightmap == null || packedHeightmap.length == 0) {
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
      // that a chunk decoded cleanly. Log at FINE only - the probe outcome
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

      // If any section has malformed data, fail closed to UNKNOWN rather than ACCEPT
      for (PaletteSection s : view.sections()) {
        if (s.hasMalformedData()) {
          diagLog("UNKNOWN:malformed-palette", worldFolder, dimensionSubpath, cx, cz);
          return new ProbeResult(Verdict.UNKNOWN, null);
        }
      }

      // Sample every (x, z) column in the chunk (256 columns). Reject on the first
      // unsafe surface. This is bounded: 256 column lookups, 3 blocks each = 768
      // palette resolves in the worst case, all off-thread.
      if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) {
        return new ProbeResult(Verdict.ACCEPT, view);
      }
      for (int lx = 0; lx < 16; lx++) {
        for (int lz = 0; lz < 16; lz++) {
          int rawHeight = readHeightmapEntry(packedHeightmap, lx, lz);
          if (rawHeight <= 0) continue; // Empty column - nothing to sample.
          int groundY = minHeight + rawHeight - 1;
          if (isMalformed(view, lx, groundY, lz)
              || isMalformed(view, lx, groundY + 1, lz)
              || isMalformed(view, lx, groundY + 2, lz)) {
            diagLog("UNKNOWN:malformed-palette-ground", worldFolder, dimensionSubpath, cx, cz);
            return new ProbeResult(Verdict.UNKNOWN, null);
          }
          if (isUnsafe(view, lx, groundY, lz, reconciledUnsafe, r)
              || isUnsafe(view, lx, groundY + 1, lz, reconciledUnsafe, r)
              || isUnsafe(view, lx, groundY + 2, lz, reconciledUnsafe, r)) {
            // ADR-016: REJECT is now advisory - the view is still returned so the
            // caller (BukkitRTPWorld.getChunkAt) can mint an Anvil-backed RTPChunk
            // and let the vert adjustor scan below the unsafe surface for a safe Y.
            // The live chunk.isSafe(...) re-check at teleport commit (ADR-016 section 4)
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
      // for other UNKNOWN:* outcomes - no stacktrace needed.
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

  /** Whole-file read of a non-Anvil region failed (missing or unreadable). */
  private static final class RegionUnreadableException extends IOException {
    private static final long serialVersionUID = 1L;
  }

  /**
   * Byte-cache lease for a whole file some sweep already loaded, validated against the
   * location-table cache's in-window mtime. Never stats or reads; {@code null} otherwise.
   */
  static AnvilRegionByteCache.Lease residentLease(Path regionFile) {
    AnvilRegionHeaderCache.Header header = AnvilRegionHeaderCache.fresh(regionFile);
    return header == null ? null : AnvilRegionByteCache.acquireIfCached(regionFile, header.mtime());
  }

  /**
   * Center-column probe of absolute chunk {@code (cx, cz)} for every platform adapter's
   * {@code probeChunkColumn}. Reads only the chunk's own {@code .mca} sectors (ADR-016); returns
   * {@code null} when no {@code .mca} exists, the slot is empty, or the region uses another
   * format (the caller falls back to the live path). Blocking I/O: run on {@link AnvilIoPool}
   * (S-005).
   *
   * @throws IOException on unreadable files or corrupt/unsupported payloads
   */
  public static ColumnProbe probeColumn(Path worldFolder, String dimensionSubpath, int cx, int cz,
                                        int minY, int maxY) throws IOException {
    if (worldFolder == null || minY > maxY) return null;
    String dim = (dimensionSubpath == null) ? "" : dimensionSubpath;
    RegionFileResolver.ResolvedRegion resolved = RegionFileResolver.resolveExisting(worldFolder, dim, cx, cz);
    if (resolved == null || resolved.reader() != AnvilReader.INSTANCE) return null;
    Path regionFile = resolved.path();
    try (AnvilRegionByteCache.Lease lease = residentLease(regionFile)) {
      if (lease != null) {
        return AnvilReader.readColumnProbe(lease.buffer(), lease.length(),
            Math.floorMod(cx, 32), Math.floorMod(cz, 32), minY, maxY);
      }
    }
    return AnvilSectorReader.readColumnProbe(regionFile, cx, cz, minY, maxY);
  }

  private static final ColumnProbeCoalescer COLUMN_PROBES = new ColumnProbeCoalescer(AnvilIoPool.get());

  /**
   * Asynchronous {@link #probeColumn}: pending requests for the same region file are coalesced and
   * served on {@link AnvilIoPool} through one channel open (group commit, at most 64 per group,
   * no added delay). Answers equal
   * {@link #probeColumn}; a failure completes only that request's future exceptionally (S-004).
   * Performs no I/O on the calling thread (S-005); the future completes on a pool thread.
   */
  public static CompletableFuture<ColumnProbe> probeColumnAsync(
      Path worldFolder, String dimensionSubpath, int cx, int cz, int minY, int maxY) {
    if (worldFolder == null || minY > maxY) return CompletableFuture.completedFuture(null);
    String dim = (dimensionSubpath == null) ? "" : dimensionSubpath;
    return COLUMN_PROBES.submit(worldFolder, dim, cx, cz, minY, maxY);
  }

  /** Region-file groups drained by {@link #probeColumnAsync} (opens are at most this). */
  public static long columnProbeGroups() {
    return COLUMN_PROBES.groupsDrained();
  }

  /** Requests served by {@link #probeColumnAsync}; divided by {@link #columnProbeGroups()} = mean group size. */
  public static long columnProbeRequests() {
    return COLUMN_PROBES.requestsServed();
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

  private static boolean isMalformed(AnvilChunkView view, int x, int worldY, int z) {
    int sy = Math.floorDiv(worldY, 16);
    return view.hasSection(sy) && view.blockIdAt(x, worldY, z) == null;
  }
}
