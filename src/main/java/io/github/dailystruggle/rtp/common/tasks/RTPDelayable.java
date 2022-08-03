package io.github.dailystruggle.rtp.common.tasks;

public interface RTPDelayable {
    long getDelay();
    void setDelay(long delay);
}
