package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.Arrays;
import java.util.Random;

/**
 * Measurement vehicle for a two-level learned-state index: an Archimedean spiral over coarse outer
 * cells, with run-length-encoded Hilbert order inside each cell.
 *
 * <p><b>Test scope only.</b> Nothing here is registered, shipped, or referenced by main code. It
 * exists to produce the scaling coefficients a model decision needs, and it is deliberately
 * structured so the measured quantities map onto production ones rather than to be efficient.
 *
 * <p>Layering rationale, since it is the whole point of the shape:
 *
 * <ul>
 *   <li><b>Outer level</b> - the shipped {@link Square} spiral, sized to coarse cells rather than
 *       to blocks. At {@code outerEdge = 512} an outer cell is one Anvil region file, so a
 *       zero-good outer cell is a file that never gets opened. Coarsening the spiral by a factor
 *       {@code outerEdge} shrinks its domain by {@code outerEdge^2}, which is what makes the
 *       spiral's {@code atan} round trip a per-selection cost instead of a per-cell cost.
 *   <li><b>Directory</b> - a Fenwick tree of per-outer-cell good counts. Gives {@code O(log n)}
 *       weighted selection and {@code O(log n)} update, so a mark does not rebuild anything global.
 *   <li><b>Inner level</b> - one blob per outer cell: bad cells as coalesced runs over Hilbert
 *       order. Hilbert keeps a 2D neighbourhood contiguous in 1D in both directions, so a blob is
 *       both a compact encoding and a single self-contained unit of storage.
 * </ul>
 *
 * <p>Selection is exactly uniform over good cells by construction: the outer draw is weighted by
 * good count and the inner draw is uniform over that cell's good cells. There is no rejection
 * sampling and no scan for the k-th good cell beyond walking one blob's runs.
 *
 * <p><b>Deviations from what a production version would do</b>, so the figures are read as bounds
 * rather than as results: the outer spiral mapping is materialized into an {@code int[]} instead of
 * computed on demand; blobs are heap objects rather than a paged byte region; and nothing is
 * persisted, so the serialized-size figures are computed from run counts rather than from a file.
 */
public final class LayeredHilbertIndex {

  /** Bytes per stored run when serialized: 8-byte Hilbert start + 4-byte length. */
  private static final int RUN_BYTES = 12;

  /** Per-blob serialized header: run count + bad-cell count. */
  private static final int BLOB_HEADER_BYTES = 8;

  /** Bytes per outer cell held resident: one Fenwick slot plus one materialized mapping slot. */
  private static final int OUTER_CELL_BYTES = 8;

  private final int radius;
  private final int outerEdge;
  private final int innerRes;
  private final int outerCellsEdge;
  private final int outerCellCount;
  private final int innerCellsEdge;
  private final int innerOrder;
  private final int innerCellCount;

  /** Outer grid index (row-major) to spiral location. Bijective when spiral-addressed. */
  private final int[] toSpiral;

  /** Inverse of {@link #toSpiral}, materialized so selection stays off a linear search. */
  private final int[] fromSpiral;

  private final boolean spiralAddressed;

  /** Bad-run starts per outer cell, in Hilbert order, disjoint and ascending. */
  private final long[][] runStarts;

  /** Bad-run lengths, parallel to {@link #runStarts}. */
  private final int[][] runLengths;

  /** Staged marks awaiting a flush, per outer cell. */
  private final long[][] pending;

  private final int[] pendingSize;

  /**
   * Outer cells with staged marks, so a flush costs one pass over the dirty set rather than one
   * pass over the domain. Without this the reconciliation measurement is dominated by a scan of
   * every outer cell, which is an artifact of the vehicle and would misattribute domain-order cost
   * to the model.
   */
  private final java.util.LinkedHashSet<Integer> dirty = new java.util.LinkedHashSet<>();

  /** Bad cell count per outer cell, after the last flush. */
  private final int[] badCells;

  /**
   * Bad fraction at or above which an outer cell is collapsed: its run table is discarded and the
   * whole cell is treated as unusable. {@code 1.0} collapses only cells that are entirely bad,
   * which is lossless. Anything lower trades precision for bytes.
   */
  private final double collapseThreshold;

  /** Collapsed outer cells: no blob, no runs, never drawn, never read from storage. */
  private final boolean[] collapsed;

  /** Inner cells discarded by collapse that were never marked bad: the precision paid. */
  private long overExcludedCells;

  /** Fenwick tree over good counts, 1-based. */
  private final long[] fenwick;

  private final Random rng;

  private long totalGood;
  private long flushedCells;
  private long flushedBlobs;

  /**
   * @param radius half-edge of the domain in blocks; the domain is {@code 2 * radius} square
   * @param outerEdge blocks per outer cell edge, a power of two dividing {@code 2 * radius}
   * @param innerRes blocks per inner cell edge, a power of two dividing {@code outerEdge}; this is
   *     the precision lever, equivalent to truncating low-order Hilbert bits
   * @param seed selection seed, so every reported figure is reproducible
   */
  public LayeredHilbertIndex(int radius, int outerEdge, int innerRes, long seed) {
    this(radius, outerEdge, innerRes, seed, 1.0d);
  }

  /**
   * @param radius half-edge of the domain in blocks; the domain is {@code 2 * radius} square
   * @param outerEdge blocks per outer cell edge, a power of two dividing {@code 2 * radius}
   * @param innerRes blocks per inner cell edge, a power of two dividing {@code outerEdge}
   * @param seed selection seed, so every reported figure is reproducible
   * @param collapseThreshold bad fraction at or above which an outer cell stops carrying a run
   *     table and is excluded whole. At {@code 1.0} this is lossless - a cell is only collapsed
   *     once every inner cell in it is already known bad - and the saving is bounded by how much
   *     of the domain is uniformly unsafe. Below {@code 1.0} the remaining good cells of a
   *     collapsed cell are discarded, which is over-exclusion and is counted as such.
   */
  public LayeredHilbertIndex(
      int radius, int outerEdge, int innerRes, long seed, double collapseThreshold) {
    if (!(collapseThreshold > 0.0d) || collapseThreshold > 1.0d) {
      throw new IllegalArgumentException("collapseThreshold must be in (0, 1]");
    }
    this.collapseThreshold = collapseThreshold;
    if (radius <= 0 || Integer.bitCount(outerEdge) != 1 || Integer.bitCount(innerRes) != 1) {
      throw new IllegalArgumentException("radius > 0 and power-of-two edges required");
    }
    if (outerEdge < innerRes || (2 * radius) % outerEdge != 0) {
      throw new IllegalArgumentException("outerEdge must divide the domain and cover innerRes");
    }
    this.radius = radius;
    this.outerEdge = outerEdge;
    this.innerRes = innerRes;
    this.outerCellsEdge = (2 * radius) / outerEdge;
    this.outerCellCount = outerCellsEdge * outerCellsEdge;
    this.innerCellsEdge = outerEdge / innerRes;
    this.innerOrder = Integer.numberOfTrailingZeros(innerCellsEdge);
    this.innerCellCount = innerCellsEdge * innerCellsEdge;
    this.rng = new Random(seed);

    this.runStarts = new long[outerCellCount][];
    this.runLengths = new int[outerCellCount][];
    this.pending = new long[outerCellCount][];
    this.pendingSize = new int[outerCellCount];
    this.badCells = new int[outerCellCount];
    this.collapsed = new boolean[outerCellCount];
    this.fenwick = new long[outerCellCount + 1];

    this.toSpiral = new int[outerCellCount];
    this.fromSpiral = new int[outerCellCount];
    this.spiralAddressed = buildSpiralMapping();
    for (int grid = 0; grid < outerCellCount; grid++) {
      fromSpiral[toSpiral[grid]] = grid;
    }

    for (int i = 0; i < outerCellCount; i++) {
      fenwickAdd(i, innerCellCount);
    }
    this.totalGood = (long) outerCellCount * innerCellCount;
  }

  // -------------------------------------------------------------------------------------
  // outer addressing
  // -------------------------------------------------------------------------------------

  /**
   * Materializes outer-grid to spiral-location mapping using the shipped spiral math at coarse
   * scale, and verifies it is a bijection.
   *
   * @return {@code true} when the spiral mapping is bijective over the coarse grid; {@code false}
   *     after falling back to row-major order, which changes only expansion order and not any
   *     footprint or reconciliation figure
   */
  private boolean buildSpiralMapping() {
    Square coarse = new Square();
    coarse.set(GenericMemoryShapeParams.radius, (long) (outerCellsEdge / 2));
    boolean[] seen = new boolean[outerCellCount];
    int half = outerCellsEdge / 2;
    for (int ox = 0; ox < outerCellsEdge; ox++) {
      for (int oz = 0; oz < outerCellsEdge; oz++) {
        long loc = coarse.xzToLocation(ox - half, oz - half);
        if (loc < 0L || loc >= outerCellCount || seen[(int) loc]) {
          for (int i = 0; i < outerCellCount; i++) {
            toSpiral[i] = i;
          }
          return false;
        }
        seen[(int) loc] = true;
        toSpiral[ox * outerCellsEdge + oz] = (int) loc;
      }
    }
    return true;
  }

  /** @return {@code true} when outer cells are addressed in spiral order */
  public boolean spiralAddressed() {
    return spiralAddressed;
  }

  /** @return outer cell index for a block coordinate pair, or {@code -1} when out of domain */
  private int outerIndex(int x, int z) {
    int ox = Math.floorDiv(x + radius, outerEdge);
    int oz = Math.floorDiv(z + radius, outerEdge);
    if (ox < 0 || oz < 0 || ox >= outerCellsEdge || oz >= outerCellsEdge) return -1;
    return toSpiral[ox * outerCellsEdge + oz];
  }

  // -------------------------------------------------------------------------------------
  // Hilbert curve
  // -------------------------------------------------------------------------------------

  /**
   * Hilbert index of a point in an {@code order}-bit square grid.
   *
   * @return distance along the curve, in {@code [0, 4^order)}
   */
  static long hilbertIndex(int order, int x, int y) {
    if (order <= 0) return 0L;
    long d = 0L;
    int px = x;
    int py = y;
    for (long s = 1L << (order - 1); s > 0L; s >>= 1) {
      long rx = (px & s) > 0L ? 1L : 0L;
      long ry = (py & s) > 0L ? 1L : 0L;
      d += s * s * ((3L * rx) ^ ry);
      if (ry == 0L) {
        if (rx == 1L) {
          px = (int) (s - 1L - px);
          py = (int) (s - 1L - py);
        }
        int t = px;
        px = py;
        py = t;
      }
    }
    return d;
  }

  /**
   * Inverse of {@link #hilbertIndex(int, int, int)}.
   *
   * @return {@code x} in the low 32 bits and {@code y} in the high 32 bits
   */
  static long hilbertPoint(int order, long d) {
    int x = 0;
    int y = 0;
    long t = d;
    for (long s = 1L; s < (1L << order); s <<= 1) {
      long rx = 1L & (t / 2L);
      long ry = 1L & (t ^ rx);
      if (ry == 0L) {
        if (rx == 1L) {
          x = (int) (s - 1L - x);
          y = (int) (s - 1L - y);
        }
        int tmp = x;
        x = y;
        y = tmp;
      }
      x += (int) (s * rx);
      y += (int) (s * ry);
      t /= 4L;
    }
    return (((long) y) << 32) | (x & 0xFFFFFFFFL);
  }

  // -------------------------------------------------------------------------------------
  // Fenwick directory
  // -------------------------------------------------------------------------------------

  private void fenwickAdd(int index, long delta) {
    for (int i = index + 1; i <= outerCellCount; i += i & (-i)) {
      fenwick[i] += delta;
    }
  }

  /**
   * Smallest outer index whose inclusive prefix of good counts exceeds {@code target}.
   *
   * @return outer index, with {@code target} rewritten by the caller into an intra-cell rank
   */
  private int fenwickSelect(long target) {
    int pos = 0;
    long remaining = target;
    for (int step = Integer.highestOneBit(outerCellCount); step > 0; step >>= 1) {
      int next = pos + step;
      if (next <= outerCellCount && fenwick[next] <= remaining) {
        pos = next;
        remaining -= fenwick[next];
      }
    }
    return pos;
  }

  private long prefixGood(int exclusiveIndex) {
    long sum = 0L;
    for (int i = exclusiveIndex; i > 0; i -= i & (-i)) {
      sum += fenwick[i];
    }
    return sum;
  }

  // -------------------------------------------------------------------------------------
  // marks
  // -------------------------------------------------------------------------------------

  /**
   * Stages one bad block coordinate. Coordinates inside the same inner cell collapse, which is the
   * precision lever rather than an accident.
   *
   * @return {@code true} when the coordinate is inside the domain and was staged
   */
  public boolean addBadLocation(int x, int z) {
    int oi = outerIndex(x, z);
    if (oi < 0) return false;
    // A collapsed cell has no run table to update and no good cell left to remove, so the mark is
    // already implied. Dropping it here is why collapse cuts reconciliation work and not only
    // bytes.
    if (collapsed[oi]) return true;
    int localX = Math.floorMod(x + radius, outerEdge) / innerRes;
    int localZ = Math.floorMod(z + radius, outerEdge) / innerRes;
    long h = innerOrder == 0 ? 0L : hilbertIndex(innerOrder, localX, localZ);

    long[] buf = pending[oi];
    if (buf == null) {
      buf = new long[8];
      pending[oi] = buf;
    } else if (pendingSize[oi] == buf.length) {
      buf = Arrays.copyOf(buf, buf.length * 2);
      pending[oi] = buf;
    }
    buf[pendingSize[oi]++] = h;
    dirty.add(oi);
    return true;
  }

  /**
   * Reconciles staged marks. Only outer cells with staged marks are touched, so the cost is a
   * function of how many cells were marked and not of the domain - which is the property being
   * measured.
   *
   * @return number of outer cells rebuilt
   */
  public int flush() {
    if (dirty.isEmpty()) return 0;
    int rebuilt = 0;
    for (int oi : dirty) {
      if (pendingSize[oi] == 0) continue;
      rebuildBlob(oi);
      rebuilt++;
    }
    dirty.clear();
    flushedBlobs += rebuilt;
    return rebuilt;
  }

  private void rebuildBlob(int oi) {
    long[] adds = Arrays.copyOf(pending[oi], pendingSize[oi]);
    pendingSize[oi] = 0;
    Arrays.sort(adds);

    long[] oldStarts = runStarts[oi];
    int[] oldLengths = runLengths[oi];
    int oldCount = oldStarts == null ? 0 : oldStarts.length;

    // Interval merge: existing runs and staged singletons are both ascending, so one pass
    // produces the coalesced result. Adjacent runs merge, which is where the encoding wins.
    long[] outStarts = new long[oldCount + adds.length];
    int[] outLengths = new int[oldCount + adds.length];
    int outCount = 0;
    int ai = 0;
    int ri = 0;
    long curStart = -1L;
    long curEnd = -1L;
    while (ai < adds.length || ri < oldCount) {
      long start;
      long end;
      boolean takeRun =
          ri < oldCount && (ai >= adds.length || oldStarts[ri] <= adds[ai]);
      if (takeRun) {
        start = oldStarts[ri];
        end = start + oldLengths[ri];
        ri++;
      } else {
        start = adds[ai];
        end = start + 1L;
        ai++;
      }
      if (curStart < 0L) {
        curStart = start;
        curEnd = end;
      } else if (start <= curEnd) {
        curEnd = Math.max(curEnd, end);
      } else {
        outStarts[outCount] = curStart;
        outLengths[outCount] = (int) (curEnd - curStart);
        outCount++;
        curStart = start;
        curEnd = end;
      }
    }
    if (curStart >= 0L) {
      outStarts[outCount] = curStart;
      outLengths[outCount] = (int) (curEnd - curStart);
      outCount++;
    }

    runStarts[oi] = Arrays.copyOf(outStarts, outCount);
    runLengths[oi] = Arrays.copyOf(outLengths, outCount);

    int bad = 0;
    for (int i = 0; i < outCount; i++) {
      bad += runLengths[oi][i];
    }
    int oldBad = badCells[oi];
    if (bad >= collapseCeiling()) {
      // Discard the run table entirely and exclude the cell whole. Any good cells left in it are
      // given up, which is the precision this lever costs and is counted rather than hidden.
      overExcludedCells += innerCellCount - bad;
      collapsed[oi] = true;
      runStarts[oi] = null;
      runLengths[oi] = null;
      bad = innerCellCount;
    }
    badCells[oi] = bad;
    long delta = (long) oldBad - bad;
    if (delta != 0L) {
      fenwickAdd(oi, delta);
      totalGood += delta;
    }
    flushedCells += innerCellCount;
  }


  // -------------------------------------------------------------------------------------
  // selection
  // -------------------------------------------------------------------------------------

  /**
   * Draws one cell uniformly at random from the good cells of the whole domain.
   *
   * @return packed block coordinates, {@code x} in the low 32 bits and {@code z} in the high 32
   *     bits; {@code Long.MIN_VALUE} when no good cell remains
   */
  public long rand() {
    if (totalGood <= 0L) return Long.MIN_VALUE;
    long target = Math.floorMod(rng.nextLong(), totalGood);
    int oi = fenwickSelect(target);
    if (oi >= outerCellCount) oi = outerCellCount - 1;
    long rank = target - prefixGood(oi);

    long h = kthGoodHilbert(oi, rank);
    long point = hilbertPoint(innerOrder, h);
    int localX = (int) (point & 0xFFFFFFFFL);
    int localZ = (int) (point >>> 32);

    int grid = fromSpiral[oi];
    int ox = grid / outerCellsEdge;
    int oz = grid % outerCellsEdge;
    int baseX = ox * outerEdge - radius + localX * innerRes;
    int baseZ = oz * outerEdge - radius + localZ * innerRes;
    int x = baseX + (innerRes == 1 ? 0 : rng.nextInt(innerRes));
    int z = baseZ + (innerRes == 1 ? 0 : rng.nextInt(innerRes));
    return (((long) z) << 32) | (x & 0xFFFFFFFFL);
  }

  /** Walks one blob's runs to find the {@code rank}-th good inner cell. */
  private long kthGoodHilbert(int oi, long rank) {
    long[] starts = runStarts[oi];
    int[] lengths = runLengths[oi];
    long cursor = 0L;
    long remaining = rank;
    if (starts != null) {
      for (int i = 0; i < starts.length; i++) {
        long gap = starts[i] - cursor;
        if (remaining < gap) return cursor + remaining;
        remaining -= gap;
        cursor = starts[i] + lengths[i];
      }
    }
    return cursor + remaining;
  }


  // -------------------------------------------------------------------------------------
  // accounting
  // -------------------------------------------------------------------------------------

  /** @return good cells remaining across the domain */
  public long totalGood() {
    return totalGood;
  }

  /** @return bad cells recorded across the domain, at inner-cell precision */
  public long totalBad() {
    long sum = 0L;
    for (int b : badCells) {
      sum += b;
    }
    return sum;
  }

  /** @return total stored runs across every blob */
  public int totalRuns() {
    int sum = 0;
    for (long[] starts : runStarts) {
      if (starts != null) sum += starts.length;
    }
    return sum;
  }

  /** @return outer cells with at least one run, i.e. blobs that must exist at all */
  public int occupiedBlobs() {
    int count = 0;
    for (long[] starts : runStarts) {
      if (starts != null && starts.length > 0) count++;
    }
    return count;
  }

  /**
   * Inner cells a collapse must contain before it fires. Rounded up, so a threshold of {@code 1.0}
   * requires literally every inner cell to be bad and stays lossless.
   */
  private int collapseCeiling() {
    int ceiling = (int) Math.ceil(collapseThreshold * innerCellCount);
    return Math.max(1, Math.min(innerCellCount, ceiling));
  }

  /** @return collapsed outer cells: one bit each, no blob, never read */
  public int collapsedOuterCells() {
    int count = 0;
    for (boolean c : collapsed) {
      if (c) count++;
    }
    return count;
  }

  /** @return outer cells still carrying a run table, i.e. the only ones costing directory bytes */
  public int mixedOuterCells() {
    return outerCellCount - collapsedOuterCells();
  }

  /**
   * Inner cells excluded by collapse that were not known bad. Zero at a threshold of {@code 1.0}
   * by construction; above zero this is the precision paid for the byte saving.
   *
   * @return over-excluded inner cells
   */
  public long overExcludedCells() {
    return overExcludedCells;
  }

  /**
   * Directory bytes once collapsed cells are demoted to one bit in a dense bitmap over the outer
   * grid, with the Fenwick tree carrying only the surviving cells. This is the accounting the
   * collapse lever is measured against; {@link #residentDirectoryBytes()} keeps the uncollapsed
   * form so earlier rows stay comparable.
   *
   * @return resident bytes of the collapsed directory
   */
  public long collapsedDirectoryBytes() {
    long bitmap = (outerCellCount + 7L) / 8L;
    return bitmap + (long) mixedOuterCells() * OUTER_CELL_BYTES;
  }

  /** @return resident bytes of the collapsed directory plus every surviving blob */
  public long collapsedFullyResidentBytes() {
    return collapsedDirectoryBytes() + blobBytes();
  }

  /** @return outer cells with zero good cells: storage that never needs to be consulted */
  public int deadOuterCells() {
    int count = 0;
    for (int b : badCells) {
      if (b >= innerCellCount) count++;
    }
    return count;
  }

  /** @return outer cell count */
  public int outerCellCount() {
    return outerCellCount;
  }

  /** @return inner cells per outer cell */
  public int innerCellCount() {
    return innerCellCount;
  }

  /**
   * Bytes that must stay resident for selection to work without touching storage: the directory
   * only. Blobs are excluded because they are the pageable half of the design.
   */
  public long residentDirectoryBytes() {
    return (long) outerCellCount * OUTER_CELL_BYTES;
  }

  /** @return serialized bytes of every blob, i.e. the pageable half */
  public long blobBytes() {
    long bytes = 0L;
    for (long[] starts : runStarts) {
      if (starts == null || starts.length == 0) continue;
      bytes += BLOB_HEADER_BYTES + (long) starts.length * RUN_BYTES;
    }
    return bytes;
  }

  /** @return resident bytes when every blob is held in memory: the fully-resident upper bound */
  public long fullyResidentBytes() {
    return residentDirectoryBytes() + blobBytes();
  }

  /** @return mean serialized bytes of an occupied blob, the natural unit of one storage read */
  public double meanBlobBytes() {
    int blobs = occupiedBlobs();
    return blobs == 0 ? 0.0d : blobBytes() / (double) blobs;
  }

  /** @return inner cells covered by one blob read, i.e. candidates served per storage operation */
  public int candidatesPerBlobRead() {
    return innerCellCount;
  }

  /** @return inner cells rebuilt across every flush so far, the reconciliation work performed */
  public long flushedCells() {
    return flushedCells;
  }

  /** @return blob rebuilds performed across every flush so far */
  public long flushedBlobs() {
    return flushedBlobs;
  }
}
