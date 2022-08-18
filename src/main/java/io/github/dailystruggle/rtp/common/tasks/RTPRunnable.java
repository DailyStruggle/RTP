package io.github.dailystruggle.rtp.common.tasks;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
    private boolean cancelled;
    private long delay = 0;

    protected boolean isRunning = false;

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
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
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
        return isRunning;
    }

    @Override
    public void run() {
        if(runnable!=null) runnable.run();
    }
}
