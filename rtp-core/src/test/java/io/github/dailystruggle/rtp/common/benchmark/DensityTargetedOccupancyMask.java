package io.github.dailystruggle.rtp.common.benchmark;

/**
 * Adjusts a {@link TiledOccupancyMask} to a target usable share while keeping its clustering.
 *
 * <p>Why this exists: the real save tiled outward is usable on about a quarter of its chunks, which
 * is a harsher world than most servers run. A comparison of two curves at a stated usable share
 * needs that share as an input, not as whatever the source save happens to be, because run counts
 * depend on the boundary length between usable and unusable ground and that boundary is a function
 * of density.
 *
 * <p>The adjustment promotes whole blocks of unusable chunks to usable, selected by a hash of the
 * block coordinates. Promotion is by block rather than by chunk deliberately: promoting individual
 * chunks would sprinkle isolated usable cells through unusable terrain, which is neither realistic
 * nor neutral - it manufactures short runs and would flatter whichever curve is worse at long ones.
 * A promoted block is a compact patch of ground, which is what added terrain looks like.
 *
 * <p><b>Caveat to carry with the figures:</b> promoted blocks are synthetic and square-edged, so
 * the added boundary is blockier than real coastline. It is added identically for both curves on
 * the same coordinates, so a comparison between them is unaffected; an absolute run count on this
 * mask is not a claim about any real world.
 */
public final class DensityTargetedOccupancyMask {

  /** Chunks per promoted block edge. Power of two, so block coordinates are a shift. */
  private static final int BLOCK_CHUNKS = 8;

  private static final int BLOCK_SHIFT = Integer.numberOfTrailingZeros(BLOCK_CHUNKS);

  private final TiledOccupancyMask source;

  /** Promotion probability applied to blocks. */
  private final double promotionRate;

  private final long seed;

  /**
   * @param source tiled real occupancy
   * @param targetUsableShare usable share to aim for, in {@code (0, 1)}
   * @param seed hash seed, so a run is reproducible
   */
  public DensityTargetedOccupancyMask(
      TiledOccupancyMask source, double targetUsableShare, long seed) {
    this.source = source;
    this.seed = seed;
    double base = source.occupiedFraction();
    // Promoting a block makes every chunk in it usable, so the expected share after promotion is
    // base + p * (1 - base). Solve for p, and clamp: a target below the base cannot be reached by
    // adding ground, and this mask deliberately never removes any.
    double p = base >= targetUsableShare ? 0.0d : (targetUsableShare - base) / (1.0d - base);
    this.promotionRate = Math.max(0.0d, Math.min(1.0d, p));
  }

  /** @return probability a block of unusable chunks is promoted */
  public double promotionRate() {
    return promotionRate;
  }

  /** @return usable share of the source before adjustment */
  public double baseUsableShare() {
    return source.occupiedFraction();
  }

  /**
   * @return expected usable share after adjustment; the benchmark counts the realised share over the
   *     domain it addresses rather than trusting this
   */
  public double expectedUsableShare() {
    double base = source.occupiedFraction();
    return base + (promotionRate * (1.0d - base));
  }

  /**
   * @param cx chunk x, unbounded
   * @param cz chunk z, unbounded
   * @return true when the chunk is usable ground
   */
  public boolean isOccupied(int cx, int cz) {
    if (source.isOccupied(cx, cz)) return true;
    if (promotionRate <= 0.0d) return false;
    // Top 53 bits of the hash as a uniform double, compared against the rate: the same form
    // java.util.Random uses, so the promoted fraction is the rate to within sampling error.
    long h = mix(cx >> BLOCK_SHIFT, cz >> BLOCK_SHIFT);
    return ((h >>> 11) * 0x1.0p-53d) < promotionRate;
  }

  /** SplitMix64 over the two block coordinates. Cheap, seeded, and no per-block table. */
  private long mix(int bx, int bz) {
    long z = (((long) bx) * 0x9E3779B97F4A7C15L) ^ (((long) bz) * 0xBF58476D1CE4E5B9L) ^ seed;
    z += 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
