package io.github.dailystruggle.rtp.common.tasks;

import java.util.concurrent.atomic.AtomicBoolean;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
    protected AtomicBoolean cancelled = new AtomicBoolean(false);
    protected AtomicBoolean isRunning = new AtomicBoolean(false);
    private long delay = 0;
    private Runnable runnable;

    public RTPRunnable() {
        runnable = null;
    }

    public RTPRunnable(Runnable runnable) {
        this.runnable = runnable;
    }

    public RTPRunnable(Runnable runnable, long delay) {
        this.runnable = runnable;
        this.delay = delay;
    }

    public RTPRunnable(int delay) {
        this.delay = delay;
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled.set(cancel);
    }

    @Override
    public long getDelay() {
        return delay;
    }

    @Override
    public void setDelay(final long delay) {
        this.delay = delay;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public void run() {
        if (runnable != null) runnable.run();
    }
}
