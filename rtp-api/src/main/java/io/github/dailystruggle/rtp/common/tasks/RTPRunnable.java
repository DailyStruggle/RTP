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
   *
   * <p><b>Spark profiler tagging.</b> When {@link #sparkFrameName()} returns a non-{@code null}
   * tag from the allow-list, execution is routed through a fixed bridge method whose Java name
   * matches the tag (e.g. {@code rtp_pipeline_attempt}). Spark's async sampler then attributes
   * samples taken inside this task to that frame, making profiler reports self-describing
   * without any soft-dependency on Spark itself. Adds one (cheap) stack frame per task run.
   * Default ({@code null}) skips the bridge entirely.
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
   * <p>The returned string must be one of the fixed allow-list entries dispatched in
   * {@link #runTagged(String)}. Unknown tags fall through to a generic
   * {@code rtp_unknown_task} bridge. Subclasses override this with a one-line constant.
   *
   * @return Spark frame tag (e.g. {@code "rtp_pipeline_attempt"}), or {@code null} for no tag
   */
  protected String sparkFrameName() {
    return null;
  }

  /**
   * Dispatches {@link #run()} through a fixed, pre-named bridge method whose Java name
   * matches the {@code tag} so it appears verbatim in Spark async-sampler stack frames.
   *
   * <p>Spark cannot see dynamic strings, only method names on the stack; this is why the
   * allow-list of bridges is hard-coded rather than synthesized.
   *
   * @param tag one of the documented allow-list tags (see {@link #sparkFrameName()})
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
   * self-dispatch onto the correct thread (entity, region, or async).
   *
   * <p>The default is {@code null}; the core wiring installs the active
   * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler} at startup. Calling
   * {@code schedule(...)} before the scheduler is installed throws
   * {@link IllegalStateException} (REQ-API: require-by-contract entry points must not
   * silently no-op).
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
   * Self-dispatches this task onto the correct thread, switching by routing context:
   * <ul>
   *   <li>a {@link #getTarget() target player} is set &rarr; the player's entity
   *       scheduler (Folia-safe entity thread);</li>
   *   <li>otherwise a {@link #getLocation() location} is set &rarr; the region/chunk
   *       thread owning that location;</li>
   *   <li>otherwise &rarr; the async pool (no spatial context to route by).</li>
   * </ul>
   * On non-Folia platforms region/entity routing collapses to the main server thread,
   * per the {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler} contract.
   *
   * @param delayTicks delay in server ticks before execution; values &le; 0 run as soon
   *     as the target thread is available
   * @throws IllegalStateException if the core scheduler has not been installed yet
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
