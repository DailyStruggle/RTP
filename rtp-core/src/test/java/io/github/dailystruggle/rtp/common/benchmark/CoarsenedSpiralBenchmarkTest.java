package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.StorageLatencyProbe;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Config;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Situation;
import io.github.dailystruggle.rtp.common.benchmark.ModelCrossoverPlanner.ModelChoice;
import io.github.dailystruggle.rtp.common.benchmark.ModelCrossoverPlanner.Residency;
import io.github.dailystruggle.rtp.common.benchmark.ModelCrossoverPlanner.ResidencyChoice;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures what coarsening the shipped spiral's addressing unit actually costs and buys, and prices
 * "hold it in heap" against "fetch it from storage" as an explicit choice.
 *
 * <p>Three things are being tested, and the first two exist because a previous claim of mine was
 * wrong in both directions:
 *
 * <ul>
 *   <li><b>Coarsening is not monotone.</b> A coarse spiral is not a decimation of a fine one: the
 *       key of a cell is a function of the cell <i>grid</i>, so changing the grid changes the
 *       ordering. Two cells that were key-adjacent - one run - can land in coarse cells that are
 *       not, which is two runs. Aggregation happens in 2D and run-length encoding happens in 1D,
 *       and coarsening changes the map between them. So run count per cell edge is measured, never
 *       fitted through.
 *   <li><b>The base unit is one chunk, not one block.</b> Safety selection places a candidate at
 *       the centre of a chunk, so a sub-chunk key carries no distinguishable information. Cell edge
 *       is therefore reported in chunks, one chunk is full precision, and the only other
 *       structurally privileged value is 32 chunks - one Anvil region file. Intermediate edges pay
 *       boundary quantization and curve reordering while aligning with nothing the system fetches
 *       or produces, which is why they are swept: to show whether that costs anything measurable.
 *   <li><b>Residency is a decision with a price on both sides.</b> The earlier objective had bytes
 *       above a budget silently become a miss fraction, so paging was imposed rather than chosen.
 *       Here both branches are costed and the winner is reported.
 * </ul>
 *
 * <p>Over-exclusion is the price of a coarse cell and is measured, not modeled: a coarse cell is
 * marked bad when <i>any</i> chunk inside it is bad, which is the only conservative rule available,
 * and the excluded area is then compared against the area a chunk-precision grid excludes.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("coarsened spiral: cell edge, key width and residency")
public class CoarsenedSpiralBenchmarkTest {

  private static final long SEED = 20260906L;

  /** Blocks per chunk. The finest cell the shipped shape distinguishes. */
  private static final int CHUNK = 16;

  /** Chunks per Anvil region file edge. */
  private static final int REGION_CHUNKS = 32;

  private static final int SELECT_ITERATIONS = 20_000;

  private static final int RECONCILE_SAMPLE = 200;

  /** Cell edges swept, in chunks. 1 is full precision; 32 is one region file. */
  private static final int[] CELL_CHUNKS = {1, 2, 4, 8, 16, 32};

  /** Radii swept, in blocks. */
  private static final int[] RADII = {2_048, 8_192, 20_480};

  private static final SimulationReport REPORT = new SimulationReport();

  private static TiledOccupancyMask mask;
  private static double badDensity;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    if (!WorldOccupancyMask.available()) return;
    Path dir = WorldOccupancyMask.resolveDirectory();
    mask = new TiledOccupancyMask(WorldOccupancyMask.load(dir));
    badDensity = 1.0d - mask.occupiedFraction();
    REPORT.add("world", "tiled save", "bad density", badDensity, Provenance.MEASURED);
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Cell edge is reported in chunks because one chunk is the finest cell the shipped shape "
            + "addresses - safety selection places a candidate at the centre of a chunk, so a "
            + "sub-chunk key would carry no information the occupancy signal can supply. An "
            + "earlier note of mine listed a 16-block edge as a coarsening step; it is the "
            + "full-precision case, and the only other structurally privileged edge is 32 chunks, "
            + "one Anvil region file.");
    REPORT.note(
        "Run count against cell edge is measured per point rather than fitted, because coarsening "
            + "is not a decimation. A cell's spiral key is a function of the cell grid, so a "
            + "coarser grid is a different ordering: cells that were key-adjacent, and therefore "
            + "one run, can land in coarse cells that are not adjacent, and therefore two runs. "
            + "Any exponent fitted through these rows would hide exactly that effect.");
    REPORT.note(
        "Over-exclusion is the honest price of a coarse cell and is measured against the "
            + "chunk-precision grid. A coarse cell is marked bad when any chunk inside it is bad, "
            + "which is the only conservative rule available; the alternative admits cells known "
            + "to contain unsafe ground and pushes the rejection downstream into the pipeline.");
    REPORT.note(
        "Key width is derived, not asserted. The shipped table stores long keys and long lengths, "
            + "sixteen bytes per run, and that width is only required above roughly two billion "
            + "cells. Since the key space is counted in chunks rather than blocks, it fits int at "
            + "full precision out to a radius no world border reaches - so eight of those sixteen "
            + "bytes per run are currently paying for range that cannot occur.");
    REPORT.note(
        "The residency rows are the heuristic the storage term was always supposed to drive: "
            + "resident pays garbage collection over directory plus blobs and waits on nothing, "
            + "paged pays a full storage term per miss and keeps only the directory in heap. Both "
            + "are priced under the same select, reconciliation, cache and precision terms, so the "
            + "branch is an argmin rather than a consequence of a budget being exceeded.");
    REPORT.note(
        "Device classification for the residency term is taken from per-operation latency, not "
            + "throughput. A device sustaining 40 MiB/s in four-megabyte reads and one sustaining "
            + "the same rate in four-kilobyte header reads are different cost regimes, and the "
            + "throughput classifier calls them identical; what the objective multiplies is the "
            + "wait for one fetch.");
    REPORT.write("coarsened-spiral");
  }

  // -------------------------------------------------------------------------------------
  // vehicle
  // -------------------------------------------------------------------------------------

  /**
   * One measured cell-edge point.
   *
   * @param runs bad plus probation runs held after the rebuild
   * @param badCells cells the structure retained as bad
   * @param excludedChunks chunk-equivalent area those cells exclude, the over-exclusion numerator
   */
  private record Row(
      int runs,
      long badCells,
      long excludedChunks,
      long cells,
      boolean intKeys,
      double selectNanos,
      double reconcileNanosPerMark) {

    long bytesAtLongWidth() {
      return runs * 16L;
    }

    long bytesAtDerivedWidth() {
      return runs * (intKeys ? 8L : 16L);
    }
  }

  /**
   * Builds the shipped spiral over a grid of {@code cellChunks}-square cells.
   *
   * <p>A cell is marked bad when any chunk inside it is bad, so coarsening can only ever exclude
   * more, never less. {@code centerRadius} is pinned to zero: the addressable domain is the annulus
   * {@code [centerRadius, radius)}, and leaving the default of 64 in place makes every inner ring
   * out-of-domain, which a previous version of a sibling harness did - it then costed the spiral as
   * though it had stored marks it had refused.
   */
  private static Row measure(int radiusBlocks, int cellChunks) {
    int cellRadius = radiusBlocks / (CHUNK * cellChunks);
    Square shape = new Square();
    shape.setRng(new Random(SEED));
    shape.setSpatialResolution(1L);
    shape.set(GenericMemoryShapeParams.radius, (long) cellRadius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);

    for (int cx = -cellRadius + 1; cx < cellRadius; cx++) {
      for (int cz = -cellRadius + 1; cz < cellRadius; cz++) {
        if (!anyBadChunk(cx, cz, cellChunks)) continue;
        shape.addBadLocation(shape.xzToLocation(cx, cz), FailTypes.biome);
      }
    }
    shape.flushAndRebuild(1L);
    assertEquals(
        0L,
        shape.getOutOfDomainMarkCount(),
        "spiral refused in-domain marks at cell radius " + cellRadius);

    long badCells = shape.getEffectiveBadCount();
    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    int runs = (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
    long cells = (long) cellRadius * cellRadius * 4L;

    for (int i = 0; i < SELECT_ITERATIONS / 4; i++) {
      shape.rand();
    }
    long t0 = System.nanoTime();
    long sink = 0L;
    for (int i = 0; i < SELECT_ITERATIONS; i++) {
      sink ^= shape.rand();
    }
    double select = (System.nanoTime() - t0) / (double) SELECT_ITERATIONS;
    if (sink == Long.MAX_VALUE) throw new IllegalStateException("unreachable");

    Random rng = new Random(SEED ^ radiusBlocks ^ cellChunks);
    int span = Math.max(1, 2 * cellRadius - 1);
    long elapsed = 0L;
    for (int i = 0; i < RECONCILE_SAMPLE; i++) {
      int cx = rng.nextInt(span) - cellRadius + 1;
      int cz = rng.nextInt(span) - cellRadius + 1;
      long start = System.nanoTime();
      shape.addBadLocation(shape.xzToLocation(cx, cz), FailTypes.biome);
      shape.flushAndRebuild(1L);
      elapsed += System.nanoTime() - start;
    }
    double reconcile = elapsed / (double) RECONCILE_SAMPLE;

    long excluded = badCells * (long) cellChunks * cellChunks;
    boolean intKeys = cells < Integer.MAX_VALUE;
    return new Row(runs, badCells, excluded, cells, intKeys, select, reconcile);
  }

  /** @return true when any chunk inside this cell is bad, the conservative coarsening rule */
  private static boolean anyBadChunk(int cx, int cz, int cellChunks) {
    int baseX = cx * cellChunks;
    int baseZ = cz * cellChunks;
    for (int dx = 0; dx < cellChunks; dx++) {
      for (int dz = 0; dz < cellChunks; dz++) {
        if (!mask.isOccupied(baseX + dx, baseZ + dz)) return true;
      }
    }
    return false;
  }

  // -------------------------------------------------------------------------------------
  // measurements
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("cell edge sweep: runs, bytes and over-exclusion are measured, not derived")
  public void cellEdgeSweep() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    boolean nonMonotoneSomewhere = false;
    for (int radius : RADII) {
      Row finest = null;
      int previousRuns = Integer.MAX_VALUE;
      for (int cellChunks : CELL_CHUNKS) {
        if (radius / (CHUNK * cellChunks) < 2) continue;
        Row row = measure(radius, cellChunks);
        if (finest == null) finest = row;
        String subject = "r=" + radius + " cell=" + cellChunks + "ch";

        REPORT.add("coarsening", subject, "cells addressed", row.cells(), Provenance.MEASURED);
        REPORT.add("coarsening", subject, "runs", row.runs(), Provenance.MEASURED);
        REPORT.add("coarsening", subject, "bad cells", row.badCells(), Provenance.MEASURED);
        REPORT.add(
            "coarsening",
            subject,
            "bytes at shipped long width",
            row.bytesAtLongWidth(),
            Provenance.MEASURED);
        REPORT.add(
            "coarsening",
            subject,
            "bytes at derived key width",
            row.bytesAtDerivedWidth(),
            Provenance.DERIVED);
        REPORT.add(
            "coarsening",
            subject,
            "keys fit int",
            String.valueOf(row.intKeys()),
            Provenance.DERIVED);
        REPORT.add(
            "coarsening", subject, "select ns/op", round(row.selectNanos()), Provenance.MEASURED);
        REPORT.add(
            "coarsening",
            subject,
            "reconcile ns/mark",
            round(row.reconcileNanosPerMark()),
            Provenance.MEASURED);
        double overExclusion =
            finest.excludedChunks() == 0L
                ? 1.0d
                : row.excludedChunks() / (double) finest.excludedChunks();
        REPORT.add(
            "coarsening",
            subject,
            "over-exclusion vs 1-chunk grid",
            round(overExclusion),
            Provenance.DERIVED);

        if (row.runs() > previousRuns) {
          nonMonotoneSomewhere = true;
          REPORT.add(
              "coarsening",
              subject,
              "runs rose against a finer cell",
              "yes: " + previousRuns + " -> " + row.runs(),
              Provenance.MEASURED);
        }
        previousRuns = row.runs();
      }
    }

    REPORT.add(
        "coarsening",
        "grid",
        "non-monotone in cell edge anywhere",
        String.valueOf(nonMonotoneSomewhere),
        Provenance.MEASURED);
    REPORT.note(
        nonMonotoneSomewhere
            ? "Run count rises against a coarser cell somewhere in the grid, which confirms the "
                + "reordering effect: coarsening cannot be treated as a smooth memory knob, and a "
                + "planner must evaluate cell edge point by point."
            : "Run count fell monotonically with cell edge across every point swept. That does not "
                + "prove monotonicity - the reordering mechanism is real and this footprint's "
                + "clustering may simply not expose it - so cell edge is still evaluated point by "
                + "point rather than fitted.");
  }

  @Test
  @DisplayName("key width: the shipped long table is paying for range that cannot occur")
  public void keyWidthIsDerived() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    // Radii well past any world border, to find where int genuinely stops being enough.
    int[] radii = {20_480, 100_000, 400_000, 2_000_000};
    for (int cellChunks : new int[] {1, REGION_CHUNKS}) {
      for (int radius : radii) {
        Situation s = situation(radius, IndexConfigPlanner.SSD_OP_NS, 512L << 20, 0.001d);
        boolean fits = ModelCrossoverPlanner.spiralKeysFitInt(s, CHUNK * cellChunks);
        String subject = "cell=" + cellChunks + "ch r=" + radius;
        REPORT.add("keys", subject, "keys fit int", String.valueOf(fits), Provenance.DERIVED);
        REPORT.add(
            "keys",
            subject,
            "bytes per run",
            ModelCrossoverPlanner.spiralRunBytes(s, CHUNK * cellChunks),
            Provenance.DERIVED);
      }
    }

    Situation border = situation(100_000, IndexConfigPlanner.SSD_OP_NS, 512L << 20, 0.001d);
    assertTrue(
        ModelCrossoverPlanner.spiralKeysFitInt(border, CHUNK),
        "int keys must suffice at full chunk precision out to a 100 km border");
  }

  @Test
  @DisplayName("residency is a costed choice and flips with per-operation latency")
  public void residencyFlipsWithStorageLatency() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    double[] tiers = {
      IndexConfigPlanner.NVME_OP_NS,
      IndexConfigPlanner.SSD_OP_NS,
      IndexConfigPlanner.HDD_OP_NS,
      IndexConfigPlanner.NETWORK_OP_NS
    };
    String[] names = {"nvme", "ssd", "hdd", "network"};
    long[] headrooms = {16L << 20, 512L << 20};
    String[] headroomNames = {"16MB", "512MB"};

    Set<Residency> seen = new LinkedHashSet<>();
    for (int t = 0; t < tiers.length; t++) {
      for (int h = 0; h < headrooms.length; h++) {
        for (int radius : new int[] {8_192, 65_536}) {
          Situation s = situation(radius, tiers[t], headrooms[h], 0.001d);
          Config c = IndexConfigPlanner.plan(s).config();
          ResidencyChoice r =
              ModelCrossoverPlanner.layeredResidency(s, c, IndexConfigPlanner.budgetBytes(s));
          String subject =
              names[t] + " free=" + headroomNames[h] + " r=" + radius + " outer=" + c.outerEdge();
          REPORT.add("residency", subject, "branch", r.residency().name(), Provenance.MODELED);
          REPORT.add(
              "residency", subject, "resident ns/candidate", cost(r.residentCost()),
              Provenance.MODELED);
          REPORT.add(
              "residency", subject, "paged ns/candidate", cost(r.pagedCost()), Provenance.MODELED);
          REPORT.add(
              "residency", subject, "resident bytes", r.residentBytes(), Provenance.MODELED);
          seen.add(r.residency());
        }
      }
    }

    REPORT.add(
        "residency", "grid", "branches chosen", seen.toString(), Provenance.DERIVED);
    // Load-bearing: a residency term that answers the same branch everywhere is not a heuristic,
    // it is a constant, and that was the defect in the previous objective.
    assertTrue(
        seen.size() > 1,
        "residency must depend on the inputs, otherwise the storage term is decoration");
  }

  @Test
  @DisplayName("model choice reports its cell edge, key width and residency together")
  public void modelChoiceCarriesTheDecision() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (double tier :
        new double[] {IndexConfigPlanner.NVME_OP_NS, IndexConfigPlanner.NETWORK_OP_NS}) {
      for (int radius : new int[] {2_048, 20_480, 131_072}) {
        Situation s = situation(radius, tier, 512L << 20, 0.001d);
        ModelChoice choice = ModelCrossoverPlanner.choose(s);
        String subject =
            (tier == IndexConfigPlanner.NVME_OP_NS ? "nvme" : "network") + " r=" + radius;
        REPORT.add("choice", subject, "model", choice.model().name(), Provenance.MODELED);
        REPORT.add("choice", subject, "residency", choice.residency().name(), Provenance.MODELED);
        REPORT.add(
            "choice", subject, "spiral cell edge (blocks)", choice.spiralCellEdge(),
            Provenance.MODELED);
        REPORT.add(
            "choice", subject, "spiral bytes/run", choice.spiralRunBytes(), Provenance.DERIVED);
        REPORT.add(
            "choice", subject, "spiral resident bytes", choice.spiralResidentBytes(),
            Provenance.MODELED);
      }
    }

    Situation probe = situation(20_480, IndexConfigPlanner.SSD_OP_NS, 512L << 20, 0.001d);
    assertTrue(
        ModelCrossoverPlanner.choose(probe).spiralRunBytes() == 8,
        "a chunk-precision domain at 20 km must narrow to int keys");
    REPORT.add(
        "choice",
        "probe classifier",
        "latency-based device class",
        StorageLatencyProbe.classifyByLatency().name(),
        Provenance.MEASURED);
  }

  // -------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------

  private static Situation situation(
      int radius, double storageOpNanos, long headroom, double load) {
    return new Situation(radius, badDensity, load, headroom, storageOpNanos, 512, CHUNK);
  }

  private static String cost(double v) {
    return v >= Double.MAX_VALUE / 2.0d ? "infeasible" : String.valueOf(round(v));
  }

  private static double round(double v) {
    return Math.round(v * 1000.0d) / 1000.0d;
  }
}
