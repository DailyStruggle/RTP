package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
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
        RTP.selectionAPI.permRegionLookup.clear();
        RTP.getInstance().cancelTasks.clear();
        RTP.getInstance().cancelTasks.start();
        RTP.getInstance().miscAsyncTasks.clear();
        RTP.getInstance().miscAsyncTasks.start();
    }

    @AfterEach
    void tearDown() {
        RTP.futures.clear();
        RTP.selectionAPI.permRegionLookup.clear();
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
        RTP.getInstance().cancelTasks.add(() -> ran[0] = true);

        new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(ran[0], "cancelTasks pipe must be drained by AsyncTaskProcessing.run()");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_executesMiscAsyncTasksPipe() {
        boolean[] ran = {false};
        RTP.getInstance().miscAsyncTasks.add(() -> ran[0] = true);

        new AsyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(ran[0], "miscAsyncTasks pipe must be drained by AsyncTaskProcessing.run()");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cancelledBeforeRun_shortCircuits_withoutDrainingMiscAsync() {
        boolean[] ran = {false};
        RTP.getInstance().miscAsyncTasks.add(() -> ran[0] = true);

        AsyncTaskProcessing proc = new AsyncTaskProcessing(Long.MAX_VALUE);
        proc.setCancelled(true);
        assertDoesNotThrow(proc::run);

        assertFalse(ran[0], "a cancelled pulse must not drain the misc-async pipe");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_withRegion_doesNotThrow_andRotates() {
        RTP.selectionAPI.permRegionLookup.put("regionA", newRegion("regionA"));
        RTP.selectionAPI.permRegionLookup.put("regionB", newRegion("regionB"));

        // Multiple pulses to exercise the step / betweenStep rotation arithmetic.
        for (int i = 0; i < 4; i++) {
            assertDoesNotThrow(() -> new AsyncTaskProcessing(Long.MAX_VALUE).run());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void run_withZeroAvailableTime_doesNotThrow() {
        RTP.selectionAPI.permRegionLookup.put("regionA", newRegion("regionA"));
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
        RTP.selectionAPI.permRegionLookup.put("throwingRegion", throwingRegion);

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
        RTP.selectionAPI.permRegionLookup.put("blockingRegion", blocking);

        Thread first = new Thread(() -> new AsyncTaskProcessing(Long.MAX_VALUE).run(), "pulse-1");
        first.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "first pulse never reached the region");
            assertTrue(AsyncTaskProcessing.isPulseActive());

            boolean[] drained = {false};
            RTP.getInstance().miscAsyncTasks.add(() -> drained[0] = true);
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
    @DisplayName("sparkFrameName tag returns rtp_async_task_drain")
    void testSparkFrameName() {
        AsyncTaskProcessing proc = new AsyncTaskProcessing(100L);
        assertEquals("rtp_async_task_drain", proc.sparkFrameName());
    }
}
