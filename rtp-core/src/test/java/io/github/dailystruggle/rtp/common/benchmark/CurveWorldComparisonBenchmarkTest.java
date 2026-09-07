package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shipped spiral against the spiral-addressed Hilbert key space, on <b>two</b> worlds: real
 * pass/fail from a real save, and the seeded noise mock at the density an operator reports in
 * practice.
 *
 * <p>Every earlier curve figure was measured on one world, so it could not distinguish a property
 * of the curve from a property of that world's terrain. The two worlds here are known to differ in
 * the direction that matters: the mock's interior is dotted with one-chunk pools and its oceans are
 * smaller, whereas the real save's inland ground is contiguous and its unusable set is one large
 * water body. A dotted interior is the adverse case for coalescing on either curve, because there
 * is no long stretch of unusable ground to merge into, so the mock is expected to read pessimistic
 * and the gap between the two worlds is the honest error bar on section 9's rows.
 *
 * <p>The swept coalescing settings include <b>3</b>, which is the shipped default in
 * {@code config.yml}. That matters for reading any field report of a usable share: an operator
 * measuring "45% usable" is measuring the world <i>after</i> the shipped default has already merged
 * runs, so part of what looks like terrain is coalescing loss. This suite separates the two by
 * reporting raw terrain share and the share the table still offers at each setting.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("curves on two worlds: real save pass/fail against the seeded noise mock")
public class CurveWorldComparisonBenchmarkTest {

  private static final long SEED = 20260906L;

  /** Usable share the mock is calibrated to - the figure reported during regeneration. */
  private static final double MOCK_SHARE = 0.45d;

  /** Radius, in chunks, of the measured domain. */
  private static final int RADIUS_CHUNKS = 128;

  /** Point edge, in chunks, of the hybrid's coarse addressing. 32 is one Anvil region file. */
  private static final int POINT_CHUNKS = 32;

  /**
   * Coalescing gaps swept, in 1D key units.
   *
   * <p>3 is the shipped default. 1 is the finest the coalescer admits, and 2 is the floor adopted
   * in ADR-085 section 9c on the grounds that a one-chunk island is not a viable destination.
   */
  private static final long[] RESOLUTIONS = {1L, 2L, 3L, 16L, 64L, 256L, 1_024L};

  /**
   * Accuracy budgets at which the two curves are compared on equal terms.
   *
   * <p>A fixed knob is <b>not</b> a fair comparison and the rows show why: the gap is in 1D key
   * units, and the two curves place different cells at that distance, so the same setting buys a
   * different amount of accuracy on each. Holding accuracy fixed and asking which table is smaller
   * is the operator's question and the only one that can be read as a byte saving.
   */
  private static final double[] LOSS_CAPS = {0.05d, 0.10d, 0.20d, 0.40d};

  private static final int SELECT_ITERATIONS = 20_000;

  private static final int MAX_REGION_FILES = 256;

  private static final String SAVE_ROOT_PROPERTY = "rtp.simulation.saveRoot";

  private static final String DEFAULT_SAVE_ROOT = "C:\\GameServers";

  private static final int SAVE_SEARCH_DEPTH = 8;

  private static final int IMAGE_SCALE = 3;

  private static final int BAD_ARGB = 0x5C6BC0;
  private static final int GOOD_ARGB = 0x2E7D32;
  private static final int LOST_ARGB = 0xD32F2F;

  private static final SimulationReport REPORT = new SimulationReport();

  /** Pass/fail over a chunk window, so one measurement path serves both worlds. */
  private interface Occupancy {
    boolean usable(int cx, int cz);
  }

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Two worlds, one measurement path. Both curves address one chunk per key, are offered the "
            + "identical mark set from the same world, and inherit the identical learned-state "
            + "machinery, so a difference between curves is the bijection and a difference between "
            + "worlds is the terrain.");
    REPORT.note(
        "The mock's interior is dotted with one-chunk pools and its oceans are smaller than the "
            + "real save's, so it has more unusable-to-usable boundary per unit of unusable ground. "
            + "That is the adverse case for coalescing on either curve - there is no long stretch "
            + "of unusable ground to merge into - so mock rows should be read as a pessimistic "
            + "bound and real-save rows as the favourable end of the same range.");
    REPORT.note(
        "spatialResolution 3 is the shipped default in config.yml. It is not free on either curve, "
            + "so a usable share measured in the field is terrain minus coalescing loss, and the "
            + "two are reported separately rather than conflated.");
    REPORT.note(
        "Settings at or above 64 key units are inadmissible at this radius on both curves and on "
            + "both worlds - they discard over 90 percent of usable ground - so they are present to "
            + "bound the sweep, not as candidate operating points. Granularity is a ratio to the "
            + "range, and radius 128 chunks is the small end.");
    REPORT.write("curve-world-comparison");
  }

  @Test
  @DisplayName("both curves, both worlds, identical radius and identical coalescing gap")
  public void bothCurvesOnBothWorlds() {
    Occupancy mock = mockWorld();
    sweep("mock 45%", mock);

    Occupancy real = realWorld();
    Assumptions.assumeTrue(real != null, "no real save available");
    sweep("real save", real);
  }

  private static Occupancy mockWorld() {
    NoiseWorldMask mask = new NoiseWorldMask(SEED, RADIUS_CHUNKS, MOCK_SHARE);
    return mask::isOccupied;
  }

  private static Occupancy realWorld() {
    Path root = Path.of(System.getProperty(SAVE_ROOT_PROPERTY, DEFAULT_SAVE_ROOT));
    List<Path> dirs = RealWorldVerdictMask.discoverRegionDirectories(root, SAVE_SEARCH_DEPTH);
    if (dirs.isEmpty()) return null;
    RealWorldVerdictMask mask =
        RealWorldVerdictMask.load(
            dirs.get(0), RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, MAX_REGION_FILES);
    // Only ground the save actually contains: an origin-centred window outside the footprint would
    // count never-generated chunks as unusable terrain, which is the fault that voided ADR-084's
    // density rows.
    if (mask.inscribedRadius() < RADIUS_CHUNKS) return null;
    return mask::isOccupied;
  }

  // -------------------------------------------------------------------------------------
  // measurement
  // -------------------------------------------------------------------------------------

  private static void sweep(String world, Occupancy occupancy) {
    double terrainShare = usableShare(occupancy);
    REPORT.add("world", world, "raw usable terrain share", terrainShare, Provenance.MEASURED);

    CurveImage img = new CurveImage("curve-world-" + world.replace(' ', '-').replace("%", ""), IMAGE_SCALE);
    drawTruth(img, world, occupancy, terrainShare);

    int[] spiralRunsAt = new int[RESOLUTIONS.length];
    double[] spiralLossAt = new double[RESOLUTIONS.length];
    int[] hybridRunsAt = new int[RESOLUTIONS.length];
    double[] hybridLossAt = new double[RESOLUTIONS.length];

    for (int i = 0; i < RESOLUTIONS.length; i++) {
      long resolution = RESOLUTIONS[i];
      Square spiral = plainSpiral();
      markAndFlush(spiral, occupancy, resolution);
      int spiralRuns = runCount(spiral);
      double spiralLoss = usableGroundLost(spiral, occupancy);

      SpiralHilbertSquare hybrid = new SpiralHilbertSquare(RADIUS_CHUNKS, POINT_CHUNKS, true);
      markAndFlush(hybrid, occupancy, resolution);
      int hybridRuns = runCount(hybrid);
      double hybridLoss = usableGroundLost(hybrid, occupancy);

      emit(world, "spiral", resolution, spiralRuns, spiralLoss, selectNanos(spiral));
      emit(world, "hybrid", resolution, hybridRuns, hybridLoss, selectNanos(hybrid));
      REPORT.add(
          "runs " + world,
          "res=" + resolution,
          "hybrid table smaller by",
          hybridRuns == 0 ? 0.0d : spiralRuns / (double) hybridRuns,
          Provenance.DERIVED);

      drawLoss(img, "spiral", resolution, spiral, occupancy, spiralRuns, spiralLoss, world);
      drawLoss(img, "hybrid", resolution, hybrid, occupancy, hybridRuns, hybridLoss, world);

      spiralRunsAt[i] = spiralRuns;
      spiralLossAt[i] = spiralLoss;
      hybridRunsAt[i] = hybridRuns;
      hybridLossAt[i] = hybridLoss;

      assertTrue(spiralLoss >= 0.0d && hybridLoss >= 0.0d, "loss must be a fraction");

      // The shipped default is not a neutral operating point: it already merges runs, so a usable
      // share measured in the field at this setting is terrain minus coalescing loss and cannot be
      // read as terrain. Both halves are reported so the two are separable.
      if (resolution == 3L) {
        REPORT.add(
            "shipped default " + world,
            "spatialResolution=3",
            "raw usable terrain share",
            terrainShare,
            Provenance.MEASURED);
        REPORT.add(
            "shipped default " + world,
            "spatialResolution=3",
            "share the spiral table still offers",
            terrainShare * (1.0d - spiralLoss),
            Provenance.DERIVED);
        REPORT.add(
            "shipped default " + world,
            "spatialResolution=3",
            "share the hybrid table still offers",
            terrainShare * (1.0d - hybridLoss),
            Provenance.DERIVED);
      }
    }

    for (double cap : LOSS_CAPS) {
      int spiralBest = smallestTableWithin(spiralRunsAt, spiralLossAt, cap);
      int hybridBest = smallestTableWithin(hybridRunsAt, hybridLossAt, cap);
      String subject = String.format("loss cap %.2f", cap);
      REPORT.add("iso-accuracy " + world, subject, "spiral runs", spiralBest, Provenance.MEASURED);
      REPORT.add("iso-accuracy " + world, subject, "hybrid runs", hybridBest, Provenance.MEASURED);
      REPORT.add(
          "iso-accuracy " + world,
          subject,
          "hybrid table smaller by",
          hybridBest <= 0 ? 0.0d : spiralBest / (double) hybridBest,
          Provenance.DERIVED);
      assertTrue(spiralBest > 0 && hybridBest > 0, "no admissible setting at cap " + cap);
    }
  }

  /**
   * Smallest run count reachable without exceeding an accuracy cap.
   *
   * <p>Minimum over the whole sweep rather than the coarsest admissible setting: coalescing is not
   * guaranteed monotone in the gap, because a wider gap changes which runs merge and merging is not
   * a decimation of the finer result.
   */
  private static int smallestTableWithin(int[] runs, double[] loss, double cap) {
    int best = -1;
    for (int i = 0; i < runs.length; i++) {
      if (loss[i] > cap) continue;
      if (best < 0 || runs[i] < best) best = runs[i];
    }
    return best;
  }

  private static void emit(
      String world, String curve, long resolution, int runs, double loss, double select) {
    String subject = curve + " res=" + resolution;
    REPORT.add("runs " + world, subject, "runs", runs, Provenance.MEASURED);
    REPORT.add("runs " + world, subject, "bytes at long width", runs * 16L, Provenance.DERIVED);
    REPORT.add("runs " + world, subject, "usable ground discarded", loss, Provenance.MEASURED);
    REPORT.add(
        "runs " + world, subject, "usable ground still offered", 1.0d - loss, Provenance.DERIVED);
    REPORT.add("runs " + world, subject, "select ns/op", select, Provenance.MEASURED);
  }

  private static Square plainSpiral() {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, (long) RADIUS_CHUNKS);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    return shape;
  }

  private static void markAndFlush(MemoryShape<?> shape, Occupancy occupancy, long resolution) {
    shape.setRng(new Random(SEED));
    shape.setSpatialResolution(resolution);
    for (int cx = -RADIUS_CHUNKS; cx < RADIUS_CHUNKS; cx++) {
      for (int cz = -RADIUS_CHUNKS; cz < RADIUS_CHUNKS; cz++) {
        if (occupancy.usable(cx, cz)) continue;
        long key = shape.xzToLocation(cx, cz);
        if (key < 0L) continue;
        shape.addBadLocation(key, FailTypes.biome);
      }
    }
    shape.flushAndRebuild(resolution);
    assertEquals(0L, shape.getOutOfDomainMarkCount(), "shape refused in-domain marks");
  }

  private static int runCount(MemoryShape<?> shape) {
    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    return (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
  }

  private static double usableGroundLost(MemoryShape<?> shape, Occupancy occupancy) {
    long good = 0L;
    long lost = 0L;
    for (int cx = -RADIUS_CHUNKS; cx < RADIUS_CHUNKS; cx++) {
      for (int cz = -RADIUS_CHUNKS; cz < RADIUS_CHUNKS; cz++) {
        if (!occupancy.usable(cx, cz)) continue;
        good++;
        if (shape.isKnownBad(cx, cz)) lost++;
      }
    }
    return good == 0L ? 0.0d : lost / (double) good;
  }

  private static double selectNanos(MemoryShape<?> shape) {
    for (int i = 0; i < SELECT_ITERATIONS / 4; i++) shape.rand();
    long t0 = System.nanoTime();
    long sink = 0L;
    for (int i = 0; i < SELECT_ITERATIONS; i++) sink ^= shape.rand();
    double ns = (System.nanoTime() - t0) / (double) SELECT_ITERATIONS;
    if (sink == Long.MAX_VALUE) throw new IllegalStateException("unreachable");
    return ns;
  }

  private static double usableShare(Occupancy occupancy) {
    long total = 0L;
    long usable = 0L;
    for (int cx = -RADIUS_CHUNKS; cx < RADIUS_CHUNKS; cx++) {
      for (int cz = -RADIUS_CHUNKS; cz < RADIUS_CHUNKS; cz++) {
        total++;
        if (occupancy.usable(cx, cz)) usable++;
      }
    }
    return total == 0L ? 0.0d : usable / (double) total;
  }

  // -------------------------------------------------------------------------------------
  // drawn output
  // -------------------------------------------------------------------------------------

  private static void drawTruth(
      CurveImage img, String world, Occupancy occupancy, double terrainShare) {
    int cells = 2 * RADIUS_CHUNKS;
    img.draw(
        "truth",
        cells,
        (cx, cz) ->
            occupancy.usable(cx - RADIUS_CHUNKS, cz - RADIUS_CHUNKS) ? GOOD_ARGB : BAD_ARGB,
        new CurveImage.Caption("Ground truth - " + world)
            .line("Input to both curves. Indigo is unusable, green is a viable start.")
            .line("Raw usable terrain share: " + String.format("%.3f", terrainShare))
            .line(cells + "x" + cells + " chunks, 1 pixel block = 1 chunk")
            .swatch(GOOD_ARGB, "usable chunk")
            .swatch(BAD_ARGB, "unusable chunk - marked bad"));
  }

  private static void drawLoss(
      CurveImage img,
      String curve,
      long resolution,
      MemoryShape<?> shape,
      Occupancy occupancy,
      int runs,
      double loss,
      String world) {
    int cells = 2 * RADIUS_CHUNKS;
    img.draw(
        "discarded-" + curve + "-res" + resolution,
        cells,
        (cx, cz) -> lossColour(shape, occupancy, cx - RADIUS_CHUNKS, cz - RADIUS_CHUNKS),
        new CurveImage.Caption("Usable ground lost to coalescing - " + curve + " on " + world)
            .line("spatialResolution = " + resolution + " key units" + (resolution == 3L ? " (shipped default)" : ""))
            .line("runs: " + runs + "   usable ground discarded: " + String.format("%.3f", loss))
            .line("Red is safe ground the table no longer offers.")
            .line(cells + "x" + cells + " chunks, 1 pixel block = 1 chunk")
            .swatch(GOOD_ARGB, "usable and still offered")
            .swatch(LOST_ARGB, "usable but refused - lost to coalescing")
            .swatch(BAD_ARGB, "unusable chunk - correctly excluded"));
  }

  private static int lossColour(MemoryShape<?> shape, Occupancy occupancy, int cx, int cz) {
    if (!occupancy.usable(cx, cz)) return BAD_ARGB;
    return shape.isKnownBad(cx, cz) ? LOST_ARGB : GOOD_ARGB;
  }
}
