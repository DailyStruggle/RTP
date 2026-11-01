package io.github.dailystruggle.rtp.folia.mock;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-memory, deterministically controllable mock of {@link AsyncScheduler}.
 *
 * <p>Submitted tasks are stored in an internal queue instead of being dispatched
 * to a real thread pool. Call {@link #executeAll()} from a test to drain the
 * queue and run every pending task synchronously on the calling thread.
 */
public class MockAsyncScheduler implements AsyncScheduler {

    private final Queue<Runnable> queue = new ArrayDeque<>();

    /**
     * Drains the internal task queue and executes every pending task
     * synchronously on the calling thread.
     *
     * @return the number of tasks that were executed
     */
    public int executeAll() {
        int count = 0;
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
            count++;
        }
        return count;
    }

    /** Returns the number of tasks currently waiting in the queue. */
    public int pendingCount() {
        return queue.size();
    }

    @Override
    public ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task) {
        MockScheduledTask scheduled = new MockScheduledTask(plugin, false);
        queue.add(() -> {
            scheduled.markRunning();
            task.accept(scheduled);
            scheduled.markFinished();
        });
        return scheduled;
    }

    @Override
    public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                    long delay, TimeUnit unit) {
        // Delay is ignored in the mock - task is queued immediately.
        return runNow(plugin, task);
    }

    @Override
    public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                        long initialDelay, long period, TimeUnit unit) {
        // Only one execution is queued per call in the mock.
        MockScheduledTask scheduled = new MockScheduledTask(plugin, true);
        queue.add(() -> {
            scheduled.markRunning();
            task.accept(scheduled);
            scheduled.markFinished();
        });
        return scheduled;
    }

    @Override
    public void cancelTasks(Plugin plugin) {
        queue.clear();
    }
}
