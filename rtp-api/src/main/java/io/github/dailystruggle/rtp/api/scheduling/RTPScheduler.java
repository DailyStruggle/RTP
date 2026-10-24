package io.github.dailystruggle.rtp.api.scheduling;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.server.api.TaskHandle;
import io.github.dailystruggle.server.api.TaskScheduler;

/**
 * Platform-agnostic scheduler SPI for synchronous, asynchronous, and region-bound tasks.
 */
@PublicApi
public interface RTPScheduler extends TaskScheduler {
  @Override
  default TaskHandle runAsync(Runnable task) {
    TrackedRTPTask tracked = runTaskAsynchronously(task);
    return TaskHandle.of(() -> {
      if (tracked != null) {
        tracked.setCancelled(true);
      }
    });
  }

  @Override
  default TaskHandle runSync(Runnable task) {
    runTask(task);
    return TaskHandle.of(() -> {});
  }

  @Override
  default TaskHandle runSyncDelayed(Runnable task, long delayTicks) {
    runTaskLater(task, delayTicks);
    return TaskHandle.of(() -> {});
  }

  @Override
  default TaskHandle runSyncTimer(Runnable task, long delayTicks, long periodTicks) {
    Object handle = runTaskTimer(task, delayTicks, periodTicks);
    return TaskHandle.of(() -> {
      if (handle != null) {
        cancelTask(handle);
      }
    });
  }

  @Override
  default TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
    Object handle = runTaskTimerAsynchronously(task, delayTicks, periodTicks);
    return TaskHandle.of(() -> {
      if (handle != null) {
        cancelTask(handle);
      }
    });
  }
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
   *
   * @param world world of chunk
   * @param cx chunk X
   * @param cz chunk Z
   * @param task task to run
   */
  void runTask(RTPWorld<?> world, int cx, int cz, Runnable task);

  /**
   * Executes a repeating task on the thread associated with the specified chunk.
   *
   * @param world world of chunk
   * @param cx chunk X
   * @param cz chunk Z
   * @param task task to run
   * @param delay initial delay in ticks
   * @param period interval in ticks
   * @return cancellable task handle
   */
  Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period);

  /**
   * Executes a delayed task on the thread associated with the specified chunk.
   *
   * @param world world of chunk
   * @param cx chunk X
   * @param cz chunk Z
   * @param task task to run
   * @param delay delay in ticks
   */
  void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay);

  /**
   * Whether new background work aimed at this chunk should wait because the tick loop that owns
   * it is backlogged or unhealthy (ADR-087 regional circuit breaker). Callers pick another
   * candidate or leave the work in its cache tier; player-facing work is never gated on this.
   * Single-loop platforms return {@link #isGenerationSaturated(RTPWorld)}: their server-wide
   * gates cover tick health.
   *
   * @param world world of chunk
   * @param cx chunk X
   * @param cz chunk Z
   * @return {@code true} when background work for this chunk should be deferred
   */
  default boolean isRegionSaturated(RTPWorld<?> world, int cx, int cz) {
    return isGenerationSaturated(world);
  }

  /**
   * Whether new background chunk loads in this world should wait because the server's chunk
   * system is still busy with earlier loads. Hosts whose chunk system serves requests
   * first-come, first-served (vanilla 1.20 / 1.21 on Spigot, Fabric, NeoForge) return
   * {@code true} while RTP's loads in flight reach a small cap, so the chunks players need are
   * not queued behind RTP's. Hosts with a prioritised chunk system (Paper, Folia) return
   * {@code false}. Player-facing work is never gated on this.
   *
   * @param world world the background work would load chunks in; may be {@code null}
   * @return {@code true} when background chunk loads should be deferred
   */
  default boolean isGenerationSaturated(RTPWorld<?> world) {
    return false;
  }
}
