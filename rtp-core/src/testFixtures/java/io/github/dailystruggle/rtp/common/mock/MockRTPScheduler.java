package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure in-memory, synchronous implementation of {@link RTPScheduler} for use in unit tests.
 *
 * <p>All tasks run immediately on the calling thread (no threads are spawned).
 * Timer tasks are queued and can be advanced via {@link #tick(long)}.
 * This removes any dependency on a real Bukkit/Paper/Folia scheduler.
 */
public class MockRTPScheduler implements RTPScheduler {

    private final List<MockTask> scheduledTasks = new ArrayList<>();
    private final AtomicLong currentTick = new AtomicLong(0);

    private static class MockTask {
        final Runnable runnable;
        long nextRun;
        final long period;
        boolean cancelled = false;

        MockTask(Runnable runnable, long nextRun, long period) {
            this.runnable = runnable;
            this.nextRun = nextRun;
            this.period = period;
        }
    }

    @Override
    public TrackedRTPTask runTaskAsynchronously(Runnable task) {
        String taskId = UUID.randomUUID().toString();
        RTPRunnable rtpRunnable = task instanceof RTPRunnable
                ? (RTPRunnable) task
                : new RTPRunnable() {
                    @Override
                    public void run() { task.run(); }
                };
        TrackedRTPTask trackedTask = new TrackedRTPTask(rtpRunnable, taskId);
        if (io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor != null) {
            io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor.registerAction(trackedTask);
        }
        trackedTask.run();
        return trackedTask;
    }

    @Override
    public void runTask(Runnable task) {
        task.run();
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        scheduledTasks.add(new MockTask(task, currentTick.get() + delay, -1));
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
        MockTask mockTask = new MockTask(task, currentTick.get() + delay, period);
        scheduledTasks.add(mockTask);
        return mockTask;
    }

    @Override
    public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof MockTask) {
            ((MockTask) task).cancelled = true;
        }
    }

    @Override
    public void scheduleTeleport(RTPPlayer player, RTPRunnable task, long delayTicks) {
        String taskId = UUID.randomUUID().toString();
        TrackedRTPTask trackedTask = new TrackedRTPTask(task, taskId);
        if (io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor != null) {
            io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor.registerAction(trackedTask);
        }
        runTaskLater(trackedTask, delayTicks);
    }

    @Override
    public void runTask(RTPLocation location, Runnable task) {
        task.run();
    }

    @Override
    public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
        task.run();
    }

    @Override
    public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    @Override
    public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
        runTaskLater(task, delay);
    }

    /**
     * Advance the mock clock by {@code ticks} and run any tasks that are due.
     *
     * @param ticks number of ticks to advance
     */
    public void tick(long ticks) {
        currentTick.addAndGet(ticks);
        List<MockTask> toRun = new ArrayList<>();
        List<MockTask> copy = new ArrayList<>(scheduledTasks);
        for (MockTask task : copy) {
            if (task.cancelled) {
                scheduledTasks.remove(task);
                continue;
            }
            if (task.nextRun <= currentTick.get()) {
                toRun.add(task);
                if (task.period <= 0) {
                    scheduledTasks.remove(task);
                } else {
                    task.nextRun += task.period;
                }
            }
        }
        for (MockTask task : toRun) {
            task.runnable.run();
        }
    }

    /** Returns the current simulated tick count. */
    public long getCurrentTick() {
        return currentTick.get();
    }
}
