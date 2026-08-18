package io.github.dailystruggle.rtp.common.configuration.enums;

/**
 * Configuration keys for the {@code metrics.yml} surface.
 * Reporting knobs only - throttle / tuning knobs continue to
 * live under {@code performance.yml}.
 */
public enum MetricsKeys {
  /** Whether {@code MetricsSnapshot.foliaRegions()} is populated on Folia (default: true). */
  foliaIncludeRegions,
  /** Aggregation strategy for the scalar {@code tps*m} fields when running on Folia. Accepts {@code mean} or {@code max}. */
  foliaAggregationTps,
  /** Aggregation strategy for the scalar {@code mspt} field when running on Folia. Accepts {@code mean} or {@code max}. */
  foliaAggregationMspt,
  /**
   * Aggregation strategy for scalar {@code tickBudgetUtilisation} on Folia.
   * Accepts {@code mean} or {@code max}.
   */
  foliaAggregationTickBudget,
  /** The configuration version */
  version
}
