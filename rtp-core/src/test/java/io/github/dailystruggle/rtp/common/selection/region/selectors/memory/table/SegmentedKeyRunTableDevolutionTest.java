package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the scale-relative <b>devolution</b> of {@link SegmentedKeyRunTable}: below the measured
 * {@link SegmentedKeyRunTable#DEVOLUTION_THRESHOLD} the table collapses to a single flat bin (no
 * bin-directory overhead), and above it it partitions into a directory of bins. Correctness of
 * {@code contains} and {@code resolveAccumulate} is asserted identical to a brute-force reference in
 * both regimes, so devolution is a backend swap, not a semantics change.
 */
class SegmentedKeyRunTableDevolutionTest {

  @Test
  @DisplayName("deriveOptimalBinSize devolves to a single whole-range bin below the threshold")
  void devolvesBelowThreshold() {
    long threshold = SegmentedKeyRunTable.DEVOLUTION_THRESHOLD;

    // Below threshold: bin size spans the whole range -> exactly one bin.
    for (long range : new long[] {256L, 1_024L, 4_096L, threshold - 1L}) {
      long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(range);
      assertTrue(binSize >= range, "below-threshold binSize (" + binSize + ") must span range " + range);
      SegmentedKeyRunTable table = buildTable(range, binSize);
      assertEquals(1, table.numBins(), "range " + range + " must devolve to a single flat bin");
    }

    // At/above threshold: partitioned directory of many bins.
    for (long range : new long[] {threshold, 65_536L, 262_144L}) {
      long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(range);
      assertTrue(binSize < range, "at/above-threshold binSize (" + binSize + ") must be < range " + range);
      SegmentedKeyRunTable table = buildTable(range, binSize);
      assertTrue(table.numBins() > 1, "range " + range + " must partition into multiple bins");
    }
  }

  @Test
  @DisplayName("Devolved (single-bin) resolution matches brute force exactly")
  void devolvedResolutionMatchesBruteForce() {
    long range = 4_096L; // below threshold -> single bin
    long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(range);
    assertEquals(1, buildTable(range, binSize).numBins(), "precondition: single bin");

    // Runs: [50,150), [400,700), [1800,3200), [3500,3900)
    long[] starts = {50L, 400L, 1_800L, 3_500L};
    long[] lengths = {100L, 300L, 1_400L, 400L};
    SegmentedKeyRunTable table =
        SegmentedKeyRunTable.fromRuns(starts, lengths, starts.length, range, binSize, 0L);

    boolean[] bad = expand(range, starts, lengths);

    // contains parity
    for (long k = 0; k < range; k++) {
      assertEquals(bad[(int) k], table.contains(k), "contains mismatch at " + k);
    }

    // resolveAccumulate parity against the enumerated good slots
    List<Long> goodSlots = new ArrayList<>();
    for (long k = 0; k < range; k++) {
      if (!bad[(int) k]) goodSlots.add(k);
    }
    assertEquals(range - table.totalCovered(), goodSlots.size(), "good count must match");
    for (int t = 0; t < goodSlots.size(); t++) {
      assertEquals(
          (long) goodSlots.get(t),
          table.resolveAccumulate(t),
          "resolveAccumulate mismatch at target " + t);
    }
  }

  private static SegmentedKeyRunTable buildTable(long range, long binSize) {
    // One small run so the table is non-trivial but tiny.
    return SegmentedKeyRunTable.fromRuns(new long[] {1L}, new long[] {2L}, 1, range, binSize, 0L);
  }

  private static boolean[] expand(long range, long[] starts, long[] lengths) {
    boolean[] bad = new boolean[(int) range];
    for (int i = 0; i < starts.length; i++) {
      for (long k = starts[i]; k < starts[i] + lengths[i] && k < range; k++) {
        bad[(int) k] = true;
      }
    }
    return bad;
  }
}
