package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.logging.Level;

/**
 * Per-tick sync pulse. Every stage is charged against one {@code syncAllottedTime} budget
 * ({@link TickBudget#syncNanos()}): latency-sensitive stages (player commands, action sessions)
 * run first, and the misc sync pipe only gets what is left, so total tick-thread cost stays at
 * the budget unless the fixed stages alone overrun it. {@code cancelTasks} is drained by
 * {@link AsyncTaskProcessing} only: two drainers on different threads would race its tasks.
 */
public final class SyncTaskProcessing extends RTPRunnable {
  private final long availableTime;

  /** @param availableTime fallback budget in nanoseconds, used only before configs load */
  public SyncTaskProcessing(long availableTime) {
    this.availableTime = availableTime;
  }

  /** Re-reads {@code syncAllottedTime}; kept for callers that refresh after a reload. */
  public static void updateConfig() {
    TickBudget.refresh();
  }

  /** Clears the cached budget. */
  public static void clearCachedConfig() {
    TickBudget.clear();
  }

  /** Budget for this pulse in nanoseconds. */
  long budgetNanos() {
    return (RTP.configs == null && availableTime > 0L) ? availableTime : TickBudget.syncNanos();
  }

  @Override
  public void run() {
    long start = System.nanoTime();
    long budget = budgetNanos();
    try {
      if (trackingId != null) {
        MemoryTracker.updateTracking(trackingId);
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "Exception during sync task processing", t);
    }

    try {
      if (!CommandsAPI.commandPipeline.isEmpty()) {
        // execute(long) always runs one command, so players never starve behind a full budget.
        CommandsAPI.execute(Math.max(0L, budget - (System.nanoTime() - start)));
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "Exception during CommandsAPI execution", t);
    }

    try {
      if (RTP.actionManager != null) {
        RTP.actionManager.tick();
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "Exception during action manager tick", t);
    }

    try {
      // Floor of 1 ns: at least one due task per tick, so teleport stages queued here
      // always progress even when the stages above spent the budget (S-004).
      long remaining = budget - (System.nanoTime() - start);
      RTP.getInstance().miscSyncTasks.execute(Math.max(1L, remaining));
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "Exception during sync task processing", t);
    }
  }
}
