package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.mock.MockLocationGenerator;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-F-001 tests: teleport response time SLA (0-2 ticks / 0-100 ms).
 * Covers cache-hit (0 ticks), deferred dispatch (<= 2 ticks), and 100 ms timeout bounds.
 */
class SLATest {

    @TempDir
    File tempDir;

    private MockRTPScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
        scheduler = accessor.getMockScheduler();
    }

    // -------------------------------------------------------------------------
    // Case 1: cache-hit path - 0 ticks
    // -------------------------------------------------------------------------

    /**
     * REQ-RTP-F-001 - When a location is pre-generated and sitting in the mock
     * queue, {@link MockLocationGenerator#getLocation} returns an already-completed
     * future before any tick advance occurs.
     */
    @Test
    @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
    void cacheHitPath_completesInZeroTicks() throws ExecutionException, InterruptedException {
        long ticksBefore = scheduler.getCurrentTick();
        MockLocationGenerator gen = new MockLocationGenerator();

        CompletableFuture<GenerationResult> future = gen.getLocation(new Object(), Set.of());

        assertTrue(future.isDone(),
                "Cache-hit path must return an already-completed future — no ticks required");
        assertNotNull(future.get(),
                "GenerationResult must be non-null on cache-hit");

        long ticksConsumed = scheduler.getCurrentTick() - ticksBefore;
        assertEquals(0, ticksConsumed,
                "REQ-RTP-F-001: cache-hit must consume 0 scheduler ticks");
    }

    // -------------------------------------------------------------------------
    // Case 2: deferred dispatch - ≤ 2 ticks
    // -------------------------------------------------------------------------

    /**
     * REQ-RTP-F-001 - A task dispatched with a 1-tick delay (worst-case within
     * the stated SLA) completes after exactly one {@link MockRTPScheduler#tick}
     * advance.  The total ticks consumed remains within the 0-2 tick window.
     */
    @Test
    @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
    void deferredDispatchPath_completesWithinTwoTicks() {
        AtomicBoolean taskRan = new AtomicBoolean(false);
        long startTick = scheduler.getCurrentTick();

        // Simulate a location-delivery task dispatched with a 1-tick delay
        scheduler.runTaskLater(() -> taskRan.set(true), 1L);

        assertFalse(taskRan.get(),
                "Task must not run before the scheduler is advanced");

        // Advance exactly 1 tick - still within the 2-tick SLA budget
        scheduler.tick(1);

        assertTrue(taskRan.get(),
                "REQ-RTP-F-001: deferred task must complete after 1 tick advance");

        long ticksConsumed = scheduler.getCurrentTick() - startTick;
        assertTrue(ticksConsumed <= 2,
                "REQ-RTP-F-001: total ticks consumed (" + ticksConsumed + ") must be ≤ 2");
    }

    // -------------------------------------------------------------------------
    // Case 3: absolute 100 ms wall-clock ceiling
    // -------------------------------------------------------------------------

    /**
     * REQ-RTP-F-001: Pipeline completes in under 100 ms wall-clock time.
     * Timeout annotation fails the test immediately if execution blocks.
     */
    @Test
    @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
    void absoluteCeiling_pipelineCompletesUnder100ms() throws ExecutionException, InterruptedException {
        MockLocationGenerator gen = new MockLocationGenerator();
        long t0 = System.currentTimeMillis();

        // Full mock SLA window: generate + advance the maximum allowed 2 ticks
        CompletableFuture<GenerationResult> future = gen.getLocation(new Object(), Set.of());
        scheduler.tick(2);

        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(future.isDone(),
                "Pipeline result must be available by tick 2");
        assertTrue(elapsed < 100,
                "REQ-RTP-F-001: wall-clock must be < 100 ms (was " + elapsed + " ms)");
    }
}
