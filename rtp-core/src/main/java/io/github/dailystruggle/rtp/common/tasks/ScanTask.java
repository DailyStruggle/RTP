package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.BiomeNames;
import io.github.dailystruggle.rtp.common.selection.region.MaterialNames;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Task for pre-scanning a region with valid teleport locations */
public class ScanTask extends RTPRunnable {
  /** Spark-profiler frame tag (diagram 05). See {@link RTPRunnable#sparkFrameName()}. */
  @Override
  protected String sparkFrameName() { return "rtp_scan_crawler"; }

  /** Number of locations to process in each step */
  public final AtomicLong scanIncrement = new AtomicLong(0L);
  private final AtomicLong cps = new AtomicLong(128);
  private static final BigInteger increment_big = new BigInteger("1");

  // Instance trackers to natively isolate data per ScanTask
  public long latestAbsolutePos = 0;
  public long latestAbsoluteTotal = 0;
  public long latestCps = 0;
  public long latestEtaSeconds = 0;

  private final Region region;
  private final AtomicLong scanIter;
  private final CompletableFuture<Boolean> done = new CompletableFuture<>();
  private long currentOffset = 0L;

  /**
   * Three-pass scan phase:
   *   2 = GENSCAN (generation-pass; for every candidate ask
   *       {@link RTPWorld#isChunkGenerated(int,int)}. Already-generated chunks
   *       are skipped (PRESCAN will visit them via the anvil probe). Not-yet-
   *       generated chunks are routed directly to {@code runFullLoadPath},
   *       which loads/generates the chunk and authoritatively validates it in
   *       a single I/O so we never load the same chunk twice. Short-lived:
   *       not persisted to the .scan file — a resumed scan begins at the
   *       persisted PRESCAN/FULLSCAN phase and skips GENSCAN entirely, since
   *       chunks generated in the prior run are still generated on disk.
   *   0 = PRESCAN (anvil probe sweep; cheap rejects only, probe-accept
   *       trusted without full-load verification).
   *   1 = FULLSCAN (classic full chunk-load sweep over positions not already
   *       marked bad by GENSCAN/PRESCAN, authoritative (2r+1)^3 safety scan).
   * The PRESCAN/FULLSCAN byte is persisted in the .scan progress file so a
   * killed/resumed scan continues in the correct phase. GENSCAN is never
   * persisted (collapsed to PRESCAN on save).
   */
  public static final int PHASE_PRESCAN = 0;
  public static final int PHASE_FULLSCAN = 1;
  public static final int PHASE_GENSCAN = 2;
  // GENSCAN disabled (2026-05-25): the dedicated chunk-generation pre-pass was
  // observed to run very slowly in practice (sustained cps in the low tens on
  // backend-b devstack). Start directly in PRESCAN; if PRESCAN encounters an
  // ungenerated chunk it flips the whole scan to FULLSCAN (which loads/
  // generates and authoritatively validates in a single I/O). GENSCAN code
  // paths are retained but unreachable from the initial phase.
  private final AtomicInteger scanPhase = new AtomicInteger(PHASE_PRESCAN);

  /** Whether the task is currently paused */
  public AtomicBoolean pause = new AtomicBoolean(false);

  private static final int MAX_PENDING_CHUNKS = 50;
  private final Semaphore inFlightGate = new Semaphore(MAX_PENDING_CHUNKS);

  // Upper bound (ms) on how long a cancel/pause drain will block the caller
  // (typically the command / main thread) waiting for in-flight chunk futures
  // to settle. A hung or timing-out chunk load must never freeze the server, so
  // the drain is bounded: once the cancelled/pause flag is set, late callbacks
  // are already guarded (they check isCancelled()/pause and complete false
  // without mutating shape state), so proceeding after the timeout is safe.
  private static final long DRAIN_TIMEOUT_MS = 5_000L;

  // In-flight chunk-load futures dispatched by runFullLoadPath. Tracked so a
  // cancel/pause can actively cancel them instead of waiting out the per-future
  // 30s orTimeout one by one — cancelling completes our future chain promptly,
  // releases the inFlightGate, and stops the timeout-exception log spam.
  private final Set<CompletableFuture<?>> inFlightChunkFutures = ConcurrentHashMap.newKeySet();

  /*
   * [TRACE] PR-8 diagnostics: concurrency gauge for the scan driver. Incremented at dispatch,
   * decremented on future completion. `peakInFlight` tracks the max observed in a batch; both are
   * logged + reset in wrapUpBatch. If peak stays ≪ MAX_PENDING_CHUNKS, the driver is the bottleneck,
   * not the I/O pool.
   */
  private final AtomicInteger inFlight = new AtomicInteger(0);
  private final AtomicInteger peakInFlight = new AtomicInteger(0);

  /*
   * PR-16 diagnostic: cumulative GC time (ms) observed at the start of the previous gauge
   * log. Delta = current - last, reported as gcDeltaMs in the log line. If this climbs with
   * scan progress it confirms the 4MB-per-region byte[] churn is pressuring the collector.
   */
  private long lastGcTotalMs = -1L;

  /*
   * PR-17 diagnostic: stage-2 (full chunk load + safety scan) cost. Count and total wall-clock
   * nanos accumulated per gauge window. Reported as fullLoads / fullLoadAvgMs. If fullLoadAvgMs
   * grows with scan progress while probe I/O (avgColdMissMs) stays flat, the degradation is in
   * stage-2 (loaded-chunk path), not the probe prefilter.
   */
  private final java.util.concurrent.atomic.AtomicLong fullLoadCount = new java.util.concurrent.atomic.AtomicLong(0L);
  private final java.util.concurrent.atomic.AtomicLong fullLoadNanos = new java.util.concurrent.atomic.AtomicLong(0L);

  /*
   * PR-19: per-outcome counters for the probe-first fast path. Diagnostic only; emitted on the
   * concurrency gauge log. Tells us which branch of evaluateScanProbe / tryProbeFirstScan is
   * consuming each candidate. After PR-18 fullLoads stayed ~= activeChecks despite the minY-1
   * fix, meaning something else is routing every candidate to runFullLoadPath. These counters
   * split that bucket so we can target the real cause.
   */
  private final AtomicLong probeOutcomeProbeNull = new AtomicLong(0L);
  private final AtomicLong probeOutcomeAdjustNull = new AtomicLong(0L);
  /*
   * PR-20: sub-attribution of probeOutcomeAdjustNull. The single adjustNull
   * counter was ambiguous across platforms (Paper-vs-Folia asymmetry couldn't
   * be diagnosed without a debugger). These four buckets are mutually
   * exclusive and sum to adjustNull. Reasons map to VerticalAdjustor.
   * ProbeRejectReason: LIGHT_GATE / WINDOW / SCAN_MISS / THREW.
   */
  private final AtomicLong probeOutcomeAdjustNullLight = new AtomicLong(0L);
  private final AtomicLong probeOutcomeAdjustNullWindow = new AtomicLong(0L);
  private final AtomicLong probeOutcomeAdjustNullScan = new AtomicLong(0L);
  private final AtomicLong probeOutcomeAdjustNullThrew = new AtomicLong(0L);
  /*
   * Counts SCAN_MISS verdicts that were short-circuited to addBadLocation
   * without dispatching runFullLoadPath. After the multi-column probe sweep
   * (JumpAdjustor / LinearAdjustor), probe SCAN_MISS is authoritative across
   * the same testCoords columns the live path would scan, so the chunk has
   * no acceptable Y and a full chunk load would only confirm the verdict.
   * Subset of probeOutcomeAdjustNullScan: every increment here also
   * increments probeOutcomeAdjustNullScan. Reported as
   * {@code adjustNull=...(scan=S scanShortCircuit=K)} so K/S = fraction of
   * SCAN_MISS cases that bypassed the full-load path.
   */
  private final AtomicLong probeOutcomeAdjustNullScanShortCircuit = new AtomicLong(0L);
  private final AtomicLong probeOutcomeBiomeReject = new AtomicLong(0L);
  private final AtomicLong probeOutcomeBlockReject = new AtomicLong(0L);
  private final AtomicLong probeOutcomeCacheMissAccept = new AtomicLong(0L);
  private final AtomicLong probeOutcomeCacheHitLoad = new AtomicLong(0L);

  /*
   * GENSCAN attribution (2026-05-19): per-batch counts of the isChunkGenerated
   * verdict during the GENSCAN phase. Reported on the same concurrency gauge
   * line as the probe outcome stats. Interpretation:
   *   genscanGenerated   = chunk already generated on disk (PRESCAN handles it)
   *   genscanUngenerated = chunk missing on disk (GENSCAN routes through full load)
   *   genscanThrew       = isChunkGenerated raised; treated conservatively as generated
   * If genscanUngenerated == 0 across an entire GENSCAN sweep on a freshly-spawned
   * world, the platform RTPWorld.isChunkGenerated implementation is reporting
   * everything as already-generated (wrong data path) and PRESCAN will then fall
   * back to runFullLoadPath because the anvil probe has nothing to read.
   */
  private final AtomicLong genscanGenerated = new AtomicLong(0L);
  private final AtomicLong genscanUngenerated = new AtomicLong(0L);
  private final AtomicLong genscanThrew = new AtomicLong(0L);

  /*
   * Cumulative (not reset per batch) count of ungenerated chunks observed during
   * the GENSCAN sweep. When > 0 at the GENSCAN -> next-phase transition, the
   * region's chunks are not all on disk and the cheap anvil-probe PRESCAN sweep
   * cannot produce useful verdicts (probe returns null -> falls back to
   * runFullLoadPath one-by-one, tripping the 2s chunk-ticket budget). In that
   * case we skip PRESCAN entirely and go straight to FULLSCAN, which authoritatively
   * loads + verifies every remaining candidate via the live chunk-load path
   * (and generates the chunk in the same I/O). See genscan->fullscan flip in run().
   */
  private final AtomicLong genscanUngeneratedTotal = new AtomicLong(0L);

  /*
   * runFullLoadPath reject sub-attribution (2026-05-19). The single fullLoads
   * counter rolls up every call to the legacy load path but says nothing
   * about *why* candidates land in goodCount=0. These buckets split the
   * reject reasons of runFullLoadPath (mutually exclusive; one per call that
   * completed res=false). fullLoadAccept counts res=true completions and
   * should equal accepts-via-full-load. Reported on the diag line as
   *   fullLoadOutcome=accept=A timeoutOrNull=T midBiome=B vertAdjust=V
   *                   physBiome=P yOverflow=Y safetyScan=S crashed=X
   * If accept=0 across an entire PRESCAN/FULLSCAN sweep with land%=0, the
   * dominant non-zero bucket pinpoints the rejection cause.
   */
  private final AtomicLong fullLoadOutcomeAccept = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomeTimeoutOrNull = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomeMidBiome = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomeVertAdjust = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomePhysBiome = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomeYOverflow = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomeSafetyScan = new AtomicLong(0L);
  private final AtomicLong fullLoadOutcomeCrashed = new AtomicLong(0L);

  // Land-percentage accounting (2026-05-19). Reverted to deriving land% from
  // MemoryShape.getEffectiveBadCount() against the per-batch finalPos1 instead
  // of carrying parallel scanGoodCount/scanBadCount atomics. The shape's
  // totalBadCount is already incremented by `addBadChunk` at every reject
  // exit path in PRESCAN/FULLSCAN, so it is the authoritative reject count;
  // GENSCAN is suppressed at the call site because its "false" verdict means
  // "ungenerated chunk to skip", not "land/water reject", and never calls
  // addBadChunk. See the LAND PERCENTAGE CALCULATION block in wrapUpBatch.

  // Tracks whether the "prescan starting" announcement has been emitted for
  // this task instance. Symmetric with the existing "prescan complete; starting
  // full-load verification pass" log emitted at the PRESCAN -> FULLSCAN flip:
  // without it, users who run /rtp scan see no indication that the cheap probe
  // sweep (PRESCAN) is the active phase, and mistake long initial runtimes for
  // a stalled fullscan. Logged once per run() entry when phase==PRESCAN.
  private final AtomicBoolean prescanAnnounced = new AtomicBoolean(false);
  private final AtomicBoolean fullscanAnnounced = new AtomicBoolean(false);
  private final AtomicBoolean genscanAnnounced = new AtomicBoolean(false);

  private long lastSaveTime = 0;

  private BigInteger cps_all = new BigInteger("0");
  private BigInteger cps_divisor = new BigInteger("0");

  {
    RTP.futures.add(done);
  }

  /**
   * Constructor for ScanTask
   *
   * @param region the region to scan
   * @param start the starting location index
   */
  public ScanTask(Region region, long start) {
    this.region = region;
    this.scanIter = new AtomicLong(start);
    long[] progress = loadProgress(region.name, region.cacheKey());
    if (progress != null) {
      if (progress.length > 3) this.currentOffset = progress[3];
      // Persisted phase byte only stores PRESCAN(0)/FULLSCAN(1); a resumed
      // scan skips the transient GENSCAN pre-phase. See PHASE_GENSCAN docs.
      if (progress.length > 4) this.scanPhase.set((int) progress[4]);
    }

    if (scanIncrement.get() <= 0) {
      long cpu = Runtime.getRuntime().availableProcessors();
      scanIncrement.set(cpu * 1000 / 32);
    } else {
      // try for 5 seconds between messages
      scanIncrement.set(cps.get() * 5);
    }

    // Clear the shape only when starting from scratch (start==0 AND PRESCAN).
    // When resuming mid-FULLSCAN with scanIter persisted as 0 (fresh Pass 2),
    // the Pass 1 bad-location bitmap must be preserved so Pass 2 can skip
    // positions already marked bad.
    if(start == 0 && (scanPhase.get() == PHASE_PRESCAN || scanPhase.get() == PHASE_GENSCAN)) {
      if (region.shape instanceof MemoryShape<?> memoryShape) {
        memoryShape.clear();
      }
    }
  }

  /**
   * Constructor for ScanTask with additional parameters for performance tracking
   *
   * @param region the region to scan
   * @param start the starting location index
   * @param cps_all total completions per second
   * @param divisor divisor for performance tracking
   * @param scanIncrementVal current scanIncrement value to carry over
   * @param cpsVal current cps value to carry over
   */
  public ScanTask(Region region, long start, BigInteger cps_all, BigInteger divisor, long scanIncrementVal, long cpsVal) {
    this.region = region;
    this.scanIter = new AtomicLong(start);
    this.cps_all = cps_all;
    this.cps_divisor = divisor;
    this.cps.set(cpsVal);
    long[] progress = loadProgress(region.name, region.cacheKey());
    if (progress != null) {
      // Persisted phase byte only stores PRESCAN(0)/FULLSCAN(1); a resumed
      // scan skips the transient GENSCAN pre-phase. See PHASE_GENSCAN docs.
      if (progress.length > 4) this.scanPhase.set((int) progress[4]);
      if (progress.length > 3) this.currentOffset = progress[3];
    }

    if (scanIncrementVal <= 0) {
      long cpu = Runtime.getRuntime().availableProcessors();
      scanIncrement.set(cpu * 10000 / 64);
    } else {
      scanIncrement.set(scanIncrementVal);
    }
  }

  private static String phaseLabel(int phase) {
    if (phase == PHASE_GENSCAN) return "genscan";
    if (phase == PHASE_FULLSCAN) return "fullscan";
    return "prescan";
  }

  /** Stop all running scan tasks */
  public static void kill() {
    RTP.getInstance().scanTasks.forEach((s, scanTask) -> scanTask.setCancelled(true));
    RTP.getInstance().scanTasks.clear();
  }

  @Override
  public void run() {
    io.github.dailystruggle.rtp.common.tools.CfDiag.scanRunBatch.increment();
    io.github.dailystruggle.rtp.common.tools.CfDiag.ensureStarted();
    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }

    if (!isRunning.compareAndSet(false, true)) {
      RTP.log(Level.FINER, "[ScanTask] run() reentry skipped (already running) region=" + region.name);
      return;
    }

    RTP.log(Level.FINE, "[ScanTask] batch entry region=" + region.name
            + " phase=" + phaseLabel(scanPhase.get())
            + " scanIter=" + scanIter.get()
            + " scanIncrement=" + scanIncrement.get()
            + " currentOffset=" + currentOffset);

    // Announce the active scan phase at the beginning so users are not
    // confused by the three-pass design (GENSCAN -> PRESCAN -> FULLSCAN).
    // Phase transitions already log in wrapUpBatch; this covers the initial
    // entry into a phase (fresh start, resume after pause, or resume after
    // restart with a persisted phase).
    int currentPhase = scanPhase.get();
    if (currentPhase == PHASE_GENSCAN) {
      if (genscanAnnounced.compareAndSet(false, true)) {
        RTP.log(Level.INFO, "[RTP] starting genscan (chunk-generation sweep; anvil prefilter bypassed for ungenerated areas) for region=" + region.name);
      }
    } else if (currentPhase == PHASE_PRESCAN) {
      if (prescanAnnounced.compareAndSet(false, true)) {
        RTP.log(Level.INFO, "[RTP] starting prescan (anvil probe sweep) for region=" + region.name);
      }
    } else if (currentPhase == PHASE_FULLSCAN) {
      if (fullscanAnnounced.compareAndSet(false, true)) {
        RTP.log(Level.INFO, "[RTP] starting fullscan (full-load verification pass) for region=" + region.name);
      }
    }

    if (pause.get() || isCancelled() || scanIncrement.get() <= 0) {
      RTP.log(Level.FINE, "[ScanTask] batch short-circuit region=" + region.name
              + " pause=" + pause.get() + " cancelled=" + isCancelled()
              + " scanIncrement=" + scanIncrement.get());
      if (pause.get() || isCancelled()) {
        if (region.getShape() instanceof MemoryShape<?> ms) {
          ms.flushAndRebuild(ms.spatialResolution);
          ms.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
        }
        save();
        if (isCancelled()) {
          RTP.getInstance().scanTasks.remove(region.name, this);
          done.complete(true);
        }
      }
      isRunning.set(false);
      return;
    }

    long timingStart = System.currentTimeMillis();

    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    VerticalAdjustor<?> vert = region.getVert();
    if (vert == null) {
      RTP.log(Level.WARNING, "null vert");
      isRunning.set(false);
      return;
    }

    ConfigParser<PerformanceKeys> performance =
            (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    ConfigParser<SafetyKeys> safety =
            (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    ConfigParser<BlocksKeys> blocks =
            (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
    ConfigParser<BiomesKeys> biomesParser =
            (ConfigParser<BiomesKeys>) RTP.configs.getParser(BiomesKeys.class);

    Object o = biomesParser.getConfigValue(BiomesKeys.biomeWhitelist, false);
    boolean whitelist = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

    o = biomesParser.getConfigValue(BiomesKeys.biomes, new ArrayList<String>());
    if (!(o instanceof List<?>)) {
      new IllegalArgumentException(
              "expected list for biomes in advanced/biomes.yml, received - " + o.getClass().getSimpleName())
              .printStackTrace();
      biomesParser.set(BiomesKeys.biomes, new ArrayList<String>());
    }

    List<?> objList = (o instanceof List) ? ((List<?>) o) : new ArrayList<String>();
    Set<String> biomeSet = objList.stream().map(o2 -> o2.toString().toUpperCase()).collect(Collectors.toSet());
    Set<String> defaultBiomes;

    if (whitelist) {
      defaultBiomes = biomeSet;
    } else {
      Set<String> biomes = RTP.serverAccessor.getBiomes(region.getWorld());
      Set<String> set = new HashSet<>();
      for (String s : biomes) {
        String su = s.toUpperCase();
        if (!BiomeNames.matches(biomeSet, su)) {
          set.add(su);
        }
      }
      defaultBiomes = set;
    }

    o = blocks.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>());
    Set<String> unsafeBlocks =
            (o instanceof Collection)
                    ? ((Collection<?>) o).stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                    : new HashSet<>();

    int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
    boolean biomeRecall = Boolean.parseBoolean(performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());

    RTPWorld world = region.getWorld();
    WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());

    long range = Double.valueOf(shape.getRange()).longValue();
    long pos;
    long limit = scanIncrement.get();
    long stride = Math.max(1L, shape.spatialResolution);
    long currentStart = scanIter.get();
    if (currentStart == 0) {
      currentStart = currentOffset;
    }
    long limitEnd = currentStart + (limit * stride);
    MutableRTPCoords cursor = new MutableRTPCoords(0, 0);
    cursor.setWorldName(region.getWorld().name());

    long activeChecks = 0;

    // 1. Initialize the sliding window gate (field, shared with setCancelled for drain-on-shutdown)

    // PR-14: region-file bin-and-batch. PR-13's 200-entry sliding sort window left hit rate at
    // 0.31-0.57 because the scan frontier spans 6-10 r.X.Z.mca files and 50 concurrent probes
    // routinely draw from multiple files at once. Instead, gather the entire run's [currentStart,
    // limitEnd) window into per-region-file bins (HashMap keyed by (rx, rz)), then dispatch one
    // bin to completion before starting the next. Within-bin dispatch keeps all 50 in-flight
    // probes on a single .mca, so the 16-entry AnvilRegionByteCache sees a single hot file and
    // hit rate approaches 1.0 across the bin.
    //
    // Trade-off: observable scan order per run() call reshuffles chunks by region-file grouping
    // instead of strict shape iteration. addBadLocation still applies per-position; scan progress
    // `scanIter` still advances by the highest `pos` reached. Cosmetic change only.
    java.util.LinkedHashMap<Long, long[]> binHeaders = new java.util.LinkedHashMap<>();
    java.util.HashMap<Long, long[]> binPositions = new java.util.HashMap<>();
    java.util.HashMap<Long, int[]> binCx = new java.util.HashMap<>();
    java.util.HashMap<Long, int[]> binCz = new java.util.HashMap<>();
    // binHeaders value: {count, capacity} tracking per-bin fill; initial capacity 64, grows 2x.

    pos = currentStart;
    while (pos < range && pos < limitEnd) {
      if (pause.get() || isCancelled()) {
        break;
      }
      if (shape.isKnownBad(pos)) {
        pos += stride;
        continue;
      }
      shape.locationToXZ(pos, cursor);
      long key = (((long) (cursor.x >> 5)) << 32) | ((cursor.z >> 5) & 0xFFFFFFFFL);
      long[] header = binHeaders.get(key);
      long[] positions;
      int[] cxArr;
      int[] czArr;
      if (header == null) {
        header = new long[]{0L, 64L};
        binHeaders.put(key, header);
        positions = new long[64];
        cxArr = new int[64];
        czArr = new int[64];
        binPositions.put(key, positions);
        binCx.put(key, cxArr);
        binCz.put(key, czArr);
      } else {
        positions = binPositions.get(key);
        cxArr = binCx.get(key);
        czArr = binCz.get(key);
        if (header[0] >= header[1]) {
          int newCap = (int) (header[1] * 2L);
          long[] np = new long[newCap];
          int[] ncx = new int[newCap];
          int[] ncz = new int[newCap];
          System.arraycopy(positions, 0, np, 0, (int) header[0]);
          System.arraycopy(cxArr, 0, ncx, 0, (int) header[0]);
          System.arraycopy(czArr, 0, ncz, 0, (int) header[0]);
          positions = np;
          cxArr = ncx;
          czArr = ncz;
          binPositions.put(key, positions);
          binCx.put(key, cxArr);
          binCz.put(key, czArr);
          header[1] = newCap;
        }
      }
      int count = (int) header[0];
      positions[count] = pos;
      cxArr[count] = cursor.x;
      czArr[count] = cursor.z;
      header[0] = count + 1L;
      pos += stride;
    }

    // Dispatch bin-by-bin: one full region file's worth of candidates completes before the next
    // bin starts. Within-bin in-flight probes all share the same .mca -> cache hit rate ~1.0.
    RTP.log(Level.FINER, "[ScanTask] dispatching bins region=" + region.name
            + " bins=" + binHeaders.size() + " cap=" + MAX_PENDING_CHUNKS);
    outer:
    for (java.util.Map.Entry<Long, long[]> binEntry : binHeaders.entrySet()) {
      if (pause.get() || isCancelled()) {
        RTP.log(Level.FINE, "[ScanTask] dispatch loop interrupted region=" + region.name
                + " pause=" + pause.get() + " cancelled=" + isCancelled());
        break;
      }
      long key = binEntry.getKey();
      int binCount = (int) binEntry.getValue()[0];
      RTP.log(Level.FINER, "[ScanTask] bin start region=" + region.name
              + " binKey=" + key + " binCount=" + binCount);
      long[] positions = binPositions.get(key);
      int[] cxArr = binCx.get(key);
      int[] czArr = binCz.get(key);

      for (int bi = 0; bi < binCount; bi++) {
        if (pause.get() || isCancelled()) {
          break outer;
        }
        final long currentPos = positions[bi];
        int centerBlockX = (cxArr[bi] << 4) + 8;
        int centerBlockZ = (czArr[bi] << 4) + 8;

        // 2. Throttle the loop natively.
        try {
          inFlightGate.acquire();
        } catch (InterruptedException e) {
          break outer;
        }

        activeChecks++;

        try {
          int now = inFlight.incrementAndGet();
          peakInFlight.accumulateAndGet(now, Math::max);
          CompletableFuture<Boolean> posFuture = testPos(region, currentPos, centerBlockX, centerBlockZ, safetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, border);

          // 3. Release the permit on asynchronous completion
          posFuture.whenComplete((res, err) -> {
            inFlight.decrementAndGet();
            inFlightGate.release();
            // Land% is now derived from MemoryShape.getEffectiveBadCount() and finalPos1
            // in wrapUpBatch; rejects already call shape.addBadChunk() at every exit path,
            // so a separate scanGoodCount/scanBadCount pair is redundant.
          });
        } catch (Exception e) {
          // Failsafe: Release the permit instantly if a synchronous error occurs
          inFlight.decrementAndGet();
          inFlightGate.release();
          // addBadChunk: chunk-uniform — within a chunk the per-column selection order is
          // deterministic, so re-rolling onto the same chunk produces the same placement and
          // the same failure. Mark the twin spiral index too.
          shape.addBadChunk(currentPos);
          RTP.log(Level.WARNING, "Synchronous calculation failure at " + currentPos, e);
        }
      }

      // PR-17: per-bin drain removed. PR-14 originally drained here (acquire(50)/release(50))
      // to let AnvilRegionByteCache settle on one region file at a time. PR-15 coalescing made
      // cross-bin cache interference cheap (~49x dedup on concurrent misses), so the barrier
      // was costing pipeline bubbles without earning correctness. Runtime measurement post-PR-16
      // showed peakInFlight degrading 50 -> ~25 as scan progressed; removing this drain lets
      // the driver keep dispatching across bin boundaries and should hold peakInFlight at cap.
    }

    final long finalPos1 = pos;
    final long finalActiveChecks = activeChecks;

    // 4. Drain the gate to wait for the final trailing chunks of the iteration to complete
    RTP.log(Level.FINER, "[ScanTask] drain begin region=" + region.name
            + " activeChecks=" + activeChecks + " inFlight=" + inFlight.get());
    long drainStart = System.nanoTime();
    try {
      inFlightGate.acquire(MAX_PENDING_CHUNKS);
      inFlightGate.release(MAX_PENDING_CHUNKS);
    } catch (InterruptedException ignored) {}
    RTP.log(Level.FINER, "[ScanTask] drain end region=" + region.name
            + " drainMs=" + ((System.nanoTime() - drainStart) / 1_000_000L));

    // 5. Execute synchronously on the async thread, avoiding detached callback contexts
    wrapUpBatch(finalPos1, finalActiveChecks, timingStart, range, shape, limitEnd);
  }

  private void wrapUpBatch(long finalPos1, long activeChecks, long timingStart, long range, MemoryShape<?> shape, long limitEnd) {
    if (activeChecks > 0) {
      long dtMillis = System.currentTimeMillis() - timingStart;
      if (dtMillis <= 0) dtMillis = 1;
      long cps_local = (activeChecks * 1000L) / dtMillis;
      cps_all = cps_all.add(new BigInteger(String.valueOf(cps_local)));
      cps_divisor = cps_divisor.add(increment_big);
      cps.set((cps.get() * 7 / 8) + cps_local / 8);

      long etaSeconds = getEtaSeconds(range, finalPos1, shape, cps_local);

      // --- LAND PERCENTAGE CALCULATION ---
      // Derived from MemoryShape.getEffectiveBadCount() (which `addBadChunk`
      // already increments at every PRESCAN/FULLSCAN reject exit path) and
      // the count of positions scanned so far in this phase (finalPos1).
      // GENSCAN's testPos returns an isChunkGenerated verdict rather than a
      // land/water verdict and never calls addBadChunk on the "ungenerated"
      // branch, so the figure is meaningless mid-GENSCAN -- report 0.00%
      // there and let the real number appear once PRESCAN/FULLSCAN starts.
      double landPercentage;
      int phaseNow = scanPhase.get();
      if (phaseNow == PHASE_GENSCAN) {
        landPercentage = 0.0;
      } else if (phaseNow == PHASE_FULLSCAN) {
        // FULLSCAN re-sweeps every position in the region; bad-count is
        // cumulative across the full range (carried over from PRESCAN), so
        // the authoritative denominator is the full region range, not the
        // per-phase finalPos1.
        long bad = (region.shape instanceof MemoryShape<?> ms) ? ms.getEffectiveBadCount() : 0L;
        long denom = Math.max(1L, range);
        long good = Math.max(0L, denom - bad);
        landPercentage = (good * 100.0) / denom;
      } else if (finalPos1 <= 0) {
        landPercentage = 0.0;
      } else {
        long bad = (region.shape instanceof MemoryShape<?> ms) ? ms.getEffectiveBadCount() : 0L;
        long evaluated = Math.max(1L, finalPos1);
        long good = Math.max(0L, evaluated - bad);
        landPercentage = (good * 100.0) / evaluated;
      }
      // ------------------------------------

      this.latestAbsolutePos = ((currentOffset * range) + finalPos1) / Math.max(1L, shape.spatialResolution);
      this.latestAbsoluteTotal = range;
      this.latestCps = cps_local;
      this.latestEtaSeconds = etaSeconds;

      long now = System.currentTimeMillis();
      if (now - lastSaveTime > 5000 || finalPos1 >= range || pause.get() || isCancelled()) {
        RTP.log(Level.FINE, "[ScanTask] checkpoint region=" + region.name
                + " pos=" + finalPos1 + "/" + range
                + " cps=" + cps_local + " etaSec=" + etaSeconds
                + " land%=" + String.format("%.2f", landPercentage));
        lastSaveTime = now;

        // [TRACE] PR-8 concurrency gauge: observed peak in-flight probes this window vs the
        // MAX_PENDING_CHUNKS cap. peak ≪ cap => driver-loop serialization (root-cause fix is driver
        // prefetch or region-file batching, not adapter-side tuning).
        int peak = peakInFlight.getAndSet(inFlight.get());
        String cacheSuffix = readAnvilCacheStatsAndReset();
        String gcSuffix = readGcDeltaMs();
        String fullLoadSuffix = readFullLoadStatsAndReset();
        String probeOutcomeSuffix = readProbeOutcomeStatsAndReset();
        String phaseLabelStr = phaseLabel(scanPhase.get());
        // Per-batch scan diagnostic split (probeNull / adjustNull /
        // cacheHitLoad / genscan*). Logged at FINE so it is available when a
        // server raises the log level to debug a scan but does not spam the
        // default INFO console. This is the single line that explains *why*
        // PRESCAN falls back to runFullLoadPath: e.g. genscanGenerated >>
        // genscanUngenerated on a freshly-spawned world means the platform
        // isChunkGenerated is disagreeing with the on-disk .mca data, and
        // probeNull dominating PRESCAN means the anvil probe found no region
        // file to read.
        RTP.log(Level.FINE, "[TRACE] ScanTask diag region=" + region.name
            + " phase=" + phaseLabelStr
            + " cps=" + cps_local + " activeChecks=" + activeChecks + " peakInFlight=" + peak
            + " currentInFlight=" + inFlight.get() + " cap=" + MAX_PENDING_CHUNKS
            + cacheSuffix + gcSuffix + fullLoadSuffix + probeOutcomeSuffix);

        String msg = RTP.configs.getConfigValue(CommandMessages.scanStatus, "").toString();

        if (msg != null && !msg.isEmpty()) {
          // Replace the placeholder with the formatted number
          if (msg.contains("[scan_landPercentage]")) {
            msg = msg.replace("[scan_landPercentage]", String.format("%.2f", landPercentage));
          }
          RTP.serverAccessor.announce(msg, "rtp.scan", "");
        }

        shape.flushAndRebuild(shape.spatialResolution);
        save();
        shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
        // Persist generator-loaded chunks. On Spigot the platform RTPWorld
        // delegates to org.bukkit.World#save() because Bukkit's autosave
        // does not reliably flush chunks produced by external generators
        // (see docs/dev/LESSONS_LEARNED.md → "Pre-Generation & Shutdown").
        // On Paper / Folia / Fabric this is a no-op — those platforms'
        // chunk systems persist via their own paths.
        try {
          region.getWorld().save();
        } catch (Throwable t) {
          RTP.log(Level.WARNING,
              "[ScanTask] world.save() failed for region=" + region.name
                  + " world=" + region.getWorld().name(), t);
        }
      }
    }

    scanIter.set(finalPos1);

    if (finalPos1 >= range) {
      if (currentOffset < Math.max(1L, shape.spatialResolution) - 1) {
        currentOffset++;
        scanIter.set(0);
        shape.flushAndRebuild(shape.spatialResolution);
        save();
        shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
        shape.exportDebugJson(region.name, region.getWorld().name());
        isRunning.set(false);
        if (!isCancelled() && !pause.get()) {
          RTP.scheduler.runTaskAsynchronously(this);
        }
        return;
      }

      // GENSCAN complete -> flip to PRESCAN, or skip straight to FULLSCAN when
      // any chunk in the region was found ungenerated on disk. Rationale: PRESCAN
      // is an anvil-probe sweep that reads .mca region files; if even one chunk
      // is missing on disk, the rest of the region is likely sparsely-generated
      // too, and the cheap probe will return null for those tiles -> fall back to
      // runFullLoadPath one chunk at a time (2s chunk-ticket-budget warnings,
      // server stalls). FULLSCAN is authoritative for the same cost and generates
      // missing chunks in the same I/O, so it strictly dominates PRESCAN when
      // ungenerated chunks are present. Per the user's clarification (2026-05-19):
      // "the entire prescan stage is supposed to be skipped if any chunks are not
      // generated".
      if (scanPhase.get() == PHASE_GENSCAN) {
        long ungen = genscanUngeneratedTotal.get();
        int nextPhase = (ungen > 0L) ? PHASE_FULLSCAN : PHASE_PRESCAN;
        scanPhase.set(nextPhase);
        currentOffset = 0L;
        scanIter.set(0);
        shape.flushAndRebuild(shape.spatialResolution);
        save();
        shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
        shape.exportDebugJson(region.name, region.getWorld().name());
        if (nextPhase == PHASE_FULLSCAN) {
          RTP.log(Level.INFO, "[RTP] genscan complete for region=" + region.name
                  + "; ungenerated chunks detected (" + ungen + ") -> skipping prescan, starting full-load verification pass");
        } else {
          RTP.log(Level.INFO, "[RTP] genscan complete for region=" + region.name
                  + "; starting prescan (anvil probe sweep)");
        }
        isRunning.set(false);
        if (!isCancelled() && !pause.get()) {
          RTP.scheduler.runTaskAsynchronously(this);
        }
        return;
      }

      // Pass 1 / PRESCAN complete -> flip to Pass 2 / FULLSCAN instead of
      // terminating. Pass 2 re-sweeps every position that Pass 1 did not
      // mark bad, running runFullLoadPath for authoritative (2r+1)^3
      // safety verification.
      if (scanPhase.get() == PHASE_PRESCAN) {
        scanPhase.set(PHASE_FULLSCAN);
        currentOffset = 0L;
        scanIter.set(0);
        shape.flushAndRebuild(shape.spatialResolution);
        save();
        shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
        shape.exportDebugJson(region.name, region.getWorld().name());
        RTP.log(Level.INFO, "[RTP] prescan complete for region=" + region.name
                + "; starting full-load verification pass");
        isRunning.set(false);
        if (!isCancelled() && !pause.get()) {
          RTP.scheduler.runTaskAsynchronously(this);
        }
        return;
      }

      RTP.log(Level.FINE, "[ScanTask] scan complete region=" + region.name
              + " finalPos=" + finalPos1 + " range=" + range);
      shape.flushAndRebuild(shape.spatialResolution);
      shape.save(region.name + "_" + region.getWorld().getSeed(), region.getWorld().name());
      save(); // Ensure final pass is securely flushed before deletion
      RTP.getInstance().scanTasks.remove(region.name, this);
      delete();
      // Mark the region as sufficiently pre-generated. This unlocks the L3
      // backlog cache pulse (Region.processBacklog), which is gated on
      // scanCompleted to avoid driving live-load chunk traffic while the
      // pre-generation crawler is still consuming tick-thread budget.
      region.scanCompleted = true;
      done.complete(true);
      super.setCancelled(true);
      isRunning.set(false);
    } else if (!isCancelled() && !pause.get()) {
      if (RTP.getInstance().scanTasks.get(region.name) == this) {
        RTP.log(Level.FINER, "[ScanTask] yielding to scheduler for next batch region=" + region.name
                + " nextScanIter=" + finalPos1);
        shape.flushAndRebuild(shape.spatialResolution);
        isRunning.set(false);
        // Yield to the main thread between batches. Without this, back-to-back
        // async reschedules can starve the server tick on Fabric (chunk ticket
        // applications are processed on the main thread; a continuously busy
        // scan flood saturates the queue and the server stalls with
        // "Can't keep up!" warnings). Hop through the main thread before
        // dispatching the next async batch -- this guarantees the server has
        // ticked at least once between batches and drained pending chunk work,
        // without hard-coding a wall-clock delay.
        //
        // 2026-05-19 fix: if the main thread is severely backlogged (Fabric
        // "Can't keep up! Running 60s behind"), the queued `runTask` lambda
        // may never fire and the scan task sits dormant forever -- never
        // declaring completion. Race the main-thread hop against a delayed
        // async fallback; whichever fires first wins via CAS, the loser is
        // a no-op. Bounds the dormancy window at ~2 seconds.
        final ScanTask self = this;
        final java.util.concurrent.atomic.AtomicBoolean dispatched =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable dispatch = () -> {
          if (dispatched.compareAndSet(false, true)) {
            RTP.scheduler.runTaskAsynchronously(self);
          }
        };
        RTP.scheduler.runTask(dispatch);
        // Fallback fires via the platform async scheduler (Bukkit/Folia/
        // Paper/Fabric) so the next batch starts even when the main thread
        // is jammed. The fallback is a self-cancelling repeating-async
        // task: cancel itself immediately after the first invocation so
        // it only ever fires once. ~2s delay (40 ticks @ 20 TPS) matches
        // the chunk-ticket budget used elsewhere in the pipeline.
        final java.util.concurrent.atomic.AtomicReference<Object> fallbackHandle =
                new java.util.concurrent.atomic.AtomicReference<>();
        Object handle = RTP.scheduler.runTaskTimerAsynchronously(() -> {
          try {
            dispatch.run();
          } finally {
            Object h = fallbackHandle.get();
            if (h != null) {
              try {
                RTP.scheduler.cancelTask(h);
              } catch (Throwable ignored) {
                // Platform scheduler may reject double-cancel; benign.
              }
            }
          }
        }, 40L, 40L);
        fallbackHandle.set(handle);
      } else {
        RTP.log(Level.FINE, "[ScanTask] task supplanted; halting reschedule region=" + region.name);
        isRunning.set(false);
      }
    } else {
      isRunning.set(false);
      if (isCancelled()) {
        RTP.log(Level.FINE, "[ScanTask] batch end (cancelled) region=" + region.name);
        RTP.getInstance().scanTasks.remove(region.name, this);
        done.complete(true);
      } else {
        RTP.log(Level.FINE, "[ScanTask] batch end (paused) region=" + region.name);
      }
    }
  }

  // PR-11: reflectively read AnvilRegionByteCache.stats() + resetStats() so rtp-core doesn't
  // need a compile-time dep on rtp-anvil for a diagnostic. Returns "" when rtp-anvil isn't on
  // the classpath (Fabric builds, unit tests) or when no probes have run yet.
  private static volatile java.lang.reflect.Method cacheStatsMethod;
  private static volatile java.lang.reflect.Method cacheResetMethod;
  private static volatile boolean cacheReflectResolved;

  private static String readAnvilCacheStatsAndReset() {
    try {
      if (!cacheReflectResolved) {
        synchronized (ScanTask.class) {
          if (!cacheReflectResolved) {
            try {
              Class<?> c = Class.forName("io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache");
              cacheStatsMethod = c.getMethod("stats");
              cacheResetMethod = c.getMethod("resetStats");
            } catch (Throwable ignored) {
              // rtp-anvil not on classpath; leave methods null.
            }
            cacheReflectResolved = true;
          }
        }
      }
      if (cacheStatsMethod == null) return "";
      Object stats = cacheStatsMethod.invoke(null);
      long hits = (long) stats.getClass().getMethod("hits").invoke(stats);
      long misses = (long) stats.getClass().getMethod("misses").invoke(stats);
      long stale = (long) stats.getClass().getMethod("stale").invoke(stats);
      long coalesced = 0L;
      try {
        coalesced = (long) stats.getClass().getMethod("coalesced").invoke(stats);
      } catch (NoSuchMethodException ignored) {
        // older rtp-anvil without PR-15 coalesced counter
      }
      // PR-16: avgColdMissMs — average Files.readAllBytes wall time on misses. If this
      // climbs from ~1ms (warm) to >20ms (cold disk) the scan frontier is paying real I/O
      // and OS page-cache pressure is the primary bottleneck.
      double avgColdMissMs = 0.0;
      try {
        avgColdMissMs = (double) stats.getClass().getMethod("avgColdMissMs").invoke(stats);
      } catch (NoSuchMethodException ignored) {
        // older rtp-anvil without PR-16 cold-read timing
      }
      long total = hits + misses + coalesced;
      if (total == 0) return " anvilCache=idle";
      double rate = (double) (hits + coalesced) / (double) total;
      if (cacheResetMethod != null) cacheResetMethod.invoke(null);
      return " anvilCacheHits=" + hits + " anvilCacheMisses=" + misses + " anvilCacheStale=" + stale
          + " anvilCacheCoalesced=" + coalesced
          + " anvilCacheHitRate=" + String.format(java.util.Locale.ROOT, "%.3f", rate)
          + " avgColdMissMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgColdMissMs);
    } catch (Throwable t) {
      return "";
    }
  }

  /**
   * PR-16 diagnostic: cumulative GC-time delta since the previous gauge log. Reported as
   * {@code gcDeltaMs=…} on the concurrency gauge line. If this grows from ~50ms/window
   * (warm) to ~500ms/window (degraded) the 4MB-per-region byte[] churn is pressuring the
   * collector — fix direction is to avoid per-file allocation (mmap / FileChannel).
   *
   * <p>Returns {@code ""} if the management bean isn't available (headless environments).</p>
   */
  private String readGcDeltaMs() {
    try {
      long total = 0L;
      for (java.lang.management.GarbageCollectorMXBean b :
              java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
        long t = b.getCollectionTime();
        if (t > 0) total += t;
      }
      long delta = (lastGcTotalMs < 0) ? 0L : Math.max(0L, total - lastGcTotalMs);
      lastGcTotalMs = total;
      return " gcDeltaMs=" + delta;
    } catch (Throwable ignored) {
      return "";
    }
  }

  /**
   * PR-17 diagnostic: stage-2 (full chunk load + safety scan) count and per-invocation
   * average wall-clock cost since the previous gauge log. Reported as
   * {@code fullLoads=N fullLoadAvgMs=X.XX}. If {@code fullLoadAvgMs} grows with scan
   * progress while {@code avgColdMissMs} stays flat, the degradation is in the loaded-chunk
   * path (more chunks passing the probe → more stage-2 work), not the probe prefilter.
   */
  private String readFullLoadStatsAndReset() {
    long count = fullLoadCount.getAndSet(0L);
    long nanos = fullLoadNanos.getAndSet(0L);
    double avgMs = count == 0 ? 0.0 : (nanos / 1_000_000.0) / count;
    return " fullLoads=" + count
            + " fullLoadAvgMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgMs);
  }

  /**
   * PR-19 diagnostic: per-outcome counters for the probe-first fast path.
   * Reported as {@code probeNull=A adjustNull=B biomeReject=C blockReject=D
   * cacheMissAccept=E cacheHitLoad=F}. After PR-18, {@code fullLoads} stayed
   * ~= activeChecks despite the minY-1 fix; these counters split the bucket so
   * we can see which branch is actually routing candidates. Interpretation:
   * large {@code probeNull} = probe I/O or UNKNOWN (missing heightmap, etc.);
   * large {@code adjustNull} = adjustor reject (wrong window / skylight / mode);
   * large {@code cacheHitLoad} = probe accepts but chunks are in live cache
   * (still paying full load, but radius scan is genuine work);
   * large {@code cacheMissAccept} = fast path is working as intended.
   */
  private String readProbeOutcomeStatsAndReset() {
    long pn = probeOutcomeProbeNull.getAndSet(0L);
    long an = probeOutcomeAdjustNull.getAndSet(0L);
    // PR-20 sub-attribution. Buckets are mutually exclusive and sum to {@code an}.
    long anL = probeOutcomeAdjustNullLight.getAndSet(0L);
    long anW = probeOutcomeAdjustNullWindow.getAndSet(0L);
    long anS = probeOutcomeAdjustNullScan.getAndSet(0L);
    long anT = probeOutcomeAdjustNullThrew.getAndSet(0L);
    long anSC = probeOutcomeAdjustNullScanShortCircuit.getAndSet(0L);
    long br = probeOutcomeBiomeReject.getAndSet(0L);
    long kr = probeOutcomeBlockReject.getAndSet(0L);
    long cma = probeOutcomeCacheMissAccept.getAndSet(0L);
    long chl = probeOutcomeCacheHitLoad.getAndSet(0L);
    long gg = genscanGenerated.getAndSet(0L);
    long gu = genscanUngenerated.getAndSet(0L);
    long gt = genscanThrew.getAndSet(0L);
    long flA = fullLoadOutcomeAccept.getAndSet(0L);
    long flT = fullLoadOutcomeTimeoutOrNull.getAndSet(0L);
    long flMB = fullLoadOutcomeMidBiome.getAndSet(0L);
    long flVA = fullLoadOutcomeVertAdjust.getAndSet(0L);
    long flPB = fullLoadOutcomePhysBiome.getAndSet(0L);
    long flYO = fullLoadOutcomeYOverflow.getAndSet(0L);
    long flSS = fullLoadOutcomeSafetyScan.getAndSet(0L);
    long flX = fullLoadOutcomeCrashed.getAndSet(0L);
    return " probeNull=" + pn
            + " adjustNull=" + an
            + "(light=" + anL + " window=" + anW + " scan=" + anS
            + " scanShortCircuit=" + anSC + " threw=" + anT + ")"
            + " biomeReject=" + br + " blockReject=" + kr
            + " cacheMissAccept=" + cma + " cacheHitLoad=" + chl
            + " genscanGenerated=" + gg
            + " genscanUngenerated=" + gu
            + " genscanThrew=" + gt
            + " fullLoadOutcome=accept=" + flA
            + " timeoutOrNull=" + flT
            + " midBiome=" + flMB
            + " vertAdjust=" + flVA
            + " physBiome=" + flPB
            + " yOverflow=" + flYO
            + " safetyScan=" + flSS
            + " crashed=" + flX;
  }

  private long getEtaSeconds(long range, long finalPos1, MemoryShape<?> shape, long cpsLocal) {
    long totalRemainingPoints = (range - finalPos1) + (Math.max(0, shape.spatialResolution - 1 - currentOffset) * range);
    if (totalRemainingPoints < 0) totalRemainingPoints = 0;
    long effectiveBad = shape.getEffectiveBadCount();
    long totalEvaluated = shape.getEffectiveGoodCount() + effectiveBad;
    double badDensity = (double) effectiveBad / (double) Math.max(1, totalEvaluated);
    long estimatedActivePointsRemaining = (long) (totalRemainingPoints * (1.0 - badDensity));

    // ETA is biased pessimistically toward recent throughput so a performance
    // dip immediately raises the displayed ETA instead of being diluted by
    // historically-fast batches. We take the minimum of:
    //   - the cumulative average cps across the whole scan,
    //   - the EWMA-smoothed cps (recent-weighted),
    //   - the just-finished batch's cps.
    // This guarantees ETA never under-predicts when throughput drops; it will
    // recover (shrink) once recent batches catch up to the cumulative average.
    long cumulativeAvg = cps_all.divide(cps_divisor).longValue();
    long ewma = cps.get();
    long batch = Math.max(1L, cpsLocal);
    long currentPointsPerSecond = Math.min(batch, Math.min(Math.max(1L, cumulativeAvg), Math.max(1L, ewma)));
    if (currentPointsPerSecond <= 0) currentPointsPerSecond = 1;
    return estimatedActivePointsRemaining / currentPointsPerSecond;
  }

  public void save() {
    if (isCancelled()) {
      RTP.log(Level.FINER, "[ScanTask] save() skipped (cancelled); deleting progress file region=" + region.name);
      delete();
      return;
    }
    RTP.log(Level.FINER, "[ScanTask] save() region=" + region.name
            + " scanIter=" + scanIter.get()
            + " currentOffset=" + currentOffset
            + " phase=" + scanPhase.get());
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File dir = new File(pluginDir, "database" + File.separator + "regionData");
    if (!dir.exists()) dir.mkdirs();
    File file = new File(dir, region.name + "_" + region.cacheKey() + ".scan");

    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
      // Layout: scanIter(8) | spatialResolution(8) | currentOffset(8) | isFine(1) | scanPhase(1) = 26 bytes.
      ByteBuffer buf = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
      buf.putLong(scanIter.get());
      Shape<?> shape = region.getShape();
      if (shape instanceof MemoryShape<?> memoryShape) {buf.putLong(memoryShape.spatialResolution);}
      buf.putLong(currentOffset);
      buf.put((byte) 0);
      // GENSCAN is transient — collapse to PRESCAN on disk so a resumed scan
      // does not re-run the chunk-generation pre-pass (chunks generated in
      // the previous run are still generated). See PHASE_GENSCAN docs.
      int persistedPhase = scanPhase.get();
      if (persistedPhase == PHASE_GENSCAN) persistedPhase = PHASE_PRESCAN;
      buf.put((byte) (persistedPhase & 0xFF));
      out.write(buf.array());
    } catch (java.io.IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  public static long[] loadProgress(String regionName, String cacheKey) {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File file =
        new File(
            pluginDir,
            "database" + File.separator + "regionData" + File.separator
                + regionName + "_" + cacheKey + ".scan");
    if (!file.exists()) return null;

    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      byte[] bytes = new byte[26];
      int read = in.read(bytes);
      if (read >= 16) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long iter = buf.getLong();
        long stride = buf.getLong();
        long offset = 0;
        long isFine = 0;
        long phase = 0;
        if (read >= 24) {
          offset = buf.getLong();
          if (read >= 25) {
            isFine = bytes[24] & 0xFF;
          }
          if (read >= 26) {
            phase = bytes[25] & 0xFF;
          }
        } else if (read == 17) {
          isFine = bytes[16] & 0xFF;
        } else if (stride == 1) {
          isFine = 1;
        }
        return new long[] {iter, stride, isFine, offset, phase, 0L};
      }
    } catch (java.io.IOException e) {
      // ignore
    }
    return null;
  }

  public void delete() {
    delete(region.name);
  }

  /**
   * Delete every {@code <regionName>_*.scan} progress file in the regionData directory.
   *
   * <p>Used by reset/start commands which do not know which cache-key suffix the previous
   * run used (it may pre-date the current config). Sweeping by name prefix avoids leaving
   * orphan progress files behind.
   */
  public static void delete(String regionName) {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File dir = new File(pluginDir, "database" + File.separator + "regionData");
    if (!dir.isDirectory()) return;
    String prefix = regionName + "_";
    String suffix = ".scan";
    String bare = regionName + suffix;
    File[] candidates =
        dir.listFiles((d, n) -> n.endsWith(suffix) && (n.startsWith(prefix) || n.equals(bare)));
    if (candidates == null) return;
    for (File f : candidates) {
      if (f.exists()) f.delete();
    }
  }

  /**
   * Test if a location within a region is valid for teleportation
   *
   * @param region the region
   * @param pos the location index
   * @param blockX block x coordinate
   * @param blockZ block z coordinate
   * @param safetyRadius safety radius for chunk verification
   * @param unsafeBlocks set of unsafe block names
   * @param defaultBiomes set of allowed biomes
   * @param biomeRecall whether to record bad locations for biomes
   * @param border world border for the current world
   * @return a future that completes with true if the location is valid
   */
  public CompletableFuture<Boolean> testPos(
          Region region,
          final long pos,
          final int blockX,
          final int blockZ,
          int safetyRadius,
          Set<String> unsafeBlocks,
          Set<String> defaultBiomes,
          boolean biomeRecall,
          WorldBorder border) {
    io.github.dailystruggle.rtp.common.tools.CfDiag.scanTestPos.increment();

    try {
      safetyRadius = Math.min(safetyRadius, 7);

      int cx = blockX >> 4;
      int cz = blockZ >> 4;
      MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
      if (shape == null) return CompletableFuture.completedFuture(false);

      if(shape.isKnownBad(pos)) { return CompletableFuture.completedFuture(false); }

      VerticalAdjustor<?> vert = region.getVert();
      if (vert == null) return CompletableFuture.completedFuture(false);

      RTPWorld<?> world = region.getWorld();

      // Fast mathematical rejection ONLY for World Border.
      // Mathematical biome check is completely removed to force chunk generation.
      // Null-guard: an absent border (e.g. platform adapter returned null for
      // this world) means "no border in effect" — accept the candidate here
      // and let downstream shape/region constraints bound the selection.
      if (border != null && !border.isInside().apply(new RTPLocation(world, blockX, (vert.maxY() + vert.minY()) / 2, blockZ))) {
        RTP.log(Level.FINER, "[ScanTask] border-reject region=" + region.name
                + " pos=" + pos + " block=(" + blockX + "," + blockZ + ")");
        // addBadChunk: chunk-uniform — within a chunk the per-column selection order is
        // deterministic, so re-rolling onto the same chunk yields the same picked column,
        // which the border will reject again. Mark the twin spiral index too.
        shape.addBadChunk(pos);
        return CompletableFuture.completedFuture(false);
      }

      if (isCancelled() || pause.get()) {
        return CompletableFuture.completedFuture(false);
      }

      CompletableFuture<Boolean> res = new CompletableFuture<>();
      int finalSafetyRadius = safetyRadius;
      final int midY = (vert.maxY() + vert.minY()) / 2;

      // Two-pass scan model (2026-04-23):
      //   Pass 1 / PRESCAN  - run the anvil probe sweep; on probe-accept we
      //                       trust the probe and complete true WITHOUT a
      //                       full chunk-load safety scan. Only cheap rejects
      //                       (biome / adjust-null / center-column unsafe)
      //                       mark bad locations.
      //   Pass 2 / FULLSCAN - skip the probe entirely and route every
      //                       non-isKnownBad position through the classic
      //                       full chunk-load (2r+1)^3 safety scan for
      //                       authoritative verification. This catches the
      //                       probe's false-accepts (waterlogged blocks,
      //                       stale-chunk body cells, etc.) and upgrades all
      //                       remaining candidates to the full safety
      //                       contract.
      if (scanPhase.get() == PHASE_FULLSCAN) {
        runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ, midY,
                finalSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, res);
        return res;
      }

      // GENSCAN: ensure the chunk is generated on disk before the anvil
      // prefilter sees it. If the chunk is already generated, skip — PRESCAN
      // will handle it via the cheap anvil probe. If NOT generated, bypass
      // the anvil prefilter entirely and run the full load path: chunk
      // loading will generate the chunk and we authoritatively validate it
      // in the same I/O so we never load the same chunk twice.
      if (scanPhase.get() == PHASE_GENSCAN) {
        boolean generated;
        try {
          generated = world.isChunkGenerated(cx, cz);
          if (generated) {
            genscanGenerated.incrementAndGet();
          } else {
            genscanUngenerated.incrementAndGet();
            genscanUngeneratedTotal.incrementAndGet();
          }
        } catch (Throwable t) {
          RTP.log(Level.FINE, "[ScanTask] isChunkGenerated threw for world=" + world.name()
                  + " chunk=(" + cx + "," + cz + "): " + t);
          // Conservative on error: treat as generated and defer to PRESCAN.
          generated = true;
          genscanThrew.incrementAndGet();
        }
        if (!generated) {
          runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ, midY,
                  finalSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, res);
          return res;
        }
        // Already generated — defer to PRESCAN. Complete with `false` so the
        // caller's gate is released and the position is not counted as good;
        // crucially, do NOT mark the position bad on the shape so PRESCAN
        // re-evaluates it via the anvil probe.
        res.complete(false);
        return res;
      }

      // GENSCAN replacement (2026-05-25): with GENSCAN disabled as the initial
      // phase, PRESCAN carries the ungenerated-chunk detection. The inline
      // `isChunkGenerated` precheck is now universal across Bukkit/Paper/Folia/Fabric:
      // every platform's RTPWorld.isChunkGenerated routes through the
      // AnvilRegionOccupancyCache (an mtime-validated, 1024-bit per-region-file
      // occupancy bitmap built once and reused across all ~1024 sibling-chunk
      // queries in that 32x32 tile). PRESCAN sweeps adjacent chunks in spiral
      // order, so first-touch cost is amortised over the whole region file —
      // the previous Paper/Folia regression (synchronous Bukkit chunk-system
      // call collapsing peakInFlight from cap=50 to ~2-3) no longer applies.
      // Conservative on error: treat as generated and let the probe path take it.
      if (scanPhase.get() == PHASE_PRESCAN) {
        boolean generated = true;
        try {
          generated = world.isChunkGenerated(cx, cz);
        } catch (Throwable t) {
          RTP.log(Level.FINE, "[ScanTask] isChunkGenerated threw for world=" + world.name()
                  + " chunk=(" + cx + "," + cz + "): " + t);
        }
        if (!generated) {
          if (scanPhase.compareAndSet(PHASE_PRESCAN, PHASE_FULLSCAN)) {
            RTP.log(Level.INFO, "[RTP] prescan encountered ungenerated chunk for region="
                    + region.name + " at (" + cx + "," + cz + ") -> switching to full-load scan");
          }
          runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ, midY,
                  finalSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, res);
          return res;
        }
      }

      // BIOME_LOOKUP_PERF_PLAN.md PR-5: probe-first with cache-aware gating.
      //
      // Try to decide the candidate from a lean column probe before paying for
      // a full chunk load. On probe-accept we key on cache residency:
      //   - cache miss: trust the probe (center column at picked Y is the
      //     authoritative safety check for safetyRadius==0; for radius>0 we
      //     silently widen to center-column-only to avoid loading the chunk).
      //   - cache hit : run a full (2r+1)^3 safety scan with an upgraded
      //     radius max(configured, 2), since the load is already paid.
      // On probe UNKNOWN/null we fall through to the legacy getOrLoadChunk
      // path with zero behavior change.
      if (tryProbeFirstScan(region, world, vert, shape, pos, blockX, blockZ,
              finalSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, res)) {
        return res;
      }

      runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ, midY,
              finalSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, res);
      return res;
    } catch (Throwable t) {
      RTP.log(Level.SEVERE, "[ScanTask] Synchronous abort at testPos for location " + pos, t);
      return CompletableFuture.completedFuture(false);
    }
  }

  /**
   * PR-5 probe-first fast path. Returns {@code true} iff a verdict was
   * committed to {@code res} (reject or accept) and the caller must not run
   * the full load path. Returns {@code false} on UNKNOWN — caller falls
   * through to {@link #runFullLoadPath}.
   *
   * <p>S-005: dispatched on the scheduler's async callback chain. Safety
   * scans on cache-hit run via {@code scheduler.runTask(targetLoc, …)} so
   * we never touch a live chunk off-thread.</p>
   */
  private boolean tryProbeFirstScan(
          Region region,
          RTPWorld<?> world,
          VerticalAdjustor<?> vert,
          MemoryShape<?> shape,
          long pos,
          int blockX,
          int blockZ,
          int configuredSafetyRadius,
          Set<String> unsafeBlocks,
          Set<String> defaultBiomes,
          boolean biomeRecall,
          CompletableFuture<Boolean> res) {
    int cx = blockX >> 4;
    int cz = blockZ >> 4;
    int minY = vert.minY();
    int maxY = vert.maxY();
    if (minY >= maxY) return false;

    CompletableFuture<ChunkColumnProbe> fut;
    try {
      // PR-18: widen probe window by one block below minY so that
      // LinearAdjustor/JumpAdjustor.adjustFromProbe (which consults the block
      // at y-1 for standing-surface safety) doesn't trivially reject with
      // probe.minY() > minY - 1. Without this the fast path was inert on
      // every candidate and every probe-accept degraded to a full load.
      fut = world.probeChunkColumn(cx, cz, minY - 1, maxY);
    } catch (Throwable t) {
      RTP.log(Level.FINE,
              "[ScanTask] probeChunkColumn threw for world=" + world.name()
                      + " chunk=(" + cx + "," + cz + "): " + t);
      probeOutcomeProbeNull.incrementAndGet();
      return false;
    }
    if (fut == null) { probeOutcomeProbeNull.incrementAndGet(); return false; }

    // Fast path: future already complete (default no-op adapter). Handle inline
    // to avoid scheduler dispatch on platforms that don't override probeChunkColumn.
    if (fut.isDone() && !fut.isCompletedExceptionally()) {
      ChunkColumnProbe probe;
      try {
        probe = fut.getNow(null);
      } catch (Throwable ignored) {
        return false;
      }
      if (probe == null) { probeOutcomeProbeNull.incrementAndGet(); return false; }
      return evaluateScanProbe(region, world, vert, shape, pos, blockX, blockZ,
              configuredSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall,
              probe, res);
    }

    // Async completion — schedule evaluation, return true so the caller does
    // not also run the legacy path. On UNKNOWN we continue to the legacy path
    // from within the callback.
    fut.whenComplete((probe, ex) -> {
      if (ex != null) {
        RTP.log(Level.FINE,
                "[ScanTask] probeChunkColumn failed for world=" + world.name()
                        + " chunk=(" + cx + "," + cz + "): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        probeOutcomeProbeNull.incrementAndGet();
        runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ,
                (maxY + minY) / 2, configuredSafetyRadius, unsafeBlocks,
                defaultBiomes, biomeRecall, res);
        return;
      }
      if (probe == null) {
        probeOutcomeProbeNull.incrementAndGet();
        runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ,
                (maxY + minY) / 2, configuredSafetyRadius, unsafeBlocks,
                defaultBiomes, biomeRecall, res);
        return;
      }
      if (evaluateScanProbe(region, world, vert, shape, pos, blockX, blockZ,
              configuredSafetyRadius, unsafeBlocks, defaultBiomes, biomeRecall,
              probe, res)) {
        return;
      }
      // UNKNOWN from adjustFromProbe — fall through to full load.
      runFullLoadPath(region, world, vert, shape, pos, blockX, blockZ,
              (maxY + minY) / 2, configuredSafetyRadius, unsafeBlocks,
              defaultBiomes, biomeRecall, res);
    });
    return true;
  }

  /**
   * Evaluate a probe for ScanTask. Returns {@code true} iff a verdict was
   * committed to {@code res}; {@code false} on UNKNOWN (caller falls through
   * to full load path).
   */
  private boolean evaluateScanProbe(
          Region region,
          RTPWorld<?> world,
          VerticalAdjustor<?> vert,
          MemoryShape<?> shape,
          long pos,
          int blockX,
          int blockZ,
          int configuredSafetyRadius,
          Set<String> unsafeBlocks,
          Set<String> defaultBiomes,
          boolean biomeRecall,
          ChunkColumnProbe probe,
          CompletableFuture<Boolean> res) {
    RTPCoords picked;
    try {
      VerticalAdjustor.AdjustResult ar = vert.adjustFromProbeWithReason(probe, world.name());
      picked = ar.picked();
      if (picked == null) {
        probeOutcomeAdjustNull.incrementAndGet();
        switch (ar.reason()) {
          case LIGHT_GATE -> {
            probeOutcomeAdjustNullLight.incrementAndGet();
            return false; // UNKNOWN: probe couldn't decide light, defer to full load.
          }
          case WINDOW -> {
            probeOutcomeAdjustNullWindow.incrementAndGet();
            return false; // UNKNOWN: probe window incomplete, defer to full load.
          }
          case THREW -> {
            probeOutcomeAdjustNullThrew.incrementAndGet();
            return false; // UNKNOWN: adjustor threw, defer to full load.
          }
          case SCAN_MISS, NONE -> {
            // Authoritative bad: the multi-column probe sweep (JumpAdjustor /
            // LinearAdjustor) iterated the same testCoords columns the live
            // adjust(RTPChunk,...) path would and found no acceptable Y on any
            // column. The probe-side predicate is at least as permissive as
            // the live predicate (state-predicated unsafe tokens are dropped
            // from the probe fast set; sky-light cold path returns
            // LIGHT_GATE_REJECT not SCAN_MISS), so a SCAN_MISS verdict
            // implies the full chunk load would also reject. Short-circuit
            // here instead of paying for a chunk load that confirms the
            // verdict — turns the historical air-column / void / no-foothold
            // adjustNull tail into a zero-cost reject.
            probeOutcomeAdjustNullScan.incrementAndGet();
            probeOutcomeAdjustNullScanShortCircuit.incrementAndGet();
            // Always record the biome for this position even though no
            // acceptable Y was found. Biome is a per-chunk property in the
            // anvil probe, so a mid-window read is representative; keyed by
            // location, so the record stays idempotent.
            try {
              int midProbeY = (vert.maxY() + vert.minY()) / 2;
              String scanMissBiome = probe.biomeAt(midProbeY);
              if (scanMissBiome != null) {
                shape.addBiomeLocation(pos, 1, scanMissBiome.toUpperCase());
              }
            } catch (Throwable ignored) {
              // Biome read is best-effort; a failure must not change the verdict.
            }
            // addBadChunk: chunk-uniform — SCAN_MISS means the multi-column probe sweep
            // iterated every testCoords column in this chunk and found no acceptable Y on
            // any of them; the twin spiral index targets a column already covered by the
            // sweep, so it is guaranteed to also reject.
            shape.addBadChunk(pos);
            res.complete(false);
            return true;
          }
        }
        return false;
      }
    } catch (Throwable t) {
      // Adjustor threw — UNKNOWN, fall through. Attribute to THREW sub-bucket.
      probeOutcomeAdjustNull.incrementAndGet();
      probeOutcomeAdjustNullThrew.incrementAndGet();
      return false;
    }
    int py = picked.y();

    // Center-column biome check. The biome of every readable point is recorded
    // below (accept or reject) so the shape always carries biome data.
    String probeBiome = probe.biomeAt(py);
    if (probeBiome != null) {
      String ub = probeBiome.toUpperCase();
      // Fill in biome data for every point whose biome we can read, regardless
      // of whether the point is ultimately accepted or rejected. The biome map
      // is keyed by location, so re-recording an already-known position is
      // idempotent ("if it doesn't already").
      shape.addBiomeLocation(pos, 1, ub);
      if (!BiomeNames.matches(defaultBiomes, ub)) {
        probeOutcomeBiomeReject.incrementAndGet();
        // addBadChunk: chunk-uniform — biome is a per-chunk property in the anvil probe;
        // the twin spiral index decodes to the same chunk and is guaranteed to fail.
        shape.addBadChunk(pos);
        res.complete(false);
        return true;
      }
    }

    // Center-column unsafe-block check at picked Y.
    String probeBlock = probe.blockAt(py);
    if (probeBlock != null && MaterialNames.matches(unsafeBlocks, probeBlock.toUpperCase())) {
      probeOutcomeBlockReject.incrementAndGet();
      // addBadChunk: chunk-uniform — within a chunk the per-column selection order is
      // deterministic, so the twin spiral index resolves to the same picked column and the
      // same unsafe block.
      shape.addBadChunk(pos);
      res.complete(false);
      return true;
    }

    // Probe accepted at center column. Now gate on cache residency.
    int cx = blockX >> 4;
    int cz = blockZ >> 4;
    final long chunkKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
    RTPChunk<?> cached = world.getCachedChunk(chunkKey);

    // Pass 1 / PRESCAN: trust probe-accept for BOTH cache-miss and cache-hit.
    // Authoritative (2r+1)^3 verification is deferred to Pass 2 / FULLSCAN,
    // which re-sweeps every position that Pass 1 did not mark bad. This keeps
    // Pass 1 a pure anvil-probe sweep (no chunk loads on accept, no region-
    // thread dispatch), while Pass 2 runs the classic authoritative scan.
    if (cached == null) {
      probeOutcomeCacheMissAccept.incrementAndGet();
    } else {
      probeOutcomeCacheHitLoad.incrementAndGet();
    }
    res.complete(true);
    return true;
  }

  /**
   * Legacy full-load path, extracted verbatim from the pre-PR-5 testPos body.
   * Invoked when the probe is unavailable (UNKNOWN) or fails.
   *
   * <p>PR-17: times each invocation from entry to {@code res} completion and accumulates into
   * {@link #fullLoadCount}/{@link #fullLoadNanos}. Diagnostic only; emitted by
   * {@link #readFullLoadStatsAndReset()}.</p>
   */
  private void runFullLoadPath(
          Region region,
          RTPWorld<?> world,
          VerticalAdjustor<?> vert,
          MemoryShape<?> shape,
          long pos,
          int blockX,
          int blockZ,
          int midY,
          int finalSafetyRadius,
          Set<String> unsafeBlocks,
          Set<String> defaultBiomes,
          boolean biomeRecall,
          CompletableFuture<Boolean> res) {
      io.github.dailystruggle.rtp.common.tools.CfDiag.scanRunFullLoad.increment();
      final long fullLoadStartNanos = System.nanoTime();
      res.whenComplete((r, e) -> {
        fullLoadCount.incrementAndGet();
        fullLoadNanos.addAndGet(System.nanoTime() - fullLoadStartNanos);
      });
      int cx = blockX >> 4;
      int cz = blockZ >> 4;

      // Probe-first resolution: anvil cache if available, else live load.
      // The mid-Y biome pre-filter is now performed on the resolved chunk so
      // ungenerated-chunk cases transparently load on demand and cached
      // anvil/live data is used when available (ADR-016 §13.1 follow-up,
      // 2026-04-20).
      final CompletableFuture<RTPChunk<?>> chunkFuture =
              world.getOrLoadChunk(cx, cz)
                      .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
      // Track so a cancel/pause can abort this load instead of waiting out the
      // 30s timeout; cancellation completes the chain promptly and releases the
      // inFlightGate (see drainInFlight).
      inFlightChunkFutures.add(chunkFuture);
      chunkFuture
              .whenComplete((resolvedChunk, throwable) -> {
                inFlightChunkFutures.remove(chunkFuture);
                try {
                  if (throwable != null || resolvedChunk == null || isCancelled() || pause.get()) {
                    // Suppress the warning once cancelled/paused: a cancel
                    // actively aborts in-flight loads, so the resulting
                    // CancellationException/TimeoutException is expected and
                    // logging it would spam the console during shutdown of a scan.
                    // A CancellationException is always benign (it can only come
                    // from us aborting the load), so suppress it unconditionally.
                    boolean stopping = isCancelled() || pause.get()
                            || unwrap(throwable) instanceof CancellationException;
                    if (throwable != null) {
                      if (!stopping) {
                        RTP.log(Level.WARNING, "[ScanTask] Chunk generation exception at " + pos, throwable);
                      }
                      fullLoadOutcomeTimeoutOrNull.incrementAndGet();
                    } else if (resolvedChunk == null) {
                      if (!stopping) {
                        RTP.log(Level.WARNING, "[ScanTask] INSTANT BYPASS: Chunk manager returned a null chunk for " + pos);
                      }
                      fullLoadOutcomeTimeoutOrNull.incrementAndGet();
                    } else {
                        if (!stopping) {
                          RTP.log(Level.WARNING, "[ScanTask] undetermined failure at " + pos);
                          fullLoadOutcomeTimeoutOrNull.incrementAndGet();
                        }
                    }
                      res.complete(false);
                    return;
                  }

                  // Mid-Y biome pre-filter on the resolved chunk — safe to run
                  // inline only for self-contained (anvil-backed) chunks. For
                  // live-backed chunks the biome read routes through
                  // CraftBlock#getBiome which enforces TickThread and would
                  // throw on the async scheduler thread we're currently on;
                  // in that case we defer the biome check to the region-thread
                  // runTask body below (the post-load biome guard already
                  // rejects out-of-biome candidates there).
                  if (resolvedChunk.isSelfContained()) {
                    String midBiome = resolvedChunk.getBiome(blockX, midY, blockZ).toUpperCase();
                    // Fill in biome data for every point whose biome we can
                    // read, regardless of accept/reject. Keyed by location, so
                    // re-recording is idempotent ("if it doesn't already").
                    shape.addBiomeLocation(pos, 1, midBiome);
                    if (!BiomeNames.matches(defaultBiomes, midBiome)) {
                      // addBadChunk: chunk-uniform — mid-Y biome read from the resolved
                      // (anvil-backed self-contained) chunk; biome is per-chunk so the twin
                      // spiral index in the same chunk is guaranteed to fail.
                      shape.addBadChunk(pos);
                      fullLoadOutcomeMidBiome.incrementAndGet();
                      res.complete(false);
                      return;
                    }
                  }

                  RTPLocation targetLoc = new RTPLocation(world, blockX, 0, blockZ);
                  RTP.serverAccessor.getScheduler().runTask(targetLoc, () -> {
                    try {
                      RTPChunk<?> chunk = resolvedChunk;

                      MutableRTPCoords localCursor = new MutableRTPCoords(blockX, blockZ);
                      localCursor.setWorldName(world.name());

                      // Always record the biome for this position, even when a
                      // later check (vert.adjust, safety scan, etc.) rejects it.
                      // For self-contained chunks the mid-Y biome was already
                      // recorded above; for live-backed chunks the biome read has
                      // to happen on this region thread, so do it here before any
                      // reject path can short-circuit. Keyed by location, so
                      // re-recording the picked-Y biome below stays idempotent.
                      if (!chunk.isSelfContained()) {
                        try {
                          String midBiomeLive = chunk.getBiome(blockX, midY, blockZ);
                          if (midBiomeLive != null) {
                            shape.addBiomeLocation(pos, 1, midBiomeLive.toUpperCase());
                          }
                        } catch (Throwable ignored) {
                          // Biome read is best-effort; a failure here must not
                          // abort the authoritative scan below.
                        }
                      }

                      if (!vert.adjust(chunk, localCursor)) {
                        // addBadChunk: chunk-uniform — within a chunk the per-column
                        // selection order is deterministic, so the twin spiral index picks
                        // the same column and vert.adjust fails identically.
                        shape.addBadChunk(pos);
                        fullLoadOutcomeVertAdjust.incrementAndGet();
                        res.complete(false);
                        return;
                      }

                      // PHYSICAL BIOME CHECK: ask the resolved chunk for its own biome
                      // (ADR-016 §13.1 follow-up, 2026-04-20). This bypasses
                      // `anvilProbeSupport.takeCached` so an evicted cache entry cannot
                      // cause a fallthrough to the seed-synth getter on a loaded chunk.
                      String actualBiome = chunk.getBiome(localCursor.x, localCursor.y, localCursor.z);
                      // Fill in biome data for every point whose biome we can
                      // read, regardless of accept/reject. Keyed by location,
                      // so re-recording is idempotent ("if it doesn't already").
                      shape.addBiomeLocation(pos, 1, actualBiome.toUpperCase());

                      if (!BiomeNames.matches(defaultBiomes, actualBiome.toUpperCase())) {
                        // addBadChunk: chunk-uniform — authoritative biome read from the
                        // loaded chunk; biome is per-chunk so the twin spiral index in the
                        // same chunk is guaranteed to fail.
                        shape.addBadChunk(pos);
                        fullLoadOutcomePhysBiome.incrementAndGet();
                        res.complete(false);
                        return;
                      }

                      boolean pass = localCursor.y < vert.maxY();
                      if (!pass) {
                        // addBadChunk: chunk-uniform — within a chunk the per-column
                        // selection order is deterministic, so the twin spiral index picks
                        // the same column and the same picked Y > maxY.
                        shape.addBadChunk(pos);
                        fullLoadOutcomeYOverflow.incrementAndGet();
                        res.complete(false);
                        return;
                      }

                      int localX = localCursor.x & 15;
                      int localZ = localCursor.z & 15;

                      int minX = Math.max(0, localX - finalSafetyRadius);
                      int maxX = Math.min(15, localX + finalSafetyRadius);
                      int minZ = Math.max(0, localZ - finalSafetyRadius);
                      int maxZ = Math.min(15, localZ + finalSafetyRadius);

                      for (int xx = minX; xx <= maxX && pass; xx++) {
                        for (int zz = minZ; zz <= maxZ && pass; zz++) {
                          for (int y = localCursor.y - finalSafetyRadius; y <= localCursor.y + finalSafetyRadius && pass; y++) {
                            if (!chunk.isSafe(xx, y, zz, unsafeBlocks)) {
                              pass = false;
                            }
                          }
                        }
                      }

                      if (isCancelled() || pause.get()) {
                        res.complete(false);
                        return;
                      }

                      if (pass) pass = GlobalRegionVerifiers.checkGlobalRegionVerifiers(localCursor).join();

                      if (pass) {
                        fullLoadOutcomeAccept.incrementAndGet();
                        res.complete(true);
                      } else {
                        // addBadChunk: chunk-uniform — within a chunk the per-column
                        // selection order is deterministic, so the twin spiral index picks
                        // the same column and the same (2r+1)^3 safety scan rejects again.
                        shape.addBadChunk(pos);
                        fullLoadOutcomeSafetyScan.incrementAndGet();
                        res.complete(false);
                      }
                    } catch (Throwable t) {
                      RTP.log(Level.SEVERE, "[ScanTask] Validation crashed on Region Thread at location " + pos, t);
                      // addBadChunk: chunk-uniform — within a chunk the per-column
                      // selection order is deterministic, so re-rolling the same chunk
                      // would crash the same way.
                      shape.addBadChunk(pos);
                      fullLoadOutcomeCrashed.incrementAndGet();
                      res.complete(false);
                    }
                  });
                } catch (Throwable t) {
                  RTP.log(Level.SEVERE, "[ScanTask] Async callback crashed at location " + pos, t);
                  // addBadChunk: chunk-uniform — within a chunk the per-column selection
                  // order is deterministic, so re-rolling the same chunk would crash the
                  // async callback identically.
                  shape.addBadChunk(pos);
                  fullLoadOutcomeCrashed.incrementAndGet();
                  res.complete(false);
                }
              });
  }

  /** Unwraps {@link CompletionException}/{@link java.util.concurrent.ExecutionException} layers to the root cause. */
  private static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
            && t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t;
  }

  /**
   * Bounded drain of the in-flight chunk-load futures. Waits up to {@code timeoutMs}
   * for outstanding chunk callbacks to settle so no ghost callback mutates the shape
   * after {@code flushAndRebuild}/{@code save}, but never blocks the caller (command /
   * main thread) indefinitely — a hung or timing-out chunk load must not freeze the
   * server. The cancelled/pause flag already guards late callbacks (they complete
   * false without mutating shape state), so proceeding after a timeout is safe.
   *
   * <p>Any still-tracked chunk futures are cancelled first so the drain does not have
   * to wait out their per-future 30s {@code orTimeout} one at a time; cancellation
   * completes the chain promptly, releases the gate, and suppresses the
   * timeout-exception log spam (the completion guards check the cancelled flag).</p>
   */
  private void drainInFlight(String reason, long timeoutMs) {
    // Actively abort outstanding chunk loads so the gate frees up promptly.
    for (CompletableFuture<?> f : inFlightChunkFutures) {
      try {
        f.cancel(true);
      } catch (Throwable ignored) { }
    }
    long drainStart = System.nanoTime();
    boolean drained = false;
    try {
      drained = inFlightGate.tryAcquire(MAX_PENDING_CHUNKS, timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      if (drained) inFlightGate.release(MAX_PENDING_CHUNKS);
    }
    long drainMs = (System.nanoTime() - drainStart) / 1_000_000L;
    if (drained) {
      RTP.log(Level.FINER, "[ScanTask] " + reason + " drain complete region=" + region.name
              + " drainMs=" + drainMs);
    } else {
      RTP.log(Level.FINE, "[ScanTask] " + reason + " drain timed out region=" + region.name
              + " after " + drainMs + "ms inFlight=" + inFlight.get()
              + " — proceeding (cancelled/pause flag guards late callbacks)");
    }
  }

  public void pause() {
    RTP.log(Level.FINE, "[ScanTask] pause requested region=" + region.name
            + " inFlight=" + inFlight.get());
    pause.set(true);
    // Drain in-flight chunk futures before saving to prevent ghost callbacks.
    drainInFlight("pause", DRAIN_TIMEOUT_MS);
    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    shape.flushAndRebuild(shape.spatialResolution);
    save();
    shape.save(region.name + "_" + region.getWorld().getSeed(), region.getWorld().name());
  }

  @Override
  public void setCancelled(boolean cancelled) {
    if (cancelled) {
      RTP.log(Level.FINE, "[ScanTask] cancellation requested region=" + region.name
              + " inFlight=" + inFlight.get());
      // Mark cancelled BEFORE draining: drainInFlight actively cancels the
      // tracked chunk futures, which fires their whenComplete handlers
      // synchronously on this thread. Those handlers suppress the
      // timeout/cancellation WARNING only when isCancelled() is already true,
      // so the flag must be set first or the cancel spams the very exceptions
      // we are trying to silence.
      super.setCancelled(true);
      try {
        done.cancel(true);
      } catch (CancellationException | CompletionException ignored) { }
      // Drain all in-flight chunk futures before saving, so no ghost callbacks
      // can call addBadLocation() after flushAndRebuild/save. Bounded so a hung
      // chunk load cannot freeze the server, and outstanding loads are cancelled
      // up front to stop the per-future 30s timeout-exception spam.
      drainInFlight("cancel", DRAIN_TIMEOUT_MS);
      MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
      if (shape != null) {
        shape.flushAndRebuild(shape.spatialResolution);
        shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
      }
      save();
      RTP.getInstance().scanTasks.remove(region.name, this);
    }
    super.setCancelled(cancelled);
  }
}
