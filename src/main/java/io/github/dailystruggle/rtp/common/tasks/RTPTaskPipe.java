package io.github.dailystruggle.rtp.common.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class RTPTaskPipe {
    private final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();
    private final Semaphore accessGuard = new Semaphore(1);
    protected long avgTime = TimeUnit.MILLISECONDS.toNanos(50);
    //    protected long avgTime = Long.MAX_VALUE;
    private boolean stop = false;

    public void execute(long availableTime) {
        if (stop) return;
        if (runnables.size() == 0) return;
        long dt = 0;
        long start = System.nanoTime();

        List<RTPDelayable> delayedRunnables = new ArrayList<>(runnables.size());

        do {
            try {
                accessGuard.acquire();
                if (stop) return;
                Runnable runnable = runnables.poll();
                if (runnable == null) continue;
                if (runnable instanceof RTPDelayable) {
                    long d = ((RTPDelayable) runnable).getDelay();
                    if (d > 0) {
                        delayedRunnables.add((RTPDelayable) runnable);
                        continue;
                    }
                }

                if (runnable instanceof RTPCancellable && ((RTPCancellable) runnable).isCancelled()) continue;

                long localStart = System.nanoTime();
                runnable.run();
                long localStop = System.nanoTime();

                long diff = localStop - localStart;
                avgTime = ((avgTime / 8) * 7) + (diff / 8);

                dt = localStop - start;
            } catch (InterruptedException ignored) {

            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                accessGuard.release();
            }

        } while (runnables.size() > 0 && (dt + avgTime) < availableTime);

        for (RTPDelayable runnable : delayedRunnables) {
            runnable.setDelay(runnable.getDelay() - 1);
        }

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
            if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).setCancelled(true);
        });
        stop = true;
    }
}
