package io.github.dailystruggle.server.api;

import java.util.logging.Level;

/** Platform-neutral information and logging supplied by the running server. */
public interface ServerAccessor {
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
}
