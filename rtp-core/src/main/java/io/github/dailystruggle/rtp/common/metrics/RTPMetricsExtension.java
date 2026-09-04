package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.RegionQueueRow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RTP-specific extension payload attached to {@link io.github.dailystruggle.metrics.api.MetricsSnapshot}.
 * Carries plugin-specific pipeline, queue, memory, and latency metrics. Immutable.
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
    public final Map<String, RegionQueueRow> regionQueueStatus;

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
                avgPipelineMs, databaseLatencyMs, 0L, 0L, 0L, 0, Collections.emptyMap());
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
        this(queueDepth, pendingTeleports, memoryTrackerEntries, chunkLoadBacklog,
                avgPipelineMs, databaseLatencyMs, slowPipelineCount, slowPipelineThresholdMs,
                queueGrowthWarnCount, queueGrowthWarnThreshold, Collections.emptyMap());
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
            int queueGrowthWarnThreshold,
            Map<String, RegionQueueRow> regionQueueStatus) {
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
        this.regionQueueStatus = (regionQueueStatus == null || regionQueueStatus.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(regionQueueStatus));
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
                + ", regionQueueStatus=" + regionQueueStatus
                + '}';
    }
}
