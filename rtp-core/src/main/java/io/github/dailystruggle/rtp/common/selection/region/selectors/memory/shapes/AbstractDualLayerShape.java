package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
import java.security.SecureRandom;
import java.util.EnumMap;

/**
 * Common base class for dual-layer continuous spiral-addressed Hilbert key space shapes (ADR-085).
 * Consolidates Hilbert curve translations, dyadic downsampling stride filtering, Keyed Feistel
 * PRP candidate distribution, and hardware-cache segmented run table caching.
 */
public abstract class AbstractDualLayerShape extends MemoryShape<GenericMemoryShapeParams> {

  protected static final SecureRandom SEED_SOURCE = new SecureRandom();

  protected final boolean derived;
  protected volatile int cachedPointEdgeChunks;

  @SuppressWarnings("java:S3077") // Volatile publication of immutable SegmentedKeyRunTable snapshot
  protected volatile SegmentedKeyRunTable segmentedTable;

  protected final long feistelSalt = SEED_SOURCE.nextLong();
  protected final java.util.concurrent.atomic.AtomicLong selectionCounter = new java.util.concurrent.atomic.AtomicLong(0);
  protected final java.util.concurrent.atomic.AtomicLong backlogCounter = new java.util.concurrent.atomic.AtomicLong(0);

  // Stochastic batch window state for sequential player spacing
  protected final java.util.concurrent.atomic.AtomicInteger activePhaseIndex = new java.util.concurrent.atomic.AtomicInteger(0);
  protected final java.util.concurrent.atomic.AtomicInteger activeWindowRemaining = new java.util.concurrent.atomic.AtomicInteger(0);
  protected final java.util.concurrent.atomic.AtomicInteger phaseStepCounter = new java.util.concurrent.atomic.AtomicInteger(0);
  protected final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong> phaseProgress =
      new java.util.concurrent.ConcurrentHashMap<>();

  protected AbstractDualLayerShape(String name, EnumMap<GenericMemoryShapeParams, Object> defaults) {
    super(GenericMemoryShapeParams.class, name, defaults);
    this.derived = true;
    this.cachedPointEdgeChunks = 0;
  }

  protected AbstractDualLayerShape(String name, EnumMap<GenericMemoryShapeParams, Object> defaults, int pointEdgeChunks) {
    super(GenericMemoryShapeParams.class, name, defaults);
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
  public long[] chunkToLocations(int cx, int cz) {
    if (!contains(cx, cz)) return EMPTY_LONG_ARRAY;
    long loc = xzToLocation(cx, cz);
    if (loc < 0L) return EMPTY_LONG_ARRAY;
    if (loc >= getEffectiveRange()) return EMPTY_LONG_ARRAY;
    return new long[] {loc};
  }

  @Override
  protected void onExpansionEpochIncrement() {
    selectionCounter.set(0);
    backlogCounter.set(0);
    activeWindowRemaining.set(0);
    phaseProgress.clear();
  }

  protected long getEpochKey(long epoch, long phaseOffset) {
    return feistelSalt ^ (epoch * 0x517CC1B727220A95L) ^ (phaseOffset * 0x9E3779B97F4A7C15L);
  }

  @Override
  protected double sample(double range) {
    if (range <= 1.0) return 0.0;
    long total = (long) range;
    int stride = deriveEffectiveStride(total);

    long curEpoch = getExpansionEpoch();
    if (stride <= 1) {
      long t = selectionCounter.getAndIncrement();
      long permuted = feistelPermute(t, total, getEpochKey(curEpoch, 0L));
      return Math.min(total - 1, Math.max(0L, permuted));
    }

    int bits = Integer.numberOfTrailingZeros(stride);
    int phaseOffset = getOrRotatePhaseOffset(stride, bits, curEpoch);

    long subsetSize = phaseOffset < total ? (total - 1 - phaseOffset) / stride + 1 : 0;
    if (subsetSize <= 0) {
      long t = selectionCounter.getAndIncrement();
      return (t % total);
    }

    long kCounter = phaseProgress.computeIfAbsent(phaseOffset, k -> new java.util.concurrent.atomic.AtomicLong(0))
        .getAndIncrement();
    long permutedK = feistelPermute(kCounter % subsetSize, subsetSize, getEpochKey(curEpoch, phaseOffset));
    long candidate = permutedK * stride + phaseOffset;
    return Math.min(total - 1, Math.max(0L, candidate));
  }

  @SuppressWarnings("PMD.PreferNonLockingExecution") // ADR-094: double-checked phase-window rotation guards a single-winner batch reseed across atomics
  protected int getOrRotatePhaseOffset(int stride, int bits, long curEpoch) {
    if (activeWindowRemaining.decrementAndGet() <= 0) {
      synchronized (this) {
        if (activeWindowRemaining.get() <= 0) {
          int step = phaseStepCounter.getAndIncrement();
          long phaseKey = feistelSalt ^ (curEpoch * 0x3C6EF372FE94F82AL);
          int permutedStep = (int) feistelPermute(step % stride, stride, phaseKey);
          int nextPhase = Integer.reverse(permutedStep) >>> (32 - bits);
          activePhaseIndex.set(nextPhase);

          // Sample Gaussian batch window centered at 32 (std dev ~6, bounds [16, 64])
          int w = (int) Math.round(32.0 + SEED_SOURCE.nextGaussian() * 6.0);
          activeWindowRemaining.set(Math.max(16, Math.min(64, w)));
        }
      }
    }
    return activePhaseIndex.get();
  }

  public int deriveEffectiveStride(long domainSize) {
    long res = spatialResolution();
    int p = getPointEdgeChunks();
    long binArea = (long) p * p;
    long maxStrideByBin = Math.max(1L, binArea / 2L);

    if (res > 1L) {
      long cellDim = 1L << (64 - Long.numberOfLeadingZeros(res - 1L));
      long cellStride = cellDim * cellDim;
      return (int) Math.max(1, Math.min(maxStrideByBin, Math.min(domainSize / 4L, cellStride)));
    }

    Object raw = data.get(GenericMemoryShapeParams.uniquePlacements);
    int ru = uniquePlacementsRadius(raw);
    if (ru > 1) {
      long footprint = (long) (2 * ru - 1) * (2 * ru - 1);
      int shift = 64 - Long.numberOfLeadingZeros(footprint - 1L);
      int derivedStride = 1 << shift;
      return (int) Math.max(1, Math.min(maxStrideByBin, Math.min(domainSize / 4L, derivedStride)));
    }

    if (ru == 1) {
      return 1;
    }

    if (expand()) {
      return deriveAdaptiveStride(domainSize);
    }

    return 1;
  }

  public long selectBacklogCandidate() {
    return selectL3Candidate();
  }

  public long currentSweepCounter() {
    return backlogCounter.get();
  }

  public long selectL3Candidate() {
    long range = getEffectiveRange();
    if (range <= 0) return -1L;

    long t = backlogCounter.getAndIncrement();
    return keyForCounter(t);
  }

  public long keyForCounter(long t) {
    long range = getEffectiveRange();
    if (range <= 0) return -1L;

    long curEpoch = getExpansionEpoch();
    if (MODE_ACCUMULATE.equals(mode())) {
      SegmentedKeyRunTable table = getOrBuildSegmentedTable(range);
      long totalGood = range - table.totalCovered();
      if (totalGood <= 0) return -1L;

      int stride = deriveAdaptiveStride(totalGood);
      int bits = Integer.numberOfTrailingZeros(stride);
      int subsetIdx = (int) (t % stride);
      int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

      long subsetSize = phaseOffset < totalGood ? (totalGood - 1 - phaseOffset) / stride + 1 : 0;
      if (subsetSize <= 0) {
        return table.resolveAccumulate(t % totalGood);
      }

      long kCounter = t / stride;
      long permutedK = feistelPermute(kCounter, subsetSize, getEpochKey(curEpoch, phaseOffset));
      long virtualGoodIndex = permutedK * stride + phaseOffset;

      return table.resolveAccumulate(virtualGoodIndex);
    }

    int stride = deriveAdaptiveStride(range);
    int bits = Integer.numberOfTrailingZeros(stride);
    int subsetIdx = (int) (t % stride);
    int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

    long subsetSize = phaseOffset < range ? (range - 1 - phaseOffset) / stride + 1 : 0;
    if (subsetSize <= 0) {
      return t % range;
    }

    long kCounter = t / stride;
    long permutedK = feistelPermute(kCounter, subsetSize, getEpochKey(curEpoch, phaseOffset));
    return permutedK * stride + phaseOffset;
  }

  public long predictNextKeyForBin(long binIndex, long fromCounter) {
    long range = getEffectiveRange();
    if (range <= 0) return -1L;

    long domainSize;
    if (MODE_ACCUMULATE.equals(mode())) {
      SegmentedKeyRunTable table = getOrBuildSegmentedTable(range);
      domainSize = range - table.totalCovered();
    } else {
      domainSize = range;
    }
    if (domainSize <= 0) return -1L;

    for (long i = 0; i < domainSize; i++) {
      long c = fromCounter + i;
      long key = keyForCounter(c);
      if (key >= 0 && (key / 1024L) == binIndex) {
        return c;
      }
    }
    return -1L;
  }

  public long[] predictUpcomingBins(int lookahead) {
    if (lookahead <= 0) {
      return new long[0];
    }
    long startCounter = currentSweepCounter();
    java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
    for (int i = 0; i < lookahead; i++) {
      long key = keyForCounter(startCounter + i);
      if (key >= 0) {
        seen.add(key / 1024L);
      }
    }
    long[] result = new long[seen.size()];
    int idx = 0;
    for (long b : seen) {
      result[idx++] = b;
    }
    return result;
  }

  public static int deriveAdaptiveStride(long domainSize) {
    if (domainSize < 64) return 1;
    if (domainSize < 256) return 4;
    if (domainSize < 1024) return 16;
    if (domainSize < 8192) return 64;
    return 256;
  }

  protected static long feistelPermute(long val, long domainSize, long seed) {
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
  protected SegmentedKeyRunTable getOrBuildSegmentedTable(long range) {
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

  protected static long xyToHilbert(int x, int y, int n, int orientation) {
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

  protected static int[] hilbertToXY(int d, int n, int orientation) {
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

  protected static int[] rot(int n, int x, int y, int rx, int ry) {
    if (ry == 0) {
      if (rx == 1) {
        x = 2 * n - 1 - x;
        y = n - 1 - y;
      }
      return new int[] {y, x};
    }
    return new int[] {x, y};
  }

  protected static int[] rotFromD(int n, int x, int y, int rx, int ry) {
    if (ry == 0) {
      if (rx == 1) {
        x = n - 1 - x;
        y = n - 1 - y;
      }
      return new int[] {y, x};
    }
    return new int[] {x, y};
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    locationToXZ(location, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    if (coords == null) return -1L;
    return xzToLocation(coords.x, coords.z);
  }
}
