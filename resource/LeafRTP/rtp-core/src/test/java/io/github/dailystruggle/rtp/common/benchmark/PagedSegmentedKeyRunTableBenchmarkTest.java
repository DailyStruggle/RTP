package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Validates bounded memory scaling and low-thrashing single-page swapping for extreme world domains
 * via {@link PagedSegmentedKeyRunTable}.
 *
 * <p>Tests:
 * <ul>
 *   <li><b>Default State:</b> Unloaded/untouched bins default to SOLID_FULL with zero allocations.
 *   <li><b>Single-Page Eviction Under Budget:</b> Adding mixed bins beyond resident page capacity
 *       evicts cold pages one at a time to storage, maintaining strictly bounded JVM heap.
 *   <li><b>Page Fault Accuracy:</b> Querying paged bins seamlessly faults in the exact 4kB page
 *       with 100.00% coordinate equivalence.
 *   <li><b>Equivalent Page Cycling:</b> Periodic cycling of equivalent paged pages rotates cache
 *       residency without thrashing.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
@Tag("simulation")
class PagedSegmentedKeyRunTableBenchmarkTest {

  @Test
  @DisplayName("Default state: untouched domain assumes empty (valid) with 0 heap arrays to allow new land selection")
  void testDefaultStateAssumesEmptyValidForNewLand() {
    long totalRange = 1_000_000L; // 1M chunks
    long binSize = 1_000L;
    int numBins = (int) (totalRange / binSize);
    int maxResident = 4;

    PagedSegmentedKeyRunTable.InMemoryMockStorage storage = new PagedSegmentedKeyRunTable.InMemoryMockStorage();
    PagedSegmentedKeyRunTable table = new PagedSegmentedKeyRunTable(totalRange, binSize, numBins, maxResident, storage);

    // 1. Initial state: 0 resident mixed pages allocated
    assertEquals(0, table.residentPageCount(), "Initial resident mixed pages must be 0");
    assertEquals(0L, table.totalCovered(), "Entire domain defaults to assumed valid (0 bad chunks)");

    // All keys report NOT covered (safe land available for selection/generation)
    assertFalse(table.contains(0L), "Key 0 assumed valid for new land selection");
    assertFalse(table.contains(500_000L), "Key 500,000 assumed valid for new land selection");
    assertFalse(table.contains(999_999L), "Key 999,999 assumed valid for new land selection");

    // Can resolve good targets across the entire domain without starvation
    assertEquals(0L, table.resolveAccumulate(0L));
    assertEquals(500_000L, table.resolveAccumulate(500_000L));
    assertEquals(999_999L, table.resolveAccumulate(999_999L));
  }

  @Test
  @DisplayName("Bounded capacity: drops cold mixed pages one at a time to stay in bounds")
  void testBoundedCapacitySinglePageDropping() {
    long totalRange = 10_000L;
    long binSize = 1_000L;
    int numBins = (int) (totalRange / binSize);
    int maxResident = 2; // Strict bound: at most 2 mixed pages in RAM

    PagedSegmentedKeyRunTable.InMemoryMockStorage storage = new PagedSegmentedKeyRunTable.InMemoryMockStorage();
    PagedSegmentedKeyRunTable table = new PagedSegmentedKeyRunTable(totalRange, binSize, numBins, maxResident, storage);

    // Bin 0: Solid empty (100% good land)
    table.putBinData(0, new int[0], new int[0], 0);
    assertEquals(PagedSegmentedKeyRunTable.BinState.SOLID_EMPTY, table.binState(0));
    assertEquals(0, table.residentPageCount(), "Solid empty requires 0 resident page allocations");

    // Bin 1: Mixed (runs [100, 200), [500, 600))
    table.putBinData(1, new int[] {100, 500}, new int[] {100, 100}, 2);
    assertEquals(1, table.residentPageCount(), "Bin 1 is resident");
    assertEquals(PagedSegmentedKeyRunTable.BinState.RESIDENT_MIXED, table.binState(1));

    // Bin 2: Mixed (runs [50, 150))
    table.putBinData(2, new int[] {50}, new int[] {100}, 1);
    assertEquals(2, table.residentPageCount(), "Bin 1 and Bin 2 are resident (at max capacity)");

    // Bin 3: Mixed (runs [200, 300)) -> Triggers single-page eviction of Bin 1!
    table.putBinData(3, new int[] {200}, new int[] {100}, 1);
    assertEquals(2, table.residentPageCount(), "Resident count strictly clamped at max 2");
    assertEquals(1, table.pageEvictions(), "Exactly 1 page evicted");
    assertEquals(PagedSegmentedKeyRunTable.BinState.PAGED_MIXED, table.binState(1), "Bin 1 evicted to PAGED_MIXED");
    assertEquals(PagedSegmentedKeyRunTable.BinState.RESIDENT_MIXED, table.binState(2), "Bin 2 remains resident");
    assertEquals(PagedSegmentedKeyRunTable.BinState.RESIDENT_MIXED, table.binState(3), "Bin 3 is resident");

    // Bin 4: Mixed (runs [400, 500)) -> Triggers single-page eviction of Bin 2!
    table.putBinData(4, new int[] {400}, new int[] {100}, 1);
    assertEquals(2, table.residentPageCount(), "Resident count still strictly 2");
    assertEquals(2, table.pageEvictions(), "Total 2 pages evicted one at a time");
    assertEquals(PagedSegmentedKeyRunTable.BinState.PAGED_MIXED, table.binState(2), "Bin 2 evicted to PAGED_MIXED");

    // Querying Bin 1 causes a page fault: loads Bin 1 back from storage, evicts Bin 3!
    assertEquals(0, table.pageFaults(), "No page faults yet");
    assertTrue(table.contains(1_150L), "Key 1150 in Bin 1 is bad");
    assertFalse(table.contains(1_050L), "Key 1050 in Bin 1 is good");
    assertEquals(1, table.pageFaults(), "Exactly 1 page fault to fetch Bin 1");
    assertEquals(2, table.residentPageCount(), "Resident count still 2");
    assertEquals(PagedSegmentedKeyRunTable.BinState.RESIDENT_MIXED, table.binState(1), "Bin 1 paged back to RESIDENT_MIXED");
    assertEquals(PagedSegmentedKeyRunTable.BinState.PAGED_MIXED, table.binState(3), "Bin 3 evicted to storage");
  }

  @Test
  @DisplayName("Accuracy and Equivalent Page Cycling: 100% equivalence under active page cycling")
  void testEquivalentPageCyclingAndAccurateResolution() {
    long totalRange = 5_000L;
    long binSize = 1_000L;
    int numBins = (int) (totalRange / binSize);
    int maxResident = 2;

    PagedSegmentedKeyRunTable.InMemoryMockStorage storage = new PagedSegmentedKeyRunTable.InMemoryMockStorage();
    PagedSegmentedKeyRunTable table = new PagedSegmentedKeyRunTable(totalRange, binSize, numBins, maxResident, storage);

    // Populate all 5 bins with mixed data
    for (int b = 0; b < numBins; b++) {
      int s1 = 100 * (b + 1);
      int l1 = 50;
      int s2 = 400 + (b * 20);
      int l2 = 80;
      table.putBinData(b, new int[] {s1, s2}, new int[] {l1, l2}, 2);
    }

    // Resident pages strictly bounded at 2, all 5 pages backed by storage (3 paged out, 2 resident)
    assertEquals(2, table.residentPageCount());
    assertEquals(5, storage.storedPages());

    // 1. Verify ACCUMULATE resolution across all good targets
    long totalGood = totalRange - table.totalCovered();
    assertTrue(totalGood > 0);

    for (long t = 0; t < 100; t++) {
      long target = (t * 31L) % totalGood;
      long loc = table.resolveAccumulate(target);
      assertTrue(loc >= 0 && loc < totalRange, "Resolved location within range");
      assertFalse(table.contains(loc), "Resolved location " + loc + " must be good");
    }

    // 2. Test Equivalent Page Cycling
    // Find a paged bin
    int pagedBin = -1;
    for (int b = 0; b < numBins; b++) {
      if (table.binState(b) == PagedSegmentedKeyRunTable.BinState.PAGED_MIXED) {
        pagedBin = b;
        break;
      }
    }
    assertTrue(pagedBin >= 0, "Must have at least one paged bin");

    long initialEvictions = table.pageEvictions();
    boolean cycled = table.cycleEquivalentPage(pagedBin);
    assertTrue(cycled, "Cycle must succeed for paged bin");
    assertEquals(initialEvictions + 1, table.pageEvictions(), "Cycling evicted 1 cold page");
    assertEquals(PagedSegmentedKeyRunTable.BinState.RESIDENT_MIXED, table.binState(pagedBin), "Cycled bin is now resident");
    assertEquals(maxResident, table.residentPageCount(), "Resident count strictly at max capacity");
  }

  @Test
  @DisplayName("Seam and Paged Recovery: Forward and reverse seam crossings into paged bins")
  void testSeamCrossingsWithPagedBins() {
    long totalRange = 4_000L;
    long binSize = 1_000L;
    int numBins = (int) (totalRange / binSize);
    int maxResident = 2; // Can only hold 2 mixed bins in RAM

    PagedSegmentedKeyRunTable.InMemoryMockStorage storage = new PagedSegmentedKeyRunTable.InMemoryMockStorage();
    PagedSegmentedKeyRunTable table = new PagedSegmentedKeyRunTable(totalRange, binSize, numBins, maxResident, storage);

    // Bin 0: Ends right at the boundary ([800, 1000))
    table.putBinData(0, new int[] {800}, new int[] {200}, 1);

    // Bin 1: Starts right at the boundary offset 0 ([0, 200)), i.e. keys [1000, 1200)
    // Physically, Bin 0 and Bin 1 form one continuous bad run: [800, 1200)
    table.putBinData(1, new int[] {0}, new int[] {200}, 1);

    // Bins 0 and 1 are resident
    assertEquals(2, table.residentPageCount());

    // Bin 2: Add mixed data, evicting Bin 0 to PAGED_MIXED on disk!
    table.putBinData(2, new int[] {100}, new int[] {50}, 1);
    assertEquals(PagedSegmentedKeyRunTable.BinState.PAGED_MIXED, table.binState(0), "Bin 0 evicted to storage");
    assertEquals(PagedSegmentedKeyRunTable.BinState.RESIDENT_MIXED, table.binState(1), "Bin 1 is resident");

    // Bin 3: Add mixed data, evicting Bin 1 to PAGED_MIXED on disk!
    table.putBinData(3, new int[] {100}, new int[] {50}, 1);
    assertEquals(PagedSegmentedKeyRunTable.BinState.PAGED_MIXED, table.binState(1), "Bin 1 evicted to storage");

    // Both seam bins (Bin 0 and Bin 1) now reside on disk!
    assertEquals(0, table.pageFaults());

    // 1. Forward query into Bin 1 at offset 0 (key 1000):
    // Even though Bin 0 is on disk, Bin 1 owns its own pinned offset 0!
    // Triggers page fault for Bin 1, but does NOT need Bin 0.
    assertTrue(table.contains(1000L), "Key 1000 at seam must be bad");
    assertTrue(table.contains(1199L), "Key 1199 in Bin 1 must be bad");
    assertFalse(table.contains(1200L), "Key 1200 in Bin 1 must be good");
    assertEquals(1, table.pageFaults(), "Faulted only Bin 1");

    // 2. Reverse query into Bin 0 at key 999 (the trailing edge touching seam):
    // Triggers page fault for Bin 0.
    assertTrue(table.contains(999L), "Key 999 at trailing edge must be bad");
    assertTrue(table.contains(800L), "Key 800 in Bin 0 must be bad");
    assertFalse(table.contains(799L), "Key 799 in Bin 0 must be good");
    assertEquals(2, table.pageFaults(), "Faulted Bin 0");
  }
}
