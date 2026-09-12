package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-083 section 8 - proof of the two-tier routing <b>model</b>, before any decision about
 * adopting it.
 *
 * <p>This is not an approval artifact and it does not ship anything: {@link TwoTierCellRouter}
 * lives in the same opt-in benchmark tier and is not registered anywhere. The tests here answer
 * the four questions that decide whether the model is even worth optimizing:
 *
 * <ol>
 *   <li>Is the draw <b>exactly uniform over good cells</b> at {@code r > 1} with deliberately
 *       unequal micro-cell occupancy? This is the section 4a claim, and the one a bitmask
 *       implementation silently fails.
 *   <li>Can the sub-draw ever return a known-bad cell, or fail to find its k-th good cell? Both
 *       must be structurally impossible, not merely rare (S-004).
 *   <li>Is the route reproducible bit for bit under an injected RNG at a fixed epoch?
 *   <li>Does the {@code PI_MIN} floor really guarantee inclusion at least once per 64 epochs?
 * </ol>
 *
 * <p>Run with {@code ./gradlew :rtp-core:simulationBenchmark}. Excluded from {@code test}.
 */
@Tag("simulation")
@DisplayName("ADR-083 two-tier cell router - model proof")
class TwoTierRouterModelBenchmarkTest {

  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  @AfterAll
  static void writeReport() {
    REPORT.write("adr083-two-tier-model");
  }

  /**
   * Deliberately unequal micro-cell occupancy: with {@code r = 4} the good-cell count per
   * micro-cell cycles over {@code 1..4} diagonals, so a set-bit ("this micro-cell has something")
   * draw would over-represent the sparse ones fourfold. That is precisely the bias ADR-083
   * section 4a rejects option 2 over, so it is the mask the uniformity test has to run against.
   */
  private static boolean unequalOccupancy(int x, int z) {
    int density = 1 + Math.floorMod((x >> 2) + (z >> 2), 4);
    return Math.floorMod(x, 4) + Math.floorMod(z, 4) < density;
  }

  private static TwoTierCellRouter router(int r, int m, long radius, TwoTierCellRouter.CellValidity validity) {
    TwoTierCellRouter shape = new TwoTierCellRouter(r, m, 0xA083L);
    shape.set(GenericMemoryShapeParams.radius, radius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    shape.setValidity(validity);
    return shape;
  }

  // -------------------------------------------------------------------------------------
  // 1 + 2: exact uniformity over good cells, and no bad cell is ever addressable
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("exact uniformity over good cells at r>1 with unequal micro-cell occupancy")
  void exactUniformityAtMicroEdgeAboveOne() {
    final int r = 4;
    final int m = 8;
    final long radius = 64L;
    TwoTierCellRouter shape = router(r, m, radius, TwoTierRouterModelBenchmarkTest::unequalOccupancy);
    shape.setRng(new Random(0xC0FFEEL));
    TwoTierCellRouter.Snapshot snap = shape.compile();

    // Ground truth: the good cells, enumerated independently of the descriptor.
    List<Long> goodCells = new ArrayList<>();
    for (int x = (int) -radius; x <= radius; x++) {
      for (int z = (int) -radius; z <= radius; z++) {
        if (!shape.inDomain(x, z)) continue;
        if (!unequalOccupancy(x, z)) continue;
        goodCells.add((((long) x) << 32) | (z & 0xFFFFFFFFL));
      }
    }
    int n = goodCells.size();
    assertTrue(n > 0, "the mask must leave good cells to draw");
    assertEquals(
        n,
        snap.includedGoodCells(),
        "the descriptor's counted good cells must equal the brute-force count; "
            + "a mismatch means Tier 2 is drawing from a different population than it claims");

    Map<Long, Integer> binOf = new HashMap<>();
    final int bins = 64;
    for (int i = 0; i < n; i++) binOf.put(goodCells.get(i), (int) ((long) i * bins / n));

    final int draws = bins * 500;
    long[] observed = new long[bins];
    for (int i = 0; i < draws; i++) {
      int[] cell = shape.selectCell();
      assertNotNull(cell, "a domain with good cells must always route to one");
      assertTrue(
          unequalOccupancy(cell[0], cell[1]),
          "the sub-draw returned a known-bad cell at " + cell[0] + "," + cell[1]);
      Integer bin = binOf.get((((long) cell[0]) << 32) | (cell[1] & 0xFFFFFFFFL));
      assertNotNull(bin, "the router returned a cell outside the good set");
      observed[bin]++;
    }

    // Bins hold near-equal numbers of good cells, so expectations are near-equal too; using the
    // exact per-bin cell count as the expectation avoids charging integer binning to the model.
    long[] cellsPerBin = new long[bins];
    for (int i = 0; i < n; i++) cellsPerBin[(int) ((long) i * bins / n)]++;
    double chiSquare = 0.0;
    for (int i = 0; i < bins; i++) {
      double expected = (double) draws * cellsPerBin[i] / n;
      double d = observed[i] - expected;
      chiSquare += d * d / expected;
    }
    double p = chiSquareUpperTail(chiSquare, bins - 1);

    String section = "uniformity (unsubsampled)";
    REPORT.add(section, "r=4 M=8 radius=64", "good cells", Long.toString(n), Provenance.MEASURED);
    REPORT.add(section, "r=4 M=8 radius=64", "draws", Integer.toString(draws), Provenance.MEASURED);
    REPORT.add(section, "r=4 M=8 radius=64", "chi-square (63 dof)", chiSquare, Provenance.MEASURED);
    REPORT.add(section, "r=4 M=8 radius=64", "p-value", p, Provenance.DERIVED);
    REPORT.add(section, "r=4 M=8 radius=64", "sub-draw misses", Long.toString(shape.subDrawMisses()), Provenance.MEASURED);
    REPORT.add(section, "r=4 M=8 radius=64", "zero-weight draws", Long.toString(shape.zeroWeightDraws()), Provenance.MEASURED);
    REPORT.note(
        "Micro-cell occupancy in this mask varies 1..4 good cells out of 16, so a set-bit Tier 2a "
            + "draw would over-represent the sparse micro-cells fourfold. The count-based draw is "
            + "the only reason this chi-square passes.");

    assertTrue(p >= 0.01, "uniformity rejected at p=" + p + " (chi-square " + chiSquare + ")");
    assertEquals(0L, shape.subDrawMisses(), "ADR-083 section 4a: the k-th good cell must always exist");
    assertEquals(0L, shape.zeroWeightDraws(), "GUARD: a zero-weight macro-cell must never be drawn");
  }

  // -------------------------------------------------------------------------------------
  // 3: determinism under injected RNG at a fixed epoch
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("same seed and epoch reproduce the same route, cell for cell")
  void routeIsReproducibleUnderInjectedRng() {
    TwoTierCellRouter a = router(4, 8, 48L, TwoTierRouterModelBenchmarkTest::unequalOccupancy);
    TwoTierCellRouter b = router(4, 8, 48L, TwoTierRouterModelBenchmarkTest::unequalOccupancy);
    a.compile();
    b.compile();
    assertEquals(a.epoch(), b.epoch(), "one compile pulse must advance the epoch by exactly one");

    a.setRng(new Random(20260905L));
    b.setRng(new Random(20260905L));
    for (int i = 0; i < 2048; i++) {
      assertArrayEquals(a.selectCell(), b.selectCell(), "route diverged at draw " + i);
    }
    REPORT.add("determinism", "r=4 M=8", "identical routes", "2048 / 2048", Provenance.MEASURED);
    REPORT.note(
        "Determinism is scoped to the test-only setRng injection: MemoryShape.rng() otherwise "
            + "falls back to ThreadLocalRandom, which is not seedable. The epoch/inclusion "
            + "decision is reproducible in production because it depends on the seed and epoch "
            + "only.");
  }

  // -------------------------------------------------------------------------------------
  // 4: the PI_MIN starvation bound
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("PI_MIN floor: every non-empty macro-cell is included at least once per 64 epochs")
  void piMinStarvationBound() {
    final long radius = 64L;
    TwoTierCellRouter shape = router(4, 8, radius, TwoTierRouterModelBenchmarkTest::unequalOccupancy);
    // Force subsampling on every macro-cell by setting the budget below any achievable footprint.
    shape.setResidentBudgetBytes(0L);

    TwoTierCellRouter.Snapshot first = shape.compile();
    int nonEmpty = first.macroCount() + first.excludedMacroCells();
    assertTrue(nonEmpty > 0, "the domain must hold non-empty macro-cells");

    Map<Long, Integer> lastSeen = new HashMap<>();
    Map<Long, Long> maxGap = new HashMap<>();
    final int epochs = 4096;
    long includedTotal = 0L;
    for (int e = 0; e < epochs; e++) {
      TwoTierCellRouter.Snapshot s = shape.compile();
      includedTotal += s.macroCount();
      for (long key : s.macroKeys) {
        Integer prev = lastSeen.put(key, e);
        if (prev != null) maxGap.merge(key, (long) (e - prev), Math::max);
      }
    }

    long worstGap = maxGap.values().stream().mapToLong(Long::longValue).max().orElse(0L);
    double meanIncluded = (double) includedTotal / epochs;

    String section = "coverage (subsampled)";
    REPORT.add(section, "pi = PI_MIN everywhere", "non-empty macro-cells", Integer.toString(nonEmpty), Provenance.MEASURED);
    REPORT.add(section, "pi = PI_MIN everywhere", "epochs", Integer.toString(epochs), Provenance.MEASURED);
    REPORT.add(section, "pi = PI_MIN everywhere", "macro-cells seen at least once", Integer.toString(lastSeen.size()), Provenance.MEASURED);
    REPORT.add(section, "pi = PI_MIN everywhere", "worst inclusion gap (epochs)", Long.toString(worstGap), Provenance.MEASURED);
    REPORT.add(section, "pi = PI_MIN everywhere", "mean included macro-cells per epoch", meanIncluded, Provenance.MEASURED);
    REPORT.add(section, "pi = PI_MIN everywhere", "expected mean (nonEmpty / 64)", nonEmpty / 64.0, Provenance.DERIVED);

    assertEquals(
        nonEmpty,
        lastSeen.size(),
        "every non-empty macro-cell must appear; a missing one is unbounded starvation");
    assertTrue(
        worstGap <= 64L,
        "systematic phase sampling promises exactly one inclusion per 64 epochs; worst gap was "
            + worstGap);
    assertTrue(
        Math.abs(meanIncluded - nonEmpty / 64.0) < 1.0,
        "inclusion rate must sit on 1/64, not merely near it; observed " + meanIncluded);
  }

  // -------------------------------------------------------------------------------------
  // 5: unitlessness (mirrors DistanceParameterTest.testSubspaceShapeRemainsUnitless)
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("routing is unitless: spatialResolution and cell units do not enter the route")
  void routingIsUnitless() {
    TwoTierCellRouter a = router(2, 8, 32L, TwoTierRouterModelBenchmarkTest::unequalOccupancy);
    TwoTierCellRouter b = router(2, 8, 32L, TwoTierRouterModelBenchmarkTest::unequalOccupancy);
    a.setSpatialResolution(1L);
    b.setSpatialResolution(64L);
    TwoTierCellRouter.Snapshot sa = a.compile();
    TwoTierCellRouter.Snapshot sb = b.compile();

    assertEquals(sa.macroCount(), sb.macroCount(), "the descriptor must not depend on a unit knob");
    assertEquals(sa.totalWeight(), sb.totalWeight(), "compensated weight must not depend on a unit knob");
    assertEquals(sa.includedGoodCells(), sb.includedGoodCells());

    a.setRng(new Random(5L));
    b.setRng(new Random(5L));
    for (int i = 0; i < 512; i++) assertArrayEquals(a.selectCell(), b.selectCell());

    REPORT.add("unitlessness", "spatialResolution 1 vs 64", "identical routes", "512 / 512", Provenance.MEASURED);
  }

  /**
   * Upper-tail probability of the chi-square distribution, via the Wilson-Hilferty cube-root
   * normal approximation. Accurate well past three digits for the degrees of freedom used here,
   * and it keeps the tier free of a statistics dependency.
   */
  static double chiSquareUpperTail(double chiSquare, int dof) {
    if (dof <= 0) return 1.0;
    double t = Math.cbrt(chiSquare / dof);
    double mean = 1.0 - 2.0 / (9.0 * dof);
    double sd = Math.sqrt(2.0 / (9.0 * dof));
    double zScore = (t - mean) / sd;
    return 0.5 * erfc(zScore / Math.sqrt(2.0));
  }

  /** Abramowitz and Stegun 7.1.26 complementary error function. */
  private static double erfc(double x) {
    double z = Math.abs(x);
    double t = 1.0 / (1.0 + 0.5 * z);
    double ans =
        t
            * Math.exp(
                -z * z
                    - 1.26551223
                    + t
                        * (1.00002368
                            + t
                                * (0.37409196
                                    + t
                                        * (0.09678418
                                            + t
                                                * (-0.18628806
                                                    + t
                                                        * (0.27886807
                                                            + t
                                                                * (-1.13520398
                                                                    + t
                                                                        * (1.48851587
                                                                            + t
                                                                                * (-0.82215223
                                                                                    + t * 0.17087277)))))))));
    return x >= 0.0 ? ans : 2.0 - ans;
  }
}
