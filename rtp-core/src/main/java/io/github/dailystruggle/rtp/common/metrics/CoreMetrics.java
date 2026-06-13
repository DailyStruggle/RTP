package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Default {@link io.github.dailystruggle.metrics.api.Metrics} implementation that combines a platform-supplied
 * {@link io.github.dailystruggle.metrics.api.MetricsBinding} with core-readable fields (queue depth, memory tracker,
 * heap, pipeline histogram).
 *
 * <p>This class is intentionally platform-agnostic &mdash; the only platform-specific
 * fields go through the injected {@link io.github.dailystruggle.metrics.api.MetricsBinding}. No {@code org.bukkit.*} or
 * other platform imports may appear here (S-005 spirit; ArchUnit core-package guard).
 *
 * <p>Lifecycle: a single {@link CoreMetrics} instance is owned by the {@code RTP}
 * facade. Platform adapters install their {@link io.github.dailystruggle.metrics.api.MetricsBinding} via
 * {@link #setBinding(io.github.dailystruggle.metrics.api.MetricsBinding)} during plugin/mod startup; the default is
 * {@link io.github.dailystruggle.metrics.api.MetricsBinding#NOOP}.
 *
 * <p>Per {@code METRICS_PLAN.md > Goals}: snapshot-not-stream, no tick-thread blocking.
 * All reads here are O(R) where R is the count of configured regions.
 */
public final class CoreMetrics implements io.github.dailystruggle.metrics.api.Metrics {

    private final PipelineHistogram pipelineHistogram = new PipelineHistogram();
    private final MetricsSnapshotRing snapshotRing = new MetricsSnapshotRing();
    private volatile io.github.dailystruggle.metrics.api.MetricsBinding binding =
            io.github.dailystruggle.metrics.api.MetricsBinding.NOOP;

    // ADR-053 §2a: cumulative count of slow immediate/unqueued teleports (REQ-RTP-OBS-005).
    private final AtomicLong slowPipelineCount = new AtomicLong(0L);
    // ADR-053 §2b: cumulative count of edge-triggered queue-growth warnings (REQ-RTP-OBS-006).
    private final AtomicLong queueGrowthWarnCount = new AtomicLong(0L);
    // Edge state for the queue-growth audit: true while the last observed depth was at-or-above
    // the threshold. The WARN fires only on the false->true transition and re-arms on true->false.
    private final AtomicBoolean queueGrowthArmed = new AtomicBoolean(false);

    /**
     * Replaces the platform binding. Safe to call from any thread; the new binding takes
     * effect on the next {@link #snapshot()} call.
     */
    public void setBinding(io.github.dailystruggle.metrics.api.MetricsBinding binding) {
        this.binding = (binding == null) ? io.github.dailystruggle.metrics.api.MetricsBinding.NOOP : binding;
        // Phase C (metrics-api §1.1): mirror into the cross-plugin static registry
        // so sibling plugins observing Metrics.currentBinding() see the live RTP
        // binding without owning this CoreMetrics instance.
        io.github.dailystruggle.metrics.api.Metrics.registerBinding(this.binding);
    }

    public io.github.dailystruggle.metrics.api.MetricsBinding getBinding() {
        return binding;
    }

    public PipelineHistogram pipelineHistogram() {
        return pipelineHistogram;
    }

    /**
     * Resolved slow-teleport audit threshold (ms) from {@code performance.yml >
     * slowPipelineThresholdMs} (default {@code 5000}). A value {@code <= 0} disables the
     * audit. Never throws; falls back to the default if the parser is unavailable.
     */
    public long slowPipelineThresholdMs() {
        try {
            ConfigParser<PerformanceKeys> perf =
                    (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            if (perf == null) return 5000L;
            return perf.getNumber(PerformanceKeys.slowPipelineThresholdMs, 5000L).longValue();
        } catch (Throwable ignored) {
            return 5000L;
        }
    }

    /**
     * Resolved queue-growth audit threshold (player count) from {@code performance.yml >
     * queueGrowthWarnThreshold} (default {@code 0} = disabled). A value {@code <= 0} disables
     * the audit. Never throws.
     */
    public int queueGrowthWarnThreshold() {
        try {
            ConfigParser<PerformanceKeys> perf =
                    (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            if (perf == null) return 0;
            return perf.getNumber(PerformanceKeys.queueGrowthWarnThreshold, 0L).intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Cumulative count of slow immediate/unqueued teleports audited (ADR-053 §2a). */
    public long slowPipelineCount() {
        return slowPipelineCount.get();
    }

    /** Cumulative count of edge-triggered queue-growth warnings (ADR-053 §2b). */
    public long queueGrowthWarnCount() {
        return queueGrowthWarnCount.get();
    }

    /**
     * Audits a completed <em>immediate/unqueued</em> teleport (ADR-053 §2a, REQ-RTP-OBS-005).
     * Queued at-rate teleports must not call this method &mdash; their elapsed window includes
     * queue-wait time and would false-positive. When the audit is enabled
     * ({@code slowPipelineThresholdMs > 0}) and {@code elapsedMs} exceeds it, increments the
     * cumulative slow-teleport counter and emits a {@code WARN} log. Never throws.
     *
     * @param elapsedMs the recorded pipeline latency in milliseconds
     * @param context   short human-readable identifier (player/region) for the log line
     */
    public void auditImmediateTeleport(long elapsedMs, String context) {
        try {
            long threshold = slowPipelineThresholdMs();
            if (threshold > 0L && elapsedMs > threshold) {
                long total = slowPipelineCount.incrementAndGet();
                RTP.log(Level.WARNING,
                        "[RTP] slow teleport: " + elapsedMs + "ms exceeded slowPipelineThresholdMs="
                                + threshold + "ms (" + context + "); slowPipelineCount=" + total);
            }
        } catch (Throwable ignored) {
            // Audit must never interfere with teleport teardown (S-004 posture).
        }
    }

    /**
     * Evaluates the edge-triggered queue-growth audit (ADR-053 §2b, REQ-RTP-OBS-006) for the
     * observed {@code queueDepth}. Fires a single {@code WARN} (and increments the counter) on
     * the transition from below-threshold to at-or-above-threshold, re-arming only after the
     * depth drops back below. Never throws.
     */
    private void evaluateQueueGrowth(int queueDepth) {
        try {
            int threshold = queueGrowthWarnThreshold();
            if (threshold <= 0) {
                queueGrowthArmed.set(false);
                return;
            }
            boolean above = queueDepth >= threshold;
            if (above) {
                if (queueGrowthArmed.compareAndSet(false, true)) {
                    long total = queueGrowthWarnCount.incrementAndGet();
                    RTP.log(Level.WARNING,
                            "[RTP] teleport queue growth: depth=" + queueDepth
                                    + " reached queueGrowthWarnThreshold=" + threshold
                                    + "; queueGrowthWarnCount=" + total);
                }
            } else {
                queueGrowthArmed.set(false);
            }
        } catch (Throwable ignored) {
            // Audit must never poison snapshot().
        }
    }

    /**
     * Returns the rolling MSPT+heap ring populated by the 1 Hz sampler
     * installed in {@code RTP.start}. Drives the {@code METRIC_SPARKLINE}
     * chart kind.
     */
    public MetricsSnapshotRing snapshotRing() {
        return snapshotRing;
    }

    @Override
    public io.github.dailystruggle.metrics.api.MetricsSnapshot snapshot() {
        io.github.dailystruggle.metrics.api.MetricsBinding b = this.binding;
        int queueDepth = computeQueueDepth();
        int memoryTrackerEntries = MemoryTracker.trackedCount();
        // Phase M0: best-effort attribution. TeleportPipelineTask wrapping in M1
        // will yield a more accurate count via a dedicated label.
        int pendingTeleports = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");

        double avgPipelineMs = pipelineHistogram.mean();
        int chunkLoadBacklog = b.chunkLoadBacklog();
        int databaseLatencyMs = b.databaseLatencyMs();

        // ADR-053 §2b: edge-triggered queue-growth audit rides the snapshot cadence.
        evaluateQueueGrowth(queueDepth);
        long slowCount = slowPipelineCount.get();
        long slowThreshold = slowPipelineThresholdMs();
        long queueGrowthCount = queueGrowthWarnCount.get();
        int queueGrowthThreshold = queueGrowthWarnThreshold();

        io.github.dailystruggle.metrics.api.MetricsSnapshot snap = new io.github.dailystruggle.metrics.api.MetricsSnapshot(
                b.tps1m(),
                b.tps5m(),
                b.tps15m(),
                b.mspt(),
                b.playerCount(),
                b.softCap(),
                HeapSampler.heapUsedBytes(),
                HeapSampler.heapMaxBytes(),
                System.currentTimeMillis(),
                b.foliaRegions()
        );
        // Phase B: attach RTP-specific extension carrying the same values as the
        // legacy public-final fields. Sibling plugins consuming the future
        // metrics-api module read host-runtime fields directly; RTP-specific
        // counters live on this extension. See metrics-api-ADR-001 (docs/adr/).
        snap = snap.withExtension(new RTPMetricsExtension(
                queueDepth,
                pendingTeleports,
                memoryTrackerEntries,
                chunkLoadBacklog,
                avgPipelineMs,
                databaseLatencyMs,
                slowCount,
                slowThreshold,
                queueGrowthCount,
                queueGrowthThreshold
        ));
        // Phase C (metrics-api §1.1): compose sibling-plugin extensions registered
        // via Metrics.registerExtension(...). Suppliers are evaluated in registration
        // order; nulls are skipped; failures are swallowed so snapshot() never throws.
        for (java.util.function.Supplier<? extends io.github.dailystruggle.metrics.api.MetricsExtension<?>> sup :
                io.github.dailystruggle.metrics.api.Metrics.registeredExtensions()) {
            try {
                io.github.dailystruggle.metrics.api.MetricsExtension<?> ext = sup.get();
                if (ext != null) {
                    snap = snap.withExtension(ext);
                }
            } catch (Throwable ignored) {
                // Defensive: a misbehaving sibling extension must not poison the snapshot.
            }
        }
        return snap;
    }

    private static int computeQueueDepth() {
        RTP rtp = RTP.getInstance();
        if (rtp == null) return 0;
        int total = 0;
        try {
            for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
                if (region != null && region.queueManager != null) {
                    total += region.queueManager.playerQueue.size();
                }
            }
            for (Region region : RTP.selectionAPI.tempRegions.values()) {
                if (region != null && region.queueManager != null) {
                    total += region.queueManager.playerQueue.size();
                }
            }
        } catch (Throwable ignored) {
            // Defensive: snapshot() must never throw. Partial counts beat no snapshot.
        }
        return total;
    }
}
