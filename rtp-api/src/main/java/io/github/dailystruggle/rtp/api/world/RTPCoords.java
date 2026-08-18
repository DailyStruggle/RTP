package io.github.dailystruggle.rtp.api.world;

/**
 * Immutable block-coordinate value type for the RTP location pipeline.
 *
 * <p>All coordinates are in block units. Platform-agnostic (REQ-API-F-004) and thread-safe.
 *
 * @param worldName canonical world name; never {@code null}
 * @param x         block X coordinate
 * @param y         block Y coordinate
 * @param z         block Z coordinate
 */
public record RTPCoords(String worldName, int x, int y, int z) {
  /**
   * Computes packed 64-bit chunk key encoding chunk X (lower 32) and chunk Z (upper 32).
   *
   * @return packed 64-bit chunk key
   */
  public long getChunkKey() {
    return ((long) (x >> 4) & 0xFFFFFFFFL) | (((long) (z >> 4) & 0xFFFFFFFFL) << 32);
  }
}
