package io.github.dailystruggle.rtp.common.metrics;

/**
 * RTP-specific extension payload attached to every
 * {@link io.github.dailystruggle.metrics.api.MetricsSnapshot}. Carries the counters
 * that are meaningful only to the random-teleport plugin itself and that have no
 * analogue on sibling plugins consuming the {@code metrics-api} module.
 *
 * <p>Authoritative accessor path for RTP-specific health signals: callers read these
 * via {@code snapshot.extension(RTPMetricsExtension.class)}. The host
 * {@code MetricsSnapshot} carries only runtime fields shared across all plugins.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code queueDepth} - aggregate {@code playerQueue} size across all configured regions.</li>
 *   <li>{@code pendingTeleports} - count of in-flight {@code TeleportPipelineTask} entries.</li>
 *   <li>{@code memoryTrackerEntries} - total {@code MemoryTracker} entries (all labels).</li>
 *   <li>{@code chunkLoadBacklog} - platform-reported chunk-load backlog.</li>
 *   <li>{@code avgPipelineMs} - rolling pipeline-stage mean in ms, or {@link Double#NaN} if unsampled.</li>
 *   <li>{@code databaseLatencyMs} - last DB round-trip in ms, or {@code -1} if unsampled.</li>
 * </ul>
 *
 * <p>Immutable. No platform types.
 */
public final class RTPMetricsExtension implements io.github.dailystruggle.metrics.api.MetricsExtension<RTPMetricsExtension> {

    public final int queueDepth;
    public final int pendingTeleports;
    public final int memoryTrackerEntries;
    public final int chunkLoadBacklog;
    public final double avgPipelineMs;
    public final int databaseLatencyMs;

    public RTPMetricsExtension(
            int queueDepth,
            int pendingTeleports,
            int memoryTrackerEntries,
            int chunkLoadBacklog,
            double avgPipelineMs,
            int databaseLatencyMs) {
        this.queueDepth = queueDepth;
        this.pendingTeleports = pendingTeleports;
        this.memoryTrackerEntries = memoryTrackerEntries;
        this.chunkLoadBacklog = chunkLoadBacklog;
        this.avgPipelineMs = avgPipelineMs;
        this.databaseLatencyMs = databaseLatencyMs;
    }

    @Override
    public String toString() {
        return "RTPMetricsExtension{"
                + "queueDepth=" + queueDepth
                + ", pendingTeleports=" + pendingTeleports
                + ", memoryTrackerEntries=" + memoryTrackerEntries
                + ", chunkLoadBacklog=" + chunkLoadBacklog
                + ", avgPipelineMs=" + avgPipelineMs
                + ", databaseLatencyMs=" + databaseLatencyMs
                + '}';
    }
}
