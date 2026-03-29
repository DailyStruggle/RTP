package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import java.util.concurrent.atomic.LongAdder;

public class PerformanceTracker {
  public static final LongAdder totalNanosecondsConsumed = new LongAdder();
  public static volatile double pluginMSPT = 0.0;

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
