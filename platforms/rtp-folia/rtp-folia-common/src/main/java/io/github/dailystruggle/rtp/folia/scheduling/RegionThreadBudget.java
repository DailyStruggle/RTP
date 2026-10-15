package io.github.dailystruggle.rtp.folia.scheduling;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-tick-thread token bucket for RTP work on Folia region threads (ADR-112).
 *
 * <p>A tick thread earns {@code budget} ns of RTP time per 50 ms and holds at most one budget,
 * so a region whose tick ran long (the server's own load) gets no catch-up allowance: RTP adds
 * at most about one budget to any region tick. A thread ticks one region at a time, so the
 * bucket bounds RTP's share of whichever region it is ticking. Debt is floored at one budget:
 * a single slow task delays that thread's next RTP work by about two ticks, not indefinitely.
 */
final class RegionThreadBudget {
  static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
  /** {@link #begin()} result for a span nested in another; never charged. */
  static final long NESTED = Long.MIN_VALUE;

  private final LongSupplier clock;
  private final LongSupplier budgetNanos;
  /** [last refill nanos, tokens, nesting depth]; thread-confined. */
  private final ThreadLocal<long[]> state = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0L, 0L});

  RegionThreadBudget(LongSupplier clock, LongSupplier budgetNanos) {
    this.clock = clock;
    this.budgetNanos = budgetNanos;
  }

  long now() {
    return clock.getAsLong();
  }

  /** Whether the calling thread may start another RTP task this tick. */
  boolean hasBudget() {
    return refill()[1] > 0L;
  }

  /** Remaining tokens for the calling thread, nanoseconds (negative while in debt). */
  long remaining() {
    return refill()[1];
  }

  /**
   * Opens a measured span; returns its start, or {@link #NESTED} when nested inside another span on
   * this thread (the outer span already covers the time, so it must not be charged twice).
   */
  long begin() {
    long[] s = state.get();
    return (s[2]++ == 0L) ? clock.getAsLong() : NESTED;
  }

  /** Closes a span opened by {@link #begin()} and charges it when it was the outermost one. */
  void end(long start) {
    long[] s = state.get();
    if (s[2] > 0L) s[2]--;
    if (start != NESTED) charge(clock.getAsLong() - start);
  }

  /** Debits {@code nanos} of measured work from the calling thread's bucket. */
  void charge(long nanos) {
    long[] s = refill();
    long b = budget();
    s[1] = Math.max(-b, s[1] - Math.max(0L, nanos));
  }

  /** Clamped to one tick: RTP may never be granted a whole region tick. */
  private long budget() {
    return Math.max(1L, Math.min(budgetNanos.getAsLong(), TICK_NANOS));
  }

  private long[] refill() {
    long[] s = state.get();
    long now = clock.getAsLong();
    long b = budget();
    if (s[0] == Long.MIN_VALUE) {
      s[0] = now;
      s[1] = b;
      return s;
    }
    long dt = now - s[0];
    if (dt <= 0L) return s;
    // b <= 50 ms and dt < 50 ms here, so the product stays far below Long.MAX_VALUE.
    long earned = dt >= TICK_NANOS ? b : (b * dt) / TICK_NANOS;
    if (earned > 0L) {
      s[1] = Math.min(b, s[1] + earned);
      s[0] = now;
    }
    return s;
  }
}
