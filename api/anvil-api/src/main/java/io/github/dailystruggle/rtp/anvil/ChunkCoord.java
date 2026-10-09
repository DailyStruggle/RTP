package io.github.dailystruggle.rtp.anvil;

/**
 * Absolute chunk coordinate. Value type shared by the batched probe ({@link AnvilPrefilter}) and
 * the batched hook SPI ({@code AnvilPrefilterRegistry.Provider#classifyBatch}).
 *
 * @param x chunk X ({@code blockX >> 4})
 * @param z chunk Z ({@code blockZ >> 4})
 */
public record ChunkCoord(int x, int z) {

  /** Region-file X ({@code r.X.Z.mca}) holding this chunk. */
  public int regionX() {
    return x >> 5;
  }

  /** Region-file Z ({@code r.X.Z.mca}) holding this chunk. */
  public int regionZ() {
    return z >> 5;
  }

  /** Packed region-file key; equal for every chunk stored in the same file. */
  public long regionKey() {
    return ((long) regionX() << 32) | (regionZ() & 0xFFFF_FFFFL);
  }
}
