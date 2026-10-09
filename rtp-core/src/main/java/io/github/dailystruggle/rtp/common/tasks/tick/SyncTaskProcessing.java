package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class SyncTaskProcessing extends RTPRunnable {
  private static volatile long cachedSyncAllottedTimeNanos = -1L;

  private final long availableTime;

  public SyncTaskProcessing(long availableTime) {
    this.availableTime = availableTime;
  }

  /**
   * Refreshes the cached {@code syncAllottedTime} from {@link PerformanceKeys} configuration.
   * Called during config reload or when PerformanceKeys parser is registered.
   */
  public static void updateConfig() {
    if (RTP.configs != null) {
      @SuppressWarnings("unchecked")
      ConfigParser<PerformanceKeys> perf =
          (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
      if (perf != null) {
        long configMs = perf.getNumber(PerformanceKeys.syncAllottedTime, 5).longValue();
        configMs = Math.min(configMs, 25);
        cachedSyncAllottedTimeNanos = TimeUnit.MILLISECONDS.toNanos(configMs);
        return;
      }
    }
    cachedSyncAllottedTimeNanos = -1L;
  }

  /**
   * Clears the cached configuration.
   */
  public static void clearCachedConfig() {
    cachedSyncAllottedTimeNanos = -1L;
  }

  @Override
  public void run() {
    try {
      if (trackingId != null) {
        MemoryTracker.updateTracking(trackingId);
      }
      long start = System.nanoTime();

      long currentAvailableTime = cachedSyncAllottedTimeNanos;
      if (currentAvailableTime <= 0) {
        if (RTP.configs != null) {
          updateConfig();
          currentAvailableTime = cachedSyncAllottedTimeNanos;
        }
        if (currentAvailableTime <= 0) {
          currentAvailableTime = availableTime;
        }
      }

      RTP.getInstance().cancelTasks.execute(currentAvailableTime - (System.nanoTime() - start));
      RTP.getInstance().miscSyncTasks.execute(currentAvailableTime - (System.nanoTime() - start));

      if (RTP.actionManager != null) {
        RTP.actionManager.tick();
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "Exception during sync task processing", t);
    } finally {
      try {
        if (!CommandsAPI.commandPipeline.isEmpty()) {
          CommandsAPI.execute();
        }
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "Exception during CommandsAPI execution", t);
      }
    }
  }
}
