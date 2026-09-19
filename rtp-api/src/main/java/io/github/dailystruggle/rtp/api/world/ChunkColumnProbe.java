package io.github.dailystruggle.rtp.api.world;

import java.util.OptionalInt;

/**
 * Lean point-query view of a single chunk's column data.
 * Answers block and biome queries across a {@code [minY, maxY]} window
 * without materialising a full {@link RTPChunk}.
 */
public interface ChunkColumnProbe {

  /**
   * @return chunk X coordinate (world coords divided by 16, floored).
   */
  int chunkX();

  /**
   * @return chunk Z coordinate (world coords divided by 16, floored).
   */
  int chunkZ();

  /**
   * @return inclusive lower world-Y bound of the window this probe was built for.
   */
  int minY();

  /**
   * @return inclusive upper world-Y bound of the window this probe was built for.
   */
  int maxY();

  /**
   * Returns top-of-solid Y hint from {@code MOTION_BLOCKING_NO_LEAVES} heightmap.
   *
   * @return heightmap top-of-solid Y hint, or empty if unavailable
   */
  OptionalInt heightmapTopY();

  /**
   * Returns the block identifier at the center column at the given world Y.
   *
   * @param y world Y to query; must satisfy {@code minY <= y <= maxY}.
   * @return namespaced block identifier (e.g. {@code "minecraft:stone"}),
   *     or {@code null} if the window does not cover {@code y} or the
   *     producer has no answer for that cell.
   */
  String blockAt(int y);

  /**
   * Returns block identifier at chunk-local column {@code (localX, localZ)} at {@code y}.
   * Default delegates to {@link #blockAt(int)}.
   *
   * @param localX chunk-local X in {@code [0..15]}
   * @param localZ chunk-local Z in {@code [0..15]}
   * @param y      world Y query in {@code [minY..maxY]}
   * @return namespaced block identifier, or null if unavailable
   */
  default String blockAt(int localX, int localZ, int y) {
    return blockAt(y);
  }

  /**
   * Returns the biome identifier at the center column at the given world Y.
   *
   * @param y world Y to query; must satisfy {@code minY <= y <= maxY}.
   * @return namespaced biome identifier (e.g. {@code "minecraft:plains"}),
   *     or {@code null} if the window does not cover {@code y} or the
   *     producer has no answer for that cell.
   */
  String biomeAt(int y);

  /**
   * Returns whether center-column block at {@code y} is air.
   *
   * @param y world Y query
   * @return true if block identifier represents air
   */
  default boolean isAirAt(int y) {
    String b = blockAt(y);
    if (b == null) return false;
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air");
  }

  /**
   * Returns whether block at chunk-local column {@code (localX, localZ)} at {@code y} is air.
   *
   * @param localX chunk-local X in {@code [0..15]}
   * @param localZ chunk-local Z in {@code [0..15]}
   * @param y      world Y query
   * @return true if block identifier represents air
   */
  default boolean isAirAt(int localX, int localZ, int y) {
    String b = blockAt(localX, localZ, y);
    if (b == null) return false;
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air");
  }

}
