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
 *   <li>{@code slowPipelineCount} - cumulative count of immediate/unqueued teleports whose
 *       latency exceeded {@code slowPipelineThresholdMs} (ADR-053 §2a, REQ-RTP-OBS-005).</li>
 *   <li>{@code slowPipelineThresholdMs} - resolved slow-teleport audit threshold in ms;
 *       {@code <= 0} disables the audit.</li>
 *   <li>{@code queueGrowthWarnCount} - cumulative count of edge-triggered queue-backpressure
 *       warnings (ADR-053 §2b, REQ-RTP-OBS-006).</li>
 *   <li>{@code queueGrowthWarnThreshold} - resolved queue-growth audit threshold (players);
 *       {@code <= 0} disables the audit.</li>
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
    public final long slowPipelineCount;
    public final long slowPipelineThresholdMs;
    public final long queueGrowthWarnCount;
    public final int queueGrowthWarnThreshold;

    /**
     * Backward-compatible constructor that leaves the ADR-053 audit counters at zero.
     * Retained so existing call sites (fallback constructions, tests) keep compiling.
     */
    public RTPMetricsExtension(
            int queueDepth,
            int pendingTeleports,
            int memoryTrackerEntries,
            int chunkLoadBacklog,
            double avgPipelineMs,
            int databaseLatencyMs) {
        this(queueDepth, pendingTeleports, memoryTrackerEntries, chunkLoadBacklog,
                avgPipelineMs, databaseLatencyMs, 0L, 0L, 0L, 0);
    }

    public RTPMetricsExtension(
            int queueDepth,
            int pendingTeleports,
            int memoryTrackerEntries,
            int chunkLoadBacklog,
            double avgPipelineMs,
            int databaseLatencyMs,
            long slowPipelineCount,
            long slowPipelineThresholdMs,
            long queueGrowthWarnCount,
            int queueGrowthWarnThreshold) {
        this.queueDepth = queueDepth;
        this.pendingTeleports = pendingTeleports;
        this.memoryTrackerEntries = memoryTrackerEntries;
        this.chunkLoadBacklog = chunkLoadBacklog;
        this.avgPipelineMs = avgPipelineMs;
        this.databaseLatencyMs = databaseLatencyMs;
        this.slowPipelineCount = slowPipelineCount;
        this.slowPipelineThresholdMs = slowPipelineThresholdMs;
        this.queueGrowthWarnCount = queueGrowthWarnCount;
        this.queueGrowthWarnThreshold = queueGrowthWarnThreshold;
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
                + ", slowPipelineCount=" + slowPipelineCount
                + ", slowPipelineThresholdMs=" + slowPipelineThresholdMs
                + ", queueGrowthWarnCount=" + queueGrowthWarnCount
                + ", queueGrowthWarnThreshold=" + queueGrowthWarnThreshold
                + '}';
    }
}
