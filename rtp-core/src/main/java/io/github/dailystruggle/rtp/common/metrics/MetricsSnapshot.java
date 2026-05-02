package io.github.dailystruggle.rtp.common.metrics;

/**
 * Immutable point-in-time snapshot of RTP runtime health signals.
 *
 * <p>The metrics SPI is documented in {@code docs/dev/METRICS_PLAN.md}. This record is the
 * single carrier consumed by {@code rtp test full}, the {@code /rtp info} health block,
 * the bStats integration, and the multi-server telemetry publisher. Callers shall treat
 * fields as scalar primitives and compute deltas themselves; the snapshot retains no
 * history.
 *
 * <p>Phase M0 (this class) ships the surface only. Platform bindings populate
 * {@link #tps1m}, {@link #tps5m}, {@link #tps15m}, and {@link #mspt} starting in Phase M1.
 * Until then a noop binding leaves them at {@link Double#NaN}.
 *
 * <p>All fields are public-final so the record stays trivially serialisable to JSON
 * (per {@code METRICS_PLAN.md > /rtp info > Verbosity}). No platform types appear here
 * (S-005 spirit / ArchUnit core-package guard).
 */
public final class MetricsSnapshot {

    /** Sentinel for &ldquo;not sampled by the active binding yet&rdquo;. Always {@link Double#NaN}. */
    public static final double UNSAMPLED = Double.NaN;

    public final double tps1m;
    public final double tps5m;
    public final double tps15m;
    public final double mspt;
    /** {@code mspt / 50.0} when {@link #mspt} is sampled, else {@link Double#NaN}. */
    public final double tickBudgetUtilisation;

    public final int playerCount;
    public final int softCap;

    public final long heapUsedBytes;
    public final long heapMaxBytes;

    public final int queueDepth;
    public final int pendingTeleports;
    public final int memoryTrackerEntries;
    public final int chunkLoadBacklog;

    /** Rolling mean of the pipeline histogram in milliseconds, or {@link Double#NaN} if no samples. */
    public final double avgPipelineMs;
    /** Last database round-trip latency in milliseconds, or {@code -1} if never sampled. */
    public final int databaseLatencyMs;

    /** Wall-clock millisecond timestamp when this snapshot was assembled. */
    public final long takenAtEpochMs;

    public MetricsSnapshot(
            double tps1m,
            double tps5m,
            double tps15m,
            double mspt,
            int playerCount,
            int softCap,
            long heapUsedBytes,
            long heapMaxBytes,
            int queueDepth,
            int pendingTeleports,
            int memoryTrackerEntries,
            int chunkLoadBacklog,
            double avgPipelineMs,
            int databaseLatencyMs,
            long takenAtEpochMs) {
        this.tps1m = tps1m;
        this.tps5m = tps5m;
        this.tps15m = tps15m;
        this.mspt = mspt;
        this.tickBudgetUtilisation = Double.isNaN(mspt) ? Double.NaN : mspt / 50.0;
        this.playerCount = playerCount;
        this.softCap = softCap;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.queueDepth = queueDepth;
        this.pendingTeleports = pendingTeleports;
        this.memoryTrackerEntries = memoryTrackerEntries;
        this.chunkLoadBacklog = chunkLoadBacklog;
        this.avgPipelineMs = avgPipelineMs;
        this.databaseLatencyMs = databaseLatencyMs;
        this.takenAtEpochMs = takenAtEpochMs;
    }

    public long heapUsedMb() {
        return heapUsedBytes / (1024L * 1024L);
    }

    public long heapMaxMb() {
        return heapMaxBytes / (1024L * 1024L);
    }

    @Override
    public String toString() {
        return "MetricsSnapshot{"
                + "tps1m=" + tps1m
                + ", tps5m=" + tps5m
                + ", tps15m=" + tps15m
                + ", mspt=" + mspt
                + ", tickBudgetUtilisation=" + tickBudgetUtilisation
                + ", playerCount=" + playerCount
                + ", softCap=" + softCap
                + ", heapUsedMb=" + heapUsedMb()
                + ", heapMaxMb=" + heapMaxMb()
                + ", queueDepth=" + queueDepth
                + ", pendingTeleports=" + pendingTeleports
                + ", memoryTrackerEntries=" + memoryTrackerEntries
                + ", chunkLoadBacklog=" + chunkLoadBacklog
                + ", avgPipelineMs=" + avgPipelineMs
                + ", databaseLatencyMs=" + databaseLatencyMs
                + ", takenAtEpochMs=" + takenAtEpochMs
                + '}';
    }
}
