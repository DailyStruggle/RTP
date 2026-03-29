package io.github.dailystruggle.rtp.common.tasks;

public class TimeBoundTaskPipe extends RTPTaskPipe {
    private long availableTime = Long.MAX_VALUE;

    public TimeBoundTaskPipe() {

    }

    public TimeBoundTaskPipe(long availableTime) {
        this.availableTime = availableTime;
    }

    @Override
    public void execute() {
        execute(availableTime);
    }

    public long getAvailableTime() {
        return availableTime;
    }

    public void setAvailableTime(long availableTime) {
        this.availableTime = availableTime;
    }
}
