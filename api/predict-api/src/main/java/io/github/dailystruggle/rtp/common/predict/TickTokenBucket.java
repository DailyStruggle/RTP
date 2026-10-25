package io.github.dailystruggle.rtp.common.predict;

import java.util.concurrent.TimeUnit;

/**
 * Ceiling-adaptive tick-refilled token bucket for bounding execution work per tick interval
 * with no multi-tick catch-up accumulation (ADR-112, ADR-123 Sections 3, 6, 8, Invariant I1).
 *
 * <ul>
 *   <li><b>Proportional tick refill:</b> Earns {@code ceilingNanos} of tokens per {@code tickNanos}
 *       (default 50 ms) and holds at most {@code ceilingNanos}, so a delayed tick gets no catch-up allowance.</li>
 *   <li><b>Ceiling adaptation:</b> When {@code ceilingNanos} increases above the previous ceiling
 *       (e.g. an exclusive loan is granted), tokens increase by the delta {@code ceilingNanos - prevCeilingNanos}
 *       up to {@code ceilingNanos}. When {@code ceilingNanos} decreases, tokens are clamped to the new ceiling.</li>
 *   <li><b>Bounded single-task debt:</b> Debiting work clamps remaining tokens at {@code -ceilingNanos}
 *       so a single slow task cannot accumulate unbounded debt across more than one tick.</li>
 * </ul>
 *
 * <p>Zero external dependencies, zero locks, and zero thread creation (Tier 1 {@code common.predict}, ADR-115).
 */
public final class TickTokenBucket {

    public static final long DEFAULT_TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final long tickNanos;
    private volatile long lastRefillNanos = Long.MIN_VALUE;
    private volatile long tokensNanos;
    private volatile long configuredCeilingNanos;

    public TickTokenBucket() {
        this(DEFAULT_TICK_NANOS);
    }

    public TickTokenBucket(long tickNanos) {
        if (tickNanos < 1L) {
            throw new IllegalArgumentException("tickNanos must be >= 1, got " + tickNanos);
        }
        this.tickNanos = tickNanos;
    }

    public TickTokenBucket(long tickNanos, long initialCeilingNanos, boolean prefillAtZero, long nowNanos) {
        this(tickNanos);
        long ceil = Math.max(0L, initialCeilingNanos);
        this.configuredCeilingNanos = ceil;
        this.tokensNanos = ceil;
        if (prefillAtZero) {
            this.lastRefillNanos = nowNanos;
        }
    }

    /**
     * Initializes the bucket at {@code nowNanos} with {@code ceilingNanos} full tokens.
     *
     * @param ceilingNanos initial ceiling and token balance in nanoseconds
     * @param nowNanos     monotonic timestamp in nanoseconds
     */
    public void initializeFull(long ceilingNanos, long nowNanos) {
        long ceil = Math.max(0L, ceilingNanos);
        this.configuredCeilingNanos = ceil;
        this.tokensNanos = ceil;
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Refills tokens up to {@code ceilingNanos} at {@code nowNanos} and returns the updated token balance.
     *
     * @param ceilingNanos current effective ceiling in nanoseconds
     * @param nowNanos     monotonic timestamp in nanoseconds
     * @return current token balance in nanoseconds (may be negative while in debt)
     */
    public long refill(long ceilingNanos, long nowNanos) {
        long ceil = Math.max(0L, ceilingNanos);
        long prevCeil = this.configuredCeilingNanos;
        this.configuredCeilingNanos = ceil;
        long last = this.lastRefillNanos;
        if (last == Long.MIN_VALUE) {
            this.lastRefillNanos = nowNanos;
            this.tokensNanos = ceil;
            return ceil;
        }
        long tok = this.tokensNanos;
        if (ceil > prevCeil) {
            tok = Math.min(ceil, tok + (ceil - prevCeil));
        }
        long dt = nowNanos - last;
        if (dt <= 0L) {
            tok = Math.min(ceil, tok);
            this.tokensNanos = tok;
            return tok;
        }
        long earned = dt >= tickNanos ? ceil : (ceil * dt) / tickNanos;
        if (earned > 0L) {
            tok = Math.min(ceil, tok + earned);
            this.lastRefillNanos = nowNanos;
        } else {
            tok = Math.min(ceil, tok);
        }
        this.tokensNanos = tok;
        return tok;
    }

    /**
     * Refills the bucket at {@code nowNanos} under {@code ceilingNanos} and debits {@code debitNanos}
     * of work, flooring remaining tokens at {@code -ceilingNanos}.
     *
     * @param debitNanos   work duration to debit in nanoseconds (clamped to {@code >= 0})
     * @param ceilingNanos current effective ceiling in nanoseconds
     * @param nowNanos     monotonic timestamp in nanoseconds
     * @return remaining token balance in nanoseconds after debit
     */
    public long charge(long debitNanos, long ceilingNanos, long nowNanos) {
        long debit = Math.max(0L, debitNanos);
        long ceil = Math.max(0L, ceilingNanos);
        long tok = refill(ceil, nowNanos);
        long next = Math.max(-ceil, tok - debit);
        this.tokensNanos = next;
        return next;
    }

    /**
     * Clamps both the configured ceiling and current token balance to at most {@code other}'s
     * ceiling and token balance (used when merging two cell groups, ADR-123 Section 3).
     *
     * @param other absorbed bucket
     */
    public void clampToMin(TickTokenBucket other) {
        if (other == null) return;
        this.configuredCeilingNanos = Math.min(this.configuredCeilingNanos, other.configuredCeilingNanos);
        this.tokensNanos = Math.min(this.tokensNanos, other.tokensNanos);
    }

    public long tokensNanos() {
        return tokensNanos;
    }

    public long configuredCeilingNanos() {
        return configuredCeilingNanos;
    }

    public long lastRefillNanos() {
        return lastRefillNanos;
    }

    public long tickNanos() {
        return tickNanos;
    }

    /** Resets the bucket to its uninitialized state so the next refill starts with a full ceiling. */
    public void reset() {
        this.lastRefillNanos = Long.MIN_VALUE;
        this.tokensNanos = 0L;
        this.configuredCeilingNanos = 0L;
    }
}
