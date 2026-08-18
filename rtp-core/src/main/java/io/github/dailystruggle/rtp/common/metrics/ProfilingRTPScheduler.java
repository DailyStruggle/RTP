package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

/**
 * {@link RTPScheduler} decorator that profiles task execution time into
 * {@link RtpSchedulerProfile#GLOBAL}, classifying work as sync or async.
 */
public final class ProfilingRTPScheduler implements RTPScheduler {

    private final RTPScheduler delegate;

    public ProfilingRTPScheduler(RTPScheduler delegate) {
        if (delegate == null) throw new IllegalArgumentException("null delegate scheduler");
        this.delegate = delegate;
    }

    /** The wrapped platform scheduler. */
    public RTPScheduler delegate() {
        return delegate;
    }

    private static Runnable timedSync(Runnable task) {
        if (task == null) return null;
        return () -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                RtpSchedulerProfile.GLOBAL.recordSync(System.nanoTime() - start);
            }
        };
    }

    private static Runnable timedAsync(Runnable task) {
        if (task == null) return null;
        return () -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                RtpSchedulerProfile.GLOBAL.recordAsync(System.nanoTime() - start);
            }
        };
    }

    @Override
    public TrackedRTPTask runTaskAsynchronously(Runnable task) {
        return delegate.runTaskAsynchronously(timedAsync(task));
    }

    @Override
    public void runTask(Runnable task) {
        delegate.runTask(timedSync(task));
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        delegate.runTaskLater(timedSync(task), delay);
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
        return delegate.runTaskTimer(timedSync(task), delay, period);
    }

    @Override
    public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
        return delegate.runTaskTimerAsynchronously(timedAsync(task), delay, period);
    }

    @Override
    public void cancelTask(Object task) {
        delegate.cancelTask(task);
    }

    @Override
    public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) {
        // Forward the typed runnable unwrapped to preserve its self-scheduling contract.
        delegate.runTaskForPlayer(player, task, delayTicks);
    }

    @Override
    public void runTask(RTPLocation location, Runnable task) {
        delegate.runTask(location, timedSync(task));
    }

    @Override
    public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
        delegate.runTask(world, cx, cz, timedSync(task));
    }

    @Override
    public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
        return delegate.runTaskTimer(world, cx, cz, timedSync(task), delay, period);
    }

    @Override
    public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
        delegate.runTaskLater(world, cx, cz, timedSync(task), delay);
    }
}
