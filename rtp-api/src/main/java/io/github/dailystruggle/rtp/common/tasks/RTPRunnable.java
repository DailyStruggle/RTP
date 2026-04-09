package io.github.dailystruggle.rtp.common.tasks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
  public static java.util.function.Consumer<Object> untrackHook = obj -> {};
  public static java.util.function.Consumer<UUID> updateHook = id -> {};
  public static java.util.function.BiFunction<Object, Long, UUID> trackHook = (obj, lifespan) -> null;

  public void runWithTracking() {
    long start = System.nanoTime();
    try {
      this.run();
    } finally {
      io.github.dailystruggle.rtp.common.tools.PerformanceTracker.totalNanosecondsConsumed.add(
              System.nanoTime() - start);
      if (trackingId != null) updateHook.accept(trackingId);
    }
  }

  protected AtomicBoolean cancelled = new AtomicBoolean(false);
  protected AtomicBoolean isRunning = new AtomicBoolean(false);
  private long delay = 0;
  private Runnable runnable;

  protected UUID trackingId;

  public RTPRunnable() { this(300000L); }
  public RTPRunnable(Runnable runnable) { this(300000L); this.runnable = runnable; }
  public RTPRunnable(Runnable runnable, long delay) { this(300000L); this.runnable = runnable; this.delay = delay; }
  public RTPRunnable(int delay) { this(300000L); this.delay = (long) delay; }

  protected RTPRunnable(long maxLifespan) {
    this.runnable = null;
    // Executes the core track method if injected
    this.trackingId = trackHook.apply(this, maxLifespan);
  }

  @Override
  public boolean isCancelled() {
    return cancelled.get();
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
  public void setCancelled(boolean cancel) {
    cancelled.set(cancel);
    if (cancel && trackingId != null) {
      untrackHook.accept(trackingId);
      trackingId = null;
    }
  }

  @Override
  public void run() {
    if (trackingId != null) updateHook.accept(trackingId);
    if (runnable != null) runnable.run();
  }
}
