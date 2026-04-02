package io.github.dailystruggle.rtp.common.tasks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
  protected AtomicBoolean cancelled = new AtomicBoolean(false);
  protected AtomicBoolean isRunning = new AtomicBoolean(false);
  private long delay = 0;
  private Runnable runnable;

  // Store the UUID to update the MemoryTracker
  protected UUID trackingId;

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
    this.delay = (long) delay;
  }

  protected RTPRunnable(long maxLifespan) {
    runnable = null;
    // Use getClass().getSimpleName() so subclasses log their actual names (e.g., "FillTask")
    this.trackingId = io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
            this,
            this.getClass().getSimpleName(),
            maxLifespan
    );
  }

  public void runWithTracking() {
    long start = System.nanoTime();
    try {
      this.run();
    } finally {
      io.github.dailystruggle.rtp.common.tools.PerformanceTracker.totalNanosecondsConsumed.add(
              System.nanoTime() - start);

      // This covers execution from task pipelines like TimeBoundTaskPipe
      if (trackingId != null) {
        io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
      }
    }
  }

  @Override
  public boolean isCancelled() {
    return cancelled.get();
  }

  @Override
  public void setCancelled(boolean cancel) {
    cancelled.set(cancel);
    if (cancel && trackingId != null) {
      trackingId = null;
    }
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

  @Override
  public void run() {
    // This covers execution directly from repeating Bukkit/Folia schedulers
    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }

    if (runnable != null) runnable.run();
  }
}
