package io.github.dailystruggle.rtp.api.claim;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Platform-neutral representation of a faction or territory claim boundary.
 *
 * <p>Exposes spatial containment, current centroid, and bounding chunk coordinates
 * without coupling callers or addons to specific platform or claim implementations.
 */
@PublicApi
public interface ClaimBoundary {

  /**
   * Unique identifier of this claim or faction (e.g. faction UUID or tag).
   *
   * @return claim identifier, never {@code null}
   */
  String id();

  /**
   * World identifier where this claim resides.
   *
   * @return world name, never {@code null}
   */
  String world();

  /**
   * Check whether block coordinates {@code (x, z)} fall within this claim's territory.
   *
   * @param x block X coordinate
   * @param z block Z coordinate
   * @return {@code true} if contained within this claim
   */
  boolean contains(int x, int z);

  /**
   * Check whether chunk coordinates {@code (cx, cz)} fall within this claim's territory.
   *
   * @param cx chunk X coordinate
   * @param cz chunk Z coordinate
   * @return {@code true} if contained within this claim
   */
  default boolean containsChunk(int cx, int cz) {
    // Default: sample chunk center column (cx * 16 + 8, cz * 16 + 8)
    return contains((cx << 4) + 8, (cz << 4) + 8);
  }

  /**
   * Current geometric centroid {@code [x, z]} of the claim.
   *
   * @return integer array {@code [x, z]} representing the centroid in block coordinates
   */
  int[] centroid();

  /**
   * Minimum chunk X covering this claim boundary.
   *
   * @return min chunk X
   */
  int minChunkX();

  /**
   * Minimum chunk Z covering this claim boundary.
   *
   * @return min chunk Z
   */
  int minChunkZ();

  /**
   * Maximum chunk X covering this claim boundary.
   *
   * @return max chunk X
   */
  int maxChunkX();

  /**
   * Maximum chunk Z covering this claim boundary.
   *
   * @return max chunk Z
   */
  int maxChunkZ();

  /**
   * Minimum block X coordinate covering this claim boundary.
   *
   * @return min block X
   */
  default int minX() {
    return minChunkX() << 4;
  }

  /**
   * Minimum block Z coordinate covering this claim boundary.
   *
   * @return min block Z
   */
  default int minZ() {
    return minChunkZ() << 4;
  }

  /**
   * Maximum block X coordinate covering this claim boundary.
   *
   * @return max block X
   */
  default int maxX() {
    return (maxChunkX() << 4) + 15;
  }

  /**
   * Maximum block Z coordinate covering this claim boundary.
   *
   * @return max block Z
   */
  default int maxZ() {
    return (maxChunkZ() << 4) + 15;
  }
}
