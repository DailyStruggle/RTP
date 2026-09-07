package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.ProximityWeightedLoss.Weighting;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Accuracy priced by <b>what the discarded ground was next to</b>, and a coalescing gap derived
 * from adjacent run lengths instead of configured flat.
 *
 * <p>Two questions, both raised by the observation that a flat loss share treats every usable chunk
 * alike:
 *
 * <ol>
 *   <li><b>Does proximity weighting widen the gap between the curves?</b> The drawn output already
 *       showed the two curves losing ground in different <i>places</i> - the hybrid's loss hugs the
 *       coast, the spiral's cuts arcs across open inland ground - and a flat share cannot see that
 *       difference. Ocean is a red light, land a green light, a beach a yellow light, and a beach is
 *       only a beach when the water beside it is a large body rather than a pool.
 *   <li><b>Does a dynamic gap beat a fixed one?</b> A fixed {@code spatialResolution} bridges a gap
 *       of N whether the runs either side are an ocean or two single-chunk pools.
 *       {@link KeyRunTable#coalesceDynamic} instead admits {@code alpha * min(adjacent lengths)},
 *       capped, so long bodies consolidate and pools do not eat their surroundings.
 * </ol>
 *
 * <p>Both worlds are measured - real Anvil pass/fail and the seeded noise mock - because a
 * proximity result depends on the shape of the unusable set, and those two worlds are known to
 * differ exactly there.
 *
 * <p>The weighting's discount and thresholds are policy, so they are swept rather than fixed, and
 * the loss <b>composition</b> (shore versus interior) is reported alongside, since that part holds
 * whatever discount is chosen.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("proximity-weighted accuracy and run-length-driven dynamic coalescing")
public class ProximityWeightedLossBenchmarkTest {

  private static final long SEED = 20260906L;

  private static final double MOCK_SHARE = 0.45d;

  private static final int RADIUS_CHUNKS = 128;

  /**
   * Half-edge of the measured window: the largest origin-centred square <b>both</b> curves address
   * in full, discovered by probing rather than assumed.
   *
   * <p>The two curves do not cover the same ground at an arbitrary radius. The hybrid's coarse grid
   * rounds up to whole points, so at radius 128 with 32-chunk points its outer point ring falls
   * outside the coarse spiral's domain; the plain spiral separately does not address its own
   * outermost ring although {@code xzToLocation} still maps it. Comparing tables built over
   * different domains is the fault that voided rows in ADR-084, so the window is measured and every
   * reported chunk is checked to be addressable by both.
   */
  private static int windowChunks;

  /** Point edge, in chunks, of the hybrid's coarse addressing. 32 is one Anvil region file. */
  private static final int POINT_CHUNKS = 32;

  /** Fixed gaps swept. 2 is ADR-085 section 9c's floor, 3 the shipped default. */
  private static final long[] FIXED_GAPS = {2L, 3L, 8L, 16L, 64L, 256L};

  /** Gap admitted per cell of the shorter adjacent run, in the dynamic rule. */
  private static final double[] ALPHAS = {0.125d, 0.25d, 0.5d, 1.0d, 2.0d};

  /** Ocean-width ceiling on the dynamic gap, in key units. */
  private static final long DYNAMIC_MAX_GAP = 1_024L;

  /** Weightings swept, so no conclusion rests on one discount. */
  private static final Weighting[] WEIGHTINGS = {
    new Weighting(1, 256L, 0.5d),
    new Weighting(2, 256L, 0.25d),
    new Weighting(4, 1_024L, 0.25d),
    new Weighting(4, 1_024L, 0.0d)
  };

  /** The weighting the headline comparison uses. */
  private static final Weighting PRIMARY = WEIGHTINGS[1];

  private static final int MAX_REGION_FILES = 256;

  private static final String SAVE_ROOT_PROPERTY = "rtp.simulation.saveRoot";

  private static final String DEFAULT_SAVE_ROOT = "C:\\GameServers";

  private static final int SAVE_SEARCH_DEPTH = 8;

  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    windowChunks = largestCommonWindow();
  }

  /**
   * Largest half-edge for which both curves address every chunk.
   *
   * <p>Descending search rather than arithmetic on the point edge: the exclusion has two
   * independent causes - the coarse grid rounding up, and the spiral's unaddressed outer ring - and
   * a formula for their interaction is one more thing that can be wrong silently.
   */
  private static int largestCommonWindow() {
    Square spiral = plainSpiral();
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(RADIUS_CHUNKS, POINT_CHUNKS, true);
    for (int w = RADIUS_CHUNKS; w >= 8; w--) {
      if (fullyAddressed(spiral, hybrid, w)) return w;
    }
    throw new IllegalStateException("no window of 8 chunks or more is addressed by both curves");
  }

  private static boolean fullyAddressed(MemoryShape<?> spiral, MemoryShape<?> hybrid, int w) {
    for (int cx = -w; cx < w; cx++) {
      for (int cz = -w; cz < w; cz++) {
        long s = spiral.xzToLocation(cx, cz);
        long h = hybrid.xzToLocation(cx, cz);
        if (s < 0L || s >= spiral.getRange() || h < 0L || h >= hybrid.getRange()) return false;
      }
    }
    return true;
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Flat loss counts discarded usable chunks and divides. Weighted loss prices each discarded "
            + "chunk by the size of the nearest connected unusable body and the distance to it, so "
            + "shore beside a large water body costs less than interior ground refused because "
            + "something three keys away in 1D was bad. The discount is policy and is swept; the "
            + "shore-versus-interior composition is not, and is reported alongside.");
    REPORT.note(
        "The fixed-gap coalescer is a transcription of MemoryShape#coalesceRuns and is asserted "
            + "equal to a real flushAndRebuild before any comparison is drawn from it. The dynamic "
            + "rule is one greedy left-to-right pass whose accumulator length drives the next gap, "
            + "so it is order-dependent and not idempotent - a second pass would merge further and "
            + "is a different rule.");
    REPORT.write("proximity-weighted-loss");
  }

  @Test
  @DisplayName("the test-scope fixed coalescer matches a real flushAndRebuild run for run")
  public void fixedCoalescerMatchesShipped() {
    NoiseWorldMask world = new NoiseWorldMask(SEED, windowChunks, MOCK_SHARE);
    long[] keys = badKeys(plainSpiral(), world::isOccupied);
    for (long gap : FIXED_GAPS) {
      Square shipped = plainSpiral();
      shipped.setSpatialResolution(gap);
      for (long key : keys) shipped.addBadLocation(key, FailTypes.biome);
      shipped.flushAndRebuild(gap);
      int shippedRuns = runCount(shipped);
      int mineRuns = KeyRunTable.exact(keys, keys.length).coalesceFixed(gap).runs();
      assertEquals(shippedRuns, mineRuns, "coalescer diverges from shipped at gap " + gap);
      REPORT.add(
          "coalescer pinning",
          "gap=" + gap,
          "runs (shipped == test scope)",
          shippedRuns,
          Provenance.MEASURED);
    }
  }

  @Test
  @DisplayName("proximity weighting on both curves, both worlds, fixed and dynamic gaps")
  public void weightedLossOnBothWorlds() {
    measure("mock 45%", new NoiseWorldMask(SEED, windowChunks, MOCK_SHARE)::isOccupied);
    ProximityWeightedLoss.ChunkOccupancy real = realWorld();
    Assumptions.assumeTrue(real != null, "no real save with a fully swept window available");
    measure("real save", real);
  }

  // -------------------------------------------------------------------------------------
  // measurement
  // -------------------------------------------------------------------------------------

  private static void measure(String world, ProximityWeightedLoss.ChunkOccupancy occupancy) {
    ProximityWeightedLoss priced = new ProximityWeightedLoss(windowChunks, occupancy);
    REPORT.add(
        "terrain " + world,
        "window",
        "half-edge addressed by both curves (chunks)",
        windowChunks,
        Provenance.MEASURED);
    REPORT.add(
        "terrain " + world, "window", "usable chunks", priced.usableChunks(), Provenance.MEASURED);
    REPORT.add("terrain " + world, "bodies", "connected unusable bodies", priced.bodyCount(), Provenance.MEASURED);
    REPORT.add("terrain " + world, "bodies", "largest body (chunks)", priced.largestBody(), Provenance.MEASURED);
    for (Weighting w : WEIGHTINGS) {
      REPORT.add(
          "terrain " + world,
          label(w),
          "share of usable ground priced as shore",
          priced.shoreShareOfUsable(w),
          Provenance.MEASURED);
    }

    Square spiral = plainSpiral();
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(RADIUS_CHUNKS, POINT_CHUNKS, true);
    assertAddressedByBoth(spiral, hybrid);
    long[] spiralKeys = badKeys(spiral, occupancy);
    long[] hybridKeys = badKeys(hybrid, occupancy);
    assertEquals(spiralKeys.length, hybridKeys.length, "curves were offered different mark sets");

    KeyRunTable spiralExact = KeyRunTable.exact(spiralKeys, spiralKeys.length);
    KeyRunTable hybridExact = KeyRunTable.exact(hybridKeys, hybridKeys.length);

    for (long gap : FIXED_GAPS) {
      double[] s = emit(world, "spiral fixed gap=" + gap, priced, spiral, spiralExact.coalesceFixed(gap));
      double[] h = emit(world, "hybrid fixed gap=" + gap, priced, hybrid, hybridExact.coalesceFixed(gap));
      ratios(world, "fixed gap=" + gap, s, h);
    }
    for (double alpha : ALPHAS) {
      String subject = String.format("dynamic alpha=%.3f", alpha);
      double[] s =
          emit(world, "spiral " + subject, priced, spiral, spiralExact.coalesceDynamic(alpha, DYNAMIC_MAX_GAP));
      double[] h =
          emit(world, "hybrid " + subject, priced, hybrid, hybridExact.coalesceDynamic(alpha, DYNAMIC_MAX_GAP));
      ratios(world, subject, s, h);
    }

    assertTrue(priced.usableChunks() > 0L, "no usable ground in " + world);
  }

  /**
   * Reports one table and returns {@code {runs, flatLoss, weightedLoss}} under the primary
   * weighting, for the ratio rows.
   */
  private static double[] emit(
      String world,
      String subject,
      ProximityWeightedLoss priced,
      MemoryShape<?> curve,
      KeyRunTable table) {
    ProximityWeightedLoss.Loss primary =
        priced.lossOf(PRIMARY, (cx, cz) -> table.contains(curve.xzToLocation(cx, cz)));
    REPORT.add("tables " + world, subject, "runs", table.runs(), Provenance.MEASURED);
    REPORT.add("tables " + world, subject, "mean run length", table.meanRunLength(), Provenance.MEASURED);
    REPORT.add("tables " + world, subject, "flat usable ground discarded", primary.flat(), Provenance.MEASURED);
    REPORT.add(
        "tables " + world,
        subject,
        "weighted loss " + label(PRIMARY),
        primary.weighted(),
        Provenance.MEASURED);
    REPORT.add(
        "tables " + world,
        subject,
        "share of the loss that is shore",
        primary.shoreShareOfLoss(),
        Provenance.DERIVED);
    for (Weighting w : WEIGHTINGS) {
      if (w.equals(PRIMARY)) continue;
      ProximityWeightedLoss.Loss loss =
          priced.lossOf(w, (cx, cz) -> table.contains(curve.xzToLocation(cx, cz)));
      REPORT.add("weighting sweep " + world, subject, "weighted loss " + label(w), loss.weighted(), Provenance.MEASURED);
    }
    return new double[] {table.runs(), primary.flat(), primary.weighted()};
  }

  private static void ratios(String world, String subject, double[] spiral, double[] hybrid) {
    REPORT.add(
        "hybrid advantage " + world,
        subject,
        "spiral runs / hybrid runs",
        hybrid[0] <= 0.0d ? 0.0d : spiral[0] / hybrid[0],
        Provenance.DERIVED);
    REPORT.add(
        "hybrid advantage " + world,
        subject,
        "spiral flat loss / hybrid flat loss",
        hybrid[1] <= 0.0d ? 0.0d : spiral[1] / hybrid[1],
        Provenance.DERIVED);
    REPORT.add(
        "hybrid advantage " + world,
        subject,
        "spiral weighted loss / hybrid weighted loss",
        hybrid[2] <= 0.0d ? 0.0d : spiral[2] / hybrid[2],
        Provenance.DERIVED);
  }

  private static String label(Weighting w) {
    return String.format(
        "(depth=%d, body>=%d, beach=%.2f)", w.beachDepthChunks(), w.largeBodyChunks(), w.beachValue());
  }

  /**
   * Fails when either curve refuses a chunk in the measured window.
   *
   * <p>Cheap, and it is the invariant every comparison in this suite rests on: two tables are only
   * comparable when they were built over the same ground.
   */
  private static void assertAddressedByBoth(MemoryShape<?> spiral, MemoryShape<?> hybrid) {
    assertTrue(
        fullyAddressed(spiral, hybrid, windowChunks),
        "a chunk in the measured window is not addressed by both curves");
  }

  private static long[] badKeys(MemoryShape<?> shape, ProximityWeightedLoss.ChunkOccupancy occupancy) {
    long[] keys = new long[4 * windowChunks * windowChunks];
    int out = 0;
    for (int cx = -windowChunks; cx < windowChunks; cx++) {
      for (int cz = -windowChunks; cz < windowChunks; cz++) {
        if (occupancy.usable(cx, cz)) continue;
        long key = shape.xzToLocation(cx, cz);
        if (key < 0L || key >= shape.getRange()) continue;
        keys[out++] = key;
      }
    }
    long[] trimmed = java.util.Arrays.copyOf(keys, out);
    java.util.Arrays.sort(trimmed);
    return trimmed;
  }

  private static Square plainSpiral() {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, (long) RADIUS_CHUNKS);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    return shape;
  }

  private static int runCount(MemoryShape<?> shape) {
    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    return (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
  }

  private static ProximityWeightedLoss.ChunkOccupancy realWorld() {
    Path root = Path.of(System.getProperty(SAVE_ROOT_PROPERTY, DEFAULT_SAVE_ROOT));
    List<Path> dirs = RealWorldVerdictMask.discoverRegionDirectories(root, SAVE_SEARCH_DEPTH);
    if (dirs.isEmpty()) return null;
    RealWorldVerdictMask mask =
        RealWorldVerdictMask.load(dirs.get(0), RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, MAX_REGION_FILES);
    if (mask.inscribedRadius() < RADIUS_CHUNKS) return null;
    return mask::isOccupied;
  }
}
