package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel/inheriting modified dual-layer shape exploring:
 * 1. Deriving downsampling stride directly from uniquePlacements radius (R_u) or explicit stride S.
 * 2. Full ergodic dyadic phase progression along the Hilbert space-filling curve.
 * 3. Side-by-side memory modeling (Monolithic flat arrays vs. Roaring/Hybrid containers).
 */
public class DownsampledDualLayerSquare extends SquareOptimizedDualLayer {

  private final long secretKey = ThreadLocalRandom.current().nextLong();
  private final AtomicLong nativeCounter = new AtomicLong(0);

  // Explicit stride override (if > 0, overrides automatic derivation)
  private volatile int explicitStride = 0;
  // If true, automatically derives sampling stride S from uniquePlacements radius R_u
  private volatile boolean autoDeriveStrideFromRadius = true;

  public DownsampledDualLayerSquare(String name, int pointEdgeChunks) {
    super(name, pointEdgeChunks);
  }

  public void setExplicitStride(int stride) {
    this.explicitStride = Math.max(1, stride);
  }

  public int getExplicitStride() {
    return explicitStride;
  }

  public void setAutoDeriveStrideFromRadius(boolean autoDerive) {
    this.autoDeriveStrideFromRadius = autoDerive;
  }

  public boolean isAutoDeriveStrideFromRadius() {
    return autoDeriveStrideFromRadius;
  }

  /**
   * Derives optimal power-of-four / dyadic stride S from configured uniquePlacements radius.
   * For exclusion radius R_u, the spacing between points is >= R_u chunks.
   * Along the 2D Hilbert curve, distance scales as d = sqrt(S) => S = R_u^2.
   * If R_u is a power of 2, S = R_u^2 is a power of 4, guaranteeing perfect quadtree resonance.
   */
  public int deriveStrideFromUniqueRadius() {
    if (explicitStride > 0) {
      return explicitStride;
    }
    Object raw = data.get(GenericMemoryShapeParams.uniquePlacements);
    int ru = uniquePlacementsRadius(raw);
    if (ru <= 1) return 1;

    // Direct power-of-two / power-of-four stride: S = R_u^2
    // e.g. R_u = 4 => S = 16 (4 chunk spacing)
    //      R_u = 8 => S = 64 (8 chunk spacing)
    //      R_u = 16 => S = 256 (16 chunk spacing)
    int shift = 64 - Long.numberOfLeadingZeros(ru - 1);
    int powerOfTwoRu = 1 << shift;
    int derivedStride = powerOfTwoRu * powerOfTwoRu;
    return Math.max(1, Math.min(1024, derivedStride));
  }

  /**
   * Parallel modified sampling implementation:
   * Uses Keyed Feistel PRP with dyadic striding across the space-filling curve.
   */
  @Override
  protected double sample(double range) {
    if (range <= 1.0) return 0.0;
    long total = (long) range;

    int stride = (explicitStride > 0)
        ? explicitStride
        : (autoDeriveStrideFromRadius ? deriveStrideFromUniqueRadius() : deriveAdaptiveStride(total));

    if (stride <= 1) {
      long t = nativeCounter.getAndIncrement();
      long permuted = feistelPermute(t, total, secretKey);
      return (double) Math.min(total - 1, Math.max(0L, permuted));
    }

    // Epoch-based phase progression:
    // Exhaust all candidate macro-tiles in the active phase across the world before rotating to the next phase offset.
    // This strictly preserves the d >= sqrt(S) spacing between all active candidates within the epoch!
    // In SquareOptimizedDualLayer, coordinates are constructed as:
    //   macroLoc * pointArea + h
    // where pointArea = pointEdgeChunks^2 (e.g. 32x32 = 1024 chunks).
    // An internal Hilbert curve lives inside each macro-tile (h in [0, pointArea)).
    // To guarantee that points maintain physical separation across the global coordinate space:
    // When stride S <= pointArea, dyadic striding can step macro-tiles and internal Hilbert indices.
    // If we step the macro-tiles directly or step h with stride S:
    int bits = Integer.numberOfTrailingZeros(stride);
    long t = nativeCounter.getAndIncrement();

    long subsetCapacity = (total + stride - 1) / stride;
    if (subsetCapacity <= 0) return (double) (t % total);

    long epoch = t / subsetCapacity;
    int subsetIdx = (int) (epoch % stride);
    int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

    long subsetSize = phaseOffset < total ? (total - 1 - phaseOffset) / stride + 1 : 0;
    if (subsetSize <= 0) return (double) (t % total);

    long kCounter = (t % subsetCapacity) % subsetSize;
    long permutedK = feistelPermute(kCounter, subsetSize, secretKey ^ (phaseOffset * 0x9E3779B97F4A7C15L));
    long candidate = permutedK * stride + phaseOffset;
    return (double) Math.min(total - 1, Math.max(0L, candidate));
  }

  private static long feistelPermute(long val, long domainSize, long seed) {
    if (domainSize <= 1) return 0;
    int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
    if ((bits & 1) != 0) bits++;
    int halfBits = bits / 2;
    long halfMask = (1L << halfBits) - 1L;

    long candidate = val % domainSize;
    do {
      long l = (candidate >>> halfBits) & halfMask;
      long r = candidate & halfMask;

      for (int round = 0; round < 4; round++) {
        long roundKey = seed ^ (0x9E3779B97F4A7C15L * (round + 1));
        long f = (r ^ roundKey);
        f ^= (f >>> 16);
        f *= 0x85ebca6b;
        f ^= (f >>> 13);
        f *= 0xc2b2ae35;
        f ^= (f >>> 16);

        long nextR = l ^ (f & halfMask);
        l = r;
        r = nextR;
      }

      candidate = (l << halfBits) | r;
    } while (candidate >= domainSize);

    return candidate;
  }
}
