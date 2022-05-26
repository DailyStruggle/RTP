package io.github.dailystruggle.rtp.common.tasks;

public interface RTPCancellable {
    boolean isCancelled();
    void setCancelled(boolean cancel);
}
