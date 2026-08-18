package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.folia.tasks.RegionKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FoliaMetricsBinding}. Drives the binding with a
 * synthetic nano-clock so EMA convergence is deterministic; player-count
 * and soft-cap are stubbed via the test-seam constructor so no Bukkit
 * runtime is required.
 *
 * <p>RTP-static config readers ({@code readIncludeRegions} /
 * {@code readAggregation}) degrade to their documented defaults when
 * {@code RTP.getInstance()} is null, which is exactly the state these
 * tests run under - so the defaults
 * ({@code includeRegions=true}, {@code tps=mean}, {@code mspt=max}) are
 * what the assertions below assume.
 */
final class FoliaMetricsBindingTest {

    /** Mints fresh {@link RegionKey}s with unique world ids so map collisions are impossible. */
    private static RegionKey region(int rx, int rz) {
        return new RegionKey(UUID.randomUUID(), rx, rz);
    }

    /** Drives {@link FoliaMetricsBinding} forward by {@code stepNanos} on each {@code .getAsLong()} call. */
    private static final class SteppingClock {
        private final AtomicLong now;
        private final long stepNanos;
        SteppingClock(long start, long stepNanos) {
            this.now = new AtomicLong(start);
            this.stepNanos = stepNanos;
        }
        long advance() { return now.addAndGet(stepNanos); }
        long peek() { return now.get(); }
    }

    /**
     * A fresh binding with no recorded ticks reports {@link MetricsSnapshot#UNSAMPLED}
     * scalars and an empty {@link FoliaMetricsBinding#foliaRegions()} list.
     */
    @Test
    void empty_binding_reports_unsampled_scalars_and_empty_regions() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps5m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps15m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.mspt(), 1e-9);
        assertTrue(b.foliaRegions().isEmpty());
        assertEquals(0, b.registrySize());
    }

    /**
     * Decoupling regression: the steady global heartbeat sampler (driven in
     * production from the global region scheduler, here via the test seam)
     * keeps the scalar TPS / MSPT sampled even when NO per-region sampler has
     * data - i.e. with zero RTP traffic and therefore no
     * {@code FoliaRegionProcessor} pulses. {@code foliaRegions()} stays empty
     * because the global sampler is not a real region.
     */
    @Test
    void global_heartbeat_keeps_scalars_sampled_with_no_region_traffic() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L); // ~20 TPS
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);

        clk.advance();
        b.tickGlobalSamplerForTest();  // seed
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);

        clk.advance();
        b.tickGlobalSamplerForTest();  // first real sample

        double tps = b.tps1m();
        assertNotEquals(MetricsSnapshot.UNSAMPLED, tps);
        assertTrue(tps > 19.0 && tps <= 20.0, "expected ~20 TPS, got " + tps);
        assertNotEquals(MetricsSnapshot.UNSAMPLED, b.mspt());

        // No real per-region sampler was recorded, so the per-region list and
        // registry stay empty even though the scalars are sampled.
        assertEquals(0, b.registrySize());
        assertTrue(b.foliaRegions().isEmpty());
    }

    /**
     * First tick for a new key seeds a sampler (no EMA yet); second tick
     * produces a valid EMA. {@code regionId} is stable across reads.
     */
    @Test
    void recordRegionTick_seeds_then_emits_valid_ema_and_stable_id() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L); // ~20 TPS
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);
        RegionKey k = region(0, 0);

        clk.advance();
        b.recordRegionTick(k);  // seed
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);

        clk.advance();
        b.recordRegionTick(k);  // first real sample
        assertEquals(1, b.registrySize());

        double tps = b.tps1m();
        assertNotEquals(MetricsSnapshot.UNSAMPLED, tps);
        assertTrue(tps > 19.0 && tps <= 20.0, "expected ~20 TPS, got " + tps);

        List<FoliaRegionSample> regions = b.foliaRegions();
        assertEquals(1, regions.size());
        String id1 = regions.get(0).regionId;
        assertNotNull(id1);
        assertTrue(id1.startsWith("region-"));

        // Re-read returns same id (lifetime-stable).
        assertEquals(id1, b.foliaRegions().get(0).regionId);
    }

    /**
     * C1.2b: {@code recordRegionTick(key, depth)} carries the per-region
     * queue depth into {@link FoliaRegionSample#queueDepth}. Subsequent
     * ticks overwrite the prior value (latest wins).
     */
    @Test
    void recordRegionTick_carries_queue_depth_per_region() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);
        RegionKey k = region(3, 4);

        clk.advance();
        b.recordRegionTick(k, 5);  // seed with depth=5
        clk.advance();
        b.recordRegionTick(k, 9);  // real sample, depth=9 (latest)

        List<FoliaRegionSample> regions = b.foliaRegions();
        assertEquals(1, regions.size());
        assertEquals(9, regions.get(0).queueDepth);

        // Latest-wins on subsequent observation.
        clk.advance();
        b.recordRegionTick(k, 2);
        assertEquals(2, b.foliaRegions().get(0).queueDepth);

        // Negative is clamped to 0.
        clk.advance();
        b.recordRegionTick(k, -1);
        assertEquals(0, b.foliaRegions().get(0).queueDepth);
    }

    /** A null {@link RegionKey} is a no-op (does not register a sampler). */
    @Test
    void recordRegionTick_null_key_is_noop() {
        FoliaMetricsBinding b = new FoliaMetricsBinding(System::nanoTime, () -> 0, () -> 0);
        b.recordRegionTick(null);
        assertEquals(0, b.registrySize());
        assertTrue(b.foliaRegions().isEmpty());
    }

    /**
     * Idle eviction: a sampler that has not been ticked for longer than
     * {@link FoliaMetricsBinding#IDLE_EVICTION_NANOS} is removed on the
     * next aggregation read.
     */
    @Test
    void idle_eviction_drops_stale_samplers() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        RegionKey k = region(1, 2);

        b.recordRegionTick(k);
        now.addAndGet(50_000_000L);
        b.recordRegionTick(k);
        assertEquals(1, b.registrySize());

        // Jump past the idle window without ticking; next aggregation evicts.
        now.addAndGet(FoliaMetricsBinding.IDLE_EVICTION_NANOS + 1L);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.tps1m(), 1e-9);
        assertEquals(0, b.registrySize());
        assertTrue(b.foliaRegions().isEmpty());
    }

    /**
     * Default aggregation: scalar TPS is the mean of per-region EMAs. With
     * two regions converged at the same rate this collapses to that rate.
     */
    @Test
    void scalar_tps_aggregation_default_mean_two_regions() {
        // Two regions ticked in lock-step at 50ms intervals each - both
        // converge to ~20 TPS, so mean aggregation collapses to ~20.
        AtomicLong now = new AtomicLong(0L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        RegionKey a = region(0, 0);
        RegionKey c = region(1, 1);

        // Seed: both regions observe their first tick at t=0.
        b.recordRegionTick(a);
        b.recordRegionTick(c);
        // First real sample: advance 50ms, tick both regions.
        now.set(50_000_000L);
        b.recordRegionTick(a);
        b.recordRegionTick(c);

        assertEquals(2, b.registrySize());
        double tps = b.tps1m();
        assertNotEquals(MetricsSnapshot.UNSAMPLED, tps);
        assertTrue(tps > 19.0 && tps <= 20.0, "mean aggregation gave " + tps);
    }

    /**
     * {@link FoliaMetricsBinding#playerCount()} / {@link FoliaMetricsBinding#softCap()}
     * delegate to the supplied {@link java.util.function.IntSupplier}s.
     */
    @Test
    void server_wide_fields_delegate_to_suppliers() {
        FoliaMetricsBinding b = new FoliaMetricsBinding(System::nanoTime, () -> 7, () -> 64);
        assertEquals(7, b.playerCount());
        assertEquals(64, b.softCap());
    }

    /**
     * With samplers present and at least one valid EMA, {@code foliaRegions()}
     * exposes a non-empty, unmodifiable list. The returned list reflects
     * the regions currently registered (post idle-eviction).
     */
    @Test
    void foliaRegions_is_unmodifiable_when_populated() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);
        RegionKey k = region(3, 4);
        clk.advance(); b.recordRegionTick(k);
        clk.advance(); b.recordRegionTick(k);

        List<FoliaRegionSample> regions = b.foliaRegions();
        assertEquals(1, regions.size());
        try {
            regions.add(new FoliaRegionSample("region-x", 20.0, 50.0, 0, 0));
            assertFalse(true, "foliaRegions() should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    /**
     * Distinct {@link RegionKey}s receive distinct ids, and the id sequence
     * is monotonically increasing (no reuse across the binding lifetime).
     */
    @Test
    void distinct_regions_get_distinct_ids() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);
        RegionKey a = region(0, 0);
        RegionKey c = region(1, 0);

        clk.advance(); b.recordRegionTick(a);
        clk.advance(); b.recordRegionTick(c);
        clk.advance(); b.recordRegionTick(a);
        clk.advance(); b.recordRegionTick(c);

        List<FoliaRegionSample> regions = b.foliaRegions();
        assertEquals(2, regions.size());
        String id0 = regions.get(0).regionId;
        String id1 = regions.get(1).regionId;
        assertNotEquals(id0, id1);
        assertTrue(id0.startsWith("region-"));
        assertTrue(id1.startsWith("region-"));
    }
}
