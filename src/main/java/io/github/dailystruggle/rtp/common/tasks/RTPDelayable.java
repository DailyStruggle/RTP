package io.github.dailystruggle.rtp.common.tasks;

public interface RTPDelayable extends Runnable {
    long getDelay();

    void setDelay( long delay );
}
