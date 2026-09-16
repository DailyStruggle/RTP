package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class AnvilRegionBinHazardTableTest {

  @Test
  public void testDiscardedFullBin() {
    long range = 10240L; // 10 bins of 1024 chunks each
    AnvilRegionBinHazardTable table = new AnvilRegionBinHazardTable(range);

    assertEquals(10, table.binCount());
    assertEquals(range, table.totalGood());

    // Discard bin 3 (e.g. solid deep ocean)
    table.discardBin(3);
    table.recomputeGoodPrefixSums();

    assertTrue(table.isBinDiscarded(3));
    assertFalse(table.isBinDiscarded(2));

    // Every key in bin 3 is bad
    for (int i = 0; i < 1024; i++) {
      assertTrue(table.isBad(3 * 1024L + i));
    }
    // Bin 2 is safe
    assertFalse(table.isBad(2 * 1024L));

    // Full discarded bin serialized size: exactly 1 byte tag!
    assertEquals(1, table.bin(3).serializedSize());
    assertEquals(range - 1024L, table.totalGood());
  }

  @Test
  public void testAccumulateSkipsDiscardedBin() {
    long range = 3072L; // 3 bins
    AnvilRegionBinHazardTable table = new AnvilRegionBinHazardTable(range);

    // Discard bin 1 (keys 1024..2047)
    table.discardBin(1);
    table.recomputeGoodPrefixSums();

    assertEquals(2048L, table.totalGood());

    // Rank 1023 -> key 1023 (end of bin 0)
    assertEquals(1023L, table.resolveAccumulate(1023L));
    // Rank 1024 -> skips entire bin 1 and resolves to key 2048 (start of bin 2)!
    assertEquals(2048L, table.resolveAccumulate(1024L));
    // Rank 1025 -> key 2049
    assertEquals(2049L, table.resolveAccumulate(1025L));
  }

  @Test
  public void testMixedBinCompactionAndSerialization() {
    long range = 2048L; // 2 bins
    AnvilRegionBinHazardTable table = new AnvilRegionBinHazardTable(range);

    // Mark chunks 50 to 55 in bin 0
    for (long i = 50; i <= 55; i++) table.markBad(i);
    // Mark chunks 58 to 62 in bin 0 (gap of 2 chunks)
    for (long i = 58; i <= 62; i++) table.markBad(i);

    // Initially BitmaskBin (131 bytes)
    assertEquals(131, table.bin(0).serializedSize());

    // Compact with minGap=1, maxGap=32: bridges [50..62]
    table.compact(1L, 32L);

    // After compaction, 1 run <= 32 runs, converts to compact RunBin (1+2+2+4 = 9 bytes!)
    assertInstanceOf(AnvilRegionBinHazardTable.RunBin.class, table.bin(0));
    assertEquals(9, table.bin(0).serializedSize());

    // Serialization round-trip
    int totalSize = table.serializedSize();
    ByteBuffer buf = ByteBuffer.allocate(totalSize);
    table.serialize(buf);
    buf.flip();

    AnvilRegionBinHazardTable loaded = AnvilRegionBinHazardTable.deserialize(buf);
    assertEquals(table.totalRange(), loaded.totalRange());
    assertEquals(table.totalGood(), loaded.totalGood());
    assertTrue(loaded.isBad(50L));
    assertTrue(loaded.isBad(56L)); // bridged gap
    assertTrue(loaded.isBad(62L));
    assertFalse(loaded.isBad(49L));
    assertFalse(loaded.isBad(63L));
  }

  @Test
  public void testRunBinCompactMergingAdjacentRuns() {
    // 1. Single run returns this
    char[] starts1 = new char[]{10};
    char[] lens1 = new char[]{20};
    AnvilRegionBinHazardTable.RunBin single = new AnvilRegionBinHazardTable.RunBin(starts1, lens1, 20);
    assertSame(single, single.compact(1L, 10L));

    // 2. Multiple adjacent runs that should be merged
    // Run 1: [100..109] (len 10)
    // Run 2: [112..119] (len 8)
    // Gap: 110..111 (len 2)
    // Driver = min(10, 8) = 8. minGap=1, maxGap=32 -> admissible = 8.
    // Gap 2 <= admissible 8, so merged run: start=100, len=(119 - 100 + 1) = 20.
    char[] starts2 = new char[]{100, 112};
    char[] lens2 = new char[]{10, 8};
    AnvilRegionBinHazardTable.RunBin rb2 = new AnvilRegionBinHazardTable.RunBin(starts2, lens2, 18);
    AnvilRegionBinHazardTable.BinContainer compacted = rb2.compact(1L, 32L);
    assertInstanceOf(AnvilRegionBinHazardTable.RunBin.class, compacted);
    AnvilRegionBinHazardTable.RunBin crb = (AnvilRegionBinHazardTable.RunBin) compacted;
    assertEquals(1, crb.runCount());
    assertEquals(20, crb.badCount());
    assertTrue(crb.isBad(100));
    assertTrue(crb.isBad(110)); // bridged gap
    assertTrue(crb.isBad(111)); // bridged gap
    assertTrue(crb.isBad(119));
    assertFalse(crb.isBad(99));
    assertFalse(crb.isBad(120));

    // 3. Runs that do NOT merge because gap exceeds admissible gap
    // Run 1: [200..202] (len 3)
    // Run 2: [250..255] (len 6)
    // Gap: 203..249 (len 47)
    // Driver = min(3, 6) = 3. minGap=1, maxGap=10 -> admissible = 3.
    // 250 > 200 + 3 + 3 = 206 -> not merged.
    char[] starts3 = new char[]{200, 250};
    char[] lens3 = new char[]{3, 6};
    AnvilRegionBinHazardTable.RunBin rb3 = new AnvilRegionBinHazardTable.RunBin(starts3, lens3, 9);
    AnvilRegionBinHazardTable.BinContainer notMerged = rb3.compact(1L, 10L);
    assertInstanceOf(AnvilRegionBinHazardTable.RunBin.class, notMerged);
    AnvilRegionBinHazardTable.RunBin crb3 = (AnvilRegionBinHazardTable.RunBin) notMerged;
    assertEquals(2, crb3.runCount());
    assertEquals(9, crb3.badCount());
    assertTrue(crb3.isBad(200));
    assertTrue(crb3.isBad(202));
    assertFalse(crb3.isBad(203));
    assertFalse(crb3.isBad(249));
    assertTrue(crb3.isBad(250));
    assertTrue(crb3.isBad(255));

    // 4. Merging that spans or reaches CHUNKS_PER_BIN (1024) -> collapses to FullDiscardedBin
    char[] startsFull = new char[]{0, 500};
    char[] lensFull = new char[]{400, 524}; // gap 100, total bad becomes 1024
    AnvilRegionBinHazardTable.RunBin rbFull = new AnvilRegionBinHazardTable.RunBin(startsFull, lensFull, 924);
    AnvilRegionBinHazardTable.BinContainer fullCompacted = rbFull.compact(200L, 500L);
    assertInstanceOf(AnvilRegionBinHazardTable.FullDiscardedBin.class, fullCompacted);
    assertEquals(AnvilRegionBinHazardTable.CHUNKS_PER_BIN, fullCompacted.badCount());
    for (int k = 0; k < 1024; k++) {
      assertTrue(fullCompacted.isBad(k));
    }
  }

  @Test
  public void testToBitmaskAndThresholdConversion() {
    // 1. RunBin.toBitmask() converting runs to 64-bit word bitmask
    char[] starts = new char[]{0, 63, 64, 127, 200};
    char[] lens = new char[]{2, 2, 1, 3, 5};
    int badCount = 2 + 2 + 1 + 3 + 5; // 13
    AnvilRegionBinHazardTable.RunBin runBin = new AnvilRegionBinHazardTable.RunBin(starts, lens, badCount);
    AnvilRegionBinHazardTable.BitmaskBin bitmaskBin = runBin.toBitmask();

    assertEquals(badCount, bitmaskBin.badCount());
    assertEquals(AnvilRegionBinHazardTable.TAG_BITMASK, bitmaskBin.tag());

    // Verify bits:
    // [0..1] in word 0: bits 0, 1
    assertTrue(bitmaskBin.isBad(0));
    assertTrue(bitmaskBin.isBad(1));
    assertFalse(bitmaskBin.isBad(2));

    // [63..64]: bit 63 of word 0 and bit 0 of word 1
    assertTrue(bitmaskBin.isBad(63));
    assertTrue(bitmaskBin.isBad(64));

    // [127..129]: bit 63 of word 1, bits 0 and 1 of word 2
    assertTrue(bitmaskBin.isBad(127));
    assertTrue(bitmaskBin.isBad(128));
    assertTrue(bitmaskBin.isBad(129));
    assertFalse(bitmaskBin.isBad(130));

    // [200..204]: in word 3 (200 >>> 6 = 3, 200 & 63 = 8)
    for (int k = 200; k <= 204; k++) {
      assertTrue(bitmaskBin.isBad(k));
    }
    assertFalse(bitmaskBin.isBad(199));
    assertFalse(bitmaskBin.isBad(205));

    // Marking in RunBin converts to bitmask and sets the bit
    AnvilRegionBinHazardTable.BinContainer marked = runBin.markBad(500);
    assertInstanceOf(AnvilRegionBinHazardTable.BitmaskBin.class, marked);
    assertTrue(marked.isBad(500));
    assertEquals(badCount + 1, marked.badCount());

    // 2. Compacting BitmaskBin when run count exceeds threshold RLE_BREAKEVEN_RUNS (32):
    // Construct 33 discrete 1-chunk runs separated by gaps of 10 chunks (minGap=0 so no bridging)
    AnvilRegionBinHazardTable.BitmaskBin denseBitmask = new AnvilRegionBinHazardTable.BitmaskBin();
    for (int r = 0; r < 33; r++) {
      denseBitmask = (AnvilRegionBinHazardTable.BitmaskBin) denseBitmask.markBad(r * 20);
    }
    assertEquals(33, denseBitmask.badCount());
    // With minGap=0, no merging occurs, so runs count remains 33 (> 32).
    // Compaction must remain BitmaskBin!
    AnvilRegionBinHazardTable.BinContainer denseCompacted = denseBitmask.compact(0L, 0L);
    assertInstanceOf(AnvilRegionBinHazardTable.BitmaskBin.class, denseCompacted);
    assertSame(denseBitmask, denseCompacted);

    // When runs count <= 32 (e.g. 32 runs), compaction converts to RunBin
    AnvilRegionBinHazardTable.BitmaskBin thirtyTwoRuns = new AnvilRegionBinHazardTable.BitmaskBin();
    for (int r = 0; r < 32; r++) {
      thirtyTwoRuns = (AnvilRegionBinHazardTable.BitmaskBin) thirtyTwoRuns.markBad(r * 20);
    }
    assertEquals(32, thirtyTwoRuns.badCount());
    AnvilRegionBinHazardTable.BinContainer thirtyTwoCompacted = thirtyTwoRuns.compact(0L, 0L);
    assertInstanceOf(AnvilRegionBinHazardTable.RunBin.class, thirtyTwoCompacted);
    assertEquals(32, ((AnvilRegionBinHazardTable.RunBin) thirtyTwoCompacted).runCount());
  }

  @Test
  public void testResolveLocalAccumulateBoundaryCoordinates() {
    // Test 32x32 region (1024 chunks) coordinate accumulation under various layouts:
    // Layout A: Leading safe gap, middle bad runs, trailing safe gap.
    // Safe: [0..9] (10 safe chunks)
    // Bad: [10..19] (10 chunks)
    // Safe: [20..49] (30 safe chunks)
    // Bad: [50..99] (50 chunks)
    // Safe: [100..1023] (924 safe chunks)
    // Total bad = 60, total good = 964.
    char[] starts = new char[]{10, 50};
    char[] lens = new char[]{10, 50};
    AnvilRegionBinHazardTable.RunBin runBin = new AnvilRegionBinHazardTable.RunBin(starts, lens, 60);
    AnvilRegionBinHazardTable.BitmaskBin bitmaskBin = runBin.toBitmask();

    int totalGood = 1024 - 60; // 964

    for (int rank = 0; rank < totalGood; rank++) {
      int expectedCoord;
      if (rank < 10) {
        expectedCoord = rank; // [0..9]
      } else if (rank < 10 + 30) {
        expectedCoord = 20 + (rank - 10); // [20..49]
      } else {
        expectedCoord = 100 + (rank - 40); // [100..1023]
      }
      assertEquals(expectedCoord, runBin.resolveLocalAccumulate(rank), "RunBin mismatch at rank " + rank);
      assertEquals(expectedCoord, bitmaskBin.resolveLocalAccumulate(rank), "BitmaskBin mismatch at rank " + rank);
    }

    // Boundary ranks:
    // First rank (0) -> 0
    assertEquals(0, runBin.resolveLocalAccumulate(0));
    assertEquals(0, bitmaskBin.resolveLocalAccumulate(0));

    // Rank 9 (last in first safe block) -> 9
    assertEquals(9, runBin.resolveLocalAccumulate(9));
    assertEquals(9, bitmaskBin.resolveLocalAccumulate(9));

    // Rank 10 (first after first bad run [10..19]) -> 20
    assertEquals(20, runBin.resolveLocalAccumulate(10));
    assertEquals(20, bitmaskBin.resolveLocalAccumulate(10));

    // Rank 39 (last in middle safe block [20..49]) -> 49
    assertEquals(49, runBin.resolveLocalAccumulate(39));
    assertEquals(49, bitmaskBin.resolveLocalAccumulate(39));

    // Rank 40 (first after second bad run [50..99]) -> 100
    assertEquals(100, runBin.resolveLocalAccumulate(40));
    assertEquals(100, bitmaskBin.resolveLocalAccumulate(40));

    // Last rank (963) -> 1023 (last chunk in 32x32 region)
    assertEquals(1023, runBin.resolveLocalAccumulate(963));
    assertEquals(1023, bitmaskBin.resolveLocalAccumulate(963));

    // Out-of-bounds ranks -> -1
    assertEquals(-1, runBin.resolveLocalAccumulate(-1));
    assertEquals(-1, bitmaskBin.resolveLocalAccumulate(-1));
    assertEquals(-1, runBin.resolveLocalAccumulate(964));
    assertEquals(-1, bitmaskBin.resolveLocalAccumulate(964));
    assertEquals(-1, runBin.resolveLocalAccumulate(1024));
    assertEquals(-1, bitmaskBin.resolveLocalAccumulate(1024));

    // Layout B: Bad run starting at 0 (no leading safe gap)
    char[] startsB = new char[]{0};
    char[] lensB = new char[]{5};
    AnvilRegionBinHazardTable.RunBin runBinB = new AnvilRegionBinHazardTable.RunBin(startsB, lensB, 5);
    AnvilRegionBinHazardTable.BitmaskBin bitmaskBinB = runBinB.toBitmask();

    assertEquals(5, runBinB.resolveLocalAccumulate(0));
    assertEquals(5, bitmaskBinB.resolveLocalAccumulate(0));
    assertEquals(1023, runBinB.resolveLocalAccumulate(1024 - 5 - 1));
    assertEquals(1023, bitmaskBinB.resolveLocalAccumulate(1024 - 5 - 1));

    // Layout C: Bad run ending at 1024 (no trailing safe gap)
    char[] startsC = new char[]{1020};
    char[] lensC = new char[]{4};
    AnvilRegionBinHazardTable.RunBin runBinC = new AnvilRegionBinHazardTable.RunBin(startsC, lensC, 4);
    AnvilRegionBinHazardTable.BitmaskBin bitmaskBinC = runBinC.toBitmask();

    assertEquals(0, runBinC.resolveLocalAccumulate(0));
    assertEquals(0, bitmaskBinC.resolveLocalAccumulate(0));
    assertEquals(1019, runBinC.resolveLocalAccumulate(1020 - 1));
    assertEquals(1019, bitmaskBinC.resolveLocalAccumulate(1020 - 1));
    assertEquals(-1, runBinC.resolveLocalAccumulate(1020));
    assertEquals(-1, bitmaskBinC.resolveLocalAccumulate(1020));

    // UnallocatedBin and FullDiscardedBin resolveLocalAccumulate
    assertEquals(0, AnvilRegionBinHazardTable.UnallocatedBin.INSTANCE.resolveLocalAccumulate(0));
    assertEquals(500, AnvilRegionBinHazardTable.UnallocatedBin.INSTANCE.resolveLocalAccumulate(500));
    assertEquals(1023, AnvilRegionBinHazardTable.UnallocatedBin.INSTANCE.resolveLocalAccumulate(1023));
    assertEquals(-1, AnvilRegionBinHazardTable.FullDiscardedBin.INSTANCE.resolveLocalAccumulate(0));
    assertEquals(-1, AnvilRegionBinHazardTable.FullDiscardedBin.INSTANCE.resolveLocalAccumulate(500));
  }
}
