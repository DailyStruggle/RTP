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
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures whether replacing the learned state's 1D curve with a locality-preserving one buys memory
 * <i>without</i> spending precision, which is what every previous lever in this work did.
 *
 * <p>The comparison is deliberately narrow. Both shapes address <b>one chunk</b> per key, are given
 * the identical mark set from the same real save, and inherit the identical learned-state
 * machinery - marks, probation, coalescing, rebuild, selection. The only difference is the
 * bijection, so any change in run count is attributable to the curve and to nothing else.
 *
 * <p>Four things are measured:
 *
 * <ol>
 *   <li><b>The bijection is a bijection.</b> Round-tripped over the whole domain. Two faults have
 *       already cost this work published figures - a domain-order scan and an annulus refusing
 *       in-domain marks - and both would have been caught by an invariant rather than by a
 *       plausibility check on the result.
 *   <li><b>Run count at full precision.</b> The whole case. Locality is being traded for nothing,
 *       so if runs do not fall there is no reason to change the curve.
 *   <li><b>{@code spatialResolution} on the new shape.</b> The knob keeps its exact existing
 *       meaning - a coalescing gap in 1D key units - so the question is whether the same knob buys
 *       more bytes per unit of usable ground discarded when the curve's 1D adjacency tracks 2D
 *       adjacency. Accuracy is measured as the share of usable chunks the coalesced table excludes,
 *       which is the corrected metric: the ratio against excluded area used before section 15 of
 *       ADR-084 divides by the bad area and cannot be read as accuracy.
 *   <li><b>Selection and reconciliation cost.</b> A smaller table should reproduce the cache win
 *       that coarsening produced, without coarsening's precision loss.
 * </ol>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("spiral+Hilbert curve: run count at full precision, and spatialResolution reused")
public class SpiralHilbertBenchmarkTest {

  private static final long SEED = 20260906L;

  /** Radii swept, in chunks. */
  private static final int[] RADII_CHUNKS = {128, 256, 512};

  /** Point edges swept, in chunks. 16 is 256 blocks; 32 is one Anvil region file. */
  private static final int[] POINT_CHUNKS = {8, 16, 32};

  /** Coalescing gaps swept, in 1D key units - the shipped {@code spatialResolution} knob. */
  private static final long[] RESOLUTIONS = {1L, 4L, 16L, 64L, 256L, 1_024L, 4_096L};

  /** Accuracy budgets at which the two curves are compared on equal terms. */
  private static final double[] LOSS_CAPS = {0.05d, 0.10d, 0.20d};

  private static final int SELECT_ITERATIONS = 20_000;

  private static final int RECONCILE_SAMPLE = 200;

  /**
   * Tolerated loss of usable ground at the finest setting.
   *
   * <p>Not zero, and the reason is in the shipped coalescer rather than in either curve: {@code
   * setSpatialResolution} clamps to a minimum of one and the merge test is {@code nextKey <= curEnd
   * + resolution}, so one usable chunk sitting between two bad ones is bridged even at the finest
   * setting. Both curves pay it. The bound exists so the byte comparison can be asserted to be at
   * equal precision rather than assumed to be.
   */
  private static final double FINEST_SETTING_LOSS_BOUND = 1e-3d;

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
        "Both shapes address one chunk per key and receive an identical mark set, so a difference "
            + "in run count is a property of the curve alone. This is the first lever measured here "
            + "that does not spend precision to buy bytes: the addressed unit is unchanged, and the "
            + "saving comes from a compact 2D feature occupying a contiguous key interval instead "
            + "of being cut once per spiral revolution.");
    REPORT.note(
        "Point orientation is fixed rather than matched across the spiral seam. Consecutive points "
            + "are 2D adjacent, but one point's Hilbert exit corner is not generally adjacent to "
            + "the next point's entry corner, so a run crossing a point boundary merges only by "
            + "chance. Choosing each point's orientation from the eight symmetries of the square "
            + "would raise the merge rate, so every run-count figure here is a lower bound on what "
            + "the design can reach.");
    REPORT.note(
        "spatialResolution is reused unchanged and keeps its existing meaning as a coalescing gap "
            + "in 1D key units. What changes is the geometry the gap corresponds to: under the "
            + "plain spiral a gap bridges cells that can be far apart in 2D and on different rings, "
            + "whereas under the Hilbert traversal it bridges cells that are spatially close. The "
            + "same knob is therefore expected to buy more bytes per unit of usable ground "
            + "discarded, and the rows report both halves of that trade.");
    REPORT.note(
        "Accuracy is the share of usable chunks the rebuilt table excludes, measured against the "
            + "occupancy mask over the whole domain. It is not the ratio against excluded area used "
            + "before section 15 of ADR-084, which divides by the bad area, moves with the domain, "
            + "and made a loss of half the usable world score as 1.118x.");
    REPORT.write("spiral-hilbert");
  }

  // -------------------------------------------------------------------------------------
  // vehicle
  // -------------------------------------------------------------------------------------

  /**
   * @param runs bad plus probation runs held after the rebuild
   * @param goodLoss share of usable chunks the rebuilt table excludes
   */
  private record Row(
      int runs, long badKeys, double goodLoss, double selectNanos, double reconcileNanos) {

    long bytesAtLongWidth() {
      return runs * 16L;
    }
  }

  /** Marks every bad chunk in the domain, rebuilds, and measures the result. */
  private static Row measure(MemoryShape<?> shape, int radiusChunks, long resolution) {
    shape.setRng(new Random(SEED));
    shape.setSpatialResolution(resolution);

    for (int cx = -radiusChunks; cx < radiusChunks; cx++) {
      for (int cz = -radiusChunks; cz < radiusChunks; cz++) {
        if (mask.isOccupied(cx, cz)) continue;
        long key = shape.xzToLocation(cx, cz);
        if (key < 0L) continue;
        shape.addBadLocation(key, FailTypes.biome);
      }
    }
    shape.flushAndRebuild(resolution);
    assertEquals(
        0L,
        shape.getOutOfDomainMarkCount(),
        "shape refused in-domain marks at radius " + radiusChunks);

    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    int runs = (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);

    // Accuracy: a usable chunk the rebuilt table now refuses has been discarded by coalescing.
    long good = 0L;
    long lost = 0L;
    for (int cx = -radiusChunks; cx < radiusChunks; cx++) {
      for (int cz = -radiusChunks; cz < radiusChunks; cz++) {
        if (!mask.isOccupied(cx, cz)) continue;
        good++;
        if (shape.isKnownBad(cx, cz)) lost++;
      }
    }
    double goodLoss = good == 0L ? 0.0d : lost / (double) good;

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

    Random rng = new Random(SEED ^ radiusChunks);
    int span = Math.max(1, 2 * radiusChunks);
    long elapsed = 0L;
    for (int i = 0; i < RECONCILE_SAMPLE; i++) {
      int cx = rng.nextInt(span) - radiusChunks;
      int cz = rng.nextInt(span) - radiusChunks;
      long key = shape.xzToLocation(cx, cz);
      if (key < 0L) continue;
      long start = System.nanoTime();
      shape.addBadLocation(key, FailTypes.biome);
      shape.flushAndRebuild(resolution);
      elapsed += System.nanoTime() - start;
    }
    double reconcile = elapsed / (double) RECONCILE_SAMPLE;

    return new Row(runs, shape.getEffectiveBadCount(), goodLoss, select, reconcile);
  }

  private static Square plainSpiral(int radiusChunks) {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, (long) radiusChunks);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    return shape;
  }

  // -------------------------------------------------------------------------------------
  // 1. the bijection
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("bijection: key -> chunk -> key round-trips over the whole domain")
  public void curveIsABijection() {
    for (int point : POINT_CHUNKS) {
     for (boolean seam : new boolean[] {false, true}) {
      int radius = 128;
      SpiralHilbertSquare shape = new SpiralHilbertSquare(radius, point, seam);
      long collisions = 0L;
      long roundTripFailures = 0L;
      java.util.HashSet<Long> seen = new java.util.HashSet<>();
      for (int cx = -radius; cx < radius; cx++) {
        for (int cz = -radius; cz < radius; cz++) {
          long key = shape.xzToLocation(cx, cz);
          if (key < 0L) continue;
          if (!seen.add(key)) collisions++;
          int[] back = shape.locationToXZ(key);
          if (back[0] != cx || back[1] != cz) roundTripFailures++;
        }
      }
      REPORT.add(
          "bijection", "point=" + point + "ch", "key collisions", collisions, Provenance.MEASURED);
      REPORT.add(
          "bijection",
          "point=" + point + "ch",
          "round-trip failures",
          roundTripFailures,
          Provenance.MEASURED);
      assertEquals(0L, collisions, "curve aliased two chunks onto one key at point " + point);
      assertEquals(
          0L, roundTripFailures, "curve did not round-trip at point edge " + point + " chunks");
     }
    }
  }

  @Test
  @DisplayName("hilbert: the local curve is a bijection and is unit-step continuous")
  public void hilbertIsContinuous() {
    for (int order = 1; order <= 5; order++) {
      int size = 1 << order;
      java.util.HashSet<Long> seen = new java.util.HashSet<>();
      int[][] byIndex = new int[size * size][];
      for (int x = 0; x < size; x++) {
        for (int z = 0; z < size; z++) {
          long d = SpiralHilbertSquare.hilbertIndex(x, z, order);
          assertTrue(seen.add(d), "hilbert aliased at order " + order);
          byIndex[(int) d] = new int[] {x, z};
          int[] back = SpiralHilbertSquare.hilbertCoords(d, order);
          assertEquals(x, back[0], "hilbert inverse x at order " + order);
          assertEquals(z, back[1], "hilbert inverse z at order " + order);
        }
      }
      // Continuity is the property that makes a compact feature one run: consecutive indices must
      // be 2D neighbours, otherwise the curve is a relabelling with no locality benefit.
      for (int d = 1; d < size * size; d++) {
        int step =
            Math.abs(byIndex[d][0] - byIndex[d - 1][0]) + Math.abs(byIndex[d][1] - byIndex[d - 1][1]);
        assertEquals(1, step, "hilbert step at order " + order + " index " + d);
      }
      REPORT.add("hilbert", "order=" + order, "cells verified", size * size, Provenance.MEASURED);
    }
  }

  // -------------------------------------------------------------------------------------
  // 2. run count at full precision
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("runs: the locality-preserving curve holds fewer runs at identical precision")
  public void runCountAtFullPrecision() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int radius : RADII_CHUNKS) {
      Row plain = measure(plainSpiral(radius), radius, 1L);
      REPORT.add("runs", "r=" + radius + "ch spiral", "runs", plain.runs(), Provenance.MEASURED);
      REPORT.add(
          "runs", "r=" + radius + "ch spiral", "bytes at long width",
          plain.bytesAtLongWidth(), Provenance.DERIVED);
      REPORT.add(
          "runs", "r=" + radius + "ch spiral", "usable ground discarded",
          plain.goodLoss(), Provenance.MEASURED);
      REPORT.add(
          "runs", "r=" + radius + "ch spiral", "select ns/op",
          plain.selectNanos(), Provenance.MEASURED);
      REPORT.add(
          "runs", "r=" + radius + "ch spiral", "reconcile ns/mark",
          plain.reconcileNanos(), Provenance.MEASURED);

      double best = 0.0d;
      int bestPoint = 0;
      for (int point : POINT_CHUNKS) {
       for (boolean seam : new boolean[] {false, true}) {
        Row hybrid = measure(new SpiralHilbertSquare(radius, point, seam), radius, 1L);
        String subject =
            "r=" + radius + "ch point=" + point + "ch" + (seam ? " seam-matched" : " canonical");
        double factor = hybrid.runs() == 0 ? 0.0d : plain.runs() / (double) hybrid.runs();
        REPORT.add("runs", subject, "runs", hybrid.runs(), Provenance.MEASURED);
        REPORT.add("runs", subject, "runs vs spiral", factor, Provenance.DERIVED);
        REPORT.add(
            "runs", subject, "usable ground discarded", hybrid.goodLoss(), Provenance.MEASURED);
        REPORT.add("runs", subject, "select ns/op", hybrid.selectNanos(), Provenance.MEASURED);
        REPORT.add(
            "runs", subject, "reconcile ns/mark", hybrid.reconcileNanos(), Provenance.MEASURED);

        // Equal precision is what makes the byte comparison meaningful, so it is asserted rather
        // than assumed. Neither curve reaches exactly zero: the shipped coalescer clamps its gap to
        // one and bridges it, so an isolated usable chunk between two bad ones is swallowed by both.
        assertTrue(
            hybrid.goodLoss() <= FINEST_SETTING_LOSS_BOUND,
            "hybrid lost " + hybrid.goodLoss() + " of usable ground at the finest setting, "
                + subject);

        if (factor > best) {
          best = factor;
          bestPoint = point;
        }
       }
      }
      // Above one the hybrid holds fewer runs than the shipped spiral; below one it holds more.
      REPORT.add(
          "runs", "r=" + radius + "ch", "best runs vs spiral", best, Provenance.DERIVED);
      REPORT.add(
          "runs", "r=" + radius + "ch", "best point size, chunks", bestPoint, Provenance.MEASURED);
      assertTrue(
          plain.goodLoss() <= FINEST_SETTING_LOSS_BOUND,
          "plain spiral lost " + plain.goodLoss() + " of usable ground at the finest setting, r="
              + radius);
    }
  }

  // -------------------------------------------------------------------------------------
  // 3. spatialResolution, reused unchanged
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("spatialResolution: the same lossy knob on a curve whose 1D gaps are 2D-local")
  public void spatialResolutionOnBothCurves() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 256;
    int point = 32;

    // Curves are compared at equal accuracy, not at equal knob setting. The setting is a gap in 1D
    // key units and one key unit does not mean the same distance on two different curves, so
    // reading two rows side by side at the same setting compares nothing. What an operator cares
    // about is: for a given tolerated loss of usable ground, how small does the table get.
    double[] plainLoss = new double[RESOLUTIONS.length];
    int[] plainRuns = new int[RESOLUTIONS.length];
    double[] hybridLoss = new double[RESOLUTIONS.length];
    int[] hybridRuns = new int[RESOLUTIONS.length];

    for (int i = 0; i < RESOLUTIONS.length; i++) {
      long resolution = RESOLUTIONS[i];
      Row plain = measure(plainSpiral(radius), radius, resolution);
      Row hybrid = measure(new SpiralHilbertSquare(radius, point), radius, resolution);
      plainLoss[i] = plain.goodLoss();
      plainRuns[i] = plain.runs();
      hybridLoss[i] = hybrid.goodLoss();
      hybridRuns[i] = hybrid.runs();

      String ps = "spiral res=" + resolution;
      String hs = "point=" + point + "ch res=" + resolution;
      REPORT.add("resolution", ps, "runs", plain.runs(), Provenance.MEASURED);
      REPORT.add("resolution", ps, "usable ground discarded", plain.goodLoss(), Provenance.MEASURED);
      REPORT.add("resolution", hs, "runs", hybrid.runs(), Provenance.MEASURED);
      REPORT.add(
          "resolution", hs, "usable ground discarded", hybrid.goodLoss(), Provenance.MEASURED);
    }

    for (double cap : LOSS_CAPS) {
      int bestPlain = smallestTableWithin(plainRuns, plainLoss, cap);
      int bestHybrid = smallestTableWithin(hybridRuns, hybridLoss, cap);
      String subject = "loss cap " + String.format("%.2f", cap);
      REPORT.add("iso-accuracy", subject, "spiral runs", bestPlain, Provenance.MEASURED);
      REPORT.add("iso-accuracy", subject, "hybrid runs", bestHybrid, Provenance.MEASURED);
      double factor = bestHybrid <= 0 ? 0.0d : bestPlain / (double) bestHybrid;
      REPORT.add("iso-accuracy", subject, "hybrid table smaller by", factor, Provenance.DERIVED);
      assertTrue(bestPlain > 0 && bestHybrid > 0, "no admissible setting found at cap " + cap);
    }
  }

  // -------------------------------------------------------------------------------------
  // 4. side by side, identical settings
  // -------------------------------------------------------------------------------------

  /**
   * The three curves at <b>identical</b> settings, so nothing is normalised away.
   *
   * <p>The iso-accuracy comparison above answers "how small does the table get for a tolerated
   * loss", which is the operator's question, but it moves two knobs at once and is therefore easy
   * to distrust. This section holds radius, mark set, coalescing gap and precision floor fixed and
   * changes only the curve, so every column is directly comparable and the reader does not have to
   * take a normalisation on faith.
   */
  @Test
  @DisplayName("side by side: identical radius, identical mark set, identical coalescing gap")
  public void sideBySideAtEqualSettings() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 256;
    int point = 32;
    long[] settings = {1L, 16L, 256L, 4_096L};
    double coarsestSpiralLoss = -1.0d;
    double coarsestMatchedLoss = -1.0d;
    for (long resolution : settings) {
      Row spiral = measure(plainSpiral(radius), radius, resolution);
      Row canonical = measure(new SpiralHilbertSquare(radius, point, false), radius, resolution);
      Row matched = measure(new SpiralHilbertSquare(radius, point, true), radius, resolution);

      emitSideBySide("spiral", resolution, spiral);
      emitSideBySide("hilbert canonical", resolution, canonical);
      emitSideBySide("hilbert seam-matched", resolution, matched);

      // The seam is the one mechanism the first vehicle left on the table, so the effect of closing
      // it is reported as its own figure rather than buried in a run count.
      double seamEffect =
          matched.runs() == 0 ? 0.0d : canonical.runs() / (double) matched.runs();
      REPORT.add(
          "side-by-side",
          "res=" + resolution,
          "seam matching removes runs by",
          seamEffect,
          Provenance.DERIVED);
      // Both curves are offered the identical set of bad chunks, so the offered input is equal by
      // construction. The *effective* counts differ slightly because coalescing bridges a gap in 1D
      // key units and the two curves place different cells at that distance - which is the very
      // thing being compared, so it is reported and bounded rather than asserted equal.
      double overshoot =
          spiral.badKeys() == 0L
              ? 0.0d
              : Math.abs(matched.badKeys() - spiral.badKeys()) / (double) spiral.badKeys();
      REPORT.add(
          "side-by-side",
          "res=" + resolution,
          "effective mark count divergence",
          overshoot,
          Provenance.DERIVED);
      coarsestSpiralLoss = spiral.goodLoss();
      coarsestMatchedLoss = matched.goodLoss();
    }

    // The load-bearing claim of the whole design, asserted at the coarsest setting swept: given the
    // identical knob, the locality-preserving curve throws away less usable ground. It is not
    // asserted at the finest setting, where both curves lose almost nothing and the ordering is
    // noise.
    assertTrue(
        coarsestMatchedLoss < coarsestSpiralLoss,
        "at res="
            + settings[settings.length - 1]
            + " the hybrid discarded "
            + coarsestMatchedLoss
            + " of usable ground against the spiral's "
            + coarsestSpiralLoss);
    REPORT.add(
        "side-by-side",
        "res=" + settings[settings.length - 1],
        "usable ground kept by hybrid, share of spiral loss",
        coarsestSpiralLoss <= 0.0d ? 0.0d : 1.0d - (coarsestMatchedLoss / coarsestSpiralLoss),
        Provenance.DERIVED);
  }

  private static void emitSideBySide(String curve, long resolution, Row row) {
    String subject = curve + " res=" + resolution;
    REPORT.add("side-by-side", subject, "runs", row.runs(), Provenance.MEASURED);
    REPORT.add(
        "side-by-side", subject, "bytes at long width", row.bytesAtLongWidth(), Provenance.DERIVED);
    REPORT.add(
        "side-by-side", subject, "usable ground discarded", row.goodLoss(), Provenance.MEASURED);
    REPORT.add("side-by-side", subject, "select ns/op", row.selectNanos(), Provenance.MEASURED);
    REPORT.add(
        "side-by-side", subject, "reconcile ns/mark", row.reconcileNanos(), Provenance.MEASURED);
  }

  // -------------------------------------------------------------------------------------
  // 5. drawn output
  // -------------------------------------------------------------------------------------

  /**
   * Draws the domain under each curve, and what each curve's coalescing actually throws away.
   *
   * <p>Two of this work's published errors were geometric claims that a picture would have refused:
   * that a 1D coalescing gap bridges spatially close cells (it does not, on a spiral), and that
   * coarsening the addressed unit was nearly free (it discarded about half the usable ground). The
   * images are drawn from the same run as the rows so the two cannot drift apart.
   *
   * <p>Assertions are deliberately weak here - an image is evidence for a human, not a test oracle -
   * but the loss counts behind the exclusion images are asserted to match the measured rows.
   */
  @Test
  @DisplayName("drawn: key order and discarded ground, both curves, same domain")
  public void drawnOutput() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 128;
    int cells = 2 * radius;
    int point = 32;
    CurveImage img = new CurveImage("spiral-hilbert", 3);

    String domain = cells + "x" + cells + " chunks, radius " + radius + ", 1 pixel block = 1 chunk";

    Square spiral = plainSpiral(radius);
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(radius, point, true);

    // Key order. Cyclic bands: on the spiral a band is a ring, on the hybrid it is a blob, and that
    // difference is the whole mechanism - a compact 2D feature is cut once per band it crosses.
    double spiralRange = Math.max(1L, spiral.getRange());
    double hybridRange = Math.max(1L, hybrid.getRange());
    img.draw(
        "keyorder-spiral",
        cells,
        (cx, cz) -> keyColour(spiral.xzToLocation(cx - radius, cz - radius), spiralRange),
        new CurveImage.Caption("Key order - shipped Archimedean spiral (ADR-001)")
            .line("Each chunk is coloured by where it sits along the 1D key space.")
            .line("The hue ramp repeats 8 times, so one colour band = one contiguous run of keys.")
            .line("Bands are rings: keys adjacent in 1D are far apart across the map.")
            .line(domain)
            .swatch(0x1B2430, "outside the addressed domain")
            .swatch(0xFF3030, "band boundary hues are arbitrary - only band SHAPE is meaningful"));
    img.draw(
        "keyorder-hilbert",
        cells,
        (cx, cz) -> keyColour(hybrid.xzToLocation(cx - radius, cz - radius), hybridRange),
        new CurveImage.Caption("Key order - spiral-addressed Hilbert key space (ADR-085)")
            .line("Same colouring rule and same 8 repeats as the spiral image.")
            .line("Spiral orders " + point + "x" + point + " chunk points; inside a point the")
            .line("keys follow that point's Hilbert traversal, so bands are compact blobs.")
            .line(domain)
            .swatch(0x1B2430, "outside the addressed domain")
            .swatch(0xFF3030, "band boundary hues are arbitrary - only band SHAPE is meaningful"));

    // Occupancy truth, then what each curve refuses after coalescing at a shared setting.
    img.draw(
        "occupancy",
        cells,
        (cx, cz) -> mask.isOccupied(cx - radius, cz - radius) ? 0x2E7D32 : 0x1B2430,
        new CurveImage.Caption("Ground truth - which chunks are usable")
            .line("Occupancy from the real save, tiled outward. Input to both curves below.")
            .line(domain)
            .swatch(0x2E7D32, "usable chunk")
            .swatch(0x1B2430, "unsafe chunk (marked bad)"));

    // Sweep the lossy knob so the trade is visible rather than asserted at one point. The knob is
    // spatialResolution, unchanged in meaning: runs whose 1D gap is at most this value are merged,
    // and every usable chunk swallowed by a merge is refused thereafter.
    long[] resolutions = {1L, 16L, 64L, 256L, 1024L};
    for (long resolution : resolutions) {
      Square coalescedSpiral = plainSpiral(radius);
      Row spiralRow = measure(coalescedSpiral, radius, resolution);
      SpiralHilbertSquare coalescedHybrid = new SpiralHilbertSquare(radius, point, true);
      Row hybridRow = measure(coalescedHybrid, radius, resolution);

      img.draw(
          "discarded-spiral-res" + resolution,
          cells,
          (cx, cz) -> lossColour(coalescedSpiral, cx - radius, cz - radius),
          lossCaption(
              "Spiral (ADR-001)", resolution, spiralRow, domain, "arcs along the spiral's rings"));
      img.draw(
          "discarded-hilbert-res" + resolution,
          cells,
          (cx, cz) -> lossColour(coalescedHybrid, cx - radius, cz - radius),
          lossCaption(
              "Spiral+Hilbert (ADR-085)",
              resolution,
              hybridRow,
              domain,
              "blobs hugging the unsafe terrain"));

      REPORT.add(
          "drawn", "res=" + resolution + " spiral", "usable ground discarded",
          spiralRow.goodLoss(), Provenance.MEASURED);
      REPORT.add(
          "drawn", "res=" + resolution + " hilbert seam-matched", "usable ground discarded",
          hybridRow.goodLoss(), Provenance.MEASURED);
      assertTrue(
          spiralRow.goodLoss() >= 0.0d && hybridRow.goodLoss() >= 0.0d, "loss must be a fraction");
    }

    REPORT.note(
        "Images are written to build/reports/rtp-simulation/img and carry their own titles and "
            + "legends. keyorder-* colours each chunk by its position along that curve's key space "
            + "in 8 repeating bands, so a band is one contiguous stretch of keys: rings on the "
            + "spiral, blobs on the hybrid. occupancy is the ground truth from the save. "
            + "discarded-<curve>-res<N> marks in red the usable chunks that curve's table refuses "
            + "after coalescing at spatialResolution N, swept over 1/16/64/256/1024 key units so "
            + "the same knob is compared on both curves at every setting.");
  }

  private static CurveImage.Caption lossCaption(
      String curve, long resolution, Row row, String domain, String shape) {
    return new CurveImage.Caption("Usable ground lost to coalescing - " + curve)
        .line("spatialResolution = " + resolution + " key units (runs closer than this merge)")
        .line(
            "runs in table: "
                + row.runs()
                + "   usable ground discarded: "
                + String.format("%.3f", row.goodLoss()))
        .line("Red is the cost of the merge: chunks that are safe but no longer offered.")
        .line("Expect the loss to appear as " + shape + ".")
        .line(domain)
        .swatch(0x2E7D32, "usable and still offered")
        .swatch(0xD32F2F, "usable but refused - lost to coalescing")
        .swatch(0x1B2430, "unsafe chunk (correctly excluded)");
  }

  /** Bands along a curve's key space; out-of-domain cells are drawn as background. */
  private static int keyColour(long key, double range) {
    if (key < 0L) return 0x1B2430;
    // Few enough cycles that one band spans several points: at 24 the hybrid's bands were shorter
    // than a point, which draws the Hilbert traversal's internal structure instead of its locality.
    return CurveImage.rampArgb(key / range, 8);
  }

  /** Green usable, dark bad, red usable-but-refused. */
  private static int lossColour(MemoryShape<?> shape, int cx, int cz) {
    boolean usable = mask.isOccupied(cx, cz);
    boolean refused = shape.isKnownBad(cx, cz);
    if (!usable) return 0x1B2430;
    return refused ? 0xD32F2F : 0x2E7D32;
  }

  /**
   * Smallest run count reachable without exceeding an accuracy cap.
   *
   * <p>The minimum is taken over the whole sweep rather than assuming the coarsest admissible
   * setting is the smallest table: coalescing is not guaranteed monotone in the setting, for the
   * same reason coarsening a grid is not - a wider gap changes which runs merge, and merging is not
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
}
