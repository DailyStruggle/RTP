package io.github.dailystruggle.server.api;

import java.util.Objects;

/**
 * Platform-neutral context for a sapling or tree-feature growth event across Bukkit/Paper
 * ({@code StructureGrowEvent}), NeoForge ({@code BlockGrowFeatureEvent}), and Fabric bone-meal
 * interaction (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class SaplingGrowContext {
  private final String worldName;
  private final int x;
  private final int y;
  private final int z;
  private final String blockKey;
  private final String biomeKey;
  private final boolean onLiquid;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral sapling growth context.
   *
   * @param worldName canonical world name; must not be {@code null}
   * @param x origin block X coordinate
   * @param y origin block Y coordinate
   * @param z origin block Z coordinate
   * @param blockKey sapling block or tree feature identifier (e.g. {@code "minecraft:oak_sapling"})
   * @param biomeKey biome identifier at the origin
   * @param onLiquid {@code true} if the origin or supporting block is liquid/waterlogged
   */
  public SaplingGrowContext(
      String worldName,
      int x,
      int y,
      int z,
      String blockKey,
      String biomeKey,
      boolean onLiquid) {
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.x = x;
    this.y = y;
    this.z = z;
    this.blockKey = blockKey == null ? "" : blockKey;
    this.biomeKey = biomeKey == null ? "" : biomeKey;
    this.onLiquid = onLiquid;
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

  public boolean onLiquid() {
    return onLiquid;
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
