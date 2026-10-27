package io.github.dailystruggle.server.api;

import java.util.UUID;

/**
 * Platform-neutral per-player clientbound world border and damage access contract.
 *
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public interface WorldBorderAccessor {
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
}
