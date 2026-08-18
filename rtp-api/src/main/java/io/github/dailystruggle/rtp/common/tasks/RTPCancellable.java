package io.github.dailystruggle.rtp.common.tasks;

/**
 * Marks a task as cancellable. Implementations must be thread-safe.
 *
 * @see RTPRunnable
 */
public interface RTPCancellable {
  /**
   * Returns whether this task has been cancelled.
   *
   * @return {@code true} if cancellation has been requested, {@code false} if eligible to run
   */
  boolean isCancelled();

  /**
   * Requests cancellation or re-enables this task.
   *
   * @param cancel {@code true} to cancel and release resources, {@code false} to un-cancel
   */
  void setCancelled(boolean cancel);
}
