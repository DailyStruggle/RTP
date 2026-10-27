package io.github.dailystruggle.server.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform-neutral handle to a scheduled task (ADR-116).
 *
 * @governedBy ADR-035
 * @governedBy ADR-115
 */
public interface TaskHandle {
  /** Cancels the scheduled task if it has not yet completed or been cancelled. */
  void cancel();

  /**
   * Returns whether this task handle has been cancelled.
   *
   * @return {@code true} if cancelled
   */
  boolean isCancelled();

  /**
   * Wraps a cancellation callback in an idempotent {@link TaskHandle}.
   *
   * @param cancelAction callback invoked at most once on the first {@link #cancel()} call;
   *     must not be {@code null}
   * @return a new {@link TaskHandle}; never {@code null}
   */
  static TaskHandle of(Runnable cancelAction) {
    Objects.requireNonNull(cancelAction, "cancelAction");
    AtomicBoolean cancelled = new AtomicBoolean(false);
    return new TaskHandle() {
      @Override
      public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
          cancelAction.run();
        }
      }

      @Override
      public boolean isCancelled() {
        return cancelled.get();
      }
    };
  }
}
