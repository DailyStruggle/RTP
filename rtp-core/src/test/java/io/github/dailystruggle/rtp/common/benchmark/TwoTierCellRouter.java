package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADR-083 two-tier hierarchical cell router - <b>measurement vehicle only</b>.
 *
 * <p>This class deliberately lives in the opt-in benchmark tier (ADR-080) under {@code
 * src/test}, not in {@code rtp-core}'s shipped shape package. ADR-083 is <b>Proposed</b> and its
 * D-005 approval gate is not met; nothing here is a production shape, is reachable from
 * configuration, or is registered with any factory. It exists to answer one question - does the
 * model hold up on real data - before any decision about shipping it is taken.
 *
 * <p>Scope, per ADR-083 section 8: read-only, in-memory, {@code (r, M)}-parameterized. No
 * persistence, no learned-state writes, no descriptor format.
 *
 * <p>Integration follows ADR-083 section 5: it extends the shipped {@link Square} so
 * {@code xzToLocation} / {@code locationToXZ} / the domain stay canonical, and overrides
 * {@link #rand()} wholesale (the {@code adjustRange -> sample -> resolve} chain is 1D-scalar
 * shaped and cannot express a {@code (macro, micro, sub)} route).
 *
 * <p><b>Known deviations from the ADR, stated so no measurement is read as more than it is:</b>
 *
 * <ul>
 *   <li>Section 7's blocked (base + {@code int[512]} offset) layout is not implemented; the
 *       descriptor uses flat primitive arrays. Reported resident bytes are therefore an upper
 *       bound on what the blocked layout would cost for the macro tier.
 *   <li>Section 6's incremental delta map is not implemented. Every compile pulse recompiles from
 *       the parent's authoritative learned state, which is exactly what the vehicle needs to
 *       measure rebuild cost honestly.
 *   <li>Section 4a's "snapshot run view" is approximated by a frozen {@link CellValidity} oracle
 *       rather than a versioned run snapshot. Marks do not move during measurement, and
 *       {@link #subDrawMisses()} is a hard guard that must read {@code 0} - a non-zero value means
 *       the count and the scan disagreed, which is the failure mode section 4a claims is
 *       structurally impossible.
 * </ul>
 */
public final class TwoTierCellRouter extends Square {

  /** Fixed-point 1.0 for integer inclusion probabilities (ADR-083 section 2). */
  public static final int PI_ONE = 1 << 20;

  /** Inclusion floor: no macro-cell may drop below 1/64. */
  public static final int PI_MIN = PI_ONE >> 6;

  private final int r;
  private final int m;
  private final int b;
  private final int log2r;
  private final long seed;

  /**
   * Per-cell validity oracle.
   *
   * <p>Pluggable on purpose. The default delegates to the parent's shipped learned state, which is
   * the production contract. The world-data vehicle instead supplies the real save's occupancy
   * mask directly, so that what is being measured is ADR-083's routing model and not the
   * {@link Square} 1D key's own collisions and gaps - the spiral key is measured separately, as
   * its own baseline row.
   */
  @FunctionalInterface
  public interface CellValidity {
    /** @return true when the cell is good (contained and not known bad) */
    boolean isGood(int x, int z);
  }

  private CellValidity validity = (x, z) -> !isKnownBad(x, z);

  /** Resident ceiling above which Tier 1 subsampling engages, cheapest-yield first. */
  private long residentBudgetBytes = Long.MAX_VALUE;

  private volatile Snapshot descriptor;
  private long epoch = -1L;

  private final AtomicLong subDrawMisses = new AtomicLong();
  private final AtomicLong zeroWeightDraws = new AtomicLong();

  /**
   * @param r micro-cell edge in cells; power of two, {@code >= 1}
   * @param m macro-cell edge in micro-cells; power of two, {@code >= 2}
   * @param seed world seed equivalent, feeding the inclusion phase
   */
  public TwoTierCellRouter(int r, int m, long seed) {
    super("TWO_TIER_R" + r + "_M" + m);
    if (r < 1 || Integer.bitCount(r) != 1) throw new IllegalArgumentException("r must be a power of two >= 1");
    if (m < 2 || Integer.bitCount(m) != 1) throw new IllegalArgumentException("M must be a power of two >= 2");
    this.r = r;
    this.m = m;
    this.b = r * m;
    this.log2r = Integer.numberOfTrailingZeros(r);
    this.seed = seed;
  }

  // ---------------------------------------------------------------------------------------
  // descriptor
  // ---------------------------------------------------------------------------------------

  /** Immutable, single-{@code volatile}-reference descriptor snapshot (ADR-083 section 7). */
  public static final class Snapshot {
    final long epoch;
    /** Packed {@code (macroX, macroZ)} of every included, non-zero-weight macro-cell. */
    final long[] macroKeys;
    /** Running sum of compensated integer weights, parallel to {@link #macroKeys}. */
    final long[] macroPrefixSums;
    /** Contained-and-good cell count per included macro-cell. */
    final int[] validCells;
    /** Integer inclusion probability per included macro-cell. */
    final int[] pi;
    /** Per-macro contained-and-good cell count per micro-cell, {@code M * M} entries. */
    final short[][] microValidCells;
    /** Per-macro running prefix sum over {@link #microValidCells}. */
    final int[][] microPrefixSums;
    /** Macro-cells that exist in the domain but were excluded from this epoch. */
    final int excludedMacroCells;
    /** Macro-cells whose contained-and-good count is zero; never drawable. */
    final int zeroWeightMacroCells;

    Snapshot(
        long epoch,
        long[] macroKeys,
        long[] macroPrefixSums,
        int[] validCells,
        int[] pi,
        short[][] microValidCells,
        int[][] microPrefixSums,
        int excludedMacroCells,
        int zeroWeightMacroCells) {
      this.epoch = epoch;
      this.macroKeys = macroKeys;
      this.macroPrefixSums = macroPrefixSums;
      this.validCells = validCells;
      this.pi = pi;
      this.microValidCells = microValidCells;
      this.microPrefixSums = microPrefixSums;
      this.excludedMacroCells = excludedMacroCells;
      this.zeroWeightMacroCells = zeroWeightMacroCells;
    }

    /** @return number of drawable macro-cells in this epoch */
    public int macroCount() {
      return macroKeys.length;
    }

    /** @return macro-cells excluded from this epoch by Tier 1 subsampling */
    public int excludedMacroCells() {
      return excludedMacroCells;
    }

    /** @return macro-cells permanently undrawable because they hold no good cell */
    public int zeroWeightMacroCells() {
      return zeroWeightMacroCells;
    }

    /** @return total compensated weight; {@code 0} means nothing is drawable */
    public long totalWeight() {
      return macroPrefixSums.length == 0 ? 0L : macroPrefixSums[macroPrefixSums.length - 1];
    }

    /** @return good cells summed over the included macro-cells */
    public long includedGoodCells() {
      long n = 0L;
      for (int v : validCells) n += v;
      return n;
    }

    /**
     * Exact resident bytes of the descriptor's primitive payload, excluding JVM object headers
     * and array-length words. Flat layout, so this is an upper bound relative to ADR-083
     * section 7's blocked macro arrays.
     *
     * @return payload bytes
     */
    public long residentBytes() {
      long bytes = 0L;
      bytes += 8L * macroKeys.length;
      bytes += 8L * macroPrefixSums.length;
      bytes += 4L * validCells.length;
      bytes += 4L * pi.length;
      for (short[] counts : microValidCells) bytes += 2L * counts.length;
      for (int[] sums : microPrefixSums) bytes += 4L * sums.length;
      return bytes;
    }
  }

  /** @return the published descriptor, or {@code null} before the first compile pulse */
  public Snapshot descriptor() {
    return descriptor;
  }

  /** @return current epoch; {@code -1} before the first compile pulse */
  public long epoch() {
    return epoch;
  }

  /** Sets the resident ceiling above which Tier 1 subsampling engages. */
  public void setResidentBudgetBytes(long budget) {
    this.residentBudgetBytes = budget;
  }

  /** Replaces the validity oracle. Must not change between a compile pulse and its draws. */
  public void setValidity(CellValidity validity) {
    this.validity = validity;
  }

  /** @return micro-cell edge in cells */
  public int microEdge() {
    return r;
  }

  /** @return macro-cell edge in cells */
  public int macroEdge() {
    return b;
  }

  /**
   * Sub-draws that could not locate the k-th good cell. ADR-083 section 4a makes this
   * structurally impossible; a non-zero reading is a model failure, not a tolerated re-roll
   * (S-004).
   *
   * @return miss count
   */
  public long subDrawMisses() {
    return subDrawMisses.get();
  }

  /** @return draws that landed on a zero-weight macro-cell; the section 8 GUARD row */
  public long zeroWeightDraws() {
    return zeroWeightDraws.get();
  }

  // ---------------------------------------------------------------------------------------
  // determinism primitives (ADR-083 section 3)
  // ---------------------------------------------------------------------------------------

  static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /**
   * Systematic rotational-phase inclusion: exactly one inclusion every {@code period} epochs,
   * with the phase decorrelated between neighbouring macro-cells.
   */
  static boolean included(long seed, int macroX, int macroZ, int pi, long epoch) {
    int period = PI_ONE / pi;
    if (period <= 1) return true;
    long cellSeed = mix(mix(seed) ^ (macroX * 0x9E3779B97F4A7C15L)) ^ (macroZ * 0xC2B2AE3D27D4EB4FL);
    int phase = (int) Math.floorMod(mix(cellSeed), (long) period);
    return Math.floorMod(epoch, (long) period) == phase;
  }

  // ---------------------------------------------------------------------------------------
  // Morton order (ADR-083 section 4)
  // ---------------------------------------------------------------------------------------

  /** Interleaves with {@code a} in the even bit positions, {@code b} in the odd ones. */
  static int morton(int a, int bb) {
    int out = 0;
    for (int i = 0; i < 16; i++) {
      out |= ((a >>> i) & 1) << (2 * i);
      out |= ((bb >>> i) & 1) << (2 * i + 1);
    }
    return out;
  }

  /** Inverse of {@link #morton}: even bits into {@code out[0]}, odd bits into {@code out[1]}. */
  static void unmorton(int code, int[] out) {
    int a = 0;
    int bb = 0;
    for (int i = 0; i < 16; i++) {
      a |= ((code >>> (2 * i)) & 1) << i;
      bb |= ((code >>> (2 * i + 1)) & 1) << i;
    }
    out[0] = a;
    out[1] = bb;
  }

  // ---------------------------------------------------------------------------------------
  // domain
  // ---------------------------------------------------------------------------------------

  private long radius() {
    return getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
  }

  private long centerRadius() {
    return getNumber(GenericMemoryShapeParams.centerRadius, 0L).longValue();
  }

  private long centerX() {
    return getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
  }

  private long centerZ() {
    return getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
  }

  /**
   * Cheap ring test equivalent to {@link Square#contains(int, int)} over the same square annulus,
   * without the {@code atan} round trip {@code xzToLocation} pays. Compiling a domain of a million
   * cells through the trig path would price the measurement at the key's cost rather than the
   * router's.
   *
   * @return true when the cell is inside the shape's annulus
   */
  public boolean inDomain(int x, int z) {
    long ring = Math.max(Math.abs(x - centerX()), Math.abs(z - centerZ()));
    return ring > centerRadius() && ring < radius();
  }

  // ---------------------------------------------------------------------------------------
  // compile pulse
  // ---------------------------------------------------------------------------------------

  /**
   * One compile pulse: recompiles the descriptor from the parent's learned state and advances the
   * epoch by exactly one (ADR-083 section 3).
   *
   * @return the freshly published snapshot
   */
  public Snapshot compile() {
    epoch++;

    long radius = radius();
    long cx = centerX();
    long cz = centerZ();
    int macroMin = (int) Math.floorDiv(-radius + cx, (long) b);
    int macroMax = (int) Math.floorDiv(radius + cx, (long) b);
    int macroMinZ = (int) Math.floorDiv(-radius + cz, (long) b);
    int macroMaxZ = (int) Math.floorDiv(radius + cz, (long) b);

    int span = (macroMax - macroMin + 1) * (macroMaxZ - macroMinZ + 1);
    long[] keys = new long[span];
    int[] valid = new int[span];
    short[][] micro = new short[span][];
    int count = 0;
    int zeroWeight = 0;

    int microCells = m * m;
    for (int mx = macroMin; mx <= macroMax; mx++) {
      for (int mz = macroMinZ; mz <= macroMaxZ; mz++) {
        short[] counts = new short[microCells];
        int total = 0;
        int originX = mx * b;
        int originZ = mz * b;
        for (int dx = 0; dx < b; dx++) {
          int x = originX + dx;
          for (int dz = 0; dz < b; dz++) {
            int z = originZ + dz;
            if (!inDomain(x, z)) continue;
            if (!validity.isGood(x, z)) continue;
            int mi = morton(dx >> log2r, dz >> log2r);
            counts[mi]++;
            total++;
          }
        }
        if (total == 0) {
          // Zero weight is absolute: never recorded, never drawable, no arrays retained.
          zeroWeight++;
          continue;
        }
        keys[count] = (((long) mx) << 32) | (mz & 0xFFFFFFFFL);
        valid[count] = total;
        micro[count] = counts;
        count++;
      }
    }

    // Tier 1 inclusion probabilities: PI_ONE everywhere unless the uncompensated table exceeds
    // the resident budget, then cheapest-yield first (ADR-083 section 2).
    int[] pi = new int[count];
    Arrays.fill(pi, PI_ONE);
    long uncompensatedBytes = estimateBytes(count, microCells);
    if (uncompensatedBytes > residentBudgetBytes) {
      Integer[] order = new Integer[count];
      for (int i = 0; i < count; i++) order[i] = i;
      final int[] validRef = valid;
      Arrays.sort(order, (p, q) -> Integer.compare(validRef[p], validRef[q]));
      long bytes = uncompensatedBytes;
      long perMacro = perMacroBytes(microCells);
      for (int i = 0; i < count && bytes > residentBudgetBytes; i++) {
        pi[order[i]] = PI_MIN;
        // An excluded macro-cell retains nothing this epoch; expectation of retention is
        // pi * perMacro, and PI_MIN keeps 1/64 of it.
        bytes -= perMacro - (perMacro >> 6);
      }
    }

    // Publish only the macro-cells included in this epoch.
    long[] outKeys = new long[count];
    long[] outSums = new long[count];
    int[] outValid = new int[count];
    int[] outPi = new int[count];
    short[][] outMicro = new short[count][];
    int[][] outMicroSums = new int[count][];
    int included = 0;
    int excluded = 0;
    long running = 0L;
    for (int i = 0; i < count; i++) {
      int mx = (int) (keys[i] >> 32);
      int mz = (int) keys[i];
      if (!included(seed, mx, mz, pi[i], epoch)) {
        excluded++;
        continue;
      }
      long weight = (long) valid[i] * PI_ONE / pi[i];
      running += weight;
      outKeys[included] = keys[i];
      outSums[included] = running;
      outValid[included] = valid[i];
      outPi[included] = pi[i];
      outMicro[included] = micro[i];
      int[] sums = new int[microCells];
      int acc = 0;
      short[] counts = micro[i];
      for (int k = 0; k < microCells; k++) {
        acc += counts[k];
        sums[k] = acc;
      }
      outMicroSums[included] = sums;
      included++;
    }

    Snapshot snapshot =
        new Snapshot(
            epoch,
            Arrays.copyOf(outKeys, included),
            Arrays.copyOf(outSums, included),
            Arrays.copyOf(outValid, included),
            Arrays.copyOf(outPi, included),
            Arrays.copyOf(outMicro, included),
            Arrays.copyOf(outMicroSums, included),
            excluded,
            zeroWeight);
    this.descriptor = snapshot;
    return snapshot;
  }

  private long perMacroBytes(int microCells) {
    return 8L + 8L + 4L + 4L + 2L * microCells + 4L * microCells;
  }

  private long estimateBytes(int macroCount, int microCells) {
    return macroCount * perMacroBytes(microCells);
  }

  // ---------------------------------------------------------------------------------------
  // selection
  // ---------------------------------------------------------------------------------------

  /**
   * Routes one draw down to a concrete cell.
   *
   * @return {@code {x, z}}, or {@code null} when nothing in the domain is drawable
   */
  public int[] selectCell() {
    Snapshot s = descriptor;
    if (s == null) s = compile();
    long total = s.totalWeight();
    if (total <= 0L) return null;

    // Tier 1: integer prefix-sum draw over compensated macro weights.
    long w = rng().nextLong(total);
    int macro = upperBound(s.macroPrefixSums, w);
    int validCells = s.validCells[macro];
    if (validCells <= 0) {
      zeroWeightDraws.incrementAndGet();
      return null;
    }

    // Tier 2a: integer prefix-sum draw over per-micro-cell good counts.
    int t = rng().nextInt(validCells);
    int[] sums = s.microPrefixSums[macro];
    int mi = upperBound(sums, t);
    int k = t - (mi > 0 ? sums[mi - 1] : 0);

    // Tier 2b: uniform sub-draw, k-th good cell of that micro-cell in Morton order.
    long key = s.macroKeys[macro];
    int originX = ((int) (key >> 32)) * b;
    int originZ = ((int) key) * b;
    int[] mc = new int[2];
    unmorton(mi, mc);
    int microOriginX = originX + mc[0] * r;
    int microOriginZ = originZ + mc[1] * r;

    int seen = 0;
    int[] sub = new int[2];
    int cells = r * r;
    for (int j = 0; j < cells; j++) {
      unmorton(j, sub);
      int x = microOriginX + sub[0];
      int z = microOriginZ + sub[1];
      if (!inDomain(x, z)) continue;
      if (!validity.isGood(x, z)) continue;
      if (seen == k) return new int[] {x, z};
      seen++;
    }
    subDrawMisses.incrementAndGet();
    return null;
  }

  /** First index whose prefix sum is strictly greater than {@code target}. */
  static int upperBound(long[] prefixSums, long target) {
    int lo = 0;
    int hi = prefixSums.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (prefixSums[mid] > target) hi = mid;
      else lo = mid + 1;
    }
    return lo;
  }

  /** First index whose prefix sum is strictly greater than {@code target}. */
  static int upperBound(int[] prefixSums, int target) {
    int lo = 0;
    int hi = prefixSums.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (prefixSums[mid] > target) hi = mid;
      else lo = mid + 1;
    }
    return lo;
  }

  @Override
  public long rand() {
    int[] cell = selectCell();
    if (cell == null) return -1L;
    return postProcess(xzToLocation(cell[0], cell[1]));
  }

  @Override
  public int[] select() {
    int[] cell = selectCell();
    return cell == null ? null : cell;
  }

  /**
   * {@code expand} adjusts a 1D range this shape never samples (ADR-083 section 3), so the knob is
   * refused rather than silently honored.
   */
  @Override
  protected boolean supportsExpand() {
    return false;
  }

  /**
   * Counts the good cells in the domain by brute force, from the same oracle the descriptor is
   * compiled from. The ground truth every uniformity and coverage assertion is checked against.
   *
   * @return contained-and-good cell count over the whole domain
   */
  public long bruteForceGoodCells() {
    long radius = radius();
    long cx = centerX();
    long cz = centerZ();
    long good = 0L;
    for (int x = (int) (cx - radius); x <= cx + radius; x++) {
      for (int z = (int) (cz - radius); z <= cz + radius; z++) {
        if (!inDomain(x, z)) continue;
        if (!validity.isGood(x, z)) continue;
        good++;
      }
    }
    return good;
  }

  /**
   * Feeds the domain's bad cells into the parent's shipped learned state, one
   * {@code addBadLocation} per bad cell through the canonical 1D key. Used only to price the
   * ADR-001 spiral baseline against the same mask; the router itself never reads it.
   *
   * @return number of {@code addBadLocation} calls made
   */
  public long seedSpiralBaselineFromValidity() {
    long radius = radius();
    long cx = centerX();
    long cz = centerZ();
    long marked = 0L;
    for (int x = (int) (cx - radius); x <= cx + radius; x++) {
      for (int z = (int) (cz - radius); z <= cz + radius; z++) {
        if (!inDomain(x, z)) continue;
        if (validity.isGood(x, z)) continue;
        addBadLocation(xzToLocation(x, z));
        marked++;
      }
    }
    flushAndRebuildIfNeeded(spatialResolution());
    return marked;
  }
}
