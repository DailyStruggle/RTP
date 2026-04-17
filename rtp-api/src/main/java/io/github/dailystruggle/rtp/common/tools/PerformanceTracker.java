package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks the plugin's contribution to server milliseconds-per-tick (MSPT).
 *
 * <p>Every {@link io.github.dailystruggle.rtp.common.tasks.RTPRunnable} that runs via
 * {@link io.github.dailystruggle.rtp.common.tasks.RTPRunnable#runWithTracking()} adds its
 * nanosecond execution time to {@link #totalNanosecondsConsumed}. A background timer
 * (started via {@link #start(RTPScheduler)}) samples and resets this accumulator once
 * per second (every 20 ticks) and converts the reading to the
 * {@link #pluginMSPT} value shown by {@code /rtp info}.
 *
 * <p><b>Thread safety:</b> {@link #totalNanosecondsConsumed} is a
 * {@link java.util.concurrent.atomic.LongAdder}, which is safe for concurrent
 * increments from multiple async task threads. {@link #pluginMSPT} is {@code volatile}
 * and is written only by the single background timer thread.
 */
public class PerformanceTracker {
  /**
   * Running total of nanoseconds consumed by all {@link io.github.dailystruggle.rtp.common.tasks.RTPRunnable}
   * executions since the last sampling interval. Sampled and reset every 20 ticks
   * by the background timer started via {@link #start(RTPScheduler)}.
   */
  public static final LongAdder totalNanosecondsConsumed = new LongAdder();
  /**
   * The plugin's average milliseconds-per-tick contribution over the most recent
   * 20-tick (1 second) window.
   *
   * <p>Computed as {@code totalNanoseconds / 1_000_000 / 20} and updated once per
   * second by the background timer. A value of {@code 0.0} means the tracker has
   * not yet completed its first sampling interval.
   */
  public static volatile double pluginMSPT = 0.0;

  /**
   * Starts the background MSPT sampling timer.
   *
   * <p>Schedules an async repeating task that fires every 20 ticks. On each
   * invocation it atomically drains {@link #totalNanosecondsConsumed}, converts
   * the reading to milliseconds, divides by 20 ticks, and stores the result in
   * {@link #pluginMSPT}.
   *
   * <p>Must be called once during plugin startup. Calling it multiple times will
   * register duplicate timers and inflate the MSPT reading.
   *
   * @param scheduler the platform scheduler to use for the repeating task;
   *                  must not be {@code null}
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
