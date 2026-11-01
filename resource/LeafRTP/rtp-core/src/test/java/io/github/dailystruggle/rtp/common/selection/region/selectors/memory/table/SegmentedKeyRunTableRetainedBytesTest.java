package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SegmentedKeyRunTableRetainedBytesTest {

  @Test
  @DisplayName("SegmentedKeyRunTable.retainedBytes() correctly computes memory footprint under sparse vs dense runs")
  public void testRetainedBytesSparseVsDense() {
    long range = 65_536L; // 64K range -> bins of 1024 chunks each (64 bins)
    long binSize = 1024L;

    // 1. Completely empty table (sparse: 0 runs, all bins empty)
    long[] emptyStarts = new long[0];
    long[] emptyLengths = new long[0];
    SegmentedKeyRunTable emptyTable = SegmentedKeyRunTable.fromRuns(
        emptyStarts, emptyLengths, 0, range, binSize, 0L
    );

    int numBins = emptyTable.numBins();
    assertEquals(64, numBins);
    for (int i = 0; i < numBins; i++) {
      assertTrue(emptyTable.bin(i).isEmpty());
    }

    // Expected bytes for empty table:
    // Base object header + fields = 48
    // Bin[] array = numBins * 8 + 24
    // dirBadPrefixSums array = numBins * 8 + 24
    // For each bin: 48 (Bin object header), and since b.isEmpty(), 0 array bytes!
    long expectedEmptyBytes = 48L
        + ((long) numBins * 8L + 24L)
        + ((long) numBins * 8L + 24L)
        + ((long) numBins * 48L);
    assertEquals(expectedEmptyBytes, emptyTable.retainedBytes());

    // 2. Completely full table (each bin is full)
    long[] fullStarts = new long[]{0L};
    long[] fullLengths = new long[]{range};
    SegmentedKeyRunTable fullTable = SegmentedKeyRunTable.fromRuns(
        fullStarts, fullLengths, 1, range, binSize, 0L
    );
    for (int i = 0; i < numBins; i++) {
      assertTrue(fullTable.bin(i).isFull());
    }
    // Full bins also share static FULL_START array and do not allocate per-bin starts/lengths arrays in retainedBytes calculation
    assertEquals(expectedEmptyBytes, fullTable.retainedBytes());

    // 3. Sparse runs table:
    // Only 2 bins have a single mixed run each, remaining 62 bins are empty
    // Bin 0 has run [10..20) -> 1 run
    // Bin 5 has run [5130..5140) -> 1 run
    long[] sparseStarts = new long[]{10L, 5130L};
    long[] sparseLengths = new long[]{10L, 10L};
    SegmentedKeyRunTable sparseTable = SegmentedKeyRunTable.fromRuns(
        sparseStarts, sparseLengths, 2, range, binSize, 0L
    );

    int nonTrivialBins = 0;
    long expectedArrayOverhead = 0L;
    for (int i = 0; i < numBins; i++) {
      SegmentedKeyRunTable.Bin b = sparseTable.bin(i);
      if (!b.isEmpty() && !b.isFull()) {
        nonTrivialBins++;
        // starts: length * 4 + 24
        // lengths: length * 4 + 24
        // badPrefixSums: length * 4 + 24
        int c = b.count();
        expectedArrayOverhead += (c * 4L + 24L) * 3L;
      }
    }
    assertEquals(2, nonTrivialBins);
    long expectedSparseBytes = expectedEmptyBytes + expectedArrayOverhead;
    assertEquals(expectedSparseBytes, sparseTable.retainedBytes());
    assertTrue(sparseTable.retainedBytes() > emptyTable.retainedBytes());

    // 4. Dense runs table:
    // Every bin has multiple alternating runs (e.g. 10 runs per bin)
    // 64 bins * 10 runs = 640 runs
    int runsPerBin = 10;
    int totalRuns = numBins * runsPerBin;
    long[] denseStarts = new long[totalRuns];
    long[] denseLengths = new long[totalRuns];
    int idx = 0;
    for (int b = 0; b < numBins; b++) {
      long bStart = b * binSize;
      for (int r = 0; r < runsPerBin; r++) {
        denseStarts[idx] = bStart + r * 50L;
        denseLengths[idx] = 20L;
        idx++;
      }
    }

    SegmentedKeyRunTable denseTable = SegmentedKeyRunTable.fromRuns(
        denseStarts, denseLengths, totalRuns, range, binSize, 0L
    );

    long expectedDenseArrayOverhead = 0L;
    for (int i = 0; i < numBins; i++) {
      SegmentedKeyRunTable.Bin b = denseTable.bin(i);
      assertFalse(b.isEmpty());
      assertFalse(b.isFull());
      assertEquals(runsPerBin, b.count());
      int c = b.count();
      expectedDenseArrayOverhead += (c * 4L + 24L) * 3L;
    }
    long expectedDenseBytes = expectedEmptyBytes + expectedDenseArrayOverhead;
    assertEquals(expectedDenseBytes, denseTable.retainedBytes());

    // Footprint of dense must be significantly higher than sparse
    assertTrue(denseTable.retainedBytes() > sparseTable.retainedBytes());
    // Specifically: dense array overhead is 64 bins * (10*4+24)*3 = 64 * 64 * 3 = 12,288 bytes
    // whereas sparse is 2 bins * (1*4+24)*3 = 2 * 28 * 3 = 168 bytes
    assertEquals(12288L, expectedDenseArrayOverhead);
    assertEquals(168L, expectedArrayOverhead);
    assertEquals(12288L - 168L, denseTable.retainedBytes() - sparseTable.retainedBytes());
  }
}
