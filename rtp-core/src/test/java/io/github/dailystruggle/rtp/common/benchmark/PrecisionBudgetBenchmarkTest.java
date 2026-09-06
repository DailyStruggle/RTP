package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Locates the point between the two known-good precision costs - 1.043x from region rejection and
 * 1.2x as the cap - at which coarsening the shipped spiral stops paying, by charting the time and
 * memory curves against measured over-exclusion and taking their crossing.
 *
 * <p>Why a crossing rather than an argmin. Time and memory both improve as the cell coarsens, and
 * they improve at different rates: memory falls roughly with run count while selection falls only
 * with the logarithm of it plus a cache effect. Normalised against the full-precision point, the
 * two curves therefore cross, and the crossing is the coarsest unit at which the marginal byte
 * saved is still worth as much as the marginal nanosecond - which is the pick a heuristic needs and
 * is not visible in either curve alone.
 *
 * <p>Both levers are charted, because they overlap rather than compose: a coarse cell removes runs
 * by merging them and region rejection removes runs by discarding them, so applying the second to
 * an already region-aligned grid may earn nothing. That is measured here rather than assumed.
 *
 * <p><b>Units.</b> Cell edge in chunks; one chunk is full precision because a candidate is placed
 * at the centre of a chunk. Radius in blocks.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("precision budget: where the time and memory curves cross")
public class PrecisionBudgetBenchmarkTest {

  private static final long SEED = 20260906L;

  private static final int CHUNK = 16;

  /** Hard cap on over-exclusion. No candidate above this is admissible at any saving. */
  private static final double EXCLUSION_CAP = 1.2d;

  /** Lower anchor: the cost region rejection was measured to charge. */
  private static final double EXCLUSION_FLOOR = 1.043d;

  private static final int SELECT_ITERATIONS = 20_000;

  private static final int RECONCILE_SAMPLE = 100;

  private static final int[] CELL_CHUNKS = {1, 2, 4, 8, 16, 32};

  /** Radii the rule is fitted on, in blocks. */
  private static final int[] FIT_RADII = {2_048, 8_192, 20_480};

  /** Radii held out of the fit, used only to score the rule. */
  private static final int[] HOLDOUT_RADII = {4_096, 12_288};

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
        "Each curve is reported as an elasticity: the fraction of the full-precision cost a "
            + "coarsening step removes per unit of over-exclusion it adds. An earlier form of this "
            + "harness normalised both curves to their endpoints, which is vacuous - two curves "
            + "forced to run from zero to one over the same interval meet only at its ends. The "
            + "crossing is where elasticity reaches unity, and it is reported as a ratio rather "
            + "than a cell edge because the edge achieving it is range-dependent.");
    REPORT.note(
        "The cap is applied before the crossing is sought, not after. A crossing found outside the "
            + "budget is not a choice the planner may make, so admitting it and then clamping "
            + "would report a pick point that is never used.");
    REPORT.note(
        "Holdout radii are measured but take no part in deriving the rule. Regret is the realised "
            + "cost of the rule's pick over the realised cost of the best admissible cell edge at "
            + "that radius, so a value of zero means the rule chose the measured optimum and not "
            + "that the model agreed with itself.");
    REPORT.write("precision-budget");
  }

  // -------------------------------------------------------------------------------------
  // vehicle
  // -------------------------------------------------------------------------------------

  /** One measured cell-edge point on the shipped spiral. */
  private record Row(
      int cellChunks,
      int runs,
      long bytes,
      long excludedChunks,
      double exclusionRatio,
      double selectNanos,
      double reconcileNanos) {}

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

    long badCells = shape.getEffectiveBadCount();
    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    int runs = (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
    long cells = (long) cellRadius * cellRadius * 4L;
    long bytes = runs * (cells < Integer.MAX_VALUE ? 8L : 16L);

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
    return new Row(cellChunks, runs, bytes, excluded, 0.0d, select, reconcile);
  }

  /** Sweeps every cell edge at one radius and fills in the exclusion ratio against 1 chunk. */
  private static List<Row> sweep(int radiusBlocks) {
    List<Row> raw = new ArrayList<>();
    for (int cell : CELL_CHUNKS) {
      if (radiusBlocks / (CHUNK * cell) < 2) continue;
      raw.add(measure(radiusBlocks, cell));
    }
    double base = raw.get(0).excludedChunks();
    List<Row> out = new ArrayList<>();
    for (Row r : raw) {
      double ratio = base == 0.0d ? 1.0d : r.excludedChunks() / base;
      out.add(
          new Row(
              r.cellChunks(),
              r.runs(),
              r.bytes(),
              r.excludedChunks(),
              ratio,
              r.selectNanos(),
              r.reconcileNanos()));
    }
    return out;
  }

  /** Rows inside the precision budget. Always non-empty: full precision costs nothing. */
  private static List<Row> admissible(List<Row> rows) {
    List<Row> out = new ArrayList<>();
    for (Row r : rows) {
      if (r.exclusionRatio() <= EXCLUSION_CAP) out.add(r);
    }
    return out;
  }

  /**
   * Blended time for one row: a selection happens per delivered candidate and a reconciliation
   * happens per mark, so the two are only comparable once weighted by how often each occurs.
   */
  private static double timeNanos(Row r, double marksPerCandidate) {
    return r.selectNanos() + marksPerCandidate * r.reconcileNanos();
  }

  /**
   * Marginal efficiency of each coarsening step: fraction of the full-precision cost removed per
   * unit of over-exclusion added.
   *
   * <p>Normalising both curves to their endpoints - which a previous version of this harness did -
   * makes the comparison vacuous, because two curves forced to run from zero to one over the same
   * interval meet only at the interval's ends. Elasticity is the quantity that actually differs
   * between the two resources and it is scale-free without being endpoint-anchored.
   *
   * @param rows admissible rows, finest first
   * @param cost the resource being spent
   * @return elasticity per step; index {@code i} is the step from row {@code i - 1} to row
   *     {@code i}, and index zero is unused
   */
  private static double[] elasticity(
      List<Row> rows, java.util.function.ToDoubleFunction<Row> cost) {
    double base = cost.applyAsDouble(rows.get(0));
    double[] out = new double[rows.size()];
    for (int i = 1; i < rows.size(); i++) {
      double saved = cost.applyAsDouble(rows.get(i - 1)) - cost.applyAsDouble(rows.get(i));
      double paid = rows.get(i).exclusionRatio() - rows.get(i - 1).exclusionRatio();
      out[i] = (base <= 0.0d || paid <= 0.0d) ? 0.0d : (saved / base) / paid;
    }
    return out;
  }

  /**
   * Over-exclusion ratio at which a resource's elasticity falls to unity - one percent of the
   * resource per one percent of precision. Above it a coarsening step costs more precision than it
   * returns in that resource; below it the step is still paying.
   *
   * @return crossing ratio, or the coarsest admissible ratio when every step still pays
   */
  private static double crossing(List<Row> rows, double[] elasticity) {
    for (int i = 1; i < rows.size(); i++) {
      if (elasticity[i] >= 1.0d) continue;
      double lo = rows.get(i - 1).exclusionRatio();
      if (i == 1) return lo;
      double prev = elasticity[i - 1];
      double t = (prev - 1.0d) / Math.max(1e-12d, prev - elasticity[i]);
      double hi = rows.get(i).exclusionRatio();
      return Math.min(hi, lo + t * (hi - lo));
    }
    return rows.get(rows.size() - 1).exclusionRatio();
  }

  // -------------------------------------------------------------------------------------
  // measurements
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("curves: time and memory against measured over-exclusion, capped at 1.2x")
  public void curvesUnderCap() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int radius : FIT_RADII) {
      List<Row> rows = sweep(radius);
      List<Row> ok = admissible(rows);
      double[] memoryElasticity = elasticity(ok, r -> r.bytes());
      double[] timeElasticity = elasticity(ok, r -> timeNanos(r, 0.01d));

      for (int i = 0; i < ok.size(); i++) {
        Row r = ok.get(i);
        String subject = "r=" + radius + " cell=" + r.cellChunks() + "ch";
        REPORT.add("curve", subject, "over-exclusion", r.exclusionRatio(), Provenance.MEASURED);
        REPORT.add("curve", subject, "runs", r.runs(), Provenance.MEASURED);
        REPORT.add("curve", subject, "bytes at derived width", r.bytes(), Provenance.DERIVED);
        REPORT.add("curve", subject, "select ns/op", r.selectNanos(), Provenance.MEASURED);
        REPORT.add("curve", subject, "reconcile ns/mark", r.reconcileNanos(), Provenance.MEASURED);
        REPORT.add(
            "curve", subject, "memory elasticity of step", memoryElasticity[i], Provenance.DERIVED);
        REPORT.add(
            "curve", subject, "time elasticity of step", timeElasticity[i], Provenance.DERIVED);
      }

      double memoryCross = crossing(ok, memoryElasticity);
      double timeCross = crossing(ok, timeElasticity);
      String subject = "r=" + radius;
      REPORT.add("crossing", subject, "memory crossing", memoryCross, Provenance.DERIVED);
      REPORT.add("crossing", subject, "time crossing", timeCross, Provenance.DERIVED);
      REPORT.add(
          "crossing",
          subject,
          "coarsest admissible cell, chunks",
          ok.get(ok.size() - 1).cellChunks(),
          Provenance.DERIVED);

      // The cap is the point of the exercise: a row outside the precision budget must never be
      // charted as a candidate, however much it saves.
      for (Row r : ok) {
        assertTrue(
            r.exclusionRatio() <= EXCLUSION_CAP,
            "an inadmissible row entered the curve at r=" + radius);
      }
      assertTrue(
          memoryCross >= 1.0d && memoryCross <= EXCLUSION_CAP,
          "memory crossing left the budget at r=" + radius + ": " + memoryCross);
      assertTrue(
          timeCross >= 1.0d && timeCross <= EXCLUSION_CAP,
          "time crossing left the budget at r=" + radius + ": " + timeCross);
    }
  }

  /**
   * Init-time pick. The two crossings bracket the choice - memory says coarsen further, time says
   * stop earlier, or the reverse - and the weight between them is the scarcity of heap rather than
   * an operator setting: with little headroom the memory crossing dominates, with plenty the time
   * crossing does. The result is clamped into the band the two measured levers already justify.
   */
  private static double pickRatio(double memoryCross, double timeCross, double headroomFraction) {
    double w = Math.min(1.0d, Math.max(0.0d, 1.0d - headroomFraction));
    double blended = w * memoryCross + (1.0d - w) * timeCross;
    return Math.min(EXCLUSION_CAP, Math.max(EXCLUSION_FLOOR, blended));
  }

  /** Coarsest admissible cell edge whose measured over-exclusion is within the picked ratio. */
  private static Row pickRow(List<Row> ok, double ratio) {
    Row chosen = ok.get(0);
    for (Row r : ok) {
      if (r.exclusionRatio() <= ratio) chosen = r;
    }
    return chosen;
  }

  /** Realised cost of a row: time plus heap pressure, in the same nanosecond unit. */
  private static double realisedCost(Row r, double marksPerCandidate, long budgetBytes) {
    double time = timeNanos(r, marksPerCandidate);
    double pressure = budgetBytes <= 0L ? 0.0d : 1_000.0d * r.bytes() / budgetBytes;
    return time + pressure;
  }

  @Test
  @DisplayName("holdout: the derived rule scored on radii it was not fitted on")
  public void holdoutRegret() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    // Rule derived from the fit radii only.
    double memorySum = 0.0d;
    double timeSum = 0.0d;
    for (int radius : FIT_RADII) {
      List<Row> ok = admissible(sweep(radius));
      memorySum += crossing(ok, elasticity(ok, r -> r.bytes()));
      timeSum += crossing(ok, elasticity(ok, r -> timeNanos(r, 0.01d)));
    }
    double memoryCross = memorySum / FIT_RADII.length;
    double timeCross = timeSum / FIT_RADII.length;
    REPORT.add("rule", "fitted", "memory crossing, mean", memoryCross, Provenance.DERIVED);
    REPORT.add("rule", "fitted", "time crossing, mean", timeCross, Provenance.DERIVED);

    double worstRegret = 0.0d;
    double worstCapRegret = 0.0d;
    for (int radius : HOLDOUT_RADII) {
      List<Row> ok = admissible(sweep(radius));
      for (double headroom : new double[] {0.05d, 0.5d, 0.95d}) {
        for (double load : new double[] {0.001d, 0.01d, 0.1d}) {
          long budget = (long) (headroom * 4L * 1024L * 1024L);
          double ratio = pickRatio(memoryCross, timeCross, headroom);
          Row picked = pickRow(ok, ratio);
          double pickedCost = realisedCost(picked, load, budget);

          Row best = ok.get(0);
          double bestCost = Double.MAX_VALUE;
          for (Row r : ok) {
            double c = realisedCost(r, load, budget);
            if (c < bestCost) {
              bestCost = c;
              best = r;
            }
          }
          double regret = bestCost <= 0.0d ? 0.0d : pickedCost / bestCost - 1.0d;
          worstRegret = Math.max(worstRegret, regret);

          // The rival rule: ignore the crossings and simply take the coarsest cell the precision
          // budget admits.
          Row capPick = ok.get(ok.size() - 1);
          double capRegret =
              bestCost <= 0.0d ? 0.0d : realisedCost(capPick, load, budget) / bestCost - 1.0d;
          worstCapRegret = Math.max(worstCapRegret, capRegret);

          String subject = "r=" + radius + " free=" + headroom + " load=" + load;
          REPORT.add("holdout", subject, "picked ratio", ratio, Provenance.DERIVED);
          REPORT.add(
              "holdout", subject, "picked cell, chunks", picked.cellChunks(), Provenance.DERIVED);
          REPORT.add(
              "holdout", subject, "optimal cell, chunks", best.cellChunks(), Provenance.MEASURED);
          REPORT.add("holdout", subject, "regret, elasticity rule", regret, Provenance.DERIVED);
          REPORT.add("holdout", subject, "regret, cap rule", capRegret, Provenance.DERIVED);
        }
      }
    }
    REPORT.add(
        "holdout", "worst case", "regret, elasticity rule", worstRegret, Provenance.DERIVED);
    REPORT.add("holdout", "worst case", "regret, cap rule", worstCapRegret, Provenance.DERIVED);

    // The load-bearing result, and it is not the one the elasticity rule was built for: within a
    // 1.2x precision budget both resources fall monotonically with cell edge, so the optimum is
    // always the coarsest admissible cell and the budget alone decides. A rule that stops short of
    // it is paying for a trade that does not exist at this precision, and its regret is reported
    // rather than hidden.
    //
    // Asserted as a comparison between the two rules rather than as an absolute bound on one of
    // them. The claim is that the cap alone is at least as good as the elasticity rule, and that is
    // a property of the ordering; an absolute bound is not, because realised cost includes timed
    // measurements and under contention from sibling suites a coarser cell can occasionally time
    // slower than a finer one, moving the argmin by one step. A fixed threshold made this pass in
    // isolation and fail in the full run, which measures the rig and not the rule.
    assertTrue(
        worstCapRegret <= worstRegret + 1e-9d,
        "the elasticity rule beat the precision cap, so an interior trade does exist: cap "
            + worstCapRegret
            + " vs elasticity "
            + worstRegret);
  }

  @Test
  @DisplayName("dual lever: region rejection applied to an already coarse grid")
  public void dualLeverOverlap() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radiusChunks = 512;
    for (int outer : new int[] {8, 32}) {
      for (double threshold : new double[] {1.00d, 0.50d}) {
        LayeredHilbertIndex index =
            new LayeredHilbertIndex(radiusChunks, outer, 1, SEED, threshold);
        for (int cx = -radiusChunks; cx < radiusChunks; cx++) {
          for (int cz = -radiusChunks; cz < radiusChunks; cz++) {
            if (!mask.isOccupied(cx, cz)) index.addBadLocation(cx, cz);
          }
        }
        index.flush();
        String subject = "outer=" + outer + "ch t=" + String.format("%.2f", threshold);
        REPORT.add("dual", subject, "runs", index.totalRuns(), Provenance.MEASURED);
        REPORT.add("dual", subject, "collapsed cells", index.collapsedOuterCells(),
            Provenance.MEASURED);
        REPORT.add("dual", subject, "fully resident bytes",
            index.collapsedFullyResidentBytes(), Provenance.DERIVED);
        REPORT.add("dual", subject, "over-excluded chunks", index.overExcludedCells(),
            Provenance.MEASURED);
      }
    }
  }
}
