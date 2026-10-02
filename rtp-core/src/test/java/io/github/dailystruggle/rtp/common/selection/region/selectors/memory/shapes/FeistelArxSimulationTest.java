package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class FeistelArxSimulationTest {

  private static class Point2D {
    final int x;
    final int z;

    Point2D(int x, int z) {
      this.x = x;
      this.z = z;
    }
  }

  // Baseline fmix32 Feistel permutation
  private static long feistelFmix32(long val, long domainSize, long seed) {
    if (domainSize <= 1) return 0;
    int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
    if ((bits & 1) != 0) bits++;
    int halfBits = bits / 2;
    long halfMask = (1L << halfBits) - 1L;

    long fullMask = (bits == 64) ? -1L : ((1L << bits) - 1L);
    long candidate = val & fullMask;
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

  // Candidate 1: SipHash-style ARX round (Reviewer optimized)
  // Interleaves additions, rotations, and XORs with round key and domain masking
  private static long feistelArxSip(long val, long domainSize, long seed) {
    if (domainSize <= 1) return 0;
    int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
    if ((bits & 1) != 0) bits++;
    int halfBits = bits / 2;
    long halfMask = (1L << halfBits) - 1L;

    long fullMask = (bits == 64) ? -1L : ((1L << bits) - 1L);
    long candidate = val & fullMask;
    do {
      long l = (candidate >>> halfBits) & halfMask;
      long r = candidate & halfMask;

      for (int round = 0; round < 4; round++) {
        long roundKey = seed ^ (0x9E3779B97F4A7C15L * (round + 1));
        // 1. Isolate the active block bits and mix with round key
        long v0 = r & halfMask;
        long v1 = roundKey;

        // 2. High-diffusion ARX sequence (SipRound style)
        v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0;
        v0 = Long.rotateLeft(v0, 32);
        v1 += v0; v0 = Long.rotateLeft(v0, 17); v0 ^= v1;
        v1 = Long.rotateLeft(v1, 21);

        // 3. Extract and compress diffused entropy down to half-block width
        long f = (v0 ^ v1) & halfMask;
        long newL = r;
        long newR = l ^ f;
        l = newL;
        r = newR;
      }
      candidate = (l << halfBits) | r;
    } while (candidate >= domainSize);

    return candidate;
  }

  // Candidate 2: ChaCha-style ARX quarter-round
  private static long feistelArxChaCha(long val, long domainSize, long seed) {
    if (domainSize <= 1) return 0;
    int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
    if ((bits & 1) != 0) bits++;
    int halfBits = bits / 2;
    long halfMask = (1L << halfBits) - 1L;

    long fullMask = (bits == 64) ? -1L : ((1L << bits) - 1L);
    long candidate = val & fullMask;
    do {
      long l = (candidate >>> halfBits) & halfMask;
      long r = candidate & halfMask;

      for (int round = 0; round < 4; round++) {
        long roundKey = seed ^ (0x9E3779B97F4A7C15L * (round + 1));
        int a = (int) r;
        int b = (int) roundKey;
        int c = (int) (roundKey >>> 32);
        int d = (int) (r ^ 0x61707865); // "expa" constant

        a += b; d ^= a; d = Integer.rotateLeft(d, 16);
        c += d; b ^= c; b = Integer.rotateLeft(b, 12);
        a += b; d ^= a; d = Integer.rotateLeft(d, 8);
        c += d; b ^= c; b = Integer.rotateLeft(b, 7);

        long f = (((long) (a ^ c)) << 32) | ((b ^ d) & 0xFFFFFFFFL);
        long newL = r;
        long newR = (l ^ f) & halfMask;
        l = newL;
        r = newR;
      }
      candidate = (l << halfBits) | r;
    } while (candidate >= domainSize);

    return candidate;
  }

  @Test
  @DisplayName("Verify strict bijection (zero duplicates) across arbitrary domains")
  public void testStrictBijection() {
    long[] domains = {2, 3, 7, 16, 64, 100, 255, 256, 1000, 1024, 4096, 65535, 65536};
    long seed = 0x517CC1B727220A95L;

    for (long dom : domains) {
      BitSet seenFmix = new BitSet((int) dom);
      BitSet seenArxSip = new BitSet((int) dom);
      BitSet seenArxChaCha = new BitSet((int) dom);

      for (long i = 0; i < dom; i++) {
        long outFmix = feistelFmix32(i, dom, seed);
        long outArxSip = feistelArxSip(i, dom, seed);
        long outArxChaCha = feistelArxChaCha(i, dom, seed);

        assertTrue(outFmix >= 0 && outFmix < dom, "fmix32 out of range: " + outFmix + " for domain " + dom);
        assertTrue(outArxSip >= 0 && outArxSip < dom, "arxSip out of range: " + outArxSip + " for domain " + dom);
        assertTrue(outArxChaCha >= 0 && outArxChaCha < dom, "arxChaCha out of range: " + outArxChaCha + " for domain " + dom);

        seenFmix.set((int) outFmix);
        seenArxSip.set((int) outArxSip);
        seenArxChaCha.set((int) outArxChaCha);
      }

      assertEquals(dom, seenFmix.cardinality(), "fmix32 not bijective for domain " + dom);
      assertEquals(dom, seenArxSip.cardinality(), "arxSip not bijective for domain " + dom);
      assertEquals(dom, seenArxChaCha.cardinality(), "arxChaCha not bijective for domain " + dom);
    }
  }

  @FunctionalInterface
  interface FeistelFunc {
    long permute(long val, long domainSize, long seed);
  }

  private static class SimulationResult {
    final String name;
    final int duplicates;
    final double avgHopDistance;
    final double minHopDistance;
    final double clarkEvansR;
    final double nsPerDraw;

    SimulationResult(String name, int duplicates, double avgHopDistance, double minHopDistance, double clarkEvansR, double nsPerDraw) {
      this.name = name;
      this.duplicates = duplicates;
      this.avgHopDistance = avgHopDistance;
      this.minHopDistance = minHopDistance;
      this.clarkEvansR = clarkEvansR;
      this.nsPerDraw = nsPerDraw;
    }
  }

  private SimulationResult runSimulation(String name, FeistelFunc func, int radius, int teleports, int stride) {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer();
    square.setData(Map.of("radius", (long) radius, "centerRadius", 0L));
    long totalChunks = square.getRange();

    long seed = 0x517CC1B727220A95L;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    List<Point2D> points = new ArrayList<>(teleports);
    Set<Long> seenLocs = new HashSet<>();
    int dupes = 0;

    int bits = 32 - Integer.numberOfLeadingZeros(stride - 1);
    long[] subsetCounters = new long[stride];

    long startNs = System.nanoTime();
    for (int t = 0; t < teleports; t++) {
      int subsetIdx = t % stride;
      int phaseOffset = Integer.reverse(subsetIdx) >>> (32 - bits);

      long subsetSize = phaseOffset < totalChunks ? (totalChunks - 1 - phaseOffset) / stride + 1 : 0;
      if (subsetSize <= 0) continue;

      long counter = subsetCounters[phaseOffset]++;
      long permutedK = func.permute(counter, subsetSize, seed ^ (phaseOffset * 0x9E3779B97F4A7C15L));
      long virtualIndex = permutedK * stride + phaseOffset;

      if (!seenLocs.add(virtualIndex)) {
        dupes++;
      }

      square.locationToXZ(virtualIndex, coords);
      points.add(new Point2D(coords.x, coords.z));
    }
    long elapsedNs = System.nanoTime() - startNs;
    double nsPerDraw = (double) elapsedNs / teleports;

    // Consecutive hop distances
    double totalHop = 0;
    double minHop = Double.MAX_VALUE;
    for (int i = 1; i < points.size(); i++) {
      Point2D p1 = points.get(i - 1);
      Point2D p2 = points.get(i);
      double dist = Math.hypot(p1.x - p2.x, p1.z - p2.z) * 16.0; // in blocks
      totalHop += dist;
      if (dist < minHop) minHop = dist;
    }
    double avgHop = totalHop / (points.size() - 1);

    // Clark-Evans R
    double clarkEvansR = computeClarkEvansR(points, radius * 2, radius * 2);

    return new SimulationResult(name, dupes, avgHop, minHop, clarkEvansR, nsPerDraw);
  }

  private double computeClarkEvansR(List<Point2D> points, double areaWidth, double areaHeight) {
    int n = points.size();
    if (n < 2) return 1.0;

    double totalNnDist = 0;
    for (int i = 0; i < n; i++) {
      Point2D p1 = points.get(i);
      double minD = Double.MAX_VALUE;
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        Point2D p2 = points.get(j);
        double d = Math.hypot(p1.x - p2.x, p1.z - p2.z);
        if (d < minD) minD = d;
      }
      totalNnDist += minD;
    }

    double observedMeanNn = totalNnDist / n;
    double density = (double) n / (areaWidth * areaHeight);
    double expectedMeanNn = 1.0 / (2.0 * Math.sqrt(density));

    return observedMeanNn / expectedMeanNn;
  }

  @Test
  @DisplayName("Comparative Simulation: fmix32 vs SipHash-ARX vs ChaCha-ARX")
  public void testComparativeSimulation() {
    int radius = 256; // 4k x 4k blocks (512x512 chunks)
    int teleports = 4096;
    int stride = 256;

    SimulationResult rFmix = runSimulation("Baseline fmix32", FeistelArxSimulationTest::feistelFmix32, radius, teleports, stride);
    SimulationResult rSip = runSimulation("SipHash-style ARX", FeistelArxSimulationTest::feistelArxSip, radius, teleports, stride);
    SimulationResult rChaCha = runSimulation("ChaCha-style ARX", FeistelArxSimulationTest::feistelArxChaCha, radius, teleports, stride);

    StringBuilder sb = new StringBuilder();
    sb.append("\n======================= FEISTEL ROUND FUNCTION SIMULATION REPORT =======================\n");
    sb.append(String.format("%-20s | %-10s | %-16s | %-16s | %-14s | %-12s%n",
        "Algorithm", "Duplicates", "Avg Hop (Blocks)", "Min Hop (Blocks)", "Clark-Evans R", "Latency"));
    sb.append("----------------------------------------------------------------------------------------\n");

    for (SimulationResult r : List.of(rFmix, rSip, rChaCha)) {
      sb.append(String.format("%-20s | %10d | %13.1f blk | %13.1f blk | %14.4f | %9.1f ns%n",
          r.name, r.duplicates, r.avgHopDistance, r.minHopDistance, r.clarkEvansR, r.nsPerDraw));
    }
    sb.append("========================================================================================\n");
    System.out.println(sb.toString());
    System.err.println(sb.toString());

    // Invariant assertions
    assertEquals(0, rFmix.duplicates, "fmix32 must have 0 duplicates");
    assertEquals(0, rSip.duplicates, "SipHash-ARX must have 0 duplicates");
    assertEquals(0, rChaCha.duplicates, "ChaCha-ARX must have 0 duplicates");

    // All must maintain Clark-Evans R between 0.95 and 1.15 (uniform random scatter)
    assertTrue(rSip.clarkEvansR >= 0.95 && rSip.clarkEvansR <= 1.15, "SipHash-ARX Clark-Evans R out of range: " + rSip.clarkEvansR);
    assertTrue(rChaCha.clarkEvansR >= 0.95 && rChaCha.clarkEvansR <= 1.15, "ChaCha-ARX Clark-Evans R out of range: " + rChaCha.clarkEvansR);

    // All must maintain average inter-arrival hop > 3,000 blocks and min hop >= 128 blocks (sqrt(S) chunks = 16*8 = 128)
    assertTrue(rSip.avgHopDistance > 3000.0, "SipHash-ARX avg hop too low: " + rSip.avgHopDistance);
    assertTrue(rSip.minHopDistance >= 128.0, "SipHash-ARX min hop too low: " + rSip.minHopDistance);
    assertTrue(rChaCha.avgHopDistance > 3000.0, "ChaCha-ARX avg hop too low: " + rChaCha.avgHopDistance);
    assertTrue(rChaCha.minHopDistance >= 128.0, "ChaCha-ARX min hop too low: " + rChaCha.minHopDistance);
  }
}
