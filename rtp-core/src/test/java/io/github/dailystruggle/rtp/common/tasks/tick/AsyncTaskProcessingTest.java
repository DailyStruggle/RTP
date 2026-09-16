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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
}
