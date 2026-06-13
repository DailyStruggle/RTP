package io.github.dailystruggle.rtp.common.configuration.enums;

/**
 * Configuration keys for the {@code metrics.yml} surface.
 * Reporting knobs only - throttle / tuning knobs continue to
 * live under {@code performance.yml}.
 *
 * <p>Defaults match {@code docs/dev/METRICS_PLAN.md > Folia Aggregation}
 * (mean for tps*, max for mspt/tickBudget) and the A4 resolution
 * ({@code foliaIncludeRegions: true}).
 */
public enum MetricsKeys {
  /** Whether {@code MetricsSnapshot.foliaRegions()} is populated on Folia (A4-resolved default: true). */
  foliaIncludeRegions,
  /** Aggregation strategy for the scalar {@code tps*m} fields when running on Folia. Accepts {@code mean} or {@code max}. */
  foliaAggregationTps,
  /** Aggregation strategy for the scalar {@code mspt} field when running on Folia. Accepts {@code mean} or {@code max}. */
  foliaAggregationMspt,
  /** Aggregation strategy for the scalar {@code tickBudgetUtilisation} field when running on Folia. Accepts {@code mean} or {@code max}. */
  foliaAggregationTickBudget,
  /** The configuration version */
  version
}
