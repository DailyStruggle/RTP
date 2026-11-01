package io.github.dailystruggle.server.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Platform-neutral context for a player leaving a bed (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class PlayerBedLeaveContext {
  private final UUID playerId;
  private final String playerName;
  private final String worldName;
  private final int bedX;
  private final int bedY;
  private final int bedZ;
  private final long worldTimeTicks;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral bed-leave event context.
   *
   * @param playerId player UUID; must not be {@code null}
   * @param playerName player account name
   * @param worldName canonical world name; must not be {@code null}
   * @param bedX bed block X coordinate
   * @param bedY bed block Y coordinate
   * @param bedZ bed block Z coordinate
   * @param worldTimeTicks world time of day in ticks when leaving the bed
   */
  public PlayerBedLeaveContext(
      UUID playerId,
      String playerName,
      String worldName,
      int bedX,
      int bedY,
      int bedZ,
      long worldTimeTicks) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.playerName = playerName == null ? "" : playerName;
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.bedX = bedX;
    this.bedY = bedY;
    this.bedZ = bedZ;
    this.worldTimeTicks = worldTimeTicks;
  }

  /**
   * Convenience constructor with default world time {@code 0L}.
   *
   * @param playerId player UUID; must not be {@code null}
   * @param worldName canonical world name; must not be {@code null}
   * @param bedX bed block X coordinate
   * @param bedY bed block Y coordinate
   * @param bedZ bed block Z coordinate
   */
  public PlayerBedLeaveContext(UUID playerId, String worldName, int bedX, int bedY, int bedZ) {
    this(playerId, "", worldName, bedX, bedY, bedZ, 0L);
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

  public long worldTimeTicks() {
    return worldTimeTicks;
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
