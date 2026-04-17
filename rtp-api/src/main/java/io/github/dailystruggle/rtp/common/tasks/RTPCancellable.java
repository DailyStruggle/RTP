package io.github.dailystruggle.rtp.common.tasks;

/**
 * Marks a task as cancellable.
 *
 * <p>Implementations must be thread-safe: {@link #isCancelled()} and
 * {@link #setCancelled(boolean)} may be called from any thread at any time,
 * including concurrently from the task's own execution thread and an external
 * cancellation source.
 *
 * <p>Cancellation is advisory: once {@code setCancelled(true)} is called, the task
 * is expected to abort at the earliest safe opportunity and release any held
 * resources (e.g. chunk tickets). There is no guarantee of immediate termination.
 *
 * @see RTPRunnable
 */
public interface RTPCancellable {
  /**
   * Returns whether this task has been cancelled.
   *
   * @return {@code true} if cancellation has been requested, {@code false} if the task
   *         is still eligible to run
   */
  boolean isCancelled();

  /**
   * Requests cancellation or re-enables this task.
   *
   * <p>Passing {@code true} signals the task to abort at the earliest safe point and
   * release all associated resources. Passing {@code false} clears a prior cancellation
   * request, but only if the task has not yet begun executing.
   *
   * @param cancel {@code true} to cancel, {@code false} to un-cancel
   */
  void setCancelled(boolean cancel);
}
