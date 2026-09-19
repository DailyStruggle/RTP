package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;

import java.util.EnumMap;
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

  private final int pointEdgeChunks;
  private final int pointArea;
  private final Square macroSquare;

  private volatile SegmentedKeyRunTable segmentedTable;

  public CircleOptimizedDualLayer() {
    this("CIRCLE_OPTIMIZED_DUAL_LAYER", 32);
  }

  public CircleOptimizedDualLayer(String name) {
    this(name, 32);
  }

  public CircleOptimizedDualLayer(String name, int pointEdgeChunks) {
    super(name);
    if (Integer.bitCount(pointEdgeChunks) != 1) {
      throw new IllegalArgumentException("pointEdgeChunks must be a power of two: " + pointEdgeChunks);
    }
    this.pointEdgeChunks = pointEdgeChunks;
    this.pointArea = pointEdgeChunks * pointEdgeChunks;
    this.macroSquare = new Square("MACRO_" + name);
  }

  private void configureMacroSquare(long macroRadius, long macroCenter) {
    EnumMap<GenericMemoryShapeParams, Object> map = new EnumMap<>(GenericMemoryShapeParams.class);
    map.put(GenericMemoryShapeParams.radius, (Object) macroRadius);
    map.put(GenericMemoryShapeParams.centerRadius, (Object) macroCenter);
    map.put(GenericMemoryShapeParams.centerX, (Object) 0L);
    map.put(GenericMemoryShapeParams.centerZ, (Object) 0L);
    macroSquare.setData(map);
  }

  @Override
  public String getCurveName() {
    return CURVE_SPIRAL_HILBERT;
  }

  @Override
  public int getPointEdgeChunks() {
    return pointEdgeChunks;
  }

  private static long computeMacroRadius(long r, int pointEdgeChunks) {
    long minPx = Math.floorDiv(-r, (long) pointEdgeChunks);
    long maxPx = Math.floorDiv(r, (long) pointEdgeChunks);
    long maxChebyshev = Math.max(Math.abs(minPx), Math.abs(maxPx));
    return maxChebyshev + 1L;
  }

  @Override
  public long getRange() {
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long macroRadius = computeMacroRadius(r, pointEdgeChunks);
    long macroCenter = (long) Math.floor((cr / Math.sqrt(2.0)) / pointEdgeChunks);
    configureMacroSquare(macroRadius, macroCenter);
    return macroSquare.getRange() * pointArea;
  }

  @Override
  public long xzToLocation(long cx, long cz) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long relX = cx - cenX;
    long relZ = cz - cenZ;

    long distSq = relX * relX + relZ * relZ;
    if (distSq > r * r || distSq < cr * cr) {
      return -1L;
    }

    long macroRadius = computeMacroRadius(r, pointEdgeChunks);
    long macroCenter = (long) Math.floor((cr / Math.sqrt(2.0)) / pointEdgeChunks);
    configureMacroSquare(macroRadius, macroCenter);

    long px = Math.floorDiv(relX, pointEdgeChunks);
    long pz = Math.floorDiv(relZ, pointEdgeChunks);

    long macroLoc = macroSquare.xzToLocation(px, pz);
    if (macroLoc < 0) return -1L;

    int lx = (int) (relX - px * pointEdgeChunks);
    int lz = (int) (relZ - pz * pointEdgeChunks);

    int orientation = orientationFor(px, pz);
    long h = xyToHilbert(lx, lz, pointEdgeChunks, orientation);
    return macroLoc * pointArea + h;
  }

  @Override
  public void locationToXZ(long loc, MutableRTPCoords output) {
    if (loc < 0) {
      if (output != null) output.setXZ(0, 0);
      return;
    }

    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long macroRadius = computeMacroRadius(r, pointEdgeChunks);
    long macroCenter = (long) Math.floor((cr / Math.sqrt(2.0)) / pointEdgeChunks);
    configureMacroSquare(macroRadius, macroCenter);

    long macroLoc = loc / pointArea;
    long h = loc % pointArea;

    macroSquare.locationToXZ(macroLoc, output);
    if (output == null) return;

    long px = output.x;
    long pz = output.z;

    int orientation = orientationFor(px, pz);
    int[] local = hilbertToXY((int) h, pointEdgeChunks, orientation);

    long cx = cenX + px * pointEdgeChunks + local[0];
    long cz = cenZ + pz * pointEdgeChunks + local[1];

    output.setXZ((int) cx, (int) cz);
  }

  @Override
  public boolean contains(int x, int z) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long relX = (long) x - cenX;
    long relZ = (long) z - cenZ;
    long distSq = relX * relX + relZ * relZ;

    if (distSq > r * r || distSq < cr * cr) {
      return false;
    }

    return super.contains(x, z);
  }

  private final long secretKey = ThreadLocalRandom.current().nextLong();
  private final java.util.concurrent.atomic.AtomicLong selectionCounter = new java.util.concurrent.atomic.AtomicLong(0);

  @Override
  public long rand() {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long r = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cenX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cenZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long crSq = cr * cr;
    long rSq = r * r;

    long range = getRange();
    if (range <= 0) return -1L;

    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    if (MODE_ACCUMULATE.equals(mode())) {
      SegmentedKeyRunTable table = getOrBuildSegmentedTable(range);
      long totalGood = range - table.totalCovered();
      if (totalGood <= 0) return -1L;

      int stride = SquareOptimizedDualLayer.deriveAdaptiveStride(totalGood);
      int bits = Integer.numberOfTrailingZeros(stride);

      for (int attempts = 0; attempts < 5; attempts++) {
        long t = selectionCounter.getAndIncrement();
        int subsetIdx = (int) (t % stride);
        int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

        long subsetSize = phaseOffset < totalGood ? (totalGood - 1 - phaseOffset) / stride + 1 : 0;
        if (subsetSize <= 0) {
          return table.resolveAccumulate(t % totalGood);
        }

        long kCounter = t / stride;
        long permutedK = feistelPermute(kCounter, subsetSize, secretKey ^ (phaseOffset * 0x9E3779B97F4A7C15L));
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

    // Standard / Default mode: Adaptive dyadic stride
    int stride = SquareOptimizedDualLayer.deriveAdaptiveStride(range);
    int bits = Integer.numberOfTrailingZeros(stride);

    for (int attempts = 0; attempts < 10; attempts++) {
      long t = selectionCounter.getAndIncrement();
      int subsetIdx = (int) (t % stride);
      int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

      long subsetSize = phaseOffset < range ? (range - 1 - phaseOffset) / stride + 1 : 0;
      if (subsetSize <= 0) continue;

      long kCounter = t / stride;
      long permutedK = feistelPermute(kCounter, subsetSize, secretKey ^ (phaseOffset * 0x9E3779B97F4A7C15L));
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

  private synchronized SegmentedKeyRunTable getOrBuildSegmentedTable(long range) {
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

  private int orientationFor(long px, long pz) {
    long maxCoord = Math.max(Math.abs(px), Math.abs(pz));
    if (maxCoord == 0) return 0;
    if (px == maxCoord && pz > -maxCoord) return 0;
    if (pz == maxCoord && px < maxCoord) return 2;
    if (px == -maxCoord && pz < maxCoord) return 4;
    return 6;
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

  private static int[] applyOrientation(int x, int y, int n, int o) {
    int max = n - 1;
    return switch (o % 8) {
      case 0 -> new int[] {x, y};
      case 1 -> new int[] {y, x};
      case 2 -> new int[] {max - y, x};
      case 3 -> new int[] {max - x, y};
      case 4 -> new int[] {max - x, max - y};
      case 5 -> new int[] {max - y, max - x};
      case 6 -> new int[] {y, max - x};
      case 7 -> new int[] {x, max - y};
      default -> new int[] {x, y};
    };
  }

  private static int[] unapplyOrientation(int x, int y, int n, int o) {
    int max = n - 1;
    return switch (o % 8) {
      case 0 -> new int[] {x, y};
      case 1 -> new int[] {y, x};
      case 2 -> new int[] {y, max - x};
      case 3 -> new int[] {max - x, y};
      case 4 -> new int[] {max - x, max - y};
      case 5 -> new int[] {max - y, max - x};
      case 6 -> new int[] {max - y, x};
      case 7 -> new int[] {x, max - y};
      default -> new int[] {x, y};
    };
  }
}
