package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

/**
 * Default {@link Metrics} implementation that combines a platform-supplied
 * {@link MetricsBinding} with core-readable fields (queue depth, memory tracker,
 * heap, pipeline histogram).
 *
 * <p>This class is intentionally platform-agnostic &mdash; the only platform-specific
 * fields go through the injected {@link MetricsBinding}. No {@code org.bukkit.*} or
 * other platform imports may appear here (S-005 spirit; ArchUnit core-package guard).
 *
 * <p>Lifecycle: a single {@link CoreMetrics} instance is owned by the {@code RTP}
 * facade. Platform adapters install their {@link MetricsBinding} via
 * {@link #setBinding(MetricsBinding)} during plugin/mod startup; the default is
 * {@link MetricsBinding#NOOP}.
 *
 * <p>Per {@code METRICS_PLAN.md > Goals}: snapshot-not-stream, no tick-thread blocking.
 * All reads here are O(R) where R is the count of configured regions.
 */
public final class CoreMetrics implements Metrics {

    private final PipelineHistogram pipelineHistogram = new PipelineHistogram();
    private volatile MetricsBinding binding = MetricsBinding.NOOP;

    /**
     * Replaces the platform binding. Safe to call from any thread; the new binding takes
     * effect on the next {@link #snapshot()} call.
     */
    public void setBinding(MetricsBinding binding) {
        this.binding = (binding == null) ? MetricsBinding.NOOP : binding;
    }

    public MetricsBinding getBinding() {
        return binding;
    }

    public PipelineHistogram pipelineHistogram() {
        return pipelineHistogram;
    }

    @Override
    public MetricsSnapshot snapshot() {
        MetricsBinding b = this.binding;
        int queueDepth = computeQueueDepth();
        int memoryTrackerEntries = MemoryTracker.trackedCount();
        // Phase M0: best-effort attribution. TeleportPipelineTask wrapping in M1
        // will yield a more accurate count via a dedicated label.
        int pendingTeleports = MemoryTracker.trackedCountByLabel("TeleportPipelineTask");

        return new MetricsSnapshot(
                b.tps1m(),
                b.tps5m(),
                b.tps15m(),
                b.mspt(),
                b.playerCount(),
                b.softCap(),
                HeapSampler.heapUsedBytes(),
                HeapSampler.heapMaxBytes(),
                queueDepth,
                pendingTeleports,
                memoryTrackerEntries,
                b.chunkLoadBacklog(),
                pipelineHistogram.mean(),
                b.databaseLatencyMs(),
                System.currentTimeMillis()
        );
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
