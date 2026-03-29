package io.github.dailystruggle.rtp.api.scheduling;

/** Interface for scheduling tasks on the server */
public interface RTPScheduler {
  /**
   * Run a task asynchronously
   *
   * @param task the task to run
   */
  void runTaskAsynchronously(Runnable task);

  /**
   * Run a task on the primary thread
   *
   * @param task the task to run
   */
  void runTask(Runnable task);

  /**
   * Run a task on the primary thread later
   *
   * @param task the task to run
   * @param delay the delay in ticks
   */
  void runTaskLater(Runnable task, long delay);

  /**
   * Run a task timer on the primary thread
   *
   * @param task the task to run
   * @param delay the delay in ticks
   * @param period the period in ticks
   * @return a task ID or object that can be used to cancel the task
   */
  Object runTaskTimer(Runnable task, long delay, long period);

  /**
   * Cancel a task
   *
   * @param task the task ID or object returned by runTaskTimer
   */
  void cancelTask(Object task);

  /**
   * Schedule a teleport contextually
   *
   * @param player the player to teleport
   * @param task the task to run
   * @param delayTicks the delay in ticks
   */
  void scheduleTeleport(
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
      io.github.dailystruggle.rtp.common.tasks.RTPRunnable task,
      long delayTicks);
}
