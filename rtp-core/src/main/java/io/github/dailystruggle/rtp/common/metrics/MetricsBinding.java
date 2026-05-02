package io.github.dailystruggle.rtp.common.metrics;

/**
 * Platform-supplied source for metric fields that {@code rtp-core} cannot derive on its
 * own (TPS / MSPT / player count / soft cap / chunk-load backlog / database latency).
 *
 * <p>Each platform adapter ({@code rtp-spigot}, {@code rtp-paper}, {@code rtp-folia},
 * {@code rtp-fabric}) implements this interface and registers it via
 * {@code RTP.serverAccessor.getMetricsBinding()} (added in Phase M1 wiring per
 * {@code METRICS_PLAN.md > Module Placement}). The {@link CoreMetrics} aggregator
 * combines the binding's contribution with core-readable fields (queue depth, memory
 * tracker, heap, pipeline histogram).
 *
 * <p>Implementations <strong>shall not</strong> block the calling thread. If a value is
 * not yet sampled, return the documented sentinel ({@link MetricsSnapshot#UNSAMPLED} for
 * doubles, {@code 0} for counts, {@code -1} for {@code databaseLatencyMs}).
 *
 * <p>The {@link #NOOP} binding is the default and is what {@link Metrics#NOOP} returns
 * when no platform is registered.
 */
public interface MetricsBinding {

    /** Per {@link MetricsSnapshot#tps1m}. */
    default double tps1m() { return MetricsSnapshot.UNSAMPLED; }

    /** Per {@link MetricsSnapshot#tps5m}. */
    default double tps5m() { return MetricsSnapshot.UNSAMPLED; }

    /** Per {@link MetricsSnapshot#tps15m}. */
    default double tps15m() { return MetricsSnapshot.UNSAMPLED; }

    /** Per {@link MetricsSnapshot#mspt}. */
    default double mspt() { return MetricsSnapshot.UNSAMPLED; }

    /** Per {@link MetricsSnapshot#playerCount}. */
    default int playerCount() { return 0; }

    /** Per {@link MetricsSnapshot#softCap}. */
    default int softCap() { return 0; }

    /** Per {@link MetricsSnapshot#chunkLoadBacklog}. */
    default int chunkLoadBacklog() { return 0; }

    /** Per {@link MetricsSnapshot#databaseLatencyMs}. */
    default int databaseLatencyMs() { return -1; }

    /** Default no-op binding; returns documented sentinels for every field. */
    MetricsBinding NOOP = new MetricsBinding() {};
}
