package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitTpsSamplerTest {

    private static BukkitTpsSampler samplerWithClock(AtomicLong clock) {
        return new BukkitTpsSampler(clock::get);
    }

    @Test
    void before_any_tick_all_getters_return_unsampled_sentinels() {
        AtomicLong clock = new AtomicLong(0L);
        BukkitTpsSampler s = samplerWithClock(clock);

        assertTrue(Double.isNaN(s.tps1m()));
        assertTrue(Double.isNaN(s.tps5m()));
        assertTrue(Double.isNaN(s.tps15m()));
        assertTrue(Double.isNaN(s.mspt()));
        assertEquals(MetricsSnapshot.UNSAMPLED, s.tps1m());
    }

    @Test
    void first_tick_only_seeds_no_ema_yet() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        BukkitTpsSampler s = samplerWithClock(clock);

        s.tick();

        assertTrue(Double.isNaN(s.tps1m()));
        assertTrue(Double.isNaN(s.mspt()));
    }

    @Test
    void steady_50ms_inter_tick_yields_20_tps_and_50_mspt() {
        AtomicLong clock = new AtomicLong(0L);
        BukkitTpsSampler s = samplerWithClock(clock);

        s.tick(); // seed
        // Bootstrap on first delta — exact 50ms — and then keep stable.
        for (int i = 0; i < 50; i++) {
            clock.addAndGet(50_000_000L); // +50ms
            s.tick();
        }

        assertEquals(20.0, s.tps1m(), 1e-9);
        assertEquals(20.0, s.tps5m(), 1e-9);
        assertEquals(20.0, s.tps15m(), 1e-9);
        assertEquals(50.0, s.mspt(), 1e-9);
    }

    @Test
    void slow_100ms_inter_tick_yields_10_tps_and_100_mspt() {
        AtomicLong clock = new AtomicLong(0L);
        BukkitTpsSampler s = samplerWithClock(clock);

        s.tick();
        for (int i = 0; i < 50; i++) {
            clock.addAndGet(100_000_000L); // +100ms
            s.tick();
        }

        assertEquals(10.0, s.tps1m(), 1e-9);
        assertEquals(100.0, s.mspt(), 1e-9);
    }

    @Test
    void faster_than_20tps_is_clamped_to_20() {
        AtomicLong clock = new AtomicLong(0L);
        BukkitTpsSampler s = samplerWithClock(clock);

        s.tick();
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(10_000_000L); // +10ms => 100 raw TPS, must clamp
            s.tick();
        }

        assertEquals(20.0, s.tps1m(), 1e-9);
        assertEquals(20.0, s.tps5m(), 1e-9);
        assertEquals(20.0, s.tps15m(), 1e-9);
    }

    @Test
    void non_progressing_clock_is_ignored_after_seed() {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        BukkitTpsSampler s = samplerWithClock(clock);

        s.tick(); // seed
        // Clock did not advance — must not produce NaN/Infinity in the EMA.
        s.tick();
        s.tick();

        // No valid delta has been observed, so EMAs remain unsampled.
        assertTrue(Double.isNaN(s.tps1m()));
        assertTrue(Double.isNaN(s.mspt()));
    }

    @Test
    void mspt_tracks_1m_ema_not_15m() {
        // Run 50ms ticks long enough to converge, then switch to 100ms ticks.
        // The 1m EMA should drift toward 100ms much faster than the 15m EMA,
        // so mspt() (=1m) > corresponding 15m-derived TPS calculation.
        AtomicLong clock = new AtomicLong(0L);
        BukkitTpsSampler s = samplerWithClock(clock);

        s.tick();
        for (int i = 0; i < 2000; i++) {
            clock.addAndGet(50_000_000L);
            s.tick();
        }
        // converged to ~50ms / 20 TPS
        assertEquals(50.0, s.mspt(), 1e-6);

        // Now switch to 100ms for a couple of minutes worth of ticks.
        for (int i = 0; i < 4000; i++) {
            clock.addAndGet(100_000_000L);
            s.tick();
        }

        // 1m EMA has had ample time to track 100ms; 15m has barely moved.
        double mspt1m = s.mspt();
        double tps15m = s.tps15m();
        assertTrue(mspt1m > 95.0 && mspt1m <= 100.0,
                "expected 1m mspt close to 100ms, was " + mspt1m);
        // 15m EMA still skewed toward the original 50ms steady state.
        assertTrue(tps15m > 10.0,
                "expected 15m tps still above 10 due to slow EMA, was " + tps15m);
    }
}
