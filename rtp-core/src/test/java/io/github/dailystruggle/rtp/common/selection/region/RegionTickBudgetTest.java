package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import io.github.dailystruggle.rtp.common.tools.CfDiag;
import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import io.github.dailystruggle.rtp.common.tools.ServerHealthGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Region.execute tick budget: promotion fan-out cap, minTPS/maxMSPT gate, sync-pipe verify")
class RegionTickBudgetTest {

    @TempDir
    Path tempDir;

    private TrackedMockWorld world;

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());
        world = new TrackedMockWorld("tick_budget_world");
        accessor.addWorld(world);
        ServerHealthGate.resetForTesting();
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
        MiscTasks.sync().clear();
        MiscTasks.sync().start();
        if (MiscTasks.backgroundSync() != null) {
            MiscTasks.backgroundSync().clear();
            MiscTasks.backgroundSync().start();
        }
        io.github.dailystruggle.rtp.common.governor.ComputeGovernor.active().reset();
    }

    @AfterEach
    void tearDown() {
        ServerHealthGate.resetForTesting();
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
        MiscTasks.sync().clear();
        if (MiscTasks.backgroundSync() != null) {
            MiscTasks.backgroundSync().clear();
        }
        io.github.dailystruggle.rtp.common.governor.ComputeGovernor.active().reset();
    }

    /** Cold cache full of candidates, hot cap far above the per-pulse promotion cap. */
    private Region regionWithColdCandidates(String name, int coldCount) {
        RegionSettings settings = new RegionSettings(
                name, world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false, coldCount, 0L, 0L, 64, 0.0, 1L, "", false);
        Region region = new Region(name, settings);
        region.cachePipeline.stop();
        for (int i = 0; i < coldCount; i++) {
            region.queueManager.unkeptLocations.offer(
                    new RTPLocation(new RTPCoords(world.name(), (i << 4) + 8, 64, 8), 1L, null));
        }
        return region;
    }

    @Test
    @DisplayName("a large hot deficit starts at most MAX_PROMOTIONS_PER_PULSE chunk loads per pulse")
    void promotionFanOutIsCappedPerPulse() {
        Region region = regionWithColdCandidates("fanout_reg", 40);
        long before = CfDiag.regionDeficitDispatch.sum();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));

        long dispatched = CfDiag.regionDeficitDispatch.sum() - before;
        assertTrue(dispatched >= 1, "the pulse must make progress");
        assertTrue(dispatched <= Region.MAX_PROMOTIONS_PER_PULSE,
                "dispatched " + dispatched + " promotions in one pulse");
    }

    @Test
    @DisplayName("REQ-RTP-S-005: on the tick thread, promotion verify is queued on the budgeted sync pipe")
    void verifyIsDeferredToSyncPipeOnTickThread() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RTPRuntime.serverAccessor().isPrimaryThread(),
                "harness runs callbacks off the primary thread");
        Region region = regionWithColdCandidates("verify_reg", 4);
        long before = MiscTasks.sync().size();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));

        assertTrue(MiscTasks.sync().size() > before,
                "chunk-load callbacks must hand verify to miscSyncTasks, not run it inline");
        assertTrue(region.inFlightCalculations.get() > 0, "promotions stay in flight until the sync pipe runs");

        MiscTasks.sync().execute(Long.MAX_VALUE);
        assertEquals(0, region.inFlightCalculations.get(), "draining the sync pipe settles every promotion");
    }

    @Test
    @DisplayName("ADR-087 I4 & I5: executePlayerFacing promotes cold shortfall for waiting player even when S_eff = 0 and c_r = 0")
    void executePlayerFacingPromotesForWaitingPlayerWhenBackgroundThrottled() {
        Region region = regionWithColdCandidates("shortfall_reg", 4);
        java.util.UUID waitingId = java.util.UUID.randomUUID();
        ((MockRTPServerAccessor) RTPRuntime.serverAccessor()).addPlayer(
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                        waitingId, "WaitingPlayer",
                        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));
        region.queueManager.playerQueue.add(waitingId);

        // Put governor into actuating state with B_cpu < 1 ms (S_eff = 0, p_eff = 0) and zero credit (c_r = 0)
        io.github.dailystruggle.rtp.common.governor.ComputeSignals throttled =
                io.github.dailystruggle.rtp.common.governor.ComputeSignals.builder()
                        .autoTune(true)
                        .regionalTicking(false)
                        .maxMspt(45.0)
                        .mspt(io.github.dailystruggle.rtp.common.governor.ComputeSignals.SampledSignal.fresh(35.5, TimeUnit.SECONDS.toNanos(10)))
                        .region(new io.github.dailystruggle.rtp.common.governor.ComputeSignals.RegionDemand(
                                "shortfall_reg", 0L, 0.0, 0, 10, 0, true))
                        .build();
        io.github.dailystruggle.rtp.common.governor.ComputeBudget b =
                io.github.dailystruggle.rtp.common.governor.ComputeGovernor.active().evaluate(throttled, TimeUnit.SECONDS.toNanos(1));
        assertEquals(0L, b.backgroundSyncNanos());
        assertEquals(0, b.promotionsPerTurn());
        assertEquals(0, b.unitsPerTurn("shortfall_reg"));

        long before = CfDiag.regionDeficitDispatch.sum();
        region.executePlayerFacing(TimeUnit.MILLISECONDS.toNanos(25));

        long dispatched = CfDiag.regionDeficitDispatch.sum() - before;
        assertEquals(1L, dispatched, "executePlayerFacing must promote cold shortfall for the 1 waiting player");
        assertEquals(0L, MiscTasks.backgroundSync().size(),
                "waiting-player promotion verify must route to player-facing MiscTasks.sync(), not backgroundSync()");
    }

    @Test
    @DisplayName("minTPS gate: an unhealthy tick pauses cold->hot promotion; healthy resumes it")
    void unhealthyTickPausesPromotion() {
        Region region = regionWithColdCandidates("gate_reg", 20);
        ServerHealthGate.setReadingsForTesting(() -> 10.0, () -> 20.0);
        long before = CfDiag.regionDeficitDispatch.sum();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));

        assertEquals(0, CfDiag.regionDeficitDispatch.sum() - before, "no chunk loads while TPS is below minTPS");
        assertEquals(20, region.queueManager.unkeptLocations.size(), "cold candidates are left untouched");

        ServerHealthGate.setReadingsForTesting(() -> 20.0, () -> 20.0);
        region.execute(TimeUnit.MILLISECONDS.toNanos(25));
        assertTrue(CfDiag.regionDeficitDispatch.sum() - before > 0, "promotion resumes once the tick recovers");
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: candidates in a saturated region stay cold, others promote")
    void saturatedRegionCandidatesStayCold() {
        Region region = regionWithColdCandidates("breaker_reg", 8);
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler original = RTPRuntime.scheduler();
        // Chunks x < 4 belong to a backlogged region; x >= 4 are healthy.
        RTPRuntime.setScheduler(new SaturationScheduler(original, cx -> cx < 4));
        try {
            long before = CfDiag.regionDeficitDispatch.sum();
            region.execute(TimeUnit.SECONDS.toNanos(1));

            long dispatched = CfDiag.regionDeficitDispatch.sum() - before;
            assertEquals(4, dispatched, "only the healthy region's candidates start chunk loads");
            int stillCold = 0;
            for (int i = 0; i < region.queueManager.unkeptLocations.size(); i++) {
                RTPLocation l = region.queueManager.unkeptLocations.get(i);
                if (l != null && (l.coords().x() >> 4) < 4) stillCold++;
            }
            assertEquals(4, stillCold, "saturated-region candidates are kept, not dropped");
        } finally {
            RTPRuntime.setScheduler(original);
        }
    }

    @Test
    @DisplayName("generation budget full (unprioritised chunk system): no promotion starts and candidates stay cold")
    void fullGenerationBudgetHoldsPromotionBack() {
        Region region = regionWithColdCandidates("budget_reg", 8);
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler original = RTPRuntime.scheduler();
        java.util.concurrent.atomic.AtomicBoolean full = new java.util.concurrent.atomic.AtomicBoolean(true);
        RTPRuntime.setScheduler(new SaturationScheduler(original, cx -> false, full::get));
        try {
            long before = CfDiag.regionDeficitDispatch.sum();
            region.execute(TimeUnit.SECONDS.toNanos(1));
            assertEquals(0, CfDiag.regionDeficitDispatch.sum() - before, "no chunk loads while the budget is full");
            assertEquals(8, region.queueManager.unkeptLocations.size(), "cold candidates are kept, not dropped");

            full.set(false);
            region.execute(TimeUnit.SECONDS.toNanos(1));
            assertTrue(CfDiag.regionDeficitDispatch.sum() - before > 0, "promotion resumes once loads drain");
        } finally {
            RTPRuntime.setScheduler(original);
        }
    }

    @Test
    @DisplayName("REQ-CORE-F-002: cold fill reserves no more queued tasks than the shared generation cap")
    void coldFillQueueIsBoundedByGenerationBudget() {
        GenerationBudget.configureServerCap(() -> 2);
        Region region = regionWithColdCandidates("cold_fill_budget_reg", 8);
        region.queueManager.unkeptLocations.clear();
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler original = RTPRuntime.scheduler();
        RTPRuntime.setScheduler(new SaturationScheduler(
                original, cx -> false, () -> GenerationBudget.server().saturated()));
        try {
            region.execute(TimeUnit.SECONDS.toNanos(1));

            assertEquals(2, region.cachePipeline.size(), "only two reserved cache tasks may be queued");
            assertEquals(2, GenerationBudget.server().inFlight());
        } finally {
            region.cachePipeline.stop();
            RTPRuntime.setScheduler(original);
        }
    }

    @Test
    @DisplayName("REQ-CORE-F-002: a heap shed pauses refill until 30 seconds after pressure clears")
    void heapShedRefillCooldownIsBounded() {
        long clearedAt = 1_000L;

        assertTrue(Region.shedRefillCooldownActive(Long.MIN_VALUE, clearedAt),
                "refill remains paused while pressure has not cleared");
        assertTrue(Region.shedRefillCooldownActive(clearedAt,
                clearedAt + Region.SHED_REFILL_COOLDOWN_NANOS - 1L));
        assertFalse(Region.shedRefillCooldownActive(clearedAt,
                clearedAt + Region.SHED_REFILL_COOLDOWN_NANOS));
    }

    @Test
    @DisplayName("REQ-CORE-F-002: cancelling a queued cache task releases its admission reservation")
    void cancellingCacheTaskReleasesGenerationSlot() {
        Region region = regionWithColdCandidates("cancel_slot_reg", 1);
        GenerationBudget.Reservation slot = GenerationBudget.server().reserve();
        assertNotNull(slot);
        RegionCacheTask task = new RegionCacheTask(region, TimeUnit.SECONDS.toNanos(1));
        task.holdGenerationSlot(slot);

        task.setCancelled(true);

        assertEquals(0, GenerationBudget.server().inFlight());
    }

    @Test
    @DisplayName("REQ-CORE-F-002: reservations cap promotions before async load accounting begins")
    void pendingLoadsCannotPassTheGenerationCap() {
        GenerationBudget.configureServerCap(() -> 2);
        PendingTrackedMockWorld pendingWorld = new PendingTrackedMockWorld("pending_budget_world");
        world = pendingWorld;
        ((MockRTPServerAccessor) RTPRuntime.serverAccessor()).addWorld(world);
        Region region = regionWithColdCandidates("pending_budget_reg", 8);
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler original = RTPRuntime.scheduler();
        RTPRuntime.setScheduler(new SaturationScheduler(
                original, cx -> false, () -> GenerationBudget.server().saturated()));
        try {
            long before = CfDiag.regionDeficitDispatch.sum();

            region.execute(TimeUnit.SECONDS.toNanos(1));

            assertEquals(2, CfDiag.regionDeficitDispatch.sum() - before,
                    "only two promotions may be admitted while adapter futures are pending");
            assertEquals(2, pendingWorld.pendingLoads.size());
            assertEquals(2, GenerationBudget.server().inFlight(),
                    "admission reservations count before the adapter load counter advances");
        } finally {
            pendingWorld.failPendingLoads();
            RTPRuntime.setScheduler(original);
            GenerationBudget.configureServerCap(null);
        }
    }

    @Test
    @DisplayName("generation budget full: the cold fill queues no new cache tasks until loads drain")
    void fullGenerationBudgetHoldsColdFillBack() {
        // Cold cap 8, nothing cached, no hot cap: the pulse's only chunk work is the cold fill.
        RegionSettings settings = new RegionSettings(
                "budget_fill_reg", world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false, 8L, 0L, 0L, 0, 0.0, 1L, "", false);
        Region region = new Region("budget_fill_reg", settings);
        region.cachePipeline.stop();
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler original = RTPRuntime.scheduler();
        java.util.concurrent.atomic.AtomicBoolean full = new java.util.concurrent.atomic.AtomicBoolean(true);
        RTPRuntime.setScheduler(new SaturationScheduler(original, cx -> false, full::get));
        try {
            region.execute(TimeUnit.MILLISECONDS.toNanos(25));
            assertEquals(0, region.cachePipeline.size(), "no cold-fill work while the budget is full");

            full.set(false);
            region.execute(TimeUnit.MILLISECONDS.toNanos(25));
            assertTrue(region.cachePipeline.size() > 0, "cold fill resumes once loads drain");
        } finally {
            RTPRuntime.setScheduler(original);
        }
    }

    @Test
    @DisplayName("stalled tick: a server tick running past STALL_MS pauses promotion even with healthy TPS readings")
    void stalledTickPausesPromotion() {
        Region region = regionWithColdCandidates("stall_reg", 8);
        ServerHealthGate.setReadingsForTesting(() -> 20.0, () -> 20.0);
        ServerHealthGate.setTickStartNanosForTesting(System.nanoTime() - TimeUnit.SECONDS.toNanos(2));
        long before = CfDiag.regionDeficitDispatch.sum();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));
        assertEquals(0, CfDiag.regionDeficitDispatch.sum() - before, "no chunk loads while the tick is stuck");

        ServerHealthGate.tickFinished();
        region.execute(TimeUnit.MILLISECONDS.toNanos(25));
        assertTrue(CfDiag.regionDeficitDispatch.sum() - before > 0, "promotion resumes once the tick finishes");
    }

    /** Delegating scheduler that reports chosen chunks as saturated. */
    private static final class SaturationScheduler implements io.github.dailystruggle.rtp.api.scheduling.RTPScheduler {
        private final io.github.dailystruggle.rtp.api.scheduling.RTPScheduler d;
        private final java.util.function.IntPredicate saturatedX;
        private final java.util.function.BooleanSupplier generationFull;

        SaturationScheduler(io.github.dailystruggle.rtp.api.scheduling.RTPScheduler d,
                            java.util.function.IntPredicate saturatedX) {
            this(d, saturatedX, () -> false);
        }

        SaturationScheduler(io.github.dailystruggle.rtp.api.scheduling.RTPScheduler d,
                            java.util.function.IntPredicate saturatedX,
                            java.util.function.BooleanSupplier generationFull) {
            this.d = d;
            this.saturatedX = saturatedX;
            this.generationFull = generationFull;
        }

        @Override public boolean isRegionSaturated(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz) {
            return saturatedX.test(cx) || isGenerationSaturated(w);
        }
        @Override public boolean isGenerationSaturated(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w) {
            return generationFull.getAsBoolean();
        }
        @Override public io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask runTaskAsynchronously(Runnable t) {
            return d.runTaskAsynchronously(t);
        }
        @Override public void runTask(Runnable t) { d.runTask(t); }
        @Override public void runTaskLater(Runnable t, long delay) { d.runTaskLater(t, delay); }
        @Override public Object runTaskTimer(Runnable t, long delay, long period) { return d.runTaskTimer(t, delay, period); }
        @Override public Object runTaskTimerAsynchronously(Runnable t, long delay, long period) {
            return d.runTaskTimerAsynchronously(t, delay, period);
        }
        @Override public void cancelTask(Object t) { d.cancelTask(t); }
        @Override public void runTaskForPlayer(io.github.dailystruggle.rtp.api.entity.RTPPlayer p,
                                               io.github.dailystruggle.rtp.common.tasks.RTPRunnable t, long delay) {
            d.runTaskForPlayer(p, t, delay);
        }
        @Override public void runTask(io.github.dailystruggle.rtp.api.world.RTPLocation l, Runnable t) { d.runTask(l, t); }
        @Override public void runTask(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz, Runnable t) {
            d.runTask(w, cx, cz, t);
        }
        @Override public Object runTaskTimer(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz,
                                             Runnable t, long delay, long period) {
            return d.runTaskTimer(w, cx, cz, t, delay, period);
        }
        @Override public void runTaskLater(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz,
                                           Runnable t, long delay) {
            d.runTaskLater(w, cx, cz, t, delay);
        }
    }

    private static final class PendingTrackedMockWorld extends TrackedMockWorld {
        private final List<CompletableFuture<Long>> pendingLoads = new ArrayList<>();

        private PendingTrackedMockWorld(String name) {
            super(name);
        }

        @Override
        public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
            chunkAsyncLoadCount.incrementAndGet();
            CompletableFuture<Long> load = new CompletableFuture<>();
            pendingLoads.add(load);
            return CompletableFuture.completedFuture(new ChunkSet(
                    this, cx, cz, List.of(load), new CompletableFuture<>()));
        }

        private void failPendingLoads() {
            for (CompletableFuture<Long> load : pendingLoads) {
                load.completeExceptionally(new IllegalStateException("test load released"));
            }
        }
    }
}
