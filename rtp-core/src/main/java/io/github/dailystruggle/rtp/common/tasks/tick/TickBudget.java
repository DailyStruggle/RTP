package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;

import java.util.concurrent.TimeUnit;

/**
 * Single source for the per-tick compute budgets in {@code performance.yml}.
 * Code fallbacks equal the shipped yml defaults; every value is clamped to
 * {@code [1, MAX_ALLOTTED_MS]} so a 50 ms tick always keeps at least half its
 * time for the server and other plugins.
 */
public final class TickBudget {
  /** Hard ceiling for {@code syncAllottedTime} / {@code asyncAllottedTime} (half a 20 TPS tick). */
  public static final long MAX_ALLOTTED_MS = 25L;
  public static final long DEFAULT_SYNC_MS = 5L;
  public static final long DEFAULT_ASYNC_MS = 25L;
  public static final long DEFAULT_PERIOD_TICKS = 20L;

  private static volatile long syncNanos = -1L;
  private static volatile long asyncNanos = -1L;
  private static volatile long periodTicks = -1L;

  static {
    io.github.dailystruggle.rtp.common.selection.SelectionAPI.setDefaultBudgetSupplier(TickBudget::asyncNanos);
  }

  private TickBudget() {}

  /** Re-reads the budgets; called on PerformanceKeys registration and config reload. */
  public static void refresh() {
    ConfigParser<PerformanceKeys> perf = parser();
    if (perf == null) {
      clear();
      return;
    }
    try {
      syncNanos = toNanos(perf.getDuration(PerformanceKeys.syncAllottedTime,
          TemporalUnit.MILLISECOND, TemporalUnit.MILLISECOND, DEFAULT_SYNC_MS));
      asyncNanos = toNanos(perf.getDuration(PerformanceKeys.asyncAllottedTime,
          TemporalUnit.MILLISECOND, TemporalUnit.MILLISECOND, DEFAULT_ASYNC_MS));
      long p = perf.getDuration(PerformanceKeys.period,
          TemporalUnit.TICK, TemporalUnit.TICK, DEFAULT_PERIOD_TICKS);
      periodTicks = Math.max(1L, p);
    } catch (Throwable t) {
      clear();
    }
  }

  /** Drops cached values; the next read re-resolves from config or defaults. */
  public static void clear() {
    syncNanos = -1L;
    asyncNanos = -1L;
    periodTicks = -1L;
  }

  /** Tick-thread budget per sync pulse, nanoseconds. */
  public static long syncNanos() {
    long v = syncNanos;
    if (v <= 0L) {
      refresh();
      v = syncNanos;
    }
    return v > 0L ? v : TimeUnit.MILLISECONDS.toNanos(DEFAULT_SYNC_MS);
  }

  /** Background-worker budget per async pulse, nanoseconds. */
  public static long asyncNanos() {
    long v = asyncNanos;
    if (v <= 0L) {
      refresh();
      v = asyncNanos;
    }
    return v > 0L ? v : TimeUnit.MILLISECONDS.toNanos(DEFAULT_ASYNC_MS);
  }

  /** Ticks between successive executions of the same region's cache refill; at least 1. */
  public static long periodTicks() {
    long v = periodTicks;
    if (v <= 0L) {
      refresh();
      v = periodTicks;
    }
    return v > 0L ? v : DEFAULT_PERIOD_TICKS;
  }

  static long toNanos(long ms) {
    return TimeUnit.MILLISECONDS.toNanos(Math.max(1L, Math.min(ms, MAX_ALLOTTED_MS)));
  }

  @SuppressWarnings("unchecked")
  private static ConfigParser<PerformanceKeys> parser() {
    if (ActiveConfigs.get() == null) return null;
    try {
      return (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
    } catch (Throwable t) {
      return null;
    }
  }
}
