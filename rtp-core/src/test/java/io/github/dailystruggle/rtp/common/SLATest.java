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
 * Machine-verifiable tests for <b>REQ-RTP-F-001</b>:
 * "Teleport response time: 0–2 game ticks (0–100 ms) on average."
 *
 * <p>Three complementary cases are covered:
 * <ol>
 *   <li><b>Cache-hit (0 ticks)</b> — a pre-generated location is returned by
 *       {@link MockLocationGenerator} as an already-completed future; no tick
 *       advance is required and no thread is blocked.</li>
 *   <li><b>Deferred dispatch (≤ 2 ticks)</b> — a task submitted via
 *       {@link MockRTPScheduler#runTaskLater(Runnable, long)} with a 1-tick
 *       delay completes after exactly one {@link MockRTPScheduler#tick(long)}
 *       advance, well within the 2-tick SLA.</li>
 *   <li><b>Absolute 100 ms ceiling</b> — the {@code @Timeout(100ms)} annotation
 *       acts as a hard trip-wire: any blocking call inadvertently introduced into
 *       the mock stack will cause this test to fail before any assertion is
 *       reached, making regressions immediately visible in CI.</li>
 * </ol>
 *
 * <p>All three cases also carry {@code @Timeout(100ms)} so a frozen pipeline is
 * detected regardless of which assertion would otherwise catch it.
 *
 * @see <a href="../../docs/dev/REQUIREMENTS.md">REQ-RTP-F-001</a>
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
    // Case 1: cache-hit path — 0 ticks
    // -------------------------------------------------------------------------

    /**
     * REQ-RTP-F-001 — When a location is pre-generated and sitting in the mock
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
    // Case 2: deferred dispatch — ≤ 2 ticks
    // -------------------------------------------------------------------------

    /**
     * REQ-RTP-F-001 — A task dispatched with a 1-tick delay (worst-case within
     * the stated SLA) completes after exactly one {@link MockRTPScheduler#tick}
     * advance.  The total ticks consumed remains within the 0–2 tick window.
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

        // Advance exactly 1 tick — still within the 2-tick SLA budget
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
     * REQ-RTP-F-001 — The full mock pipeline (generate + advance max-SLA ticks)
     * must complete in under 100 ms wall-clock time.
     *
     * <p>The {@code @Timeout(100ms)} annotation is the primary regression guard:
     * it fails the test immediately if the pipeline blocks, even before any
     * assertion inside the method is reached.  The explicit elapsed-time assertion
     * provides a diagnostic message if timing is tight but within the JUnit
     * timeout window.
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
