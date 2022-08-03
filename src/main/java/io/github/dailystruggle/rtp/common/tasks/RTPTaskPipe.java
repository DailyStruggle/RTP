package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.tasks.RTPCancellable;
import io.github.dailystruggle.rtp.common.tasks.RTPDelayable;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class RTPTaskPipe {
    protected long avgTime = 0;
    private final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();

    public void execute(long availableTime) {
        if(runnables.size() == 0) return;
        long dt = 0;
        long start = System.nanoTime();

        do {
            Runnable runnable = Objects.requireNonNull(runnables.poll());
            if(runnable instanceof RTPDelayable RTPDelayable) {
                long d = RTPDelayable.getDelay();
                if(d>0) {
                    RTPDelayable.setDelay(d-1);
                    runnables.add(runnable);
                    continue;
                }
            }

            if(runnable instanceof RTPCancellable RTPCancellable && RTPCancellable.isCancelled()) continue;

            long localStart = System.nanoTime();
            runnable.run();
            long localStop = System.nanoTime();

            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
            long diff = localStop - localStart;
            if(avgTime == 0) avgTime = diff;
            else avgTime = ((avgTime*7)/8) + (diff/8);

            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = localStop -start;
        } while (runnables.size()>0 && dt+avgTime< availableTime);


    }

    public long size() {
        return runnables.size();
    }

    public long avgTime() {
        return avgTime;
    }

    public void add(Runnable runnable) {
        runnables.add(runnable);
    }

    public void clear() {
        runnables.clear();
    }
}
