package io.github.dailystruggle.rtp.common.tasks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base task for the RTP execution pipeline (REQ-API-ARCH-003): combines
 * {@link RTPCancellable}, {@link RTPDelayable}, {@link Runnable}.
 */
public class RTPRunnable implements Runnable, RTPCancellable, RTPDelayable {
  /**
   * Hook called when a task finishes or is cancelled to remove it from tracking.
   * The default no-op is replaced at startup by the core tracking subsystem.
   */
  public static java.util.function.Consumer<Object> untrackHook = obj -> {};
  /**
   * Hook called at the start of each {@link #run()} invocation to refresh last-seen timestamp.
   * The default no-op is replaced at startup by the core tracking subsystem.
   */
  public static java.util.function.Consumer<UUID> updateHook = id -> {};
  /**
   * Hook called during construction to register this task and obtain a tracking {@link UUID}.
   * First arg is task instance; second is lifespan in ms. Returns null by default.
   */
  public static java.util.function.BiFunction<Object, Long, UUID> trackHook = (obj, lifespan) -> null;

  /**
   * Executes this task and records the nanoseconds consumed in
   * {@link io.github.dailystruggle.rtp.common.tools.PerformanceTracker#totalNanosecondsConsumed}.
   * Handles Spark profiler bridge routing if {@link #sparkFrameName()} is configured.
   */
  public void runWithTracking() {
    long start = System.nanoTime();
    try {
      String tag = sparkFrameName();
      if (tag == null) {
        this.run();
      } else {
        runTagged(tag);
      }
    } finally {
      io.github.dailystruggle.rtp.common.tools.PerformanceTracker.totalNanosecondsConsumed.add(
              System.nanoTime() - start);
      if (trackingId != null) updateHook.accept(trackingId);
    }
  }

  /**
   * Returns a Spark-profiler frame tag for this task, or {@code null} to opt out.
   *
   * @return Spark frame tag (e.g. {@code "rtp_pipeline_attempt"}), or {@code null}
   */
  protected String sparkFrameName() {
    return null;
  }

  /**
   * Dispatches {@link #run()} through a fixed bridge method matching {@code tag} for Spark profiling.
   *
   * @param tag allow-list tag from {@link #sparkFrameName()}
   */
  private void runTagged(String tag) {
    switch (tag) {
      case "rtp_pipeline_attempt":   rtp_pipeline_attempt();   break;
      case "rtp_cache_generator":    rtp_cache_generator();    break;
      case "rtp_scan_crawler":       rtp_scan_crawler();       break;
      case "rtp_async_task_drain":   rtp_async_task_drain();   break;
      case "rtp_scan_drain":         rtp_scan_drain();         break;
      case "rtp_force_queue":        rtp_force_queue();        break;
      default:                       rtp_unknown_task();       break;
    }
  }

  // --- Spark-tagged bridge methods. Names are the contract; do not rename without
  // --- updating LESSONS_LEARNED.md and any saved Spark report URLs that reference them.
  private void rtp_pipeline_attempt()   { this.run(); }
  private void rtp_cache_generator()    { this.run(); }
  private void rtp_scan_crawler()       { this.run(); }
  private void rtp_async_task_drain()   { this.run(); }
  private void rtp_scan_drain()         { this.run(); }
  private void rtp_force_queue()        { this.run(); }
  private void rtp_unknown_task()       { this.run(); }

  protected AtomicBoolean cancelled = new AtomicBoolean(false);
  protected AtomicBoolean isRunning = new AtomicBoolean(false);
  private long delay = 0;
  private Runnable runnable;

  /**
   * Optional entity-thread routing target. When non-{@code null}, {@link #schedule()}
   * dispatches this task onto the player's entity scheduler (the correct thread for
   * modifying that player on Folia).
   */
  private io.github.dailystruggle.rtp.api.entity.RTPPlayer target;
  /**
   * Optional region-thread routing location. When set (and no {@link #target} is set),
   * {@link #schedule()} dispatches this task onto the region/chunk thread that owns this
   * location. Backs {@link #getLocation()} unless a subclass overrides it.
   */
  private io.github.dailystruggle.rtp.api.world.RTPLocation location;

  /**
   * Platform scheduler used by {@link #schedule()} and {@link #schedule(long)} to
   * self-dispatch onto the correct thread.
   */
  public static io.github.dailystruggle.rtp.api.scheduling.RTPScheduler scheduler;

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
   * Primary constructor. Invokes {@link #trackHook} to register this task with the memory tracker.
   *
   * @param maxLifespan maximum time in milliseconds before cleanup
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
   * Returns the region-thread routing location associated with this task, or
   * {@code null} if this task carries no location context (e.g. it is a pre-generation
   * task). Subclasses may override to derive the location dynamically.
   *
   * @return the destination location, or {@code null}
   */
  public io.github.dailystruggle.rtp.api.world.RTPLocation getLocation() {
    return location;
  }

  /**
   * Sets the region-thread routing location used by {@link #schedule()}.
   *
   * @param location the location context, or {@code null} to clear it
   * @return this task, for chaining
   */
  public RTPRunnable setLocation(io.github.dailystruggle.rtp.api.world.RTPLocation location) {
    this.location = location;
    return this;
  }

  /**
   * Returns the entity-thread routing target (player) associated with this task, or
   * {@code null} if this task carries no player context.
   *
   * @return the target player, or {@code null}
   */
  public io.github.dailystruggle.rtp.api.entity.RTPPlayer getTarget() {
    return target;
  }

  /**
   * Sets the entity-thread routing target used by {@link #schedule()}. When set, the
   * player's entity scheduler takes precedence over any {@link #getLocation() location}.
   *
   * @param target the target player, or {@code null} to clear it
   * @return this task, for chaining
   */
  public RTPRunnable setTarget(io.github.dailystruggle.rtp.api.entity.RTPPlayer target) {
    this.target = target;
    return this;
  }

  /**
   * Self-dispatches this task onto the correct thread using its own {@link #getDelay()}.
   *
   * @see #schedule(long)
   */
  public void schedule() {
    schedule(getDelay());
  }

  /**
   * Self-dispatches this task onto the correct thread (entity, region, or async).
   *
   * @param delayTicks delay in server ticks before execution (&le; 0 runs immediately)
   * @throws IllegalStateException if scheduler is not installed
   */
  public void schedule(long delayTicks) {
    io.github.dailystruggle.rtp.api.scheduling.RTPScheduler s = scheduler;
    if (s == null) {
      throw new IllegalStateException(
          "RTPRunnable.scheduler not installed; cannot schedule before core is loaded");
    }
    io.github.dailystruggle.rtp.api.entity.RTPPlayer p = getTarget();
    if (p != null) {
      // Entity scheduler natively supports a tick delay and takes RTPRunnable directly.
      s.runTaskForPlayer(p, this, Math.max(0L, delayTicks));
      return;
    }
    io.github.dailystruggle.rtp.api.world.RTPLocation loc = getLocation();
    if (loc != null) {
      int cx = loc.getBlockX() >> 4;
      int cz = loc.getBlockZ() >> 4;
      if (delayTicks <= 0) {
        s.runTask(loc, this::runWithTracking);
      } else {
        s.runTaskLater(loc.world(), cx, cz, this::runWithTracking, delayTicks);
      }
      return;
    }
    // No spatial context: route off-thread (or main-thread-delayed if a delay is set).
    if (delayTicks <= 0) {
      s.runTaskAsynchronously(this::runWithTracking);
    } else {
      s.runTaskLater(this::runWithTracking, delayTicks);
    }
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
