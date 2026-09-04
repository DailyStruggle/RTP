package io.github.dailystruggle.rtp.common.benchmark;

import java.util.LinkedHashMap;
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
      int regionFreezes) {}

  /** The arm the model is calibrated on. Exactly one, by construction. */
  static final Anchor PAPER_CALIBRATION =
      new Anchor(
          "this plugin (paper)", "paper", true,
          18.7, 1.0, 2.0, 46.0, 518.0, 687.0, 2.79, 85.8, 98.4, 1.58, 0.85, 17.97, -1);

  /** Untouched validation arms. No parameter is permitted to be fitted to any of these. */
  static Map<String, Anchor> validation() {
    Map<String, Anchor> m = new LinkedHashMap<>();
    m.put(
        "clustered live-verify (paper)",
        new Anchor(
            "clustered live-verify (paper)", "paper", false,
            13.1, 30.0, 189.0, 322.0, 1936.0, 2172.0, 65.0, 156.8, 292.0, 3.38, 0.47, 19.06,
            -1));
    m.put(
        "uniform live-verify (paper)",
        new Anchor(
            "uniform live-verify (paper)", "paper", false,
            6.0, 480.0, 3217.0, 4402.0, 6229.0, 6383.0, 1163.0, 859.0, 3663.0, 5.86, 0.08, 25.57,
            -1));
    m.put(
        "this plugin (folia)",
        new Anchor(
            "this plugin (folia)", "folia", false,
            14.6, 100.0, 150.0, 183.0, 268.0, 501.0, Double.NaN, 51.0, 341.0,
            Double.NaN, Double.NaN, 2.87, 0));
    m.put(
        "clustered live-verify (folia)",
        new Anchor(
            "clustered live-verify (folia)", "folia", false,
            7.68, 225.0, 494.0, 704.0, 1399.0, 4598.0, Double.NaN, 51.0, 307.0,
            Double.NaN, Double.NaN, 6.26, 7));
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
}
