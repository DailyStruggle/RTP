package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.List;

/**
 * {@link MetricsBinding} that sources TPS / MSPT from the
 * <a href="https://spark.lucko.me/">spark</a> profiler when it is installed,
 * merging those richer values over a native {@code delegate} binding for every
 * other field. spark exposes per-window TPS and a mean-MSPT statistic that are
 * consistent across Paper / Spigot / Folia and more accurate than the coarse
 * {@code Server#getTPS()} / {@code Server#getAverageTickTime()} the native
 * bindings read.
 *
 * <p><b>Soft-dependency.</b> spark is never required. The binding probes the
 * spark API once at construction; if the API classes are absent
 * ({@link ClassNotFoundException} / {@link NoClassDefFoundError} - including
 * trimmed assemblies such as the rtp-lite jar) it enters a permanent
 * <em>disabled</em> state and every field simply delegates. The
 * {@code softdepend: [spark]} entry in {@code plugin.yml} only constrains load
 * order so spark is enabled before RTP; it does not make spark mandatory.
 *
 * <p><b>Per-field merge (approved design).</b> Only {@link #tps1m()} /
 * {@link #tps5m()} / {@link #tps15m()} / {@link #mspt()} are taken from spark.
 * {@link #playerCount()}, {@link #softCap()}, {@link #chunkLoadBacklog()},
 * {@link #databaseLatencyMs()}, and {@link #foliaRegions()} always delegate -
 * spark does not own those. When spark is present but a statistic is not yet
 * sampled (or {@code SparkProvider.get()} transiently throws before spark
 * finishes init), the affected getter returns the delegate's value instead, so
 * the merge self-heals once spark warms up.
 *
 * <p><b>Threading.</b> Every getter is non-blocking and never throws on the
 * calling thread (binding contract). All reflective access is wrapped so a
 * spark-side failure degrades to the delegate / sentinel rather than
 * propagating.
 *
 * <p><b>Folia.</b> This binding <em>is</em> applied on Folia, wrapping the
 * native {@code FoliaMetricsBinding} delegate. spark supplies the scalar
 * TPS/MSPT (Folia's native per-region sampler can only derive MSPT from the
 * inter-tick interval, not real per-tick processing time), while
 * {@link #foliaRegions()} delegates to the Folia binding so per-region
 * samples are preserved.
 *
 * <p>Testable without spark on the classpath via the package-private
 * {@link #SparkMetricsBinding(MetricsBinding, SparkStats)} constructor.
 */
public final class SparkMetricsBinding implements MetricsBinding {

    /** Source of spark-derived statistic values. */
    interface SparkStats {
        /** {@code true} once the spark API has been linked successfully. */
        boolean available();

        /**
         * TPS for window index {@code 0=1m, 1=5m, 2=15m}, or
         * {@link MetricsSnapshot#UNSAMPLED} ({@code NaN}) if unavailable / not
         * yet sampled.
         */
        double tps(int windowIdx);

        /** Mean MSPT (last minute), or {@link MetricsSnapshot#UNSAMPLED}. */
        double mspt();
    }

    /**
     * Collects engine telemetry dataset for spark metadata serialization.
     * Guaranteed never to throw and safely degrades if core or subcomponents
     * are uninitialized.
     *
     * @return structured map of telemetry key-value pairs
     */
    public static java.util.Map<String, Object> collectTelemetry() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        try {
            // MemoryTracker State
            map.put("rtp.memory.tracked_entries_total", io.github.dailystruggle.rtp.common.tools.MemoryTracker.trackedCount());
            map.put("rtp.memory.active_tasks", io.github.dailystruggle.rtp.common.tools.MemoryTracker.activeTasks());
            map.put("rtp.memory.active_chunk_tickets", io.github.dailystruggle.rtp.common.tools.MemoryTracker.activeTickets());
            map.put("rtp.memory.ceiling_bytes", io.github.dailystruggle.rtp.common.tools.MemoryTracker.getMemoryCeiling());

            // Queue & Cache State
            long l1Ready = 0;
            long l2Cold = 0;
            long l3BacklogBins = 0;

            try {
                if (io.github.dailystruggle.rtp.common.RTP.selectionAPI != null) {
                    java.util.Map<String, io.github.dailystruggle.rtp.common.selection.region.Region> perm =
                            io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup;
                    if (perm != null) {
                        for (io.github.dailystruggle.rtp.common.selection.region.Region r : perm.values()) {
                            if (r != null && r.queueManager != null) {
                                if (r.queueManager.keptLocations != null) {
                                    l1Ready += r.queueManager.keptLocations.size();
                                }
                                if (r.queueManager.unkeptLocations != null) {
                                    l2Cold += r.queueManager.unkeptLocations.size();
                                }
                                if (r.queueManager.backlogLocations != null) {
                                    l3BacklogBins += Math.max(0, r.queueManager.backlogLocations.size()
                                            - r.queueManager.backlogLocations.validatedSize()
                                            - r.queueManager.backlogLocations.invalidatedSize());
                                }
                            }
                        }
                    }

                    java.util.Map<java.util.UUID, io.github.dailystruggle.rtp.common.selection.region.Region> temp =
                            io.github.dailystruggle.rtp.common.RTP.selectionAPI.tempRegions;
                    if (temp != null) {
                        for (io.github.dailystruggle.rtp.common.selection.region.Region r : temp.values()) {
                            if (r != null && r.queueManager != null) {
                                if (r.queueManager.keptLocations != null) {
                                    l1Ready += r.queueManager.keptLocations.size();
                                }
                                if (r.queueManager.unkeptLocations != null) {
                                    l2Cold += r.queueManager.unkeptLocations.size();
                                }
                                if (r.queueManager.backlogLocations != null) {
                                    l3BacklogBins += Math.max(0, r.queueManager.backlogLocations.size()
                                            - r.queueManager.backlogLocations.validatedSize()
                                            - r.queueManager.backlogLocations.invalidatedSize());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            map.put("rtp.queue.l1_ready_count", l1Ready);
            map.put("rtp.queue.l2_cold_count", l2Cold);
            map.put("rtp.queue.l3_backlog_bins", l3BacklogBins);

            // Teleport Pipeline
            long pendingTeleports = 0;
            double avgLatency = 0.0;
            long slowCount = 0;

            try {
                pendingTeleports = io.github.dailystruggle.rtp.common.tools.MemoryTracker.trackedCountByLabel("TeleportPipelineTask");
            } catch (Throwable ignored) {
            }

            try {
                if (io.github.dailystruggle.rtp.common.RTP.metrics instanceof io.github.dailystruggle.rtp.common.metrics.CoreMetrics) {
                    io.github.dailystruggle.rtp.common.metrics.CoreMetrics cm =
                            (io.github.dailystruggle.rtp.common.metrics.CoreMetrics) io.github.dailystruggle.rtp.common.RTP.metrics;
                    avgLatency = cm.pipelineHistogram().mean();
                    slowCount = cm.slowPipelineCount();
                }
            } catch (Throwable ignored) {
            }

            map.put("rtp.pipeline.pending_teleports", pendingTeleports);
            map.put("rtp.pipeline.avg_latency_ms", avgLatency);
            map.put("rtp.pipeline.slow_count", slowCount);
        } catch (Throwable ignored) {
        }
        return map;
    }

    private final MetricsBinding delegate;
    private final SparkStats spark;

    /** Production constructor: builds a reflective spark accessor. */
    public SparkMetricsBinding(MetricsBinding delegate) {
        this(delegate, new ReflectiveSparkStats());
    }

    /** Test seam. */
    SparkMetricsBinding(MetricsBinding delegate, SparkStats spark) {
        this.delegate = (delegate == null) ? MetricsBinding.NOOP : delegate;
        this.spark = spark;
    }

    /** Visible for diagnostics / tests. */
    boolean sparkEnabled() {
        return spark.available();
    }

    private double sparkTpsOr(int windowIdx, double fallback) {
        if (!spark.available()) return fallback;
        double v = spark.tps(windowIdx);
        return Double.isNaN(v) ? fallback : v;
    }

    @Override
    public double tps1m() {
        return sparkTpsOr(0, delegate.tps1m());
    }

    @Override
    public double tps5m() {
        return sparkTpsOr(1, delegate.tps5m());
    }

    @Override
    public double tps15m() {
        return sparkTpsOr(2, delegate.tps15m());
    }

    @Override
    public double mspt() {
        double fallback = delegate.mspt();
        if (!spark.available()) return fallback;
        double v = spark.mspt();
        return Double.isNaN(v) ? fallback : v;
    }

    @Override
    public int playerCount() {
        return delegate.playerCount();
    }

    @Override
    public int softCap() {
        return delegate.softCap();
    }

    @Override
    public int chunkLoadBacklog() {
        return delegate.chunkLoadBacklog();
    }

    @Override
    public int databaseLatencyMs() {
        return delegate.databaseLatencyMs();
    }

    @Override
    public List<FoliaRegionSample> foliaRegions() {
        return delegate.foliaRegions();
    }
}
