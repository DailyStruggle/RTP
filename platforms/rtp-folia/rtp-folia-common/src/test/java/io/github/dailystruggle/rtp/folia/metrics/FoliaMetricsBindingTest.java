package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.folia.tasks.RegionKey;
import org.junit.jupiter.api.DisplayName;
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

    /** Ticks {@code k} at {@code intervalNanos} for {@code ticks} samples starting at {@code now}. */
    private static void tickAt(FoliaMetricsBinding b, AtomicLong now, RegionKey k, long intervalNanos, int ticks) {
        for (int i = 0; i < ticks; i++) {
            now.addAndGet(intervalNanos);
            b.recordRegionTick(k);
        }
    }

    @Test
    @DisplayName("ADR-087: worstTps1m / worstMspt are UNSAMPLED before any tick loop is sampled")
    void worst_metrics_unsampled_when_empty() {
        FoliaMetricsBinding b = new FoliaMetricsBinding(System::nanoTime, () -> 0, () -> 0);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.worstTps1m(), 1e-9);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.worstMspt(), 1e-9);
    }

    @Test
    @DisplayName("ADR-087: worstTps1m is the minimum and worstMspt the maximum over regions and the global heartbeat")
    void worst_metrics_are_min_tps_and_max_mspt() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        RegionKey healthy = region(0, 0);
        RegionKey lagging = region(5, 5);

        b.tickGlobalSamplerForTest();
        b.recordRegionTick(healthy);
        b.recordRegionTick(lagging);
        now.addAndGet(50_000_000L);
        b.tickGlobalSamplerForTest();
        b.recordRegionTick(healthy);
        // Lagging region: 100 ms between ticks (10 TPS).
        now.addAndGet(50_000_000L);
        b.recordRegionTick(lagging);

        double worstTps = b.worstTps1m();
        assertTrue(worstTps > 9.0 && worstTps < 11.0, "worst TPS should track the lagging region, got " + worstTps);
        assertTrue(b.tps1m() > worstTps, "mean aggregation hides the lagging region; worst must not");
        double worstMspt = b.worstMspt();
        assertTrue(worstMspt > 95.0 && worstMspt < 105.0, "worst MSPT should be ~100 ms, got " + worstMspt);
    }

    @Test
    @DisplayName("ADR-087: healthy 50 ms tick intervals report worstMspt UNSAMPLED, not a value above maxMSPT")
    void healthy_interval_does_not_report_mspt() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        tickAt(b, now, region(0, 0), 50_000_000L, 5);
        assertEquals(MetricsSnapshot.UNSAMPLED, b.worstMspt(), 1e-9);
        assertTrue(b.worstTps1m() > 19.0);
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: regionTps1m is keyed by chunk >> 5 and UNSAMPLED for unknown regions")
    void region_tps_keyed_by_chunk_shift_five() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        UUID worldId = UUID.randomUUID();
        RegionKey lagging = new RegionKey(worldId, 1, 2);
        b.recordRegionTick(lagging);
        tickAt(b, now, lagging, 100_000_000L, 3); // 10 TPS

        assertTrue(b.regionalTicking());
        double tps = b.regionTps1m(worldId, 32, 64);
        assertTrue(tps > 9.0 && tps < 11.0, "expected ~10 TPS, got " + tps);
        assertEquals(tps, b.regionTps1m(worldId, 63, 95), 1e-9, "same 32x32-chunk region");
        assertTrue(Double.isNaN(b.regionTps1m(worldId, 0, 0)), "unknown region is UNSAMPLED");
        assertTrue(Double.isNaN(b.regionTps1m(worldId, 64, 64)));
        assertTrue(Double.isNaN(b.regionTps1m(UUID.randomUUID(), 32, 64)), "other world is UNSAMPLED");
        assertTrue(Double.isNaN(b.regionTps1m(null, 32, 64)));
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: negative chunk coordinates floor into their region")
    void region_tps_negative_chunks() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        UUID worldId = UUID.randomUUID();
        RegionKey k = new RegionKey(worldId, -1, -1);
        b.recordRegionTick(k);
        tickAt(b, now, k, 50_000_000L, 3);
        assertTrue(b.regionTps1m(worldId, -1, -32) > 19.0);
        assertTrue(Double.isNaN(b.regionTps1m(worldId, 0, -1)));
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: regionMspt reports only overrun intervals")
    void region_mspt_is_overrun_only() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        UUID worldId = UUID.randomUUID();
        RegionKey healthy = new RegionKey(worldId, 0, 0);
        RegionKey lagging = new RegionKey(worldId, 4, 0);
        b.recordRegionTick(healthy);
        b.recordRegionTick(lagging);
        for (int i = 0; i < 4; i++) {
            now.addAndGet(50_000_000L);
            b.recordRegionTick(healthy);
            if (i % 2 == 1) b.recordRegionTick(lagging); // 100 ms intervals
        }

        assertTrue(Double.isNaN(b.regionMspt(worldId, 0, 0)), "healthy 50 ms interval is not an overrun");
        double mspt = b.regionMspt(worldId, 128, 0);
        assertTrue(mspt > 95.0 && mspt < 105.0, "expected ~100 ms, got " + mspt);
        assertTrue(Double.isNaN(b.regionMspt(worldId, 512, 512)), "unknown region is UNSAMPLED");
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: a region idle past the eviction window reads UNSAMPLED")
    void region_readings_unsampled_when_idle() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        UUID worldId = UUID.randomUUID();
        RegionKey k = new RegionKey(worldId, 0, 0);
        b.recordRegionTick(k);
        tickAt(b, now, k, 200_000_000L, 3);
        assertTrue(b.regionTps1m(worldId, 0, 0) < 19.0);

        now.addAndGet(FoliaMetricsBinding.IDLE_EVICTION_NANOS + 1L);
        assertTrue(Double.isNaN(b.regionTps1m(worldId, 0, 0)));
        assertTrue(Double.isNaN(b.regionMspt(worldId, 0, 0)));
    }

    /** Fake probe timers: records ticks and cancellations instead of scheduling. */
    private static final class FakeTimers implements FoliaMetricsBinding.RegionTimers {
        final List<Runnable> ticks = new java.util.ArrayList<>();
        final List<Object> cancelled = new java.util.ArrayList<>();

        @Override
        public Object start(io.github.dailystruggle.rtp.api.world.RTPWorld<?> world, int cx, int cz, Runnable tick) {
            ticks.add(tick);
            return new Object();
        }

        @Override
        public void cancel(Object handle) {
            cancelled.add(handle);
        }
    }

    private static io.github.dailystruggle.rtp.api.world.RTPWorld<?> world(UUID id) {
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class);
        org.mockito.Mockito.when(w.id()).thenReturn(id);
        return w;
    }

    @Test
    @DisplayName("ADR-087: an observed region gets one probe whose ticks feed its sampler")
    void observed_region_probe_feeds_sampler() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());

        b.observeRegion(w, 0, 0);
        b.observeRegion(w, 3, 7); // same 32x32-chunk region: no second probe
        assertEquals(1, b.probeCount());
        assertEquals(1, timers.ticks.size());

        Runnable probe = timers.ticks.get(0);
        probe.run();
        now.addAndGet(50_000_000L);
        probe.run();
        assertEquals(1, b.registrySize());
        assertTrue(b.worstTps1m() > 19.0);
        assertEquals(1, b.foliaRegions().size());
    }

    @Test
    @DisplayName("ADR-087: a probe cancels itself once no RTP dispatch was observed for its idle window")
    void idle_probe_cancels_itself() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0, timers);
        b.observeRegion(world(UUID.randomUUID()), 0, 0);
        Runnable probe = timers.ticks.get(0);

        now.addAndGet(FoliaMetricsBinding.PROBE_IDLE_NANOS + 1L);
        probe.run();
        assertEquals(0, b.probeCount());
        assertEquals(1, timers.cancelled.size());
    }

    @Test
    @DisplayName("ADR-087: a probe gap from a region reload re-seeds instead of reporting lag")
    void probe_gap_reseeds_sampler() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());
        b.observeRegion(w, 0, 0);
        Runnable probe = timers.ticks.get(0);
        probe.run();
        now.addAndGet(50_000_000L);
        probe.run();

        now.addAndGet(FoliaMetricsBinding.PROBE_GAP_NANOS + 1L); // still inside the idle window
        probe.run();
        now.addAndGet(50_000_000L);
        probe.run();
        assertEquals(1, b.probeCount());
        // Folding the 5 s gap into the 1m EMA would drop TPS to ~18.4.
        assertTrue(b.worstTps1m() > 19.0, "reload gap must not be folded into TPS, got " + b.worstTps1m());
    }

    @Test
    @DisplayName("ADR-087: a silent probe (region unloaded) is replaced on the next observed dispatch")
    void silent_probe_is_replaced_on_observe() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());
        b.observeRegion(w, 0, 0);
        timers.ticks.get(0).run();

        now.addAndGet(FoliaMetricsBinding.PROBE_GAP_NANOS + 1L);
        b.observeRegion(w, 0, 0);
        assertEquals(2, timers.ticks.size(), "a fresh probe is started");
        assertEquals(1, timers.cancelled.size(), "the silent probe is cancelled");
        assertEquals(1, b.probeCount());
    }

    @Test
    @DisplayName("ADR-087: concurrently probed regions are capped")
    void probes_are_capped() {
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(System::nanoTime, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());
        for (int i = 0; i < FoliaMetricsBinding.MAX_REGION_PROBES + 10; i++) {
            b.observeRegion(w, i << 5, 0);
        }
        assertEquals(FoliaMetricsBinding.MAX_REGION_PROBES, b.probeCount());
    }

    @Test
    @DisplayName("ADR-087: observeRegion via MetricsBinding interface and spark-like decorator starts probe")
    void observe_region_via_interface_and_decorator() {
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(System::nanoTime, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());

        // Call via MetricsBinding interface reference with Object world
        MetricsBinding mb = b;
        mb.observeRegion((Object) w, 0, 0);
        assertEquals(1, b.probeCount());
        assertEquals(1, timers.ticks.size());

        // Call via a decorating MetricsBinding
        MetricsBinding decorator = new MetricsBinding() {
            @Override
            public void observeRegion(Object world, int cx, int cz) {
                b.observeRegion(world, cx, cz);
            }
        };
        decorator.observeRegion((Object) w, 64, 0);
        assertEquals(2, b.probeCount());
        assertEquals(2, timers.ticks.size());
    }

    @Test
    @DisplayName("Transient region with < 20 samples does not distort mature mean TPS")
    void transient_region_excluded_when_mature_sampler_present() {
        SteppingClock clk = new SteppingClock(0L, 50_000_000L); // ~20 TPS
        FoliaMetricsBinding b = new FoliaMetricsBinding(clk::peek, () -> 0, () -> 0);

        // Mature the global heartbeat with 25 ticks (24 valid delta samples >= MIN_SAMPLES)
        for (int i = 0; i < 25; i++) {
            clk.advance();
            b.tickGlobalSamplerForTest();
        }
        double baselineTps = b.tps1m();
        assertTrue(baselineTps > 19.5 && baselineTps <= 20.0, "mature baseline should be ~20 TPS, got " + baselineTps);

        // A newly probed region experiences startup jitter: delta = 70ms (14.3 TPS), only 1 sample
        RegionKey transientRegion = region(10, 10);
        clk.advance();
        b.recordRegionTick(transientRegion); // seed
        clk.now.addAndGet(70_000_000L);
        b.recordRegionTick(transientRegion); // 1 sample (< 20)

        // Immature region must be excluded from aggregate mean TPS
        double tpsWithTransient = b.tps1m();
        assertEquals(baselineTps, tpsWithTransient, 1e-6, "transient region must not distort mature mean TPS");

        // Now advance and tick the region 25 times at 70ms (now mature >= MIN_SAMPLES)
        for (int i = 0; i < 25; i++) {
            clk.now.addAndGet(70_000_000L);
            b.recordRegionTick(transientRegion);
        }

        // Now that the region is mature, it is factored into the mean weighted by tick count
        double tpsWithMatureLag = b.tps1m();
        assertTrue(tpsWithMatureLag < baselineTps, "mature degraded region should pull down the mean");
    }

    @Test
    @DisplayName("foliaRegions exposes sampleCount and ageMillis")
    void foliaRegions_exposes_sampleCount_and_age() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);
        RegionKey k = region(1, 1);

        b.recordRegionTick(k); // seed at t=1000ms
        for (int i = 0; i < 5; i++) {
            now.addAndGet(50_000_000L);
            b.recordRegionTick(k);
        }

        List<FoliaRegionSample> list = b.foliaRegions();
        assertEquals(1, list.size());
        FoliaRegionSample sample = list.get(0);
        assertEquals(5L, sample.sampleCount);
        assertEquals(250L, sample.ageMillis);
    }

    @Test
    @DisplayName("ADR-087: inactive regions (> 2s without a tick) are excluded from aggregate mean TPS")
    void aggregate_excludes_inactive_regions_past_2s_window() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0);

        // Mature global heartbeat at 20 TPS
        for (int i = 0; i < 25; i++) {
            now.addAndGet(50_000_000L);
            b.tickGlobalSamplerForTest();
        }
        double baselineTps = b.tps1m();
        assertTrue(baselineTps > 19.5);

        // A region had load: 25 ticks at 100ms (10 TPS)
        RegionKey region = region(5, 5);
        b.recordRegionTick(region);
        for (int i = 0; i < 25; i++) {
            now.addAndGet(100_000_000L);
            b.recordRegionTick(region);
        }

        // While active, it pulls down the mean
        double activeMeanTps = b.tps1m();
        assertTrue(activeMeanTps < baselineTps, "active degraded region pulls down mean");

        // The region unloads and stops ticking; 3 seconds pass (> 2s active window)
        now.addAndGet(3L * 1_000_000_000L);
        b.tickGlobalSamplerForTest(); // global keeps ticking

        // Inactive region is now excluded from the aggregate mean
        double recoveredMeanTps = b.tps1m();
        assertEquals(baselineTps, recoveredMeanTps, 1e-6,
                "inactive region must be excluded from aggregate mean TPS");
    }

    @Test
    @DisplayName("ADR-087: evictIdle cancels and removes probes on unloaded chunks")
    void evictIdle_cancels_and_removes_unloaded_probe() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());

        b.observeRegion(w, 0, 0);
        assertEquals(1, b.probeCount());
        timers.ticks.get(0).run();

        // Region unloads: probe never ticks again; time advances past PROBE_IDLE_NANOS
        now.addAndGet(FoliaMetricsBinding.PROBE_IDLE_NANOS + 1L);

        // Inspection/aggregation triggers evictIdle()
        b.tps1m();

        assertEquals(0, b.probeCount(), "unloaded probe must be removed");
        assertEquals(1, timers.cancelled.size(), "unloaded probe handle must be cancelled");
        assertEquals(0, b.registrySize(), "unloaded sampler must be cleaned up");
    }

    @Test
    @DisplayName("ADR-087: replaced silent probe does not fold reload gap into TPS")
    void silent_probe_replacement_does_not_blend_gap_into_tps() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        FakeTimers timers = new FakeTimers();
        FoliaMetricsBinding b = new FoliaMetricsBinding(now::get, () -> 0, () -> 0, timers);
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> w = world(UUID.randomUUID());

        b.observeRegion(w, 0, 0);
        timers.ticks.get(0).run();
        now.addAndGet(50_000_000L);
        timers.ticks.get(0).run();

        // 30 seconds pass (region unloaded between spiral passes)
        now.addAndGet(30L * 1_000_000_000L);
        b.observeRegion(w, 0, 0); // replaces silent probe with fresh probe

        assertEquals(2, timers.ticks.size());
        Runnable probe2 = timers.ticks.get(1);
        probe2.run(); // first tick of replacement probe (seeds)
        now.addAndGet(50_000_000L);
        probe2.run(); // second tick

        double worstTps = b.worstTps1m();
        assertTrue(worstTps > 19.9, "reload gap must not depress TPS, got " + worstTps);
    }
}
