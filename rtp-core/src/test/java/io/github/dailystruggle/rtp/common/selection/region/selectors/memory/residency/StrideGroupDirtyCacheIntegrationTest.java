package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.residency;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.anvil.StorageLatencyProbe;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Validates integration between rotating bad location cache model, dirty caching,
 * dynamic bin swapping, quota-filling fallback, and stride-sharded batch persistence.
 */
class StrideGroupDirtyCacheIntegrationTest {

  @BeforeEach
  void setUp(@TempDir File tempDir) {
    RTPTestSetup.install(tempDir);
    StorageLatencyProbe.reset();
  }

  private static final class TestCursor implements SweepCursor {
    private final long[] bins;
    TestCursor(long[] bins) { this.bins = bins; }
    @Override public long currentSweepCounter() { return 0L; }
    @Override public long[] predictUpcomingBins(int lookahead) { return bins; }
  }

  @Test
  @DisplayName("Selective update: only resident bins are updated in RAM, non-resident stay in shard / dirty cache")
  void testSelectiveUpdateOnlyResidentBinsUpdated() {
    long binsPerGroup = 4L;
    long keysPerGroup = binsPerGroup * StrideGroupResidencyManager.CHUNKS_PER_BIN;
    long totalRange = 10L * keysPerGroup;

    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new TestCursor(new long[]{0L}), totalRange, binsPerGroup, Long.MAX_VALUE, 16, 64.0);

    // Group 0 is resident
    mgr.loadStrideGroup(0L);
    assertTrue(mgr.isResidentExact(0L), "Group 0 must be resident");
    // Group 1 is not resident
    assertFalse(mgr.isResidentExact(1L), "Group 1 must not be resident");

    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_SHAPE", 32);
    shape.set(GenericMemoryShapeParams.radius, 1000L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.setResidencyManager(mgr);

    long keyInGroup0 = 100L;
    long keyInGroup1 = keysPerGroup + 50L;

    // Mark key in group 0 (resident)
    shape.addBadLocation(keyInGroup0, LocationGenerator.FailTypes.biome);
    // Mark key in group 1 (non-resident)
    shape.addBadLocation(keyInGroup1, LocationGenerator.FailTypes.safety);

    // Both keys must report known bad (via dirty caching / shard)
    assertTrue(shape.isKnownBad(keyInGroup0), "Key in resident group 0 must be known bad");
    assertTrue(shape.isKnownBad(keyInGroup1), "Key in non-resident group 1 must be known bad");

    // Check manager resident status
    assertTrue(mgr.isResidentExact(0L), "Group 0 remains resident");
    assertFalse(mgr.isResidentExact(1L), "Group 1 must NOT be rematerialized into resident RAM by addBadLocation");

    // Shard contains marks
    assertTrue(mgr.isKnownBad(keyInGroup0));
    assertTrue(mgr.isKnownBad(keyInGroup1));
  }

  @Test
  @DisplayName("Stride batch persistence: saves batches with mapping table and supports arbitrary lookup")
  void testStrideBatchPersistenceAndArbitraryLookup(@TempDir File tempDir) throws IOException {
    long binsPerGroup = 2L;
    long keysPerGroup = binsPerGroup * StrideGroupResidencyManager.CHUNKS_PER_BIN;
    long totalRange = 10L * keysPerGroup;

    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new TestCursor(new long[0]), totalRange, binsPerGroup, Long.MAX_VALUE, 8, 64.0);

    // Populate group 0, group 2, group 5
    mgr.markBad(10L);
    mgr.markBad(25L);

    long g2Start = 2L * keysPerGroup;
    mgr.markBad(g2Start + 100L);
    mgr.markBad(g2Start + 200L);
    mgr.markBad(g2Start + 300L);

    long g5Start = 5L * keysPerGroup;
    mgr.markBad(g5Start + 77L);

    File batchFile = new File(tempDir, "stride_batch.bin");
    mgr.saveBatchShards(batchFile, 32L);

    assertTrue(batchFile.exists());
    assertTrue(batchFile.length() > 40L, "Batch file should have header and payload");

    // Arbitrary lookup of group 2 without loading entire file
    List<Long> g2Keys = StrideGroupResidencyManager.readBatchShardArbitrary(batchFile, 2L);
    assertNotNull(g2Keys);
    assertEquals(3, g2Keys.size());
    assertTrue(g2Keys.contains(g2Start + 100L));
    assertTrue(g2Keys.contains(g2Start + 200L));
    assertTrue(g2Keys.contains(g2Start + 300L));

    // Arbitrary lookup of group 5
    List<Long> g5Keys = StrideGroupResidencyManager.readBatchShardArbitrary(batchFile, 5L);
    assertNotNull(g5Keys);
    assertEquals(1, g5Keys.size());
    assertTrue(g5Keys.contains(g5Start + 77L));

    // Arbitrary lookup of non-existent group returns null
    assertNull(StrideGroupResidencyManager.readBatchShardArbitrary(batchFile, 99L));
  }

  @Test
  @DisplayName("Fallback to quota filling when disk is slower than quota filling")
  void testFallbackToQuotaFillingWhenDiskSlower() {
    long binsPerGroup = 4L;
    long keysPerGroup = binsPerGroup * StrideGroupResidencyManager.CHUNKS_PER_BIN;
    long totalRange = 10L * keysPerGroup;

    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new TestCursor(new long[0]), totalRange, binsPerGroup, Long.MAX_VALUE, 8, 64.0);

    // When no disk operations or probe shows fast device, isDiskSlowerThanQuotaFilling is false
    assertFalse(mgr.isDiskSlowerThanQuotaFilling());

    // Record high disk latency (e.g. 100 ms) compared to quota scan
    mgr.recordDiskTime(100_000_000L, 1024);
    // Devolve group to trigger quota scan timing
    for (long k = 0; k < 300; k++) {
      mgr.markBad(k);
    }
    mgr.devolveToCount(0L);

    assertTrue(mgr.getLastQuotaScanNanos() > 0L);
    assertTrue(mgr.isDiskSlowerThanQuotaFilling(), "Should indicate disk is slower than quota filling");
  }

  @Test
  @DisplayName("shouldSwapBins reflects residency manager and heap/disk conditions")
  void testShouldSwapBins() {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_SWAP", 32);
    shape.set(GenericMemoryShapeParams.radius, 500L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);

    // Without residency manager and without heap pressure, shouldSwapBins is false
    assertFalse(shape.shouldSwapBins());

    // With residency manager attached, shouldSwapBins is true
    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new TestCursor(new long[0]), 100_000L, 4L, Long.MAX_VALUE, 4, 64.0);
    shape.setResidencyManager(mgr);
    assertTrue(shape.shouldSwapBins());
    assertNull(shape.getHazardMirror(), "Hazard mirror should be null when swapping bins");
  }
}
