package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-005: AimdLimiter Lag-Aware AIMD Stability Control and Deadband Discipline")
class AimdLimiterTest {

    @Test
    @DisplayName("Validation: Constructor rejects invalid bounds and control fractions")
    void constructor_validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new AimdLimiter(10.0, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new AimdLimiter(Double.NaN, 10.0));
        assertThrows(IllegalArgumentException.class,
                () -> new AimdLimiter(1.0, 10.0, 10.0, 0.0, 0.1, 0.1, 1000L, 1000L, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AimdLimiter(1.0, 10.0, 10.0, 1.0, 0.1, 0.1, 1000L, 1000L, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AimdLimiter(1.0, 10.0, 10.0, 0.5, 0.0, 0.1, 1000L, 1000L, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new AimdLimiter(1.0, 10.0, 10.0, 0.5, 0.1, -0.1, 1000L, 1000L, 1000L));
    }

    @Test
    @DisplayName("Signal State (ADR-087 I6): NEVER_SAMPLED leaves limit and published value at ceiling")
    void neverSampled_holdsCeiling() {
        AimdLimiter limiter = new AimdLimiter(2.0, 16.0);
        double published = limiter.evaluate(16.0, false, 0L, false, true, 1_000_000L);
        assertEquals(16.0, published, 1e-9);
        assertEquals(16.0, limiter.rawLimit(), 1e-9);
        assertFalse(limiter.hasCut());
        assertEquals(0L, limiter.cutCount());
    }

    @Test
    @DisplayName("Breach Cut & Cooldown: Halves limit immediately and enforces max(minCooldown, signalLag) before next cut")
    void breachCut_andLagAwareCooldown() {
        long minCooldown = TimeUnit.SECONDS.toNanos(1);
        long waitNanos = TimeUnit.SECONDS.toNanos(5);
        long windowNanos = TimeUnit.SECONDS.toNanos(5);
        AimdLimiter limiter = new AimdLimiter(2.0, 16.0, 16.0, 0.5, 0.10, 0.10, minCooldown, waitNanos, windowNanos);

        long t0 = TimeUnit.SECONDS.toNanos(10);
        long signalLag = TimeUnit.SECONDS.toNanos(3);

        // First breach cuts immediately: 16.0 * 0.5 = 8.0
        double pub1 = limiter.evaluate(16.0, true, signalLag, false, false, t0);
        assertEquals(8.0, pub1, 1e-9);
        assertEquals(8.0, limiter.rawLimit(), 1e-9);
        assertTrue(limiter.hasCut());
        assertTrue(limiter.isLimiterBinding(16.0));
        assertEquals(1L, limiter.cutCount());

        // Still breaching after 2s (< 3s lag cooldown) -> no second cut yet
        double pub2 = limiter.evaluate(16.0, true, signalLag, false, false, t0 + TimeUnit.SECONDS.toNanos(2));
        assertEquals(8.0, pub2, 1e-9);
        assertEquals(1L, limiter.cutCount());

        // Still breaching at 3s (>= 3s lag cooldown) -> second cut: 8.0 * 0.5 = 4.0
        double pub3 = limiter.evaluate(16.0, true, signalLag, false, false, t0 + TimeUnit.SECONDS.toNanos(3));
        assertEquals(4.0, pub3, 1e-9);
        assertEquals(2L, limiter.cutCount());
    }

    @Test
    @DisplayName("Additive Recovery & Asymmetric Deadband: Recovers after wait and publishes past deadband or at ceiling")
    void additiveRecovery_andAsymmetricDeadband() {
        long minCooldown = TimeUnit.SECONDS.toNanos(1);
        long waitNanos = TimeUnit.SECONDS.toNanos(5);
        long windowNanos = TimeUnit.SECONDS.toNanos(5);
        // floor = 0.0, ceiling = 100.0, cut = 0.5, recovery = 10% per 5s (+10.0 per 5s), deadband = 10%
        AimdLimiter limiter = new AimdLimiter(0.0, 100.0, 100.0, 0.5, 0.10, 0.10, minCooldown, waitNanos, windowNanos);

        long t = TimeUnit.SECONDS.toNanos(1);
        // Breach cuts to 50.0
        assertEquals(50.0, limiter.evaluate(100.0, true, 0L, false, false, t), 1e-9);

        // 5s of clean fresh samples -> wait satisfied, still at 50.0
        t += TimeUnit.SECONDS.toNanos(5);
        assertEquals(50.0, limiter.evaluate(100.0, false, 0L, false, false, t), 1e-9);
        assertEquals(50.0, limiter.rawLimit(), 1e-9);

        // +2.5s clean -> rawLimit increases by +5.0 to 55.0 (exactly +10% of 50.0, not > threshold 55.0) -> held by deadband
        t += TimeUnit.MILLISECONDS.toNanos(2500);
        assertEquals(50.0, limiter.evaluate(100.0, false, 0L, false, false, t), 1e-9);
        assertEquals(55.0, limiter.rawLimit(), 1e-9);

        // +1s clean -> rawLimit = 57.0 (> 55.0 deadband threshold) -> publishes 57.0
        t += TimeUnit.SECONDS.toNanos(1);
        assertEquals(57.0, limiter.evaluate(100.0, false, 0L, false, false, t), 1e-9);

        // +25s clean -> rawLimit reaches 100.0 (ceiling) -> publishes 100.0 and clears hasCut
        t += TimeUnit.SECONDS.toNanos(25);
        assertEquals(100.0, limiter.evaluate(100.0, false, 0L, false, false, t), 1e-9);
        assertFalse(limiter.hasCut());
    }

    @Test
    @DisplayName("Signal State (ADR-087 I6): STALE holds limit and recovery timer without raising published value")
    void staleSignal_holdsLimitAndRecoveryTimer() {
        AimdLimiter limiter = new AimdLimiter(10.0, 100.0);
        long t = TimeUnit.SECONDS.toNanos(1);

        // Cut to 50.0
        assertEquals(50.0, limiter.evaluate(100.0, true, 0L, false, false, t), 1e-9);

        // Advance 20 seconds while signal is STALE -> must not recover or raise published value
        t += TimeUnit.SECONDS.toNanos(20);
        assertEquals(50.0, limiter.evaluate(100.0, false, 0L, true, false, t), 1e-9);
        assertEquals(50.0, limiter.rawLimit(), 1e-9);
        assertTrue(limiter.hasCut());
    }

    @Test
    @DisplayName("Floor Duration, Bounds Update, and Reset: Tracks floor residency and clamps state")
    void forceToFloor_durationBoundsAndReset() {
        AimdLimiter limiter = new AimdLimiter(4.0, 32.0);
        long t0 = TimeUnit.SECONDS.toNanos(10);

        assertEquals(0L, limiter.durationAtFloorNanos(t0));
        limiter.forceToFloor(0L, t0);
        assertEquals(4.0, limiter.publishedValue(), 1e-9);
        assertTrue(limiter.hasCut());
        assertEquals(TimeUnit.SECONDS.toNanos(3), limiter.durationAtFloorNanos(t0 + TimeUnit.SECONDS.toNanos(3)));

        // Raise floor to 8.0 while cut -> clamps limit and published to 8.0
        limiter.updateBounds(8.0, 64.0);
        assertEquals(8.0, limiter.floor(), 1e-9);
        assertEquals(64.0, limiter.ceiling(), 1e-9);
        assertEquals(8.0, limiter.publishedValue(), 1e-9);

        // Reset restores ceiling
        limiter.reset();
        assertEquals(64.0, limiter.publishedValue(), 1e-9);
        assertEquals(64.0, limiter.rawLimit(), 1e-9);
        assertFalse(limiter.hasCut());
        assertEquals(0L, limiter.cutCount());
    }

    @Test
    @DisplayName("Sub-second 250ms evaluations on nanosecond-scaled bounds converge cleanly to ceiling and reset lag per episode")
    void nanosecondScaleConvergence_andEpisodeLagReset() {
        long ms = TimeUnit.MILLISECONDS.toNanos(1);
        long sec = TimeUnit.SECONDS.toNanos(1);
        AimdLimiter limiter = new AimdLimiter(ms, 25.0 * ms);

        // High-lag breach episode (L = 30s) followed by partial recovery, then a new low-lag breach (L = 2s)
        limiter.evaluate(25.0 * ms, true, 30 * sec, false, false, 0L);
        limiter.evaluate(25.0 * ms, false, 30 * sec, false, false, sec);
        // New breach episode with 2s lag resets activeLagNanos to 2s
        limiter.forceToFloor(2 * sec, 2 * sec);
        limiter.evaluate(25.0 * ms, true, 2 * sec, false, false, 2 * sec);

        long step = 250 * ms;
        long total = 2 * sec + Math.max(5 * sec, 2 * sec) + 50 * sec;
        double pub = 0.0;
        for (long t = 2 * sec + step; t <= total; t += step) {
            pub = limiter.evaluate(25.0 * ms, false, 2 * sec, false, false, t);
        }
        assertEquals(25.0 * ms, pub, 1.0);
        assertEquals(25.0 * ms, limiter.rawLimit(), 1.0);
        assertFalse(limiter.hasCut(), "200 x 250ms steps must clear hasCut at ceiling without float drift");
    }
}
