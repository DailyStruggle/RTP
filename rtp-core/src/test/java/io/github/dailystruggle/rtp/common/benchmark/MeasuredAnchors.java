package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measured ground truth from the real stress harness, transcribed once so the queueing model has a
 * single calibration and validation surface (ADR-080).
 *
 * <p><b>Why these live in code.</b> A model that is checked against numbers a reader cannot see is
 * not falsifiable. Every field here is a direct measurement from a named run, and the model's own
 * output is reported beside it as a signed residual, so a reader can see where the model is wrong
 * and in which direction.
 *
 * <p><b>Calibration / validation split, and it is not negotiable.</b> Exactly one arm is marked
 * {@link Anchor#calibration()} - the plugin under development on Paper. Its figures are the only
 * ones any parameter is allowed to be fitted to. Every other arm is untouched validation. Fitting to
 * a competitor's row and then reporting agreement with that row is circular, and it is the specific
 * failure this split exists to prevent.
 *
 * <p><b>Provenance.</b> Latency, throughput and MSPT figures are from the throughput/stability runs
 * on a pregenerated world, radius 4096 blocks on every plugin, cooldowns and teleport delays
 * zeroed, 3 concurrent clients issuing back-to-back requests. Chunks-per-attempt and async
 * chunk-load share are from the earlier instrumented Paper run (`20260617-175906`), which measured
 * chunk attribution but not the full latency distribution. Mixing two runs is a known weakness and
 * is reported as such: the counts and the latencies were not taken simultaneously.
 */
final class MeasuredAnchors {

  private MeasuredAnchors() {}

  /**
   * One measured arm.
   *
   * @param name arm label, used as the report subject
   * @param platform {@code paper} or {@code folia}
   * @param calibration true for the single arm parameters may be fitted to
   * @param throughputPerSecond delivered teleports per second over the phase
   * @param p50Millis latency percentiles as measured end to end per request
   * @param meanMillis arithmetic mean latency; reported because it exposes the bimodality that a
   *     percentile set alone hides (a p50 of 1 ms with a mean of 2.79 ms is a different distribution
   *     from a p50 of 30 ms with a mean of 65 ms)
   * @param msptP99Millis tick-time percentiles, the operator-visible health axis
   * @param chunksPerAttempt live chunk materialisations per attempt, or {@code NaN} when the run
   *     that produced the latency figures did not attribute chunk loads
   * @param asyncChunkShare fraction of chunk loads served off the tick thread, or {@code NaN}
   * @param mainCpuPerAttemptMillis tick-thread CPU per attempt. Combined with the two columns above
   *     this pins the cost of one foreground chunk load with no fitting at all:
   *     {@code mainCpu / (chunks * (1 - asyncShare))}. Deriving that cost instead of fitting it is
   *     what keeps the model's tail an output rather than a tuned parameter.
   * @param regionFreezes Folia region freeze events observed over the phase, or {@code -1} when not
   *     applicable to the platform
   * @param servedFromCacheFraction fraction of attempts the harness classified as served without
   *     selection chunk work, or {@link #NO_VALUE}. This is the measurement the latency gate is
   *     blocked on: a p50 sitting on the boundary between the cached and cold modes cannot be
   *     validated without knowing which side of it the real distribution mostly falls on.
   * @param foregroundChunkShare measured fraction of chunk loads that landed on a tick thread, or
   *     {@link #NO_VALUE}. Independent of {@link #asyncChunkShare()}, which is an aggregate of a
   *     different run; the two are reported side by side and never averaged.
   * @param regionFileReadsPerAttempt distinct 32x32 bins touched per attempt, or {@link #NO_VALUE}
   * @param binCandidatesPerBatch measured candidates per binned batch, or {@link #NO_VALUE}. The
   *     model currently assumes 64; batching is worth up to four orders of magnitude on the read
   *     term, so an assumed occupancy is an assumed order of magnitude.
   * @param storageReadColdP50Us first-touch region-file read p50 on the rig, or {@link #NO_VALUE}
   * @param storageClass device verdict, or {@link #NO_LABEL}. Without it a read-cost figure is
   *     machine-relative and non-portable, so the label travels with the number or the number is
   *     not used.
   * @param regionContextAcquisitionsPerAttempt measured Folia region-context acquisitions per
   *     attempt, or {@link #NO_VALUE}
   */
  record Anchor(
      String name,
      String platform,
      boolean calibration,
      double throughputPerSecond,
      double p50Millis,
      double p95Millis,
      double p99Millis,
      double p999Millis,
      double maxMillis,
      double meanMillis,
      double msptP99Millis,
      double msptMaxMillis,
      double chunksPerAttempt,
      double asyncChunkShare,
      double mainCpuPerAttemptMillis,
      int regionFreezes,
      double servedFromCacheFraction,
      double foregroundChunkShare,
      double regionFileReadsPerAttempt,
      double binCandidatesPerBatch,
      double storageReadColdP50Us,
      String storageClass,
      double regionContextAcquisitionsPerAttempt) {}

  /**
   * No-data sentinel for every real-valued harness quantity.
   *
   * <p>{@code NaN} rather than {@code 0} or {@code -1} on purpose: it propagates through arithmetic
   * instead of silently producing a plausible product, so an absent input cannot become a quiet
   * zero somewhere downstream. Counts are still written as {@code -1} in the harness CSV; they are
   * translated to this sentinel on transcription.
   */
  static final double NO_VALUE = Double.NaN;

  /** No-data sentinel for string-valued harness quantities. Empty, never {@code "UNKNOWN"}. */
  static final String NO_LABEL = "";

  /**
   * The harness-supplied quantities this tier would consume, in the order they are reported.
   *
   * <p>Named here so the completeness gate enumerates them from one place rather than from whatever
   * the report happened to emit. Adding an instrumented quantity to the harness and forgetting to
   * add it here would make the gate pass by omission.
   */
  static final List<String> EXPECTED_INPUTS =
      List.of(
          "servedFromCacheFraction",
          "foregroundChunkShare",
          "regionFileReadsPerAttempt",
          "binCandidatesPerBatch",
          "storageReadColdP50Us",
          "storageClass",
          "regionContextAcquisitionsPerAttempt");

  /**
   * The arm the model is calibrated on. Exactly one, by construction.
   *
   * <p>The seven trailing fields are the newly instrumented harness quantities. Every one of them
   * carries the no-data sentinel because no run has been executed under any of the instrumentation
   * tracks - see the checklist statuses M7 / S7 / F7 / N7 / F-FG9. They are transcribed the moment
   * an archived run supplies them, and no other change is then needed.
   */
  static final Anchor PAPER_CALIBRATION =
      new Anchor(
          "this plugin (paper)", "paper", true,
          18.7, 1.0, 2.0, 46.0, 518.0, 687.0, 2.79, 85.8, 98.4, 1.58, 0.85, 17.97, -1,
          NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_LABEL, NO_VALUE);

  /** Untouched validation arms. No parameter is permitted to be fitted to any of these. */
  static Map<String, Anchor> validation() {
    Map<String, Anchor> m = new LinkedHashMap<>();
    m.put(
        "clustered live-verify (paper)",
        new Anchor(
            "clustered live-verify (paper)", "paper", false,
            13.1, 30.0, 189.0, 322.0, 1936.0, 2172.0, 65.0, 156.8, 292.0, 3.38, 0.47, 19.06,
            -1,
            NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_LABEL, NO_VALUE));
    m.put(
        "uniform live-verify (paper)",
        new Anchor(
            "uniform live-verify (paper)", "paper", false,
            6.0, 480.0, 3217.0, 4402.0, 6229.0, 6383.0, 1163.0, 859.0, 3663.0, 5.86, 0.08, 25.57,
            -1,
            NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_LABEL, NO_VALUE));
    m.put(
        "this plugin (folia)",
        new Anchor(
            "this plugin (folia)", "folia", false,
            14.6, 100.0, 150.0, 183.0, 268.0, 501.0, Double.NaN, 51.0, 341.0,
            Double.NaN, Double.NaN, 2.87, 0,
            NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_LABEL, NO_VALUE));
    m.put(
        "clustered live-verify (folia)",
        new Anchor(
            "clustered live-verify (folia)", "folia", false,
            7.68, 225.0, 494.0, 704.0, 1399.0, 4598.0, Double.NaN, 51.0, 307.0,
            Double.NaN, Double.NaN, 6.26, 7,
            NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_LABEL, NO_VALUE));
    return m;
  }

  /**
   * Signed relative error, model against measurement.
   *
   * <p>Signed rather than absolute on purpose. A model that is wrong by 40 % on every arm in the
   * flattering direction is biased, and an absolute-error gate cannot tell that apart from a model
   * that is wrong by 40 % in both directions. The sign is what makes the bias test possible.
   *
   * @return {@code (model - measured) / measured}, or {@code NaN} when there is nothing to compare
   */
  static double signedError(double model, double measured) {
    if (Double.isNaN(measured) || measured == 0.0) return Double.NaN;
    return (model - measured) / measured;
  }

  /** Ratio form, for the "within 2x" style of gate. Always {@code >= 1}. */
  static double foldError(double model, double measured) {
    if (Double.isNaN(measured) || measured <= 0.0 || model <= 0.0) return Double.NaN;
    return model > measured ? model / measured : measured / model;
  }

  /**
   * Requests in flight, by Little's law.
   *
   * <p>A structural check that costs nothing and is independent of every cost constant: a design
   * measured at 6.0 teleports per second with a 1 163 ms mean latency was carrying ~7 requests
   * concurrently while only 3 clients were asking, which locates its queue inside the plugin or the
   * platform rather than at the client. A model that reproduces the latency but not the concurrency
   * has reproduced the number without the mechanism.
   */
  static double inFlight(double throughputPerSecond, double meanMillis) {
    return throughputPerSecond * meanMillis / 1_000.0;
  }

  /** True when a real-valued harness quantity is present. A zero is present; a sentinel is not. */
  static boolean hasValue(double v) {
    return !Double.isNaN(v);
  }

  /** True when a string-valued harness quantity is present. */
  static boolean hasLabel(String v) {
    return v != null && !v.isBlank();
  }

  /**
   * Provenance computed from the sentinel rather than asserted by hand.
   *
   * <p>The label and the data therefore cannot drift apart: a quantity is MEASURED exactly when a
   * run supplied it, and the modelled fallback keeps its MODELED label automatically. Hand-written
   * provenance is how a placeholder gets published as a measurement.
   */
  static Provenance provenanceOf(double harnessValue) {
    return hasValue(harnessValue) ? Provenance.MEASURED : Provenance.MODELED;
  }

  /** The value when the harness supplied it, otherwise the model's own constant. */
  static double orModelled(double harnessValue, double modelled) {
    return hasValue(harnessValue) ? harnessValue : modelled;
  }

  /**
   * Names of the expected harness inputs that no anchor supplies.
   *
   * <p>Scanned across every arm, calibration included: an input present on one arm only cannot
   * validate a between-arm comparison, so "present" means present somewhere is deliberately
   * <em>not</em> the test - a name is reported absent unless the calibration arm carries it.
   */
  static List<String> absentInputs() {
    Anchor a = PAPER_CALIBRATION;
    List<String> absent = new ArrayList<>();
    if (!hasValue(a.servedFromCacheFraction())) absent.add("servedFromCacheFraction");
    if (!hasValue(a.foregroundChunkShare())) absent.add("foregroundChunkShare");
    if (!hasValue(a.regionFileReadsPerAttempt())) absent.add("regionFileReadsPerAttempt");
    if (!hasValue(a.binCandidatesPerBatch())) absent.add("binCandidatesPerBatch");
    if (!hasValue(a.storageReadColdP50Us())) absent.add("storageReadColdP50Us");
    if (!hasLabel(a.storageClass())) absent.add("storageClass");
    if (!hasValue(a.regionContextAcquisitionsPerAttempt())) {
      absent.add("regionContextAcquisitionsPerAttempt");
    }
    return absent;
  }
}
