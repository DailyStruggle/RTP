package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.governor.ComputeBudget;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;

import java.util.concurrent.TimeUnit;

/**
 * Single source for the per-tick compute budgets in {@code performance.yml}.
 * Code fallbacks equal the shipped yml defaults; every value is clamped to
 * {@code [1, MAX_ALLOTTED_MS]} so a 50 ms tick always keeps at least half its
 * time for the server and other plugins.
 *
 * <p>Player-facing accessors ({@link #syncNanos()}, {@link #asyncNanos()}, {@link #periodTicks()})
 * always return the operator-configured values (ADR-087 Invariant I4). Background callers opt into
 * {@link #backgroundSyncNanos()}, {@link #backgroundAsyncNanos()}, and {@link #backgroundPeriodTicks()}
 * which read the published {@link ComputeBudget} snapshot when {@code autoTune} is active (ADR-087 Section 7.3).
 */
public final class TickBudget {
  /** Hard ceiling for {@code syncAllottedTime} / {@code asyncAllottedTime} (half a 20 TPS tick). */
  public static final long MAX_ALLOTTED_MS = 25L;
  public static final long DEFAULT_SYNC_MS = 5L;
  public static final long DEFAULT_ASYNC_MS = 25L;
  public static final long DEFAULT_PERIOD_TICKS = 20L;
  public static final boolean DEFAULT_AUTO_TUNE = true;

  private static volatile long syncNanos = -1L;
  private static volatile long asyncNanos = -1L;
  private static volatile long periodTicks = -1L;
  private static volatile int autoTuneState = -1; // -1 = unresolved, 0 = false, 1 = true

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
      Object rawAutoTune = perf.getConfigValue(PerformanceKeys.autoTune, DEFAULT_AUTO_TUNE);
      boolean at = (rawAutoTune instanceof Boolean b)
          ? b
          : (rawAutoTune == null ? DEFAULT_AUTO_TUNE : Boolean.parseBoolean(rawAutoTune.toString()));
      autoTuneState = at ? 1 : 0;
    } catch (Throwable t) {
      clear();
    }
  }

  /** Drops cached values; the next read re-resolves from config or defaults. */
  public static void clear() {
    syncNanos = -1L;
    asyncNanos = -1L;
    periodTicks = -1L;
    autoTuneState = -1;
  }

  /** Whether {@code performance.yml#autoTune} is enabled. */
  public static boolean autoTune() {
    int st = autoTuneState;
    if (st < 0) {
      refresh();
      st = autoTuneState;
    }
    return st < 0 ? false : (st == 1);
  }

  /** Test seam: overrides cached {@code autoTune} state until next {@link #refresh()} or {@link #clear()}. */
  public static void setAutoTuneForTesting(boolean enabled) {
    autoTuneState = enabled ? 1 : 0;
  }

  /** Tick-thread budget per sync pulse, nanoseconds (configured ceiling for player-facing paths, Invariant I4). */
  public static long syncNanos() {
    long v = syncNanos;
    if (v <= 0L) {
      refresh();
      v = syncNanos;
    }
    return v > 0L ? v : TimeUnit.MILLISECONDS.toNanos(DEFAULT_SYNC_MS);
  }

  /** Background-worker budget per async pulse, nanoseconds (configured ceiling for player-facing paths, Invariant I4). */
  public static long asyncNanos() {
    long v = asyncNanos;
    if (v <= 0L) {
      refresh();
      v = asyncNanos;
    }
    return v > 0L ? v : TimeUnit.MILLISECONDS.toNanos(DEFAULT_ASYNC_MS);
  }

  /** Configured ticks between successive executions of the same region's cache refill; at least 1. */
  public static long periodTicks() {
    long v = periodTicks;
    if (v <= 0L) {
      refresh();
      v = periodTicks;
    }
    return v > 0L ? v : DEFAULT_PERIOD_TICKS;
  }

  /** Effective background sync budget per tick in nanoseconds (ADR-087 Section 4.3 item 4 & 7.3). */
  public static long backgroundSyncNanos() {
    long cfg = syncNanos();
    ComputeBudget b = ComputeGovernor.currentBudget();
    if (b != null && b.isActuating()) {
      return Math.min(cfg, Math.max(0L, b.backgroundSyncNanos()));
    }
    return cfg;
  }

  /** Effective background async budget per pulse in nanoseconds (ADR-087 Section 4.3 item 3 & 7.3). */
  public static long backgroundAsyncNanos() {
    long cfg = asyncNanos();
    ComputeBudget b = ComputeGovernor.currentBudget();
    if (b != null && b.isActuating()) {
      return Math.min(cfg, Math.max(TimeUnit.MILLISECONDS.toNanos(1), b.backgroundAsyncNanos()));
    }
    return cfg;
  }

  /** Effective background idle period in ticks (ADR-087 Section 4.3 item 1 & 7.3). */
  public static long backgroundPeriodTicks() {
    long cfg = periodTicks();
    ComputeBudget b = ComputeGovernor.currentBudget();
    if (b != null && b.isActuating()) {
      return Math.max(cfg, b.backgroundPeriodTicks());
    }
    return cfg;
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
