package io.github.dailystruggle.rtp.api.world;

/**
 * Immutable block-coordinate value type used throughout the RTP location pipeline.
 *
 * <p>All coordinates are in block units (1 unit = 1 block). World name is the
 * canonical server world name as returned by the platform (case-sensitive).
 *
 * <p>This type is platform-agnostic (REQ-API-F-004) and safe to pass across thread
 * boundaries without synchronization.
 *
 * @param worldName canonical name of the world; never {@code null}
 * @param x         block X coordinate
 * @param y         block Y coordinate
 * @param z         block Z coordinate
 * @see MutableRTPCoords
 * @see RTPLocation
 */
public record RTPCoords(String worldName, int x, int y, int z) {
  /**
   * Computes a 64-bit chunk key encoding the chunk X and chunk Z coordinates of
   * this block position, compatible with Minecraft's internal chunk map format.
   *
   * <p>Chunk X is stored in the lower 32 bits and chunk Z in the upper 32 bits,
   * each masked to {@code 0xFFFFFFFFL} to treat them as unsigned values.
   *
   * @return packed chunk key; suitable for use as a map key or cache lookup
   */
  public long getChunkKey() {
    return ((long) (x >> 4) & 0xFFFFFFFFL) | (((long) (z >> 4) & 0xFFFFFFFFL) << 32);
  }
}
