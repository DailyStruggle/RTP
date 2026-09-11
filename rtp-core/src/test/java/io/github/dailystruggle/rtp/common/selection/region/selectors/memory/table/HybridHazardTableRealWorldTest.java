package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.benchmark.RealWorldVerdictMask;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("edge")
public class HybridHazardTableRealWorldTest {

  @Test
  public void testRealWorldMcaHybridTable() throws Exception {
    Path regionDir = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world\\dimensions\\minecraft\\overworld\\region");
    int regionFileCap = 256;

    System.out.println("\n[DEBUG_LOG] === LOADING REAL MCA DATA FOR HYBRID HAZARD TABLE ===");
    long startLoad = System.currentTimeMillis();
    RealWorldVerdictMask mask = RealWorldVerdictMask.load(regionDir, RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, regionFileCap);
    long loadTime = System.currentTimeMillis() - startLoad;

    System.out.printf("[DEBUG_LOG] Loaded %d real MCA files in %.2f seconds%n", mask.regionFilesRead(), loadTime / 1000.0);

    int inscribedR = Math.min(mask.inscribedRadius(), 192);
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("SQUARE_REAL_HYBRID", 32);
    square.set(GenericMemoryShapeParams.radius, (long) inscribedR);
    square.set(GenericMemoryShapeParams.centerRadius, 0L);
    square.set(GenericMemoryShapeParams.centerX, 0L);
    square.set(GenericMemoryShapeParams.centerZ, 0L);

    long totalRange = square.getRange();
    System.out.printf("[DEBUG_LOG] World Range: %,d chunks (Radius: %d chunks)%n", totalRange, inscribedR);

    // 1. Ingest real MCA chunks into HybridHazardTable via markBad (simulating cold start or raw scan)
    HybridHazardTable table = new HybridHazardTable(totalRange);
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    long rawBadCount = 0;
    long startIngest = System.nanoTime();
    for (long loc = 0; loc < totalRange; loc++) {
      square.locationToXZ(loc, coords);
      if (Math.abs(coords.x) > inscribedR || Math.abs(coords.z) > inscribedR || !mask.isOccupied(coords.x, coords.z)) {
        table.markBad(loc);
        rawBadCount++;
      }
    }
    long ingestDuration = System.nanoTime() - startIngest;

    System.out.printf("[DEBUG_LOG] Ingested %,d chunks into HybridHazardTable in %.2f ms (%.1f ns/chunk)%n",
        totalRange, ingestDuration / 1_000_000.0, (double) ingestDuration / totalRange);
    System.out.printf("[DEBUG_LOG] Raw Bad Chunks: %,d (%.1f%% of world)%n", rawBadCount, (rawBadCount * 100.0) / totalRange);

    table.recomputeGoodPrefixSums();
    long rawGood = table.totalGood();
    assertEquals(totalRange - rawBadCount, rawGood);

    // Initial state: BitmaskContainer size
    int rawSerializedSize = table.serializedSize();
    System.out.printf("[DEBUG_LOG] Initial Raw Bitmask Size: %,d bytes (%.2f KB)%n", rawSerializedSize, rawSerializedSize / 1024.0);

    // 2. Test compaction with minGap=1, maxGap=32
    long startCompact = System.currentTimeMillis();
    table.compact(1L, 32L);
    long compactTime = System.currentTimeMillis() - startCompact;

    int compactedSize = table.serializedSize();
    System.out.printf("[DEBUG_LOG] Compacted Size (minGap=1, maxGap=32): %,d bytes (%.2f KB) in %d ms%n",
        compactedSize, compactedSize / 1024.0, compactTime);

    // Breakdown of containers
    int unalloc = 0, land = 0, hazard = 0, bitmask = 0, runs = 0;
    for (int i = 0; i < table.containerCount(); i++) {
      byte tag = table.container(i).tag();
      switch (tag) {
        case HybridHazardTable.TAG_UNALLOCATED -> unalloc++;
        case HybridHazardTable.TAG_SOLID_LAND -> land++;
        case HybridHazardTable.TAG_SOLID_HAZARD -> hazard++;
        case HybridHazardTable.TAG_BITMASK -> bitmask++;
        case HybridHazardTable.TAG_RUNS -> runs++;
      }
    }
    System.out.printf("[DEBUG_LOG] Containers: %d Total | Land=%d, Hazard=%d, Runs=%d, Bitmask=%d, Unalloc=%d%n",
        table.containerCount(), land, hazard, runs, bitmask, unalloc);

    // 3. Verify point queries against the ground truth mask
    long startQueries = System.nanoTime();
    int querySamples = 50_000;
    int mismatches = 0;
    for (int i = 0; i < querySamples; i++) {
      long probe = (long) (Math.random() * totalRange);
      square.locationToXZ(probe, coords);
      boolean expectedBad = (Math.abs(coords.x) > inscribedR || Math.abs(coords.z) > inscribedR || !mask.isOccupied(coords.x, coords.z));
      boolean actualBad = table.isBad(probe);
      // Under minGap=1 bridging, an actualBad might bridge across tiny gaps, but must NEVER say safe where expected is bad!
      if (expectedBad && !actualBad) {
        mismatches++;
      }
    }
    long queryDuration = System.nanoTime() - startQueries;

    System.out.printf("[DEBUG_LOG] Executed %,d point queries in %.2f ms (%.1f ns/query). Mismatches: %d%n",
        querySamples, queryDuration / 1_000_000.0, (double) queryDuration / querySamples, mismatches);
    assertEquals(0, mismatches, "HybridHazardTable must never mark a real hazard as safe!");

    // 4. Test Accumulate Mode Selection on real world data
    long totalSafe = table.totalGood();
    System.out.printf("[DEBUG_LOG] Safe Usable Chunks in Real World: %,d chunks%n", totalSafe);
    assertTrue(totalSafe > 0);

    long startAccum = System.nanoTime();
    int accumSamples = 10_000;
    int accumViolations = 0;
    for (int i = 0; i < accumSamples; i++) {
      long rank = (long) (Math.random() * totalSafe);
      long loc = table.resolveAccumulate(rank);
      assertTrue(loc >= 0 && loc < totalRange);
      if (table.isBad(loc)) {
        accumViolations++;
      }
    }
    long accumDuration = System.nanoTime() - startAccum;

    System.out.printf("[DEBUG_LOG] Resolved %,d Accumulate selections in %.2f ms (%.1f ns/select). Violations: %d%n",
        accumSamples, accumDuration / 1_000_000.0, (double) accumDuration / accumSamples, accumViolations);
    assertEquals(0, accumViolations, "Accumulate mode selections must be 100% safe!");

    // 5. Verify Serialization Round-Trip on real world data
    ByteBuffer buf = ByteBuffer.allocate(compactedSize);
    table.serialize(buf);
    buf.flip();

    HybridHazardTable loaded = HybridHazardTable.deserialize(buf);
    assertEquals(table.totalRange(), loaded.totalRange());
    assertEquals(table.containerCount(), loaded.containerCount());
    assertEquals(table.totalGood(), loaded.totalGood());
    assertEquals(table.serializedSize(), loaded.serializedSize());

    for (int i = 0; i < 10_000; i++) {
      long probe = (long) (Math.random() * totalRange);
      assertEquals(table.isBad(probe), loaded.isBad(probe));
    }
    System.out.println("[DEBUG_LOG] Real world binary serialization round-trip: 100% PERFECT PARITY!");
  }
}
