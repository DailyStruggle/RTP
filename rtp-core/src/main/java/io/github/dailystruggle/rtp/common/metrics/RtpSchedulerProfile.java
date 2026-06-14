package io.github.dailystruggle.rtp.common.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-global, always-on accumulator of the wall-clock time RTP's own
 * scheduler spends executing the tasks RTP submits to it.
 *
 * <p>Unlike the host MSPT (which measures whole-server tick utilisation and is
 * therefore not attributable to RTP), every task counted here was submitted
 * through {@code RTP.scheduler}, so the totals are RTP-attributable cost. The
 * decorator that feeds this profile classifies each task by submission family:
 * <ul>
 *   <li>{@code sync} - work routed onto the main server thread (on Folia, the
 *       global/region scheduler): {@code runTask}, {@code runTaskLater},
 *       {@code runTask(location/chunk, ...)}, and the main-thread timer bodies.</li>
 *   <li>{@code async} - work routed onto an asynchronous pool:
 *       {@code runTaskAsynchronously} and the async timer bodies.</li>
 * </ul>
 *
 * <p>Wait-free: counters are {@link LongAdder}. No locking, no tick-thread
 * blocking. Reads are eventually-consistent snapshots; callers must tolerate a
 * counter advancing between two reads.
 *
 * <p>Lifecycle: a single {@link #GLOBAL} instance lives for the process.
 * {@link #reset()} exists for tests; it is not called on {@code /reload}.
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
