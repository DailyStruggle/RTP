package io.github.dailystruggle.server.api;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Process-wide access to the installed server bridge and generic logging fallback. */
public final class ServerAccessors {
  private static final Logger LOGGER = Logger.getLogger("Server");
  private static volatile ServerAccessor accessor;
  private static volatile TaskScheduler schedulerOverride;
  private static volatile LogSink logSink;

  /**
   * Pluggable log sink override (e.g. {@code RTPRuntime::log} for bootstrap sub-INFO log
   * buffering before {@code logging.yml} is loaded).
   */
  @FunctionalInterface
  public interface LogSink {
    /**
     * Records a log message at {@code level} with optional {@code throwable}.
     *
     * @param level log level
     * @param message log message
     * @param throwable associated throwable, or {@code null}
     */
    void log(Level level, String message, Throwable throwable);
  }

  private ServerAccessors() {}

  /** Returns the installed server bridge, or {@code null} before server initialization. */
  public static ServerAccessor get() {
    return accessor;
  }

  /** Installs or clears the server bridge. */
  public static void set(ServerAccessor serverAccessor) {
    accessor = serverAccessor;
    if (serverAccessor == null) {
      schedulerOverride = null;
      logSink = null;
    }
  }

  /**
   * Installs or clears an explicit {@link LogSink} override. When non-{@code null},
   * {@link #log(Level, String)} and {@link #log(Level, String, Throwable)} delegate to the sink.
   *
   * @param sink log sink override, or {@code null} to clear
   */
  public static void setLogSink(LogSink sink) {
    logSink = sink;
  }

  /**
   * Installs or clears an explicit {@link TaskScheduler} override. When {@code null},
   * {@link #scheduler()} delegates to {@link ServerAccessor#scheduler()}.
   *
   * @param scheduler task scheduler override, or {@code null} to clear
   */
  public static void setScheduler(TaskScheduler scheduler) {
    schedulerOverride = scheduler;
  }

  /**
   * Returns the active {@link TaskScheduler} from the explicit override or installed
   * {@link ServerAccessor}, if present.
   *
   * @return optional task scheduler; never {@code null}
   */
  public static Optional<TaskScheduler> scheduler() {
    TaskScheduler explicit = schedulerOverride;
    if (explicit != null) {
      return Optional.of(explicit);
    }
    ServerAccessor current = accessor;
    return current == null ? Optional.empty() : current.scheduler();
  }

  /** Logs through the installed log sink, server bridge, or generic server logger. */
  public static void log(Level level, String message) {
    LogSink sink = logSink;
    if (sink != null) {
      sink.log(level, message, null);
      return;
    }
    ServerAccessor current = accessor;
    if (current == null) {
      LOGGER.log(level, message);
    } else {
      current.log(level, message);
    }
  }

  /** Logs through the installed log sink, server bridge, or generic server logger. */
  public static void log(Level level, String message, Throwable throwable) {
    LogSink sink = logSink;
    if (sink != null) {
      sink.log(level, message, throwable);
      return;
    }
    ServerAccessor current = accessor;
    if (current == null) {
      if (throwable == null) {
        LOGGER.log(level, message);
      } else {
        LOGGER.log(level, message, throwable);
      }
    } else {
      if (throwable == null) {
        current.log(level, message);
      } else {
        current.log(level, message, throwable);
      }
    }
  }
}
