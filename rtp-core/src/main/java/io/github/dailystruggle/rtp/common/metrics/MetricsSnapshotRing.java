package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size rolling time-series of selected scalar fields from
 * {@link MetricsSnapshot}, used by the {@code METRIC_SPARKLINE} chart kind.
 *
 * <p>Design:
 * <ul>
 *   <li>{@link #CAPACITY 128} slots per series (matches the vanilla map
 *       width: one column per slot in the renderer's fast path).</li>
 *   <li>Two parallel series: MSPT (milliseconds-per-tick, double encoded as
 *       millibits in a {@code long}) and heap-used bytes. Both indexed by
 *       the same monotonically-increasing {@code writeIndex} so the
 *       caller's i-th MSPT sample matches the i-th heap sample.</li>
 *   <li>Wait-free writes (CAS-free via {@link AtomicLong#getAndIncrement()}
 *       + {@link AtomicLongArray#set}). Reads are lock-free snapshots that
 *       walk the populated portion of each ring.</li>
 * </ul>
 *
 * <p>MSPT encoding: stored as {@code Double.doubleToLongBits(mspt)}; the
 * snapshot accessor unpacks via {@code Double.longBitsToDouble}. {@code NaN}
 * is preserved unchanged (the {@code UNSAMPLED} sentinel from
 * {@link MetricsSnapshot#UNSAMPLED}).
 *
 * <p>Heap encoding: stored as raw bytes ({@code long}).
 *
 * <p>Aggregation: the MSPT recorded is {@code snapshot.mspt}, which the
 * platform binding has already aggregated across Folia regions per the
 * admin-configurable {@code foliaAggregationMspt} mode ({@code max} default,
 * or {@code mean}). This keeps the sparkline consistent with the scalar MSPT
 * surfaced by {@code /rtp info} and the telemetry publishers. Only when the
 * binding leaves {@code snapshot.mspt} unsampled (e.g. per-region detail is
 * enabled but no host-scalar was produced) does the ring fall back to the
 * {@code max} across {@code MetricsSnapshot#foliaRegions} so a worst-region
 * spike still renders. Heap is a per-JVM scalar, no aggregation needed.
 * See {@link #recordFromSnapshot}.
 *
 * <p>No tick-thread blocking. No locking.
 */
public final class MetricsSnapshotRing {

    public static final int CAPACITY = 128;

    private final AtomicLongArray msptBits = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray heapBytes = new AtomicLongArray(CAPACITY);
    private final AtomicLong writeIndex = new AtomicLong(0L);

    /**
     * Records one sample from {@code snapshot}. MSPT is taken from
     * {@code snapshot.mspt} (already aggregated per the configurable
     * {@code foliaAggregationMspt} mode by the platform binding on Folia,
     * and the single-thread value on every other runtime), falling back to
     * the {@code max} across {@code snapshot.foliaRegions} only when the
     * host scalar is unsampled. Heap is taken from
     * {@code snapshot.heapUsedBytes} directly.
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
