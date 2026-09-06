package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Config;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Plan;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Situation;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
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
 * Measures the cost-based configuration planner against a brute-force optimum, and measures the
 * chosen configuration at ranges up to a 20 km radius.
 *
 * <p>Two decisions make the large-range rows possible without generating terrain:
 *
 * <ul>
 *   <li><b>The domain is a real save repeated outward</b> ({@link TiledOccupancyMask}). Exact block
 *       borders are irrelevant to run counts, blob sizes and reconciliation locality, so tiling a
 *       real footprint measures the same quantities a generated 20 km world would.
 *   <li><b>Precision is chunk-native at scale.</b> The occupancy signal a save carries is one bit
 *       per chunk, so a 16-block inner cell loses nothing. Sub-chunk precision is exercised
 *       separately at small radius, where the mark population is affordable.
 * </ul>
 *
 * <p><b>Regret is the deliverable, not the winner.</b> A heuristic that cannot be shown to land near
 * the measured optimum is a guess with extra steps, so every planned choice is scored against the
 * best configuration actually measured under the same objective.
 */
@Tag("simulation")
@DisplayName("configuration planner regret and scale sweep")
public class HeuristicPlannerBenchmarkTest {

  private static final long SEED = 20260905L;

  /** Outer cells beyond which a measured sweep no longer fits the benchmark heap. */
  private static final long MAX_MEASURED_OUTER_CELLS = 1L << 18;

  /** Staged marks between flushes while populating, so pending buffers stay bounded. */
  private static final int FLUSH_EVERY = 2_000_000;

  private static final int SELECT_ITERATIONS = 20_000;

  private static final int RECONCILE_SAMPLE = 1_500;

  private static final SimulationReport REPORT = new SimulationReport();

  /** Edge, in blocks, of the finest distinction the occupancy signal can make: one chunk. */
  private static final int SIGNAL_RESOLUTION = 16;

  private static TiledOccupancyMask mask;
  private static double badDensity;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    if (!WorldOccupancyMask.available()) return;
    Path dir = WorldOccupancyMask.resolveDirectory();
    WorldOccupancyMask source = WorldOccupancyMask.load(dir);
    mask = new TiledOccupancyMask(source);
    badDensity = 1.0d - mask.occupiedFraction();
    REPORT.add(
        "world",
        "tiled save",
        "source region files",
        String.valueOf(source.regionFileCount()),
        Provenance.MEASURED);
    REPORT.add(
        "world",
        "tiled save",
        "tile period (blocks)",
        mask.periodBlocks()[0] + "x" + mask.periodBlocks()[1],
        Provenance.DERIVED);
    REPORT.add("world", "tiled save", "bad density", badDensity, Provenance.MEASURED);
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "The domain is a real save's region footprint repeated outward with alternate tiles "
            + "mirrored, so large-range rows are measured rather than extrapolated. Periodicity "
            + "is the stated limitation: no bad feature larger than one tile can exist, which "
            + "bounds run lengths from above and makes these run counts conservative.");
    REPORT.note(
        "Precision at scale is one inner cell per chunk, which is the native resolution of the "
            + "occupancy signal itself. Finer inner cells are measured at small radius only, "
            + "because staging one mark per block is a vehicle limitation and not a property of "
            + "the model.");
    REPORT.note(
        "Regret is measured against the same objective the planner optimizes, evaluated on "
            + "measured select, reconciliation, footprint and over-exclusion terms. A low regret "
            + "therefore says the fitted coefficients rank configurations correctly, not that the "
            + "objective's weights are correct - the precision weight in particular is a policy "
            + "choice and is stated as one.");
    REPORT.note(
        "Storage latency enters the objective as a term that can only raise residency, never "
            + "lower it, so an operator on spinning rust is never asked to trade RAM away first. "
            + "At the measured operating point, however, the device class does not change the "
            + "choice: serialized blob bytes are close to invariant in outer edge, because runs "
            + "per blob grow with a blob's area at the same rate the blob count falls. The "
            + "latency term therefore has little to act on here, and claiming device adaptivity "
            + "as a demonstrated benefit would overstate the evidence.");
    REPORT.write("planner-regret-scale");
  }

  // -------------------------------------------------------------------------------------
  // measurement helpers
  // -------------------------------------------------------------------------------------

  /** One configuration's measured coefficients. */
  private record Measured(
      Config config,
      long directoryBytes,
      long blobBytes,
      int runs,
      int occupiedBlobs,
      int deadOuterCells,
      long badInnerCells,
      double excludedFraction,
      double selectNanos,
      double reconcileNanosPerMark) {}

  /**
   * Marks every inner cell whose chunk is absent from the tiled footprint, flushing periodically so
   * staged buffers stay bounded at large radius.
   */
  private static void populate(LayeredHilbertIndex index, int radius, int innerRes) {
    int staged = 0;
    for (int x = -radius; x < radius; x += innerRes) {
      for (int z = -radius; z < radius; z += innerRes) {
        if (mask.isOccupied(x >> 4, z >> 4)) continue;
        index.addBadLocation(x, z);
        if (++staged >= FLUSH_EVERY) {
          index.flush();
          staged = 0;
        }
      }
    }
    index.flush();
  }

  private static double selectNanos(LayeredHilbertIndex index, int iterations) {
    for (int i = 0; i < iterations / 4; i++) {
      index.rand();
    }
    long start = System.nanoTime();
    long sink = 0L;
    for (int i = 0; i < iterations; i++) {
      sink ^= index.rand();
    }
    long elapsed = System.nanoTime() - start;
    if (sink == Long.MAX_VALUE) throw new IllegalStateException("unreachable");
    return elapsed / (double) iterations;
  }

  /** Reconciliation cost per mark: one mark staged and flushed at a time, as a live mark would be. */
  private static double reconcileNanosPerMark(
      LayeredHilbertIndex index, int radius, int innerRes, int samples) {
    Random rng = new Random(SEED ^ radius ^ innerRes);
    int span = Math.max(1, 2 * radius - 1);
    long elapsed = 0L;
    int applied = 0;
    for (int i = 0; i < samples; i++) {
      int x = rng.nextInt(span) - radius;
      int z = rng.nextInt(span) - radius;
      long start = System.nanoTime();
      if (index.addBadLocation(x, z)) {
        index.flush();
        applied++;
      }
      elapsed += System.nanoTime() - start;
    }
    return applied == 0 ? Double.MAX_VALUE : elapsed / (double) applied;
  }

  private static Measured measure(int radius, Config c) {
    LayeredHilbertIndex index = new LayeredHilbertIndex(radius, c.outerEdge(), c.innerRes(), SEED);
    populate(index, radius, c.innerRes());
    long bad = index.totalBad();
    double excluded = bad * (double) c.innerRes() * c.innerRes() / (4.0d * radius * radius);
    double select = index.totalGood() > 0L ? selectNanos(index, SELECT_ITERATIONS) : Double.MAX_VALUE;
    double reconcile = reconcileNanosPerMark(index, radius, c.innerRes(), RECONCILE_SAMPLE);
    return new Measured(
        c,
        index.residentDirectoryBytes(),
        index.blobBytes(),
        index.totalRuns(),
        index.occupiedBlobs(),
        index.deadOuterCells(),
        bad,
        excluded,
        select,
        reconcile);
  }

  /**
   * Configurations the vehicle can actually build and hold at this radius. The vehicle needs the
   * domain to divide evenly and the outer grid to fit the benchmark heap; the planner does not, so
   * a regret comparison has to be held to this narrower set.
   */
  private static List<Config> measurableConfigs(int radius, int minInnerRes) {
    List<Config> out = new ArrayList<>();
    for (Config c : IndexConfigPlanner.feasible(situation(radius, 0.0d, 1L << 40, 0d))) {
      if (c.innerRes() < minInnerRes) continue;
      if ((2L * radius) % c.outerEdge() != 0L) continue;
      long cellsEdge = (2L * radius) / c.outerEdge();
      if (cellsEdge < 2L || cellsEdge % 2L != 0L) continue;
      if (IndexConfigPlanner.outerCells(radius, c) > MAX_MEASURED_OUTER_CELLS) continue;
      out.add(c);
    }
    return out;
  }

  private static Situation situation(
      int radius, double marksPerCandidate, long headroom, double storageNanos) {
    return new Situation(
        radius, badDensity, marksPerCandidate, headroom, storageNanos, 16, SIGNAL_RESOLUTION);
  }

  private static double realized(Situation s, Measured m, long budget, double baselineExcluded) {
    return IndexConfigPlanner.realizedCost(
        s,
        m.config(),
        budget,
        m.selectNanos(),
        m.reconcileNanosPerMark(),
        m.directoryBytes(),
        m.blobBytes(),
        m.excludedFraction() - baselineExcluded);
  }

  /**
   * Bad density of the origin-centred domain, sampled rather than taken from the tile average: the
   * save is dense near spawn and sparse at its edges, so a tile-wide figure would misstate the
   * density the index at a given radius actually sees.
   */
  private static double sampleBadDensity(int radius) {
    Random rng = new Random(SEED ^ radius);
    int samples = 200_000;
    int bad = 0;
    for (int i = 0; i < samples; i++) {
      int x = rng.nextInt(2 * radius) - radius;
      int z = rng.nextInt(2 * radius) - radius;
      if (!mask.isOccupied(x >> 4, z >> 4)) bad++;
    }
    return bad / (double) samples;
  }

  // -------------------------------------------------------------------------------------
  // 1. regret against the measured optimum
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("planner lands within a stated regret of the measured optimum across devices and budgets")
  public void plannerRegretAgainstMeasuredOptimum() {
    Assumptions.assumeTrue(mask != null, "no save in testdata-world; skipping");

    int radius = 1024;
    badDensity = sampleBadDensity(radius);
    REPORT.add("regret", "r=" + radius, "sampled bad density", badDensity, Provenance.MEASURED);
    List<Config> configs = measurableConfigs(radius, 1);
    List<Measured> table = new ArrayList<>();
    double baselineExcluded = 1.0d;
    for (Config c : configs) {
      Measured m = measure(radius, c);
      table.add(m);
      baselineExcluded = Math.min(baselineExcluded, m.excludedFraction());
      REPORT.add("calibration r=" + radius, m.config().toString(), "runs", String.valueOf(m.runs()), Provenance.MEASURED);
      REPORT.add(
          "calibration r=" + radius,
          m.config().toString(),
          "resident directory bytes",
          String.valueOf(m.directoryBytes()),
          Provenance.MEASURED);
      REPORT.add(
          "calibration r=" + radius,
          m.config().toString(),
          "blob bytes (pageable)",
          String.valueOf(m.blobBytes()),
          Provenance.MEASURED);
      REPORT.add(
          "calibration r=" + radius, m.config().toString(), "select ns/op", m.selectNanos(), Provenance.MEASURED);
      REPORT.add(
          "calibration r=" + radius,
          m.config().toString(),
          "reconcile ns/mark",
          m.reconcileNanosPerMark(),
          Provenance.MEASURED);
      REPORT.add(
          "calibration r=" + radius,
          m.config().toString(),
          "domain fraction excluded",
          m.excludedFraction(),
          Provenance.DERIVED);
    }

    double[] devices = {
      IndexConfigPlanner.NVME_OP_NS, IndexConfigPlanner.SSD_OP_NS, IndexConfigPlanner.HDD_OP_NS
    };
    String[] deviceNames = {"nvme", "ssd", "hdd"};
    long[] headrooms = {16L << 20, 512L << 20, 8L << 30};
    String[] headroomNames = {"16MB free", "512MB free", "8GB free"};
    double[] loads = {0.01d, 1.0d};

    double worstRegret = 0.0d;
    for (int d = 0; d < devices.length; d++) {
      for (int h = 0; h < headrooms.length; h++) {
        for (double load : loads) {
          Situation s = situation(radius, load, headrooms[h], devices[d]);
          Plan plan = IndexConfigPlanner.planAmong(s, configs);
          long budget = IndexConfigPlanner.budgetBytes(s);

          Measured chosen = null;
          Measured best = null;
          double bestCost = Double.MAX_VALUE;
          for (Measured m : table) {
            if (m.directoryBytes() > budget) continue;
            double cost = realized(s, m, budget, baselineExcluded);
            if (cost < bestCost) {
              bestCost = cost;
              best = m;
            }
            if (m.config().equals(plan.config())) chosen = m;
          }
          assertTrue(chosen != null && best != null, "planner chose an unmeasured configuration");

          double chosenCost = realized(s, chosen, budget, baselineExcluded);
          double regret = chosenCost / bestCost - 1.0d;
          worstRegret = Math.max(worstRegret, regret);

          String subject = deviceNames[d] + " / " + headroomNames[h] + " / load " + load;
          REPORT.add("regret", subject, "planner choice", plan.config().toString(), Provenance.MODELED);
          REPORT.add("regret", subject, "measured optimum", best.config().toString(), Provenance.MEASURED);
          REPORT.add("regret", subject, "planner realized ns", chosenCost, Provenance.DERIVED);
          REPORT.add("regret", subject, "optimum realized ns", bestCost, Provenance.DERIVED);
          REPORT.add("regret", subject, "regret", regret, Provenance.DERIVED);
        }
      }
    }

    REPORT.add("regret", "worst case", "regret across all trace points", worstRegret, Provenance.DERIVED);
    assertTrue(
        worstRegret <= 0.35d,
        "planner regret " + worstRegret + " exceeds the 0.35 bound the heuristic is worth having");
  }

  // -------------------------------------------------------------------------------------
  // 2. the chosen configuration, measured out to a 20 km radius
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("planner choice measured at chunk precision from 1k to 20k radius on tiled real data")
  public void plannerChoiceAtScale() {
    Assumptions.assumeTrue(mask != null, "no save in testdata-world; skipping");

    int[] radii = {1024, 2048, 4096, 8192, 20480};
    long headroom = Runtime.getRuntime().maxMemory() - usedHeap();
    double worstRegret = 0.0d;

    for (int radius : radii) {
      badDensity = sampleBadDensity(radius);
      REPORT.add("scale r=" + radius, "domain", "sampled bad density", badDensity, Provenance.MEASURED);
      List<Config> configs = measurableConfigs(radius, 16);
      Situation s = situation(radius, 0.1d, headroom, IndexConfigPlanner.SSD_OP_NS);
      Plan plan = IndexConfigPlanner.planAmong(s, configs);
      long budget = IndexConfigPlanner.budgetBytes(s);

      Measured chosen = null;
      Measured best = null;
      double bestCost = Double.MAX_VALUE;
      for (Config c : configs) {
        Measured m = measure(radius, c);
        REPORT.add("scale r=" + radius, m.config().toString(), "outer cells", String.valueOf(IndexConfigPlanner.outerCells(radius, c)), Provenance.DERIVED);
        REPORT.add("scale r=" + radius, m.config().toString(), "runs", String.valueOf(m.runs()), Provenance.MEASURED);
        REPORT.add("scale r=" + radius, m.config().toString(), "resident directory bytes", String.valueOf(m.directoryBytes()), Provenance.MEASURED);
        REPORT.add("scale r=" + radius, m.config().toString(), "blob bytes (pageable)", String.valueOf(m.blobBytes()), Provenance.MEASURED);
        REPORT.add("scale r=" + radius, m.config().toString(), "dead outer cells (never read)", String.valueOf(m.deadOuterCells()), Provenance.MEASURED);
        REPORT.add("scale r=" + radius, m.config().toString(), "select ns/op", m.selectNanos(), Provenance.MEASURED);
        REPORT.add("scale r=" + radius, m.config().toString(), "reconcile ns/mark", m.reconcileNanosPerMark(), Provenance.MEASURED);
        REPORT.add("scale r=" + radius, m.config().toString(), "runs per occupied blob", m.runs() / (double) Math.max(1, m.occupiedBlobs()), Provenance.DERIVED);
        double cost = realized(s, m, budget, 0.0d);
        REPORT.add("scale r=" + radius, m.config().toString(), "realized ns/candidate", cost, Provenance.DERIVED);
        if (cost < bestCost) {
          bestCost = cost;
          best = m;
        }
        if (c.equals(plan.config())) chosen = m;
      }
      assertTrue(chosen != null && best != null, "planner chose an unmeasured configuration");

      double regret = realized(s, chosen, budget, 0.0d) / bestCost - 1.0d;
      worstRegret = Math.max(worstRegret, regret);
      REPORT.add("scale r=" + radius, "planner", "resident budget bytes", String.valueOf(budget), Provenance.DERIVED);
      REPORT.add("scale r=" + radius, "planner", "choice", plan.config().toString(), Provenance.MODELED);
      REPORT.add("scale r=" + radius, "planner", "rationale", plan.rationale(), Provenance.MODELED);
      REPORT.add("scale r=" + radius, "planner", "measured optimum", best.config().toString(), Provenance.MEASURED);
      REPORT.add("scale r=" + radius, "planner", "regret", regret, Provenance.DERIVED);
      REPORT.add(
          "scale r=" + radius,
          "planner",
          "resident fraction of total state",
          chosen.directoryBytes() / (double) (chosen.directoryBytes() + chosen.blobBytes()),
          Provenance.DERIVED);
      REPORT.add(
          "scale r=" + radius,
          "planner",
          "expand step / radius",
          plan.config().outerEdge() / (double) radius,
          Provenance.DERIVED);
    }

    assertTrue(worstRegret <= 0.35d, "planner regret at scale " + worstRegret + " exceeds 0.35");
  }

  private static long usedHeap() {
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }

  // -------------------------------------------------------------------------------------
  // 3. the heuristic's decision shape, independent of world data
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("granularity is chosen as a ratio, so the same edge is rejected small and accepted large")
  public void granularityIsScaleRelative() {
    long headroom = 2L << 30;
    badDensity = 0.3d;
    Plan small = IndexConfigPlanner.plan(situation(1024, 0.1d, headroom, IndexConfigPlanner.SSD_OP_NS));
    Plan large = IndexConfigPlanner.plan(situation(100_000, 0.1d, headroom, IndexConfigPlanner.SSD_OP_NS));

    assertTrue(
        small.config().outerEdge() / 1024.0d <= IndexConfigPlanner.MAX_EXPAND_RATIO,
        "a 1 km radius must not be given a coarse expansion step");
    assertTrue(
        large.config().outerEdge() > small.config().outerEdge(),
        "a 100 km radius should tolerate a coarser outer cell than a 1 km one");

    REPORT.add("granularity rule", "r=1024", "chosen outer edge", String.valueOf(small.config().outerEdge()), Provenance.MODELED);
    REPORT.add("granularity rule", "r=1024", "expand step / radius", small.config().outerEdge() / 1024.0d, Provenance.DERIVED);
    REPORT.add("granularity rule", "r=100000", "chosen outer edge", String.valueOf(large.config().outerEdge()), Provenance.MODELED);
    REPORT.add("granularity rule", "r=100000", "expand step / radius", large.config().outerEdge() / 100_000.0d, Provenance.DERIVED);
  }

  @Test
  @DisplayName("storage latency can only raise residency, never trade it away")
  public void slowStorageBuysResidency() {
    int radius = 8192;
    // Deliberately tight, so the paging term actually binds; at a roomy headroom every candidate
    // is fully resident and the device class cannot express a preference at all.
    long headroom = 32L << 20;
    double previousMiss = -1.0d;
    for (double[] device :
        new double[][] {
          {IndexConfigPlanner.NVME_OP_NS, 0}, {IndexConfigPlanner.SSD_OP_NS, 1}, {IndexConfigPlanner.HDD_OP_NS, 2}
        }) {
      badDensity = 0.3d;
      Situation s = situation(radius, 0.1d, headroom, device[0]);
      Plan plan = IndexConfigPlanner.plan(s);
      long budget = IndexConfigPlanner.budgetBytes(s);
      double blobs = IndexConfigPlanner.blobBytes(s, plan.config());
      double forBlobs = Math.max(0.0d, budget - plan.modeledResidentBytes());
      double miss = blobs <= forBlobs ? 0.0d : 1.0d - forBlobs / blobs;
      String name = device[1] == 0 ? "nvme" : device[1] == 1 ? "ssd" : "hdd";
      REPORT.add("device response", name, "chosen config", plan.config().toString(), Provenance.MODELED);
      REPORT.add("device response", name, "modeled miss fraction", miss, Provenance.MODELED);
      REPORT.add("device response", name, "modeled cost ns/candidate", plan.modeledCostNanos(), Provenance.MODELED);
      REPORT.add("device response", name, "resident directory bytes", String.valueOf(plan.modeledResidentBytes()), Provenance.MODELED);
      if (previousMiss >= 0.0d) {
        assertTrue(miss <= previousMiss + 1e-9d, "a slower device must not increase the miss fraction");
      }
      previousMiss = miss;
    }
  }

  @Test
  @DisplayName("hysteresis suppresses a switch that only saves nanoseconds")
  public void hysteresisSuppressesThrash() {
    assertFalse(
        IndexConfigPlanner.shouldSwitch(1000.0d, 950.0d, 0.20d),
        "a 5% improvement must not trigger a full descriptor rebuild");
    assertTrue(
        IndexConfigPlanner.shouldSwitch(1000.0d, 700.0d, 0.20d),
        "a 30% improvement is worth one rebuild");
    REPORT.add("hysteresis", "margin 0.20", "switch at 5% gain", "false", Provenance.MODELED);
    REPORT.add("hysteresis", "margin 0.20", "switch at 30% gain", "true", Provenance.MODELED);
  }

  @Test
  @DisplayName("a tighter heap forces a coarser directory rather than a failure")
  public void tightHeapDegradesGracefully() {
    int radius = 20480;
    badDensity = 0.3d;
    Plan roomy = IndexConfigPlanner.plan(situation(radius, 0.1d, 8L << 30, IndexConfigPlanner.SSD_OP_NS));
    Plan tight = IndexConfigPlanner.plan(situation(radius, 0.1d, 64L << 20, IndexConfigPlanner.SSD_OP_NS));

    assertTrue(
        tight.config().outerEdge() >= roomy.config().outerEdge(),
        "less headroom must not produce a larger directory");
    assertTrue(
        tight.modeledResidentBytes() <= tight.budgetBytes(),
        "the chosen directory must fit the derived budget");
    assertEquals(radius, 20480, "radius under test");

    REPORT.add("heap response", "8GB free", "chosen config", roomy.config().toString(), Provenance.MODELED);
    REPORT.add("heap response", "8GB free", "resident bytes", String.valueOf(roomy.modeledResidentBytes()), Provenance.MODELED);
    REPORT.add("heap response", "64MB free", "chosen config", tight.config().toString(), Provenance.MODELED);
    REPORT.add("heap response", "64MB free", "resident bytes", String.valueOf(tight.modeledResidentBytes()), Provenance.MODELED);
    REPORT.add("heap response", "64MB free", "rationale", tight.rationale(), Provenance.MODELED);
  }
}
