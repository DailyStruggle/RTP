package io.github.dailystruggle.server.api;

import java.io.File;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * Platform-neutral information, logging, and capability surface supplied by the running server.
 *
 * <p>Core server identity, version compatibility, logging, scheduling, tick health, lifecycle
 * hooks, and progress-bar control live directly on this interface, while domain-specific host
 * capabilities are composed through focused sub-interfaces ({@link ScoreboardAccessor}, {@link
 * WorldBorderAccessor}, {@link PaletteTagAccessor}, {@link EffectHandleBridge}, {@link
 * MenuContextAccessor}, and {@link CommandDispatchAccessor}) with safe default implementations.
 *
 * @governedBy ADR-048
 * @governedBy ADR-049
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public interface ServerAccessor
    extends ScoreboardAccessor,
        WorldBorderAccessor,
        PaletteTagAccessor,
        EffectHandleBridge,
        MenuContextAccessor,
        CommandDispatchAccessor {
  /**
   * Returns the server-reported version string.
   *
   * @return server version string; never {@code null}
   */
  String getServerVersion();

  /**
   * Returns the platform identifier (for example, {@code "paper"} or {@code "folia"}).
   *
   * @return platform identifier; never {@code null}
   */
  String getPlatform();

  /**
   * Returns the headline Minecraft version integer (for example, {@code 21} for 1.21.x).
   *
   * @return server version, or {@code null} if it cannot be determined
   */
  Integer getServerIntVersion();

  /**
   * Returns the coarse {@link PlatformFamily} this server belongs to.
   *
   * <p>Unrecognised platform strings are logged and classified as UNKNOWN.
   *
   * @return the platform family; never {@code null}
   */
  default PlatformFamily getPlatformFamily() {
    String platform = getPlatform();
    if (platform != null) {
      if (platform.equalsIgnoreCase("fabric")) return PlatformFamily.FABRIC;
      if (platform.equalsIgnoreCase("neoforge")) return PlatformFamily.NEOFORGE;
      if (platform.equalsIgnoreCase("Folia")
          || platform.equalsIgnoreCase("Paper")
          || platform.equalsIgnoreCase("Spigot")) {
        return PlatformFamily.BUKKIT;
      }
    }
    log(
        Level.WARNING,
        "Unrecognised server platform '"
            + platform
            + "'; classifying as PlatformFamily.UNKNOWN. Platform-specific addon behaviour will be skipped.");
    return PlatformFamily.UNKNOWN;
  }

  /**
   * Tests whether this server belongs to the given {@link PlatformFamily}.
   *
   * @param family the family to test against; {@code null} returns {@code false}
   * @return {@code true} if the server matches {@code family}
   */
  default boolean isPlatformFamily(PlatformFamily family) {
    return family != null && getPlatformFamily() == family;
  }

  /**
   * Tests whether the integer server version is at least {@code minServerVersion}.
   *
   * <p>Returns {@code false} if the version is unknown.
   *
   * @param minServerVersion inclusive minimum version
   * @return {@code true} if the version is known and at least the minimum
   */
  default boolean isServerVersionAtLeast(int minServerVersion) {
    Integer version = getServerIntVersion();
    return version != null && version >= minServerVersion;
  }

  /**
   * Tests whether the integer server version is at most {@code maxServerVersion}.
   *
   * <p>Returns {@code false} if the version is unknown.
   *
   * @param maxServerVersion inclusive maximum version
   * @return {@code true} if the version is known and at most the maximum
   */
  default boolean isServerVersionAtMost(int maxServerVersion) {
    Integer version = getServerIntVersion();
    return version != null && version <= maxServerVersion;
  }

  /**
   * Tests whether this server satisfies both the platform-family and version constraints.
   *
   * @param requiredFamily required family, or {@code null} for any family
   * @param minServerVersion inclusive minimum ({@code 0} for no floor)
   * @param maxServerVersion inclusive maximum ({@link Integer#MAX_VALUE} for no ceiling)
   * @return {@code true} if the server satisfies the constraints
   */
  default boolean isCompatible(
      PlatformFamily requiredFamily, int minServerVersion, int maxServerVersion) {
    if (requiredFamily != null && getPlatformFamily() != requiredFamily) {
      return false;
    }
    boolean versionBounded = minServerVersion > 0 || maxServerVersion < Integer.MAX_VALUE;
    if (!versionBounded) {
      return true;
    }
    Integer version = getServerIntVersion();
    return version != null && version >= minServerVersion && version <= maxServerVersion;
  }

  /**
   * Writes a message to the server log at the given level.
   *
   * @param level the log level; must not be {@code null}
   * @param message the message; must not be {@code null}
   */
  void log(Level level, String message);

  /**
   * Writes a message and associated throwable to the server log at the given level.
   *
   * @param level the log level; must not be {@code null}
   * @param message the message; must not be {@code null}
   * @param throwable the exception to log; must not be {@code null}
   */
  void log(Level level, String message, Throwable throwable);

  /**
   * Returns the platform-neutral {@link TaskScheduler} provided by this server, if available.
   *
   * @return optional task scheduler; never {@code null}
   */
  default Optional<TaskScheduler> scheduler() {
    return Optional.empty();
  }

  /**
   * Returns the server name or network identity if configured, or {@code null}.
   *
   * @return server name or {@code null}
   */
  default String getServerName() {
    return null;
  }

  /**
   * Returns the plugin or mod data directory.
   *
   * @return data directory; never {@code null}
   */
  default File getPluginDirectory() {
    return new File(".");
  }

  /**
   * Returns whether the calling thread is the main server tick thread.
   *
   * @return {@code true} if called from the primary server thread
   */
  default boolean isPrimaryThread() {
    return true;
  }

  /**
   * Returns milliseconds current tick has exceeded its 50ms budget.
   *
   * @return over-time in ms; 0 if on time
   */
  default long overTime() {
    return 0L;
  }

  /**
   * Returns the server's average ticks-per-second (TPS) over the given number of recent ticks.
   *
   * @param ticks the sample window in ticks (e.g. 20 for 1 second, 100 for 5 seconds)
   * @return average TPS; defaults to {@code 20.0}
   */
  default double getTPS(int ticks) {
    return 20.0;
  }

  /**
   * Returns names of online players for tab completion without platform coupling.
   *
   * @return immutable snapshot of online player names; never {@code null}
   */
  default Set<String> getOnlinePlayerNames() {
    return Collections.emptySet();
  }

  /**
   * Returns the platform {@link PlayerLifecycleHook} (ADR-049).
   *
   * @return the platform lifecycle hook; never {@code null}
   */
  default PlayerLifecycleHook getPlayerLifecycleHook() {
    return NoopPlayerLifecycleHook.INSTANCE;
  }

  /** Hides and discards every progress bar previously shown by this server accessor. */
  default void clearProgressBars() {}

  /**
   * Dispatches a console command on the server runtime (ADR-116 Decision 2).
   *
   * @param command command string to execute
   */
  @Override
  default void dispatchConsoleCommand(String command) {
    CommandDispatchAccessor.super.dispatchConsoleCommand(command);
  }
}
