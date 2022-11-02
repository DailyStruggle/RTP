package io.github.dailystruggle.rtp.common.tasks;

import org.apache.commons.lang3.mutable.MutableBoolean;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
    protected MutableBoolean cancelled = new MutableBoolean(false);
    private long delay = 0;

    protected MutableBoolean isRunning = new MutableBoolean(false);

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
        return cancelled.booleanValue();
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled.setValue(cancel);
    }

    @Override
    public void setDelay(final long delay) {
        this.delay = delay;
    }

    @Override
    public long getDelay() {
        return delay;
    }

    public boolean isRunning() {
        return isRunning.booleanValue();
    }

    @Override
    public void run() {
        if(runnable!=null) runnable.run();
    }
}
