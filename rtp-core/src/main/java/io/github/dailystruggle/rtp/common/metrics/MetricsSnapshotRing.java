package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size rolling time-series of selected scalar fields from {@link MetricsSnapshot}.
 * Used by {@code METRIC_SPARKLINE} chart rendering. Lock-free ring of 128 samples.
 */
public final class MetricsSnapshotRing {

    public static final int CAPACITY = 128;

    private final AtomicLongArray msptBits = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray heapBytes = new AtomicLongArray(CAPACITY);
    private final AtomicLong writeIndex = new AtomicLong(0L);

    /**
     * Records one sample from {@code snapshot} (MSPT and heapUsedBytes).
     */
    public void recordFromSnapshot(MetricsSnapshot snapshot) {
        if (snapshot == null) return;
        double mspt = aggregateMspt(snapshot);
        long heap = snapshot.heapUsedBytes;
        long idx = writeIndex.getAndIncrement();
        int slot = (int) (idx & (CAPACITY - 1));
        msptBits.set(slot, Double.doubleToLongBits(mspt));
        heapBytes.set(slot, heap);
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
