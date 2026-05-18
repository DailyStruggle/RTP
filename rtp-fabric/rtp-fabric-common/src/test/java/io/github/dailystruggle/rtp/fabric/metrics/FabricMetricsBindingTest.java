package io.github.dailystruggle.rtp.fabric.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Two ticks 50 ms apart yield ~20 TPS / ~50 ms MSPT. */
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

        double mspt = b.mspt();
        assertTrue(mspt > 49.0 && mspt < 51.0, "expected ~50 ms mspt, got " + mspt);
    }

    /**
     * Sustained 100 ms tick deltas (10 TPS) converge the 1-minute EMA
     * downward over many ticks. We don't require full convergence — just
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
        // Same clock value — delta=0, must be a no-op (no EMA emitted).
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
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding(null, () -> 0, () -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding(clock::get, null, () -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FabricMetricsBinding(clock::get, () -> 0, null));
    }

    /** Sustained 20 TPS keeps all three EMAs near-nominal. */
    @Test
    void sustained_nominal_ticks_keep_all_emas_nominal() {
        AtomicLong clock = new AtomicLong(0L);
        FabricMetricsBinding b = new FabricMetricsBinding(clock::get, () -> 0, () -> 0);
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
