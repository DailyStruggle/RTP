package io.github.dailystruggle.server.api;

import java.util.Objects;

/**
 * Platform-neutral context for natural leaf block decay (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class LeavesDecayContext {
  private final String worldName;
  private final int x;
  private final int y;
  private final int z;
  private final String blockKey;
  private final String biomeKey;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral leaf decay context.
   *
   * @param worldName canonical world name; must not be {@code null}
   * @param x block X coordinate
   * @param y block Y coordinate
   * @param z block Z coordinate
   * @param blockKey leaf block identifier; must not be {@code null}
   * @param biomeKey biome identifier at the decaying leaf, or empty string
   */
  public LeavesDecayContext(
      String worldName, int x, int y, int z, String blockKey, String biomeKey) {
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.x = x;
    this.y = y;
    this.z = z;
    this.blockKey = Objects.requireNonNull(blockKey, "blockKey");
    this.biomeKey = biomeKey == null ? "" : biomeKey;
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
