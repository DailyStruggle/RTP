package io.github.dailystruggle.server.api;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Process-wide access to the installed server bridge and generic logging fallback. */
public final class ServerAccessors {
  private static final Logger LOGGER = Logger.getLogger("Server");
  private static volatile ServerAccessor accessor;
  private static volatile TaskScheduler schedulerOverride;

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
    }
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

  /** Logs through the installed server bridge, or the generic server logger before initialization. */
  public static void log(Level level, String message) {
    ServerAccessor current = accessor;
    if (current == null) {
      LOGGER.log(level, message);
    } else {
      current.log(level, message);
    }
  }

  /** Logs through the installed server bridge, or the generic server logger before initialization. */
  public static void log(Level level, String message, Throwable throwable) {
    ServerAccessor current = accessor;
    if (current == null) {
      LOGGER.log(level, message, throwable);
    } else {
      current.log(level, message, throwable);
    }
  }
}
