package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class RTPTaskPipe {
    protected long avgTime = 0;
    private boolean stop = false;
    private final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();

    public void execute(long availableTime) {
        if(stop) return;
        if(runnables.size() == 0) return;
        long dt = 0;
        long start = System.nanoTime();

        List<Runnable> delayedRunnables = new ArrayList<>(runnables.size());

        for(Runnable runnable : runnables) {
            if(stop) return;
            if(runnable instanceof RTPDelayable RTPDelayable) {
                long d = RTPDelayable.getDelay();
                if(d>0) {
                    RTPDelayable.setDelay(d-1);
                }
            }
        }

        do {
            if(stop) return;
            Runnable runnable = runnables.poll();
            if(runnable == null) continue;
            if(runnable instanceof RTPDelayable RTPDelayable) {
                long d = RTPDelayable.getDelay();
                if(d>0) {
                    delayedRunnables.add(runnable);
                    continue;
                }
            }

            if(runnable instanceof RTPCancellable rtpCancellable && rtpCancellable.isCancelled()) continue;

            long localStart = System.nanoTime();
            runnable.run();
            long localStop = System.nanoTime();

            if(localStop < localStart) localStart = -(Long.MAX_VALUE - localStart);
            long diff = localStop - localStart;
            if(avgTime == 0) avgTime = diff;
            else avgTime = ((avgTime*7)/8) + (diff/8);

            if(localStop < start) start = -(Long.MAX_VALUE-start); //overflow correction
            dt = localStop -start;
        } while (runnables.size()>0 && dt+avgTime<availableTime);

        runnables.addAll(delayedRunnables);
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

    public void stop() {
        runnables.forEach(runnable -> {
            if(runnable instanceof RTPRunnable rtpRunnable) rtpRunnable.setCancelled(true);
        });
        stop = true;
    }
}
