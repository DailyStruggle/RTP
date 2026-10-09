package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
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

  @Test
  @DisplayName("AbstractDualLayerShape handles multi-threaded phase rotation and domain exhaustion without crashing or deadlock")
  void testConcurrentSamplingAndEpochRatcheting() throws Exception {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("CONCURRENT_TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 128L);
    square.set(GenericMemoryShapeParams.centerRadius, 16L);

    int threadCount = 8;
    int samplesPerThread = 500;
    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.ConcurrentHashMap<Long, Boolean> results = new java.util.concurrent.ConcurrentHashMap<>();

    for (int t = 0; t < threadCount; t++) {
      executor.submit(() -> {
        try {
          for (int i = 0; i < samplesPerThread; i++) {
            long loc = square.rand();
            assertTrue(loc >= 0, "Selected location must be valid");
            results.put(loc, Boolean.TRUE);
          }
        } finally {
          latch.countDown();
        }
      });
    }

    boolean finished = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
    executor.shutdown();
    assertTrue(finished, "Concurrent sampling should complete within 10 seconds without deadlocks");
    assertFalse(results.isEmpty(), "Results must contain sampled locations");
  }

  @Test
  @DisplayName("setFeistelSalt resets selection and phase state atomically")
  void testFeistelSaltReset() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("RESET_TEST_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 128L);
    square.rand();
    square.rand();

    square.setFeistelSalt(999999L);
    assertEquals(999999L, square.feistelSalt);
    assertEquals(0, square.currentSweepCounter());
  }

  @Test
  @DisplayName("SegmentedKeyRunTable invalidation tracks published snapshot epoch without stale caching")
  void testSegmentedTableInvalidationOnSnapshotPublication() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("INVALIDATION_SQUARE", 32);
    square.set(GenericMemoryShapeParams.radius, 128L);
    square.set(GenericMemoryShapeParams.mode, "ACCUMULATE");

    long range = square.getEffectiveRange();
    SegmentedKeyRunTable table1 = square.getOrBuildSegmentedTable(range);
    assertNotNull(table1);
    assertEquals(0, table1.totalCovered());

    // Raw discovery adds to dirty cache without publishing snapshot yet
    square.addBadLocation(50L, io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.safety, 3600L);
    // Before flush, snapshot has not changed -> cached table returned
    SegmentedKeyRunTable tableSame = square.getOrBuildSegmentedTable(range);
    assertSame(table1, tableSame, "Segmented table should be cached while snapshot reference has not changed");

    // Flush and rebuild publishes new BadLocationsSnapshot
    square.flushAndRebuild(1L);
    SegmentedKeyRunTable table2 = square.getOrBuildSegmentedTable(range);
    assertNotNull(table2);
    assertNotSame(table1, table2, "Segmented table must be rebuilt after flushAndRebuild publishes a new snapshot");
    assertTrue(table2.totalCovered() > 0, "New segmented table must reflect newly published bad locations");
    assertTrue(table2.contains(50L), "New segmented table must contain bad location 50L");
  }
}
