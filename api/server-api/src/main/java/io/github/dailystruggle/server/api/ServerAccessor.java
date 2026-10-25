package io.github.dailystruggle.server.api;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
   * Returns permission probe for menu visibility filtering (ADR-048).
   *
   * @param player player UUID
   * @return non-null permission predicate; defaults to always {@code false}
   */
  default Predicate<String> menuPermissionProbe(UUID player) {
    return node -> false;
  }

  /**
   * Returns effective permissions snapshot for menu namespaces (ADR-048).
   *
   * @param player player UUID
   * @return permission set; empty if unresolved
   */
  default Set<String> menuEffectivePermissions(UUID player) {
    return Collections.emptySet();
  }

  /**
   * Returns BCP-47 locale tag for menu rendering (ADR-048).
   *
   * @param player player UUID
   * @return locale tag; defaults to {@code "en_us"}
   */
  default String menuLocale(UUID player) {
    return "en_us";
  }

  /**
   * Returns descriptor for player's current region/world (ADR-048).
   *
   * @param player player UUID
   * @return region descriptor; empty string if unknown
   */
  default String menuRegionDescriptor(UUID player) {
    return "";
  }

  /**
   * Registers a platform-neutral command tree with the underlying server runtime.
   *
   * @param rootCommand the command tree root
   * @param aliases aliases to bind
   */
  default void registerCommands(Object rootCommand, String... aliases) {}

  /**
   * Dispatches a raw command line as if entered by the given sender.
   *
   * @param senderId the caller UUID
   * @param commandLine full command string
   * @return {@code true} if dispatched, {@code false} otherwise
   */
  default boolean executeCommand(UUID senderId, String commandLine) {
    return false;
  }

  /**
   * Executes a console command and captures emitted messages/feedback lines into a consumer.
   *
   * @param commandLine the command string to execute
   * @param lineConsumer consumer receiving each line of output
   * @return {@code true} if the command was successfully dispatched, {@code false} otherwise
   */
  default boolean executeCommandWithCapture(String commandLine, Consumer<String> lineConsumer) {
    return false;
  }

  /**
   * Executes a console command and captures emitted messages/feedback lines into a
   * {@link CompletableFuture}.
   *
   * @param commandLine the command string to execute
   * @return future completing with captured output lines
   */
  default CompletableFuture<List<String>> executeCommandWithCapture(String commandLine) {
    CompletableFuture<List<String>> future = new CompletableFuture<>();
    List<String> lines = new CopyOnWriteArrayList<>();
    boolean dispatched = executeCommandWithCapture(commandLine, lines::add);
    if (!dispatched) {
      future.complete(Collections.emptyList());
    } else {
      future.complete(lines);
    }
    return future;
  }

  /**
   * Returns the set of Minecraft scoreboard tags currently applied to the given player.
   *
   * @param playerId player UUID
   * @return unmodifiable set of scoreboard tags; empty if unknown or player offline
   */
  default Set<String> getScoreboardTags(UUID playerId) {
    return Collections.emptySet();
  }

  /**
   * Adds a scoreboard tag to the player.
   *
   * @param playerId player UUID
   * @param tag the tag to add
   * @return {@code true} if added, {@code false} if already present or player offline
   */
  default boolean addScoreboardTag(UUID playerId, String tag) {
    return false;
  }

  /**
   * Removes a scoreboard tag from the player.
   *
   * @param playerId player UUID
   * @param tag the tag to remove
   * @return {@code true} if removed, {@code false} if not present or player offline
   */
  default boolean removeScoreboardTag(UUID playerId, String tag) {
    return false;
  }

  /**
   * Retrieves the current scoreboard score for the specified player and objective name.
   *
   * @param playerId the player's unique identifier; must not be {@code null}
   * @param objective the objective name; must not be {@code null}
   * @return the player's score, or {@code null} if the objective or score does not exist
   */
  default Integer getScoreboardScore(UUID playerId, String objective) {
    return null;
  }

  /**
   * Sets a scoreboard score for the player on a given objective.
   *
   * @param playerId player UUID
   * @param objective the objective name
   * @param score the score value
   */
  default void setScoreboardScore(UUID playerId, String objective, int score) {}

  /**
   * Resets scores for the player on a given objective, or across all objectives if {@code null}.
   *
   * @param playerId player UUID
   * @param objective objective name, or {@code null} to reset all
   */
  default void resetScoreboardScore(UUID playerId, String objective) {}

  /**
   * Returns whether the host exposes a scoreboard objective.
   *
   * @param objective the objective name; must not be {@code null}
   * @return {@link Optional#of(Object)} containing {@code true} when present or {@code false} when
   *     absent, or {@link Optional#empty()} when the host cannot determine whether it is present
   */
  default Optional<Boolean> hasScoreboardObjective(String objective) {
    return Optional.empty();
  }

  /**
   * Ensures a scoreboard objective exists.
   *
   * @param objective the objective name
   * @param criteria the objective criteria (defaults to {@code "dummy"} if {@code null})
   */
  default void ensureScoreboardObjective(String objective, String criteria) {}

  /**
   * Sends a clientbound per-player world border packet setting its center and size.
   *
   * @param playerId player UUID to receive the world border
   * @param centerX center X coordinate
   * @param centerZ center Z coordinate
   * @param size border diameter (width) in blocks
   */
  default void sendWorldBorder(UUID playerId, double centerX, double centerZ, double size) {
    sendWorldBorder(playerId, centerX, centerZ, size, size, 0L);
  }

  /**
   * Sends a clientbound per-player world border packet setting its center and optionally lerping
   * size over time.
   *
   * @param playerId player UUID to receive the world border
   * @param centerX center X coordinate
   * @param centerZ center Z coordinate
   * @param oldSize starting border diameter (width) in blocks
   * @param newSize target border diameter (width) in blocks
   * @param shrinkSeconds duration in seconds to transition from oldSize to newSize (0 for instant)
   */
  default void sendWorldBorder(
      UUID playerId,
      double centerX,
      double centerZ,
      double oldSize,
      double newSize,
      long shrinkSeconds) {
    sendWorldBorder(playerId, centerX, centerZ, oldSize, newSize, shrinkSeconds, 0.0, 0.0);
  }

  /**
   * Sends a clientbound per-player world border packet setting its center, size transition, damage
   * amount, and buffer.
   *
   * @param playerId player UUID to receive the world border
   * @param centerX center X coordinate
   * @param centerZ center Z coordinate
   * @param oldSize starting border diameter (width) in blocks
   * @param newSize target border diameter (width) in blocks
   * @param shrinkSeconds duration in seconds to transition from oldSize to newSize (0 for instant)
   * @param damageAmount damage per block outside buffer
   * @param damageBuffer buffer distance in blocks before damage begins
   */
  default void sendWorldBorder(
      UUID playerId,
      double centerX,
      double centerZ,
      double oldSize,
      double newSize,
      long shrinkSeconds,
      double damageAmount,
      double damageBuffer) {}

  /**
   * Applies damage to the specified player. Safe across server threads and platform runtimes.
   *
   * @param playerId player UUID
   * @param amount amount of damage to apply
   */
  default void damagePlayer(UUID playerId, double amount) {}

  /**
   * Resets the player's clientbound world border to match the world's actual border.
   *
   * @param playerId player UUID whose world border is to be restored
   */
  default void resetWorldBorder(UUID playerId) {}

  /**
   * Returns the set of all material names known to the server.
   *
   * @return set of material name strings in the platform's canonical format; never {@code null}
   */
  default Set<String> materials() {
    return Collections.emptySet();
  }

  /**
   * Returns immutable snapshot of live block tags for safety parsing (ADR-017).
   *
   * <p>Keyed by lowercase {@code namespace:path}. Values are sets of uppercase material names.
   *
   * @return immutable tag map; never {@code null}
   */
  default Map<String, Set<String>> blockTagSnapshot() {
    return Collections.emptyMap();
  }

  /**
   * Instructs the accessor to rebuild its block-tag snapshot on the next call to {@link
   * #blockTagSnapshot()}.
   */
  default void rebuildBlockTagSnapshot() {}

  /**
   * Reconciles a raw on-disk block or biome palette identifier against the platform registry.
   *
   * <p>The default implementation strips any leading {@code namespace:} prefix and normalizes to
   * uppercase under {@link Locale#ROOT}.
   *
   * @param raw raw identifier from disk or config (e.g. {@code "minecraft:stone"}, {@code "stone"})
   * @return canonical identifier for lookups, or {@code null} if {@code raw} is {@code null}
   */
  default String reconcilePaletteIdentifier(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    int colon = trimmed.indexOf(':');
    String path = (colon < 0) ? trimmed : trimmed.substring(colon + 1);
    return path.toUpperCase(Locale.ROOT);
  }

  /**
   * Reconciles a collection of raw identifiers into an unmodifiable set of canonical identifiers.
   *
   * @param raw collection of raw identifiers
   * @return unmodifiable set of canonical identifiers
   */
  default Set<String> reconcilePaletteIdentifiers(Collection<String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptySet();
    Set<String> out = new LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      String n = reconcilePaletteIdentifier(s);
      if (n != null && !n.isEmpty()) out.add(n);
    }
    return Collections.unmodifiableSet(out);
  }

  /**
   * Checks whether the reconciled form of a raw palette identifier matches any entry in the
   * reconciled unsafe set.
   *
   * @param rawPaletteId raw block palette identifier
   * @param reconciledUnsafe set of reconciled unsafe block identifiers
   * @return {@code true} if matches, {@code false} otherwise
   */
  default boolean matchesPaletteIdentifier(String rawPaletteId, Set<String> reconciledUnsafe) {
    if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return false;
    String n = reconcilePaletteIdentifier(rawPaletteId);
    return n != null && !n.isEmpty() && reconciledUnsafe.contains(n);
  }

  /**
   * Wraps a platform-specific player object into a platform-neutral effect player handle (ADR-116
   * Decision 2).
   *
   * @param player platform player object or handle
   * @return wrapped player handle, or {@code null} if unsupported or unresolvable
   */
  default Object wrapEffectPlayer(Object player) {
    return null;
  }

  /**
   * Wraps a platform-specific location or entity object into a platform-neutral effect location
   * handle (ADR-116 Decision 2).
   *
   * @param location platform location, entity, or handle
   * @return wrapped location handle, or {@code null} if unsupported or unresolvable
   */
  default Object wrapEffectLocation(Object location) {
    return null;
  }

  /**
   * Resolves the online player standing at the given effect location handle, if any (ADR-116
   * Decision 2).
   *
   * @param location effect location handle or platform location
   * @return wrapped player handle at the location, or {@code null}
   */
  default Object resolveEffectPlayerAt(Object location) {
    return null;
  }

  /**
   * Dispatches a console command on the server runtime (ADR-116 Decision 2).
   *
   * @param command command string to execute
   */
  default void dispatchConsoleCommand(String command) {
    if (command != null && !command.isEmpty()) {
      executeCommand(null, command);
    }
  }
}
