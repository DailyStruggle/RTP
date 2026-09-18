package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Circle shape implementing the continuous Chebyshev-addressed Hilbert key space with
 * hardware-cache segmented secondary tables (ADR-085).
 *
 * <p>Nests the circular boundary within an integer Chebyshev square macro grid:
 * outer square envelope circumscribes the circle ($\text{macro\_r} = \lceil R / P \rceil$),
 * while the inner square envelope is inscribed in the inner donut hole
 * ($\text{macro\_cr} = \lfloor (CR / \sqrt{2}) / P \rfloor$), completely eliminating
 * polar coordinate collisions and boundary clipping.
 *
 * <p>Registered in ShapeFactory as {@code "CIRCLE_OPTIMIZED_DUAL_LAYER"}.
 */
public class CircleOptimizedDualLayer extends Circle {

  private final boolean derived;
  private volatile int cachedPointEdgeChunks;

  private volatile SegmentedKeyRunTable segmentedTable;

  public CircleOptimizedDualLayer() {
    this("CIRCLE_OPTIMIZED_DUAL_LAYER");
  }

  public CircleOptimizedDualLayer(String name) {
    super(name);
    this.derived = true;
    this.cachedPointEdgeChunks = 0;
  }

  public CircleOptimizedDualLayer(String name, int pointEdgeChunks) {
    super(name);
    if (Integer.bitCount(pointEdgeChunks) != 1) {
      throw new IllegalArgumentException("pointEdgeChunks must be a power of two: " + pointEdgeChunks);
    }
    this.derived = false;
    this.cachedPointEdgeChunks = pointEdgeChunks;
  }

  @Override
  public String getCurveName() {
    return CURVE_SPIRAL_HILBERT;
  }

  @Override
  @SuppressWarnings("PMD.PreferNonLockingExecution") // ADR-094: lazy-init dynamic point edge cache
  public int getPointEdgeChunks() {
    if (!derived) {
      return cachedPointEdgeChunks;
    }
    long radius = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    int computed = derivePointEdgeChunks(radius);
    int current = cachedPointEdgeChunks;
    if (current == 0 || computed > current) {
      synchronized (this) {
        current = cachedPointEdgeChunks;
        if (current == 0 || computed > current) {
          cachedPointEdgeChunks = computed;
          return computed;
        }
      }
    }
    return current;
  }

  @Override
  public long getRange() {
    int p = getPointEdgeChunks();
    int area = p * p;
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    if (r <= cr) return 0L;
    long kOuter = Math.max(1L, (r + p - 1) / p);
    long kInner = (cr <= 0) ? 0L : (long) Math.floor((cr / Math.sqrt(2.0)) / p);
    if (kOuter <= kInner) return 0L;
    return (4L * kOuter * kOuter - 4L * kInner * kInner) * area;
  }

  /**
   * Returns the dynamic effective circular radius. When expand is enabled, this expands
   * to account for the learned bad area: r_eff = sqrt(r^2 + badSum / PI).
   *
   * @return the effective circular radius under current expansion state
   */
  public long getEffectiveRadius() {
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    if (supportsExpand() && expand()) {
      long[] sums = badPrefixSumsCache;
      long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;
      if (badSum > 0L) {
        return (long) Math.ceil(Math.sqrt((double) r * r + (double) badSum / Math.PI));
      }
    }
    return r;
  }

  /**
   * Returns the effective outer Chebyshev macro-ring index corresponding to the effective radius.
   *
   * @param rEff the effective radius
   * @param p the point edge chunks
   * @return the outer macro-ring index
   */
  public long getEffectiveKOuter(long rEff, int p) {
    return Math.max(1L, (rEff + p - 1) / p);
  }

  @Override
  public long xzToLocation(long cx, long cz) {
    int p = getPointEdgeChunks();
    int area = p * p;
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long rEff = getEffectiveRadius();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long relX = cx - cenX;
    long relZ = cz - cenZ;

    long distSq = relX * relX + relZ * relZ;
    if (distSq < cr * cr) {
      return -1L;
    }
    if (distSq > rEff * rEff) {
      return -1L;
    }

    long px = Math.floorDiv(relX, p);
    long pz = Math.floorDiv(relZ, p);

    long kX = (px >= 0) ? (px + 1L) : -px;
    long kZ = (pz >= 0) ? (pz + 1L) : -pz;
    long K = Math.max(kX, kZ);

    long kInner = (cr <= 0) ? 0L : (long) Math.floor((cr / Math.sqrt(2.0)) / p);
    long kOuterEff = getEffectiveKOuter(rEff, p);

    if (K <= kInner) return -1L;
    if (K > kOuterEff) return -1L;

    long side;
    long sideStep;
    if (px == K - 1L && pz > -K) {
      side = 0L;
      sideStep = pz + (K - 1L);
    } else if (pz == K - 1L && px < K - 1L) {
      side = 1L;
      sideStep = (K - 2L) - px;
    } else if (px == -K && pz < K - 1L) {
      side = 2L;
      sideStep = (K - 2L) - pz;
    } else {
      side = 3L;
      sideStep = px - (-K + 1L);
    }

    long fullMacroIdx = 4L * (K - 1L) * (K - 1L) + side * (2L * K - 1L) + sideStep;
    long macroLoc = fullMacroIdx - 4L * kInner * kInner;
    if (macroLoc < 0) return -1L;

    int lx = (int) (relX - px * p);
    int lz = (int) (relZ - pz * p);

    int orientation = orientationFor(px, pz);
    long h = xyToHilbert(lx, lz, p, orientation);
    return macroLoc * area + h;
  }

  @Override
  public void locationToXZ(long loc, MutableRTPCoords output) {
    if (loc < 0) {
      if (output != null) output.setXZ(0, 0);
      return;
    }

    int p = getPointEdgeChunks();
    int area = p * p;
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long kInner = (cr <= 0) ? 0L : (long) Math.floor((cr / Math.sqrt(2.0)) / p);
    long macroLoc = loc / area;
    long h = loc % area;

    long fullMacroIdx = macroLoc + 4L * kInner * kInner;

    long target = fullMacroIdx / 4L;
    long K = (long) Math.floor(Math.sqrt(target)) + 1L;
    while ((K - 1L) * (K - 1L) > target) K--;
    while (K * K <= target) K++;

    long ringBase = 4L * (K - 1L) * (K - 1L);
    long step = fullMacroIdx - ringBase;
    long sideLen = 2L * K - 1L;
    long side = step / sideLen;
    long sideStep = step % sideLen;

    long px, pz;
    if (side == 0) {
      px = K - 1L;
      pz = -(K - 1L) + sideStep;
    } else if (side == 1) {
      pz = K - 1L;
      px = (K - 2L) - sideStep;
    } else if (side == 2) {
      px = -K;
      pz = (K - 2L) - sideStep;
    } else {
      pz = -K;
      px = (-K + 1L) + sideStep;
    }

    int orientation = orientationFor(px, pz);
    int[] local = hilbertToXY((int) h, p, orientation);

    long cx = cenX + px * p + local[0];
    long cz = cenZ + pz * p + local[1];

    if (output != null) {
      output.setXZ((int) cx, (int) cz);
    }
  }

  @Override
  public long rand() {
    for (int attempts = 0; attempts < 100; attempts++) {
      long loc = super.rand();
      if (loc >= 0) {
        return loc;
      }
    }
    return -1L;
  }

  @Override
  public int[] select() {
    for (int attempts = 0; attempts < 100; attempts++) {
      long loc = rand();
      if (loc >= 0) {
        return locationToXZ(loc);
      }
    }
    return null;
  }

  @Override
  public boolean contains(int x, int z) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long rEff = getEffectiveRadius();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long relX = (long) x - cenX;
    long relZ = (long) z - cenZ;
    long distSq = relX * relX + relZ * relZ;

    if (distSq < cr * cr) {
      return false;
    }

    if (distSq > rEff * rEff) {
      return false;
    }

    if (expand()) {
      long loc = xzToLocation(x, z);
      if (loc < 0L || loc >= getEffectiveRange()) return false;
    }

    return true;
  }

  @Override
  public long[] chunkToLocations(int cx, int cz) {
    if (!contains(cx, cz)) return EMPTY_LONG_ARRAY;
    long loc = xzToLocation(cx, cz);
    if (loc < 0L) return EMPTY_LONG_ARRAY;
    if (loc >= getEffectiveRange()) return EMPTY_LONG_ARRAY;
    return new long[] {loc};
  }

  @Override
  protected long postProcess(long location) {
    if (location < 0) return location;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    locationToXZ(location, coords);
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long rEff = getEffectiveRadius();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
    long relX = (long) coords.x - cenX;
    long relZ = (long) coords.z - cenZ;
    long distSq = relX * relX + relZ * relZ;
    if (distSq < cr * cr || distSq > rEff * rEff) {
      return -1L;
    }
    return location;
  }

  private static final SecureRandom SEED_SOURCE = new SecureRandom();
  private final long feistelSalt = SEED_SOURCE.nextLong();
  private final java.util.concurrent.atomic.AtomicLong selectionCounter = new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong backlogCounter = new java.util.concurrent.atomic.AtomicLong(0);

  @Override
  protected void onExpansionEpochIncrement() {
    selectionCounter.set(0);
    backlogCounter.set(0);
  }

  private long getEpochKey(long epoch, long phaseOffset) {
    return feistelSalt ^ (epoch * 0x517CC1B727220A95L) ^ (phaseOffset * 0x9E3779B97F4A7C15L);
  }

  /**
   * Optimized native candidate distribution model.
   * Dynamically evaluates effective dyadic downsampling stride S based on
   * {@code spatialResolution}, {@code uniquePlacements}, and {@code expand}.
   * If {@code S <= 1} (e.g. fixed radius expand:false with default spatialResolution),
   * uses unbinned Keyed Feistel Pseudorandom Permutation (PRP) across [0, range)
   * for non-repeating sampling preserving all valid land.
   * If S > 1, applies Dyadic Bit-Reversal Bisection Striding to space candidates
   * and accelerate outward frontier expansion.
   */
  @Override
  protected double sample(double range) {
    if (range <= 1.0) return 0.0;
    long total = (long) range;
    int stride = deriveEffectiveStride(total);

    long curEpoch = getExpansionEpoch();
    if (stride <= 1) {
      long t = selectionCounter.getAndIncrement();
      long permuted = feistelPermute(t, total, getEpochKey(curEpoch, 0L));
      return (double) Math.min(total - 1, Math.max(0L, permuted));
    }

    int bits = Integer.numberOfTrailingZeros(stride);
    long t = selectionCounter.getAndIncrement();

    // Epoch-based phase progression:
    // Exhaust all candidate macro-tiles in the active phase across the world before rotating to the next phase offset.
    // This strictly preserves the d >= sqrt(S) spacing between all active candidates within the epoch!
    long subsetCapacity = (total + stride - 1) / stride;
    if (subsetCapacity <= 0) {
      return (double) (t % total);
    }
    long epoch = t / subsetCapacity;
    int subsetIdx = (int) (epoch % stride);
    int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

    long subsetSize = phaseOffset < total ? (total - 1 - phaseOffset) / stride + 1 : 0;
    if (subsetSize <= 0) {
      return (double) (t % total);
    }

    long kCounter = (t % subsetCapacity) % subsetSize;
    long permutedK = feistelPermute(kCounter, subsetSize, getEpochKey(curEpoch, phaseOffset));
    long candidate = permutedK * stride + phaseOffset;
    return (double) Math.min(total - 1, Math.max(0L, candidate));
  }

  /**
   * Derives effective dyadic stride S from {@code spatialResolution},
   * {@code uniquePlacements}, and {@code expand}.
   *
   * @param domainSize available candidate count
   * @return power-of-two dyadic stride in [1 .. 1024]
   */
  public int deriveEffectiveStride(long domainSize) {
    long res = spatialResolution();
    // 1. Explicit spatialResolution override: res > 1 directly dictates sampling cell area
    if (res > 1L) {
      long cellDim = 1L << (64 - Long.numberOfLeadingZeros(res - 1L));
      long cellStride = cellDim * cellDim;
      return (int) Math.max(1, Math.min(1024L, Math.min(domainSize / 4L, cellStride)));
    }

    // 2. Expand mode: derive from uniquePlacements exclusion radius (or view distance if auto)
    if (expand()) {
      Object raw = data.get(GenericMemoryShapeParams.uniquePlacements);
      int ru = uniquePlacementsRadius(raw);
      if (ru > 1) {
        long footprint = (long) (2 * ru - 1) * (2 * ru - 1);
        int shift = 64 - Long.numberOfLeadingZeros(footprint - 1L);
        int derived = 1 << shift;
        return (int) Math.max(1, Math.min(1024, Math.min(domainSize / 4L, (long) derived)));
      }
    }

    // 3. Default (fixed-radius, spatialResolution=1): full 1:1 resolution (S = 1)
    return 1;
  }

  /**
   * Dedicated backlog harvest selection.
   * Specifically uses Dyadic Stride and Keyed Feistel Pseudorandom Permutation (PRP)
   * for Poisson-spaced candidate binning across the backlog queue.
   */
  public long selectBacklogCandidate() {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long crSq = cr * cr;
    long rSq = r * r;

    long range = getRange();
    if (range <= 0) return -1L;

    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    long curEpoch = getExpansionEpoch();
    if (MODE_ACCUMULATE.equals(mode())) {
      SegmentedKeyRunTable table = getOrBuildSegmentedTable(range);
      long totalGood = range - table.totalCovered();
      if (totalGood <= 0) return -1L;

      int stride = SquareOptimizedDualLayer.deriveAdaptiveStride(totalGood);
      int bits = Integer.numberOfTrailingZeros(stride);

      for (int attempts = 0; attempts < 5; attempts++) {
        long t = backlogCounter.getAndIncrement();
        int subsetIdx = (int) (t % stride);
        int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

        long subsetSize = phaseOffset < totalGood ? (totalGood - 1 - phaseOffset) / stride + 1 : 0;
        if (subsetSize <= 0) {
          return table.resolveAccumulate(t % totalGood);
        }

        long kCounter = t / stride;
        long permutedK = feistelPermute(kCounter, subsetSize, getEpochKey(curEpoch, phaseOffset));
        long virtualGoodIndex = permutedK * stride + phaseOffset;

        long loc = table.resolveAccumulate(virtualGoodIndex);
        if (loc < 0 || loc >= range) continue;

        locationToXZ(loc, coords);
        long dx = (long) coords.x - cenX;
        long dz = (long) coords.z - cenZ;
        long distSq = dx * dx + dz * dz;

        if (distSq >= crSq && distSq <= rSq) {
          return loc;
        }
      }
      return -1L;
    }

    // Standard / Default mode for backlog: Dyadic stride binning
    int stride = SquareOptimizedDualLayer.deriveAdaptiveStride(range);
    int bits = Integer.numberOfTrailingZeros(stride);

    for (int attempts = 0; attempts < 10; attempts++) {
      long t = backlogCounter.getAndIncrement();
      int subsetIdx = (int) (t % stride);
      int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

      long subsetSize = phaseOffset < range ? (range - 1 - phaseOffset) / stride + 1 : 0;
      if (subsetSize <= 0) continue;

      long kCounter = t / stride;
      long permutedK = feistelPermute(kCounter, subsetSize, getEpochKey(curEpoch, phaseOffset));
      long loc = permutedK * stride + phaseOffset;

      if (loc < 0 || loc >= range) continue;

      locationToXZ(loc, coords);
      long dx = (long) coords.x - cenX;
      long dz = (long) coords.z - cenZ;
      long distSq = dx * dx + dz * dz;

      if (distSq >= crSq && distSq <= rSq) {
        return loc;
      }
    }

    return -1L;
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
        long newL = r;
        long newR = (l ^ f) & halfMask;
        l = newL;
        r = newR;
      }
      candidate = (l << halfBits) | r;
    } while (candidate >= domainSize);

    return candidate;
  }

  @SuppressWarnings("PMD.PreferNonLockingExecution") // ADR-094: lazy-build segmented run table cache
  private SegmentedKeyRunTable getOrBuildSegmentedTable(long range) {
    synchronized (this) {
      long[] keys = badKeysCache;
      long[] sums = badPrefixSumsCache;
      int count = Math.min(keys.length, sums.length);

      if (segmentedTable != null && segmentedTable.totalRange() == range) {
        return segmentedTable;
      }

      long[] widths = new long[count];
      long prev = 0L;
      for (int i = 0; i < count; i++) {
        widths[i] = sums[i] - prev;
        prev = sums[i];
      }

      long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(range);
      segmentedTable = SegmentedKeyRunTable.fromRuns(keys, widths, count, range, binSize, 3L);
      return segmentedTable;
    }
  }

  // --- Macro-Ring Area LUT & Quantile Remapping ---

  private static final class MacroRingLUT {
    final long radius;
    final long centerRadius;
    final int p;
    final long kInner;
    final long kOuter;
    final long[] cumRingChunks;
    final long totalValidChunks;

    MacroRingLUT(long radius, long centerRadius, int p, long kInner, long kOuter, long[] cumRingChunks, long totalValidChunks) {
      this.radius = radius;
      this.centerRadius = centerRadius;
      this.p = p;
      this.kInner = kInner;
      this.kOuter = kOuter;
      this.cumRingChunks = cumRingChunks;
      this.totalValidChunks = totalValidChunks;
    }
  }

  private volatile MacroRingLUT cachedMacroRingLUT;

  /**
   * Analytical count of valid circular chunks inside a Chebyshev macro-tile [px, pz] of size P x P.
   * Uses 4-corner bounding box tests in O(1) time, only visiting chunks for boundary-intersecting tiles.
   */
  public static int countTileChunks(long px, long pz, int p, long crSq, long rSq) {
    long x0 = px * p;
    long x1 = (px + 1L) * p - 1L;
    long z0 = pz * p;
    long z1 = (pz + 1L) * p - 1L;

    long c00 = x0 * x0 + z0 * z0;
    long c01 = x0 * x0 + z1 * z1;
    long c10 = x1 * x1 + z0 * z0;
    long c11 = x1 * x1 + z1 * z1;

    long dSqMax = Math.max(Math.max(c00, c01), Math.max(c10, c11));

    long cx = (x0 <= 0L && 0L <= x1) ? 0L : ((x0 > 0L) ? x0 : x1);
    long cz = (z0 <= 0L && 0L <= z1) ? 0L : ((z0 > 0L) ? z0 : z1);
    long dSqMin = cx * cx + cz * cz;

    // Fully outside
    if (dSqMin > rSq || dSqMax < crSq) {
      return 0;
    }
    // Fully inside
    if (dSqMax <= rSq && dSqMin >= crSq) {
      return p * p;
    }

    // Boundary tile: count intersecting chunks
    int count = 0;
    for (long x = x0; x <= x1; x++) {
      long xSq = x * x;
      for (long z = z0; z <= z1; z++) {
        long d2 = xSq + z * z;
        if (d2 >= crSq && d2 <= rSq) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Retrieves or builds the analytical MacroRingLUT for Chebyshev macro-rings.
   * Evaluates valid chunks per ring in O(K) tile tests rather than full-domain chunk enumeration.
   */
  @SuppressWarnings("PMD.PreferNonLockingExecution") // ADR-094: lazy-build macro ring LUT cache
  public MacroRingLUT getOrBuildMacroRingLUT() {
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    int p = getPointEdgeChunks();

    MacroRingLUT current = cachedMacroRingLUT;
    if (current != null && current.radius == r && current.centerRadius == cr && current.p == p) {
      return current;
    }

    synchronized (this) {
      current = cachedMacroRingLUT;
      if (current != null && current.radius == r && current.centerRadius == cr && current.p == p) {
        return current;
      }

      long kInner = (cr <= 0) ? 0L : (long) Math.floor((cr / Math.sqrt(2.0)) / p);
      long kOuter = Math.max(1L, (r + p) / p);
      int ringCount = (int) Math.max(1L, kOuter - kInner);

      long[] cumRingChunks = new long[ringCount];
      long crSq = cr * cr;
      long rSq = r * r;
      long running = 0L;

      for (int i = 0; i < ringCount; i++) {
        long K = kInner + 1L + i;
        long sideLen = 2L * K - 1L;
        long ringValid = 0L;

        for (int side = 0; side < 4; side++) {
          for (long step = 0; step < sideLen; step++) {
            long px, pz;
            if (side == 0) {
              px = K - 1L;
              pz = -(K - 1L) + step;
            } else if (side == 1) {
              pz = K - 1L;
              px = (K - 2L) - step;
            } else if (side == 2) {
              px = -K;
              pz = (K - 2L) - step;
            } else {
              pz = -K;
              px = (-K + 1L) + step;
            }
            ringValid += countTileChunks(px, pz, p, crSq, rSq);
          }
        }
        running += ringValid;
        cumRingChunks[i] = running;
      }

      MacroRingLUT created = new MacroRingLUT(r, cr, p, kInner, kOuter, cumRingChunks, running);
      cachedMacroRingLUT = created;
      return created;
    }
  }

  /**
   * Resolves a target quantile g in [0, 1] into a discrete location key using
   * the quadratic area mapping and analytical macro-ring LUT.
   * Guarantees &le; 3% Gaussian distribution error with 0% boundary rejections.
   *
   * @param g sampled 1D Gaussian quantile in [0, 1]
   * @return resolved location key within this shape's hybrid key space
   */
  public long sampleQuantileLocation(double g) {
    MacroRingLUT lut = getOrBuildMacroRingLUT();
    if (lut.totalValidChunks <= 0L) return -1L;

    long r = lut.radius;
    long cr = lut.centerRadius;
    int p = lut.p;
    int area = p * p;

    double cRatio = (r > 0) ? (double) cr / (double) r : 0.0;
    double u = (2.0 * cRatio * g + (1.0 - cRatio) * g * g) / (1.0 + cRatio);
    long targetIdx = (long) (u * lut.totalValidChunks);
    if (targetIdx >= lut.totalValidChunks) targetIdx = lut.totalValidChunks - 1L;

    int ringIdx = java.util.Arrays.binarySearch(lut.cumRingChunks, targetIdx);
    if (ringIdx < 0) ringIdx = -ringIdx - 1;
    if (ringIdx >= lut.cumRingChunks.length) ringIdx = lut.cumRingChunks.length - 1;

    long K = lut.kInner + 1L + ringIdx;
    long sideLen = 2L * K - 1L;
    long totalTilesInRing = 4L * sideLen;

    // Pick tile and internal coordinate within ring K
    long randomTileStep = ThreadLocalRandom.current().nextLong(totalTilesInRing);
    long side = randomTileStep / sideLen;
    long sideStep = randomTileStep % sideLen;

    long px, pz;
    if (side == 0) {
      px = K - 1L;
      pz = -(K - 1L) + sideStep;
    } else if (side == 1) {
      pz = K - 1L;
      px = (K - 2L) - sideStep;
    } else if (side == 2) {
      px = -K;
      pz = (K - 2L) - sideStep;
    } else {
      pz = -K;
      px = (-K + 1L) + sideStep;
    }

    int orientation = orientationFor(px, pz);
    long randomHilbert = ThreadLocalRandom.current().nextLong(area);

    long fullMacroIdx = 4L * (K - 1L) * (K - 1L) + side * (2L * K - 1L) + sideStep;
    long macroLoc = fullMacroIdx - 4L * lut.kInner * lut.kInner;
    if (macroLoc < 0) return -1L;

    return macroLoc * area + randomHilbert;
  }


  private static long xyToHilbert(int x, int y, int n, int orientation) {
    int rx, ry;
    long d = 0;
    int rotatedX = x;
    int rotatedY = y;
    if (orientation != 0) {
      int[] rot = applyOrientation(x, y, n, orientation);
      rotatedX = rot[0];
      rotatedY = rot[1];
    }
    for (int s = n / 2; s > 0; s /= 2) {
      rx = (rotatedX & s) > 0 ? 1 : 0;
      ry = (rotatedY & s) > 0 ? 1 : 0;
      d += (long) s * s * ((3 * rx) ^ ry);
      int[] rot = rot(s, rotatedX, rotatedY, rx, ry);
      rotatedX = rot[0];
      rotatedY = rot[1];
    }
    return d;
  }

  private static int[] hilbertToXY(int d, int n, int orientation) {
    int rx, ry, t = d;
    int x = 0;
    int y = 0;
    for (int s = 1; s < n; s *= 2) {
      rx = 1 & (t / 2);
      ry = 1 & (t ^ rx);
      int[] r = rotFromD(s, x, y, rx, ry);
      x = r[0] + s * rx;
      y = r[1] + s * ry;
      t /= 4;
    }
    if (orientation != 0) {
      return unapplyOrientation(x, y, n, orientation);
    }
    return new int[] {x, y};
  }

  private static int[] rot(int n, int x, int y, int rx, int ry) {
    if (ry == 0) {
      if (rx == 1) {
        x = 2 * n - 1 - x;
        y = n - 1 - y;
      }
      return new int[] {y, x};
    }
    return new int[] {x, y};
  }

  private static int[] rotFromD(int n, int x, int y, int rx, int ry) {
    if (ry == 0) {
      if (rx == 1) {
        x = n - 1 - x;
        y = n - 1 - y;
      }
      return new int[] {y, x};
    }
    return new int[] {x, y};
  }

}
