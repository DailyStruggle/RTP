package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

/**
 * Sample vehicle for a locality-preserving 1D key: the shipped Archimedean square spiral, addressed
 * at a coarse point, with each point's share of the key space expanded in place into that point's
 * Hilbert traversal of the chunks inside it.
 *
 * <p>The key space stays a single contiguous {@code [0, range)} interval of longs, so every piece of
 * shipped machinery that operates on 1D keys is inherited unchanged - {@code addBadLocation},
 * probation, {@code spatialResolution} coalescing, {@code flushAndRebuild}, {@code badSum}, {@code
 * adjustRange}, {@code sample}, {@code resolve}, {@code nearestGood}, {@code expand}, {@code mode},
 * and selection itself. Only the bijection changes:
 *
 * <pre>key = spiralIndex(point) * P^2 + hilbertIndex(chunk within point)</pre>
 *
 * <h2>What this is for</h2>
 *
 * Every coarsening lever measured before this one bought bytes by <b>discarding precision</b>: a
 * cell became bad when any chunk inside it was bad, which at a bad density of 0.751 throws away
 * roughly half of all usable ground at one region file per cell. This vehicle buys bytes a different
 * way. The addressed unit stays <b>one chunk</b> - the finest unit the safety signal can distinguish,
 * since selection places a candidate at a chunk centre - and the saving is expected to come from run
 * count instead: under the plain spiral a compact 2D feature is cut once per revolution, so runs
 * scale with the feature's linear extent, whereas a feature contained inside one point costs O(1)
 * runs under the Hilbert traversal regardless of its area.
 *
 * <p>{@code spatialResolution} therefore keeps its existing meaning exactly - it is still a
 * coalescing gap in 1D key units - and becomes the lossy lever, applied to a curve whose 1D
 * adjacency corresponds much more closely to 2D adjacency. That is the comparison the accompanying
 * benchmark makes: the same knob, the same semantics, a different shape.
 *
 * <h2>Deviations, stated rather than discovered later</h2>
 *
 * <ul>
 *   <li><b>Point orientation is fixed, not seam-matched.</b> Consecutive spiral points are 2D
 *       adjacent, but the exit corner of one point's Hilbert curve is not generally adjacent to the
 *       entry corner of the next, so a run crossing a point boundary merges only by luck. Choosing
 *       each point's orientation from the eight symmetries of the square to match the previous point
 *       would raise the merge rate; it is deliberately not done here, so the measured figures are a
 *       <b>lower bound</b> on what the design can achieve.
 *   <li><b>Coarse addressing is delegated to a configured {@link Square}</b> rather than
 *       reimplemented, so the ring arithmetic under test is the shipped arithmetic and cannot drift
 *       from it.
 *   <li><b>The centre annulus is not modelled.</b> {@code centerRadius} is pinned to zero on the
 *       delegate; a coarse point straddling an inner boundary would need partial admission, which is
 *       an {@code expand} granularity question and not what is being measured.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier, excluded from {@code build}. Nothing here is
 * registered or referenced by shipped code; the D-005 gate is closed.
 */
public final class SpiralHilbertSquare extends Square {

  /** Chunks per point edge. Power of two, so the Hilbert order is a bit count. */
  private final int pointChunks;

  /** Hilbert order, i.e. {@code log2(pointChunks)}. */
  private final int order;

  /** Chunks per point, the size of one point's slice of the key space. */
  private final long pointCells;

  /**
   * Whether each point's Hilbert traversal is rotated to meet the spiral's local travel direction.
   *
   * <p>False reproduces the first vehicle: every point uses the canonical orientation, so a run
   * crossing a point boundary merges only by luck. True aligns the traversal's entry-to-exit axis
   * with the direction the spiral is moving on that ring side, so the exit corner of one point is
   * adjacent to the entry corner of the next along the three straight sides of every ring.
   */
  private final boolean seamMatched;

  /** Shipped spiral, configured over the coarse point grid. Supplies the outer ordering. */
  private final Square coarse;

  private final int radiusChunks;

  /**
   * Canonical orientation, i.e. the first vehicle. Retained so the seam cost is measurable as a
   * difference against it rather than asserted.
   *
   * @param radiusChunks addressed radius, in chunks
   * @param pointChunks chunks per point edge; must be a power of two
   */
  public SpiralHilbertSquare(int radiusChunks, int pointChunks) {
    this(radiusChunks, pointChunks, false);
  }

  /**
   * @param radiusChunks addressed radius, in chunks
   * @param pointChunks chunks per point edge; must be a power of two
   * @param seamMatched align each point's traversal with the spiral's local travel direction
   */
  public SpiralHilbertSquare(int radiusChunks, int pointChunks, boolean seamMatched) {
    super("SPIRAL_HILBERT");
    if (Integer.bitCount(pointChunks) != 1) {
      throw new IllegalArgumentException("pointChunks must be a power of two: " + pointChunks);
    }
    this.seamMatched = seamMatched;
    this.pointChunks = pointChunks;
    this.order = Integer.numberOfTrailingZeros(pointChunks);
    this.pointCells = (long) pointChunks * pointChunks;
    this.radiusChunks = radiusChunks;

    int coarseRadius = Math.max(1, ceilDiv(radiusChunks, pointChunks));
    this.coarse = new Square("SPIRAL_HILBERT_POINTS");
    this.coarse.set(GenericMemoryShapeParams.radius, (long) coarseRadius);
    this.coarse.set(GenericMemoryShapeParams.centerRadius, 0L);
    this.coarse.set(GenericMemoryShapeParams.centerX, 0L);
    this.coarse.set(GenericMemoryShapeParams.centerZ, 0L);

    set(GenericMemoryShapeParams.radius, (long) radiusChunks);
    set(GenericMemoryShapeParams.centerRadius, 0L);
    set(GenericMemoryShapeParams.centerX, 0L);
    set(GenericMemoryShapeParams.centerZ, 0L);
  }

  private static int ceilDiv(int a, int b) {
    return (a + b - 1) / b;
  }

  /** @return chunks per point edge */
  public int pointChunks() {
    return pointChunks;
  }

  /** @return whether point orientation is matched to the spiral's travel direction */
  public boolean seamMatched() {
    return seamMatched;
  }

  /** @return addressed radius, in chunks */
  public int radiusChunks() {
    return radiusChunks;
  }

  @Override
  public long getRange() {
    return coarse.getRange() * pointCells;
  }

  /**
   * Offset between same-angle cells on adjacent <i>point</i> rings, scaled into chunk keys.
   *
   * <p>The inherited value describes the plain spiral's ring spacing, which is not the spacing of
   * this key space: one point ring is {@code 8R} points, each holding {@code P^2} keys. Coalescing
   * and probation adjacency both consult this, so leaving it inherited would have them reason about
   * a curve that is not in use.
   */
  @Override
  protected long neighbourRingOffset(int cx, int cz) {
    long px = Math.floorDiv(cx, pointChunks);
    long pz = Math.floorDiv(cz, pointChunks);
    long r = Math.max(Math.abs(px), Math.abs(pz));
    if (r <= 0L) return pointCells;
    return 8L * r * pointCells;
  }

  @Override
  public long xzToLocation(long x, long z) {
    long px = Math.floorDiv(x, pointChunks);
    long pz = Math.floorDiv(z, pointChunks);
    long pointIndex = coarse.xzToLocation(px, pz);
    if (pointIndex < 0L) return pointIndex;
    int lx = (int) (x - (px * pointChunks));
    int lz = (int) (z - (pz * pointChunks));
    if (seamMatched) {
      int[] o = orientForward(lx, lz, travelDirection(px, pz), pointChunks);
      lx = o[0];
      lz = o[1];
    }
    return (pointIndex * pointCells) + hilbertIndex(lx, lz, order);
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    return xzToLocation(coords.x, coords.z);
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords out = new MutableRTPCoords(0, 0);
    locationToXZ(location, out);
    return new int[] {out.x, out.z};
  }

  @Override
  public void locationToXZ(long location, MutableRTPCoords output) {
    long pointIndex = Math.floorDiv(location, pointCells);
    long local = location - (pointIndex * pointCells);
    coarse.locationToXZ(pointIndex, output);
    int[] d = hilbertCoords(local, order);
    int lx = d[0];
    int lz = d[1];
    if (seamMatched) {
      int dir = travelDirection(output.x, output.z);
      int[] back = orientInverse(lx, lz, dir, pointChunks);
      lx = back[0];
      lz = back[1];
    }
    output.setXZ((output.x * pointChunks) + lx, (output.z * pointChunks) + lz);
  }

  // -------------------------------------------------------------------------------------
  // Seam matching
  // -------------------------------------------------------------------------------------

  /**
   * Direction the shipped square spiral is travelling as it passes through point {@code (px, pz)}.
   *
   * <p>Read off the shipped octant decomposition: the ring is walked counter-clockwise from
   * {@code (r, 0)}, so travel is {@code +z} on the east side, {@code -x} on the north, {@code -z}
   * on the west and {@code +x} on the south. Derived from the coordinates rather than stored, so
   * the bijection stays a closed-form pair of functions and needs no side table.
   *
   * <p>At the four ring corners the direction changes within the point, so whichever value is
   * returned is right for one of the two adjacent points and wrong for the other. Four points per
   * ring are therefore unmatched by construction - which is why matching raises the merge rate
   * rather than guaranteeing it.
   *
   * @return 0 for {@code +x}, 1 for {@code +z}, 2 for {@code -x}, 3 for {@code -z}
   */
  static int travelDirection(long px, long pz) {
    long r = Math.max(Math.abs(px), Math.abs(pz));
    if (r == 0L) return 0;
    if (pz == r) return 2;
    if (px == -r) return 3;
    if (pz == -r) return 0;
    return 1;
  }

  /**
   * Maps a point-local coordinate into the orientation whose Hilbert entry and exit corners lie on
   * the {@code dir} axis.
   *
   * <p>The canonical curve runs from {@code (0, 0)} to {@code (size - 1, 0)}, so its axis is
   * {@code +x}; the other three cases are the reflections and transposition that carry that axis
   * onto the required direction. All eight symmetries of the square preserve 4-adjacency, so the
   * traversal stays unit-step continuous and the map stays a bijection.
   */
  static int[] orientForward(int lx, int lz, int dir, int size) {
    return switch (dir) {
      case 1 -> new int[] {lz, lx};
      case 2 -> new int[] {size - 1 - lx, lz};
      case 3 -> new int[] {size - 1 - lz, lx};
      default -> new int[] {lx, lz};
    };
  }

  /** Inverse of {@link #orientForward(int, int, int, int)}. */
  static int[] orientInverse(int u, int v, int dir, int size) {
    return switch (dir) {
      case 1 -> new int[] {v, u};
      case 2 -> new int[] {size - 1 - u, v};
      case 3 -> new int[] {v, size - 1 - u};
      default -> new int[] {u, v};
    };
  }

  // -------------------------------------------------------------------------------------
  // Hilbert curve, order n over a 2^n square
  // -------------------------------------------------------------------------------------

  /**
   * Index of {@code (x, z)} along the order-{@code n} Hilbert curve.
   *
   * <p>Standard rotation/reflection form: at each level the quadrant is selected by the current
   * high bits and the remaining coordinates are folded into the canonical orientation. Chosen over
   * a lookup table because the order changes with the point size, and over Morton order because
   * Morton's key adjacency breaks at every power-of-two boundary, which is exactly the fragmentation
   * this vehicle exists to remove.
   */
  static long hilbertIndex(int x, int z, int n) {
    int size = 1 << n;
    long index = 0L;
    for (int s = size >> 1; s > 0; s >>= 1) {
      int rx = (x & s) > 0 ? 1 : 0;
      int rz = (z & s) > 0 ? 1 : 0;
      index += (long) s * s * ((3 * rx) ^ rz);
      // Rotate the remaining subsquare into canonical orientation. The reflection is about the
      // full square, not the current subsquare - using s here is the classic transcription error
      // and yields a map that is no longer a bijection.
      if (rz == 0) {
        if (rx == 1) {
          x = size - 1 - x;
          z = size - 1 - z;
        }
        int t = x;
        x = z;
        z = t;
      }
    }
    return index;
  }

  /**
   * Inverse of {@link #hilbertIndex(int, int, int)}.
   *
   * @return {@code {x, z}} within the {@code 2^n} square
   */
  static int[] hilbertCoords(long index, int n) {
    int x = 0;
    int z = 0;
    for (int s = 1; s < (1 << n); s <<= 1) {
      int rx = (int) ((index >> 1) & 1L);
      int rz = (int) ((index ^ rx) & 1L);
      if (rz == 0) {
        if (rx == 1) {
          x = s - 1 - x;
          z = s - 1 - z;
        }
        int t = x;
        x = z;
        z = t;
      }
      x += s * rx;
      z += s * rz;
      index >>= 2;
    }
    return new int[] {x, z};
  }
}
