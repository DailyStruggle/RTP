package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Latency and throughput tier: a discrete-event queueing model calibrated on one measured arm and
 * validated against the rest (ADR-080).
 *
 * <p><b>Opt in only.</b> {@code @Tag("simulation")} plus the {@code **&#47;benchmark&#47;**}
 * exclusion on the {@code test} task. Runs via {@code :rtp-core:simulationBenchmark}.
 *
 * <p><b>The discipline that makes the output worth reading.</b> Three parameters are fitted, and
 * they are fitted against exactly one arm - this plugin on Paper. Every competitor arm, and both
 * Folia arms, are then run with those same frozen parameters and their own behavioural
 * configuration, and the residual against measurement is reported. Nothing is fitted to a
 * competitor row: agreement with a row that was used to choose the parameters is not evidence.
 *
 * <p><b>Signed residuals, not absolute.</b> The previous operation-count model passed a 2x gate
 * while being conservative in the flattering direction on every single arm, which is bias rather
 * than accuracy. The bias row here is the mean signed residual across validation arms; a model that
 * misses in one direction everywhere fails even when every individual arm is inside tolerance.
 *
 * <p><b>Assertions are self-consistency only.</b> Determinism, non-empty report, every arm serving
 * requests. The accuracy gates are emitted as report rows, deliberately not as red tests: a failing
 * test creates pressure to loosen a threshold, and these thresholds were fixed before any result
 * was read.
 */
@Tag("simulation")
@DisplayName("ADR-080 latency and throughput: queueing model against measured runs")
class LatencyQueueBenchmarkTest {

  private static final String SECTION = "latency queue";

  private static final String GATE = "latency queue, gates";

  private static final SimulationReport REPORT = new SimulationReport();

  /** Requests per arm in the reported runs. Enough for a stable p99.9 at 3 clients. */
  private static final int REQUESTS = 20_000;

  /** Shorter runs during the parameter search; the search only needs the coarse shape. */
  private static final int SEARCH_REQUESTS = 6_000;

  /** The harness ran three accounts issuing back-to-back requests. */
  private static final int CLIENTS = 3;

  /** Platform async chunk-IO / plugin worker parallelism. MODELED. */
  private static final int ASYNC_WORKERS = 8;

  private static final int CHUNK_RADIUS = 256;

  private static final int CHUNKS_PER_BLOB = 24;

  /** Anchored to the 35-65 % unsafe-ground range measured on real worlds. */
  private static final double UNSAFE_FRACTION = 0.45;

  /**
   * Candidates that share one region file in a binned batch. Region files hold 32x32 chunks, so a
   * batch large enough to cover the region amortises one read across many candidates; 64 is a
   * deliberately conservative occupancy rather than the 1024 a full region would allow.
   */
  private static final double BINNED_BATCH = 64.0;

  /** Measured by the anvil benchmark in this same tier: cold region-file read, p50. */
  private static final double REGION_READ_COLD_US = 2_075.0;

  /** Safety probe through the live block API once the chunk is resident. MODELED. */
  private static final double LIVE_BLOCK_CHECK_US = 400.0;

  /** Spatial-memory rejection: no chunk, no file read. Measured warm-hit order of magnitude. */
  private static final double MEMORY_LOOKUP_US = 2.0;

  /** Folia region-context acquisition. MODELED; the axis section 14 predicted and never priced. */
  private static final double REGION_HOP_US = 1_500.0;

  /** Server tick cost with no plugin work. MODELED. */
  private static final double BASE_TICK_US = 20_000.0;

  /**
   * Background cache-refill pulse interval, one server tick. The shipped queue manager fills on a
   * scheduled task with a bounded budget per pulse, so refill is paced rather than greedy.
   *
   * <p>Pulse width matters as much as rate. The same 40 locations per second delivered as a single
   * 40-item burst once a second produced a 19x over-prediction of the calibration arm's p50,
   * because any request arriving inside the burst queued behind all of it. Per-tick pacing
   * delivers the same
   * rate without the self-inflicted burst - and matches how the task is actually scheduled.
   */
  private static final long REFILL_PULSE_US = 50_000L;

  /**
   * Candidate evaluations one refill pulse may perform. Fitted on the calibration arm and then
   * applied identically to every arm that caches - it is a scheduling configuration, not a platform
   * constant, and a measured 1 ms p50 with a 2 ms p95 is only reachable if refill outruns demand.
   * Holding it identical across arms keeps it from becoming a per-arm tuning knob dressed as a
   * configuration difference; what then differs between arms is the capacity their own verification
   * order consumes per location produced.
   */
  private static int refillBudget = 8;

  private static QueueSim.Costs calibrated;

  private static double dispatchCap;

  @AfterAll
  static void writeReport() {
    REPORT.write("latency-queue");
  }

  private static CandidateStream stream() {
    return new CandidateStream(1 << 20, CHUNK_RADIUS, CHUNKS_PER_BLOB, UNSAFE_FRACTION, 20260904L);
  }

  /**
   * Cold region-file read: the harness measurement when a run supplied it, this tier's own anvil
   * figure otherwise.
   *
   * <p>The fallback is not a placeholder - it is a real measurement from the same tier - but it is
   * one machine's figure with no recorded device class, which is why the harness column supersedes
   * it the moment it exists. The substitution happens here and nowhere else, so the two can never
   * both be in play.
   */
  private static double regionReadColdUs() {
    return MeasuredAnchors.orModelled(
        MeasuredAnchors.PAPER_CALIBRATION.storageReadColdP50Us(), REGION_READ_COLD_US);
  }

  /** Candidates per binned batch: measured occupancy when available, the assumed 64 otherwise. */
  private static double binnedBatch() {
    return MeasuredAnchors.orModelled(
        MeasuredAnchors.PAPER_CALIBRATION.binCandidatesPerBatch(), BINNED_BATCH);
  }

  private static QueueSim.Costs costs(double chunkLoadUs, double servePathUs) {
    return new QueueSim.Costs(
        chunkLoadUs,
        regionReadColdUs(),
        regionReadColdUs() / binnedBatch(),
        LIVE_BLOCK_CHECK_US,
        servePathUs,
        MEMORY_LOOKUP_US,
        REGION_HOP_US,
        BASE_TICK_US);
  }

  /**
   * This plugin's behaviour: answer safety from region-file bytes off-tick, bin those reads by
   * owning region file, remember rejected ground durably, keep a bounded destination cache.
   */
  private static QueueSim.Arm thisPlugin(boolean folia) {
    return new QueueSim.Arm(
        folia ? "this plugin (folia)" : "this plugin (paper)",
        folia,
        true,
        true,
        true,
        0L,
        false,
        1_024,
        folia ? 0.14 : 0.85,
        1.0,
        32,
        REFILL_PULSE_US,
        refillBudget);
  }

  /**
   * Cost of one foreground chunk load, derived rather than fitted.
   *
   * <p>The calibration run measured tick-thread CPU per attempt, chunks per attempt and the share
   * of chunk loads served off-tick. Those three pin the foreground cost of a single chunk by
   * arithmetic - {@code mainCpu / (chunks * (1 - asyncShare))} - with no search and no target
   * latency involved. It matters that this is derived: chunk cost is the dominant term in every
   * arm, and a fitted dominant term makes every downstream figure a restatement of the fit.
   *
   * <p>A three-parameter search that included this value independently selected 73.8 ms against the
   * 75.8 ms this arithmetic gives - a 3 % agreement between a fit and a derivation that share no
   * inputs, which is the reason for trusting the queue structure at all.
   */
  private static double derivedChunkLoadUs() {
    MeasuredAnchors.Anchor a = MeasuredAnchors.PAPER_CALIBRATION;
    return a.mainCpuPerAttemptMillis() * 1_000.0
        / (a.chunksPerAttempt() * (1.0 - a.asyncChunkShare()));
  }

  /**
   * Fit the two remaining parameters against the one calibration arm.
   *
   * <p>Dispatch-rate ceiling and serve-path cost. The first sets whether the arm is dispatch- or
   * service-limited; the second sets the cache-hit mode, which is what a 1 ms p50 is. Chunk cost is
   * derived, not searched. Objective is the sum of squared log ratios over throughput, p50, p99 and
   * p99.9, so an error of 2x costs the same whichever way it points and no single percentile can
   * dominate the fit.
   */
  @Test
  @DisplayName("calibration: fit cost parameters against the measured Paper run for this plugin")
  void calibrate() {
    MeasuredAnchors.Anchor target = MeasuredAnchors.PAPER_CALIBRATION;
    CandidateStream stream = stream();

    double bestScore = Double.MAX_VALUE;
    double bestChunk = 0.0;
    double bestServe = 0.0;
    double bestCap = 0.0;
    int bestBudget = refillBudget;

    double chunkLoadUs = derivedChunkLoadUs();
    {
      for (int budget : new int[] {4, 8, 16, 32, 64, 128}) {
        refillBudget = budget;
        for (double cap = 12.0; cap <= 60.0; cap += 2.0) {
          for (double serveUs = 100.0; serveUs <= 3_200.0; serveUs *= 1.5) {
          QueueSim.Result r =
              QueueSim.run(
                  thisPlugin(false),
                  costs(chunkLoadUs, serveUs),
                  stream,
                  SEARCH_REQUESTS,
                  CLIENTS,
                  cap,
                  ASYNC_WORKERS);
          double score =
              sq(logRatio(r.throughputPerSecond(), target.throughputPerSecond()))
                  + sq(logRatio(r.p50Millis(), target.p50Millis()))
                  + sq(logRatio(r.p99Millis(), target.p99Millis()))
                  + sq(logRatio(r.p999Millis(), target.p999Millis()));
            if (score < bestScore) {
              bestScore = score;
              bestChunk = chunkLoadUs;
              bestServe = serveUs;
              bestCap = cap;
              bestBudget = budget;
            }
          }
        }
      }
    }

    calibrated = costs(bestChunk, bestServe);
    dispatchCap = bestCap;
    refillBudget = bestBudget;

    REPORT.add(SECTION, "calibration", "derived foreground chunk-load cost (us)", bestChunk,
        Provenance.DERIVED);
    REPORT.add(SECTION, "calibration", "fitted serve-path cost (us)", bestServe, Provenance.DERIVED);
    REPORT.add(SECTION, "calibration", "fitted dispatch ceiling (per s)", bestCap,
        Provenance.DERIVED);
    REPORT.add(SECTION, "calibration", "fitted refill budget per tick",
        Integer.toString(bestBudget), Provenance.DERIVED);
    REPORT.add(SECTION, "calibration", "objective (sum of squared log ratios)", bestScore,
        Provenance.DERIVED);
    REPORT.add(SECTION, "calibration", "region-file cold read (us)", regionReadColdUs(),
        Provenance.MEASURED);
    REPORT.add(SECTION, "calibration", "binned read per candidate (us)",
        regionReadColdUs() / binnedBatch(), Provenance.DERIVED);
    REPORT.add(SECTION, "calibration", "candidates per binned batch", binnedBatch(),
        MeasuredAnchors.provenanceOf(MeasuredAnchors.PAPER_CALIBRATION.binCandidatesPerBatch()));
    // Empty is the string sentinel. A read cost without a device class is machine-relative, so the
    // label rides in the same table as the cost rather than in a caption.
    REPORT.add(SECTION, "calibration", "storage class (empty = not measured)",
        MeasuredAnchors.PAPER_CALIBRATION.storageClass(),
        MeasuredAnchors.hasLabel(MeasuredAnchors.PAPER_CALIBRATION.storageClass())
            ? Provenance.MEASURED
            : Provenance.MODELED);
    REPORT.add(SECTION, "calibration", "live block check (us)", LIVE_BLOCK_CHECK_US,
        Provenance.MODELED);
    REPORT.add(SECTION, "calibration", "region hop (us)", REGION_HOP_US, Provenance.MODELED);
    REPORT.add(SECTION, "calibration", "async workers", Integer.toString(ASYNC_WORKERS),
        Provenance.MODELED);
    REPORT.add(SECTION, "calibration", "clients", Integer.toString(CLIENTS), Provenance.MEASURED);

    REPORT.note(
        "The dominant cost term is derived, not fitted. Foreground chunk-load cost comes from the "
            + "measured tick-thread CPU, chunks per attempt and async share of the calibration run "
            + "by arithmetic alone; an independent three-parameter search that included it selected "
            + "a value 3 % away, which is the main reason to trust the queue structure. Three "
            + "scheduling parameters are fitted - dispatch ceiling, serve-path cost and refill "
            + "budget - against one arm only, this plugin on Paper, and are then applied unchanged "
            + "to every other arm. It still absorbs the omitted terms (worldgen, unload and save, "
            + "view-distance aftershock, host GC), so treat it as a model parameter and never as a "
            + "platform figure.");

    assertTrue(bestChunk > 0.0, "calibration must select a chunk-load cost");
    assertTrue(Double.isFinite(bestScore), "objective must be finite");
  }

  /**
   * Run every arm with the frozen parameters and report the residual against measurement.
   *
   * <p>Chunk count is matched per arm in one step (measured chunks per attempt divided by the
   * candidates per request the model produces), so chunks-per-request is an <em>input</em> for the
   * competitor arms and is explicitly not evidence. Latency, throughput, tick time and in-flight
   * concurrency are the outputs that carry the validation.
   */
  @Test
  @DisplayName("validation: frozen parameters against every measured arm, with signed residuals")
  void validate() {
    if (calibrated == null) calibrate();

    CandidateStream stream = stream();
    Map<String, QueueSim.Arm> arms = new LinkedHashMap<>();
    arms.put(MeasuredAnchors.PAPER_CALIBRATION.name(), thisPlugin(false));
    // Clustered selection, load-then-check through the live block API, a shallow destination cache
    // at its published default depth, and blanket wall-clock expiry of remembered ground.
    arms.put(
        "clustered live-verify (paper)",
        new QueueSim.Arm(
            "clustered live-verify (paper)", false, false, false, true, 900_000_000L, true, 20,
            0.47, 1.0, 32, REFILL_PULSE_US, refillBudget));
    // Uniform selection, load-then-check, no destination cache and no memory at all: every request
    // starts from nothing and almost all of its chunk work lands on the tick thread.
    arms.put(
        "uniform live-verify (paper)",
        new QueueSim.Arm(
            "uniform live-verify (paper)", false, false, false, false, 0L, false, 0,
            0.08, 1.0, 32, REFILL_PULSE_US, 0));
    arms.put("this plugin (folia)", thisPlugin(true));
    arms.put(
        "clustered live-verify (folia)",
        new QueueSim.Arm(
            "clustered live-verify (folia)", true, false, false, true, 900_000_000L, true, 20,
            0.0, 1.0, 32, REFILL_PULSE_US, refillBudget));

    Map<String, MeasuredAnchors.Anchor> anchors = MeasuredAnchors.validation();
    anchors.put(MeasuredAnchors.PAPER_CALIBRATION.name(), MeasuredAnchors.PAPER_CALIBRATION);

    double biasSum = 0.0;
    int biasCount = 0;
    int withinTwoFold = 0;
    int compared = 0;

    for (Map.Entry<String, QueueSim.Arm> e : arms.entrySet()) {
      MeasuredAnchors.Anchor a = anchors.get(e.getKey());
      QueueSim.Arm arm = matchChunkCount(e.getValue(), a, stream);

      QueueSim.Result r =
          QueueSim.run(arm, calibrated, stream, REQUESTS, CLIENTS, dispatchCap, ASYNC_WORKERS);
      QueueSim.Result again =
          QueueSim.run(arm, calibrated, stream, REQUESTS, CLIENTS, dispatchCap, ASYNC_WORKERS);
      assertTrue(
          r.p999Millis() == again.p999Millis(),
          "replay must be deterministic for " + e.getKey());
      assertTrue(r.unservedFraction() < 0.05, "arm must serve requests: " + e.getKey());

      String subject = e.getKey() + (a.calibration() ? " [calibration]" : " [validation]");

      emit(subject, "throughput (per s)", r.throughputPerSecond(), a.throughputPerSecond());
      emit(subject, "latency p50 (ms)", r.p50Millis(), a.p50Millis());
      emit(subject, "latency p95 (ms)", r.p95Millis(), a.p95Millis());
      emit(subject, "latency p99 (ms)", r.p99Millis(), a.p99Millis());
      emit(subject, "latency p99.9 (ms)", r.p999Millis(), a.p999Millis());
      emit(subject, "latency max (ms)", r.maxMillis(), a.maxMillis());
      emit(subject, "latency mean (ms)", r.meanMillis(), a.meanMillis());
      emit(subject, "MSPT p99 (ms)", r.msptP99Millis(), a.msptP99Millis());
      emit(subject, "MSPT max (ms)", r.msptMaxMillis(), a.msptMaxMillis());
      emit(
          subject,
          "in flight (Little's law)",
          r.inFlight(),
          MeasuredAnchors.inFlight(a.throughputPerSecond(), a.meanMillis()));

      // Bimodality: a mean describes neither mode, so the mode split is reported next to it. When
      // the harness supplies its own mode split this becomes a residual - the measurement the
      // accuracy gate is blocked on - and until then it stays a bare model output.
      emit(subject, "served from cache (fraction)", r.cacheHitFraction(),
          a.servedFromCacheFraction());
      emit(subject, "foreground chunk share", 1.0 - arm.asyncChunkShare(),
          a.foregroundChunkShare());
      if (arm.folia()) {
        emit(subject, "region contexts per request", r.attemptsPerRequest(),
            a.regionContextAcquisitionsPerAttempt());
      }
      REPORT.add(SECTION, subject, "candidates evaluated per request", r.attemptsPerRequest(),
          Provenance.MODELED);
      REPORT.add(SECTION, subject, "chunk materialisations per request (input)",
          r.chunksPerRequest(), Provenance.DERIVED);
      REPORT.add(SECTION, subject, "stalls over 1 s per 1000", r.stallsOverOneSecond(),
          Provenance.MODELED);
      REPORT.add(SECTION, subject, "stalls over 5 s per 1000", r.stallsOverFiveSeconds(),
          Provenance.MODELED);
      if (arm.folia()) {
        REPORT.add(SECTION, subject, "region freezes (model)",
            Integer.toString(r.regionFreezes()), Provenance.MODELED);
        REPORT.add(SECTION, subject, "region freezes (measured)",
            Integer.toString(a.regionFreezes()), Provenance.MEASURED);
      }

      if (!a.calibration()) {
        for (double[] pair :
            new double[][] {
              {r.throughputPerSecond(), a.throughputPerSecond()},
              {r.p50Millis(), a.p50Millis()},
              {r.p99Millis(), a.p99Millis()},
              {r.meanMillis(), a.meanMillis()}
            }) {
          double signed = MeasuredAnchors.signedError(pair[0], pair[1]);
          if (Double.isNaN(signed)) continue;
          biasSum += signed;
          biasCount++;
          compared++;
          if (MeasuredAnchors.foldError(pair[0], pair[1]) <= 2.0) withinTwoFold++;
        }
      }
    }

    double bias = biasCount > 0 ? biasSum / biasCount : Double.NaN;
    double withinFraction = compared > 0 ? (double) withinTwoFold / compared : 0.0;

    // Thresholds fixed before any result was read. Emitted as rows, not assertions: see the class
    // comment. A failure here means no latency or throughput figure from this tier may be
    // published, which is a decision for the maintainer, not for the build.
    REPORT.add(GATE, "gate", "validation comparisons", Integer.toString(compared),
        Provenance.DERIVED);
    REPORT.add(GATE, "gate", "within 2x (fraction, threshold 0.80)", withinFraction,
        Provenance.DERIVED);
    REPORT.add(GATE, "gate", "mean signed residual (bias, threshold +-0.50)", bias,
        Provenance.DERIVED);
    REPORT.add(GATE, "gate", "verdict: accuracy",
        withinFraction >= 0.80 ? "PASS" : "FAIL", Provenance.DERIVED);
    REPORT.add(GATE, "gate", "verdict: bias",
        Math.abs(bias) <= 0.50 ? "PASS" : "FAIL", Provenance.DERIVED);

    // Input completeness, fixed in advance alongside the other two thresholds. An absent input is
    // not a smaller failure than a numeric one: the accuracy rows above fall back to modelled
    // constants wherever a harness column is missing, so accuracy passing over absent inputs would
    // be the model agreeing with itself.
    List<String> absent = MeasuredAnchors.absentInputs();
    int expectedInputs = MeasuredAnchors.EXPECTED_INPUTS.size();
    boolean complete = absent.isEmpty();
    REPORT.add(GATE, "gate", "harness inputs present",
        (expectedInputs - absent.size()) + " of " + expectedInputs, Provenance.DERIVED);
    REPORT.add(GATE, "gate", "harness inputs absent",
        complete ? MeasuredAnchors.NO_LABEL : String.join(" ", absent), Provenance.DERIVED);
    REPORT.add(GATE, "gate", "verdict: input completeness", complete ? "PASS" : "FAIL",
        Provenance.DERIVED);
    REPORT.add(
        GATE,
        "gate",
        "verdict: publishable",
        !complete
            ? "FAIL (INPUTS ABSENT)"
            : (withinFraction >= 0.80 && Math.abs(bias) <= 0.50 ? "PASS" : "FAIL"),
        Provenance.DERIVED);

    REPORT.note(
        "Chunk materialisations per request is an input on the competitor arms, matched in one step "
            + "to the measured chunk attribution, and is therefore not evidence of anything. The "
            + "validation rests on latency percentiles, throughput, tick time and in-flight "
            + "concurrency, none of which were fitted to any competitor row.");
    REPORT.note(
        "The two runs that produced the anchors are not the same run: latency, throughput and MSPT "
            + "come from the throughput and stability phases, while chunk attribution and async "
            + "share come from the earlier instrumented Paper run. A residual may therefore be a "
            + "mismatch between runs rather than a model error.");
    REPORT.note(
        "Folia region freezes are the crudest thing in this tier: a region more than five seconds "
            + "behind is counted once per episode. Only the sign of that count - zero for the "
            + "off-tick prefilter, non-zero for a live-block verifier that must acquire a region "
            + "per scattered candidate - should be read as a result. The magnitude should not.");
    REPORT.note(
        "Where the model is still wrong, and why it is deliberately left wrong: the clustered "
            + "live-verify arm over-predicts p50 by roughly an order of magnitude. Its modelled "
            + "cache-hit fraction sits within a few points of 0.5, so its p50 falls on the boundary "
            + "between the cached and cold modes and is extremely sensitive - the measured mean of "
            + "65 ms against a 30 ms p50 says the real distribution straddles the same boundary. "
            + "Closing that residual would require fitting a parameter to a competitor row, which "
            + "the calibration split forbids. It needs a measurement, not a knob: cache-hit rate "
            + "per plugin. The harness is now instrumented for it and the residual row is wired, "
            + "but no run has emitted the column, so the slot holds its no-data sentinel.");
    REPORT.note(
        "The bias verdict failing while individual arms improve is the gate working as intended. "
            + "Residuals are now predominantly positive, meaning the model over-states competitor "
            + "cost - which flatters this plugin. That is exactly the direction a published figure "
            + "must not be wrong in, so no latency or throughput figure from this tier may be "
            + "published until the bias row passes.");
    REPORT.note(
        "Input completeness is a gate in its own right, and it fails. The harness has been "
            + "instrumented for a per-attempt cache-served flag, a foreground/async chunk split, "
            + "region-file read counts with measured bin occupancy, a device class for the read "
            + "cost and Folia region-context acquisitions, but no run has yet emitted any of "
            + "them, so every slot carries its no-data sentinel and the model falls back to its "
            + "own constants. Nothing was relabelled MEASURED for that reason: a provenance label "
            + "with no measurement behind it is worse than a missing row, because it reads as "
            + "evidence.");
    REPORT.note(
        "Every omission runs one way. World generation, chunk unload and save, entity and "
            + "view-distance aftershock, host GC pauses and the platform-owned chunk payload are "
            + "all absent, and all of them cost more to a design that materialises more chunks. "
            + "Model spread between designs is a lower bound.");

    assertTrue(REPORT.size() > 0, "report must contain rows");
  }

  /**
   * One-step match of an arm's chunk count to its measured chunk attribution.
   *
   * <p>Run once at unit cost to learn how many candidates the arm's behaviour evaluates per
   * request, then set chunks-per-candidate so the product lands on the measured figure. Disclosed
   * as an input rather than presented as agreement.
   */
  private static QueueSim.Arm matchChunkCount(
      QueueSim.Arm arm, MeasuredAnchors.Anchor anchor, CandidateStream stream) {
    if (Double.isNaN(anchor.chunksPerAttempt()) || anchor.chunksPerAttempt() <= 0.0) return arm;
    QueueSim.Result probe =
        QueueSim.run(
            arm, calibrated, stream, SEARCH_REQUESTS, CLIENTS, dispatchCap, ASYNC_WORKERS);
    if (probe.chunksPerRequest() <= 0.0) return arm;
    double scaled =
        arm.chunksPerCandidate() * anchor.chunksPerAttempt() / probe.chunksPerRequest();
    return new QueueSim.Arm(
        arm.name(),
        arm.folia(),
        arm.prefilterFirst(),
        arm.binnedReads(),
        arm.durableMemory(),
        arm.memoryTtlUs(),
        arm.clustered(),
        arm.cacheDepth(),
        arm.asyncChunkShare(),
        Math.max(1.0, scaled),
        arm.maxAttempts(),
        arm.refillPulseUs(),
        arm.refillBudget());
  }

  private static void emit(String subject, String metric, double model, double measured) {
    REPORT.add(SECTION, subject, metric + " [model]", model, Provenance.MODELED);
    if (Double.isNaN(measured)) return;
    REPORT.add(SECTION, subject, metric + " [measured]", measured, Provenance.MEASURED);
    REPORT.add(SECTION, subject, metric + " [signed residual]",
        MeasuredAnchors.signedError(model, measured), Provenance.DERIVED);
  }

  private static double logRatio(double model, double measured) {
    if (model <= 0.0 || measured <= 0.0) return 4.0;
    return Math.log(model / measured);
  }

  private static double sq(double v) {
    return v * v;
  }
}
