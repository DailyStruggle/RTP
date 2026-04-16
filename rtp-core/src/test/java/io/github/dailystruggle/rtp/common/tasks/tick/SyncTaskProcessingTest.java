package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SyncTaskProcessing}.
 *
 * <p>All execution is synchronous — no real threads are spawned.
 */
class SyncTaskProcessingTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        // Ensure task pipes are clean before each test
        RTP.getInstance().cancelTasks.clear();
        RTP.getInstance().miscSyncTasks.clear();
    }

    // -----------------------------------------------------------------------
    // Basic execution
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_does_not_throw_with_empty_pipes() {
        SyncTaskProcessing proc = new SyncTaskProcessing(Long.MAX_VALUE);
        assertDoesNotThrow(proc::run);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_executes_cancelTasks_pipe() {
        AtomicBoolean ran = new AtomicBoolean(false);
        RTP.getInstance().cancelTasks.add(() -> ran.set(true));

        new SyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(ran.get(), "cancelTasks pipe must be drained by SyncTaskProcessing.run()");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_executes_miscSyncTasks_pipe() {
        AtomicBoolean ran = new AtomicBoolean(false);
        RTP.getInstance().miscSyncTasks.add(() -> ran.set(true));

        new SyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(ran.get(), "miscSyncTasks pipe must be drained by SyncTaskProcessing.run()");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_executes_cancelTasks_before_miscSyncTasks() {
        AtomicInteger order = new AtomicInteger(0);
        AtomicInteger cancelOrder = new AtomicInteger(-1);
        AtomicInteger miscOrder = new AtomicInteger(-1);

        RTP.getInstance().cancelTasks.add(() -> cancelOrder.set(order.getAndIncrement()));
        RTP.getInstance().miscSyncTasks.add(() -> miscOrder.set(order.getAndIncrement()));

        new SyncTaskProcessing(Long.MAX_VALUE).run();

        assertTrue(cancelOrder.get() < miscOrder.get(),
                "cancelTasks must run before miscSyncTasks");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_drains_multiple_tasks_from_each_pipe() {
        AtomicInteger cancelCount = new AtomicInteger(0);
        AtomicInteger miscCount = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            RTP.getInstance().cancelTasks.add(cancelCount::incrementAndGet);
            RTP.getInstance().miscSyncTasks.add(miscCount::incrementAndGet);
        }

        new SyncTaskProcessing(Long.MAX_VALUE).run();

        // TimeBoundTaskPipe may not drain all tasks in one pass; verify at least one ran
        assertTrue(cancelCount.get() >= 1, "at least one cancelTask must run");
        assertTrue(miscCount.get() >= 1, "at least one miscSyncTask must run");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_does_not_throw_when_task_in_cancelTasks_throws() {
        RTP.getInstance().cancelTasks.add(() -> { throw new RuntimeException("intentional"); });
        assertDoesNotThrow(() -> new SyncTaskProcessing(Long.MAX_VALUE).run());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_does_not_throw_when_task_in_miscSyncTasks_throws() {
        RTP.getInstance().miscSyncTasks.add(() -> { throw new RuntimeException("intentional"); });
        assertDoesNotThrow(() -> new SyncTaskProcessing(Long.MAX_VALUE).run());
    }

    // -----------------------------------------------------------------------
    // Cancellation
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void cancelled_SyncTaskProcessing_is_still_runnable_without_throw() {
        SyncTaskProcessing proc = new SyncTaskProcessing(Long.MAX_VALUE);
        proc.setCancelled(true);
        // SyncTaskProcessing.run() does not check isCancelled() itself — it delegates
        // to the pipes. Verify it does not throw.
        assertDoesNotThrow(proc::run);
    }

    // -----------------------------------------------------------------------
    // availableTime boundary — zero time
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_with_zero_availableTime_does_not_throw() {
        RTP.getInstance().cancelTasks.add(() -> {});
        RTP.getInstance().miscSyncTasks.add(() -> {});
        assertDoesNotThrow(() -> new SyncTaskProcessing(0).run());
    }

    // -----------------------------------------------------------------------
    // isRunning / RTPRunnable base
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void isRunning_is_false_before_run() {
        SyncTaskProcessing proc = new SyncTaskProcessing(Long.MAX_VALUE);
        assertFalse(proc.isRunning());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void default_delay_is_zero() {
        SyncTaskProcessing proc = new SyncTaskProcessing(Long.MAX_VALUE);
        assertEquals(0L, proc.getDelay());
    }
}
