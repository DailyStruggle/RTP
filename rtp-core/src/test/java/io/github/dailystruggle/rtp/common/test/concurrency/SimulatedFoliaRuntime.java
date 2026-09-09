package io.github.dailystruggle.rtp.common.test.concurrency;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless Folia scheduler simulator for concurrency, tick budget, and backpressure testing (ADR-090, ADR-087).
 * <p>
 * Simulates Folia's threaded regional execution by maintaining:
 * <ul>
 *   <li>A regional worker thread pool bounded to {@code maxWorkerThreads}</li>
 *   <li>Regional task dispatch tracking partitioned by chunk coordinate keys</li>
 *   <li>Simulated MSPT (tick duration) to verify adaptive period floating and backpressure shedding</li>
 * </ul>
 */
public final class SimulatedFoliaRuntime implements RTPScheduler, AutoCloseable {

    private final int maxWorkerThreads;
    private final ExecutorService workerPool;
    private final AtomicInteger activeRegionalTasks = new AtomicInteger(0);
    private final AtomicInteger maxObservedConcurrentTasks = new AtomicInteger(0);
    private final ConcurrentHashMap<Long, AtomicInteger> regionTaskCounts = new ConcurrentHashMap<>();
    private final AtomicLong simulatedTick = new AtomicLong(0);
    private final AtomicLong simulatedMspt = new AtomicLong(15); // Baseline 15ms healthy MSPT
    private final List<SimulatedTimerTask> scheduledTasks = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static class SimulatedTimerTask {
        final Runnable runnable;
        long nextRun;
        final long period;
        boolean cancelled = false;

        SimulatedTimerTask(Runnable runnable, long nextRun, long period) {
            this.runnable = runnable;
            this.nextRun = nextRun;
            this.period = period;
        }
    }

    public SimulatedFoliaRuntime(int maxWorkerThreads) {
        this.maxWorkerThreads = maxWorkerThreads;
        this.workerPool = Executors.newFixedThreadPool(maxWorkerThreads);
    }

    /**
     * Set simulated MSPT in milliseconds to simulate tick degradation / spikes.
     */
    public void setSimulatedMspt(long mspt) {
        this.simulatedMspt.set(mspt);
    }

    public long getSimulatedMspt() {
        return simulatedMspt.get();
    }

    public int getActiveRegionalTasks() {
        return activeRegionalTasks.get();
    }

    public int getMaxObservedConcurrentTasks() {
        return maxObservedConcurrentTasks.get();
    }

    @Override
    public TrackedRTPTask runTaskAsynchronously(Runnable task) {
        String taskId = UUID.randomUUID().toString();
        RTPRunnable rtpRunnable = (task instanceof RTPRunnable) ? (RTPRunnable) task : new RTPRunnable() {
            @Override
            public void run() { task.run(); }
        };
        TrackedRTPTask trackedTask = new TrackedRTPTask(rtpRunnable, taskId);
        workerPool.execute(trackedTask);
        return trackedTask;
    }

    @Override
    public void runTask(Runnable task) {
        task.run();
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        synchronized (scheduledTasks) {
            scheduledTasks.add(new SimulatedTimerTask(task, simulatedTick.get() + delay, -1));
        }
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
        SimulatedTimerTask t = new SimulatedTimerTask(task, simulatedTick.get() + delay, period);
        synchronized (scheduledTasks) {
            scheduledTasks.add(t);
        }
        return t;
    }

    @Override
    public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof SimulatedTimerTask) {
            ((SimulatedTimerTask) task).cancelled = true;
        }
    }

    @Override
    public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) {
        runTaskLater(task, delayTicks);
    }

    @Override
    public void runTask(RTPLocation location, Runnable task) {
        int cx = (int) (location.x() >> 4);
        int cz = (int) (location.z() >> 4);
        runTask(location.world(), cx, cz, task);
    }

    @Override
    public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
        long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        regionTaskCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();

        workerPool.execute(() -> {
            activeRegionalTasks.incrementAndGet();
            maxObservedConcurrentTasks.updateAndGet(curr -> Math.max(curr, activeRegionalTasks.get()));
            try {
                task.run();
            } finally {
                activeRegionalTasks.decrementAndGet();
                regionTaskCounts.get(key).decrementAndGet();
            }
        });
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
     * Advances simulated time by {@code ticks} and executes due tasks.
     */
    public void tick(long ticks) {
        simulatedTick.addAndGet(ticks);
        List<SimulatedTimerTask> toRun = new ArrayList<>();
        synchronized (scheduledTasks) {
            List<SimulatedTimerTask> copy = new ArrayList<>(scheduledTasks);
            for (SimulatedTimerTask t : copy) {
                if (t.cancelled) {
                    scheduledTasks.remove(t);
                    continue;
                }
                if (t.nextRun <= simulatedTick.get()) {
                    toRun.add(t);
                    if (t.period <= 0) {
                        scheduledTasks.remove(t);
                    } else {
                        t.nextRun += t.period;
                    }
                }
            }
        }
        for (SimulatedTimerTask t : toRun) {
            t.runnable.run();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
