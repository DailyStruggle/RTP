package io.github.dailystruggle.rtp.common.selection.region.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CacheMemoryCostUnitsApiTest {

  @Test
  void resolvesRawCountsAndExplicitMemorySizes() {
    assertEquals(7L, CacheMemoryCost.resolveCapacity(null, 7L, 128));
    assertEquals(9L, CacheMemoryCost.resolveCapacity(9, 7L, 128));
    assertEquals(1L, CacheMemoryCost.resolveCapacity(true, 7L, 128));
    assertEquals(0L, CacheMemoryCost.resolveCapacity(false, 7L, 128));
    assertEquals(7L, CacheMemoryCost.resolveCapacity("  ", 7L, 128));
    assertEquals(1L, CacheMemoryCost.resolveCapacity("TrUe", 7L, 128));
    assertEquals(0L, CacheMemoryCost.resolveCapacity("FALSE", 7L, 128));
    assertEquals(8L, CacheMemoryCost.resolveCapacity("1KiB", 7L, 128));
    assertEquals(-1L, CacheMemoryCost.resolveCapacity("-1MiB", 7L, 128));
    assertEquals(1_048_576L, CacheMemoryCost.resolveCapacity("1MiB", 7L, 0));
    assertEquals(12L, CacheMemoryCost.resolveCapacity("12.9", 7L, 128));
    assertEquals(0L, CacheMemoryCost.resolveCapacity("not-a-size", 7L, 128));
  }

  @Test
  void clampsIntegerCapacities() {
    assertEquals(0, CacheMemoryCost.resolveCapacityInt(-1L, 7, 128));
    assertEquals(Integer.MAX_VALUE, CacheMemoryCost.resolveCapacityInt((long) Integer.MAX_VALUE + 1, 7, 128));
    assertEquals(12, CacheMemoryCost.resolveCapacityInt("12", 7, 128));
  }
}
