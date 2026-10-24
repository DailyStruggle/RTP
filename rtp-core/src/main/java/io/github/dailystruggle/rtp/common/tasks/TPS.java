package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;

public class TPS {
  public static double getTPS(int ticks) {
    return RTPRuntime.serverAccessor().getTPS(ticks);
  }

  public static long timeSinceTick(int ticks) {
    return (long) (1000.0 * ticks / getTPS(ticks));
  }
}
