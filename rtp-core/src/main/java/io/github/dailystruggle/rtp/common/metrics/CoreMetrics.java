package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Platform-neutral {@link io.github.dailystruggle.metrics.api.Metrics} implementation (ADR-053).
 * Aggregates core telemetry (queue depth, memory tracker, heap, pipeline histogram)
 * with platform-provided {@link io.github.dailystruggle.metrics.api.MetricsBinding}.
 */
public final class CoreMetrics implements io.github.dailystruggle.metrics.api.Metrics {

    /** Constructs a CoreMetrics instance with the NOOP binding. */
    public CoreMetrics() {}

    private final PipelineHistogram pipelineHistogram = new PipelineHistogram();
    private final MetricsSnapshotRing snapshotRing = new MetricsSnapshotRing();
    private volatile io.github.dailystruggle.metrics.api.MetricsBinding binding =
            io.github.dailystruggle.metrics.api.MetricsBinding.NOOP;

    // ADR-053 section 2a: cumulative count of slow immediate/unqueued teleports (REQ-RTP-OBS-005).
    private final AtomicLong slowPipelineCount = new AtomicLong(0L);
    // ADR-053 section 2b: cumulative count of edge-triggered queue-growth warnings (REQ-RTP-OBS-006).
    private final AtomicLong queueGrowthWarnCount = new AtomicLong(0L);
    // Edge state for the queue-growth audit: true while the last observed depth was at-or-above
    // the threshold. The WARN fires only on the false->true transition and re-arms on true->false.
    private final AtomicBoolean queueGrowthArmed = new AtomicBoolean(false);

    /**
     * Replaces the platform binding. Safe to call from any thread; the new binding takes
     * effect on the next {@link #snapshot()} call.
     *
     * @param binding the new binding; {@code null} resets to {@link io.github.dailystruggle.metrics.api.MetricsBinding#NOOP}
     */
    public void setBinding(io.github.dailystruggle.metrics.api.MetricsBinding binding) {
        this.binding = (binding == null) ? io.github.dailystruggle.metrics.api.MetricsBinding.NOOP : binding;
        // Mirror into the cross-plugin static registry so sibling plugins
        // observing Metrics.currentBinding() see the live RTP binding without owning this CoreMetrics instance.
        io.github.dailystruggle.metrics.api.Metrics.registerBinding(this.binding);
    }

    /**
     * Returns the currently installed binding.
     *
     * @return the active binding; never {@code null}
     */
    public io.github.dailystruggle.metrics.api.MetricsBinding getBinding() {
        return binding;
    }

    /**
     * Returns the pipeline histogram for recording teleport latencies.
     *
     * @return the pipeline histogram; never {@code null}
     */
    public PipelineHistogram pipelineHistogram() {
        return pipelineHistogram;
    }

    /**
     * Resolved slow-teleport audit threshold (ms) from {@code performance.yml >
     * slowPipelineThresholdMs} (default {@code 5000}). A value {@code <= 0} disables the
     * audit. Never throws; falls back to the default if the parser is unavailable.
     *
     * @return the threshold in milliseconds; {@code <= 0} means disabled
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
     *
     * @return the threshold as a player count; {@code <= 0} means disabled
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

    /**
     * Returns the cumulative count of slow immediate/unqueued teleports audited (ADR-053 section 2a).
     *
     * @return cumulative slow-teleport count
     */
    public long slowPipelineCount() {
        return slowPipelineCount.get();
    }

    /**
     * Returns the cumulative count of edge-triggered queue-growth warnings (ADR-053 section 2b).
     *
     * @return cumulative queue-growth warning count
     */
    public long queueGrowthWarnCount() {
        return queueGrowthWarnCount.get();
    }

    /**
     * Audits an unqueued teleport latency against {@code slowPipelineThresholdMs} (ADR-053, REQ-RTP-OBS-005).
     *
     * @param elapsedMs recorded pipeline latency in ms
     * @param context   identifier for logging
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
     * Evaluates the edge-triggered queue-growth audit (ADR-053 section 2b, REQ-RTP-OBS-006) for the
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
     *
     * @return the snapshot ring; never {@code null}
     */
    public MetricsSnapshotRing snapshotRing() {
        return snapshotRing;
    }

    /**
     * Samples the current runtime state and returns a snapshot.
     *
     * @return a fresh {@link io.github.dailystruggle.metrics.api.MetricsSnapshot}; never {@code null}
     */
    @Override
    public io.github.dailystruggle.metrics.api.MetricsSnapshot snapshot() {
        io.github.dailystruggle.metrics.api.MetricsBinding b = this.binding;
        int queueDepth = computeQueueDepth();
        int memoryTrackerEntries = MemoryTracker.trackedCount();
        int pendingTeleports = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");

        double avgPipelineMs = pipelineHistogram.mean();
        int chunkLoadBacklog = b.chunkLoadBacklog();
        int databaseLatencyMs = b.databaseLatencyMs();

        // ADR-053 section 2b: edge-triggered queue-growth audit rides the snapshot cadence.
        evaluateQueueGrowth(queueDepth);
        long slowCount = slowPipelineCount.get();
        long slowThreshold = slowPipelineThresholdMs();
        long queueGrowthCount = queueGrowthWarnCount.get();
        int queueGrowthThreshold = queueGrowthWarnThreshold();
        java.util.Map<String, io.github.dailystruggle.metrics.api.RegionQueueRow> regionQueueStatus =
                computeRegionQueueStatus();

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
        // Attach RTP-specific extension carrying RTP metric values. Sibling plugins
        // consuming metrics-api read host-runtime fields directly; RTP-specific
        // counters live on this extension (see metrics-api-ADR-001).
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
                queueGrowthThreshold,
                regionQueueStatus
        ));
        // Compose sibling-plugin extensions registered via Metrics.registerExtension(...).
        // Suppliers are evaluated in registration order; nulls are skipped; failures are swallowed.
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

    private static java.util.Map<String, io.github.dailystruggle.metrics.api.RegionQueueRow> computeRegionQueueStatus() {
        java.util.Map<String, io.github.dailystruggle.metrics.api.RegionQueueRow> result = new java.util.LinkedHashMap<>();
        RTP rtp = RTP.getInstance();
        if (rtp == null || RTP.selectionAPI == null) return result;

        try {
            java.util.Map<String, Region> allRegions = new java.util.LinkedHashMap<>();
            if (RTP.selectionAPI.permRegionLookup != null) {
                allRegions.putAll(RTP.selectionAPI.permRegionLookup);
            }
            if (RTP.selectionAPI.tempRegions != null) {
                for (java.util.Map.Entry<java.util.UUID, Region> e : RTP.selectionAPI.tempRegions.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        allRegions.putIfAbsent(e.getKey().toString(), e.getValue());
                    }
                }
            }

            int count = 0;
            int overflowQueue = 0;
            int overflowKept = 0;
            int overflowKeptCap = 0;
            int overflowUnkept = 0;
            int overflowUnkeptCap = 0;
            io.github.dailystruggle.metrics.api.RegionQueueStatus worstStatus = io.github.dailystruggle.metrics.api.RegionQueueStatus.OK;

            for (java.util.Map.Entry<String, Region> entry : allRegions.entrySet()) {
                Region region = entry.getValue();
                if (region == null) continue;
                String name = entry.getKey();
                RegionQueueManager qm = region.queueManager;
                io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings = region.getSettings();

                int qDepth = (qm != null) ? qm.playerQueue.size() : 0;
                int kept = (qm != null && qm.keptLocations != null) ? qm.keptLocations.size() : 0;
                int keptCap = (settings != null) ? settings.activeChunkCap() : 0;
                int unkept = (qm != null && qm.unkeptLocations != null) ? qm.unkeptLocations.size() : 0;
                int unkeptCap = (settings != null) ? (int) settings.cacheCap() : 0;

                Integer login = (qm != null && qm.loginLocations != null) ? qm.loginLocations.size() : null;
                Integer loginCap = (login != null && qm != null && qm.loginLocations != null)
                        ? qm.loginLocations.capacity() : null;

                io.github.dailystruggle.metrics.api.RegionQueueStatus status =
                        io.github.dailystruggle.metrics.api.RegionQueueStatus.derive(qDepth, kept, keptCap, unkept);

                if (count >= 64) {
                    // Cardinality bound per METRICS_PLAN.md: summarize surplus under __overflow__
                    overflowQueue += qDepth;
                    overflowKept += kept;
                    overflowKeptCap += keptCap;
                    overflowUnkept += unkept;
                    overflowUnkeptCap += unkeptCap;
                    if (status.ordinal() > worstStatus.ordinal()) {
                        worstStatus = status;
                    }
                } else {
                    java.util.Map<String, Integer> stageOccupancy = new java.util.LinkedHashMap<>();
                    stageOccupancy.put("hot", kept);
                    stageOccupancy.put("cold", unkept);
                    if (qm != null && qm.backlogLocations != null) {
                        stageOccupancy.put("backlog", qm.backlogLocations.size());
                    }
                    if (login != null) {
                        stageOccupancy.put("login", login);
                    }
                    if (qm != null && qm.networkKeptLocations != null) {
                        stageOccupancy.put("network", qm.networkKeptLocations.size());
                    }

                    result.put(name, new io.github.dailystruggle.metrics.api.RegionQueueRow(
                            qDepth,
                            kept,
                            keptCap,
                            unkept,
                            unkeptCap,
                            login,
                            loginCap,
                            status,
                            stageOccupancy,
                            0
                    ));
                    count++;
                }
            }

            if (count >= 64 && (overflowQueue > 0 || overflowKept > 0 || overflowUnkept > 0)) {
                result.put("__overflow__", new io.github.dailystruggle.metrics.api.RegionQueueRow(
                        overflowQueue,
                        overflowKept,
                        overflowKeptCap,
                        overflowUnkept,
                        overflowUnkeptCap,
                        null,
                        null,
                        worstStatus,
                        java.util.Collections.emptyMap(),
                        0
                ));
            }
        } catch (Throwable ignored) {
            // Defensive: snapshot() must never throw.
        }

        return result;
    }
}
