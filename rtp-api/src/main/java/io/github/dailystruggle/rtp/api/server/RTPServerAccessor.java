package io.github.dailystruggle.rtp.api.server;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
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
import java.util.logging.Level;

/**
 * Primary bridge between the platform-agnostic RTP core and the concrete server
 * implementation (Spigot, Paper, Folia).
 *
 * <p>A single instance is stored in {@link io.github.dailystruggle.rtp.api.RTPAPI#serverAccessor}
 * and is set during plugin startup. The interface is the only point at which
 * platform-specific APIs (Bukkit scheduler, Paper async chunk loading, Folia region
 * threading) are touched, keeping all other modules platform-agnostic
 * (REQ-API-F-004, REQ-API-NF-002).
 *
 * <p><b>Active-task registry:</b> {@link #activeTasks} is a shared, thread-safe map
 * of currently in-flight {@link TrackedRTPTask}s. Use {@link #registerAction(TrackedRTPTask)}
 * and {@link #removeAction(String)} to manage entries; use {@link #getTaskSnapshot()}
 * to read a consistent snapshot for display (e.g. {@code /rtp info}).
 *
 * <p><b>Thread safety:</b> All methods are safe to call from any thread unless
 * otherwise noted in the method Javadoc.
 */
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
   * Returns a consistent snapshot of all currently active tasks, mapping each
   * tracking ID to the number of milliseconds elapsed since the task was queued.
   *
   * <p>Intended for display only (e.g. {@code /rtp info}). The snapshot is taken
   * atomically over the map but individual age values may be slightly stale.
   *
   * @return a new map of {@code trackingId -> ageMillis}; never {@code null}
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
   * Returns the server's major Minecraft data version as an integer
   * (e.g. {@code 1_21_R1} maps to the NMS version integer).
   *
   * @return the integer NMS version; {@code null} if it cannot be determined
   */
  Integer getServerIntVersion();

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
   * Returns the number of milliseconds the current server tick has exceeded its
   * 50 ms budget (i.e. how late the tick is).
   *
   * <p>Used by the scheduling subsystem to defer expensive work when the server
   * is already overloaded, preventing additional tick stalls.
   *
   * @return over-time in milliseconds; {@code 0} if the tick is on time
   */
  long overTime();

  /**
   * Returns the plugin's data folder (e.g. {@code plugins/RTP/}).
   *
   * @return the plugin data directory; never {@code null}
   */
  File getPluginDirectory();

  /**
   * Sends the configured message for {@code msgType} to the player identified by
   * {@code target}, optionally tagging the message with {@code tag} for
   * placeholder resolution.
   *
   * @param target  UUID of the recipient player; must not be {@code null}
   * @param msgType the message key; must not be {@code null}
   * @param tag     an optional context tag passed to placeholder handlers;
   *                {@code null} means no tag
   */
  void sendMessage(UUID target, MessagesKeys msgType, String tag);

  default void sendMessage(UUID target, MessagesKeys msgType) {
    sendMessage(target, msgType, null);
  }

  /**
   * Sends the configured message for {@code msgType} to two recipients, typically
   * the teleport initiator and the teleported player when they differ.
   *
   * @param target1 UUID of the first recipient; must not be {@code null}
   * @param target2 UUID of the second recipient; must not be {@code null}
   * @param msgType the message key; must not be {@code null}
   * @param tag     optional context tag; {@code null} means no tag
   */
  void sendMessage(UUID target1, UUID target2, MessagesKeys msgType, String tag);

  default void sendMessage(UUID target1, UUID target2, MessagesKeys msgType) {
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
   * Sends a message from one player's perspective to another, allowing
   * placeholder resolution relative to both the sender and the target.
   *
   * @param sender  UUID of the sender (used for placeholder context); must not be {@code null}
   * @param target  UUID of the display recipient; must not be {@code null}
   * @param message the fully-resolved message text; must not be {@code null}
   * @param tag     optional context tag; {@code null} means no tag
   */
  void sendMessage(UUID sender, UUID target, String message, String tag);

  default void sendMessage(UUID sender, UUID target, String message) {
    sendMessage(sender, target, message, null);
  }

  /**
   * Sends a rich-text message to a command sender with optional hover and click
   * components (e.g. for the {@code /rtp info} region list).
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
   * Applies colour codes and registered placeholder replacements to {@code text}
   * in the context of the given player.
   *
   * @param player the player whose context is used for placeholder resolution;
   *               {@code null} for console/server context
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
   * Returns an immutable snapshot of the platform's live block-tag registry,
   * keyed by the lowercase {@code namespace:path} tag identifier (no leading
   * {@code #}), matching the parsed form of a {@code SafetyToken} with
   * {@code Kind.TAG}. Values are immutable {@link Set}s of upper-case material
   * names in the platform's canonical format (e.g. {@code "OAK_LEAVES"}).
   *
   * <p>The snapshot is consumed by the ADR-017 safety-token compiler
   * ({@code SafetyCompilationCache}) to expand tag tokens into their constituent
   * material names at compile time. Implementations shall return a <b>stable
   * reference</b> between reloads — i.e. the map is rebuilt only when
   * {@link #rebuildBlockTagSnapshot()} is invoked — so that downstream caches
   * may key on {@link System#identityHashCode(Object)} to detect invalidation.
   *
   * <p>The default implementation returns {@link java.util.Collections#emptyMap()},
   * which is the correct behaviour for platforms without a tag registry and for
   * test accessors that do not need to exercise the tag path. Bukkit-family
   * platforms override this to expose {@code Bukkit.getTags(Tag.REGISTRY_BLOCKS, …)}.
   * Fabric and other non-Bukkit platforms may delegate to the standalone
   * {@code rtp-tags} disk resolver.
   *
   * <p><b>Thread safety:</b> the returned map and all value sets shall be
   * immutable. Callers shall treat them as read-only and shall not rely on
   * reference equality across rebuilds.
   *
   * @return immutable {@code tagToken -> materialNames} map; never {@code null}.
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
}
