package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-083 section 8 - the two-tier router exercised against a <b>real Minecraft save</b>.
 *
 * <p>The model proof ({@link TwoTierRouterModelBenchmarkTest}) runs on a synthetic mask chosen to
 * be adversarial. This test asks the different question: does the model behave the way the ADR
 * claims when the validity mask is whatever a real world happens to look like - clustered around
 * spawn, ragged at the frontier, with entire macro tiles holding nothing at all. Synthetic masks
 * are uniform in a way real saves never are, and every memory and uniformity figure in ADR-083
 * depends on that difference.
 *
 * <p><b>Data.</b> A save copied by hand into the gitignored {@code testdata-world/} directory (or
 * {@code -Drtp.simulation.worldRegionDir=...}). It is never committed - gigabytes of {@code .mca}
 * - and when it is absent this test skips rather than substituting invented data. Only the 4 KiB
 * location table of each region file is read; no chunk is inflated and nothing is written.
 *
 * <p>Nothing here approves ADR-083. It measures the proposal so the decision has evidence.
 */
@Tag("simulation")
@DisplayName("ADR-083 two-tier cell router - real world-data use case")
class TwoTierRouterWorldDataBenchmarkTest {

  private static final SimulationReport REPORT = new SimulationReport();

  /** Cap on the domain radius in chunks; keeps a brute-force ground truth affordable. */
  private static final int MAX_RADIUS = Integer.getInteger("rtp.simulation.twoTier.maxRadius", 512);

  private static final int DRAWS = Integer.getInteger("rtp.simulation.twoTier.draws", 200_000);

  private static WorldOccupancyMask mask;
  private static int radius;

  @BeforeAll
  static void setup() {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;

    Path dir = WorldOccupancyMask.resolveDirectory();
    if (!WorldOccupancyMask.available()) {
      REPORT.note("no save found at " + dir.toAbsolutePath() + "; world-data rows omitted");
      return;
    }
    long t0 = System.nanoTime();
    mask = WorldOccupancyMask.load(dir);
    long loadNanos = System.nanoTime() - t0;
    radius = Math.min(MAX_RADIUS, mask.inscribedRadius());

    int[] bounds = mask.chunkBounds();
    String section = "world data";
    REPORT.add(section, "save", "region files", Integer.toString(mask.regionFileCount()), Provenance.MEASURED);
    REPORT.add(section, "save", "region files with chunks", Integer.toString(mask.occupiedRegionFileCount()), Provenance.MEASURED);
    REPORT.add(section, "save", "chunks on disk", Long.toString(mask.occupiedChunks()), Provenance.MEASURED);
    REPORT.add(section, "save", "chunk bounds", Arrays.toString(bounds), Provenance.MEASURED);
    REPORT.add(section, "save", "location-table read ms", loadNanos / 1e6, Provenance.MEASURED);
    REPORT.add(section, "domain", "radius (cells = chunks)", Integer.toString(radius), Provenance.DERIVED);
  }

  @AfterAll
  static void writeReport() {
    REPORT.write("adr083-two-tier-world-data");
  }

  private static TwoTierCellRouter router(int r, int m) {
    TwoTierCellRouter shape = new TwoTierCellRouter(r, m, 0xA083L);
    shape.set(GenericMemoryShapeParams.radius, (long) radius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    shape.setValidity((x, z) -> mask.isOccupied(x, z));
    return shape;
  }

  /**
   * Rank of every good cell in the domain, indexed by {@code (x + radius) * side + (z + radius)},
   * or {@code -1} when the cell is bad or outside. The ground truth the descriptor is checked
   * against, built without consulting the descriptor.
   */
  private static int[] goodCellRanks(TwoTierCellRouter shape, int[] outCount) {
    int side = 2 * radius + 1;
    int[] rank = new int[side * side];
    Arrays.fill(rank, -1);
    int n = 0;
    for (int x = -radius; x <= radius; x++) {
      for (int z = -radius; z <= radius; z++) {
        if (!shape.inDomain(x, z)) continue;
        if (!mask.isOccupied(x, z)) continue;
        rank[(x + radius) * side + (z + radius)] = n++;
      }
    }
    outCount[0] = n;
    return rank;
  }

  @Test
  @DisplayName("(r, M) sweep on real save data: footprint, compile cost, selection cost, uniformity")
  void twoTierSweepOnRealSave() {
    assumeTrue(mask != null, "no save data present; copy a world into testdata-world/ to run this");
    assumeTrue(radius >= 64, "save is too small to host a meaningful domain");

    int[][] configs = {{1, 32}, {4, 32}, {16, 32}};
    for (int[] cfg : configs) {
      int r = cfg[0];
      int m = cfg[1];
      TwoTierCellRouter shape = router(r, m);
      String subject = "r=" + r + " M=" + m + " (macro edge " + shape.macroEdge() + ")";

      int[] count = new int[1];
      int[] rank = goodCellRanks(shape, count);
      int good = count[0];
      assertTrue(good > 0, "the save must leave good cells inside the domain");

      long t0 = System.nanoTime();
      TwoTierCellRouter.Snapshot snap = shape.compile();
      long compileNanos = System.nanoTime() - t0;

      assertEquals(
          good,
          snap.includedGoodCells(),
          "descriptor good-cell count must match brute force; a mismatch means the router draws "
              + "from a different population than it reports");

      String section = "two-tier on real save";
      REPORT.add(section, subject, "macro-cells drawable", Integer.toString(snap.macroCount()), Provenance.MEASURED);
      REPORT.add(section, subject, "macro-cells zero-weight (never drawn)", Integer.toString(snap.zeroWeightMacroCells()), Provenance.MEASURED);
      REPORT.add(section, subject, "good cells", Integer.toString(good), Provenance.MEASURED);
      REPORT.add(section, subject, "descriptor bytes", Long.toString(snap.residentBytes()), Provenance.DERIVED);
      REPORT.add(section, subject, "descriptor bytes per good cell", (double) snap.residentBytes() / good, Provenance.DERIVED);
      REPORT.add(section, subject, "compile pulse ms", compileNanos / 1e6, Provenance.MEASURED);

      // Selection: warm, then time, then check the distribution over the same draws.
      shape.setRng(new Random(0x5EEDL));
      for (int i = 0; i < 10_000; i++) shape.selectCell();

      final int bins = 64;
      long[] observed = new long[bins];
      long[] cellsPerBin = new long[bins];
      for (int i = 0; i < good; i++) cellsPerBin[(int) ((long) i * bins / good)]++;

      int side = 2 * radius + 1;
      long t1 = System.nanoTime();
      for (int i = 0; i < DRAWS; i++) {
        int[] cell = shape.selectCell();
        assertNotNull(cell, "a domain with good cells must always route to one");
        int idx = rank[(cell[0] + radius) * side + (cell[1] + radius)];
        assertTrue(idx >= 0, "the router returned a cell the save does not hold: " + cell[0] + "," + cell[1]);
        observed[(int) ((long) idx * bins / good)]++;
      }
      long selectNanos = System.nanoTime() - t1;

      double chiSquare = 0.0;
      for (int i = 0; i < bins; i++) {
        double expected = (double) DRAWS * cellsPerBin[i] / good;
        double d = observed[i] - expected;
        chiSquare += d * d / expected;
      }
      double p = TwoTierRouterModelBenchmarkTest.chiSquareUpperTail(chiSquare, bins - 1);

      REPORT.add(section, subject, "selection ns/op", (double) selectNanos / DRAWS, Provenance.MEASURED);
      REPORT.add(section, subject, "chi-square (63 dof)", chiSquare, Provenance.MEASURED);
      REPORT.add(section, subject, "p-value", p, Provenance.DERIVED);
      REPORT.add(section, subject, "sub-draw misses", Long.toString(shape.subDrawMisses()), Provenance.MEASURED);
      REPORT.add(section, subject, "zero-weight draws", Long.toString(shape.zeroWeightDraws()), Provenance.MEASURED);

      assertEquals(0L, shape.subDrawMisses(), "ADR-083 section 4a: the k-th good cell must always exist");
      assertEquals(0L, shape.zeroWeightDraws(), "GUARD: a zero-weight macro-cell must never be drawn");
      assertTrue(p >= 0.01, subject + ": uniformity rejected at p=" + p + " (chi-square " + chiSquare + ")");
    }

    REPORT.note(
        "Zero-weight macro-cells are the only I/O-avoidance guarantee ADR-083 makes. On this save "
            + "they are whole unexplored tiles, which is exactly the case a caller mapping a "
            + "macro-cell onto a region file would otherwise pay a cold .mca open for.");
    REPORT.note(
        "Descriptor bytes are the flat-array measurement vehicle, not ADR-083 section 7's blocked "
            + "layout, so the macro-tier share of every footprint row is an upper bound.");
  }

  @Test
  @DisplayName("ADR-001 spiral baseline on the same save: run count and rebuild cost")
  void spiralBaselineOnSameSave() {
    assumeTrue(mask != null, "no save data present; copy a world into testdata-world/ to run this");
    assumeTrue(radius >= 64, "save is too small to host a meaningful domain");
    // The spiral baseline marks every bad cell individually through the canonical 1D key, which is
    // O(domain) marks plus amortized merges. Kept behind its own radius knob so the sweep above
    // stays runnable when the baseline is the expensive arm.
    int baselineRadius = Integer.getInteger("rtp.simulation.twoTier.baselineRadius", Math.min(radius, 256));

    Square spiral = new Square("ADR001_BASELINE");
    spiral.set(GenericMemoryShapeParams.radius, (long) baselineRadius);
    spiral.set(GenericMemoryShapeParams.centerRadius, 0L);
    spiral.set(GenericMemoryShapeParams.centerX, 0L);
    spiral.set(GenericMemoryShapeParams.centerZ, 0L);

    long marked = 0L;
    long t0 = System.nanoTime();
    for (int x = -baselineRadius; x <= baselineRadius; x++) {
      for (int z = -baselineRadius; z <= baselineRadius; z++) {
        int ring = Math.max(Math.abs(x), Math.abs(z));
        if (ring == 0 || ring >= baselineRadius) continue;
        if (mask.isOccupied(x, z)) continue;
        spiral.addBadLocation(spiral.xzToLocation(x, z));
        marked++;
      }
    }
    spiral.flushAndRebuildIfNeeded(spiral.spatialResolution());
    long markNanos = System.nanoTime() - t0;

    long t1 = System.nanoTime();
    spiral.flushAndRebuildIfNeeded(spiral.spatialResolution());
    long rebuildNanos = System.nanoTime() - t1;

    int runs = spiral.badKeysSnapshot().length;
    String section = "ADR-001 spiral baseline";
    String subject = "radius=" + baselineRadius;
    REPORT.add(section, subject, "cells marked bad", Long.toString(marked), Provenance.MEASURED);
    REPORT.add(section, subject, "coalesced bad runs", Integer.toString(runs), Provenance.MEASURED);
    REPORT.add(section, subject, "run-table bytes (keys + sums)", Long.toString(16L * runs), Provenance.DERIVED);
    REPORT.add(section, subject, "mark + first rebuild ms", markNanos / 1e6, Provenance.MEASURED);
    REPORT.add(section, subject, "steady-state rebuild ms", rebuildNanos / 1e6, Provenance.MEASURED);
    REPORT.note(
        "The spiral baseline is measured against the same mask at the same origin, but it is not "
            + "a like-for-like structure: it encodes bad space as 1D runs and the descriptor "
            + "encodes good space as per-macro counts. Read the two footprint rows as scaling "
            + "behaviour on real data, not as one number replacing another.");

    assertTrue(runs > 0, "the mask must produce bad runs on the spiral baseline");
  }
}
