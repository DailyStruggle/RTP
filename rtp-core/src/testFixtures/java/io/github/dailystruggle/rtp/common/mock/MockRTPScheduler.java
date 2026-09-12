package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure in-memory implementation of {@link RTPScheduler} for use in unit tests.
 *
 * <p>Two execution models are supported:
 *
 * <ul>
 *   <li><b>Synchronous (default):</b> all tasks run immediately on the calling
 *       thread; no threads are spawned. Re-entrant {@link #runTaskAsynchronously}
 *       calls are trampolined so self-dispatching chains (e.g. {@code ScanTask})
 *       do not blow the stack. This preserves the "everything runs before the
 *       initial call returns" semantics the bulk of the suite relies on.
 *   <li><b>Threaded (opt-in via {@link #enableServerThreads()}):</b> models the
 *       native server's thread topology - a single <em>main</em> lane and a small
 *       <em>async</em> worker pool - so tests can assert thread affinity, e.g.
 *       that teleport-style work is enforced onto the main thread while blocking
 *       waits inside an async task resolve against a separate worker. Call
 *       {@link #shutdown()} in teardown to release the lanes.
 * </ul>
 *
 * <p>Timer tasks are queued in both models and advanced via {@link #tick(long)}.
 */
public class MockRTPScheduler implements RTPScheduler {

    private final List<MockTask> scheduledTasks = new ArrayList<>();
    private final AtomicLong currentTick = new AtomicLong(0);

    /**
     * Trampoline queue for re-entrant {@link #runTaskAsynchronously(Runnable)} calls
     * in the synchronous model. Preserves the "everything runs before the initial
     * call returns" semantics existing tests rely on, while replacing recursion
     * with iteration so batch chains don't blow the stack.
     */
    private static final ThreadLocal<Deque<Runnable>> asyncTrampoline = new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // Threaded ("server topology") model
    // -------------------------------------------------------------------------

    /** Logical scheduling lanes mirrored from the native server. */
    public enum Lane { MAIN, ASYNC }

    /** Marks the lane owning the current thread (null on the test/calling thread). */
    private static final ThreadLocal<Lane> CURRENT_LANE = new ThreadLocal<>();

    private volatile boolean threaded = false;
    private volatile ExecutorService mainLane;
    private volatile ExecutorService asyncLane;

    /**
     * Switch this scheduler into the threaded server-topology model: a single
     * main lane plus a two-worker async pool (three threads total, matching a
     * native "1 main thread + async pool" server). Idempotent.
     *
     * <p>The async pool intentionally has more than one worker so that a task
     * which blocks on a nested {@link #runTaskAsynchronously} result (the shape
     * of {@code rtp test scheduler}'s probe) resolves against a free worker
     * instead of dead-locking against itself.
     *
     * @return this scheduler, for chaining
     */
    public synchronized MockRTPScheduler enableServerThreads() {
        if (threaded) return this;
        mainLane = Executors.newSingleThreadExecutor(laneFactory(Lane.MAIN, "rtp-mock-main"));
        asyncLane = Executors.newFixedThreadPool(2, laneFactory(Lane.ASYNC, "rtp-mock-async"));
        threaded = true;
        return this;
    }

    /** @return {@code true} if this scheduler is running in the threaded model. */
    public boolean isThreaded() {
        return threaded;
    }

    /** @return {@code true} if the calling thread is this scheduler's main lane. */
    public boolean isOnMainLane() {
        return CURRENT_LANE.get() == Lane.MAIN;
    }

    /** @return the lane owning the calling thread, or {@code null} off-lane. */
    public Lane currentLane() {
        return CURRENT_LANE.get();
    }

    /** Stop the lane executors (no-op in the synchronous model). */
    public synchronized void shutdown() {
        threaded = false;
        if (mainLane != null) {
            mainLane.shutdownNow();
            mainLane = null;
        }
        if (asyncLane != null) {
            asyncLane.shutdownNow();
            asyncLane = null;
        }
    }

    private static ThreadFactory laneFactory(Lane lane, String name) {
        return r -> {
            Thread t =
                    new Thread(
                            () -> {
                                CURRENT_LANE.set(lane);
                                r.run();
                            },
                            name);
            t.setDaemon(true);
            return t;
        };
    }

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

        if (threaded) {
            // Hand off to the async pool. A separate worker executes the task,
            // so a caller that later blocks on its result does not stall itself.
            asyncLane.submit((Runnable) trackedTask);
            return trackedTask;
        }

        // Synchronous model: trampoline nested runTaskAsynchronously calls (e.g.
        // a task that re-dispatches itself at the end of run()) onto the calling
        // thread's deque, returning immediately. The outermost call drains the
        // deque iteratively. Preserves "runs before the call returns" semantics
        // without growing the stack frame-for-frame with each self-re-dispatch.
        Deque<Runnable> queue = asyncTrampoline.get();
        if (queue != null) {
            queue.addLast(trackedTask);
            return trackedTask;
        }
        queue = new ArrayDeque<>();
        asyncTrampoline.set(queue);
        try {
            trackedTask.run();
            Runnable next;
            while ((next = queue.pollFirst()) != null) {
                next.run();
            }
        } finally {
            asyncTrampoline.remove();
        }
        return trackedTask;
    }

    @Override
    public void runTask(Runnable task) {
        dispatchMain(task);
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
    public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) {
        String taskId = UUID.randomUUID().toString();
        TrackedRTPTask trackedTask = new TrackedRTPTask(task, taskId);
        if (io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor != null) {
            io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor.registerAction(trackedTask);
        }
        runTaskLater(trackedTask, delayTicks);
    }

    @Override
    public void runTask(RTPLocation location, Runnable task) {
        // Region tier: on non-Folia platforms the region owner is the main
        // thread, so route region work onto the main lane.
        dispatchMain(task);
    }

    @Override
    public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
        dispatchMain(task);
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
     * Dispatch synchronous ("primary"/region) work. In the synchronous model
     * this runs inline. In the threaded model it is enforced onto the main lane;
     * work already on the main lane runs inline to avoid self-deadlock.
     */
    private void dispatchMain(Runnable task) {
        if (!threaded || CURRENT_LANE.get() == Lane.MAIN) {
            task.run();
            return;
        }
        mainLane.submit(task);
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
