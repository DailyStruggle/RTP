package io.github.dailystruggle.rtp.common.runtime;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Process-wide holder for the platform services core packages use (ADR-115 Tier 1).
 *
 * <p>The composition root ({@code common.RTP}) and platform entry points install the slots;
 * every other package reads them here instead of through {@code RTP}, so no domain package
 * depends on the composition root. Slots are {@code volatile} and start {@code null}: a read
 * before platform enable returns {@code null}, as the former {@code RTP} statics did.
 *
 * <p>Logging: sub-INFO records are buffered while the injected gate reports that the
 * {@code logging.yml} parser is not built (default gate: always, matching a null config),
 * capped at {@value #PENDING_LOGS_CAP}; {@link #flushPendingLogs()} replays them in order and
 * opens the buffer permanently. With no accessor installed, records go to the JUL logger
 * {@code "RTP"} so pre-enable and post-failed-enable paths never NPE.
 */
public final class RTPRuntime {
  /** Hard cap so a misconfigured/late bootstrap can't grow the buffer unbounded. */
  static final int PENDING_LOGS_CAP = 4096;

  private static volatile RTPServerAccessor serverAccessor;
  private static volatile RTPScheduler scheduler;
  private static volatile RTPEconomy economy;
  private static volatile BooleanSupplier logBufferGate = () -> true;

  /** Sub-INFO records emitted before the logging parser exists; drained by {@link #flushPendingLogs()}. */
  private static final ConcurrentLinkedQueue<Object[]> pendingLogs = new ConcurrentLinkedQueue<>();

  /** True until {@link #flushPendingLogs()} runs; volatile so the hot log path needs no lock. */
  private static volatile boolean logsBuffered = true;

  static {
    ServerAccessors.setLogSink(RTPRuntime::log);
  }

  private static final RTPContext CONTEXT = new RTPContext() {
    @Override
    public RTPServerAccessor serverAccessor() {
      return RTPRuntime.serverAccessor();
    }

    @Override
    public RTPScheduler scheduler() {
      return RTPRuntime.scheduler();
    }

    @Override
    public RTPEconomy economy() {
      return RTPRuntime.economy();
    }

    @Override
    public void log(Level level, String msg) {
      RTPRuntime.log(level, msg);
    }

    @Override
    public void log(Level level, String msg, Throwable t) {
      RTPRuntime.log(level, msg, t);
    }
  };

  private RTPRuntime() {
  }

  /** View over the live slots; reads always reflect the latest install. */
  public static RTPContext context() {
    return CONTEXT;
  }

  public static RTPServerAccessor serverAccessor() {
    return serverAccessor;
  }

  public static void setServerAccessor(RTPServerAccessor accessor) {
    serverAccessor = accessor;
    ServerAccessors.set(accessor);
    ServerAccessors.setLogSink(RTPRuntime::log);
  }

  public static RTPScheduler scheduler() {
    return scheduler;
  }

  public static void setScheduler(RTPScheduler s) {
    scheduler = s;
  }

  public static RTPEconomy economy() {
    return economy;
  }

  public static void setEconomy(RTPEconomy e) {
    economy = e;
  }

  /**
   * Installs the predicate that reports whether sub-INFO records must still be buffered.
   * A {@code null} gate restores the default (always buffer until flushed).
   */
  public static void setLogBufferGate(BooleanSupplier gate) {
    logBufferGate = gate == null ? () -> true : gate;
  }

  private static boolean gateClosed() {
    try {
      return logBufferGate.getAsBoolean();
    } catch (Throwable t) {
      return true;
    }
  }

  /**
   * Drains buffered sub-INFO records through the live sink, in emission order. Idempotent;
   * once drained, later {@link #log} calls bypass the buffer.
   */
  public static void flushPendingLogs() {
    logsBuffered = false;
    RTPServerAccessor accessor = serverAccessor;
    Object[] entry;
    while ((entry = pendingLogs.poll()) != null) {
      Level lvl = (Level) entry[0];
      String msg = (String) entry[1];
      Throwable t = (Throwable) entry[2];
      try {
        if (accessor == null) {
          if (t == null) Logger.getLogger("RTP").log(lvl, msg);
          else Logger.getLogger("RTP").log(lvl, msg, t);
        } else {
          if (t == null) accessor.log(lvl, msg);
          else accessor.log(lvl, msg, t);
        }
      } catch (Throwable ignored) {
        // Never let replay failures break the caller.
      }
    }
  }

  public static void log(Level level, String str) {
    RTPServerAccessor accessor = serverAccessor;
    if (logsBuffered && level != null && level.intValue() < Level.INFO.intValue()
        && gateClosed()) {
      if (pendingLogs.size() < PENDING_LOGS_CAP) {
        pendingLogs.offer(new Object[] {level, str, null});
      }
      return;
    }
    if (accessor == null) {
      Logger.getLogger("RTP").log(level, str);
      return;
    }
    accessor.log(level, str);
  }

  public static void log(Level level, String str, Throwable throwable) {
    RTPServerAccessor accessor = serverAccessor;
    if (logsBuffered && level != null && level.intValue() < Level.INFO.intValue()
        && gateClosed()) {
      if (pendingLogs.size() < PENDING_LOGS_CAP) {
        pendingLogs.offer(new Object[] {level, str, throwable});
      }
      return;
    }
    if (accessor == null) {
      if (throwable == null) {
        Logger.getLogger("RTP").log(level, str);
      } else {
        Logger.getLogger("RTP").log(level, str, throwable);
      }
      return;
    }
    if (throwable == null) {
      accessor.log(level, str);
    } else {
      accessor.log(level, str, throwable);
    }
  }

  /** Test hook: re-arms the bootstrap buffer and drops pending records. */
  static void resetLogBufferForTests() {
    pendingLogs.clear();
    logsBuffered = true;
  }

  /** Test hook: the installed buffering gate, so a test can restore it. */
  static BooleanSupplier logBufferGateForTests() {
    return logBufferGate;
  }

  /** Test hook: whether sub-INFO records are still eligible for buffering. */
  static boolean logsBufferedForTests() {
    return logsBuffered;
  }

  /** Test hook: restores the buffering flag captured by {@link #logsBufferedForTests()}. */
  static void setLogsBufferedForTests(boolean buffered) {
    logsBuffered = buffered;
  }

  /** Test hook: number of records currently buffered. */
  static int pendingLogCountForTests() {
    return pendingLogs.size();
  }
}
