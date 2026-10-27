package io.github.dailystruggle.server.api;

import java.util.Objects;

/**
 * Platform-neutral scheduling and region-thread ownership contract (ADR-116).
 *
 * @governedBy ADR-035
 * @governedBy ADR-115
 */
public interface TaskScheduler {
  /**
   * Schedules a task for asynchronous execution off the primary or region thread.
   *
   * @param task task to execute; must not be {@code null}
   * @return handle for the scheduled task; never {@code null}
   */
  TaskHandle runAsync(Runnable task);

  /**
   * Schedules a task on the primary server thread (or global region on regionalized servers).
   *
   * @param task task to execute; must not be {@code null}
   * @return handle for the scheduled task; never {@code null}
   */
  TaskHandle runSync(Runnable task);

  /**
   * Schedules a delayed synchronous task.
   *
   * @param task task to execute; must not be {@code null}
   * @param delayTicks delay in server ticks before execution
   * @return handle for the scheduled task; never {@code null}
   */
  default TaskHandle runSyncDelayed(Runnable task, long delayTicks) {
    Objects.requireNonNull(task, "task");
    return runSync(task);
  }

  /**
   * Schedules a repeating synchronous timer task.
   *
   * @param task task to execute; must not be {@code null}
   * @param delayTicks initial delay in server ticks
   * @param periodTicks period in server ticks between executions
   * @return handle for the scheduled task; never {@code null}
   */
  default TaskHandle runSyncTimer(Runnable task, long delayTicks, long periodTicks) {
    Objects.requireNonNull(task, "task");
    return runSync(task);
  }

  /**
   * Schedules a repeating asynchronous timer task.
   *
   * @param task task to execute; must not be {@code null}
   * @param delayTicks initial delay in server ticks
   * @param periodTicks period in server ticks between executions
   * @return handle for the scheduled task; never {@code null}
   */
  default TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
    Objects.requireNonNull(task, "task");
    return runAsync(task);
  }

  /**
   * Schedules a task on the thread owning the given world and chunk coordinates (regionalized
   * servers), or falls back to {@link #runSync(Runnable)} on single-thread servers.
   *
   * @param worldName target world identifier; must not be {@code null}
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param task task to execute; must not be {@code null}
   * @return handle for the scheduled task; never {@code null}
   */
  default TaskHandle runAtLocation(String worldName, int chunkX, int chunkZ, Runnable task) {
    Objects.requireNonNull(worldName, "worldName");
    Objects.requireNonNull(task, "task");
    return runSync(task);
  }

  /**
   * Returns whether the current thread owns the region covering the given world and chunk
   * coordinates. Defaults to {@link #isPrimaryThread()}.
   *
   * @param worldName target world identifier; must not be {@code null}
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @return {@code true} if owned by the calling thread
   */
  default boolean isOwnedByCurrentRegion(String worldName, int chunkX, int chunkZ) {
    Objects.requireNonNull(worldName, "worldName");
    return isPrimaryThread();
  }

  /**
   * Returns whether the calling thread is the server primary thread.
   *
   * @return {@code true} if on the primary thread
   */
  default boolean isPrimaryThread() {
    return true;
  }
}
