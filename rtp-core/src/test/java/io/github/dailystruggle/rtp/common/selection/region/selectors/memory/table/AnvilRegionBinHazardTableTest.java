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
}
