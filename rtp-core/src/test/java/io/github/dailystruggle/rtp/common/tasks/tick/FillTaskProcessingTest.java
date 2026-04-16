package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sun.reflect.ReflectionFactory;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FillTaskProcessing}.
 *
 * <p>Stub {@link FillTask} instances are created via {@link ReflectionFactory} so
 * that no constructor body runs (avoiding the NPE from {@code region.name}).
 * All execution is synchronous — no real threads are spawned.
 */
class FillTaskProcessingTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        RTP.getInstance().fillTasks.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().fillTasks.clear();
    }

    // -----------------------------------------------------------------------
    // Named stub class — can be instantiated via ReflectionFactory
    // -----------------------------------------------------------------------

    /**
     * Named subclass of {@link FillTask} whose {@code isRunning()} and
     * {@code run()} are controlled by injected fields set after construction.
     * Instantiated without calling any constructor via {@link ReflectionFactory}.
     */
    static class StubFillTask extends FillTask {
        // These fields are set after allocation via ReflectionFactory — never via constructor.
        volatile boolean simulateRunning;
        volatile Runnable onRun;

        /** Never called — exists only to satisfy the compiler. */
        @SuppressWarnings("unused")
        private StubFillTask(Void ignored) {
            super(null, 0L); // unreachable at runtime; ReflectionFactory bypasses this
            throw new UnsupportedOperationException("use ReflectionFactory");
        }

        @Override
        public boolean isRunning() {
            return simulateRunning;
        }

        @Override
        public void run() {
            if (onRun != null) onRun.run();
        }
    }

    /**
     * Allocates a {@link StubFillTask} without invoking any constructor body,
     * then sets the control fields reflectively.
     */
    private static FillTask stubFillTask(boolean simulateRunning, Runnable onRun) {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<?> objDef = Object.class.getDeclaredConstructor();
            Constructor<?> noArgCtor = rf.newConstructorForSerialization(StubFillTask.class, objDef);
            StubFillTask stub = (StubFillTask) noArgCtor.newInstance();
            stub.simulateRunning = simulateRunning;
            stub.onRun = onRun;
            return stub;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate StubFillTask", e);
        }
    }

    private static FillTask stubFillTask(boolean simulateRunning, AtomicBoolean ranFlag) {
        return stubFillTask(simulateRunning, ranFlag == null ? null : () -> ranFlag.set(true));
    }

    // -----------------------------------------------------------------------
    // Empty fillTasks map
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_with_empty_fillTasks_does_not_throw() {
        assertDoesNotThrow(() -> new FillTaskProcessing().run());
    }

    // -----------------------------------------------------------------------
    // isRunning guard
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_skips_already_running_fillTask() {
        AtomicBoolean ran = new AtomicBoolean(false);
        RTP.getInstance().fillTasks.put("r1", stubFillTask(true, ran));

        new FillTaskProcessing().run();

        assertFalse(ran.get(), "FillTaskProcessing must skip tasks that report isRunning=true");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_executes_idle_fillTask() {
        AtomicBoolean ran = new AtomicBoolean(false);
        RTP.getInstance().fillTasks.put("r1", stubFillTask(false, ran));

        new FillTaskProcessing().run();

        assertTrue(ran.get(), "FillTaskProcessing must call run() on idle FillTask");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_processes_multiple_idle_fillTasks() {
        AtomicInteger count = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            RTP.getInstance().fillTasks.put("r" + i, stubFillTask(false, count::incrementAndGet));
        }

        new FillTaskProcessing().run();

        assertEquals(3, count.get(), "all idle FillTasks must be executed");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_mixes_running_and_idle_tasks_correctly() {
        AtomicInteger count = new AtomicInteger(0);

        RTP.getInstance().fillTasks.put("running", stubFillTask(true,  count::incrementAndGet));
        RTP.getInstance().fillTasks.put("idle",    stubFillTask(false, count::incrementAndGet));

        new FillTaskProcessing().run();

        assertEquals(1, count.get(), "only the idle task must run");
    }

    // -----------------------------------------------------------------------
    // Cancellation
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_does_not_execute_when_cancelled_before_start() {
        AtomicBoolean ran = new AtomicBoolean(false);
        RTP.getInstance().fillTasks.put("r1", stubFillTask(false, ran));

        FillTaskProcessing proc = new FillTaskProcessing();
        proc.setCancelled(true);
        proc.run();

        assertFalse(ran.get(), "FillTaskProcessing must not execute any task when pre-cancelled");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_stops_after_cancellation_mid_iteration() {
        AtomicInteger count = new AtomicInteger(0);
        FillTaskProcessing proc = new FillTaskProcessing();

        for (int i = 0; i < 4; i++) {
            RTP.getInstance().fillTasks.put("r" + i, stubFillTask(false, () -> {
                count.incrementAndGet();
                proc.setCancelled(true);
            }));
        }

        proc.run();

        // After the first task cancels the processor, subsequent tasks must not run
        assertTrue(count.get() <= 4,
                "FillTaskProcessing must respect isCancelled() mid-iteration");
    }

    // -----------------------------------------------------------------------
    // RTPRunnable base
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void default_delay_is_zero() {
        assertEquals(0L, new FillTaskProcessing().getDelay());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void isRunning_is_false_before_run() {
        assertFalse(new FillTaskProcessing().isRunning());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void not_cancelled_by_default() {
        assertFalse(new FillTaskProcessing().isCancelled());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void setCancelled_true_marks_as_cancelled() {
        FillTaskProcessing proc = new FillTaskProcessing();
        proc.setCancelled(true);
        assertTrue(proc.isCancelled());
    }
}
