package io.github.dailystruggle.rtp.spigot.scheduling;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.scheduler.BukkitSchedulerMock;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-tests for {@link BukkitSchedulerImpl} using MockBukkit.
 *
 * <p>Covers four dispatch paths:
 * <ol>
 *   <li>Synchronous execution on the main thread.</li>
 *   <li>Asynchronous task dispatch and completion.</li>
 *   <li>Repeating timer returning a cancellable handle.</li>
 *   <li>Task cancellation preventing future execution.</li>
 * </ol>
 *
 * <p>Traceability: REQ-NF-002 (cross-platform thread safety).
 */
class BukkitSchedulerImplTest {

    private MockPlugin plugin;
    private BukkitSchedulerImpl scheduler;
    private BukkitSchedulerMock bukkitScheduler;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        scheduler = new BukkitSchedulerImpl(plugin);
        bukkitScheduler = (BukkitSchedulerMock) org.bukkit.Bukkit.getScheduler();
        // Ensure RTPAPI.serverAccessor is null so registerAction is skipped safely
        RTPAPI.serverAccessor = null;
    }

    @AfterEach
    void tearDown() {
        RTPAPI.serverAccessor = null;
        MockBukkit.unmock();
    }

    /**
     * When called from the primary thread, {@code runTask} must execute the
     * runnable immediately and synchronously — no tick advance required.
     * MockBukkit designates the test thread as the primary (main) thread.
     */
    @Test
    @Timeout(2)
    void runTask_onPrimaryThread_executesImmediately() {
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.runTask(() -> ran.set(true));

        assertTrue(ran.get(), "runTask must execute synchronously on the primary thread");
    }

    /**
     * {@code runTaskAsynchronously} must dispatch the task and it must complete
     * before {@link BukkitSchedulerMock#waitAsyncTasksFinished()} returns.
     * The returned {@link TrackedRTPTask} must be non-null.
     */
    @Test
    @Timeout(5)
    void runTaskAsynchronously_dispatchesTask_andCompletes() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean(false);

        TrackedRTPTask handle = scheduler.runTaskAsynchronously(() -> ran.set(true));

        assertNotNull(handle, "runTaskAsynchronously must return a non-null TrackedRTPTask");
        bukkitScheduler.waitAsyncTasksFinished();
        assertTrue(ran.get(), "Async task must complete before waitAsyncTasksFinished returns");
    }

    /**
     * {@code runTaskTimer} must return a non-null {@link BukkitTask} handle that
     * can later be passed to {@link BukkitSchedulerImpl#cancelTask(Object)}.
     */
    @Test
    @Timeout(2)
    void runTaskTimer_returnsNonNullBukkitTaskHandle() {
        Object handle = scheduler.runTaskTimer(() -> {}, 1L, 20L);

        assertNotNull(handle, "runTaskTimer must return a non-null handle");
        assertInstanceOf(BukkitTask.class, handle, "Handle must be a BukkitTask");
    }

    /**
     * A task cancelled before its scheduled tick must never execute, even after
     * the scheduler is advanced past the original delay.
     */
    @Test
    @Timeout(2)
    void cancelTask_preventsScheduledTaskFromRunning() {
        AtomicBoolean ran = new AtomicBoolean(false);
        Object handle = scheduler.runTaskTimer(() -> ran.set(true), 50L, 20L);

        scheduler.cancelTask(handle);
        bukkitScheduler.performTicks(100);

        assertFalse(ran.get(), "Cancelled task must not run when ticks are advanced past its delay");
    }
}
