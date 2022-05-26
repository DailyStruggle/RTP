package io.github.dailystruggle.rtp.common.tasks;

public abstract class RTPRunnable implements Runnable, RTPCancellable {
    private boolean cancelled;

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }
}
