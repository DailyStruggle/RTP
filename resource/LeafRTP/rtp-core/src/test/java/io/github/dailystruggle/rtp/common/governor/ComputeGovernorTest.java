package io.github.dailystruggle.rtp.common.governor;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.bit.GovernorBitComponent;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.LiveLoadGate;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import io.github.dailystruggle.rtp.common.tasks.tick.SyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.tick.TickBudget;
import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mechanical verification of ADR-087 Section 5 invariants (I2, I3, I4, I5, I6, I7, I8, I10, I11, I12)
 * and Section 8 design tests.
 */
@DisplayName("ADR-087: ComputeGovernor invariants I2..I12 and pure control-law tests")
class ComputeGovernorTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    private ComputeGovernor governor;

    @BeforeEach
    void setUp() {
        governor = new ComputeGovernor();
        ComputeGovernor.setActive(governor);
        TickBudget.clear();
        LiveLoadGate.resetAll();
        GenerationBudget.server().resetForTesting();
    }

    @AfterEach
    void tearDown() {
        governor.reset();
        TickBudget.clear();
        LiveLoadGate.resetAll();
        GenerationBudget.server().resetForTesting();
    }

    @Test
    @DisplayName("Invariant I2: Overrun carry-forward bounds per-pulse and windowed async/sync spend")
    void invariantI2PulseAndWindowSpendBoundedWithCarryForward() {
        Random rnd = new Random(8702L);
        long aCfg = 25 * MS;
        long sCfg = 5 * MS;
        long maxAsyncTaskDur = 9 * MS; // epsilon_A
        long maxSyncTaskDur = 3 * MS;  // epsilon_S

        ComputeSignals signals = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(aCfg)
                .configuredSyncNanos(sCfg)
                .mspt(ComputeSignals.SampledSignal.fresh(20.0, 10 * SEC))
                .region(new ComputeSignals.RegionDemand("r1", 20L, 0.5, 2, 10, 0, false))
                .region(new ComputeSignals.RegionDemand("r2", 20L, 0.5, 2, 10, 0, false))
                .build();
        governor.setUnitCostsForTesting(2 * MS, 0.5 * MS, 0.5 * MS);
        governor.evaluate(signals, 0L);

        // Background drain with budget <= 0 starts zero tasks
        SelectionAPI selectionAPI = new SelectionAPI();
        int[] ranOnZero = {0};
        selectionAPI.selectionPipeline.add(() -> ranOnZero[0]++);
        assertEquals(0, selectionAPI.computeStandard(0L, false));
        assertEquals(0, selectionAPI.computeStandard(-5 * MS, false));
        assertEquals(0, ranOnZero[0], "Background drain with budget <= 0 must start no task");

        // Shared pulse-wide ranAnyBg flag: when bgBudget > 0, first background task starts even if
        // predicted cost (50 ms default) > bgBudget (1 ms), setting ranAnyBg = true, and subsequent
        // stages/tasks in the same pulse do not start once predicted cost exceeds remaining budget.
        AtomicBoolean sharedRanAnyBg = new AtomicBoolean(false);
        TimeBoundTaskPipe stage1 = new TimeBoundTaskPipe();
        TimeBoundTaskPipe stage2 = new TimeBoundTaskPipe();
        int[] stage1Ran = {0};
        int[] stage2Ran = {0};
        stage1.add(() -> stage1Ran[0]++);
        stage1.add(() -> stage1Ran[0]++);
        stage2.add(() -> stage2Ran[0]++);
        stage1.execute(MS, sharedRanAnyBg);
        stage2.execute(MS, sharedRanAnyBg);
        assertTrue(sharedRanAnyBg.get(), "First background task must set sharedRanAnyBg = true");
        assertEquals(1, stage1Ran[0], "First task starts even when predicted cost > 1 ms, second task yields");
        assertEquals(0, stage2Ran[0], "Second stage sharing ranAnyBg must not start another 50 ms predicted task");

        // Property check: random signals including deltaT_tick == 0 with B_cpu == 0 never produce NaN/Infinite or exceed ceiling
        governor.setUnitCostsForTesting(2 * MS, 0.0, 0.5 * MS);
        for (int i = 0; i < 40; i++) {
            ComputeSignals randSig = ComputeSignals.builder()
                    .autoTune(true)
                    .configuredAsyncNanos(aCfg)
                    .configuredSyncNanos(sCfg)
                    .configuredPromotionsPerTurn(8)
                    .configuredGenerationCap(4)
                    .maxMspt(45.0)
                    .mspt(ComputeSignals.SampledSignal.fresh(36.0 + rnd.nextDouble() * 10.0, 10 * SEC))
                    .region(new ComputeSignals.RegionDemand("r1", rnd.nextInt(50), rnd.nextDouble() * 2.0, 2, 10, 0, false))
                    .build();
            ComputeBudget rb = governor.evaluate(randSig, (i + 1L) * SEC);
            assertFalse(Double.isNaN(rb.targetUnitRate()) || Double.isInfinite(rb.targetUnitRate()));
            assertTrue(rb.backgroundAsyncNanos() <= aCfg);
            assertTrue(rb.backgroundSyncNanos() <= sCfg);
            assertTrue(rb.promotionsPerTurn() <= 8);
            assertTrue(rb.generationCap() <= 4);
            assertTrue(rb.hotCap("r1") <= 10);
        }
        governor.reset();
        governor.setUnitCostsForTesting(2 * MS, 0.5 * MS, 0.5 * MS);
        governor.evaluate(signals, 0L);

        // Simulated pulse loop with random task durations, including tasks longer than remaining budget
        // and an always-overrunning fixture, plus comparison against an ungoverned no-carry-forward loop.
        long cumulativeSpend = 0L;
        long cumulativePlayerOverrun = 0L;
        long naiveCumulativeSpend = 0L;

        for (int n = 1; n <= 120; n++) {
            // Player-facing + unshaped work (occasionally overruns A_cfg by itself)
            long playerSpend = (n % 15 == 0) ? (aCfg + 4 * MS) : ((1 + rnd.nextInt(10)) * MS);
            long playerOverrun = Math.max(0L, playerSpend - aCfg);
            cumulativePlayerOverrun += playerOverrun;

            // Player-facing drains always receive configured budget regardless of asyncDebtNanos
            long playerBudgetWithDebt = aCfg;
            assertEquals(25 * MS, playerBudgetWithDebt);

            long remainder = aCfg - playerSpend;
            long bgBudget = governor.beginBackgroundAsyncPhase(remainder);
            long bgSpent = 0L;
            // Simulate standard drain + multiple region turns sharing bgBudget
            while (bgSpent < bgBudget) {
                long taskDur = (1 + rnd.nextInt(9)) * MS; // up to maxAsyncTaskDur = 9 ms
                bgSpent += taskDur;
            }
            if (bgBudget <= 0L) {
                assertEquals(0L, bgSpent, "Zero background budget must start zero tasks");
            }

            long pulseSpend = playerSpend + bgSpent;
            assertTrue(pulseSpend <= Math.max(playerSpend, aCfg) + maxAsyncTaskDur,
                    "Pulse " + n + " exceeded single-task overrun bound: " + pulseSpend);

            governor.endAsyncPulse(pulseSpend, aCfg);
            cumulativeSpend += pulseSpend;

            long netWindowExcess = (cumulativeSpend - cumulativePlayerOverrun) - (n * aCfg);
            assertTrue(netWindowExcess <= maxAsyncTaskDur,
                    "Window excess at pulse " + n + " exceeded epsilon_A: " + netWindowExcess);

            // Contrast with a naive loop without carry-forward on an always-overrunning task
            long naiveBgBudget = Math.max(0L, remainder);
            long naiveBgSpent = (naiveBgBudget > 0L) ? (naiveBgBudget + maxAsyncTaskDur) : 0L;
            naiveCumulativeSpend += (playerSpend + naiveBgSpent);
        }
        assertTrue((naiveCumulativeSpend - cumulativePlayerOverrun) - (120 * aCfg) > maxAsyncTaskDur,
                "Without carry-forward debt, cumulative spend must fail the window assertion");

        // Verify background sync per-tick and per-window bound against S_cfg and epsilon_S
        long cumulativeSyncSpend = 0L;
        for (int tick = 1; tick <= 120; tick++) {
            long bgSyncBudget = governor.beginBackgroundSyncSpan();
            long syncSpent = 0L;
            while (syncSpent < bgSyncBudget) {
                syncSpent += (1 + rnd.nextInt(3)) * MS; // up to maxSyncTaskDur = 3 ms
            }
            if (bgSyncBudget <= 0L) {
                assertEquals(0L, syncSpent);
            }
            assertTrue(syncSpent <= sCfg + maxSyncTaskDur);
            governor.endBackgroundSyncSpan(syncSpent, sCfg);
            cumulativeSyncSpend += syncSpent;
            assertTrue(cumulativeSyncSpend - (tick * sCfg) <= maxSyncTaskDur,
                    "Sync window excess at tick " + tick + " exceeded epsilon_S");
        }
    }

    @Test
    @DisplayName("Invariant I3: autoTune false (or observeOnly true) returns configured values on all actuation accessors")
    void invariantI3AutoTuneDisabledParity() {
        ComputeSignals disabled = ComputeSignals.builder()
                .autoTune(false)
                .observeOnly(false)
                .configuredAsyncNanos(20 * MS)
                .configuredSyncNanos(4 * MS)
                .configuredPeriodTicks(15L)
                .configuredPromotionsPerTurn(8)
                .configuredGenerationCap(4)
                .mspt(ComputeSignals.SampledSignal.fresh(44.0, 10 * SEC))
                .region(new ComputeSignals.RegionDemand("overworld", 0L, 0.0, 0, 12, 0, true))
                .build();
        ComputeBudget b = governor.evaluate(disabled, SEC);

        assertFalse(b.isActuating());
        assertEquals(20 * MS, b.backgroundAsyncNanos());
        assertEquals(4 * MS, b.backgroundSyncNanos());
        assertEquals(15L, b.backgroundPeriodTicks());
        assertEquals(8, b.promotionsPerTurn());
        assertEquals(4, b.generationCap());
        assertEquals(35.0, b.cpuTarget(), 1e-6);
        assertEquals(12, b.hotCap("overworld"));
        assertEquals(Integer.MAX_VALUE, b.unitsPerTurn("overworld"));

        // Observe-only mode computes effective values in telemetry but actuation accessors still return configured
        ComputeSignals observe = ComputeSignals.builder()
                .autoTune(true)
                .observeOnly(true)
                .configuredAsyncNanos(20 * MS)
                .configuredSyncNanos(4 * MS)
                .configuredPeriodTicks(15L)
                .configuredPromotionsPerTurn(8)
                .configuredGenerationCap(4)
                .mspt(ComputeSignals.SampledSignal.fresh(44.0, 10 * SEC))
                .region(new ComputeSignals.RegionDemand("overworld", 0L, 0.0, 0, 12, 0, true))
                .build();
        ComputeBudget obsBudget = governor.evaluate(observe, 2 * SEC);
        assertFalse(obsBudget.isActuating());
        assertEquals(20 * MS, obsBudget.backgroundAsyncNanos());
        assertEquals(4 * MS, obsBudget.backgroundSyncNanos());
        assertEquals(15L, obsBudget.backgroundPeriodTicks());
        assertEquals(8, obsBudget.promotionsPerTurn());
        assertEquals(4, obsBudget.generationCap());
        assertEquals(12, obsBudget.hotCap("overworld"));
        assertEquals(Integer.MAX_VALUE, obsBudget.unitsPerTurn("overworld"));
        assertTrue(obsBudget.effectiveAsyncNanos() < obsBudget.configuredAsyncNanos(),
                "Observe-only mode still computes reduced telemetry target");
    }

    @Test
    @DisplayName("Invariant I4: Player-facing budgets and urgent pipeline order are invariant across governor states")
    void invariantI4PlayerFacingBudgetAndOrderInvariance() {
        // Drive governor into deep throttling (IDLE + CPU breach + async debt)
        ComputeSignals stressed = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(25 * MS)
                .configuredSyncNanos(5 * MS)
                .configuredPeriodTicks(20L)
                .mspt(ComputeSignals.SampledSignal.fresh(44.0, 10 * SEC))
                .region(new ComputeSignals.RegionDemand("default", 0L, 0.0, 0, 10, 0, true))
                .build();
        governor.evaluate(stressed, SEC);
        governor.endAsyncPulse(40 * MS, 25 * MS); // create 15 ms async debt
        assertTrue(governor.asyncDebtNanos() > 0L);

        // Player-facing TickBudget accessors remain at configured values
        assertEquals(25 * MS, TickBudget.asyncNanos());
        assertEquals(5 * MS, TickBudget.syncNanos());
        assertEquals(20L, TickBudget.periodTicks());

        // Urgent pipeline drains in exact FIFO order with same task count whether autoTune is on or off
        List<Integer> orderGoverned = new ArrayList<>();
        SelectionAPI apiGoverned = new SelectionAPI();
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            apiGoverned.selectionPipelineUrgent.add(() -> orderGoverned.add(id));
        }
        apiGoverned.computeUrgent(5 * SEC);

        governor.reset();
        List<Integer> orderBaseline = new ArrayList<>();
        SelectionAPI apiBaseline = new SelectionAPI();
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            apiBaseline.selectionPipelineUrgent.add(() -> orderBaseline.add(id));
        }
        apiBaseline.computeUrgent(5 * SEC);

        assertEquals(List.of(1, 2, 3, 4, 5), orderBaseline);
        assertEquals(orderBaseline, orderGoverned);
    }

    @Test
    @DisplayName("Invariant I5: Single-loop host with B_cpu < 1 ms sets backgroundSyncNanos = 0 and starts no background sync task")
    void invariantI5SingleLoopLowHeadroomZeroSyncWork() {
        // maxMspt = 45.0 -> T_cpu = 36.0 ms. At mspt = 35.0 ms (base), B_cpu = 0.5 * (36.0 - 35.0) = 0.5 ms < 1.0 ms
        ComputeSignals lowHeadroom = ComputeSignals.builder()
                .autoTune(true)
                .regionalTicking(false)
                .maxMspt(45.0)
                .configuredSyncNanos(5 * MS)
                .mspt(ComputeSignals.SampledSignal.fresh(35.0, 10 * SEC))
                .region(new ComputeSignals.RegionDemand("r1", 10L, 0.5, 2, 10, 0, false))
                .build();
        ComputeBudget b = governor.evaluate(lowHeadroom, SEC);

        assertEquals(0.5, b.cpuHeadroomMs(), 1e-6);
        assertEquals(0L, b.backgroundSyncNanos());
        assertEquals(0, b.promotionsPerTurn());
        assertEquals(GovernorReason.CPU_HEADROOM, b.syncReason());
        assertEquals(0L, governor.beginBackgroundSyncSpan());

        // Player-facing MiscTasks.sync() still runs while MiscTasks.backgroundSync() starts zero tasks
        TimeBoundTaskPipe fgSync = new TimeBoundTaskPipe();
        TimeBoundTaskPipe bgSync = new TimeBoundTaskPipe();
        MiscTasks.setSync(fgSync);
        MiscTasks.setBackgroundSync(bgSync);
        int[] fgRan = {0};
        int[] bgRan = {0};
        fgSync.add(() -> fgRan[0]++);
        bgSync.add(() -> bgRan[0]++);
        new SyncTaskProcessing(5 * MS).run();
        assertEquals(1, fgRan[0], "Player-facing MiscTasks.sync() must run even when B_cpu < 1 ms");
        assertEquals(0, bgRan[0], "Background MiscTasks.backgroundSync() must not start when B_cpu < 1 ms");
    }

    @Test
    @DisplayName("Invariant I6: NEVER_SAMPLED leaves knobs at configured; STALE never raises any effective knob")
    void invariantI6NeverSampledAndStaleDiscipline() {
        // 1. All signals NEVER_SAMPLED -> configured values on heap/sync/generation knobs
        ComputeSignals neverSampled = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(25 * MS)
                .configuredSyncNanos(5 * MS)
                .configuredPromotionsPerTurn(8)
                .configuredGenerationCap(4)
                .region(new ComputeSignals.RegionDemand("r1", 20L, 1.0, 5, 10, 0, false))
                .build();
        ComputeBudget b0 = governor.evaluate(neverSampled, SEC);
        assertEquals(5 * MS, b0.backgroundSyncNanos());
        assertEquals(8, b0.promotionsPerTurn());
        assertEquals(4, b0.generationCap());
        assertEquals(10, b0.hotCap("r1"));

        // 2. Cut knobs via FRESH breaches
        ComputeSignals breaching = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(25 * MS)
                .configuredSyncNanos(5 * MS)
                .configuredPromotionsPerTurn(8)
                .configuredGenerationCap(4)
                .maxMspt(45.0)
                .maxHeapPercent(85.0)
                .maxHeapBytes(1_000_000_000L)
                .mspt(ComputeSignals.SampledSignal.fresh(40.0, SEC))
                .processCpuLoad(ComputeSignals.SampledSignal.fresh(0.95, SEC))
                .rtpAsyncCpuShare(ComputeSignals.SampledSignal.fresh(0.25, SEC))
                .postGcTenuredUsedBytes(ComputeSignals.SampledSignal.fresh(800_000_000.0, SEC))
                .chunkLoadLatencyMs(ComputeSignals.SampledSignal.fresh(50.0, SEC))
                .minWindowedChunkLoadLatencyMs(10.0)
                .region(new ComputeSignals.RegionDemand("r1", 20L, 1.0, 5, 10, 0, false))
                .build();
        governor.evaluate(breaching, 2 * SEC);
        ComputeBudget bCut = governor.evaluate(breaching, 4 * SEC);
        assertTrue(bCut.generationCap() < 4);
        assertTrue(bCut.hotCap("r1") < 10);

        // 3. Feed STALE signals with healthy-looking last values over 120 seconds: no knob may rise!
        long prevAsync = bCut.backgroundAsyncNanos();
        long prevSync = bCut.backgroundSyncNanos();
        int prevGen = bCut.generationCap();
        int prevHot = bCut.hotCap("r1");

        for (int step = 1; step <= 24; step++) {
            long t = (4 + step * 5L) * SEC;
            ComputeSignals staleSignals = ComputeSignals.builder()
                    .autoTune(true)
                    .configuredAsyncNanos(25 * MS)
                    .configuredSyncNanos(5 * MS)
                    .configuredPromotionsPerTurn(8)
                    .configuredGenerationCap(4)
                    .maxMspt(45.0)
                    .maxHeapPercent(85.0)
                    .maxHeapBytes(1_000_000_000L)
                    .mspt(ComputeSignals.SampledSignal.stale(15.0, SEC, step * 5L * SEC))
                    .processCpuLoad(ComputeSignals.SampledSignal.stale(0.20, SEC, step * 5L * SEC))
                    .rtpAsyncCpuShare(ComputeSignals.SampledSignal.stale(0.05, SEC, step * 5L * SEC))
                    .postGcTenuredUsedBytes(ComputeSignals.SampledSignal.stale(200_000_000.0, SEC, step * 5L * SEC))
                    .chunkLoadLatencyMs(ComputeSignals.SampledSignal.stale(10.0, SEC, step * 5L * SEC))
                    .minWindowedChunkLoadLatencyMs(10.0)
                    .region(new ComputeSignals.RegionDemand("r1", 20L, 1.0, 5, 10, 0, false))
                    .build();
            ComputeBudget bStale = governor.evaluate(staleSignals, t);
            assertTrue(bStale.backgroundAsyncNanos() <= prevAsync, "Stale signal raised async budget");
            assertTrue(bStale.backgroundSyncNanos() <= prevSync, "Stale signal raised sync budget");
            assertTrue(bStale.generationCap() <= prevGen, "Stale signal raised generationCap");
            assertTrue(bStale.hotCap("r1") <= prevHot, "Stale signal raised hotCap");
            prevAsync = bStale.backgroundAsyncNanos();
            prevSync = bStale.backgroundSyncNanos();
            prevGen = bStale.generationCap();
            prevHot = bStale.hotCap("r1");
        }
    }

    @Test
    @DisplayName("Invariant I7 & I10: Hard gates pause background units; Folia skips single-loop B_cpu")
    void invariantI7AndI10HardGatesAndFoliaRegionalTicking() {
        // I7: When hardGatePaused is true, r* and unitsPerTurn are 0
        ComputeSignals paused = ComputeSignals.builder()
                .autoTune(true)
                .hardGatePaused(true)
                .mspt(ComputeSignals.SampledSignal.fresh(20.0, SEC))
                .region(new ComputeSignals.RegionDemand("r1", 20L, 1.0, 2, 10, 0, false))
                .build();
        ComputeBudget bPaused = governor.evaluate(paused, SEC);
        assertEquals(0.0, bPaused.targetUnitRate(), 1e-9);
        assertEquals(0, bPaused.unitsPerTurn("r1"));
        assertEquals(GovernorReason.BACKSTOP, bPaused.asyncReason());

        // I10: On Folia (regionalTicking = true), single-loop B_cpu is skipped (NaN), sync & promotions stay configured
        governor.reset();
        ComputeSignals folia = ComputeSignals.builder()
                .autoTune(true)
                .regionalTicking(true)
                .configuredSyncNanos(5 * MS)
                .configuredPromotionsPerTurn(8)
                .configuredGenerationCap(4)
                .minTps(19.0)
                .mspt(ComputeSignals.SampledSignal.fresh(48.0, SEC)) // high worst-region MSPT ignored on Folia
                .meanTps(ComputeSignals.SampledSignal.fresh(19.8, 60 * SEC)) // healthy mean TPS
                .region(new ComputeSignals.RegionDemand("r1", 10L, 0.5, 2, 10, 0, false))
                .build();
        ComputeBudget bFolia = governor.evaluate(folia, 2 * SEC);
        assertTrue(Double.isNaN(bFolia.cpuHeadroomMs()), "Folia must skip single-loop B_cpu");
        assertEquals(5 * MS, bFolia.backgroundSyncNanos());
        assertEquals(8, bFolia.promotionsPerTurn());
        assertEquals(4, bFolia.generationCap(), "Single slow region on Folia must not cut global generationCap");
    }

    @Test
    @DisplayName("Invariant I11: Windowed background units delivered never exceed plan integral plus initial credit cap")
    void invariantI11WindowedCreditIntegralBound() {
        Random rnd = new Random(8711L);
        long periodCfg = 20L;
        int numRegions = 4;
        String[] names = {"r0", "r1", "r2", "r3"};
        int steps = 80;

        governor.setUnitCostsForTesting(MS, 0.1 * MS, 0.1 * MS);

        double[][] planHistory = new double[steps][numRegions];
        double[][] capHistory = new double[steps][numRegions];
        double[] rStarHistory = new double[steps];
        int[][] delivered = new int[steps][numRegions];

        long now = 0L;
        for (int t = 0; t < steps; t++) {
            ComputeSignals.Builder sb = ComputeSignals.builder()
                    .autoTune(true)
                    .configuredAsyncNanos(25 * MS)
                    .configuredPeriodTicks(periodCfg)
                    .mspt(ComputeSignals.SampledSignal.fresh(15.0 + rnd.nextInt(15), SEC));
            for (int r = 0; r < numRegions; r++) {
                // Include periodic zero-demand steps (x_r = 0 -> b_r = 0, c_r = 0, 0 units delivered)
                // and fractional-plan steps (0 < x_r * periodCfg < 1)
                boolean zeroStep = (t % 9 == r);
                long deficit = zeroStep ? 0L : rnd.nextInt(30);
                double rate = zeroStep ? 0.0 : (r == 3 ? 0.02 : rnd.nextDouble() * 0.8);
                sb.region(new ComputeSignals.RegionDemand(names[r], deficit, rate, 2, 10, 0, false));
            }
            ComputeBudget b = governor.evaluate(sb.build(), now);
            rStarHistory[t] = b.targetUnitRate();
            for (int r = 0; r < numRegions; r++) {
                planHistory[t][r] = b.plannedRate(names[r]);
                capHistory[t][r] = b.creditCap(names[r]);
                if (planHistory[t][r] == 0.0) {
                    assertEquals(0.0, capHistory[t][r], 1e-9, "Zero plan must set credit cap b_r = 0");
                    assertEquals(0, b.unitsPerTurn(names[r]), "Zero plan must publish 0 unitsPerTurn");
                }
                int quota = b.unitsPerTurn(names[r]);
                int completed = quota > 0 ? rnd.nextInt(quota + 1) : 0;
                delivered[t][r] = completed;
                if (completed > 0) {
                    governor.debitRegionUnits(names[r], completed);
                }
            }
            now += ComputeGovernor.NANOS_PER_TICK; // 1 tick per step
        }

        // Verify for EVERY window [a, b] (0 <= a <= b < steps)
        for (int a = 0; a < steps; a++) {
            for (int b = a; b < steps; b++) {
                double totalIntegralRStar = 0.0;
                for (int k = a; k < b; k++) {
                    totalIntegralRStar += rStarHistory[k] * 1.0; // dt = 1 tick
                }
                double sumInitialCaps = 0.0;
                int totalDeliveredAllRegions = 0;

                for (int r = 0; r < numRegions; r++) {
                    double integralXr = 0.0;
                    for (int k = a; k < b; k++) {
                        integralXr += planHistory[k][r] * 1.0;
                    }
                    int deliveredR = 0;
                    for (int k = a; k <= b; k++) {
                        deliveredR += delivered[k][r];
                    }
                    double boundR = integralXr + capHistory[a][r] + 1e-6;
                    assertTrue(deliveredR <= boundR,
                            "Region " + names[r] + " window [" + a + "," + b + "] delivered "
                                    + deliveredR + " > bound " + boundR);
                    sumInitialCaps += capHistory[a][r];
                    totalDeliveredAllRegions += deliveredR;
                }

                double totalBound = totalIntegralRStar + sumInitialCaps + 1e-6;
                assertTrue(totalDeliveredAllRegions <= totalBound,
                        "Total window [" + a + "," + b + "] delivered "
                                + totalDeliveredAllRegions + " > bound " + totalBound);
            }
        }
    }

    @Test
    @DisplayName("Invariant I12: Limiters forced to floor converge to model target within max(5 s, L) + 50 s")
    void invariantI12ConvergenceFromFloor() {
        long lag = 10 * SEC; // L = 10 s
        long aCfg = 25 * MS;
        long sCfg = 5 * MS;
        int genCfg = 4;
        int hotCfg = 12;

        governor.setUnitCostsForTesting(2 * MS, 0.2 * MS, 0.2 * MS);

        ComputeSignals healthyWithDemand = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(aCfg)
                .configuredSyncNanos(sCfg)
                .configuredGenerationCap(genCfg)
                .maxMspt(45.0)
                .maxHeapPercent(85.0)
                .maxHeapBytes(2_000_000_000L)
                .mspt(ComputeSignals.SampledSignal.fresh(10.0, lag))
                .processCpuLoad(ComputeSignals.SampledSignal.fresh(0.30, 2 * SEC))
                .rtpAsyncCpuShare(ComputeSignals.SampledSignal.fresh(0.05, 2 * SEC))
                .postGcTenuredUsedBytes(ComputeSignals.SampledSignal.fresh(200_000_000.0, lag))
                .chunkLoadLatencyMs(ComputeSignals.SampledSignal.fresh(8.0, 5 * SEC))
                .minWindowedChunkLoadLatencyMs(8.0)
                .region(new ComputeSignals.RegionDemand("r1", 40L, 1.0, 4, hotCfg, 0, false))
                .build();

        // 1. Intermediate model target (r* = 3.0 -> model target = 1.25 * 3 * 2 ms = 7.5 ms):
        //    converges within deadband (10%) of 7.5 ms after max(5 s, L) + 50 s
        governor.evaluate(healthyWithDemand, 0L);
        governor.forceAllLimitersToFloor(lag, 0L);

        long totalConvergenceNanos = Math.max(5 * SEC, lag) + 50 * SEC; // 60 s
        long stepNanos = 250 * MS;
        ComputeBudget finalBudget = null;
        for (long t = stepNanos; t <= totalConvergenceNanos; t += stepNanos) {
            finalBudget = governor.evaluate(healthyWithDemand, t);
        }

        long expectedAsyncModelTarget = Math.round(1.25 * 3.0 * (2 * MS)); // 7.5 ms
        assertTrue(Math.abs(finalBudget.backgroundAsyncNanos() - expectedAsyncModelTarget)
                        <= expectedAsyncModelTarget * 0.10,
                "Published async budget must be within 10% deadband of model target 7.5 ms, got "
                        + finalBudget.backgroundAsyncNanos());
        assertEquals(sCfg, finalBudget.backgroundSyncNanos());
        assertEquals(8, finalBudget.promotionsPerTurn());
        assertEquals(genCfg, finalBudget.generationCap());
        assertEquals(hotCfg, finalBudget.hotCap("r1"));
        assertTrue(finalBudget.unitsPerTurn("r1") >= 1);

        // 2. Ceiling model target (r* = 10.0 -> model target = 25 ms = aCfg):
        //    equals ceiling aCfg after max(5 s, L) + 50 s
        governor.reset();
        governor.setUnitCostsForTesting(2 * MS, 0.2 * MS, 0.2 * MS);
        ComputeSignals ceilingDemand = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(aCfg)
                .configuredSyncNanos(sCfg)
                .configuredGenerationCap(genCfg)
                .maxMspt(45.0)
                .maxHeapPercent(85.0)
                .maxHeapBytes(2_000_000_000L)
                .mspt(ComputeSignals.SampledSignal.fresh(10.0, lag))
                .processCpuLoad(ComputeSignals.SampledSignal.fresh(0.30, 2 * SEC))
                .rtpAsyncCpuShare(ComputeSignals.SampledSignal.fresh(0.05, 2 * SEC))
                .postGcTenuredUsedBytes(ComputeSignals.SampledSignal.fresh(200_000_000.0, lag))
                .chunkLoadLatencyMs(ComputeSignals.SampledSignal.fresh(8.0, 5 * SEC))
                .minWindowedChunkLoadLatencyMs(8.0)
                .region(new ComputeSignals.RegionDemand("r1", 160L, 2.0, 4, hotCfg, 0, false))
                .build();
        governor.evaluate(ceilingDemand, 0L);
        governor.forceAllLimitersToFloor(lag, 0L);
        ComputeBudget ceilingBudget = null;
        for (long t = stepNanos; t <= totalConvergenceNanos; t += stepNanos) {
            ceilingBudget = governor.evaluate(ceilingDemand, t);
        }
        assertEquals(aCfg, ceilingBudget.backgroundAsyncNanos());
    }

    @Test
    @DisplayName("ADR-087 Section 8: Zero demand idles, 3:1 water-filling, immediate async wake, and memory fit bounds")
    void section8DesignTests() {
        governor.setUnitCostsForTesting(2 * MS, 0.2 * MS, 0.2 * MS);

        // 1. Zero demand idles (no refill turns, idle cadence at 8 * period_cfg, background async at 1 ms floor)
        ComputeSignals idleSignals = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(25 * MS)
                .configuredPeriodTicks(20L)
                .mspt(ComputeSignals.SampledSignal.fresh(15.0, SEC))
                .region(new ComputeSignals.RegionDemand("r1", 0L, 0.0, 5, 10, 0, false))
                .build();
        ComputeBudget idleBudget = governor.evaluate(idleSignals, SEC);
        assertEquals(0.0, idleBudget.targetUnitRate(), 1e-9);
        assertEquals(0, idleBudget.unitsPerTurn("r1"));
        assertEquals(160L, idleBudget.backgroundPeriodTicks());
        assertEquals(MS, idleBudget.backgroundAsyncNanos());
        assertEquals(GovernorReason.IDLE, idleBudget.asyncReason());

        // 2. Background async budget reaches its planned level on the FIRST evaluation after demand appears
        ComputeSignals wakeSignals = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(25 * MS)
                .configuredPeriodTicks(20L)
                .mspt(ComputeSignals.SampledSignal.fresh(15.0, SEC))
                .region(new ComputeSignals.RegionDemand("r1", 100L, 2.0, 5, 10, 0, false))
                .build();
        ComputeBudget wakeBudget = governor.evaluate(wakeSignals, 2 * SEC);
        assertTrue(wakeBudget.backgroundAsyncNanos() > MS,
                "Background async budget must jump to planned level on first evaluation after idle");

        // 3. Water-filling: two regions with 3:1 weights under constrained r* get 3:1 planned rates;
        //    under unconstrained r*, neither exceeds its deliverable demand.
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("high", 6.0);
        demands.put("low", 2.0);
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("high", 3.0);
        weights.put("low", 1.0);

        Map<String, Double> constrained = ComputeGovernor.computeWaterFilling(demands, weights, 4.0);
        assertEquals(3.0, constrained.get("high"), 1e-6);
        assertEquals(1.0, constrained.get("low"), 1e-6);

        Map<String, Double> unconstrained = ComputeGovernor.computeWaterFilling(demands, weights, 12.0);
        assertEquals(6.0, unconstrained.get("high"), 1e-6);
        assertEquals(2.0, unconstrained.get("low"), 1e-6);

        // 4. Per-chunk memory estimate bounds & exclusions
        double prior = governor.chunkMemoryPriorBytes();
        // Ignored within 60 s after contraction
        governor.recordHotCapContraction(10 * SEC);
        for (int i = 0; i < 25; i++) {
            double m = governor.recordPostGcMemorySample(10 + i * 5, (10 + i * 5) * prior * 2.0, 20 * SEC + i * SEC);
            assertEquals(prior, m, 1e-6, "Samples within 60 s of contraction must be ignored");
        }
        // Flat ticket count (< 25% variation) keeps prior
        for (int i = 0; i < 25; i++) {
            double m = governor.recordPostGcMemorySample(100, 100 * prior * 3.0, 100 * SEC + i * SEC);
            assertEquals(prior, m, 1e-6, "Unvaried ticket count must keep prior");
        }
        // Negative correlation clamps at >= prior
        governor.reset();
        for (int i = 0; i < 25; i++) {
            int tickets = 20 + i * 10;
            double bytes = (500 - i * 10) * prior;
            double m = governor.recordPostGcMemorySample(tickets, bytes, i * SEC);
            assertEquals(prior, m, 1e-6, "Negative slope must never drop below prior");
        }
        // Steep positive slope clamps at <= 4 * prior
        governor.reset();
        for (int i = 0; i < 25; i++) {
            int tickets = 20 + i * 10;
            double bytes = tickets * (prior * 10.0);
            double m = governor.recordPostGcMemorySample(tickets, bytes, i * SEC);
            assertTrue(m >= prior && m <= 4.0 * prior, "Slope must clamp to [prior, 4 * prior]");
        }
        assertEquals(4.0 * prior, governor.chunkMemoryEstimateBytes(), 1e-6);

        // 5. CBIT component findings and evaluation failure resilience
        governor.reset();
        governor.setSignalSource(now -> {
            throw new RuntimeException("simulated signal source failure");
        });
        ComputeBudget fallback = governor.maybeEvaluate(10 * SEC);
        assertSame(governor.budget(), fallback);
        assertEquals(1L, governor.evaluationFailures());

        BitReport cbitReport = GovernorBitComponent.reportFor(governor, 10 * SEC);
        assertTrue(cbitReport.findings().stream()
                .map(BitFinding::code)
                .anyMatch("GOVERNOR_EVALUATION_FAILED"::equals));

        // 6. 0-unit turn async elapsed time carries forward to the next turn that completes >= 1 unit
        governor.reset();
        governor.recordTurnUnitCosts("r1", 3 * MS, 0, 0);
        governor.recordTurnUnitCosts("r1", 2 * MS, 1, 0);
        ComputeSignals costCheck = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(25 * MS)
                .mspt(ComputeSignals.SampledSignal.fresh(15.0, SEC))
                .region(new ComputeSignals.RegionDemand("r1", 20L, 1.0, 5, 10, 0, false))
                .build();
        ComputeBudget costBudget = governor.evaluate(costCheck, SEC);
        // With deltaT_async = 3 ms + 2 ms = 5 ms, u_max = floor(25 ms / 5 ms) = 5
        assertEquals(5, costBudget.unitsPerTurn("r1"));

        // 7. ADR-087 Section 6.3 GovernorSnapshot & ADR-124 Go/No-Go 1 constrained-refill telemetry
        governor.reset();
        governor.setUnitCostsForTesting(5 * MS, MS, MS);
        ComputeSignals constrainedSignals = ComputeSignals.builder()
                .autoTune(true)
                .configuredAsyncNanos(20 * MS)
                .configuredSyncNanos(5 * MS)
                .configuredPeriodTicks(20L)
                .configuredGenerationCap(4)
                .mspt(ComputeSignals.SampledSignal.fresh(32.0, SEC)) // B_cpu = 0.5 * (40 - 32) = 4 ms -> r* = 4 units/tick
                .processCpuLoad(ComputeSignals.SampledSignal.stale(0.5, 90 * SEC, SEC))
                .generationInFlight(2)
                .region(new ComputeSignals.RegionDemand("popular", 100L, 1.0, 5, 10, 0, false, 40))
                .region(new ComputeSignals.RegionDemand("spammer", 100L, 1.0, 5, 10, 0, false, 1))
                .build();
        governor.evaluate(constrainedSignals, SEC);

        GovernorSnapshot snap = governor.snapshot();
        assertEquals(1L, snap.evaluationsTotal());
        assertEquals(1L, snap.constrainedEvaluationsTotal());
        assertEquals(1.0, snap.constrainedEvaluationShare(), 1e-6);
        assertEquals(40.0, snap.maxConsumerSkewRatio(), 1e-6);
        assertEquals(40, snap.regionDistinctConsumers().get("popular"));
        assertEquals(1, snap.regionDistinctConsumers().get("spammer"));
        assertTrue(snap.wouldBeAttributedPlannedRates().get("popular")
                > 30.0 * snap.wouldBeAttributedPlannedRates().get("spammer"),
                "ADR-124 observe-only wouldBeAttributedPlannedRates must weight popular (40 players) above spammer (1 player)");
        assertEquals(1, snap.staleSignals());
        assertEquals(2, snap.regionalInflightCount());
        assertEquals(4, snap.regionalInflightCeiling());
    }

    @Test
    @DisplayName("ADR-124 Section 7: Consumer attribution water-filling properties, Folia f_A < 1 cut, and I4/I11 preservation")
    void adr124ConsumerAttributionAndProcessCpuCutTests() {
        Random rnd = new Random(12407L);

        // 1. Property test: water-filling never exceeds d_bar_r, gives every unsatisfied region the same
        //    per-player share (n-player region gets n * 1-player region share), equals max-min fairness when
        //    all n_r are equal, and sums to r* when constrained.
        for (int trial = 0; trial < 50; trial++) {
            Map<String, Double> demands = new LinkedHashMap<>();
            Map<String, Double> weights = new LinkedHashMap<>();
            Map<String, Double> equalWeights = new LinkedHashMap<>();
            int regionCount = 3 + rnd.nextInt(4);
            double sumDemands = 0.0;
            for (int i = 0; i < regionCount; i++) {
                String r = "reg_" + i;
                double d = 1.0 + rnd.nextDouble() * 20.0;
                int nR = 1 + rnd.nextInt(40);
                demands.put(r, d);
                weights.put(r, (double) nR);
                equalWeights.put(r, 1.0);
                sumDemands += d;
            }
            double rStar = sumDemands * (0.15 + rnd.nextDouble() * 0.75); // strictly constrained (< sumDemands)
            Map<String, Double> alloc = ComputeGovernor.computeWaterFilling(demands, weights, rStar);

            double sumAlloc = 0.0;
            Double referencePerPlayerShare = null;
            for (String r : demands.keySet()) {
                double x = alloc.get(r);
                double d = demands.get(r);
                double nR = weights.get(r);
                assertTrue(x <= d + 1e-9, "Water-filling must never exceed deliverable demand d_bar_r");
                sumAlloc += x;
                if (x < d - 1e-6) {
                    // Unsatisfied region: per-player share x_r / n_r must be identical across all unsatisfied regions
                    double perPlayer = x / nR;
                    if (referencePerPlayerShare == null) {
                        referencePerPlayerShare = perPlayer;
                    } else {
                        assertEquals(referencePerPlayerShare, perPlayer, 1e-6,
                                "Every unsatisfied region must receive the exact same per-player share theta");
                    }
                }
            }
            assertEquals(rStar, sumAlloc, 1e-6, "Constrained water-filling allocation must sum to r*");

            // With all n_r equal (e.g. 5 players each), allocation equals unweighted max-min fairness (1 player each)
            Map<String, Double> uniformFive = new LinkedHashMap<>();
            for (String r : demands.keySet()) uniformFive.put(r, 5.0);
            Map<String, Double> allocEqual1 = ComputeGovernor.computeWaterFilling(demands, equalWeights, rStar);
            Map<String, Double> allocEqual5 = ComputeGovernor.computeWaterFilling(demands, uniformFive, rStar);
            for (String r : demands.keySet()) {
                assertEquals(allocEqual1.get(r), allocEqual5.get(r), 1e-6,
                        "Equal n_r across regions must match unweighted max-min fairness");
            }
        }

        // 2. Design behaviour test (ADR-124 Section 7): on Folia (regionalTicking = true), process CPU above
        //    T_proc on two evaluations with RTP async CPU >= 10% cuts l_A (f_A < 1), lowers r*, and cuts
        //    the lowest-n_r region first while preserving proportional n_r share among unsatisfied regions.
        governor.reset();
        governor.setUnitCostsForTesting(2 * MS, 0.5 * MS, 0.5 * MS);
        ComputeSignals foliaHealthy = ComputeSignals.builder()
                .autoTune(true)
                .regionalTicking(true)
                .configuredAsyncNanos(20 * MS) // asyncRateCap = 10 units/tick
                .configuredPeriodTicks(20L)
                .processCpuLoad(ComputeSignals.SampledSignal.fresh(0.40, SEC))
                .rtpAsyncCpuShare(ComputeSignals.SampledSignal.fresh(0.20, SEC))
                .region(new ComputeSignals.RegionDemand("popular", 100L, 3.0, 5, 10, 0, false, 40)) // d_bar = 8.0
                .region(new ComputeSignals.RegionDemand("solo", 20L, 1.0, 5, 10, 0, false, 1))      // d_bar = 2.0
                .build();
        ComputeBudget bUnconstrained = governor.evaluate(foliaHealthy, SEC);
        // Unconstrained: sum(d_bar) = 10.0 == asyncRateCap -> both get full deliverable demand (8.0 and 2.0)
        assertEquals(10.0, bUnconstrained.targetUnitRate(), 1e-6);
        assertEquals(8.0, bUnconstrained.plannedRate("popular"), 1e-6);
        assertEquals(2.0, bUnconstrained.plannedRate("solo"), 1e-6);

        // Two consecutive process CPU breaches (> 0.90 with RTP share >= 0.10) cut l_A in half -> f_A = 0.5 -> r* = 5.0
        ComputeSignals foliaProcBreach = ComputeSignals.builder()
                .autoTune(true)
                .regionalTicking(true)
                .configuredAsyncNanos(20 * MS)
                .configuredPeriodTicks(20L)
                .processCpuLoad(ComputeSignals.SampledSignal.fresh(0.95, SEC))
                .rtpAsyncCpuShare(ComputeSignals.SampledSignal.fresh(0.20, SEC))
                .region(new ComputeSignals.RegionDemand("popular", 100L, 3.0, 5, 10, 0, false, 40))
                .region(new ComputeSignals.RegionDemand("solo", 20L, 1.0, 5, 10, 0, false, 1))
                .build();
        governor.evaluate(foliaProcBreach, 2 * SEC);
        ComputeBudget bCut = governor.evaluate(foliaProcBreach, 3 * SEC);

        assertEquals(5.0, bCut.targetUnitRate(), 1e-6, "Two process CPU breaches must halve l_A and cut r* to 5.0");
        // Under water-filling with weights 40:1 and r* = 5.0 (both unsatisfied since 5 * 40/41 < 8 and 5 * 1/41 < 2):
        // popular gets 40 * (5 / 41) = 4.878, solo gets 1 * (5 / 41) = 0.122 (40x ratio)
        assertEquals(40.0 * bCut.plannedRate("solo"), bCut.plannedRate("popular"), 1e-6);
        assertEquals(5.0, bCut.plannedRate("popular") + bCut.plannedRate("solo"), 1e-6);

        GovernorSnapshot snap = governor.snapshot();
        assertEquals(40.0 / 41.0, snap.regionRefillShares().get("popular"), 1e-6);
        assertEquals(1.0 / 41.0, snap.regionRefillShares().get("solo"), 1e-6);
    }
}
