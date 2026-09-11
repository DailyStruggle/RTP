package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.residency;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * ADR-092 (2026-09-10) sweep-predicted residency for stride-group bins.
 *
 * <p>Verifies prefetch-on-approach / release-behind driven by the selector cursor,
 * budget-breach devolution to memoryless counts (bitmask-tier only, over-scan,
 * never under-mark), and that a devolved+rematerialized group produces identical
 * {@code isKnownBad} / accumulate results on a seeded sweep.
 */
class StrideGroupResidencyTest {

  @BeforeEach
  void setUp(@TempDir File tempDir) {
    RTPTestSetup.install(tempDir);
  }

  private static SquareOptimizedDualLayer shape(String name, long radius) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer(name, 32);
    shape.set(GenericMemoryShapeParams.radius, radius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    return shape;
  }

  @Test
  @DisplayName("devolved + rematerialized group yields identical isKnownBad / accumulate on a seeded sweep")
  void devolveThenRematerialize_identical() {
    long binsPerGroup = 4L;
    long keysPerGroup = binsPerGroup * StrideGroupResidencyManager.CHUNKS_PER_BIN;
    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new FixedCursor(new long[0]), 1L << 22, binsPerGroup,
        Long.MAX_VALUE, 64, 64.0);

    // Seed a dense (bitmask-tier) group 0: >= 64 marks/bin over 4 bins.
    Random rng = new Random(0xC0FFEEL);
    Set<Long> marked = new TreeSet<>();
    while (marked.size() < 600) {
      marked.add((long) rng.nextInt((int) keysPerGroup));
    }
    for (long key : marked) {
      mgr.markBad(key);
    }

    mgr.loadStrideGroup(0L);
    assertTrue(mgr.isResidentExact(0L), "group should be resident after prefetch");

    // Capture exact behaviour while resident.
    boolean[] isBadBefore = new boolean[(int) keysPerGroup];
    for (int k = 0; k < keysPerGroup; k++) {
      isBadBefore[k] = mgr.isKnownBad(k);
    }
    long good = keysPerGroup - marked.size();
    long[] accBefore = new long[(int) good];
    for (int r = 0; r < good; r++) {
      accBefore[r] = mgr.resolveGroupAccumulate(0L, r);
    }

    // Devolve under (forced) budget breach: bitmask-tier so eligible.
    assertTrue(mgr.devolveToCount(0L), "dense bitmask-tier group must be devolvable");
    assertTrue(mgr.isDevolved(0L));
    assertFalse(mgr.isResidentExact(0L));
    // Over-scan, never under-mark: reaffirmed count covers every shard mark.
    assertTrue(mgr.devolvedCount(0L) >= marked.size(),
        "reaffirmed count must not under-represent the shard");

    // Rematerialize strictly via prefetch-on-approach.
    mgr.loadStrideGroup(0L);
    assertTrue(mgr.isResidentExact(0L), "group must rematerialize on approach");
    assertFalse(mgr.isDevolved(0L));

    // Identical isKnownBad + accumulate.
    for (int k = 0; k < keysPerGroup; k++) {
      assertEquals(isBadBefore[k], mgr.isKnownBad(k), "isKnownBad mismatch at local key " + k);
    }
    long[] accAfter = new long[(int) good];
    for (int r = 0; r < good; r++) {
      accAfter[r] = mgr.resolveGroupAccumulate(0L, r);
    }
    assertArrayEquals(accBefore, accAfter, "accumulate results must be identical after rematerialization");
  }

  @Test
  @DisplayName("sparse array/run groups are never devolved")
  void sparseGroupNeverDevolved() {
    long binsPerGroup = 4L;
    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new FixedCursor(new long[0]), 1L << 22, binsPerGroup,
        Long.MAX_VALUE, 64, 64.0);

    // Only a handful of marks: far below the bitmask-tier density floor.
    for (long key = 0; key < 10; key++) {
      mgr.markBad(key * 37L);
    }
    mgr.loadStrideGroup(0L);
    assertTrue(mgr.isResidentExact(0L));
    assertFalse(mgr.devolveToCount(0L), "sparse group must not be devolved");
    assertTrue(mgr.isResidentExact(0L), "sparse group stays exact");
    assertFalse(mgr.isDevolved(0L));
  }

  @Test
  @DisplayName("marks survive eviction (page-out) and devolution - never dropped")
  void marksNeverDropped() {
    long binsPerGroup = 4L;
    long keysPerGroup = binsPerGroup * StrideGroupResidencyManager.CHUNKS_PER_BIN;
    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new FixedCursor(new long[0]), 1L << 22, binsPerGroup,
        Long.MAX_VALUE, 64, 64.0);

    Random rng = new Random(7L);
    Set<Long> marked = new TreeSet<>();
    while (marked.size() < 500) {
      marked.add((long) rng.nextInt((int) keysPerGroup));
    }
    for (long key : marked) {
      mgr.markBad(key);
    }
    mgr.loadStrideGroup(0L);

    // Page-out is lossless.
    mgr.pageOut(0L);
    assertFalse(mgr.isResidentExact(0L));
    for (long key : marked) {
      assertTrue(mgr.isKnownBad(key), "page-out dropped a mark at " + key);
    }

    // Devolution is lossless for point checks too.
    mgr.loadStrideGroup(0L);
    assertTrue(mgr.devolveToCount(0L));
    for (long key : marked) {
      assertTrue(mgr.isKnownBad(key), "devolution dropped a mark at " + key);
    }
  }

  @Test
  @DisplayName("prefetch-on-approach loads upcoming groups; release-behind pages out the rest")
  void prefetchAndReleaseFromCursor() {
    SquareOptimizedDualLayer shape = shape("RESIDENCY_SWEEP", 1024L);
    long range = shape.getRange();
    assertTrue(range > 0);

    int lookahead = 64;
    long binsPerGroup = 8L;
    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        new DualLayerSweepCursor(shape), range, binsPerGroup,
        Long.MAX_VALUE, lookahead, 64.0);

    // First advance: resident groups == the distinct upcoming groups.
    long[] upcoming1 = shape.predictUpcomingBins(lookahead);
    Set<Long> groups1 = new LinkedHashSet<>();
    for (long bin : upcoming1) groups1.add(bin / binsPerGroup);
    mgr.advance();
    assertEquals(groups1.size(), mgr.residentGroupCount(),
        "resident groups must match distinct upcoming groups");
    for (long g : groups1) {
      assertTrue(mgr.isResidentExact(g), "upcoming group " + g + " must be prefetched");
    }

    // Move the cursor well past the first window.
    for (int i = 0; i < 5000; i++) {
      shape.selectL3Candidate();
    }
    long[] upcoming2 = shape.predictUpcomingBins(lookahead);
    Set<Long> groups2 = new LinkedHashSet<>();
    for (long bin : upcoming2) groups2.add(bin / binsPerGroup);

    mgr.advance();
    // Groups behind the cursor (no longer upcoming) are released.
    for (long g : groups1) {
      if (!groups2.contains(g)) {
        assertFalse(mgr.isResidentExact(g), "group " + g + " behind cursor must be released");
      }
    }
    for (long g : groups2) {
      assertTrue(mgr.isResidentExact(g), "new upcoming group " + g + " must be prefetched");
    }
  }

  @Test
  @DisplayName("budget breach devolves furthest bitmask-tier groups while keeping the nearest resident and losing no marks")
  void budgetBreachDevolvesFurthest() {
    long binsPerGroup = 4L;
    long keysPerGroup = binsPerGroup * StrideGroupResidencyManager.CHUNKS_PER_BIN;
    // Four upcoming groups (0..3) in access order; bin b -> group b (binsPerGroup=4, one bin each here).
    long[] upcomingBins = {0L, binsPerGroup, 2 * binsPerGroup, 3 * binsPerGroup};
    FixedCursor cursor = new FixedCursor(upcomingBins);

    // Budget only large enough for a couple of dense groups -> forces devolution.
    long perGroupBytes;
    {
      StrideGroupResidencyManager probe = new StrideGroupResidencyManager(
          cursor, 1L << 22, binsPerGroup, Long.MAX_VALUE, 8, 64.0);
      denselyMark(probe, 0L, keysPerGroup, 300);
      probe.loadStrideGroup(0L);
      perGroupBytes = probe.residentBytes(0L);
      assertTrue(perGroupBytes > 0);
    }
    long budget = perGroupBytes * 2 + perGroupBytes / 2; // room for ~2 groups

    StrideGroupResidencyManager mgr = new StrideGroupResidencyManager(
        cursor, 1L << 22, binsPerGroup, budget, 8, 64.0);
    List<Long> allMarks = new ArrayList<>();
    for (long g = 0; g < 4; g++) {
      long start = g * keysPerGroup;
      allMarks.addAll(denselyMark(mgr, start, keysPerGroup, 300));
    }

    mgr.advance();

    assertTrue(mgr.totalResidentBytes() <= budget, "resident footprint must be under budget");
    // Nearest group (index 0 in upcoming order) stays resident; furthest devolves first.
    assertTrue(mgr.isResidentExact(0L), "nearest upcoming group must stay resident");
    assertTrue(mgr.isDevolved(3L), "furthest upcoming group must devolve first");
    // No marks lost regardless of residency state.
    for (long key : allMarks) {
      assertTrue(mgr.isKnownBad(key), "budget devolution dropped a mark at " + key);
    }
  }

  private static List<Long> denselyMark(StrideGroupResidencyManager mgr, long start, long span, int count) {
    Random rng = new Random(start ^ 0x9E3779B9L);
    Set<Long> local = new TreeSet<>();
    while (local.size() < count) {
      local.add((long) rng.nextInt((int) span));
    }
    List<Long> global = new ArrayList<>(local.size());
    for (long k : local) {
      long key = start + k;
      mgr.markBad(key);
      global.add(key);
    }
    return global;
  }

  /** Deterministic {@link SweepCursor} stub returning a fixed upcoming-bin window. */
  private static final class FixedCursor implements SweepCursor {
    private final long[] bins;

    FixedCursor(long[] bins) {
      this.bins = bins;
    }

    @Override
    public long currentSweepCounter() {
      return 0L;
    }

    @Override
    public long[] predictUpcomingBins(int lookahead) {
      return bins;
    }
  }
}
