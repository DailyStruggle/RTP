package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Platform-neutral sleep, world clock, weather, phantom-rest statistic, and plugin-messaging SPI
 * composed into {@link ServerAccessor} with safe default no-op implementations (ADR-049, ADR-115).
 *
 * <p>Implementations on regionalized servers (Folia) must dispatch world-clock and weather
 * mutations on the global region scheduler and per-player wake / statistic mutations on the owning
 * entity scheduler (Rule F-001..F-003).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public interface SleepWorldAccessor {

  /**
   * Returns the current time-of-day (0..23999 ticks) for {@code worldName}.
   *
   * @param worldName canonical world name
   * @return world time of day in ticks, or {@code 0L} if unavailable
   */
  default long getWorldTime(String worldName) {
    return 0L;
  }

  /**
   * Returns the monotonic full world time (total elapsed ticks across days) for {@code worldName}.
   *
   * @param worldName canonical world name
   * @return full world time in ticks, or {@code 0L} if unavailable
   */
  default long getWorldFullTime(String worldName) {
    return 0L;
  }

  /**
   * Sets the world time (or advances full time to match {@code timeTicks}) for {@code worldName} on
   * the global region / main server thread.
   *
   * @param worldName canonical world name
   * @param timeTicks target world time in ticks
   */
  default void setWorldTime(String worldName, long timeTicks) {}

  /**
   * Returns the current {@link WeatherState} in {@code worldName}.
   *
   * @param worldName canonical world name
   * @return current weather state; never {@code null}
   */
  default WeatherState getWeather(String worldName) {
    return WeatherState.CLEAR;
  }

  /**
   * Sets the weather state in {@code worldName} on the global region / main server thread.
   *
   * @param worldName canonical world name
   * @param state target weather state; must not be {@code null}
   * @param durationTicks weather duration in ticks ({@code <= 0} uses platform default)
   */
  default void setWeather(String worldName, WeatherState state, int durationTicks) {}

  /**
   * Captures an immutable list of {@link PlayerSleepSnapshot} records for all online players
   * currently in {@code worldName}.
   *
   * @param worldName canonical world name
   * @return immutable snapshot list; never {@code null}
   */
  default List<PlayerSleepSnapshot> getWorldPlayers(String worldName) {
    return Collections.emptyList();
  }

  /**
   * Returns the maximum player capacity configured on this server shard (e.g. {@code max-players}
   * in {@code server.properties}).
   *
   * @return configured maximum players ({@code >= 1}), defaulting to {@code 20}
   */
  default int getMaxPlayers() {
    return 20;
  }

  /**
   * Returns the server's average milliseconds-per-tick (MSPT), or {@code 0.0} when unsampled.
   *
   * @return average MSPT in milliseconds ({@code >= 0.0})
   */
  default double getMSPT() {
    return 0.0;
  }

  /**
   * Wakes the specified player if currently sleeping, dispatching on the player's owning entity
   * scheduler on regionalized servers.
   *
   * @param playerId target player UUID
   * @param setSpawn whether to update the player's respawn location to the bed
   */
  default void wakePlayer(UUID playerId, boolean setSpawn) {}

  /**
   * Resets the {@code TIME_SINCE_REST} statistic to {@code 0} for the specified player on their
   * owning entity scheduler so Phantoms do not spawn after a coordinated night skip.
   *
   * @param playerId target player UUID
   */
  default void resetTimeSinceRest(UUID playerId) {}

  /**
   * Registers an incoming/outgoing plugin messaging channel handler (e.g. {@code "leafsleep:sync"}).
   *
   * @param channel namespaced channel identifier; must not be {@code null}
   * @param receiver callback receiving {@code (carrierPlayerId, payloadBytes)}
   * @return closeable handle to unregister the channel; never {@code null}
   */
  default AutoCloseable registerMessagingChannel(
      String channel, BiConsumer<UUID, byte[]> receiver) {
    return () -> {};
  }

  /**
   * Sends a raw plugin-message payload on {@code channel} via any available player carrier
   * connection (or server-wide broadcast channel).
   *
   * @param channel namespaced channel identifier; must not be {@code null}
   * @param payload raw message bytes; must not be {@code null}
   * @return {@code true} if at least one carrier connection transmitted the payload
   */
  default boolean sendPluginMessage(String channel, byte[] payload) {
    return false;
  }

  /**
   * Sends an actionbar overlay message (rendered above the hotbar) to {@code playerId} on their
   * owning entity scheduler.
   *
   * @param playerId recipient player UUID
   * @param message legacy-formatted message text ({@code &} color codes supported)
   */
  default void sendActionBar(UUID playerId, String message) {}

  /**
   * Creates or updates a personal bossbar identified by {@code barId} for {@code playerId}.
   *
   * @param playerId recipient player UUID
   * @param barId stable bar identifier; must not be {@code null}
   * @param title bar title ({@code &} color prefix selects bar color)
   * @param progress fill fraction clamped to {@code [0.0, 1.0]}
   */
  default void showBossBar(UUID playerId, String barId, String title, double progress) {}

  /**
   * Hides and removes a personal bossbar previously shown under {@code barId} for {@code playerId}.
   *
   * @param playerId recipient player UUID
   * @param barId stable bar identifier; must not be {@code null}
   */
  default void clearBossBar(UUID playerId, String barId) {}
}
