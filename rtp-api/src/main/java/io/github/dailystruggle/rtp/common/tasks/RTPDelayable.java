package io.github.dailystruggle.rtp.common.tasks;

/**
 * Extends {@link Runnable} with a scheduler-facing delay in server ticks.
 *
 * <p>Thread safety: {@link #getDelay()} and {@link #setDelay(long)} are safe from any thread.
 *
 * @see RTPRunnable
 */
public interface RTPDelayable extends Runnable {
  /**
   * Returns the number of server ticks the scheduler should wait before executing
   * this task.
   *
   * @return delay in ticks; {@code 0} means run immediately
   */
  long getDelay();

  /**
   * Sets server ticks to wait before executing task.
   *
   * @param delay delay in ticks; must be >= 0
   */
  void setDelay(long delay);
}
