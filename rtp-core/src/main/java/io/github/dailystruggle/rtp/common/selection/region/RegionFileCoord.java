package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;

/**
 * Immutable key identifying a single Anvil region file ({@code .mca}) within a world.
 *
 * <p>Each Anvil region file covers a 32&times;32 chunk area, i.e. the chunk
 * coordinates {@code (cx, cz)} map to {@code (cx >> 5, cz >> 5)}. This record is
 * used as the bin key for the L3 backlog cache cross-RTP-region index
 * (see <a href="../../../../../../../../../../docs/adr/ADR-028-l3-backlog-cache.md">ADR-028</a>).
 *
 * <p>The world name is included so two distinct worlds with overlapping
 * {@code (rx, rz)} pairs do not collide in the same bin map. The term
 * &quot;region&quot; is overloaded in this codebase: the canonical RTP-domain concept
 * is {@link Region}, while this type addresses the Minecraft Anvil region file.
 *
 * @param worldName canonical name of the world; never {@code null}
 * @param rx        region-file X coordinate ({@code chunkX >> 5})
 * @param rz        region-file Z coordinate ({@code chunkZ >> 5})
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
