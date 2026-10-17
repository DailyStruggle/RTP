package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPCancellable;
import io.github.dailystruggle.rtp.common.tasks.RTPDelayable;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;

/**
 * Count- and time-bound pipe for Folia (ADR-004, ADR-112). Each {@link #execute} runs at most
 * {@code maxTasksPerTick} tasks and stops inline work once {@code availableTime} is spent on the
 * draining thread. Region-bound tasks ({@link RTPRunnable#getLocation()} non-null) are handed to
 * the owning region instead of running here, count toward the task cap, and are charged to that
 * region's own budget by the scheduler, so a pulse divides work across region threads instead of
 * running it back to back on the drainer.
 *
 * <p>Parity with {@code TimeBoundTaskPipe}: a positive budget always runs at least one due task
 * (progress), a budget of zero or less runs nothing, cancelled tasks are discarded, a task with
 * {@code delay > 0} is held back and its delay decremented once per execute, and each task runs
 * in its own try/catch so one failure cannot abort the drain (S-004: logged).
 */
public class CountBoundTaskPipe extends RTPTaskPipe {
    private final Plugin plugin;
    private final int maxTasksPerTick;

    public CountBoundTaskPipe(Plugin plugin, int maxTasksPerTick) {
        this.plugin = plugin;
        this.maxTasksPerTick = Math.max(1, maxTasksPerTick);
    }

    @Override
    public boolean execute() {
        return execute(Long.MAX_VALUE);
    }

    @Override
    public boolean execute(long availableTime) {
        if (stop) return true;
        if (runnables.isEmpty()) return true;
        if (availableTime <= 0) return false;
        long start = System.nanoTime();
        List<Runnable> delayed = new ArrayList<>();
        int budget = 0;
        try {
            // Clock checked only after the first task: a 1 ns budget still guarantees progress.
            while (budget < maxTasksPerTick && (budget == 0 || System.nanoTime() - start < availableTime)) {
                if (stop) return true;
                Runnable runnable = runnables.poll();
                if (runnable == null) break;
                if (isCancelled(runnable)) continue;
                if (runnable instanceof RTPDelayable delayable && delayable.getDelay() > 0) {
                    delayed.add(runnable);
                    continue;
                }
                budget++;
                RTPLocation location = runnable instanceof RTPRunnable rtpRunnable ? rtpRunnable.getLocation() : null;
                if (location == null) {
                    runIsolated(runnable);
                } else {
                    dispatchToRegion(location, runnable);
                }
            }
        } finally {
            for (Runnable runnable : delayed) {
                RTPDelayable delayable = (RTPDelayable) runnable;
                delayable.setDelay(delayable.getDelay() - 1);
            }
            if (!stop) runnables.addAll(delayed);
        }
        return runnables.isEmpty();
    }

    private static boolean isCancelled(Runnable runnable) {
        return runnable instanceof RTPCancellable cancellable && cancellable.isCancelled();
    }

    private static void runIsolated(Runnable runnable) {
        try {
            if (runnable instanceof RTPRunnable rtpRunnable) rtpRunnable.runWithTracking();
            else runnable.run();
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Count-bound pipe task failed: " + t.getMessage(), t);
        }
    }

    private static void dispatchToRegion(RTPLocation location, Runnable runnable) {
        try {
            RTPScheduler scheduler = RTP.serverAccessor != null ? RTP.serverAccessor.getScheduler() : null;
            if (scheduler == null) scheduler = RTP.scheduler;
            if (scheduler == null) {
                throw new IllegalStateException("no scheduler installed for region-bound task");
            }
            // Re-check on the region thread: the task may be cancelled while queued for a permit.
            scheduler.runTask(location, () -> {
                if (!isCancelled(runnable)) runIsolated(runnable);
            });
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Count-bound pipe regional dispatch failed: " + t.getMessage(), t);
        }
    }
}
