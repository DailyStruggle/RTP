package io.github.dailystruggle.rtp.common.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-global accumulator of wall-clock time spent executing RTP tasks.
 * Uses lock-free {@link LongAdder} counters for sync and async execution timing.
 */
public final class RtpSchedulerProfile {

    /** Shared process-wide instance fed by the profiling scheduler decorator. */
    public static final RtpSchedulerProfile GLOBAL = new RtpSchedulerProfile();

    private final LongAdder syncNanos = new LongAdder();
    private final LongAdder asyncNanos = new LongAdder();
    private final LongAdder syncRuns = new LongAdder();
    private final LongAdder asyncRuns = new LongAdder();

    /** Records one main-thread / region task execution of {@code nanos} duration. Negative input ignored. */
    public void recordSync(long nanos) {
        if (nanos < 0L) return;
        syncNanos.add(nanos);
        syncRuns.increment();
    }

    /** Records one asynchronous task execution of {@code nanos} duration. Negative input ignored. */
    public void recordAsync(long nanos) {
        if (nanos < 0L) return;
        asyncNanos.add(nanos);
        asyncRuns.increment();
    }

    /** Cumulative nanoseconds spent in main-thread / region tasks since process start (or last {@link #reset()}). */
    public long syncNanos() {
        return syncNanos.sum();
    }

    /** Cumulative nanoseconds spent in asynchronous tasks since process start (or last {@link #reset()}). */
    public long asyncNanos() {
        return asyncNanos.sum();
    }

    /** Cumulative count of main-thread / region task executions. */
    public long syncRuns() {
        return syncRuns.sum();
    }

    /** Cumulative count of asynchronous task executions. */
    public long asyncRuns() {
        return asyncRuns.sum();
    }

    /** Resets all counters to zero. For tests only. */
    public void reset() {
        syncNanos.reset();
        asyncNanos.reset();
        syncRuns.reset();
        asyncRuns.reset();
    }
}
