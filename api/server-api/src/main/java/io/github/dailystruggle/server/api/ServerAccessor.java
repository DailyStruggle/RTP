package io.github.dailystruggle.server.api;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform-neutral information, logging, and capability surface supplied by the running server.
 *
 * <p>Core server identity, version compatibility, logging, scheduling, tick health, lifecycle
 * hooks, and progress-bar control live directly on this interface, while domain-specific host
 * capabilities are composed through focused sub-interfaces ({@link ScoreboardAccessor}, {@link
 * WorldBorderAccessor}, {@link PaletteTagAccessor}, {@link EffectHandleBridge}, {@link
 * MenuContextAccessor}, {@link CommandDispatchAccessor}, and {@link SleepWorldAccessor}) with safe
 * default implementations.
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
        CommandDispatchAccessor,
        SleepWorldAccessor {
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

  /**
   * Returns the platform {@link WorldEventHook} for subscribing to player block-interaction, block
   * break, leaf decay, chunk load, and sapling growth events (ADR-049, ADR-115).
   *
   * @return the platform world event hook; never {@code null}
   */
  default WorldEventHook getWorldEventHook() {
    return NoopWorldEventHook.INSTANCE;
  }

  /**
   * Delivers a custom-tagged item stack specified by {@link TaggedItemSpec} to {@code playerName}
   * (or {@code fallbackPlayerId} when {@code playerName} is omitted).
   *
   * @param fallbackPlayerId fallback online player UUID when {@code playerName} is blank
   * @param playerName online player name, or {@code null}
   * @param item item specification; must not be {@code null}
   * @return {@code true} if the player was online and received the item
   */
  default boolean giveItem(UUID fallbackPlayerId, String playerName, TaggedItemSpec item) {
    return false;
  }

  /**
   * Drops a custom-tagged item stack specified by {@link TaggedItemSpec} naturally at {@code (x, y,
   * z)} in {@code worldName}.
   *
   * @param worldName canonical world name
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @param item item specification; must not be {@code null}
   */
  default void dropItem(String worldName, int x, int y, int z, TaggedItemSpec item) {}

  /**
   * Returns the root configuration or plugin data folder as a {@link Path}.
   *
   * @return root folder path; never {@code null}
   */
  default Path getRootFolder() {
    return getPluginDirectory().toPath();
  }

  /**
   * Resolves the filesystem directory for the given world or level name (for example, to write
   * worldgen datapacks), or {@code null} if unknown.
   *
   * @param worldName canonical world name
   * @return world directory path, or {@code null}
   */
  default Path getWorldFolder(String worldName) {
    return null;
  }

  /**
   * Returns the names of currently loaded worlds on this server.
   *
   * @return immutable list of world names; never {@code null}
   */
  default List<String> getWorldNames() {
    return Collections.emptyList();
  }

  /**
   * Schedules a repeating task on the server or global region scheduler.
   *
   * @param task runnable to execute
   * @param delayTicks initial delay in ticks
   * @param periodTicks period between executions in ticks
   */
  default void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
    scheduler().ifPresent(s -> s.runSyncTimer(task, delayTicks, periodTicks));
  }

  /**
   * Schedules a one-shot delayed task anchored to the region owning {@code (chunkX, chunkZ)} in
   * {@code worldName}.
   *
   * @param worldName canonical world name
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param task runnable to execute
   * @param delayTicks delay in ticks ({@code <= 0} executes as soon as the region thread is owned)
   */
  default void scheduleDelayedTask(
      String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
    if (delayTicks <= 0L) {
      task.run();
    } else {
      scheduler().ifPresent(s -> s.runSyncDelayed(task, delayTicks));
    }
  }

  /**
   * Resolves the canonical biome identifier (e.g. {@code "minecraft:plains"}) at {@code (x, y, z)}
   * in {@code worldName}.
   *
   * @param worldName canonical world name
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @return biome key, or empty string if unavailable
   */
  default String getBiomeKey(String worldName, int x, int y, int z) {
    return "";
  }

  /**
   * Places a structure template identified by {@code structureKey} centered on {@code (x, y, z)} in
   * {@code worldName}.
   *
   * @param worldName canonical world name
   * @param x center X coordinate
   * @param y base Y coordinate
   * @param z center Z coordinate
   * @param structureKey namespaced structure identifier
   * @return {@code true} if the structure template was found and placed
   */
  default boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
    return false;
  }

  /**
   * Sets the block state at {@code (x, y, z)} in {@code worldName} without triggering physics
   * cascades.
   *
   * @param worldName canonical world name
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @param blockData serialized block state or material string
   * @return {@code true} if the block was updated
   */
  default boolean setBlock(String worldName, int x, int y, int z, String blockData) {
    return false;
  }

  /**
   * Returns the current block state or material identifier at {@code (x, y, z)} in {@code
   * worldName}, or {@code null} if the chunk is not loaded or not owned by the current region
   * thread (S-005).
   *
   * @param worldName canonical world name
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @return block identifier or {@code null}
   */
  default String getBlockState(String worldName, int x, int y, int z) {
    return null;
  }

  /**
   * Returns whether the block at {@code (x, y, z)} in {@code worldName} is air or a replaceable
   * plant/snow/leaf/sapling block.
   *
   * @param worldName canonical world name
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @return {@code true} if replaceable
   */
  default boolean isReplaceable(String worldName, int x, int y, int z) {
    return true;
  }

  /**
   * Returns the highest non-air block Y coordinate at {@code (x, z)} in {@code worldName}, or
   * {@link Integer#MIN_VALUE} when unknown.
   *
   * @param worldName canonical world name
   * @param x block X coordinate
   * @param z block Z coordinate
   * @return highest block Y or {@link Integer#MIN_VALUE}
   */
  default int getHighestBlockY(String worldName, int x, int z) {
    return Integer.MIN_VALUE;
  }

  /**
   * Returns the minimum build height for {@code worldName}.
   *
   * @param worldName canonical world name
   * @return minimum build height (defaults to {@code -64})
   */
  default int getMinHeight(String worldName) {
    return -64;
  }

  /**
   * Returns whether chunk {@code (chunkX, chunkZ)} in {@code worldName} is currently loaded in
   * memory without triggering synchronous chunk generation or I/O (S-005).
   *
   * @param worldName canonical world name
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @return {@code true} if the chunk is currently loaded
   */
  default boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
    return true;
  }

  /**
   * Reads a named persistent per-chunk string value for {@code (chunkX, chunkZ)} in {@code
   * worldName}, or {@code null} when none is stored or the chunk is unloaded (S-005).
   *
   * @param worldName canonical world name
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param key metadata key
   * @return stored string value or {@code null}
   */
  default String getChunkData(String worldName, int chunkX, int chunkZ, String key) {
    return null;
  }

  /**
   * Writes (or clears when {@code data} is {@code null}/empty) a named persistent per-chunk string
   * value for {@code (chunkX, chunkZ)} in {@code worldName}.
   *
   * @param worldName canonical world name
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param key metadata key
   * @param data string payload, or {@code null}/empty to remove
   */
  default void setChunkData(String worldName, int chunkX, int chunkZ, String key, String data) {}

  /**
   * Atomically reads, transforms, and writes a named persistent per-chunk string value on the
   * owning region thread without loading unloaded chunks (S-005).
   *
   * @param worldName canonical world name
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param key metadata key
   * @param updater transformation function
   */
  default void updateChunkData(
      String worldName, int chunkX, int chunkZ, String key, UnaryOperator<String> updater) {
    String updated = updater.apply(getChunkData(worldName, chunkX, chunkZ, key));
    setChunkData(worldName, chunkX, chunkZ, key, updated);
  }

  /**
   * Sends a plain or status message to the given player UUID (or console when {@code playerId} is
   * {@code null} or the server console UUID).
   *
   * @param playerId recipient player UUID or console sentinel
   * @param message message text
   * @param error {@code true} if the message represents an error condition
   */
  default void sendMessage(UUID playerId, String message, boolean error) {}

  /**
   * Returns the running server's Minecraft DataVersion used when serializing structure NBT.
   *
   * @return Minecraft DataVersion integer
   */
  default int getDataVersion() {
    return 3578;
  }

  /**
   * Returns a JUL {@link Logger} for this server accessor.
   *
   * @return logger instance; never {@code null}
   */
  default Logger logger() {
    return Logger.getLogger("ServerAccessor");
  }

  /**
   * Sets a batch of block states inside a single chunk {@code (chunkX, chunkZ)} in {@code
   * worldName} on its owning thread.
   *
   * @param worldName canonical world name
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param blocks map from block coordinates to serialized block state strings
   * @return {@code true} if the batch was applied or scheduled
   */
  default boolean setChunkBlocks(
      String worldName, int chunkX, int chunkZ, Map<BlockPos3i, String> blocks) {
    if (blocks == null || blocks.isEmpty()) return true;
    boolean ok = true;
    for (Map.Entry<BlockPos3i, String> entry : blocks.entrySet()) {
      BlockPos3i pos = entry.getKey();
      ok &= setBlock(worldName, pos.x(), pos.y(), pos.z(), entry.getValue());
    }
    return ok;
  }

  /**
   * Resolves a command caller's live target or block coordinates for command defaults.
   *
   * @param callerId command caller UUID
   * @return caller location snapshot, or {@code null} if unavailable
   */
  default CallerLocation resolveCallerLocation(UUID callerId) {
    return null;
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
