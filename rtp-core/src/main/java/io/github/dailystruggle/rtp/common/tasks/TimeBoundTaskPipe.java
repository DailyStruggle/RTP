package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class TimeBoundTaskPipe extends RTPTaskPipe {
    private long availableTime = Long.MAX_VALUE;

    public TimeBoundTaskPipe() {

    }

    public TimeBoundTaskPipe(long availableTime) {
        this.availableTime = availableTime;
    }

    @Override
    public boolean execute() {
        return execute(availableTime);
    }

    @Override
    public boolean execute(long availableTime) {
        if (stop) return true;
        if (runnables.isEmpty()) return true;
        long dt = 0;
        long start = System.nanoTime();

        List<Runnable> delayedRunnables = new ArrayList<>(runnables.size());

        do {
            try {
                accessGuard.acquire();
                if (stop) return true;
                Runnable runnable = runnables.poll();
                if (runnable == null) continue;
                if (runnable instanceof RTPDelayable) {
                    long d = ((RTPDelayable) runnable).getDelay();
                    if (d > 0) {
                        delayedRunnables.add((RTPDelayable) runnable);
                        continue;
                    }
                }

                if (runnable instanceof RTPCancellable && ((RTPCancellable) runnable).isCancelled())
                    continue;

                long localStart = System.nanoTime();
                try {
                    if (runnable instanceof RTPRunnable)
                        ((RTPRunnable) runnable).runWithTracking();
                    else runnable.run();
                } catch (Throwable throwable) {
                    RTP.log(Level.WARNING, throwable.getMessage(), throwable);
                    continue;
                }
                long localStop = System.nanoTime();

                long diff = localStop - localStart;
                avgTime = ((avgTime / 8) * 7) + (diff / 8);

                dt = localStop - start;
            } catch (InterruptedException ignored) {

            } catch (Throwable t) {
                RTP.log(Level.WARNING, t.getMessage(), t);
            } finally {
                accessGuard.release();
            }

        } while (!runnables.isEmpty() && (dt + avgTime) < availableTime);

        for (Runnable runnable : delayedRunnables) {
            ((RTPDelayable) runnable).setDelay(((RTPDelayable) runnable).getDelay() - 1);
        }

        runnables.addAll(delayedRunnables);

        return runnables.isEmpty();
    }

    public long getAvailableTime() {
        return availableTime;
    }

    public void setAvailableTime(long availableTime) {
        this.availableTime = availableTime;
    }
}
