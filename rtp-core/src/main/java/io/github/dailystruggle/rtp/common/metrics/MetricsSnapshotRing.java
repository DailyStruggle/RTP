package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.api.metrics.MetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Fixed-size rolling time-series of selected scalar fields from {@link MetricsSnapshot},
 * plus multi-tier bucket condensation ({@code 5m@1s, 1h@10s, 24h@1m}) that promotes completed
 * buckets to {@link MetricTimeSeriesPublisher} on tier boundary crossings (ADR-039, ADR-040,
 * REQ-RTP-METTS-002, REQ-RTP-METTS-003).
 */
public final class MetricsSnapshotRing {

    public static final int CAPACITY = 128;

    public static final String TIER_5M_1S = "5m@1s";
    public static final String TIER_1H_10S = "1h@10s";
    public static final String TIER_24H_1M = "24h@1m";

    private final AtomicLongArray msptBits = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray heapBytes = new AtomicLongArray(CAPACITY);
    private final AtomicLong writeIndex = new AtomicLong(0L);

    private volatile Supplier<String> backendIdSupplier = () -> "backend-local";
    private volatile LongSupplier generationSupplier = () -> 1L;
    private volatile Supplier<String> publishedTierSupplier =
            () -> NetworkTimeSeriesConfig.DEFAULT_TIER;
    private volatile @Nullable Consumer<MetricBucketRow> promotionListener;

    private final ConcurrentHashMap<String, Map<String, TierState>> metricTiers =
            new ConcurrentHashMap<>();

    public MetricsSnapshotRing() {
        // Default constructor
    }

    public MetricsSnapshotRing(
            String backendId,
            String publishedTier,
            long generation,
            @Nullable Consumer<MetricBucketRow> promotionListener) {
        this.backendIdSupplier = () -> (backendId == null || backendId.isBlank()) ? "backend-local" : backendId;
        this.publishedTierSupplier =
                () -> (publishedTier == null || publishedTier.isBlank())
                        ? NetworkTimeSeriesConfig.DEFAULT_TIER
                        : publishedTier.trim();
        this.generationSupplier = () -> Math.max(0L, generation);
        this.promotionListener = promotionListener;
    }

    public void configurePublication(
            Supplier<String> backendIdSupplier,
            Supplier<String> publishedTierSupplier,
            LongSupplier generationSupplier,
            @Nullable Consumer<MetricBucketRow> promotionListener) {
        this.backendIdSupplier = Objects.requireNonNull(backendIdSupplier, "backendIdSupplier");
        this.publishedTierSupplier =
                Objects.requireNonNull(publishedTierSupplier, "publishedTierSupplier");
        this.generationSupplier = Objects.requireNonNull(generationSupplier, "generationSupplier");
        this.promotionListener = promotionListener;
    }

    public void setPromotionListener(@Nullable Consumer<MetricBucketRow> listener) {
        this.promotionListener = listener;
    }

    public void setPublishedTier(String tier) {
        final String clean =
                (tier == null || tier.isBlank()) ? NetworkTimeSeriesConfig.DEFAULT_TIER : tier.trim();
        this.publishedTierSupplier = () -> clean;
    }

    public void setBackendId(String backendId) {
        final String clean =
                (backendId == null || backendId.isBlank()) ? "backend-local" : backendId.trim();
        this.backendIdSupplier = () -> clean;
    }

    public void setGeneration(long generation) {
        final long clean = Math.max(0L, generation);
        this.generationSupplier = () -> clean;
    }

    /**
     * Records one sample from {@code snapshot} (MSPT and heapUsedBytes) and feeds condensed
     * tiers at {@code snapshot.timestampMillis / 1000L}.
     */
    public void recordFromSnapshot(MetricsSnapshot snapshot) {
        if (snapshot == null) return;
        double mspt = aggregateMspt(snapshot);
        long heap = snapshot.heapUsedBytes;
        long idx = writeIndex.getAndIncrement();
        int slot = (int) (idx & (CAPACITY - 1));
        msptBits.set(slot, Double.doubleToLongBits(mspt));
        heapBytes.set(slot, heap);

        long epochSeconds = Math.max(0L, snapshot.takenAtEpochMs / 1000L);
        if (Double.isFinite(mspt)) {
            recordSample("mspt", mspt, epochSeconds);
        }
        if (heap >= 0L) {
            recordSample("heap_used_bytes", (double) heap, epochSeconds);
        }
        if (Double.isFinite(snapshot.tps1m)) {
            recordSample("tps_1m", snapshot.tps1m, epochSeconds);
        }
        if (snapshot.playerCount >= 0) {
            recordSample("player_count", snapshot.playerCount, epochSeconds);
        }
        RTPMetricsExtension ext = snapshot.extension(RTPMetricsExtension.class);
        if (ext != null) {
            recordSample("queue_depth", ext.queueDepth, epochSeconds);
            recordSample("pending_teleports", ext.pendingTeleports, epochSeconds);
            if (Double.isFinite(ext.avgPipelineMs)) {
                recordSample("avg_pipeline_ms", ext.avgPipelineMs, epochSeconds);
            }
        }
    }

    /**
     * Records a single metric sample at {@code timestampEpochSeconds} into the local tiers.
     *
     * <p>Per ADR-040 / REQ-RTP-METTS-002, per-sample and per-tick writes never occur; a
     * {@link MetricBucketRow} is emitted to the configured promotion listener only when a
     * condensed tier bucket crosses its window boundary and becomes immutable.
     *
     * @param metricKey             non-blank metric key
     * @param value                 finite sample value
     * @param timestampEpochSeconds UTC epoch seconds (&gt;= 0)
     */
    public synchronized void recordSample(
            String metricKey, double value, long timestampEpochSeconds) {
        if (metricKey == null || metricKey.isBlank()) return;
        if (!Double.isFinite(value) || timestampEpochSeconds < 0L) return;

        String publishedTier = resolvePublishedTier();
        Map<String, TierState> tiers =
                metricTiers.computeIfAbsent(metricKey, k -> createTierStates(publishedTier));
        if (!tiers.containsKey(publishedTier)) {
            int winSec = NetworkTimeSeriesConfig.parseWindowSeconds(publishedTier);
            long retSec = NetworkTimeSeriesConfig.parseRetentionSeconds(publishedTier);
            int cap = (int) Math.max(1L, retSec / Math.max(1, winSec));
            tiers.put(publishedTier, new TierState(publishedTier, winSec, cap));
        }

        String backendId = resolveBackendId();
        long generation = Math.max(0L, generationSupplier.getAsLong());
        Consumer<MetricBucketRow> listener = this.promotionListener;

        for (TierState state : tiers.values()) {
            MetricBucketRow promoted =
                    state.onSample(backendId, metricKey, value, timestampEpochSeconds, generation);
            if (promoted != null
                    && state.windowSeconds > 1
                    && state.tierSpec.equalsIgnoreCase(publishedTier)
                    && listener != null) {
                listener.accept(promoted);
            }
        }
    }

    /**
     * Advances all active metric accumulators to {@code timestampEpochSeconds}, promoting any
     * buckets whose window has completed ({@code windowStartEpoch + windowSeconds <= timestampEpochSeconds})
     * (REQ-RTP-METTS-002).
     */
    public synchronized void advanceToEpochSeconds(long timestampEpochSeconds) {
        if (timestampEpochSeconds < 0L) return;
        String publishedTier = resolvePublishedTier();
        String backendId = resolveBackendId();
        long generation = Math.max(0L, generationSupplier.getAsLong());
        Consumer<MetricBucketRow> listener = this.promotionListener;

        for (Map.Entry<String, Map<String, TierState>> entry : metricTiers.entrySet()) {
            String metricKey = entry.getKey();
            for (TierState state : entry.getValue().values()) {
                MetricBucketRow promoted =
                        state.advanceTo(backendId, metricKey, timestampEpochSeconds, generation);
                if (promoted != null
                        && state.windowSeconds > 1
                        && state.tierSpec.equalsIgnoreCase(publishedTier)
                        && listener != null) {
                    listener.accept(promoted);
                }
            }
        }
    }

    /**
     * Returns completed buckets for {@code (metricKey, tierSpec)} in chronological order.
     */
    public synchronized List<MetricBucketRow> completedBuckets(String metricKey, String tierSpec) {
        if (metricKey == null || tierSpec == null) return Collections.emptyList();
        Map<String, TierState> map = metricTiers.get(metricKey);
        if (map == null) return Collections.emptyList();
        TierState state = map.get(tierSpec);
        if (state == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(state.completed));
    }

    private String resolvePublishedTier() {
        String t = publishedTierSupplier.get();
        return (t == null || t.isBlank()) ? NetworkTimeSeriesConfig.DEFAULT_TIER : t.trim();
    }

    private String resolveBackendId() {
        String b = backendIdSupplier.get();
        return (b == null || b.isBlank()) ? "backend-local" : b.trim();
    }

    private static Map<String, TierState> createTierStates(String publishedTier) {
        Map<String, TierState> map = new LinkedHashMap<>();
        map.put(TIER_5M_1S, new TierState(TIER_5M_1S, 1, 300));
        map.put(TIER_1H_10S, new TierState(TIER_1H_10S, 10, 360));
        map.put(TIER_24H_1M, new TierState(TIER_24H_1M, 60, 1440));
        if (publishedTier != null && !map.containsKey(publishedTier)) {
            int winSec = NetworkTimeSeriesConfig.parseWindowSeconds(publishedTier);
            long retSec = NetworkTimeSeriesConfig.parseRetentionSeconds(publishedTier);
            int cap = (int) Math.max(1L, retSec / Math.max(1, winSec));
            map.put(publishedTier, new TierState(publishedTier, winSec, cap));
        }
        return map;
    }

    private static final class TierState {
        final String tierSpec;
        final int windowSeconds;
        final int capacity;
        final Deque<MetricBucketRow> completed;

        long activeWindowStart = -1L;
        int count = 0;
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double[] samples = new double[16];

        TierState(String tierSpec, int windowSeconds, int capacity) {
            this.tierSpec = tierSpec;
            this.windowSeconds = Math.max(1, windowSeconds);
            this.capacity = Math.max(1, capacity);
            this.completed = new ArrayDeque<>(Math.min(capacity, 256));
        }

        @Nullable
        MetricBucketRow onSample(
                String backendId,
                String metricKey,
                double value,
                long timestampEpochSeconds,
                long generation) {
            long alignedWindow = (timestampEpochSeconds / windowSeconds) * windowSeconds;
            MetricBucketRow promoted = null;
            if (activeWindowStart >= 0L && alignedWindow > activeWindowStart && count > 0) {
                promoted = finalizeActive(backendId, metricKey, generation);
            }
            if (activeWindowStart < 0L || alignedWindow > activeWindowStart) {
                activeWindowStart = alignedWindow;
                count = 0;
                sum = 0.0;
                min = Double.POSITIVE_INFINITY;
                max = Double.NEGATIVE_INFINITY;
            }
            if (alignedWindow < activeWindowStart) {
                // Out-of-order older sample: ignore for active window accumulator.
                return null;
            }
            if (count == samples.length) {
                samples = Arrays.copyOf(samples, samples.length * 2);
            }
            samples[count++] = value;
            sum += value;
            if (value < min) min = value;
            if (value > max) max = value;
            return promoted;
        }

        @Nullable
        MetricBucketRow advanceTo(
                String backendId,
                String metricKey,
                long timestampEpochSeconds,
                long generation) {
            if (activeWindowStart >= 0L
                    && count > 0
                    && timestampEpochSeconds >= activeWindowStart + windowSeconds) {
                MetricBucketRow promoted = finalizeActive(backendId, metricKey, generation);
                activeWindowStart = -1L;
                count = 0;
                sum = 0.0;
                min = Double.POSITIVE_INFINITY;
                max = Double.NEGATIVE_INFINITY;
                return promoted;
            }
            return null;
        }

        private MetricBucketRow finalizeActive(
                String backendId, String metricKey, long generation) {
            double mean = sum / count;
            if (mean < min) mean = min;
            if (mean > max) mean = max;
            double[] copy = Arrays.copyOf(samples, count);
            Arrays.sort(copy);
            int p95Idx = Math.min(count - 1, Math.max(0, (int) Math.ceil(0.95 * count) - 1));
            double p95 = copy[p95Idx];
            if (p95 < min) p95 = min;
            if (p95 > max) p95 = max;

            MetricBucketRow row =
                    new MetricBucketRow(
                            backendId,
                            metricKey,
                            activeWindowStart,
                            windowSeconds,
                            count,
                            mean,
                            min,
                            max,
                            Double.valueOf(p95),
                            generation);
            while (completed.size() >= capacity) {
                completed.pollFirst();
            }
            completed.addLast(row);
            return row;
        }
    }

    private static double aggregateMspt(MetricsSnapshot snapshot) {
        // Prefer the host scalar: on Folia the binding has already aggregated
        // per-region MSPT per the configurable foliaAggregationMspt mode
        // (max|mean), so honouring it here keeps the sparkline consistent with
        // every other MSPT surface. On single-region runtimes this is simply
        // the single-thread value.
        if (!Double.isNaN(snapshot.mspt)) {
            return snapshot.mspt;
        }
        // Fallback: the host scalar was unsampled but per-region detail may
        // still carry data (e.g. foliaIncludeRegions=true while the scalar
        // path lagged). Surface the worst region so a spike still renders.
        List<FoliaRegionSample> regions = snapshot.foliaRegions;
        if (regions == null || regions.isEmpty()) {
            return snapshot.mspt;
        }
        double max = Double.NaN;
        for (FoliaRegionSample r : regions) {
            if (r == null) continue;
            double m = r.mspt;
            if (Double.isNaN(m)) continue;
            if (Double.isNaN(max) || m > max) {
                max = m;
            }
        }
        return max;
    }

    /** Number of distinct samples observed (capped at {@link #CAPACITY}). */
    public int sampleCount() {
        long total = writeIndex.get();
        return total >= CAPACITY ? CAPACITY : (int) total;
    }

    /** Total samples ever recorded since process start (uncapped). */
    public long totalRecorded() {
        return writeIndex.get();
    }

    /**
     * Snapshot of the populated MSPT samples in chronological order
     * (oldest first, newest last). Returns an array of length
     * {@link #sampleCount()}; an empty array if no samples have been
     * recorded yet. {@code NaN} values are preserved (caller treats them
     * as "no data" for that pixel column).
     */
    public double[] msptSnapshot() {
        long total = writeIndex.get();
        int n = total >= CAPACITY ? CAPACITY : (int) total;
        double[] out = new double[n];
        if (n == 0) return out;
        long start = total - n;
        for (int i = 0; i < n; i++) {
            int slot = (int) ((start + i) & (CAPACITY - 1));
            out[i] = Double.longBitsToDouble(msptBits.get(slot));
        }
        return out;
    }

    /**
     * Snapshot of the populated heap-used-bytes samples in chronological
     * order (oldest first, newest last). Same length as
     * {@link #msptSnapshot()}; the two arrays are index-aligned (the
     * i-th heap sample was recorded alongside the i-th MSPT sample).
     */
    public long[] heapSnapshot() {
        long total = writeIndex.get();
        int n = total >= CAPACITY ? CAPACITY : (int) total;
        long[] out = new long[n];
        if (n == 0) return out;
        long start = total - n;
        for (int i = 0; i < n; i++) {
            int slot = (int) ((start + i) & (CAPACITY - 1));
            out[i] = heapBytes.get(slot);
        }
        return out;
    }
}
