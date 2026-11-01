package io.github.dailystruggle.server.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable platform-neutral snapshot of an online player's sleep, rest, and eligibility state in a
 * world (ADR-049, ADR-115).
 *
 * @param playerId player UUID; never {@code null}
 * @param playerName player username; never {@code null}
 * @param sleeping {@code true} if currently lying in a bed
 * @param sleepTicks ticks spent in bed during the current sleep session
 * @param sleepIgnored {@code true} if excluded from sleep quorum by server or plugin state
 * @param gameMode canonical uppercase game mode name (e.g. {@code "SURVIVAL"}, {@code "CREATIVE"})
 * @param vanishedOrAfk {@code true} if vanished or flagged AFK by metadata/permissions
 * @param timeSinceRestTicks ticks since last rest ({@code TIME_SINCE_REST} statistic)
 * @param permissions granted permission nodes relevant to sleep coordination; never {@code null}
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public record PlayerSleepSnapshot(
    UUID playerId,
    String playerName,
    boolean sleeping,
    int sleepTicks,
    boolean sleepIgnored,
    String gameMode,
    boolean vanishedOrAfk,
    int timeSinceRestTicks,
    Set<String> permissions) {

  /** Compact constructor normalizing nullable strings and defensively copying permissions. */
  public PlayerSleepSnapshot {
    Objects.requireNonNull(playerId, "playerId");
    playerName = playerName == null ? "" : playerName;
    gameMode = gameMode == null || gameMode.isBlank() ? "SURVIVAL" : gameMode;
    permissions =
        permissions == null || permissions.isEmpty() ? Set.of() : Set.copyOf(permissions);
  }

  /**
   * Returns whether this snapshot grants {@code permission} (or wildcard {@code "*"}).
   *
   * @param permission permission node to check
   * @return {@code true} if granted
   */
  public boolean hasPermission(String permission) {
    if (permission == null || permission.isEmpty()) {
      return true;
    }
    return permissions.contains(permission) || permissions.contains("*");
  }
}
