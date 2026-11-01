package io.github.dailystruggle.rtp.common.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size rolling sample window for {@code TeleportPipelineTask} completion times (ms).
 * Wait-free ring buffer (256 samples) with lock-free atomic index.
 */
public final class PipelineHistogram {

    public static final int CAPACITY = 256;

    private final AtomicLongArray samples = new AtomicLongArray(CAPACITY);
    private final AtomicLong writeIndex = new AtomicLong(0L);

    /** Records a completion time. Negative values are clamped to zero. */
    public void record(long millis) {
        if (millis < 0L) millis = 0L;
        long idx = writeIndex.getAndIncrement();
        samples.set((int) (idx & (CAPACITY - 1)), millis);
    }

    /** Number of distinct samples observed (capped at {@link #CAPACITY} for the mean window). */
    public int sampleCount() {
        long total = writeIndex.get();
        return total >= CAPACITY ? CAPACITY : (int) total;
    }

    /** Total samples ever recorded since process start (uncapped). */
    public long totalRecorded() {
        return writeIndex.get();
    }

    /**
     * Arithmetic mean of the samples currently in the ring.
     * Returns {@link Double#NaN} when no samples have been recorded.
     */
    public double mean() {
        int n = sampleCount();
        if (n == 0) return Double.NaN;
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += samples.get(i);
        }
        return ((double) sum) / n;
    }

    /**
     * Point-in-time copy of the populated portion of the ring, sorted ascending.
     * Bounded by {@link #CAPACITY}; never touches the wait-free write path. Returns an
     * empty array when no samples have been recorded.
     *
     * <p>See {@code ADR-053}: percentiles are computed on demand by sorting this copy,
     * pulling in no new dependency.
     */
    private long[] sortedSnapshot() {
        int n = sampleCount();
        if (n == 0) return new long[0];
        long[] copy = new long[n];
        for (int i = 0; i < n; i++) {
            copy[i] = samples.get(i);
        }
        java.util.Arrays.sort(copy);
        return copy;
    }

    /**
     * The {@code p}-th percentile (0..100) of the samples currently in the ring, in ms.
     * Uses the nearest-rank method on a sorted point-in-time copy. Returns
     * {@link Double#NaN} when no samples have been recorded. Does not mutate the ring
     * and does not block {@link #record(long)} (REQ-RTP-OBS-004).
     */
    public double percentile(double p) {
        return percentile(sortedSnapshot(), p);
    }

    private static double percentile(long[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        if (p <= 0.0) return sorted[0];
        if (p >= 100.0) return sorted[n - 1];
        // Nearest-rank: rank = ceil(p/100 * n), 1-based, clamped to [1, n].
        int rank = (int) Math.ceil((p / 100.0) * n);
        if (rank < 1) rank = 1;
        if (rank > n) rank = n;
        return sorted[rank - 1];
    }

    /**
     * Snapshot of P50/P75/P90/P95/P99 (and min/max) computed from a single sorted copy
     * of the ring so all five percentiles describe the same point-in-time window. All
     * fields are {@link Double#NaN} when no samples have been recorded.
     */
    public Percentiles percentiles() {
        long[] sorted = sortedSnapshot();
        if (sorted.length == 0) {
            return new Percentiles(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, 0);
        }
        return new Percentiles(
                percentile(sorted, 50.0),
                percentile(sorted, 75.0),
                percentile(sorted, 90.0),
                percentile(sorted, 95.0),
                percentile(sorted, 99.0),
                sorted[0],
                sorted[sorted.length - 1],
                sorted.length);
    }

    /** Immutable carrier for a single point-in-time percentile readout (ms). */
    public static final class Percentiles {
        public final double p50;
        public final double p75;
        public final double p90;
        public final double p95;
        public final double p99;
        public final double min;
        public final double max;
        public final int sampleCount;

        public Percentiles(double p50, double p75, double p90, double p95, double p99,
                           double min, double max, int sampleCount) {
            this.p50 = p50;
            this.p75 = p75;
            this.p90 = p90;
            this.p95 = p95;
            this.p99 = p99;
            this.min = min;
            this.max = max;
            this.sampleCount = sampleCount;
        }
    }
}
