package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;

/**
 * Source of spark-derived statistic values. Top level so {@link ReflectiveSparkStats} does not
 * depend on {@link SparkMetricsBinding}, which constructs it.
 */
interface SparkStatSource {
    /** {@code true} once the spark API has been linked successfully. */
    boolean available();

    /**
     * TPS for window index {@code 0=1m, 1=5m, 2=15m}, or
     * {@link MetricsSnapshot#UNSAMPLED} ({@code NaN}) if unavailable / not
     * yet sampled.
     */
    double tps(int windowIdx);

    /** Mean MSPT (last minute), or {@link MetricsSnapshot#UNSAMPLED}. */
    double mspt();
}
