package io.github.dailystruggle.rtp.fabric.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FabricMetricsBinding}. Drives the binding with a
 * synthetic nano-clock so EMA convergence is deterministic; player-count
 * and soft-cap are stubbed via the test-seam constructor so no
 * {@code MinecraftServer} runtime is required.
 *
 * <p>Section C, C2. Sister to {@code FoliaMetricsBindingTest} but simpler
 * because Fabric is single-region.
 */
final class FabricMetricsBindingTest {

    /** Fresh binding reports {@link MetricsSnapshot#UNSAMPLED} for every scalar. */
    @Test
    void empty_binding_reports_unsampled_scalars() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps5m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps15m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.mspt(), 1e-9);
    }

    /** First tick seeds the timestamp; no EMA emitted until the second tick. */
    @Test
    void first_tick_only_seeds() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick();
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.mspt(), 1e-9);
    }

    /**
     * Two ticks 50 ms apart yield ~20 TPS. MSPT must NOT be derived from that
     * 50 ms interval - with no work source it stays unsampled rather than
     * reporting the sleep-padded gap as tick time.
     */
    @Test
    void two_ticks_at_nominal_tickrate_yield_twenty_tps() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick(); // seed
        clock.addAndGet(50_000_000L); // 50 ms later
        b.tick();

        double tps = b.tps1m();
        assertNotEquals(MetricsSnapshot.UNSAMPLED, tps);
        assertTrue(tps > 19.5 && tps <= 20.0, "expected ~20 TPS, got " + tps);

        assertEquals(MetricsSnapshot.UNSAMPLED, b.mspt(), 1e-9,
                "MSPT must not be inferred from the sleep-padded tick interval");
    }

    /**
     * MSPT reflects in-tick work, not the tick interval. An idle server ticks
     * every 50 ms while doing ~4 ms of work; MSPT must report ~4, well below
     * the 50 ms interval floor that previously pinned the reading.
     */
    @Test
    void mspt_reports_work_duration_not_tick_interval() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(
                clock::get, () -> 0, () -> 0, () -> 4_000_000L);
        b.tick(); // seed
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(50_000_000L); // nominal 20 TPS cadence
            b.tick();
        }
        double mspt = b.mspt();
        assertTrue(mspt > 3.5 && mspt < 4.5, "expected ~4 ms mspt, got " + mspt);
        assertTrue(b.tps1m() > 19.5, "TPS must still track the interval");
    }

    /**
     * Regression guard for the reported defect: MSPT must be able to come back
     * down after a lag spike. The old interval-based derivation floored at
     * 50 ms, so the reading could only ever rise.
     */
    @Test
    void mspt_decreases_after_a_spike_subsides() {
        AtomicLong clock = new AtomicLong(0L);
        AtomicLong work = new AtomicLong(45_000_000L); // 45 ms of work: near overrun
        FabricMetricsBinding b = new FabricMetricsBinding(
                clock::get, () -> 0, () -> 0, work::get);
        b.tick(); // seed
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(50_000_000L);
            b.tick();
        }
        double spiked = b.mspt();
        assertTrue(spiked > 40.0, "expected a high mspt during the spike, got " + spiked);

        // Load subsides to 2 ms of work per tick.
        work.set(2_000_000L);
        for (int i = 0; i < 400; i++) {
            clock.addAndGet(50_000_000L);
            b.tick();
        }
        double recovered = b.mspt();
        assertTrue(recovered < spiked,
                "mspt must decrease once load subsides: spiked=" + spiked
                        + ", recovered=" + recovered);
        assertTrue(recovered < 10.0,
                "mspt must fall well below the 50 ms interval floor, got " + recovered);
    }

    /**
     * A work source that is unavailable ({@code <= 0}) or throwing leaves MSPT
     * unsampled - never a fabricated value - while TPS keeps working.
     */
    @Test
    void unavailable_or_throwing_work_source_leaves_mspt_unsampled() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding unavailable = new FabricMetricsBinding(
                clock::get, () -> 0, () -> 0, () -> 0L);
        FabricMetricsBinding throwing = new FabricMetricsBinding(
                clock::get, () -> 0, () -> 0,
                () -> { throw new RuntimeException("boom"); });
        unavailable.tick();
        throwing.tick();
        clock.addAndGet(50_000_000L);
        unavailable.tick();
        throwing.tick();

        assertEquals(MetricsSnapshot.UNSAMPLED, unavailable.mspt(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, throwing.mspt(), 1e-9);
        assertTrue(throwing.tps1m() > 19.5, "TPS must survive a failing work source");
    }

    /**
     * Sustained 100 ms tick deltas (10 TPS) converge the 1-minute EMA
     * downward over many ticks. We don't require full convergence - just
     * monotonic movement away from the nominal seed.
     */
    @Test
    void slow_ticks_drive_tps_below_twenty() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick(); // seed
        clock.addAndGet(50_000_000L);
        b.tick(); // baseline at ~20 TPS

        double baseline = b.tps1m();
        // Now 200 slow ticks at 100 ms each (10 TPS).
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(100_000_000L);
            b.tick();
        }
        double slow = b.tps1m();
        assertTrue(slow < baseline,
                "expected slow EMA < baseline (" + baseline + "), got " + slow);
        assertTrue(slow < 20.0);
        assertTrue(slow >= 0.0);
    }

    /** TPS is clamped to {@code [0, 20]} even on a near-zero delta. */
    @Test
    void zero_delta_is_ignored() {
        AtomicLong clock = new AtomicLong(1_000L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick();
        // Same clock value - delta=0, must be a no-op (no EMA emitted).
        b.tick();
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
    }

    /** Negative deltas (clock regression) are ignored. */
    @Test
    void negative_delta_is_ignored() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick(); // seed at 1e9
        clock.set(500_000_000L); // regression
        b.tick();
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
    }

    /** PlayerCount / softCap delegate to suppliers and clamp negatives to 0. */
    @Test
    void player_and_cap_delegate_to_suppliers() {
        AtomicLong clock = new AtomicLong(0L);
        AtomicInteger pc = new AtomicInteger(7);
        AtomicInteger cap = new AtomicInteger(20);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, pc::get, cap::get);
        assertEquals(7, b.playerCount());
        assertEquals(20, b.softCap());

        pc.set(-3);
        cap.set(-1);
        assertEquals(0, b.playerCount());
        assertEquals(0, b.softCap());
    }

    /** Throwing suppliers degrade gracefully to 0 instead of propagating. */
    @Test
    void throwing_suppliers_degrade_to_zero() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(
                clock::get,
                () -> { throw new RuntimeException("boom"); },
                () -> { throw new RuntimeException("boom"); });
        assertEquals(0, b.playerCount());
        assertEquals(0, b.softCap());
    }

    /** Fabric is single-region: foliaRegions() inherits empty-list default. */
    @Test
    void folia_regions_is_empty_by_default() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
        assertTrue(b.foliaRegions().isEmpty());
    }

    /** Constructor rejects null clock / suppliers. */
    @Test
    void constructor_rejects_nulls() {
        AtomicLong clock = new AtomicLong(0L);
        // Cast disambiguates the two 3-arg overloads
        // (LongSupplier,IntSupplier,IntSupplier) vs (IntSupplier,IntSupplier,LongSupplier).
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding((LongSupplier) null, () -> 0, () -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding(clock::get, null, () -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding(clock::get, () -> 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding(clock::get, () -> 0, () -> 0, null));
    }

    /** Sustained 20 TPS keeps all three EMAs near-nominal. */
    @Test
    void sustained_nominal_ticks_keep_all_emas_nominal() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(
                clock::get, () -> 0, () -> 0, () -> 3_000_000L);
        b.tick();
        for (int i = 0; i < 1500; i++) {
            clock.addAndGet(50_000_000L);
            b.tick();
        }
        assertTrue(b.tps1m() > 19.9 && b.tps1m() <= 20.0);
        assertTrue(b.tps5m() > 19.9 && b.tps5m() <= 20.0);
        assertTrue(b.tps15m() > 19.9 && b.tps15m() <= 20.0);
        assertFalse(Double.isNaN(b.mspt()));
    }
}
