package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AsyncTaskProcessing} - the per-pulse asynchronous drain that
 * prunes completed futures, executes the cancel / misc-async pipes, drives
 * {@code SelectionAPI.compute()} and rotates through one region per pulse.
 *
 * <p>All execution is synchronous - no real threads are spawned.
 */
class AsyncTaskProcessingTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        RTP.futures.clear();
        ActiveSelection.get().permRegionLookup.clear();
        ActiveSelection.get().selectionPipelineUrgent.clear();
        ActiveSelection.get().selectionPipeline.clear();
        MiscTasks.cancel().clear();
        MiscTasks.cancel().start();
        MiscTasks.async().clear();
        MiscTasks.async().start();
        if (MiscTasks.backgroundSync() != null) {
            MiscTasks.backgroundSync().clear();
            MiscTasks.backgroundSync().start();
        }
        ComputeGovernor.active().reset();
        AsyncTaskProcessing.resetRotationForTesting();
        TickBudget.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.futures.clear();
        ActiveSelection.get().permRegionLookup.clear();
        ActiveSelection.get().selectionPipelineUrgent.clear();
        ActiveSelection.get().selectionPipeline.clear();
    }

    private Region newRegion(String name) {
        MockRTPWorld world = new MockRTPWorld(name + "_world");
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                name, world, square, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        return new Region(name, settings);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_doesNotThrow_withNoRegions() {
        assertDoesNotThrow(() -> new AsyncTaskProcessing(Long.MAX_VALUE).run());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_prunesCompletedFutures_keepsPending() {
        CompletableFuture<String> done = CompletableFuture.completedFuture("ok");
        CompletableFuture<String> pending = new CompletableFuture<>();
        RTP.futures.add(done);
        RTP.futures.add(pending);

        new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(RTP.futures.contains(pending), "pending future must be retained");
        assertFalse(RTP.futures.contains(done), "completed future must be pruned");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_executesCancelTasksPipe() {
        boolean[] ran = {false};
        MiscTasks.cancel().add(() -> ran[0] = true);

        new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(ran[0], "cancelTasks pipe must be drained by AsyncTaskProcessing.run()");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_executesMiscAsyncTasksPipe() {
        boolean[] ran = {false};
        MiscTasks.async().add(() -> ran[0] = true);

        new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(ran[0], "miscAsyncTasks pipe must be drained by AsyncTaskProcessing.run()");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cancelledBeforeRun_shortCircuits_withoutDrainingMiscAsync() {
        boolean[] ran = {false};
        MiscTasks.async().add(() -> ran[0] = true);

        AsyncTaskProcessing proc = new AsyncTaskProcessing(Long.MAX_VALUE);
        proc.setCancelled(true);
        assertDoesNotThrow(proc::run);

        assertFalse(ran[0], "a cancelled pulse must not drain the misc-async pipe");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_withRegion_doesNotThrow_andRotates() {
        ActiveSelection.get().permRegionLookup.put("regionA", newRegion("regionA"));
        ActiveSelection.get().permRegionLookup.put("regionB", newRegion("regionB"));

        // Multiple pulses to exercise the step / betweenStep rotation arithmetic.
        for (int i = 0; i < 4; i++) {
            assertDoesNotThrow(() -> new AsyncTaskProcessing(Long.MAX_VALUE).run());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_withZeroAvailableTime_doesNotThrow() {
        ActiveSelection.get().permRegionLookup.put("regionA", newRegion("regionA"));
        assertDoesNotThrow(() -> new AsyncTaskProcessing(0L).run());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_withPerformanceConfig_executesRegionAndHandlesException() {
        // Set up mock region that throws an exception during execute to test error handling
        Region base = newRegion("throwingRegion");
        Region throwingRegion = new Region("throwingRegion", base.getSettings()) {
            @Override
            public void execute(long allottedTime) {
                throw new RuntimeException("simulated region execute error");
            }
        };
        ActiveSelection.get().permRegionLookup.put("throwingRegion", throwingRegion);

        // Run pulse, verifying it catches the exception and logs it rather than crashing
        assertDoesNotThrow(() -> new AsyncTaskProcessing(100_000L).run());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-RTP-S-004: a pulse that is still running makes later ticks skip instead of stacking")
    void overlappingPulse_isSkipped_whilePreviousRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        Region base = newRegion("blockingRegion");
        Region blocking = new Region("blockingRegion", base.getSettings()) {
            @Override
            public void execute(long allottedTime) {
                executions.incrementAndGet();
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ActiveSelection.get().permRegionLookup.put("blockingRegion", blocking);

        Thread first = new Thread(() -> new AsyncTaskProcessing(Long.MAX_VALUE).run(), "pulse-1");
        first.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "first pulse never reached the region");
            assertTrue(AsyncTaskProcessing.isPulseActive());

            boolean[] drained = {false};
            MiscTasks.async().add(() -> drained[0] = true);
            for (int i = 0; i < 5; i++) new AsyncTaskProcessing(Long.MAX_VALUE).run();

            assertEquals(1, executions.get(), "overlapping ticks must not re-enter the region");
            assertFalse(drained[0], "a skipped tick must not drain pipes");
        } finally {
            release.countDown();
            first.join(5_000);
        }
        assertFalse(AsyncTaskProcessing.isPulseActive(), "guard must clear once the pulse ends");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("performance.yml period: autoTune false refills once per `period` pulses; autoTune true refills every pulse with credit")
    void period_singleRegion_runsOncePerPeriod() {
        @SuppressWarnings("unchecked")
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
        if (perf != null) {
            perf.set(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.autoTune, false);
            TickBudget.refresh();
        }

        AtomicInteger executions = new AtomicInteger();
        Region base = newRegion("countingRegion");
        Region counting = new Region("countingRegion", base.getSettings()) {
            @Override
            public void execute(long allottedTime) {
                executions.incrementAndGet();
            }
        };
        ActiveSelection.get().permRegionLookup.put("countingRegion", counting);

        long period = TickBudget.periodTicks();
        assertTrue(period > 1, "precondition: default period spans several pulses");
        for (long i = 0; i < period * 3; i++) new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertEquals(3, executions.get(), "autoTune false: one turn per period, not per period-1");

        if (perf != null) {
            perf.set(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.autoTune, true);
            TickBudget.refresh();
            ComputeGovernor.active().reset();
            executions.set(0);
            for (long i = 0; i < 5; i++) new AsyncTaskProcessing(Long.MAX_VALUE).run();
            assertEquals(5, executions.get(), "autoTune true: region with credit runs on every pulse");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("asyncAllottedTime: region budget never exceeds the documented 25 ms ceiling")
    void regionBudget_isClampedToCeiling() {
        long[] seen = {-1L};
        Region base = newRegion("budgetRegion");
        Region probe = new Region("budgetRegion", base.getSettings()) {
            @Override
            public void execute(long allottedTime) {
                seen[0] = allottedTime;
            }
        };
        ActiveSelection.get().permRegionLookup.put("budgetRegion", probe);

        new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(seen[0] > 0, "region must receive a positive budget");
        assertTrue(seen[0] <= TimeUnit.MILLISECONDS.toNanos(TickBudget.MAX_ALLOTTED_MS),
                "budget " + seen[0] + "ns exceeds the ceiling");
    }

    @Test
    @DisplayName("sparkFrameName tag returns rtp_async_task_drain")
    void testSparkFrameName() {
        AsyncTaskProcessing proc = new AsyncTaskProcessing(100L);
        assertEquals("rtp_async_task_drain", proc.sparkFrameName());
    }

    @Test
    @DisplayName("SelectionAPI compute drains urgent and standard pipelines, tolerating task errors")
    void selectionApi_compute_drainsPipelines() {
        AtomicInteger urgentRan = new AtomicInteger();
        AtomicInteger standardRan = new AtomicInteger();
        ActiveSelection.get().selectionPipelineUrgent.add(urgentRan::incrementAndGet);
        ActiveSelection.get().selectionPipelineUrgent.add(() -> { throw new RuntimeException("simulated task error"); });
        ActiveSelection.get().selectionPipeline.add(standardRan::incrementAndGet);

        assertDoesNotThrow(() -> ActiveSelection.get().compute());

        assertEquals(1, urgentRan.get());
        assertEquals(1, standardRan.get());
        assertTrue(ActiveSelection.get().selectionPipelineUrgent.isEmpty());
        assertTrue(ActiveSelection.get().selectionPipeline.isEmpty());
    }

    @Test
    @DisplayName("AsyncTaskProcessing uses availableTime fallback when configs is null")
    void run_usesConstructorTimeWhenConfigsNull() {
        io.github.dailystruggle.rtp.common.configuration.Configs saved = ActiveConfigs.get();
        ActiveConfigs.set(null);
        try {
            assertDoesNotThrow(() -> new AsyncTaskProcessing(1_000_000L).run());
        } finally {
            ActiveConfigs.set(saved);
        }
    }
}
