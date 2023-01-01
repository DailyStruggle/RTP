package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class RTPTaskPipe {
    protected long avgTime = TimeUnit.MILLISECONDS.toNanos(50);
//    protected long avgTime = Long.MAX_VALUE;
    private boolean stop = false;
    private final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();
    private final Semaphore accessGuard = new Semaphore(1);

    public void execute(long availableTime) {
        if(stop) return;
        if(runnables.size() == 0) return;
        long dt = 0;
        long start = System.nanoTime();

        List<Runnable> delayedRunnables = new ArrayList<>(runnables.size());

        for(Runnable runnable : runnables) {
            if(stop) return;
            if(runnable instanceof RTPDelayable) {
                long d = ((RTPDelayable) runnable).getDelay();
                if(d>0) {
                    ((RTPDelayable) runnable).setDelay(d-1);
                }
                if(d>1) {
                    delayedRunnables.add(runnable);
                }
            }
        }

        do {
            try {
                accessGuard.acquire();
                    if(stop) return;
                Runnable runnable = runnables.poll();
                if(runnable == null) continue;
                if(runnable instanceof RTPDelayable) {
                    long d = ((RTPDelayable) runnable).getDelay();
                    if(d>0) {
                        continue;
                    }
                }

                if(runnable instanceof RTPCancellable && ((RTPCancellable) runnable).isCancelled()) continue;

                long localStart = System.nanoTime();
                runnable.run();
                long localStop = System.nanoTime();

                long diff = localStop - localStart;
                avgTime = ((avgTime/8)*7) + (diff/8);

                dt = localStop - start;
            } catch (InterruptedException ignored) {

            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                accessGuard.release();
            }

        } while (runnables.size()>0 && (dt+avgTime)<availableTime);

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

    public void start() {
        stop = false;
    }

    public void stop() {
        runnables.forEach(runnable -> {
            if(runnable instanceof RTPRunnable) ((RTPRunnable) runnable).setCancelled(true);
        });
        stop = true;
    }
}
