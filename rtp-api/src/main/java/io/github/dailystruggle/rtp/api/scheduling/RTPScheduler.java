package io.github.dailystruggle.rtp.api.scheduling;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

/**
 * Platform-agnostic abstraction for scheduling tasks on the server.
 *
 * <p>This interface provides methods for running tasks synchronously and asynchronously,
 * with support for delays and repeating timers. It also includes methods for
 * scheduling tasks on specific regions or chunks, which is essential for Folia
 * compatibility.
 *
 * <p>Implementations are provided by the platform adapter (e.g., {@code rtp-bukkit},
 * {@code rtp-folia}).
 */
public interface RTPScheduler {
  /**
   * Executes a task in an asynchronous thread.
   *
   * @param task the task to run
   * @return a {@link TrackedRTPTask} wrapper for monitoring the task
   */
  TrackedRTPTask runTaskAsynchronously(Runnable task);

  /**
   * Executes a task on the main server thread.
   *
   * @param task the task to run
   */
  void runTask(Runnable task);

  /**
   * Executes a task on the main server thread after a specified delay.
   *
   * @param task the task to run
   * @param delay the delay in server ticks
   */
  void runTaskLater(Runnable task, long delay);


  /**
   * Executes a repeating task on the main server thread.
   *
   * @param task the task to run
   * @param delay the delay before the first execution, in server ticks
   * @param period the interval between subsequent executions, in server ticks
   * @return a platform-specific task object that can be used to cancel it
   */
  Object runTaskTimer(Runnable task, long delay, long period);

  /**
   * Executes a repeating task in an asynchronous thread.
   *
   * @param task the task to run
   * @param delay the delay before the first execution, in server ticks
   * @param period the interval between subsequent executions, in server ticks
   * @return a platform-specific task object that can be used to cancel it
   */
  Object runTaskTimerAsynchronously(Runnable task, long delay, long period);

  /**
   * Cancels a previously scheduled task.
   *
   * @param task the platform-specific task object returned by a timer method
   */
  void cancelTask(Object task);

  /**
   * Schedules a teleport task with a delay, handling player-specific contexts.
   *
   * @param player the player to be teleported
   * @param task the teleport task to run
   * @param delayTicks the delay in server ticks
   */
  void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks);

  /**
   * Executes a task on the thread associated with the specified location.
   * On non-Folia platforms, this is the main server thread.
   *
   * @param location the location context for the task
   * @param task the task to run
   */
  void runTask(RTPLocation location, Runnable task);

  /**
   * Executes a task on the thread associated with the specified chunk.
   * On non-Folia platforms, this is the main server thread.
   *
   * @param world the world of the chunk
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   * @param task the task to run
   */
  void runTask(RTPWorld<?> world, int cx, int cz, Runnable task);

  /**
   * Executes a repeating task on the thread associated with the specified chunk.
   * On non-Folia platforms, this is the main server thread.
   *
   * @param world the world of the chunk
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   * @param task the task to run
   * @param delay the delay before the first execution, in server ticks
   * @param period the interval between subsequent executions, in server ticks
   * @return a platform-specific task object that can be used to cancel it
   */
  Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period);

  /**
   * Executes a task on the thread associated with the specified chunk after a delay.
   * On non-Folia platforms, this is the main server thread.
   *
   * @param world the world of the chunk
   * @param cx the chunk's X coordinate
   * @param cz the chunk's Z coordinate
   * @param task the task to run
   * @param delay the delay in server ticks
   */
  void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay);
}
