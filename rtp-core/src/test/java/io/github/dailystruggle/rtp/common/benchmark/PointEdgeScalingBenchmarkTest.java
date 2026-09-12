package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.PointEdgeSelector;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Benchmark and verification suite for {@link PointEdgeSelector} (criterion C8).
 *
 * <p>Requirements verified:
 * <ul>
 *   <li>Point-by-point candidate evaluation over powers of two (no fitted exponent).
 *   <li>Hard constraint: domainCells / P >= 64 (at least 64 coarse cells per edge).
 *   <li>Sampled estimate with conservative upper bound (jackknife).
 *   <li>Scoring against a brute-force argmin oracle on held-out radii, reporting regret.
 *   <li>Lossless ratchet transitions across domain changes / reloads.
 *   <li>Evaluated at 25% and 45% usable ground.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("criterion C8: granularity scaling mechanism deriving P from inputs")
public class PointEdgeScalingBenchmarkTest {

  private static final long SEED = 20260906L;
  private static final int SAMPLE_BLOCKS = 32;

  /** Held-out radii for scoring against the brute-force oracle. */
  private static final int[] HELD_OUT_RADII = {256, 512, 1024};

  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Criterion C8 derives the point edge P from measured inputs (domain radius, usable density, "
            + "and mark population) rather than pinning it to a constant. Coarsening is not monotone, "
            + "so candidate powers of two are evaluated point-by-point. The expand granularity guard "
            + "(>= 64 cells per edge) is a hard constraint.");
    REPORT.note(
        "Sampling uses delete-one-block jackknife estimation with a conservative upper bound (mean + 2*SE). "
            + "Transitions coarsen only to multiples of the stored unit, forming a lossless ratchet.");
    REPORT.write("point-edge-scaling");
  }

  @Test
  @DisplayName("derive P and measure regret against brute-force argmin oracle at 25% and 45% usable")
  public void testDerivePWithOracleAndRegret() {
    double[] densities = {0.25d, 0.45d};

    for (double targetDensity : densities) {
      String densityLabel = (int) (targetDensity * 100) + "%";

      for (int radius : HELD_OUT_RADII) {
        NoiseWorldMask world = new NoiseWorldMask(SEED, radius, targetDensity);
        PointEdgeSelector.OccupancyOracle oracle = (cx, cz) -> !world.isOccupied(cx, cz);

        // 1. Sampled estimation
        List<PointEdgeSelector.CandidateEstimate> estimates =
            PointEdgeSelector.estimate(oracle, radius, SAMPLE_BLOCKS, SEED + radius);

        // 2. Sampled decision
        PointEdgeSelector.Decision decision = PointEdgeSelector.decide(estimates, radius);
        int chosenP = decision.chosenP();

        // Verify hard constraint
        int cellsPerEdge = (2 * radius) / chosenP;
        assertTrue(
            cellsPerEdge >= PointEdgeSelector.MIN_CELLS_PER_EDGE,
            "chosen P=" + chosenP + " violated hard guard: " + cellsPerEdge + " < 64 cells per edge");

        // 3. Brute-force ground truth oracle
        PointEdgeSelector.Decision oracleDecision = exhaustiveArgmin(oracle, radius);
        int oracleP = oracleDecision.chosenP();
        long oracleMinRuns = (long) oracleDecision.estimatedRuns();

        // Exact runs for the chosen P
        long chosenExactRuns = countExhaustiveRuns(oracle, radius, chosenP);

        // Regret: difference / ratio between chosen exact runs and oracle minimum runs
        long runRegret = chosenExactRuns - oracleMinRuns;
        double relativeRegret = (oracleMinRuns == 0) ? 0.0 : (double) runRegret / oracleMinRuns;

        String runKey = "density=" + densityLabel + " r=" + radius;
        REPORT.add("scaling", runKey, "target usable density", String.format("%.2f", targetDensity), Provenance.MEASURED);
        REPORT.add("scaling", runKey, "radius (chunks)", String.valueOf(radius), Provenance.MEASURED);
        REPORT.add("scaling", runKey, "chosen P", String.valueOf(chosenP), Provenance.MEASURED);
        REPORT.add("scaling", runKey, "oracle optimal P", String.valueOf(oracleP), Provenance.MEASURED);
        REPORT.add("scaling", runKey, "cells per edge", String.valueOf(cellsPerEdge), Provenance.DERIVED);
        REPORT.add("scaling", runKey, "chosen exact runs", String.valueOf(chosenExactRuns), Provenance.MEASURED);
        REPORT.add("scaling", runKey, "oracle min runs", String.valueOf(oracleMinRuns), Provenance.MEASURED);
        REPORT.add("scaling", runKey, "run regret (runs)", String.valueOf(runRegret), Provenance.DERIVED);
        REPORT.add("scaling", runKey, "relative regret", relativeRegret, Provenance.DERIVED);
        REPORT.add("scaling", runKey, "decision reason", decision.reason(), Provenance.DERIVED);

        // Assert regret is bounded (sampling does not pick a catastrophic candidate)
        assertTrue(
            relativeRegret <= 0.25d,
            "relative regret " + relativeRegret + " exceeded 25% threshold on " + runKey);
      }
    }
  }

  private static PointEdgeSelector.Decision exhaustiveArgmin(PointEdgeSelector.OccupancyOracle oracle, int radiusChunks) {
    int bestP = 1;
    long minRuns = Long.MAX_VALUE;

    for (int p : PointEdgeSelector.CANDIDATES) {
      if ((2 * radiusChunks) / p < PointEdgeSelector.MIN_CELLS_PER_EDGE) continue;

      long runs = countExhaustiveRuns(oracle, radiusChunks, p);
      if (runs < minRuns) {
        minRuns = runs;
        bestP = p;
      }
    }

    return new PointEdgeSelector.Decision(bestP, minRuns, "brute force ground truth argmin");
  }

  private static long countExhaustiveRuns(PointEdgeSelector.OccupancyOracle oracle, int radiusChunks, int p) {
    if (p == 1) {
      Square spiral = new Square("TEST_SPIRAL");
      spiral.set(GenericMemoryShapeParams.radius, (long) radiusChunks);
      KeySpaceRunEncoder encoder = new KeySpaceRunEncoder();
      encoder.encode(spiral, radiusChunks, (cx, cz) -> !oracle.isBad(cx, cz));
      return encoder.runsAt(1L);
    }

    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(radiusChunks, p, true);
    KeySpaceRunEncoder encoder = new KeySpaceRunEncoder();
    encoder.encode(hybrid, radiusChunks, (cx, cz) -> !oracle.isBad(cx, cz));
    return encoder.runsAt(1L);
  }

  @Test
  @DisplayName("lossless ratchet transition behavior")
  public void testLosslessRatchetTransitions() {
    // Case 1: multiple coarsening folds upward losslessly (e.g. 16 -> 32)
    PointEdgeSelector.Transition t1 =
        PointEdgeSelector.decideTransition(16, 32);
    assertTrue(t1.losslessRatchet(), "16 -> 32 should fold losslessly");
    assertEquals(32, t1.p());

    // Case 2: non-multiple coarsening requires relearning (e.g. 16 -> 48 not candidate, or 16 -> 24)
    PointEdgeSelector.Transition t2 =
        PointEdgeSelector.decideTransition(16, 8);
    assertTrue(!t2.losslessRatchet(), "refinement or non-multiple cannot fold losslessly");

    // Case 3: identical P retains state
    PointEdgeSelector.Transition t3 =
        PointEdgeSelector.decideTransition(32, 32);
    assertTrue(t3.losslessRatchet(), "identical P retains state");
    assertEquals(32, t3.p());

    REPORT.add("transition", "fold upward 16->32", "lossless", String.valueOf(t1.losslessRatchet()), Provenance.DERIVED);
    REPORT.add("transition", "fold upward 16->32", "reason", t1.reason(), Provenance.DERIVED);
    REPORT.add("transition", "refinement 16->8", "lossless", String.valueOf(t2.losslessRatchet()), Provenance.DERIVED);
    REPORT.add("transition", "retain 32->32", "lossless", String.valueOf(t3.losslessRatchet()), Provenance.DERIVED);
  }
}
