package io.github.dailystruggle.rtp.api.scheduling;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.UUID;

/**
 * A {@link RTPRunnable} wrapper that records timing and lifecycle state for an
 * underlying task and removes it from the server accessor's active-task map upon
 * completion or cancellation.
 *
 * <p>Instances are created by the scheduling subsystem and stored in
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#activeTasks} under their
 * {@link #getTrackingId()} key. The {@code /rtp info} command reads this map to report
 * in-flight teleport count and per-task age.
 *
 * <p><b>State machine:</b>
 * <pre>
 *   PENDING  ──run()──&gt;  RUNNING  ──finally──&gt;  COMPLETED
 * </pre>
 * A task moves from {@code PENDING} to {@code RUNNING} when {@link #run()} is
 * entered, and to {@code COMPLETED} in the {@code finally} block regardless of
 * success or exception, satisfying REQ-API-ARCH-003.
 *
 * <p><b>Thread safety:</b> All timing fields are written exactly once by the
 * thread executing {@link #run()}, and are only read after that thread exits the
 * method. {@link #getState()} and {@link #getDuration()} are therefore safe to
 * call from any thread after the task has started.
 *
 * @see io.github.dailystruggle.rtp.api.server.RTPServerAccessor#activeTasks
 * @see TaskState
 */
public class TrackedRTPTask extends RTPRunnable {
  /** The underlying task whose execution is being tracked. */
  private final RTPRunnable task;
  /** Unique string key used to register and remove this task in the active-task map. */
  private final String trackingId;
  /** Wall-clock time in milliseconds ({@link System#currentTimeMillis()}) when this task was created. */
  private final long queuedTime;
  /** Wall-clock time in milliseconds when {@link #run()} was entered; {@code -1} if not yet started. */
  private long startTime = -1;
  /** Wall-clock time in milliseconds when {@link #run()} returned; {@code -1} if not yet finished. */
  private long endTime = -1;

  /**
   * Creates a tracked wrapper using the string form of a {@link UUID} as the tracking key.
   *
   * @param task       the task to wrap; must not be {@code null}
   * @param trackingId the UUID whose {@link UUID#toString()} becomes the tracking key
   */
  public TrackedRTPTask(RTPRunnable task, UUID trackingId) {
    this(task, trackingId.toString());
  }

  /**
   * Creates a tracked wrapper with an explicit string tracking key.
   *
   * @param task       the task to wrap; must not be {@code null}
   * @param trackingId the key used to register and later remove this task from the
   *                   active-task map; must be unique across all currently active tasks
   */
  public TrackedRTPTask(RTPRunnable task, String trackingId) {
    this.task = task;
    this.trackingId = trackingId;
    this.queuedTime = System.currentTimeMillis();
  }

  @Override
  public boolean isCancelled() {
    return task.isCancelled();
  }

  @Override
  public void setCancelled(boolean cancel) {
    task.setCancelled(cancel);
  }

  @Override
  public long getDelay() {
    return task.getDelay();
  }

  @Override
  public void setDelay(long delay) {
    task.setDelay(delay);
  }

  @Override
  public boolean isRunning() {
    return task.isRunning();
  }

  @Override
  public void run() {
    this.startTime = System.currentTimeMillis();
    try {
      task.run();
    } finally {
      this.endTime = System.currentTimeMillis();
      if (io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor != null) {
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor.removeAction(trackingId);
      }
      RTPRunnable.untrackHook.accept(this);
    }
  }

  /**
   * Returns the current lifecycle state of this task.
   *
   * @return {@link TaskState#PENDING} if not yet started,
   *         {@link TaskState#RUNNING} if currently executing, or
   *         {@link TaskState#COMPLETED} if finished
   */
  public TaskState getState() {
    if (endTime != -1) return TaskState.COMPLETED;
    if (startTime != -1) return TaskState.RUNNING;
    return TaskState.PENDING;
  }

  /**
   * Returns the elapsed execution time in milliseconds.
   *
   * <p>If the task has not started, returns {@code 0}. If the task is still
   * running, returns the time since it started. If completed, returns the total
   * wall-clock execution time.
   *
   * @return duration in milliseconds; {@code 0} if the task has not started
   */
  public long getDuration() {
    if (startTime == -1) return 0;
    if (endTime == -1) return System.currentTimeMillis() - startTime;
    return endTime - startTime;
  }

  /**
   * Returns the wall-clock time in milliseconds when this task was created and
   * queued, as reported by {@link System#currentTimeMillis()}.
   *
   * @return creation timestamp in milliseconds
   */
  public long getQueuedTime() {
    return queuedTime;
  }

  /**
   * Returns the wall-clock time in milliseconds when {@link #run()} was entered,
   * or {@code -1} if the task has not started yet.
   *
   * @return start timestamp in milliseconds, or {@code -1}
   */
  public long getStartTime() {
    return startTime;
  }

  /**
   * Returns the wall-clock time in milliseconds when {@link #run()} returned,
   * or {@code -1} if the task has not finished yet.
   *
   * @return end timestamp in milliseconds, or {@code -1}
   */
  public long getEndTime() {
    return endTime;
  }

  /**
   * Returns the string key used to identify this task in
   * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#activeTasks}.
   *
   * @return the tracking key; never {@code null}
   */
  public String getTrackingId() {
    return trackingId;
  }

  /**
   * Returns the underlying task being tracked.
   *
   * @return the wrapped {@link RTPRunnable}; never {@code null}
   */
  public RTPRunnable getTask() {
    return task;
  }

  /**
   * Lifecycle states of a {@link TrackedRTPTask}.
   */
  public enum TaskState {
    /** Task has been created but {@link #run()} has not been called yet. */
    PENDING,
    /** {@link #run()} has been entered and has not yet returned. */
    RUNNING,
    /** {@link #run()} has returned (normally or via exception). */
    COMPLETED
  }
}
