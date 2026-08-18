package io.github.dailystruggle.rtp.api.server;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.BiomeSampleCapability;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.io.File;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Primary bridge between platform-neutral RTP core and concrete server runtimes.
 *
 * <p>Single instance stored in {@link io.github.dailystruggle.rtp.api.RTPAPI#serverAccessor}.
 * Only layer allowed to touch platform-specific APIs (REQ-API-F-004, REQ-API-NF-002).
 *
 * <p>{@link #activeTasks} tracks active {@link TrackedRTPTask} instances thread-safely.
 */
@PublicApi
public interface RTPServerAccessor {
  ConcurrentHashMap<String, TrackedRTPTask> activeTasks = new ConcurrentHashMap<>();

  /**
   * Registers a task in the active-task map under its {@link TrackedRTPTask#getTrackingId()}.
   *
   * @param task the task to register; must not be {@code null}
   */
  default void registerAction(TrackedRTPTask task) {
    activeTasks.put(task.getTrackingId(), task);
  }

  /**
   * Removes the task with the given tracking ID from the active-task map.
   * Called automatically by {@link TrackedRTPTask#run()} in its {@code finally} block.
   *
   * @param trackingId the key to remove; no-op if not present
   */
  default void removeAction(String trackingId) {
    activeTasks.remove(trackingId);
  }

  /**
   * Returns a snapshot of active tasks mapping tracking ID to elapsed queue age in ms.
   *
   * @return snapshot map of {@code trackingId -> ageMillis}; never {@code null}
   */
  default Map<String, Long> getTaskSnapshot() {
    ConcurrentHashMap<String, Long> snapshot = new ConcurrentHashMap<>();
    activeTasks.forEach(
        (s, trackedRTPTask) ->
            snapshot.put(s, System.currentTimeMillis() - trackedRTPTask.getQueuedTime()));
    return snapshot;
  }

  /**
   * Returns the platform-reported server version string (e.g. {@code "git-Paper-400 (MC: 1.21.1)"}).
   *
   * @return server version string; never {@code null}
   */
  String getServerVersion();

  /**
   * Returns the RTP plugin version as declared in {@code plugin.yml}.
   *
   * @return plugin version string; never {@code null}
   */
  String getPluginVersion();

  /**
   * Returns a short identifier for the server platform (e.g. {@code "paper"}, {@code "folia"}).
   *
   * @return platform identifier; never {@code null}
   */
  String getPlatform();

  /**
   * Returns the coarse {@link PlatformFamily} this server belongs to.
   *
   * <p>Prefer over string-matching {@link #getPlatform()} for runtime gating.
   * Default maps canonical platform strings; unrecognised platforms return UNKNOWN.
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
        "[RTP] Unrecognised server platform '"
            + platform
            + "'; classifying as PlatformFamily.UNKNOWN. Platform-specific addon behaviour will be skipped.");
    return PlatformFamily.UNKNOWN;
  }

  /**
   * Returns the headline Minecraft version integer (e.g. 21 for 1.21.x, 26 for 26.x).
   *
   * <p>Opaque monotonically increasing integer used for version range comparisons.
   *
   * @return the integer server version; {@code null} if undetermined
   */
  Integer getServerIntVersion();

  /**
   * Tests whether this server belongs to the given {@link PlatformFamily}.
   *
   * @param family the family to test against; {@code null} returns {@code false}
   * @return {@code true} if server matches {@code family}
   */
  default boolean isPlatformFamily(PlatformFamily family) {
    return family != null && getPlatformFamily() == family;
  }

  /**
   * Tests whether the integer Minecraft version is at least {@code minServerVersion}.
   *
   * <p>Returns {@code false} if version is unknown (fails closed).
   *
   * @param minServerVersion inclusive minimum version
   * @return {@code true} if server version is known and {@code >= minServerVersion}
   */
  default boolean isServerVersionAtLeast(int minServerVersion) {
    Integer v = getServerIntVersion();
    return v != null && v >= minServerVersion;
  }

  /**
   * Tests whether the integer Minecraft version is at most {@code maxServerVersion}.
   *
   * <p>Returns {@code false} if version is unknown (fails closed).
   *
   * @param maxServerVersion inclusive maximum version
   * @return {@code true} if server version is known and {@code <= maxServerVersion}
   */
  default boolean isServerVersionAtMost(int maxServerVersion) {
    Integer v = getServerIntVersion();
    return v != null && v <= maxServerVersion;
  }

  /**
   * Platform-and-version compatibility gate for addons.
   *
   * <p>Returns {@code true} if family matches and version falls in bounds.
   *
   * @param requiredFamily   required family, or {@code null} for any
   * @param minServerVersion inclusive minimum ({@code 0} for no floor)
   * @param maxServerVersion inclusive maximum ({@link Integer#MAX_VALUE} for no ceiling)
   * @return {@code true} if runtime satisfies constraints
   */
  default boolean isCompatible(PlatformFamily requiredFamily, int minServerVersion, int maxServerVersion) {
    if (requiredFamily != null && getPlatformFamily() != requiredFamily) {
      return false;
    }
    boolean versionBounded = minServerVersion > 0 || maxServerVersion < Integer.MAX_VALUE;
    if (versionBounded) {
      Integer v = getServerIntVersion();
      if (v == null || v < minServerVersion || v > maxServerVersion) {
        return false;
      }
    }
    return true;
  }

  /**
   * Looks up a world by its canonical name.
   *
   * @param name the world name; must not be {@code null}
   * @return the corresponding {@link RTPWorld}, or {@code null} if no such world is loaded
   */
  RTPWorld<?> getRTPWorld(String name);

  /**
   * Looks up a world by its unique ID.
   *
   * @param id the world UUID; must not be {@code null}
   * @return the corresponding {@link RTPWorld}, or {@code null} if no such world is loaded
   */
  RTPWorld<?> getRTPWorld(UUID id);

  /**
   * Returns an unmodifiable snapshot of all currently loaded worlds.
   *
   * @return list of loaded worlds; never {@code null}, may be empty
   */
  List<RTPWorld<?>> getRTPWorlds();

  /**
   * Returns the online player with the given UUID, or {@code null} if the player
   * is not currently online.
   *
   * @param uuid the player UUID; must not be {@code null}
   * @return the online player, or {@code null}
   */
  RTPPlayer getPlayer(UUID uuid);

  /**
   * Returns the online player with the given name, or {@code null} if no online
   * player has that name (case-insensitive match depends on platform).
   *
   * @param name the player name; must not be {@code null}
   * @return the online player, or {@code null}
   */
  RTPPlayer getPlayer(String name);

  /**
   * Returns names of online players for tab completion without platform coupling.
   *
   * @return immutable snapshot of online player names; never {@code null}
   */
  default Set<String> getOnlinePlayerNames() {
    return java.util.Collections.emptySet();
  }

  /**
   * Returns a synthetic {@link RTPPlayer} representing the server console, used
   * when an admin command targets the console as the teleport destination.
   *
   * @return the console player wrapper, or {@code null} if not supported by the platform
   */
  @Nullable
  RTPPlayer getConsolePlayer();

  /**
   * Returns the {@link RTPCommandSender} for the given UUID. Unlike
   * {@link #getPlayer(UUID)}, this may also return the console sender when
   * {@code uuid} equals the console's pseudo-UUID.
   *
   * @param uuid the sender UUID; must not be {@code null}
   * @return the command sender, or {@code null} if not found
   */
  RTPCommandSender getSender(UUID uuid);

  /**
   * Returns the platform {@link PlayerLifecycleHook} (ADR-049).
   *
   * @return the platform lifecycle hook; never {@code null}
   */
  default PlayerLifecycleHook getPlayerLifecycleHook() {
    return NoopPlayerLifecycleHook.INSTANCE;
  }

  /**
   * Returns milliseconds current tick has exceeded its 50ms budget.
   *
   * @return over-time in ms; 0 if on time
   */
  long overTime();

  /**
   * Returns the plugin's data folder (e.g. {@code plugins/RTP/}).
   *
   * @return the plugin data directory; never {@code null}
   */
  File getPluginDirectory();

  /**
   * Sends configured message to a player with optional context tag.
   *
   * @param target  UUID of recipient player; must not be {@code null}
   * @param msgType message key; must not be {@code null}
   * @param tag     optional placeholder context tag
   */
  void sendMessage(UUID target, Enum<?> msgType, String tag);

  default void sendMessage(UUID target, Enum<?> msgType) {
    sendMessage(target, msgType, null);
  }

  /**
   * Sends configured message to two recipients (e.g. sender and target).
   *
   * @param target1 UUID of first recipient; must not be {@code null}
   * @param target2 UUID of second recipient; must not be {@code null}
   * @param msgType message key; must not be {@code null}
   * @param tag     optional placeholder context tag
   */
  void sendMessage(UUID target1, UUID target2, Enum<?> msgType, String tag);

  default void sendMessage(UUID target1, UUID target2, Enum<?> msgType) {
    sendMessage(target1, target2, msgType, null);
  }

  /**
   * Sends a raw (already-resolved) message string to a player.
   *
   * @param target  UUID of the recipient; must not be {@code null}
   * @param message the fully-resolved message text; must not be {@code null}
   * @param tag     optional context tag; {@code null} means no tag
   */
  void sendMessage(UUID target, String message, String tag);

  default void sendMessage(UUID target, String message) {
    sendMessage(target, message, null);
  }

  /**
   * Sends a clickable message to a player with a command suggestion pre-filled
   * in the chat bar when the message is clicked.
   *
   * @param target     UUID of the recipient; must not be {@code null}
   * @param message    the display text; must not be {@code null}
   * @param suggestion the command text to suggest on click; must not be {@code null}
   */
  void sendMessageAndSuggest(UUID target, String message, String suggestion);

  /**
   * Sends message from sender perspective to target for relative placeholder context.
   *
   * @param sender  UUID of sender for placeholder context; must not be {@code null}
   * @param target  UUID of display recipient; must not be {@code null}
   * @param message resolved message text; must not be {@code null}
   * @param tag     optional context tag
   */
  void sendMessage(UUID sender, UUID target, String message, String tag);

  default void sendMessage(UUID sender, UUID target, String message) {
    sendMessage(sender, target, message, null);
  }

  /**
   * Sends rich-text message with optional hover and click actions.
   *
   * @param target  the recipient; must not be {@code null}
   * @param message the display text; must not be {@code null}
   * @param hover   text shown on hover; {@code null} disables hover
   * @param click   command run on click; {@code null} disables click action
   * @param tag     optional context tag; {@code null} means no tag
   */
  void sendMessage(RTPCommandSender target, String message, String hover, String click, String tag);

  default void sendMessage(RTPCommandSender target, String message, String hover, String click) {
    sendMessage(target, message, hover, click, null);
  }

  /**
   * Sends rich-text message with auto-dispatching click action (ADR-050).
   *
   * @param target     the recipient; must not be {@code null}
   * @param message    the display text; must not be {@code null}
   * @param hover      text shown on hover; {@code null} disables hover
   * @param runCommand command auto-dispatched on click; {@code null} disables click
   * @param tag        optional context tag; {@code null} means no tag
   */
  default void sendMessageWithRunCommand(
      RTPCommandSender target, String message, String hover, String runCommand, String tag) {
    sendMessage(target, message, hover, runCommand, tag);
  }

  default void sendMessageWithRunCommand(
      RTPCommandSender target, String message, String hover, String runCommand) {
    sendMessageWithRunCommand(target, message, hover, runCommand, null);
  }

  /**
   * Applies colour codes and placeholders in player context.
   *
   * @param player player UUID for placeholders or {@code null} for console
   * @param text   the raw text to format; must not be {@code null}
   * @return the formatted string; never {@code null}
   */
  String format(@Nullable UUID player, String text);

  /**
   * Applies placeholder replacements to {@code text} without translating colour
   * codes. Useful when the output will be logged or compared programmatically.
   *
   * @param player the player context; {@code null} for console/server context
   * @param text   the raw text; must not be {@code null}
   * @return the placeholder-substituted string without colour codes; never {@code null}
   */
  String formatNoColor(@Nullable UUID player, String text);

  /**
   * Writes a message to the server log at the given level.
   *
   * @param level the log level; must not be {@code null}
   * @param msg   the message; must not be {@code null}
   */
  void log(Level level, String msg);

  /**
   * Writes a message and associated throwable to the server log at the given level.
   *
   * @param level     the log level; must not be {@code null}
   * @param msg       the message; must not be {@code null}
   * @param throwable the exception to log; must not be {@code null}
   */
  void log(Level level, String msg, Throwable throwable);

  /**
   * Broadcasts {@code msg} to all online players who hold {@code permission}.
   *
   * @param msg        the message to broadcast; must not be {@code null}
   * @param permission the permission node required to receive the message;
   *                   must not be {@code null}
   * @param tag        optional context tag; {@code null} means no tag
   */
  void announce(String msg, String permission, String tag);

  default void announce(String msg, String permission) {
    announce(msg, permission, null);
  }

  /**
   * Returns the set of biome names present in the given world.
   *
   * @param rtpWorld the world to query; must not be {@code null}
   * @return set of canonical biome name strings; never {@code null}
   */
  Set<String> getBiomes(RTPWorld<?> rtpWorld);
  /**
   * Returns the union of all biome names across all loaded worlds.
   *
   * @return set of all known biome name strings; never {@code null}
   */
  Set<String> getBiomes();

  /**
   * Classifies coordinate biome sampling cost for the world (ADR-062).
   *
   * <p>Default returns GENERATE_REQUIRED.
   *
   * @param rtpWorld world to classify; never {@code null}
   * @return sampling capability; never {@code null}
   */
  default BiomeSampleCapability biomeSampleCapability(RTPWorld<?> rtpWorld) {
    return BiomeSampleCapability.GENERATE_REQUIRED;
  }

  /**
   * Best-effort non-blocking biome sampling at coordinates (ADR-062).
   *
   * <p>Returns null if cheap sampling is unavailable without chunk I/O (S-005).
   *
   * @param rtpWorld world to sample; never {@code null}
   * @param x block x
   * @param y block y
   * @param z block z
   * @return uppercase biome name or {@code null}
   */
  default @Nullable String sampleBiome(RTPWorld<?> rtpWorld, int x, int y, int z) {
    return null;
  }

  /**
   * Returns whether the calling thread is the main server tick thread.
   *
   * <p>Used by the scheduling subsystem to decide whether a task can access
   * world state directly or must be dispatched via the scheduler.
   *
   * @return {@code true} if called from the primary server thread
   */
  boolean isPrimaryThread();

  /**
   * Returns the set of all material names known to the server
   * (e.g. for safety-check configuration validation).
   *
   * @return set of material name strings in the platform's canonical format;
   *         never {@code null}
   */
  Set<String> materials();

  /**
   * Returns immutable snapshot of live block tags for safety parsing (ADR-017).
   *
   * <p>Keyed by lowercase namespace:path. Values are sets of uppercase material names.
   * Reference remains stable between reloads.
   *
   * @return immutable tag map; never {@code null}
   */
  default Map<String, Set<String>> blockTagSnapshot() {
    return java.util.Collections.emptyMap();
  }

  /**
   * Instructs the accessor to rebuild its block-tag snapshot on the next call
   * to {@link #blockTagSnapshot()}. Invoked by the {@code /rtp reload} handler
   * and by test fixtures that swap the underlying tag registry.
   *
   * <p>The default implementation is a no-op for platforms that do not maintain
   * a tag snapshot.
   */
  default void rebuildBlockTagSnapshot() {
    // no-op by default.
  }

  /**
   * Shuts down all RTP subsystems cleanly (called on plugin disable).
   * Releases chunk tickets, cancels pending tasks, and flushes any pending state.
   */
  void stop();

  /**
   * Starts all RTP subsystems with no platform plugin reference.
   * Prefer {@link #start(Object)} during normal plugin enable.
   */
  void start();

  /**
   * Starts all RTP subsystems, passing the platform plugin instance for
   * scheduler registration and resource access.
   *
   * @param plugin the platform plugin object (e.g. a Bukkit {@code JavaPlugin});
   *               must not be {@code null}
   */
  void start(Object plugin);

  /**
   * Injects a custom function that resolves the biome name at a given location.
   *
   * <p>Implementations should call the platform API asynchronously if possible
   * (REQ-API-ARCH-002). The default getter uses the server's built-in biome lookup.
   *
   * @param getter the biome resolver; must not be {@code null}
   */
  void setBiomeGetter(
      java.util.function.Function<io.github.dailystruggle.rtp.api.world.RTPLocation, String>
          getter);

  /**
   * Injects a custom function that returns all biome names present in a world.
   *
   * @param getter the world-to-biomes resolver; must not be {@code null}
   */
  void setBiomesGetter(
      java.util.function.Function<
              io.github.dailystruggle.rtp.api.world.RTPWorld<?>, java.util.Set<String>>
          getter);

  /**
   * Returns the platform's world-border object for the named world, or
   * {@code null} if the world has no border or is not loaded.
   *
   * @param worldName the canonical world name; must not be {@code null}
   * @return the platform world-border object, or {@code null}
   */
  Object getWorldBorder(String worldName);

  /**
   * Returns the registered shape object for the given shape name, or {@code null}
   * if no shape with that name has been registered.
   *
   * @param name the shape name (case-sensitive); must not be {@code null}
   * @return the shape object, or {@code null}
   */
  Object getShape(String name);

  /**
   * Registers a custom world-border resolver, replacing the default implementation.
   *
   * @param function a function mapping world name to a platform border object;
   *                 must not be {@code null}
   * @return {@code true} if registration succeeded
   */
  boolean setWorldBorderFunction(Function<String, ?> function);

  /**
   * Registers a custom shape resolver (REQ-API-F-001), replacing the default lookup.
   *
   * @param shapeFunction a function mapping shape name to a shape object;
   *                      must not be {@code null}
   * @return {@code true} if registration succeeded
   */
  boolean setShapeFunction(java.util.function.Function<String, ?> shapeFunction);

  /**
   * Creates and returns a new task pipeline object used to sequence async
   * teleport steps on the appropriate platform threads.
   *
   * @return a new task pipe instance; never {@code null}
   */
  Object createTaskPipe();

  /**
   * Creates and returns a new cache pipeline object used to sequence async
   * pre-generation steps.
   *
   * @return a new cache pipe instance; never {@code null}
   */
  Object createCachePipe();

  /**
   * Returns the underlying platform plugin instance (e.g. the Bukkit
   * {@code JavaPlugin}) for cases where raw platform access is unavoidable.
   *
   * @return the platform plugin object; never {@code null} after {@link #start(Object)}
   */
  Object getPlugin();

  /** Release all plugin chunk tickets (e.g. on disable). No-op on non-Paper implementations. */
  default void releaseAllChunkTickets() {}

  /**
   * Applies any platform-specific shape post-processing to the given location
   * (e.g. adjusting the Y coordinate on Paper for heightmap queries).
   * Delegates to {@link RTPWorld#platform(RTPLocation)} by default.
   *
   * @param location the location to process; must not be {@code null}
   */
  default void shapePlatform(RTPLocation location) {
    location.world().platform(location);
  }

  /**
   * Returns the platform scheduler used to dispatch sync and async tasks.
   *
   * @return the scheduler; never {@code null} after {@link #start(Object)}
   */
  io.github.dailystruggle.rtp.api.scheduling.RTPScheduler getScheduler();

  /**
   * Returns the location generator responsible for maintaining the pre-generation
   * queue and serving validated locations to the teleport pipeline.
   *
   * @return the location generator; never {@code null} after {@link #start(Object)}
   */
  ILocationGenerator getLocationGenerator();

  /**
   * Returns the server's average ticks-per-second (TPS) over the given number
   * of recent ticks.
   *
   * @param ticks the sample window in ticks (e.g. 20 for 1 second, 100 for 5 seconds)
   * @return average TPS; typically between 0 and 20
   */
  double getTPS(int ticks);

  // ---------------------------------------------------------------------------
  // Progress-bar surface (platform-neutral on-screen progress feedback)
  // ---------------------------------------------------------------------------

  /**
   * Shows or reconciles progress bars keyed by stable id.
   *
   * @param bars bar states by id; never {@code null}
   */
  default void updateProgressBars(Map<String, ProgressBar> bars) {}

  /**
   * Hides and discards every progress bar previously shown via
   * {@link #updateProgressBars(Map)}.
   */
  default void clearProgressBars() {}

  // ---------------------------------------------------------------------------
  // Menu platform surface (ADR-048)
  //
  // Default implementations are conservative; platform adapters should override
  // them. The defaults route through {@link #getSender(UUID)} so out-of-tree
  // implementations gain functional behaviour automatically once the sender is
  // wired, but they never throw and never block.
  // ---------------------------------------------------------------------------

  /**
   * Returns permission probe for menu visibility filtering (ADR-048).
   *
   * @param player player UUID
   * @return non-null permission predicate
   */
  default Predicate<String> menuPermissionProbe(UUID player) {
    return node -> {
      if (player == null || node == null) {
        return false;
      }
      try {
        RTPCommandSender sender = getSender(player);
        return sender != null && sender.hasPermission(node);
      } catch (Throwable t) {
        return false;
      }
    };
  }

  /**
   * Returns effective permissions snapshot for menu namespaces (ADR-048).
   *
   * @param player player UUID
   * @return permission set; empty if unresolved
   */
  default Set<String> menuEffectivePermissions(UUID player) {
    if (player == null) {
      return java.util.Collections.emptySet();
    }
    try {
      RTPCommandSender sender = getSender(player);
      if (sender == null) {
        return java.util.Collections.emptySet();
      }
      Set<String> perms = sender.getEffectivePermissions();
      return perms == null ? java.util.Collections.emptySet() : perms;
    } catch (Throwable t) {
      return java.util.Collections.emptySet();
    }
  }

  /**
   * Returns BCP-47 locale tag for menu rendering (ADR-048).
   *
   * @param player player UUID
   * @return locale tag; defaults to "en_us"
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
}
