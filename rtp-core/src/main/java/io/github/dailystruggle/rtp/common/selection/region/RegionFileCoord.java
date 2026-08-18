package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;

/**
 * Immutable key identifying a single Anvil region file ({@code .mca}) within a world (ADR-028).
 *
 * @param worldName world name
 * @param rx region-file X coordinate ({@code chunkX >> 5})
 * @param rz region-file Z coordinate ({@code chunkZ >> 5})
 */
public record RegionFileCoord(String worldName, int rx, int rz) {
  /**
   * Derives the bin key for the chunk containing the given block coordinates.
   *
   * @param coords block-precision coordinates; never {@code null}
   * @return the bin key for the {@code .mca} file containing this block
   */
  public static RegionFileCoord of(RTPCoords coords) {
    return new RegionFileCoord(coords.worldName(), coords.x() >> 9, coords.z() >> 9);
  }
}
