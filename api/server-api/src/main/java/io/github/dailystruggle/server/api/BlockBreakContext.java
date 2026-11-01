package io.github.dailystruggle.server.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Platform-neutral context for a block break event (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class BlockBreakContext {
  private final UUID playerId;
  private final String worldName;
  private final int x;
  private final int y;
  private final int z;
  private final String blockKey;
  private final String biomeKey;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral block break context.
   *
   * @param playerId breaking player's UUID, or {@code null} if non-player
   * @param worldName canonical world name; must not be {@code null}
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @param blockKey block state or material identifier; must not be {@code null}
   * @param biomeKey biome identifier at the broken block, or empty string
   */
  public BlockBreakContext(
      UUID playerId, String worldName, int x, int y, int z, String blockKey, String biomeKey) {
    this.playerId = playerId;
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.x = x;
    this.y = y;
    this.z = z;
    this.blockKey = Objects.requireNonNull(blockKey, "blockKey");
    this.biomeKey = biomeKey == null ? "" : biomeKey;
  }

  public UUID playerId() {
    return playerId;
  }

  public String worldName() {
    return worldName;
  }

  public int x() {
    return x;
  }

  public int y() {
    return y;
  }

  public int z() {
    return z;
  }

  public String blockKey() {
    return blockKey;
  }

  public String biomeKey() {
    return biomeKey;
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
