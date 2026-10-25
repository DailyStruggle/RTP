package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-112 & ADR-123: TickTokenBucket Proportional Refill, Ceiling Adaptation, and Debt Flooring")
class TickTokenBucketTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long TICK = TickTokenBucket.DEFAULT_TICK_NANOS;

    @Test
    @DisplayName("Validation: Constructor rejects non-positive tickNanos")
    void constructor_validatesTickNanos() {
        assertThrows(IllegalArgumentException.class, () -> new TickTokenBucket(0L));
        assertThrows(IllegalArgumentException.class, () -> new TickTokenBucket(-10L));
    }

    @Test
    @DisplayName("Refill & Charge: Starts full on first refill, refills proportionally, caps at ceiling, and floors debt")
    void refillAndChargeBehavior() {
        TickTokenBucket bucket = new TickTokenBucket(TICK);
        long budget = 5 * MS;
        long t0 = 1_000_000_000L;

        // First refill initializes to full ceiling
        assertEquals(budget, bucket.refill(budget, t0));
        assertEquals(budget, bucket.tokensNanos());
        assertEquals(budget, bucket.configuredCeilingNanos());

        // Spend 3 ms -> 2 ms remaining
        assertEquals(2 * MS, bucket.charge(3 * MS, budget, t0));

        // Half a tick later -> earns 2.5 ms -> 4.5 ms remaining
        long tHalf = t0 + TICK / 2;
        assertEquals(4_500_000L, bucket.refill(budget, tHalf));

        // Multi-tick jump earns no catch-up beyond 1 ceiling
        long tLate = tHalf + 10 * TICK;
        assertEquals(budget, bucket.refill(budget, tLate));

        // Large debit floors debt at -ceiling
        assertEquals(-budget, bucket.charge(100 * MS, budget, tLate));
        // 1 tick clears debt to 0
        assertEquals(0L, bucket.refill(budget, tLate + TICK));
        // 2nd tick restores full budget
        assertEquals(budget, bucket.refill(budget, tLate + 2 * TICK));
    }

    @Test
    @DisplayName("Ceiling Adaptation & Merge Clamp: Increasing ceiling grants delta immediately; decreasing clamps")
    void ceilingAdaptationAndClampToMin() {
        long t0 = 1_000_000_000L;
        TickTokenBucket a = new TickTokenBucket(TICK, 5 * MS, true, t0);
        assertEquals(5 * MS, a.refill(5 * MS, t0));

        // Spend 4 ms -> 1 ms left
        a.charge(4 * MS, 5 * MS, t0);
        assertEquals(MS, a.tokensNanos());

        // Ceiling rises from 5 ms to 10 ms (e.g. exclusive loan) at same timestamp -> +5 ms delta -> 6 ms
        assertEquals(6 * MS, a.refill(10 * MS, t0));

        // Ceiling drops back to 5 ms -> clamped to 5 ms
        assertEquals(5 * MS, a.refill(5 * MS, t0));

        // Merge clampToMin keeps minimum ceiling and tokens
        TickTokenBucket b = new TickTokenBucket(TICK, 3 * MS, true, t0);
        b.charge(2 * MS, 3 * MS, t0); // 1 ms left, ceiling 3 ms
        a.clampToMin(b);
        assertEquals(3 * MS, a.configuredCeilingNanos());
        assertEquals(MS, a.tokensNanos());

        a.reset();
        assertEquals(Long.MIN_VALUE, a.lastRefillNanos());
        assertEquals(5 * MS, a.refill(5 * MS, t0 + TICK));
    }

    @Test
    @DisplayName("Default constructor, initializeFull, tickNanos, and non-advancing clock branches")
    void defaultConstructorInitializeFullAndClockBranches() {
        TickTokenBucket def = new TickTokenBucket();
        assertEquals(TICK, def.tickNanos());
        def.initializeFull(4 * MS, 100L);
        assertEquals(4 * MS, def.tokensNanos());
        assertEquals(4 * MS, def.configuredCeilingNanos());
        assertEquals(100L, def.lastRefillNanos());

        // Non-advancing timestamp (dt <= 0) clamps tokens without earning
        assertEquals(3 * MS, def.refill(3 * MS, 100L));
        assertEquals(3 * MS, def.refill(3 * MS, 50L));

        // Tiny dt where earned == 0
        assertEquals(3 * MS, def.refill(3 * MS, 101L));

        // Null clampToMin is a safe no-op
        def.clampToMin(null);
        assertEquals(3 * MS, def.tokensNanos());

        // Constructor with prefillAtZero=false leaves lastRefillNanos at Long.MIN_VALUE
        TickTokenBucket unprefilled = new TickTokenBucket(TICK, 2 * MS, false, 100L);
        assertEquals(Long.MIN_VALUE, unprefilled.lastRefillNanos());
    }
}
