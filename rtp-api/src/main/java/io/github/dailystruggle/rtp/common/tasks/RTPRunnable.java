package io.github.dailystruggle.rtp.common.tasks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base task for the RTP execution pipeline (REQ-API-ARCH-003): combines
 * {@link RTPCancellable}, {@link RTPDelayable}, {@link Runnable}. Static hooks
 * {@link #trackHook}/{@link #updateHook}/{@link #untrackHook} integrate with the
 * memory tracker; defaults are no-ops, replaced by core at startup. Submit via
 * {@link #runWithTracking()} for MSPT accounting + lifecycle cleanup.
 * Override {@link #run()} for task logic. {@link #cancelled}/{@link #isRunning}
 * are atomic; other fields effectively immutable after construction.
 */
public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
  /**
   * Hook called when a task finishes or is cancelled to remove it from the
   * active-task registry and release associated resources.
   * The default no-op is replaced at startup by the core tracking subsystem.
   */
  public static java.util.function.Consumer<Object> untrackHook = obj -> {};
  /**
   * Hook called at the start of each {@link #run()} invocation to refresh the
   * task's last-seen timestamp, preventing the memory tracker from treating it
   * as a leaked/stalled task.
   * The default no-op is replaced at startup by the core tracking subsystem.
   */
  public static java.util.function.Consumer<UUID> updateHook = id -> {};
  /**
   * Hook called during construction to register this task in the active-task map
   * and obtain a tracking {@link java.util.UUID}.
   *
   * <p>The {@code Long} argument is the maximum lifespan in milliseconds after
   * which the task is considered a memory leak and forcibly cleaned up.
   * The default no-op returns {@code null}, meaning the task is untracked.
   * Replaced at startup by the core tracking subsystem.
   */
  public static java.util.function.BiFunction<Object, Long, UUID> trackHook = (obj, lifespan) -> null;

  /**
   * Executes this task and records the nanoseconds consumed in
   * {@link io.github.dailystruggle.rtp.common.tools.PerformanceTracker#totalNanosecondsConsumed},
   * which feeds the rolling MSPT calculation.
   *
   * <p>Prefer this method over calling {@link #run()} directly when submitting to the
   * scheduler so that performance accounting and lifecycle cleanup run automatically.
   */
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

  /** Creates a task with a default maximum lifespan of 5 minutes (300 000 ms). */
  public RTPRunnable() { this(300000L); }
  /**
   * Creates a task that delegates {@link #run()} to {@code runnable}, with a
   * default maximum lifespan of 5 minutes.
   *
   * @param runnable the delegate to execute; must not be {@code null}
   */
  public RTPRunnable(Runnable runnable) { this(300000L); this.runnable = runnable; }
  /**
   * Creates a task that delegates {@link #run()} to {@code runnable} and waits
   * {@code delay} ticks before execution, with a default maximum lifespan of 5 minutes.
   *
   * @param runnable the delegate to execute; must not be {@code null}
   * @param delay    scheduler delay in ticks; must be &ge; 0
   */
  public RTPRunnable(Runnable runnable, long delay) { this(300000L); this.runnable = runnable; this.delay = delay; }
  /**
   * Creates a task with the given scheduler delay and a default maximum lifespan
   * of 5 minutes.
   *
   * @param delay scheduler delay in ticks; must be &ge; 0
   */
  public RTPRunnable(int delay) { this(300000L); this.delay = (long) delay; }

  /**
   * Primary constructor used by all public constructors.
   *
   * <p>Invokes {@link #trackHook} to register this task with the memory tracker.
   * Subclasses that need a custom lifespan should call this constructor directly.
   *
   * @param maxLifespan maximum time in milliseconds this task may remain in the
   *                    active-task map before being considered a memory leak
   */
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

  /**
   * Returns whether this task is currently executing.
   *
   * @return {@code true} if {@link #run()} has started but not yet returned
   */
  public boolean isRunning() {
    return isRunning.get();
  }

  /**
   * Returns the teleport destination associated with this task, or {@code null} if
   * this task does not represent a player teleport (e.g. it is a pre-generation task).
   *
   * @return the destination location, or {@code null}
   */
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
