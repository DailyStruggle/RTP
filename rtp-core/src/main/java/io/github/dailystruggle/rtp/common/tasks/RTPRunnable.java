package io.github.dailystruggle.rtp.common.tasks;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.UUID;

import java.util.concurrent.atomic.AtomicBoolean;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
  protected UUID trackingId;

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
    this(300000L);
  }

  public RTPRunnable(Runnable runnable) {
    this(300000L);
    this.runnable = runnable;
  }

  public RTPRunnable(Runnable runnable, long delay) {
    this(300000L);
    this.runnable = runnable;
    this.delay = delay;
  }

  public RTPRunnable(int delay) {
    this(300000L);
    this.delay = delay;
  }

  protected RTPRunnable(long maxLifespan) {
    this.trackingId = io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
            this, this.getClass().getSimpleName(), maxLifespan);
    runnable = null;
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
    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }
    if (runnable != null) runnable.run();
  }
}
