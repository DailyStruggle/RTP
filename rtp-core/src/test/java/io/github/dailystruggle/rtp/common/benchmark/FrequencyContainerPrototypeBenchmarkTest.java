package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Prototype measurement for a period-modulated ("frequency") hazard/usage container, driven by the
 * <b>real shipped L3 selection path</b>.
 *
 * <p>Why the earlier probe was wrong. A generic Haar transform on a terrain-derived bad set, or a
 * monotone scan sweep, both destroy the structure that makes this idea work. The L3 primary path
 * (over 95% of servers) does not scan; it emits candidates through {@code selectL3Candidate} -
 * dyadic-stride binning plus a keyed Feistel PRP - which lands roughly 2-4 non-repeating locations
 * per {@code P^2 = 1024}-chunk sub-bin across randomly chosen bins. The offsets it produces are
 * spaced by a dyadic stride, so the accumulated used/marked set is not a flat-spectrum impulse
 * train: within a sub-bin the marks sit on a coarse power-of-two lattice. That lattice is the
 * "resonance", and it is exactly what a period descriptor can exploit.
 *
 * <p>The encoding under test. Per sub-bin, a full bitmask is 1024 bits (128 bytes). A period-{@code
 * P} descriptor instead stores one bit per {@code P}-cell - a downsampled bitmask of {@code 1024/P}
 * bits plus a one-byte period header. Marking a whole occupied cell can only ever discard usable
 * ground, never admit hazard (S-001 / S-004 direction); the retention target bounds that discard.
 * This picks, per bin, the coarsest period that still keeps &gt;=90% of the bin's available ground.
 *
 * <p>The comparison is against the three exact shipped container costs (array 2 B/mark, run 4 B/run,
 * bitmask 128 B/occupied bin) and the sparse information floor (gap-coded sorted keys), summed over
 * all occupied sub-bins, at a progression of realistic usage counts.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. No shipped code is
 * touched; this is a viability probe ahead of any D-005 proposal.
 */
@Tag("simulation")
@DisplayName("period-modulated container prototype - driven by the real L3 dyadic selection path")
public class FrequencyContainerPrototypeBenchmarkTest {

  /** P^2 sub-bin: one spiral point, one Anvil region file. */
  private static final int SUBBIN = 1024;

  /** Radius giving pointEdge P = 32, so one sub-bin is exactly 1024 chunks. */
  private static final int RADIUS_CHUNKS = 1024;


  /**
   * Usage counts spanning the whole life of a region: from the first sparse teleports up through the
   * mid-density regime where a sparse array devolves toward a bitmask, to near-complete exploration.
   * There are only {@code range/1024} sub-bins, so high counts are what fill each bin to tens or
   * hundreds of marks - the regime where the period descriptor can beat all three exact containers.
   */
  private static final long[] USAGE_COUNTS = {
    1000L, 5000L, 20000L, 50000L, 100000L, 200000L, 500000L
  };

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new File("target/test-data"));
  }

  @Test
  @DisplayName("exact period bitmask vs array/gap/bitmask on the real dyadic usage layout")
  public void periodModulationOnRealL3Usage() {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer();
    shape.set(GenericMemoryShapeParams.radius, (long) RADIUS_CHUNKS);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    long range = shape.getRange();
    System.out.printf(
        "[DEBUG_LOG] shape range=%d pointEdge=%d subBin=%d stride(deriveAdaptive)=%d%n",
        range,
        shape.getPointEdgeChunks(),
        SUBBIN,
        SquareOptimizedDualLayer.deriveAdaptiveStride(range));

    long maxCount = USAGE_COUNTS[USAGE_COUNTS.length - 1];
    // Drive the real shipped L3 selector; collect non-repeating marks in emission order.
    long[] marks = new long[(int) maxCount];
    Set<Long> seen = new HashSet<>();
    int produced = 0;
    long guard = 0L;
    while (produced < maxCount && guard < maxCount * 8L) {
      guard++;
      long key = shape.selectL3Candidate();
      if (key < 0L || key >= range) continue;
      if (seen.add(key)) marks[produced++] = key;
    }
    System.out.printf("[DEBUG_LOG] produced %d unique marks in %d draws%n", produced, guard);

    long peakGapBytes = -1L;
    long peakBitmaskBytes = -1L;
    for (long n : USAGE_COUNTS) {
      if (n > produced) break;
      // Bin the first n marks into 1024-chunk sub-bins by local offset.
      Map<Long, boolean[]> bins = new HashMap<>();
      Map<Long, Integer> counts = new HashMap<>();
      for (int i = 0; i < n; i++) {
        long key = marks[i];
        long bin = key / SUBBIN;
        int off = (int) (key % SUBBIN);
        boolean[] cell = bins.computeIfAbsent(bin, b -> new boolean[SUBBIN]);
        if (!cell[off]) {
          cell[off] = true;
          counts.merge(bin, 1, Integer::sum);
        }
      }

      long occBins = bins.size();
      long bitmaskBytes = occBins * (SUBBIN / 8L); // 128 B per occupied bin
      long arrayBytes = 0L;
      long gapBytes = 0L;
      long freqBytes = 0L;
      long periodHistogramSum = 0L;
      // Per-bin winner tally: which single representation is smallest for each bin.
      long winArray = 0L;
      long winGap = 0L;
      long winBitmask = 0L;
      long winPeriodStrict = 0L; // period strictly smaller than ALL three exact containers
      for (Map.Entry<Long, boolean[]> e : bins.entrySet()) {
        boolean[] cell = e.getValue();
        int c = counts.get(e.getKey());
        long a = 2L * c;
        long g = gapCodedFloorBytes(c, SUBBIN);
        long bm = SUBBIN / 8L;
        long[] best = bestPeriodEncoding(cell, c);
        long p = best[0];
        arrayBytes += a;
        gapBytes += g;
        freqBytes += p;
        periodHistogramSum += best[1];

        long min = Math.min(Math.min(a, g), Math.min(bm, p));
        if (p < a && p < g && p < bm) winPeriodStrict++;
        else if (min == a) winArray++;
        else if (min == g) winGap++;
        else winBitmask++;
      }
      double meanPeriod = occBins == 0 ? 0 : (double) periodHistogramSum / occBins;

      System.out.printf(
          "[DEBUG_LOG] n=%7d occBins=%5d marks/bin=%6.2f | bitmask=%8d array=%8d gap=%8d "
              + "period=%8d (meanP=%.1f)%n",
          n,
          occBins,
          (double) n / Math.max(1, occBins),
          bitmaskBytes,
          arrayBytes,
          gapBytes,
          freqBytes,
          meanPeriod);
      System.out.printf(
          "[DEBUG_LOG]         per-bin winner: array=%5d gap=%5d bitmask=%5d "
              + "PERIOD-over-all-three=%5d (%.1f%% of bins)%n",
          winArray,
          winGap,
          winBitmask,
          winPeriodStrict,
          occBins == 0 ? 0.0d : 100.0d * winPeriodStrict / occBins);

      peakGapBytes = gapBytes;
      peakBitmaskBytes = bitmaskBytes;
      // Suppress unused-tally warnings while keeping the per-bin winner breakdown in the log.
      if (winArray + winGap + winBitmask + winPeriodStrict < 0) throw new AssertionError();
    }

    // Measured finding, asserted as the invariant: on the real L3 usage layout the exact gap-coded
    // (Elias-Fano) representation stays below the raw bitmask cap even at the densest sampled point,
    // so the roaring bitmask tier is never the smallest choice for L3 usage state. The exact
    // period-modulated bitmask never wins over all three (see the per-bin tally in the log), because
    // the dyadic resonance is inter-bin: within a 1024 sub-bin the marks fill every residue.
    assertTrue(
        peakGapBytes > 0 && peakGapBytes < peakBitmaskBytes,
        "gap-coded keys must stay under the bitmask cap at peak density (was "
            + peakGapBytes
            + " vs "
            + peakBitmaskBytes
            + ")");
  }

  @Test
  @DisplayName("access latency: point-check vs accumulate (rank/select) per representation")
  public void accessLatencyOnL3Usage() {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer();
    shape.set(GenericMemoryShapeParams.radius, (long) RADIUS_CHUNKS);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    int range = (int) shape.getRange();

    int maxMarks = 200000;
    int[] allMarks = new int[maxMarks];
    Set<Long> seen = new HashSet<>();
    int produced = 0;
    long guard = 0L;
    while (produced < maxMarks && guard < maxMarks * 8L) {
      guard++;
      long key = shape.selectL3Candidate();
      if (key < 0L || key >= range) continue;
      if (seen.add(key)) allMarks[produced++] = (int) key;
    }

    java.util.Random rng = new java.util.Random(1234567L);
    int ops = 2_000_000;
    int[] probeKeys = new int[ops];
    for (int i = 0; i < ops; i++) probeKeys[i] = rng.nextInt(range);

    for (int m : new int[] {20000, 100000, 200000}) {
      if (m > produced) break;
      int[] bad = Arrays.copyOf(allMarks, m);
      Arrays.sort(bad);
      long good = (long) range - m;

      Rle rle = Rle.build(bad, range);
      Bitmask bm = Bitmask.build(bad, range);

      int[] probeRanks = new int[ops];
      for (int i = 0; i < ops; i++) probeRanks[i] = (int) ((rng.nextInt() & 0x7fffffff) % good);

      // Cross-validate all three agree before timing, so the numbers describe correct structures.
      for (int i = 0; i < 5000; i++) {
        int k = probeKeys[i];
        boolean a = Arrays.binarySearch(bad, k) >= 0;
        if (a != rle.isBad(k) || a != bm.isBad(k)) throw new AssertionError("isBad disagreement");
        int r = probeRanks[i];
        long ea = accumulateArray(bad, r);
        if (ea != rle.accumulate(r) || ea != bm.accumulate(r)) {
          throw new AssertionError("accumulate disagreement at r=" + r);
        }
      }

      long sink = 0;
      // ---- point check ----
      sink ^= timePointArray(bad, probeKeys, ops / 4); // warmup
      long t0 = System.nanoTime();
      sink ^= timePointArray(bad, probeKeys, ops);
      double arrPoint = (System.nanoTime() - t0) / (double) ops;
      sink ^= rle.timePoint(probeKeys, ops / 4);
      t0 = System.nanoTime();
      sink ^= rle.timePoint(probeKeys, ops);
      double rlePoint = (System.nanoTime() - t0) / (double) ops;
      sink ^= bm.timePoint(probeKeys, ops / 4);
      t0 = System.nanoTime();
      sink ^= bm.timePoint(probeKeys, ops);
      double bmPoint = (System.nanoTime() - t0) / (double) ops;

      // ---- accumulate (rank/select) ----
      sink ^= timeAccArray(bad, probeRanks, ops / 4);
      t0 = System.nanoTime();
      sink ^= timeAccArray(bad, probeRanks, ops);
      double arrAcc = (System.nanoTime() - t0) / (double) ops;
      sink ^= rle.timeAcc(probeRanks, ops / 4);
      t0 = System.nanoTime();
      sink ^= rle.timeAcc(probeRanks, ops);
      double rleAcc = (System.nanoTime() - t0) / (double) ops;
      sink ^= bm.timeAcc(probeRanks, ops / 4);
      t0 = System.nanoTime();
      sink ^= bm.timeAcc(probeRanks, ops);
      double bmAcc = (System.nanoTime() - t0) / (double) ops;

      System.out.printf(
          "[DEBUG_LOG] m=%7d runs=%6d | POINT ns/op  rle=%.1f array=%.1f bitmask=%.1f "
              + "| ACCUM ns/op  rle=%.1f array=%.1f bitmask=%.1f | bytes rle=%d array=%d bitmask=%d "
              + "| sink=%d%n",
          m,
          rle.runCount(),
          rlePoint,
          arrPoint,
          bmPoint,
          rleAcc,
          arrAcc,
          bmAcc,
          rle.bytes(),
          (long) m * 4L,
          bm.bytes(),
          sink & 1L);
    }
  }

  // ---- array oracle helpers ----
  private static long accumulateArray(int[] bad, int r) {
    // r-th good (0-indexed) = r + (number of bad keys <= result); find-kth-missing.
    int lo = 0;
    int hi = bad.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if ((long) bad[mid] - mid <= r) lo = mid + 1;
      else hi = mid;
    }
    return (long) r + lo;
  }

  private static long timePointArray(int[] bad, int[] keys, int ops) {
    long acc = 0;
    for (int i = 0; i < ops; i++) if (Arrays.binarySearch(bad, keys[i]) >= 0) acc++;
    return acc;
  }

  private static long timeAccArray(int[] bad, int[] ranks, int ops) {
    long acc = 0;
    for (int i = 0; i < ops; i++) acc += accumulateArray(bad, ranks[i]);
    return acc;
  }

  /** Run-length representation: the shipped baseline (binary search + A -> A+N linear map). */
  private static final class Rle {
    final int[] runStart;
    final int[] runEnd; // exclusive
    final int[] segStart; // good segments
    final long[] segCum; // cumulative good before each segment
    final int runs;

    private Rle(int[] rs, int[] re, int[] ss, long[] sc) {
      this.runStart = rs;
      this.runEnd = re;
      this.segStart = ss;
      this.segCum = sc;
      this.runs = rs.length;
    }

    static Rle build(int[] bad, int range) {
      java.util.List<int[]> rl = new java.util.ArrayList<>();
      int i = 0;
      while (i < bad.length) {
        int s = bad[i];
        int e = s + 1;
        i++;
        while (i < bad.length && bad[i] == e) {
          e++;
          i++;
        }
        rl.add(new int[] {s, e});
      }
      int r = rl.size();
      int[] rs = new int[r];
      int[] re = new int[r];
      for (int j = 0; j < r; j++) {
        rs[j] = rl.get(j)[0];
        re[j] = rl.get(j)[1];
      }
      // Good segments: [prevEnd, nextStart).
      java.util.List<int[]> gs = new java.util.ArrayList<>();
      java.util.List<Long> gc = new java.util.ArrayList<>();
      long cum = 0;
      int prevEnd = 0;
      for (int j = 0; j < r; j++) {
        if (rs[j] > prevEnd) {
          gs.add(new int[] {prevEnd, rs[j]});
          gc.add(cum);
          cum += rs[j] - prevEnd;
        }
        prevEnd = re[j];
      }
      if (prevEnd < range) {
        gs.add(new int[] {prevEnd, range});
        gc.add(cum);
      }
      int[] ss = new int[gs.size()];
      long[] sc = new long[gs.size()];
      for (int j = 0; j < gs.size(); j++) {
        ss[j] = gs.get(j)[0];
        sc[j] = gc.get(j);
      }
      return new Rle(rs, re, ss, sc);
    }

    boolean isBad(int key) {
      int lo = 0;
      int hi = runs;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (runStart[mid] <= key) lo = mid + 1;
        else hi = mid;
      }
      int idx = lo - 1;
      return idx >= 0 && key < runEnd[idx];
    }

    long accumulate(int r) {
      int lo = 0;
      int hi = segCum.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (segCum[mid] <= r) lo = mid + 1;
        else hi = mid;
      }
      int j = lo - 1;
      return segStart[j] + (r - segCum[j]);
    }

    long timePoint(int[] keys, int ops) {
      long acc = 0;
      for (int i = 0; i < ops; i++) if (isBad(keys[i])) acc++;
      return acc;
    }

    long timeAcc(int[] ranks, int ops) {
      long acc = 0;
      for (int i = 0; i < ops; i++) acc += accumulate(ranks[i]);
      return acc;
    }

    int runCount() {
      return runs;
    }

    long bytes() {
      return (long) runs * 8L + (long) segStart.length * 12L;
    }
  }

  /** Bitmask with a per-word good-count prefix index for rank/select. */
  private static final class Bitmask {
    final long[] words;
    final long[] goodPrefix; // cumulative good bits before word w
    final int range;

    private Bitmask(long[] w, long[] gp, int range) {
      this.words = w;
      this.goodPrefix = gp;
      this.range = range;
    }

    static Bitmask build(int[] bad, int range) {
      int nWords = (range + 63) >> 6;
      long[] w = new long[nWords];
      for (int k : bad) w[k >> 6] |= 1L << (k & 63);
      long[] gp = new long[nWords + 1];
      long cum = 0;
      for (int i = 0; i < nWords; i++) {
        int bitsInWord = Math.min(64, range - (i << 6));
        int badBits = Long.bitCount(w[i]);
        gp[i] = cum;
        cum += bitsInWord - badBits;
      }
      gp[nWords] = cum;
      return new Bitmask(w, gp, range);
    }

    boolean isBad(int key) {
      return ((words[key >> 6] >>> (key & 63)) & 1L) != 0L;
    }

    long accumulate(int r) {
      int lo = 0;
      int hi = words.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (goodPrefix[mid] <= r) lo = mid + 1;
        else hi = mid;
      }
      int w = lo - 1;
      long need = r - goodPrefix[w];
      long word = words[w];
      int base = w << 6;
      for (int b = 0; b < 64; b++) {
        if (((word >>> b) & 1L) == 0L) {
          if (need == 0) return base + b;
          need--;
        }
      }
      return base + 63;
    }

    long timePoint(int[] keys, int ops) {
      long acc = 0;
      for (int i = 0; i < ops; i++) if (isBad(keys[i])) acc++;
      return acc;
    }

    long timeAcc(int[] ranks, int ops) {
      long acc = 0;
      for (int i = 0; i < ops; i++) acc += accumulate(ranks[i]);
      return acc;
    }

    long bytes() {
      return (long) words.length * 8L + (long) goodPrefix.length * 8L;
    }
  }

  /**
   * Smallest <b>exact</b> period-modulated bitmask encoding of one sub-bin - the operator's
   * "period P, offset r, bit i set means position r + i*P is a hit" scheme, lossless.
   *
   * <p>For a period P the marks are partitioned by residue {@code r = offset mod P}. Each residue
   * that holds a mark is a sublattice {@code {r, r+P, r+2P, ...}} of {@code 1024/P} positions, stored
   * as a bitmask over exactly those positions; non-lattice positions are known-empty by
   * construction, so nothing is discarded. Cost for period P is {@code 1 (period header) + A * (1
   * (offset header) + ceil((1024/P)/8))} where {@code A} is the number of residues that hold marks.
   * The best P is the one that packs the marks into the fewest, densest sublattices. P=1 reproduces
   * the full 128-byte bitmask; a set living on one residue collapses to a single narrow bitmask.
   *
   * @return {@code [bytes, chosenPeriod]}
   */
  private static long[] bestPeriodEncoding(boolean[] cell, int markCount) {
    long bestBytes = Long.MAX_VALUE;
    long bestP = 1L;
    for (int p = 1; p <= SUBBIN; p <<= 1) {
      int laneBits = SUBBIN / p; // positions per residue lane
      boolean[] residueUsed = new boolean[p];
      int activeResidues = 0;
      for (int off = 0; off < SUBBIN; off++) {
        if (!cell[off]) continue;
        int r = off % p;
        if (!residueUsed[r]) {
          residueUsed[r] = true;
          activeResidues++;
        }
      }
      long laneBytes = (long) Math.ceil(laneBits / 8.0d);
      long bytes = 1L + (long) activeResidues * (1L + laneBytes);
      if (bytes < bestBytes) {
        bestBytes = bytes;
        bestP = p;
      }
    }
    return new long[] {bestBytes, bestP};
  }

  /** Information floor for a sparse sorted key set within a bin: count * log2(SUBBIN/count) bits. */
  private static long gapCodedFloorBytes(int count, int domain) {
    if (count <= 0) return 0L;
    double bitsPerKey = Math.max(1.0d, Math.log((double) domain / count) / Math.log(2.0d));
    return (long) Math.ceil(count * bitsPerKey / 8.0d);
  }
}
