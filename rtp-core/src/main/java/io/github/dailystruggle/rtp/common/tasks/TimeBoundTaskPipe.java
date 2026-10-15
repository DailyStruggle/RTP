package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class TimeBoundTaskPipe extends RTPTaskPipe {
    private long availableTime = Long.MAX_VALUE;
    protected final BudgetPacer pacer = BudgetPacer.ofTaskPipe(avgTime);

    public TimeBoundTaskPipe() {

    }

    public TimeBoundTaskPipe(long availableTime) {
        this.availableTime = availableTime;
    }

    public BudgetPacer pacer() {
        return pacer;
    }

    @Override
    public boolean execute() {
        return execute(availableTime);
    }

    /**
     * Drains due tasks until the predicted next task would overrun {@code availableTime}.
     * A positive budget always runs at least one due task (progress); a budget of zero or less
     * runs nothing, so an exhausted tick is never overrun.
     */
    @Override
    public boolean execute(long availableTime) {
        if (stop) return true;
        if (runnables.isEmpty()) return true;
        if (availableTime <= 0) return false;
        long dt = 0;
        long start = System.nanoTime();
        boolean ranAny = false;

        List<Runnable> delayedRunnables = new ArrayList<>(runnables.size());

        while (!runnables.isEmpty() && pacer.canExecute(dt, availableTime, ranAny)) {
            try {
                accessGuard.acquire();
            } catch (InterruptedException e) {
                // Re-acquiring would throw again forever; leave the rest for the next pulse.
                Thread.currentThread().interrupt();
                break;
            }
            try {
                if (stop) return true;
                Runnable runnable = runnables.poll();
                if (runnable == null) continue;
                if (runnable instanceof RTPDelayable) {
                    long d = ((RTPDelayable) runnable).getDelay();
                    if (d > 0) {
                        delayedRunnables.add((RTPDelayable) runnable);
                        continue;
                    }
                }

                if (runnable instanceof RTPCancellable && ((RTPCancellable) runnable).isCancelled())
                    continue;

                long localStart = System.nanoTime();
                try {
                    if (runnable instanceof RTPRunnable)
                        ((RTPRunnable) runnable).runWithTracking();
                    else runnable.run();
                } catch (Throwable throwable) {
                    RTP.log(Level.WARNING, throwable.getMessage(), throwable);
                }
                long localStop = System.nanoTime();
                ranAny = true;

                long diff = localStop - localStart;
                pacer.recordDuration(diff);
                avgTime = pacer.predictedCostNanos();

                dt = localStop - start;
            } catch (Throwable t) {
                RTP.log(Level.WARNING, t.getMessage(), t);
            } finally {
                accessGuard.release();
            }
        }

        for (Runnable runnable : delayedRunnables) {
            ((RTPDelayable) runnable).setDelay(((RTPDelayable) runnable).getDelay() - 1);
        }

        runnables.addAll(delayedRunnables);

        return runnables.isEmpty();
    }

    public long getAvailableTime() {
        return availableTime;
    }

    public void setAvailableTime(long availableTime) {
        this.availableTime = availableTime;
    }
}
