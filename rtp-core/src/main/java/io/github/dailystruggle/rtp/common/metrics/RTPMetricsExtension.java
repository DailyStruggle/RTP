package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.RegionQueueRow;
import io.github.dailystruggle.rtp.common.selection.region.ImmediateGovernorRow;
import io.github.dailystruggle.rtp.common.selection.region.LiveLoadGateRow;
import io.github.dailystruggle.rtp.common.selection.region.ProbeGovernorRow;

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
    /** ADR-109 probe-first governors keyed {@code region|path}; empty until a candidate runs. */
    public final Map<String, ProbeGovernorRow> probeGovernors;
    /** ADR-110 purpose gate on native chunk loads, keyed by region; empty until fill runs. */
    public final Map<String, LiveLoadGateRow> liveLoadGates;
    /** ADR-114 on-demand chunk load governors keyed by region; empty until on-demand runs. */
    public final Map<String, ImmediateGovernorRow> immediateGovernors;

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
        this(builder()
                .queueDepth(queueDepth)
                .pendingTeleports(pendingTeleports)
                .memoryTrackerEntries(memoryTrackerEntries)
                .chunkLoadBacklog(chunkLoadBacklog)
                .avgPipelineMs(avgPipelineMs)
                .databaseLatencyMs(databaseLatencyMs));
    }

    private RTPMetricsExtension(Builder b) {
        this.queueDepth = b.queueDepth;
        this.pendingTeleports = b.pendingTeleports;
        this.memoryTrackerEntries = b.memoryTrackerEntries;
        this.chunkLoadBacklog = b.chunkLoadBacklog;
        this.avgPipelineMs = b.avgPipelineMs;
        this.databaseLatencyMs = b.databaseLatencyMs;
        this.slowPipelineCount = b.slowPipelineCount;
        this.slowPipelineThresholdMs = b.slowPipelineThresholdMs;
        this.queueGrowthWarnCount = b.queueGrowthWarnCount;
        this.queueGrowthWarnThreshold = b.queueGrowthWarnThreshold;
        this.regionQueueStatus = immutableCopy(b.regionQueueStatus);
        this.probeGovernors = immutableCopy(b.probeGovernors);
        this.liveLoadGates = immutableCopy(b.liveLoadGates);
        this.immediateGovernors = immutableCopy(b.immediateGovernors);
    }

    /** Starts an extension with every counter at zero and every table empty. */
    public static Builder builder() {
        return new Builder();
    }

    private static <V> Map<String, V> immutableCopy(Map<String, V> source) {
        return (source == null || source.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** Mutable staging for {@link RTPMetricsExtension}; {@code null} tables read as empty. */
    public static final class Builder {
        private int queueDepth;
        private int pendingTeleports;
        private int memoryTrackerEntries;
        private int chunkLoadBacklog;
        private double avgPipelineMs;
        private int databaseLatencyMs;
        private long slowPipelineCount;
        private long slowPipelineThresholdMs;
        private long queueGrowthWarnCount;
        private int queueGrowthWarnThreshold;
        private Map<String, RegionQueueRow> regionQueueStatus;
        private Map<String, ProbeGovernorRow> probeGovernors;
        private Map<String, LiveLoadGateRow> liveLoadGates;
        private Map<String, ImmediateGovernorRow> immediateGovernors;

        private Builder() {
        }

        public Builder queueDepth(int v) {
            queueDepth = v;
            return this;
        }

        public Builder pendingTeleports(int v) {
            pendingTeleports = v;
            return this;
        }

        public Builder memoryTrackerEntries(int v) {
            memoryTrackerEntries = v;
            return this;
        }

        public Builder chunkLoadBacklog(int v) {
            chunkLoadBacklog = v;
            return this;
        }

        public Builder avgPipelineMs(double v) {
            avgPipelineMs = v;
            return this;
        }

        public Builder databaseLatencyMs(int v) {
            databaseLatencyMs = v;
            return this;
        }

        /** ADR-053 slow-pipeline audit: count over threshold and the threshold itself. */
        public Builder slowPipeline(long count, long thresholdMs) {
            slowPipelineCount = count;
            slowPipelineThresholdMs = thresholdMs;
            return this;
        }

        /** ADR-053 queue-growth audit: warnings emitted and the depth that triggers one. */
        public Builder queueGrowthWarn(long count, int threshold) {
            queueGrowthWarnCount = count;
            queueGrowthWarnThreshold = threshold;
            return this;
        }

        public Builder regionQueueStatus(Map<String, RegionQueueRow> v) {
            regionQueueStatus = v;
            return this;
        }

        public Builder probeGovernors(Map<String, ProbeGovernorRow> v) {
            probeGovernors = v;
            return this;
        }

        public Builder liveLoadGates(Map<String, LiveLoadGateRow> v) {
            liveLoadGates = v;
            return this;
        }

        public Builder immediateGovernors(Map<String, ImmediateGovernorRow> v) {
            immediateGovernors = v;
            return this;
        }

        public RTPMetricsExtension build() {
            return new RTPMetricsExtension(this);
        }
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
                + ", probeGovernors=" + probeGovernors
                + ", liveLoadGates=" + liveLoadGates
                + ", immediateGovernors=" + immediateGovernors
                + '}';
    }
}
