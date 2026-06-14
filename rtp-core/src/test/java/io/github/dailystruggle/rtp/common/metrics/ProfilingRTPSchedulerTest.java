package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ProfilingRTPScheduler} times the tasks it forwards and
 * classifies them into the sync (main-thread / region) and async buckets of
 * {@link RtpSchedulerProfile}, while delegating execution verbatim.
 */
class ProfilingRTPSchedulerTest {

    /** Delegate that runs every submitted Runnable inline so timing is observable in-test. */
    private static final class InlineScheduler implements RTPScheduler {
        @Override public TrackedRTPTask runTaskAsynchronously(Runnable task) { task.run(); return null; }
        @Override public void runTask(Runnable task) { task.run(); }
        @Override public void runTaskLater(Runnable task, long delay) { task.run(); }
        @Override public Object runTaskTimer(Runnable task, long delay, long period) { task.run(); return new Object(); }
        @Override public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) { task.run(); return new Object(); }
        @Override public void cancelTask(Object task) { }
        @Override public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) { }
        @Override public void runTask(RTPLocation location, Runnable task) { task.run(); }
        @Override public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) { task.run(); }
        @Override public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) { task.run(); return new Object(); }
        @Override public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) { task.run(); }
    }

    private ProfilingRTPScheduler scheduler;

    @BeforeEach
    void setUp() {
        RtpSchedulerProfile.GLOBAL.reset();
        scheduler = new ProfilingRTPScheduler(new InlineScheduler());
    }

    @Test
    @DisplayName("sync-family submissions are timed into the sync bucket and executed")
    void syncFamily_recordedAndRun() {
        final int[] runs = {0};
        Runnable r = () -> runs[0]++;
        scheduler.runTask(r);
        scheduler.runTaskLater(r, 5L);
        scheduler.runTask((RTPLocation) null, r);
        scheduler.runTaskTimer(r, 0L, 1L);

        assertEquals(4, runs[0], "every sync task must still execute on the delegate");
        assertEquals(4L, RtpSchedulerProfile.GLOBAL.syncRuns(), "four sync executions must be recorded");
        assertEquals(0L, RtpSchedulerProfile.GLOBAL.asyncRuns(), "no async executions expected");
        assertTrue(RtpSchedulerProfile.GLOBAL.syncNanos() >= 0L, "sync nanos must be non-negative");
    }

    @Test
    @DisplayName("async-family submissions are timed into the async bucket and executed")
    void asyncFamily_recordedAndRun() {
        final int[] runs = {0};
        Runnable r = () -> runs[0]++;
        scheduler.runTaskAsynchronously(r);
        scheduler.runTaskTimerAsynchronously(r, 0L, 1L);

        assertEquals(2, runs[0], "every async task must still execute on the delegate");
        assertEquals(2L, RtpSchedulerProfile.GLOBAL.asyncRuns(), "two async executions must be recorded");
        assertEquals(0L, RtpSchedulerProfile.GLOBAL.syncRuns(), "no sync executions expected");
    }

    @Test
    @DisplayName("runTaskForPlayer is forwarded unwrapped (not profiled)")
    void runTaskForPlayer_notProfiled() {
        scheduler.runTaskForPlayer(null, new RTPRunnable(() -> {}, 0), 0L);
        assertEquals(0L, RtpSchedulerProfile.GLOBAL.syncRuns());
        assertEquals(0L, RtpSchedulerProfile.GLOBAL.asyncRuns());
    }
}
