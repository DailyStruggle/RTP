package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

public final class SyncTaskProcessing extends RTPRunnable {
  private final long availableTime;

  public SyncTaskProcessing(long availableTime) {
    this.availableTime = availableTime;
  }

  @Override
  public void run() {
    try {
      if (trackingId != null) {
        io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
      }
      long start = System.nanoTime();

      long currentAvailableTime = availableTime; // fallback
      if (RTP.configs != null) {
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>) RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
        if (perf != null) {
          long configMs = perf.getNumber(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.syncAllottedTime, 5).longValue();
          configMs = Math.min(configMs, 25);
          currentAvailableTime = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(configMs);
        }
      }

      RTP.getInstance().cancelTasks.execute(currentAvailableTime - (System.nanoTime() - start));
      RTP.getInstance().miscSyncTasks.execute(currentAvailableTime - (System.nanoTime() - start));
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
      try {
        CommandsAPI.execute();
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }
  }
}
