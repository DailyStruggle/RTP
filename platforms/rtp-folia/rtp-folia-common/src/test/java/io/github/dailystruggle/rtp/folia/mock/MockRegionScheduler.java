package io.github.dailystruggle.rtp.folia.mock;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * In-memory, deterministically controllable mock of {@link RegionScheduler}.
 *
 * <p>Submitted tasks are stored in an internal queue instead of being dispatched
 * to real region threads. Call {@link #executeAll()} from a test to drain the
 * queue and run every pending task synchronously on the calling thread.
 */
public class MockRegionScheduler implements RegionScheduler {

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
    public void execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable run) {
        queue.add(run);
    }

    @Override
    public ScheduledTask run(Plugin plugin, World world, int chunkX, int chunkZ,
                             Consumer<ScheduledTask> task) {
        MockScheduledTask scheduled = new MockScheduledTask(plugin, false);
        queue.add(() -> {
            scheduled.markRunning();
            task.accept(scheduled);
            scheduled.markFinished();
        });
        return scheduled;
    }

    @Override
    public ScheduledTask runDelayed(Plugin plugin, World world, int chunkX, int chunkZ,
                                    Consumer<ScheduledTask> task, long delayTicks) {
        // Delay is ignored in the mock — task is queued immediately.
        return run(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public ScheduledTask runAtFixedRate(Plugin plugin, World world, int chunkX, int chunkZ,
                                        Consumer<ScheduledTask> task, long initialDelayTicks,
                                        long periodTicks) {
        // Only one execution is queued per call in the mock.
        MockScheduledTask scheduled = new MockScheduledTask(plugin, true);
        queue.add(() -> {
            scheduled.markRunning();
            task.accept(scheduled);
            scheduled.markFinished();
        });
        return scheduled;
    }
}
