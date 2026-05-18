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
public final class CoreMetrics implements io.github.dailystruggle.metrics.api.Metrics {

    private final PipelineHistogram pipelineHistogram = new PipelineHistogram();
    private volatile io.github.dailystruggle.metrics.api.MetricsBinding binding =
            io.github.dailystruggle.metrics.api.MetricsBinding.NOOP;

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
        // counters live on this extension. See PROPOSAL-metrics-api-extraction.md §1.1.
        snap = snap.withExtension(new RTPMetricsExtension(
                queueDepth,
                pendingTeleports,
                memoryTrackerEntries,
                chunkLoadBacklog,
                avgPipelineMs,
                databaseLatencyMs
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
