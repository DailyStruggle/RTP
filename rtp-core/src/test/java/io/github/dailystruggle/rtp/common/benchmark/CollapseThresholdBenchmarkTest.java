package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Collapse lever: discard the run table of an outer cell once enough of it is known unsafe, and
 * carry the cell as one bit instead.
 *
 * <p>Why this and not the earlier levers. Resident cost is dominated by the directory, which holds
 * one slot per <i>addressed</i> outer cell whether that cell is useful or not - which is why
 * coarsening the inner cell bought 1.53x bytes for 2.18x over-exclusion and why the resident
 * fraction came out near 0.6 rather than the withdrawn 0.007. Collapsing removes slots rather than
 * shrinking them, so it moves the floor itself.
 *
 * <p>Three thresholds are swept:
 *
 * <ul>
 *   <li><b>1.00</b> - collapse only a cell that is entirely unsafe. Lossless by construction: there
 *       is no good cell left to discard, so this is a pure accounting win and its size is purely a
 *       fact about how clustered real terrain is.
 *   <li><b>0.75</b> and <b>0.50</b> - collapse a mostly-unsafe cell, discarding the good cells that
 *       remain in it. This is over-exclusion, it is the operator's "ocean width" question in a
 *       different form, and it is reported as a ratio against the chunks a full-precision index
 *       excludes rather than left as an assertion.
 * </ul>
 *
 * <p>Occupancy is the real save tiled outward (see {@link TiledOccupancyMask}), so clustering is
 * measured rather than synthesized - the whole size of this lever is a clustering question, and a
 * uniform random mask would answer it with a number that means nothing.
 *
 * <p><b>Units.</b> Every coordinate, radius and cell edge here is counted in <b>chunks</b>. One
 * chunk is the finest addressable cell, because a candidate is placed at the centre of a chunk.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("collapse threshold: unsafe sections carried as one bit")
public class CollapseThresholdBenchmarkTest {

  private static final long SEED = 20260906L;

  /** Thresholds swept: the bad fraction at which an outer cell stops carrying a run table. */
  private static final double[] THRESHOLDS = {1.00d, 0.75d, 0.50d};

  /** Outer cell edges swept, in chunks. 32 chunks is one Anvil region file. */
  private static final int[] OUTER_CHUNKS = {8, 16, 32};

  /** Domain half-edges swept, in chunks: 4 096 and 16 384 blocks. */
  private static final int[] RADII_CHUNKS = {256, 1_024};

  private static final int SELECT_ITERATIONS = 20_000;

  private static final SimulationReport REPORT = new SimulationReport();

  private static TiledOccupancyMask mask;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    if (!WorldOccupancyMask.available()) return;
    Path dir = WorldOccupancyMask.resolveDirectory();
    mask = new TiledOccupancyMask(WorldOccupancyMask.load(dir));
    REPORT.add(
        "world", "tiled save", "bad density", 1.0d - mask.occupiedFraction(), Provenance.MEASURED);
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "A collapsed outer cell holds no Fenwick slot and no blob, only a bit in a dense bitmap "
            + "over the outer grid. Directory bytes are therefore counted as one bit per outer "
            + "cell plus one full slot per surviving cell, which is the layout the lever is "
            + "proposing; the uncollapsed directory figure is reported beside it so the two are "
            + "comparable rather than substituted.");
    REPORT.note(
        "At threshold 1.00 the lever is lossless and the saving is entirely a fact about the real "
            + "save's clustering, not about the encoding. Below 1.00 the good cells remaining in a "
            + "collapsed cell are discarded, and that count is reported as over-excluded chunks "
            + "rather than folded into the byte saving.");
    REPORT.note(
        "Marks are staged and flushed once, so a cell collapses on its final bad count. Flushing "
            + "incrementally would collapse some cells earlier and then drop the marks that would "
            + "have followed - cheaper and lossier both, and a policy question rather than a "
            + "property of the structure, so it is held fixed here.");
    REPORT.note(
        "Collapse and coarsening pull against each other and the rows show it: a coarser outer "
            + "cell is less likely to be uniformly unsafe, so the coarser the grid the smaller the "
            + "fraction of it a lossless collapse can remove. Any planner using both levers has to "
            + "resolve that interaction rather than apply them independently.");
    REPORT.write("collapse-threshold");
  }

  // -------------------------------------------------------------------------------------
  // vehicle
  // -------------------------------------------------------------------------------------

  /**
   * One measured configuration.
   *
   * @param trueBadChunks chunks the mask reports unsafe, i.e. what full precision would exclude
   * @param excludedChunks chunks the index actually excludes after collapse
   */
  private record Row(
      int outerCells,
      int mixedCells,
      int collapsedCells,
      int runs,
      long directoryBytes,
      long collapsedDirectoryBytes,
      long blobBytes,
      long fullyResidentBytes,
      long collapsedFullyResidentBytes,
      long trueBadChunks,
      long excludedChunks,
      long goodChunks,
      double selectNanos) {

    double overExclusionRatio() {
      return trueBadChunks == 0L ? 1.0d : excludedChunks / (double) trueBadChunks;
    }

    double mixedFraction() {
      return outerCells == 0 ? 0.0d : mixedCells / (double) outerCells;
    }
  }

  private static Row measure(int radiusChunks, int outerChunks, double threshold) {
    LayeredHilbertIndex index =
        new LayeredHilbertIndex(radiusChunks, outerChunks, 1, SEED, threshold);

    long trueBad = 0L;
    for (int cx = -radiusChunks; cx < radiusChunks; cx++) {
      for (int cz = -radiusChunks; cz < radiusChunks; cz++) {
        if (mask.isOccupied(cx, cz)) continue;
        trueBad++;
        index.addBadLocation(cx, cz);
      }
    }
    index.flush();

    double select = 0.0d;
    if (index.totalGood() > 0L) {
      for (int i = 0; i < SELECT_ITERATIONS / 4; i++) {
        index.rand();
      }
      long t0 = System.nanoTime();
      long sink = 0L;
      for (int i = 0; i < SELECT_ITERATIONS; i++) {
        sink ^= index.rand();
      }
      select = (System.nanoTime() - t0) / (double) SELECT_ITERATIONS;
      if (sink == Long.MAX_VALUE) throw new IllegalStateException("unreachable");
    }

    long domain = (long) index.outerCellCount() * index.innerCellCount();
    return new Row(
        index.outerCellCount(),
        index.mixedOuterCells(),
        index.collapsedOuterCells(),
        index.totalRuns(),
        index.residentDirectoryBytes(),
        index.collapsedDirectoryBytes(),
        index.blobBytes(),
        index.fullyResidentBytes(),
        index.collapsedFullyResidentBytes(),
        trueBad,
        domain - index.totalGood(),
        index.totalGood(),
        select);
  }

  // -------------------------------------------------------------------------------------
  // measurements
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("threshold sweep: bytes saved against precision given up, on the real save")
  public void thresholdSweep() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int radius : RADII_CHUNKS) {
      for (int outer : OUTER_CHUNKS) {
        if (radius / outer < 2) continue;
        Row lossless = null;
        Row previous = null;
        for (double threshold : THRESHOLDS) {
          Row row = measure(radius, outer, threshold);
          if (lossless == null) lossless = row;
          String subject =
              "r=" + radius + "ch outer=" + outer + "ch t=" + String.format("%.2f", threshold);

          REPORT.add("collapse", subject, "outer cells", row.outerCells(), Provenance.DERIVED);
          REPORT.add("collapse", subject, "collapsed cells", row.collapsedCells(),
              Provenance.MEASURED);
          REPORT.add("collapse", subject, "mixed cells", row.mixedCells(), Provenance.MEASURED);
          REPORT.add("collapse", subject, "mixed fraction", row.mixedFraction(),
              Provenance.MEASURED);
          REPORT.add("collapse", subject, "runs", row.runs(), Provenance.MEASURED);
          REPORT.add("collapse", subject, "directory bytes, uncollapsed",
              row.directoryBytes(), Provenance.DERIVED);
          REPORT.add("collapse", subject, "directory bytes, collapsed",
              row.collapsedDirectoryBytes(), Provenance.DERIVED);
          REPORT.add("collapse", subject, "blob bytes", row.blobBytes(), Provenance.DERIVED);
          REPORT.add("collapse", subject, "fully resident bytes, collapsed",
              row.collapsedFullyResidentBytes(), Provenance.DERIVED);
          REPORT.add("collapse", subject, "chunks truly bad", row.trueBadChunks(),
              Provenance.MEASURED);
          REPORT.add("collapse", subject, "chunks excluded", row.excludedChunks(),
              Provenance.MEASURED);
          REPORT.add("collapse", subject, "over-exclusion vs full precision",
              row.overExclusionRatio(), Provenance.DERIVED);
          REPORT.add("collapse", subject, "good chunks remaining", row.goodChunks(),
              Provenance.MEASURED);
          REPORT.add("collapse", subject, "select ns/op", row.selectNanos(), Provenance.MEASURED);
          if (lossless.collapsedFullyResidentBytes() > 0L) {
            REPORT.add(
                "collapse",
                subject,
                "bytes vs lossless collapse",
                row.collapsedFullyResidentBytes()
                    / (double) lossless.collapsedFullyResidentBytes(),
                Provenance.DERIVED);
          }

          // Lowering the threshold can only collapse more cells, so it can only shrink the
          // directory and can only exclude more. A violation would mean collapse is not a
          // monotone lever and nothing built on top of it could be planned.
          if (previous != null) {
            assertTrue(
                row.collapsedCells() >= previous.collapsedCells(),
                "collapse count fell as the threshold dropped at " + subject);
            assertTrue(
                row.excludedChunks() >= previous.excludedChunks(),
                "exclusion fell as the threshold dropped at " + subject);
          }
          previous = row;
        }
      }
    }
  }

  @Test
  @DisplayName("collapsing only fully unsafe cells is lossless")
  public void losslessAtFullThreshold() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 256;
    int outer = 16;
    LayeredHilbertIndex collapsing =
        new LayeredHilbertIndex(radius, outer, 1, SEED, 1.00d);
    LayeredHilbertIndex plain = new LayeredHilbertIndex(radius, outer, 1, SEED, 1.00d);

    for (int cx = -radius; cx < radius; cx++) {
      for (int cz = -radius; cz < radius; cz++) {
        if (mask.isOccupied(cx, cz)) continue;
        collapsing.addBadLocation(cx, cz);
        plain.addBadLocation(cx, cz);
      }
    }
    collapsing.flush();
    plain.flush();

    assertEquals(
        0L,
        collapsing.overExcludedCells(),
        "a threshold of 1.00 discarded good cells, so the lever is not lossless");
    assertEquals(
        plain.totalGood(),
        collapsing.totalGood(),
        "collapsing entirely-bad cells changed the good population");
    assertTrue(
        collapsing.collapsedDirectoryBytes() <= collapsing.residentDirectoryBytes(),
        "collapsed directory is not smaller than the uncollapsed one");

    REPORT.add(
        "lossless",
        "r=256ch outer=16ch",
        "collapsed cells",
        collapsing.collapsedOuterCells(),
        Provenance.MEASURED);
    REPORT.add(
        "lossless",
        "r=256ch outer=16ch",
        "directory saving",
        1.0d
            - collapsing.collapsedDirectoryBytes()
                / (double) Math.max(1L, collapsing.residentDirectoryBytes()),
        Provenance.DERIVED);
  }

  @Test
  @DisplayName("a collapsed cell is never drawn")
  public void collapsedCellsAreNeverDrawn() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 128;
    int outer = 16;
    LayeredHilbertIndex index = new LayeredHilbertIndex(radius, outer, 1, SEED, 0.50d);
    for (int cx = -radius; cx < radius; cx++) {
      for (int cz = -radius; cz < radius; cz++) {
        if (!mask.isOccupied(cx, cz)) index.addBadLocation(cx, cz);
      }
    }
    index.flush();
    Assumptions.assumeTrue(index.totalGood() > 0L, "no good cell survived the collapse");

    int draws = 50_000;
    for (int i = 0; i < draws; i++) {
      long packed = index.rand();
      int x = (int) (packed & 0xFFFFFFFFL);
      int z = (int) (packed >> 32);
      assertTrue(
          x >= -radius && x < radius && z >= -radius && z < radius,
          "draw left the domain at (" + x + ", " + z + ")");
    }

    REPORT.add(
        "draws",
        "r=128ch outer=16ch t=0.50",
        "draws inside domain",
        draws,
        Provenance.MEASURED);
    REPORT.add(
        "draws",
        "r=128ch outer=16ch t=0.50",
        "good chunks remaining",
        index.totalGood(),
        Provenance.MEASURED);
  }
}
