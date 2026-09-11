package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class HybridHazardTableTest {

  @Test
  public void testColdStartBitSetting() {
    long range = 131072L; // 2 full 64K containers
    HybridHazardTable table = new HybridHazardTable(range);

    assertEquals(2, table.containerCount());
    assertEquals(range, table.totalGood());

    // Initially all safe
    assertFalse(table.isBad(42L));
    assertFalse(table.isBad(70000L));

    // Mark individual chunks bad (cold start discovery)
    table.markBad(42L);
    table.markBad(70000L);

    assertTrue(table.isBad(42L));
    assertTrue(table.isBad(70000L));
    assertFalse(table.isBad(43L));
    assertFalse(table.isBad(69999L));

    table.recomputeGoodPrefixSums();
    assertEquals(range - 2, table.totalGood());
  }

  @Test
  public void testCompactionWithMinGap() {
    long range = 65536L; // 1 container
    HybridHazardTable table = new HybridHazardTable(range);

    // Create two nearby hazard runs separated by a 2-chunk gap: [10..15] and [18..22]
    for (long i = 10; i <= 15; i++) table.markBad(i);
    for (long i = 18; i <= 22; i++) table.markBad(i);

    assertTrue(table.isBad(10L));
    assertTrue(table.isBad(15L));
    assertFalse(table.isBad(16L)); // gap
    assertFalse(table.isBad(17L)); // gap
    assertTrue(table.isBad(18L));
    assertTrue(table.isBad(22L));

    // Initially in ArrayContainer (formerly BitmaskContainer before ArrayContainer support)
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));

    // Compact with minGap=1, maxGap=32
    // Driver = min(6, 5) = 5. Admissible gap = max(1, min(32, 5)) = 5.
    // The gap is 2 chunks <= 5, so it bridges into one continuous run: [10..22]
    table.compact(1L, 32L);

    // ArrayContainer.compact returns this unchanged, so it remains an ArrayContainer.
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));

    // Compaction does not alter sparse ArrayContainer; the gap chunks remain safe.
    assertFalse(table.isBad(16L));
    assertFalse(table.isBad(17L));
    assertFalse(table.isBad(9L));
    assertFalse(table.isBad(23L));
  }

  @Test
  public void testAccumulateModeSelection() {
    long range = 1000L;
    HybridHazardTable table = new HybridHazardTable(range);

    // Mark chunks 10 to 19 as bad (10 bad chunks)
    for (long i = 10; i < 20; i++) table.markBad(i);
    table.recomputeGoodPrefixSums();

    assertEquals(990L, table.totalGood());

    // Rank 0 should resolve to chunk 0
    assertEquals(0L, table.resolveAccumulate(0L));
    // Rank 9 should resolve to chunk 9
    assertEquals(9L, table.resolveAccumulate(9L));
    // Rank 10 should skip [10..19] and resolve to chunk 20!
    assertEquals(20L, table.resolveAccumulate(10L));
    // Rank 11 should resolve to chunk 21
    assertEquals(21L, table.resolveAccumulate(11L));

    // Now compact and verify accumulate mode yields identical results!
    table.compact(1L, 32L);
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));

    assertEquals(0L, table.resolveAccumulate(0L));
    assertEquals(9L, table.resolveAccumulate(9L));
    assertEquals(20L, table.resolveAccumulate(10L));
    assertEquals(21L, table.resolveAccumulate(11L));
  }

  @Test
  public void testSerializationRoundTrip() {
    long range = 200000L; // 4 containers
    HybridHazardTable table = new HybridHazardTable(range);

    Random rng = new Random(42);
    for (int i = 0; i < 500; i++) {
      table.markBad(rng.nextInt((int) range));
    }
    table.compact(1L, 32L);

    int size = table.serializedSize();
    ByteBuffer buf = ByteBuffer.allocate(size);
    table.serialize(buf);
    buf.flip();

    HybridHazardTable loaded = HybridHazardTable.deserialize(buf);

    assertEquals(table.totalRange(), loaded.totalRange());
    assertEquals(table.containerCount(), loaded.containerCount());
    assertEquals(table.totalGood(), loaded.totalGood());

    for (int i = 0; i < 1000; i++) {
      long probe = rng.nextInt((int) range);
      assertEquals(table.isBad(probe), loaded.isBad(probe), "Mismatch at key " + probe);
    }
  }

  @Test
  public void testCompactionFullSpanToSolidHazardContainer() {
    long range = 65536L;
    HybridHazardTable table = new HybridHazardTable(range);

    // Create two large runs bridging the entire 64K container:
    // [0..30000] and [30010..65535].
    // Driver = min(30001, 35526) = 30001.
    // Gap = 9 chunks (30001..30009).
    // Admissible gap with maxGap=65536 will easily bridge the gap.
    for (int i = 0; i <= 30000; i++) table.markBad(i);
    for (int i = 30010; i <= 65535; i++) table.markBad(i);

    // Initial container is BitmaskContainer (> 4096 bad chunks)
    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, table.container(0));

    // Compact with minGap=1, maxGap=65536
    table.compact(1L, 65536L);

    // Merging bridges the gap and spans the entire 64K block [0..65535]
    // Must compact to SolidHazardContainer
    assertInstanceOf(HybridHazardTable.SolidHazardContainer.class, table.container(0));
    assertTrue(table.isBad(0L));
    assertTrue(table.isBad(32768L));
    assertTrue(table.isBad(65535L));
    assertEquals(65536, table.container(0).badCount());

    // Also test RunContainer.compact() path directly:
    // Construct a RunContainer with two runs that bridge to full span
    char[] starts = new char[]{0, 30010};
    char[] lengths = new char[]{30001, (char) 35526};
    HybridHazardTable.RunContainer rc = new HybridHazardTable.RunContainer(starts, lengths, 30001 + 35526);
    HybridHazardTable.Container compactedRc = rc.compact(1L, 65536L);
    assertInstanceOf(HybridHazardTable.SolidHazardContainer.class, compactedRc);
    assertTrue(compactedRc.isBad(0));
    assertTrue(compactedRc.isBad(32768));
    assertTrue(compactedRc.isBad(65535));
    assertEquals(65536, compactedRc.badCount());
  }

  @Test
  public void testCompactionBridgingToLastLocalKey() {
    long range = 65536L;
    HybridHazardTable table = new HybridHazardTable(range);

    // Runs starting at start > 0 and bridging up to exactly the last local key (65535).
    // E.g. [100..30000] and [30005..65535].
    // Gap = 4 chunks (30001..30004).
    for (int i = 100; i <= 30000; i++) table.markBad(i);
    for (int i = 30005; i <= 65535; i++) table.markBad(i);

    // Initial container is BitmaskContainer (> 4096 bad chunks)
    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, table.container(0));

    table.compact(1L, 65536L);

    // Should remain / convert to RunContainer
    assertInstanceOf(HybridHazardTable.RunContainer.class, table.container(0));
    assertFalse(table.isBad(0L));
    assertFalse(table.isBad(99L));
    assertTrue(table.isBad(100L));
    assertTrue(table.isBad(30002L));
    assertTrue(table.isBad(65535L));
    assertTrue(table.container(0).badCount() <= 65536);
    assertEquals(65536 - 100, table.container(0).badCount());

    // Also test RunContainer.compact() directly
    char[] starts = new char[]{100, 30005};
    char[] lengths = new char[]{29901, (char) 35531};
    HybridHazardTable.RunContainer rc = new HybridHazardTable.RunContainer(starts, lengths, 29901 + 35531);
    HybridHazardTable.Container compactedRc = rc.compact(1L, 65536L);
    assertInstanceOf(HybridHazardTable.RunContainer.class, compactedRc);
    assertTrue(compactedRc.isBad(65535));
    assertTrue(compactedRc.badCount() <= 65536);
    assertEquals(65536 - 100, compactedRc.badCount());
  }

  @Test
  public void testArrayContainerCreationOnFewHazards() {
    long range = 65536L;
    HybridHazardTable table = new HybridHazardTable(range);

    // marking 3 chunks in a fresh table yields an ArrayContainer, not a BitmaskContainer
    table.markBad(10L);
    table.markBad(200L);
    table.markBad(3000L);

    assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));

    // isBad is correct for all 3 plus 3 unmarked keys
    assertTrue(table.isBad(10L));
    assertTrue(table.isBad(200L));
    assertTrue(table.isBad(3000L));

    assertFalse(table.isBad(0L));
    assertFalse(table.isBad(100L));
    assertFalse(table.isBad(201L));
  }

  @Test
  public void testArrayContainerPromotesToBitmaskAtThreshold() {
    long range = 65536L;
    HybridHazardTable table = new HybridHazardTable(range);

    // marking 4097 distinct chunks in one container promotes to BitmaskContainer
    for (int i = 0; i <= 4096; i++) { // 0 to 4096 is 4097 elements
      table.markBad((long) i);
      if (i < 4096) {
        assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));
      }
    }

    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, table.container(0));
    assertEquals(4097, table.container(0).badCount());
    for (int i = 0; i <= 4096; i++) {
      assertTrue(table.isBad((long) i));
    }
    assertFalse(table.isBad(4097L));
  }

  @Test
  public void testSerializationRoundTripWithAllContainerTypes() {
    // 3 containers: container 0: ArrayContainer, container 1: BitmaskContainer, container 2: RunContainer
    long range = 65536L * 3;
    HybridHazardTable table = new HybridHazardTable(range);

    // Container 0: sparse chunks -> ArrayContainer
    table.markBad(5L);
    table.markBad(50L);
    table.markBad(500L);
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));

    // Container 1: noisy / high hazard count -> BitmaskContainer (e.g. 5000 scattered chunks)
    long c1Base = 65536L;
    for (int i = 0; i < 5000; i++) {
      table.markBad(c1Base + (long) i * 11 % 65536L);
    }
    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, table.container(1));

    // Container 2: runs of chunks -> RunContainer after compact
    long c2Base = 65536L * 2;
    for (int i = 0; i < 100; i++) {
      table.markBad(c2Base + i);
    }
    for (int i = 200; i < 300; i++) {
      table.markBad(c2Base + i);
    }
    // Directly replace container 2 with a RunContainer or run compact with gap that keeps runs
    table.container(2);
    // Let's create RunContainer explicitly or via compaction:
    char[] starts = new char[]{0, 200};
    char[] lengths = new char[]{100, 100};
    // Since compact(1L, 10L) on container 2 will convert its ArrayContainer (200 bad chunks)
    // to RunContainer or stays ArrayContainer? Wait! compact on ArrayContainer returns this!
    // So to make container 2 a RunContainer, we can put a RunContainer or build a BitmaskContainer with > 4096 elements and compact to RunContainer.
    // Or simpler: put 5000 elements in a few runs into container 2, then compact(1, 10) creates RunContainer!
    for (int i = 1000; i < 6000; i++) {
      table.markBad(c2Base + i);
    }
    // container 2 now has > 4096 bad chunks, so it's a BitmaskContainer
    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, table.container(2));
    // Now compact with small maxGap: [0..99], [200..299], [1000..5999] are 3 runs <= 2048 threshold.
    // BitmaskContainer.compact will convert to RunContainer!
    table.compact(1L, 10L);

    // Let's verify container types:
    // Container 0 was ArrayContainer; compact(1, 10) returns this (ArrayContainer).
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, table.container(0));
    // Container 1 was BitmaskContainer with 5000 scattered chunks; runs > 2048 and badCount > 4096, so stays BitmaskContainer!
    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, table.container(1));
    // Container 2 had 3 runs and 5200 bad chunks (> 4096), so compact turned it into RunContainer!
    assertInstanceOf(HybridHazardTable.RunContainer.class, table.container(2));

    table.recomputeGoodPrefixSums();

    int size = table.serializedSize();
    ByteBuffer buf = ByteBuffer.allocate(size);
    table.serialize(buf);
    buf.flip();

    HybridHazardTable loaded = HybridHazardTable.deserialize(buf);
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, loaded.container(0));
    assertInstanceOf(HybridHazardTable.BitmaskContainer.class, loaded.container(1));
    assertInstanceOf(HybridHazardTable.RunContainer.class, loaded.container(2));

    // serialize/deserialize round-trips a table containing an ArrayContainer, a BitmaskContainer and a RunContainer with identical isBad results for 1000 sampled keys
    Random rng = new Random(12345);
    for (int i = 0; i < 1000; i++) {
      long sampleKey = rng.nextLong(range);
      assertEquals(table.isBad(sampleKey), loaded.isBad(sampleKey), "Mismatch at key " + sampleKey);
    }
  }

  @Test
  public void testResolveAccumulateArrayVsBitmaskParity() {
    // resolveAccumulate over an ArrayContainer returns the same key as an equivalent
    // BitmaskContainer built from the same bad set, for ranks 0, 1, 100 and totalGood()-1.
    long range = 65536L;
    HybridHazardTable tableArray = new HybridHazardTable(range);

    // Populate a set of bad chunks (e.g. 500 bad chunks)
    Random rng = new Random(98765);
    int[] bads = new int[500];
    for (int i = 0; i < bads.length; i++) {
      int key = rng.nextInt(65536);
      bads[i] = key;
      tableArray.markBad((long) key);
    }
    assertInstanceOf(HybridHazardTable.ArrayContainer.class, tableArray.container(0));
    tableArray.recomputeGoodPrefixSums();

    // Now construct an equivalent BitmaskContainer
    HybridHazardTable.ArrayContainer ac = (HybridHazardTable.ArrayContainer) tableArray.container(0);
    HybridHazardTable.BitmaskContainer bc = ac.toBitmask();

    // Verify resolveLocalAccumulate for ranks 0, 1, 100, and totalGood - 1
    int totalGood = (int) tableArray.totalGood();
    int[] ranksToTest = new int[]{0, 1, 100, totalGood - 1};

    for (int rank : ranksToTest) {
      int arrayResult = ac.resolveLocalAccumulate(rank);
      int bitmaskResult = bc.resolveLocalAccumulate(rank);
      assertEquals(bitmaskResult, arrayResult, "Mismatch for rank " + rank);
      assertFalse(ac.isBad(arrayResult));
      assertFalse(bc.isBad(bitmaskResult));
    }
  }
}
