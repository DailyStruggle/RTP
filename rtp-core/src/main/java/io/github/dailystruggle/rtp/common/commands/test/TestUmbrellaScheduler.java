package io.github.dailystruggle.rtp.common.commands.test;

/**
 * Platform-agnostic scheduler sink for deferred {@code /rtp test} command dispatches.
 * Wired through {@link TestUmbrellaContext}.
 */
public interface TestUmbrellaScheduler {

  /**
   * Schedules {@code task} after at least {@code delayMillis} milliseconds (S-004, S-005).
   *
   * @param delayMillis delay in milliseconds (values < 0 clamped to 0)
   * @param task        runnable to execute (non-null)
   */
  void runLater(long delayMillis, Runnable task);
}
