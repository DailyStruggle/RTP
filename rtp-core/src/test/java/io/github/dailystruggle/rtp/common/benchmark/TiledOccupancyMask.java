package io.github.dailystruggle.rtp.common.benchmark;

/**
 * Extends a real {@link WorldOccupancyMask} to an unbounded domain by repeating its region
 * footprint outward.
 *
 * <p>Why this exists: the model decision needs occupancy statistics at ranges far beyond any save
 * that can be generated in reasonable time, and the exact block coordinates of a bad area are
 * irrelevant to every quantity being measured - run counts, blob sizes, reconciliation locality and
 * directory footprint all depend on the <i>shape and density</i> of clustering, not on where it
 * sits. Repeating a real footprint therefore preserves what matters while removing the need to
 * generate terrain.
 *
 * <p>Tiling is at <b>region granularity</b>, so a repeated tile is always a whole number of {@code
 * .mca} files and no artificial structure is introduced inside a region. Alternate tiles are
 * mirrored, which prevents the periodicity from lining ragged edges up into long straight seams
 * across tile boundaries - a plain modulo tiling produces a grid of identical edges that would
 * flatter any run-length encoding.
 *
 * <p><b>Caveat to carry with the figures:</b> occupancy is periodic with the source save's bounding
 * box, so features larger than one tile cannot exist and long-range correlation beyond the tile
 * period is absent. Figures dominated by within-tile structure (runs per blob, blob bytes,
 * reconciliation per mark) are faithful; a figure that depended on continent-scale correlation
 * would not be, and none of the reported ones do.
 */
public final class TiledOccupancyMask {

  private final WorldOccupancyMask source;
  private final int minRegionX;
  private final int minRegionZ;
  private final int periodRegionsX;
  private final int periodRegionsZ;

  /**
   * @param source mask read from a real save
   */
  public TiledOccupancyMask(WorldOccupancyMask source) {
    this.source = source;
    int[] bounds = source.chunkBounds();
    int minRx = bounds[0] >> 5;
    int maxRx = bounds[1] >> 5;
    int minRz = bounds[2] >> 5;
    int maxRz = bounds[3] >> 5;
    this.minRegionX = minRx;
    this.minRegionZ = minRz;
    this.periodRegionsX = Math.max(1, maxRx - minRx + 1);
    this.periodRegionsZ = Math.max(1, maxRz - minRz + 1);
  }

  /** @return tile period in regions, {@code {x, z}} */
  public int[] periodRegions() {
    return new int[] {periodRegionsX, periodRegionsZ};
  }

  /** @return tile period in blocks, {@code {x, z}} */
  public long[] periodBlocks() {
    return new long[] {512L * periodRegionsX, 512L * periodRegionsZ};
  }

  /**
   * @param cx chunk x, unbounded
   * @param cz chunk z, unbounded
   * @return true when the corresponding chunk of the tiled footprint exists
   */
  public boolean isOccupied(int cx, int cz) {
    int rx = cx >> 5;
    int rz = cz >> 5;
    int offX = Math.floorMod(rx - minRegionX, periodRegionsX);
    int offZ = Math.floorMod(rz - minRegionZ, periodRegionsZ);
    boolean flipX = Math.floorDiv(rx - minRegionX, periodRegionsX) % 2 != 0;
    boolean flipZ = Math.floorDiv(rz - minRegionZ, periodRegionsZ) % 2 != 0;
    if (flipX) offX = periodRegionsX - 1 - offX;
    if (flipZ) offZ = periodRegionsZ - 1 - offZ;
    int srcRx = minRegionX + offX;
    int srcRz = minRegionZ + offZ;
    int localCx = flipX ? 31 - (cx & 31) : (cx & 31);
    int localCz = flipZ ? 31 - (cz & 31) : (cz & 31);
    return source.isOccupied((srcRx << 5) + localCx, (srcRz << 5) + localCz);
  }

  /**
   * Occupied fraction of the tiled footprint, i.e. one minus the bad density a shape would learn.
   *
   * @return fraction in {@code [0, 1]}
   */
  public double occupiedFraction() {
    long tileChunks = 1024L * periodRegionsX * periodRegionsZ;
    return tileChunks == 0L ? 0.0d : source.occupiedChunks() / (double) tileChunks;
  }
}
