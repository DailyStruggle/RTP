package io.github.dailystruggle.rtp.common.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size rolling sample window for {@code TeleportPipelineTask} completion times,
 * recorded in milliseconds.
 *
 * <p>Phase M0 ships the data structure; Phase M1 wires
 * {@code TeleportPipelineTask} completion paths to call {@link #record(long)}.
 *
 * <p>Design (per {@code METRICS_PLAN.md > Open Items}): 256-sample ring buffer, never
 * resets. Writes are wait-free via an atomic monotonically-increasing index; reads are
 * snapshots that walk the populated portion of the ring. Callers shall not assume
 * temporal ordering of samples within a snapshot &mdash; only that {@link #mean()}
 * returns the arithmetic mean of the most recent up-to-{@link #CAPACITY} samples.
 *
 * <p>No tick-thread blocking. No locking.
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
}
