package io.github.dailystruggle.server.api;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Platform-neutral context for a player attempting to enter a bed (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class PlayerBedEnterContext {
  private final UUID playerId;
  private final String playerName;
  private final String worldName;
  private final int bedX;
  private final int bedY;
  private final int bedZ;
  private final String bedEnterResult;
  private final Predicate<String> permissionPredicate;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral bed-enter event context.
   *
   * @param playerId sleeping player's UUID; must not be {@code null}
   * @param playerName sleeping player's display/account name
   * @param worldName canonical world name; must not be {@code null}
   * @param bedX bed block X coordinate
   * @param bedY bed block Y coordinate
   * @param bedZ bed block Z coordinate
   * @param bedEnterResult platform outcome token (e.g. {@code "OK"}, {@code "NOT_POSSIBLE_NOW"})
   * @param permissionPredicate evaluates whether the player holds a permission node
   */
  public PlayerBedEnterContext(
      UUID playerId,
      String playerName,
      String worldName,
      int bedX,
      int bedY,
      int bedZ,
      String bedEnterResult,
      Predicate<String> permissionPredicate) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.playerName = playerName == null ? "" : playerName;
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.bedX = bedX;
    this.bedY = bedY;
    this.bedZ = bedZ;
    this.bedEnterResult =
        bedEnterResult == null || bedEnterResult.isBlank() ? "OK" : bedEnterResult;
    this.permissionPredicate = permissionPredicate != null ? permissionPredicate : perm -> true;
  }

  /**
   * Convenience constructor for a successful bed-enter event.
   *
   * @param playerId sleeping player's UUID; must not be {@code null}
   * @param worldName canonical world name; must not be {@code null}
   * @param bedX bed block X coordinate
   * @param bedY bed block Y coordinate
   * @param bedZ bed block Z coordinate
   */
  public PlayerBedEnterContext(UUID playerId, String worldName, int bedX, int bedY, int bedZ) {
    this(playerId, "", worldName, bedX, bedY, bedZ, "OK", perm -> true);
  }

  public UUID playerId() {
    return playerId;
  }

  public String playerName() {
    return playerName;
  }

  public String worldName() {
    return worldName;
  }

  public int bedX() {
    return bedX;
  }

  public int bedY() {
    return bedY;
  }

  public int bedZ() {
    return bedZ;
  }

  public String bedEnterResult() {
    return bedEnterResult;
  }

  /**
   * Returns whether the vanilla bed-enter check succeeded ({@code "OK"}).
   *
   * @return {@code true} if the player is permitted to enter the bed
   */
  public boolean isOk() {
    return "OK".equalsIgnoreCase(bedEnterResult);
  }

  public boolean hasPermission(String permission) {
    if (permission == null || permission.isEmpty()) {
      return true;
    }
    return permissionPredicate.test(permission);
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
