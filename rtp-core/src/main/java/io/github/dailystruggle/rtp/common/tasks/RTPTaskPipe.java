package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public abstract class RTPTaskPipe {
  protected final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();
  protected final Semaphore accessGuard = new Semaphore(1);
  protected long avgTime = TimeUnit.MILLISECONDS.toNanos(50);
  //    protected long avgTime = Long.MAX_VALUE;
  protected boolean stop = false;

  public abstract void execute();

  public abstract void execute(long availableTime);

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
    runnables.forEach(
        runnable -> {
          if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).setCancelled(true);
        });
    stop = true;
  }
}
