package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DualLayerStrideQuotaRotationTest {

  @Test
  @DisplayName("AbstractDualLayerShape samples with per-draw dyadic rotation and zero duplicates")
  void testDyadicRotationAndZeroDuplicates() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 1024L);
    square.set(GenericMemoryShapeParams.centerRadius, 64L);

    int testSamples = 4096;
    Set<Long> seen = new HashSet<>();

    for (int i = 0; i < testSamples; i++) {
      long loc = square.rand();
      assertTrue(loc >= 0, "Selected location should be valid (>= 0)");
      boolean added = seen.add(loc);
      assertTrue(added, "Duplicate location found at index " + i + ": " + loc);
    }

    assertEquals(testSamples, seen.size(), "All 4,096 samples must be completely unique");
  }
}
