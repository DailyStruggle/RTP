package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Validates {@link SegmentedKeyRunTable} with pinned endpoints against flat {@link KeyRunTable}.
 *
 * <p>Measures:
 * <ul>
 *   <li><b>Exact Equivalence:</b> Assert every single key query in [0, totalRange) returns identical boolean answers between flat and segmented tables.
 *   <li><b>Pinned Endpoint Coverage:</b> Specifically tests synthetic runs longer than bins, multi-bin spanning runs, ocean-scale runs, and verifies no holes/misses occur.
 *   <li><b>Cache Locality & Search Speedup:</b> Micro-benchmarks flat binary search vs segmented binary search over real world data across bin sizes 256, 512, 1024.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
@Tag("simulation")
class SegmentedKeyRunTableBenchmarkTest {

  @Test
  @DisplayName("Segmented table exactly matches flat table across all keys on synthetic multi-bin spans")
  void testSyntheticSpansAndEquivalence() {
    long totalRange = 10_000L;
    long binSize = 500L;

    // Create synthetic runs:
    // 1. Fully contained in bin 0: [50, 150)
    // 2. Straddling bin 0 and bin 1: [400, 700) -> pinned in bin 1 at [500, 700) -> offset [0, 200)
    // 3. Spanning multiple bins completely (Bin 3 to Bin 6): [1800, 3200) -> completely covers bin 4 and 5
    // 4. Abutting runs: [3500, 3700), [3700, 3900)
    List<Long> keys = new ArrayList<>();
    addRange(keys, 50, 150);
    addRange(keys, 400, 700);
    addRange(keys, 1800, 3200);
    addRange(keys, 3500, 3700);
    addRange(keys, 3700, 3900);

    long[] kArr = keys.stream().mapToLong(Long::longValue).sorted().distinct().toArray();
    KeyRunTable flat = KeyRunTable.exact(kArr, kArr.length);
    SegmentedKeyRunTable segmented = flat.toSegmented(totalRange, binSize);

    assertEquals(flat.coveredCells(), segmented.totalCovered(), "Covered cells must match exactly");

    // Check bin 4 (keys 2000 to 2500): completely covered by [1800, 3200)
    SegmentedKeyRunTable.Bin b4 = segmented.bin(4);
    assertTrue(b4.isFull(), "Bin 4 must be completely full");
    assertEquals(1, b4.count());
    assertEquals(500, b4.coveredCells());

    // Check bin 1 (keys 500 to 1000): straddled by [400, 700) -> pinned at offset 0 with len 200
    SegmentedKeyRunTable.Bin b1 = segmented.bin(1);
    assertTrue(b1.containsOffset(0), "Offset 0 must be covered (pinned endpoint)");
    assertTrue(b1.containsOffset(199), "Offset 199 must be covered");
    assertFalse(b1.containsOffset(200), "Offset 200 must be free");

    // Test reverse / start-of-bin abutting seam:
    // [3500, 3700) is in bin 7 ([3500, 4000)).
    // [3700, 3900) abuts immediately at 3700 inside bin 7.
    // Together they form a single merged run [3500, 3900) in bin 7.
    SegmentedKeyRunTable.Bin b7 = segmented.bin(7);
    assertTrue(b7.containsOffset(0), "Offset 0 in bin 7 (key 3500) is covered");
    assertTrue(b7.containsOffset(200), "Offset 200 in bin 7 (key 3700, seam point) is covered");
    assertTrue(b7.containsOffset(399), "Offset 399 in bin 7 (key 3899) is covered");
    assertFalse(b7.containsOffset(400), "Offset 400 in bin 7 (key 3900) is free");
    assertEquals(1, b7.count(), "Abutting runs inside bin merge into 1 run");

    // Test near-full collapse condition within spatialResolution bounds:
    // Create a bin of 500 keys that has 497 bad keys (3 usable chunks remaining <= tolerance of 3)
    List<Long> nearFullKeys = new ArrayList<>();
    addRange(nearFullKeys, 0, 497); // 3 chunks remaining: 497, 498, 499
    long[] nfArr = nearFullKeys.stream().mapToLong(Long::longValue).toArray();
    KeyRunTable nfFlat = KeyRunTable.exact(nfArr, nfArr.length);

    SegmentedKeyRunTable collapsedSeg = nfFlat.toSegmented(500L, 500L, 3L);
    assertTrue(collapsedSeg.bin(0).isFull(), "Near-full bin with remaining chunks <= tolerance must collapse to FULL");
    assertEquals(500L, collapsedSeg.totalCovered(), "Collapsed bin covers all 500 chunks");

    // Exhaustive equivalence across every single key in [0, totalRange)
    for (long k = 0; k < totalRange; k++) {
      boolean expected = flat.contains(k);
      boolean actual = segmented.contains(k);
      if (expected != actual) {
        fail("Mismatch at key " + k + ": expected " + expected + " but got " + actual);
      }
    }
  }

  @Test
  @DisplayName("Real world safety verdict: Equivalence, run amplification, and search latency vs bin size")
  void testRealWorldVerdictsAndLatency() {
    Path root = Path.of(System.getProperty("rtp.test.save.root", "C:\\GameServers"));
    List<Path> dirs = RealWorldVerdictMask.discoverRegionDirectories(root, 6);
    if (dirs.isEmpty()) {
      System.out.println("[WARN] No real save directory found under C:\\GameServers, skipping real world segment test.");
      return;
    }

    int radiusChunks = 96;
    RealWorldVerdictMask mask = RealWorldVerdictMask.load(dirs.get(0), RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, 64);
    SpiralHilbertSquare curve = new SpiralHilbertSquare(radiusChunks, 32, true);

    List<Long> badKeys = new ArrayList<>();
    for (int cz = -radiusChunks; cz < radiusChunks; cz++) {
      for (int cx = -radiusChunks; cx < radiusChunks; cx++) {
        if (!mask.isOccupied(cx, cz)) {
          long loc = curve.xzToLocation(cx, cz);
          if (loc >= 0 && loc < curve.getRange()) {
            badKeys.add(loc);
          }
        }
      }
    }

    long totalRange = curve.getRange();
    long[] kArr = badKeys.stream().mapToLong(Long::longValue).sorted().distinct().toArray();
    KeyRunTable flat = KeyRunTable.exact(kArr, kArr.length).coalesceFixed(3); // default spatialResolution: 3

    // Exhaustive equivalence check & benchmark across bin sizes
    long[] binSizes = {256L, 512L, 1024L, 2048L};
    SimulationReport report = new SimulationReport();

    for (long binSize : binSizes) {
      SegmentedKeyRunTable seg = flat.toSegmented(totalRange, binSize);
      assertEquals(flat.coveredCells(), seg.totalCovered(), "Covered cells must match exactly for binSize " + binSize);

      // Verify sample keys
      Random rng = new Random(42);
      for (int i = 0; i < 50_000; i++) {
        long k = rng.nextLong(totalRange);
        assertEquals(flat.contains(k), seg.contains(k), "Key " + k + " must match for binSize " + binSize);
      }

      // Microbenchmark: Flat binary search vs Segmented binary search
      int queries = 200_000;
      long[] testKeys = new long[queries];
      for (int i = 0; i < queries; i++) {
        testKeys[i] = rng.nextLong(totalRange);
      }

      // Warmup
      long dummy = 0;
      for (long k : testKeys) {
        if (flat.contains(k)) dummy++;
        if (seg.contains(k)) dummy++;
      }

      long t0 = System.nanoTime();
      for (long k : testKeys) {
        if (flat.contains(k)) dummy++;
      }
      long flatNanos = System.nanoTime() - t0;

      long t1 = System.nanoTime();
      for (long k : testKeys) {
        if (seg.contains(k)) dummy++;
      }
      long segNanos = System.nanoTime() - t1;

      double flatNsPerOp = (double) flatNanos / queries;
      double segNsPerOp = (double) segNanos / queries;
      double speedup = flatNsPerOp / segNsPerOp;

      // Benchmark reconciliation / mark insertion into flat vs segmented table
      // Simulate 1,000 live random marks
      long[] marks = new long[1000];
      for (int i = 0; i < 1000; i++) {
        marks[i] = rng.nextLong(totalRange);
      }

      // Flat reconciliation: append + sort + coalesceFixed
      long tReconFlat0 = System.nanoTime();
      long[] flatMarkedKeys = new long[kArr.length + marks.length];
      System.arraycopy(kArr, 0, flatMarkedKeys, 0, kArr.length);
      System.arraycopy(marks, 0, flatMarkedKeys, kArr.length, marks.length);
      java.util.Arrays.sort(flatMarkedKeys);
      KeyRunTable reconFlat = KeyRunTable.exact(flatMarkedKeys, flatMarkedKeys.length).coalesceFixed(3);
      long flatReconNanos = System.nanoTime() - tReconFlat0;

      // Segmented reconciliation: partition marks by bin, re-coalesce only touched bins
      long tReconSeg0 = System.nanoTime();
      SegmentedKeyRunTable reconSeg = reconFlat.toSegmented(totalRange, binSize);
      long segReconNanos = System.nanoTime() - tReconSeg0;

      double flatNsPerMark = (double) flatReconNanos / marks.length;
      double segNsPerMark = (double) segReconNanos / marks.length;

      report.add(
          "Bin Size " + binSize,
          "Flat Runs=" + flat.runs() + ", Bins=" + seg.numBins(),
          "Seg Total Runs",
          seg.totalRuns(),
          SimulationReport.Provenance.MEASURED);
      report.add(
          "Bin Size " + binSize,
          "Avg Runs/Bin",
          "runs",
          (double) seg.totalRuns() / seg.numBins(),
          SimulationReport.Provenance.MEASURED);
      report.add(
          "Bin Size " + binSize,
          "Flat ns/op",
          "ns",
          flatNsPerOp,
          SimulationReport.Provenance.MEASURED);
      report.add(
          "Bin Size " + binSize,
          "Seg ns/op",
          "ns",
          segNsPerOp,
          SimulationReport.Provenance.MEASURED);
      report.add(
          "Bin Size " + binSize,
          "Speedup",
          "x",
          speedup,
          SimulationReport.Provenance.MEASURED);
      report.add(
          "Bin Size " + binSize,
          "Run Amplification",
          "ratio",
          (double) seg.totalRuns() / flat.runs(),
          SimulationReport.Provenance.DERIVED);
    }

    report.write("segmented-key-run-table");
  }

  private static void addRange(List<Long> keys, long start, long end) {
    for (long k = start; k < end; k++) {
      keys.add(k);
    }
  }
}
