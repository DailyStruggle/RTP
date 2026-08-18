package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks plugin contribution to server milliseconds-per-tick (MSPT).
 * Accumulates nanoseconds via {@link LongAdder} and samples every 20 ticks.
 */
public class PerformanceTracker {
  /** Running total of nanoseconds consumed since last 20-tick sample. */
  public static final LongAdder totalNanosecondsConsumed = new LongAdder();
  /** Average plugin MSPT contribution over the most recent 20-tick window. */
  public static volatile double pluginMSPT = 0.0;

  /**
   * Starts the background MSPT sampling timer (fires every 20 ticks).
   *
   * @param scheduler platform scheduler; non-null
   */
  public static void start(RTPScheduler scheduler) {
    scheduler.runTaskTimerAsynchronously(
        () -> {
          long nanos = totalNanosecondsConsumed.sumThenReset();
          pluginMSPT = (nanos / 1_000_000.0) / 20.0;
        },
        20,
        20);
  }
}
