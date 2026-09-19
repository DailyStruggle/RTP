package io.github.dailystruggle.rtp.neoforge.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NeoForgeMetricsBinding} (REQ-RTP-OBS-001). Drives the
 * binding with a synthetic nano-clock so EMA convergence is deterministic;
 * player-count, soft-cap, and tick-work are stubbed via the test-seam
 * constructor so no {@code MinecraftServer} runtime is required.
 *
 * <p>Sister to {@code FabricMetricsBindingTest} - both runtimes share the
 * single-region vanilla tick loop and the same MSPT contract.
 */
final class NeoForgeMetricsBindingTest {

    /** Fresh binding reports {@link MetricsSnapshot#UNSAMPLED} for every scalar. */
    @Test
    void empty_binding_reports_unsampled_scalars() {
        AtomicLong clock = new AtomicLong(0L);
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(clock::get, () -> 0, () -> 0);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps5m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps15m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.mspt(), 1e-9);
    }

    /** TPS still derives from the tick interval: 50 ms apart yields ~20 TPS. */
    @Test
    void tps_derives_from_tick_interval() {
        AtomicLong clock = new AtomicLong(0L);
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick(); // seed
        clock.addAndGet(50_000_000L);
        b.tick();
        double tps = b.tps1m();
        assertTrue(tps > 19.5 && tps <= 20.0, "expected ~20 TPS, got " + tps);
    }

    /**
     * MSPT reflects in-tick work, not the tick interval. An idle server ticks
     * every 50 ms while doing ~4 ms of work; MSPT must report ~4, well below
     * the 50 ms interval floor that previously pinned the reading.
     */
    @Test
    void mspt_reports_work_duration_not_tick_interval() {
        AtomicLong clock = new AtomicLong(0L);
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(
                clock::get, () -> 0, () -> 0, () -> 4_000_000L);
        b.tick(); // seed
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(50_000_000L);
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
        AtomicLong work = new AtomicLong(45_000_000L);
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(
                clock::get, () -> 0, () -> 0, work::get);
        b.tick(); // seed
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(50_000_000L);
            b.tick();
        }
        double spiked = b.mspt();
        assertTrue(spiked > 40.0, "expected a high mspt during the spike, got " + spiked);

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
        NeoForgeMetricsBinding unavailable = new NeoForgeMetricsBinding(
                clock::get, () -> 0, () -> 0, () -> 0L);
        NeoForgeMetricsBinding throwing = new NeoForgeMetricsBinding(
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

    /** Zero and negative tick intervals are ignored (no EMA emitted). */
    @Test
    void non_progressing_clock_is_ignored() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(clock::get, () -> 0, () -> 0);
        b.tick();
        b.tick(); // delta = 0
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
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
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(clock::get, pc::get, cap::get);
        assertEquals(7, b.playerCount());
        assertEquals(20, b.softCap());

        pc.set(-3);
        cap.set(-1);
        assertEquals(0, b.playerCount());
        assertEquals(0, b.softCap());
    }

    /** NeoForge is single-region: foliaRegions() inherits empty-list default. */
    @Test
    void folia_regions_is_empty_by_default() {
        AtomicLong clock = new AtomicLong(0L);
        NeoForgeMetricsBinding b = new NeoForgeMetricsBinding(clock::get, () -> 0, () -> 0);
        assertTrue(b.foliaRegions().isEmpty());
    }

    /** Constructor rejects null clock / suppliers. */
    @Test
    void constructor_rejects_nulls() {
        AtomicLong clock = new AtomicLong(0L);
        // Cast disambiguates the two 3-arg overloads
        // (LongSupplier,IntSupplier,IntSupplier) vs (IntSupplier,IntSupplier,LongSupplier).
        assertThrows(IllegalArgumentException.class,
                () -> new NeoForgeMetricsBinding((LongSupplier) null, () -> 0, () -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NeoForgeMetricsBinding(clock::get, null, () -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NeoForgeMetricsBinding(clock::get, () -> 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new NeoForgeMetricsBinding(clock::get, () -> 0, () -> 0, null));
    }
}
