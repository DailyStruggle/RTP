package io.github.dailystruggle.rtp.common.tasks;

public abstract class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
    private boolean cancelled;
    private long delay = 0;

    public RTPRunnable() {

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
}
