package io.github.dailystruggle.rtp.common.tasks;

/**
 * Extends {@link Runnable} with a scheduler-facing delay contract.
 *
 * <p>The delay value is a hint to the scheduler indicating how many server ticks
 * should elapse before the task is first dispatched. A value of {@code 0} means
 * the task should be run as soon as possible on the next available scheduling
 * opportunity.
 *
 * <p><b>Thread safety:</b> Implementations must allow {@link #getDelay()} and
 * {@link #setDelay(long)} to be called from any thread. Changing the delay after
 * the task has already been submitted to a scheduler has no effect.
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
   * Sets the number of server ticks the scheduler should wait before executing
   * this task.
   *
   * <p>Must be called before submitting the task to a scheduler; changes after
   * submission are silently ignored.
   *
   * @param delay delay in ticks; must be &ge; 0
   */
  void setDelay(long delay);
}
