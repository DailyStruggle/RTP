package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Process-global, always-on accumulator of location-generation outcomes.
 *
 * <p>Every terminal outcome of a {@code PregenTask} attempt is recorded here,
 * independent of the per-invocation {@code verbose} flag: a success, or a failure
 * bucketed by {@link LocationGenerator.FailTypes}. This is the data source that lets
 * the plugin report success / failure rates and a per-cause rejection breakdown
 * (the analogue of a competitor's {@code /rtp unsafe-stats}) without simulating
 * traffic &mdash; the live pipeline fills it organically.
 *
 * <p>Wait-free: counters are {@link AtomicLong} / {@link AtomicLongArray}. No locking,
 * no tick-thread blocking (METRICS_PLAN.md &gt; Goals). Reads are eventually-consistent
 * snapshots; callers must tolerate a counter advancing between two reads.
 *
 * <p>Lifecycle: a single {@link #GLOBAL} instance lives for the process. {@link #reset()}
 * exists for tests and an operator-triggered stats reset; it is not called on
 * {@code /reload}.
 */
public final class RtpOutcomeStats {

    /** Shared process-wide instance fed by the generation pipeline. */
    public static final RtpOutcomeStats GLOBAL = new RtpOutcomeStats();

    private final AtomicLong successes = new AtomicLong(0L);
    private final AtomicLongArray failuresByCause =
            new AtomicLongArray(LocationGenerator.FailTypes.values().length);

    /** Records one successful location generation. */
    public void recordSuccess() {
        successes.incrementAndGet();
    }

    /** Records one rejected attempt, bucketed by {@code cause}. No-op on {@code null}. */
    public void recordFailure(LocationGenerator.FailTypes cause) {
        if (cause == null) return;
        failuresByCause.incrementAndGet(cause.ordinal());
    }

    /** Total successful generations recorded since process start (or last {@link #reset()}). */
    public long successCount() {
        return successes.get();
    }

    /** Failures recorded for a single cause. */
    public long failureCount(LocationGenerator.FailTypes cause) {
        if (cause == null) return 0L;
        return failuresByCause.get(cause.ordinal());
    }

    /** Sum of failures across every cause. */
    public long totalFailures() {
        long total = 0L;
        for (int idx = 0; idx < failuresByCause.length(); idx++) {
            total += failuresByCause.get(idx);
        }
        return total;
    }

    /**
     * Fraction of recorded outcomes that succeeded, in {@code [0.0, 1.0]}.
     * Returns {@link Double#NaN} when no outcomes have been recorded yet.
     */
    public double successRate() {
        long s = successes.get();
        long total = s + totalFailures();
        if (total == 0L) return Double.NaN;
        return ((double) s) / total;
    }

    /** Immutable-style snapshot of per-cause failure counts. Causes with zero are included. */
    public Map<LocationGenerator.FailTypes, Long> failureBreakdown() {
        Map<LocationGenerator.FailTypes, Long> out =
                new EnumMap<>(LocationGenerator.FailTypes.class);
        for (LocationGenerator.FailTypes cause : LocationGenerator.FailTypes.values()) {
            out.put(cause, failuresByCause.get(cause.ordinal()));
        }
        return out;
    }

    /** Resets all counters to zero. For tests and operator-triggered stats resets. */
    public void reset() {
        successes.set(0L);
        for (int idx = 0; idx < failuresByCause.length(); idx++) {
            failuresByCause.set(idx, 0L);
        }
    }
}
