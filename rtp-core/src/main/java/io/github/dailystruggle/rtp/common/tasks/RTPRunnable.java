package io.github.dailystruggle.rtp.common.tasks;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.atomic.AtomicBoolean;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
  public void runWithTracking() {
    long start = System.nanoTime();
    try {
      this.run();
    } finally {
      io.github.dailystruggle.rtp.common.tools.PerformanceTracker.totalNanosecondsConsumed.add(
          System.nanoTime() - start);
    }
  }

  protected AtomicBoolean cancelled = new AtomicBoolean(false);
  protected AtomicBoolean isRunning = new AtomicBoolean(false);
  private long delay = 0;
  private Runnable runnable;

  public RTPRunnable() {
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
        this, this.getClass().getSimpleName(), 300000L);
    runnable = null;
  }

  public RTPRunnable(Runnable runnable) {
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
        this, this.getClass().getSimpleName(), 300000L);
    this.runnable = runnable;
  }

  public RTPRunnable(Runnable runnable, long delay) {
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
        this, this.getClass().getSimpleName(), 300000L);
    this.runnable = runnable;
    this.delay = delay;
  }

  public RTPRunnable(int delay) {
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
        this, this.getClass().getSimpleName(), 300000L);
    this.delay = delay;
  }

  @Override
  public boolean isCancelled() {
    return cancelled.get();
  }

  @Override
  public void setCancelled(boolean cancel) {
    cancelled.set(cancel);
  }

  @Override
  public long getDelay() {
    return delay;
  }

  @Override
  public void setDelay(final long delay) {
    this.delay = delay;
  }

  public boolean isRunning() {
    return isRunning.get();
  }

  public io.github.dailystruggle.rtp.api.world.RTPLocation getLocation() {
    return null;
  }

  public RTPLocation getTargetLocation() {
    return null;
  }

  @Override
  public void run() {
    if (runnable != null) runnable.run();
  }
}
