package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.action.ActionServices;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.governor.ComputeBudget;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.concurrent.atomic.AtomicBoolean;
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
    return (ActiveConfigs.get() == null && availableTime > 0L) ? availableTime : TickBudget.syncNanos();
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
      RTPRuntime.log(Level.WARNING, "Exception during sync task processing", t);
    }

    try {
      if (!CommandsAPI.commandPipeline.isEmpty()) {
        // execute(long) always runs one command, so players never starve behind a full budget.
        CommandsAPI.execute(Math.max(0L, budget - (System.nanoTime() - start)));
      }
    } catch (Throwable t) {
      RTPRuntime.log(Level.WARNING, "Exception during CommandsAPI execution", t);
    }

    try {
      if (ActionServices.actionManager() != null) {
        ActionServices.actionManager().tick();
      }
    } catch (Throwable t) {
      RTPRuntime.log(Level.WARNING, "Exception during action manager tick", t);
    }

    try {
      long remainingBeforeMiscSync = budget - (System.nanoTime() - start);
      if (MiscTasks.sync() != null) {
        // Floor of 1 ns: at least one due player-facing task per tick, so teleport stages queued
        // here always progress even when the stages above spent the budget (S-004, ADR-087 I4).
        MiscTasks.sync().execute(Math.max(1L, remainingBeforeMiscSync));
      }

      RTPTaskPipe bgSyncPipe = MiscTasks.backgroundSync();
      long remainingAfterPlayerSync = budget - (System.nanoTime() - start);
      ComputeBudget govBudget = ComputeGovernor.currentBudget();
      if (govBudget != null && govBudget.isActuating()) {
        long bgSyncAvail = ComputeGovernor.active().beginBackgroundSyncSpan(remainingAfterPlayerSync);
        long syncStart = System.nanoTime();
        AtomicBoolean ranAnyBgSync = new AtomicBoolean(false);
        if (bgSyncPipe != null && bgSyncAvail > 0L) {
          bgSyncPipe.execute(bgSyncAvail, ranAnyBgSync);
        }
        long now = System.nanoTime();
        ComputeGovernor.active().endBackgroundSyncSpan(now - syncStart, now - start, budget);
      } else if (bgSyncPipe != null) {
        bgSyncPipe.execute(Math.max(1L, remainingAfterPlayerSync));
      }
    } catch (Throwable t) {
      RTPRuntime.log(Level.WARNING, "Exception during sync task processing", t);
    }
  }
}
