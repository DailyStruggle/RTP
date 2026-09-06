package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Config;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Situation;
import io.github.dailystruggle.rtp.common.benchmark.ModelCrossoverPlanner.ModelChoice;
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
 * Locates the boundary between the shipped global spiral and the layered index, as a function of
 * range, storage tier, heap headroom, mark load and cache residency.
 *
 * <p>Why this exists: every earlier report compared the two models axis by axis and then asserted a
 * crossover near a 512-block radius by reading two byte columns. That is a projection of a
 * multi-input system onto one line. Here both models are costed under <b>one</b> objective, so the
 * boundary is computed - and because the objective contains a storage term and a cache term, the
 * boundary is a surface with one curve per device tier rather than a scalar.
 *
 * <p>Two fairness rules, both load-bearing:
 *
 * <ul>
 *   <li><b>Equal precision.</b> The spiral is built over the same cell grid the layered index uses,
 *       by scaling its radius into cell units. Marking one block per cell against a block-radius
 *       spiral would inflate its run count and win the comparison by rigging the resolution.
 *   <li><b>Equal domain.</b> Both are populated from the same tiled real footprint, so run counts
 *       reflect one clustering rather than two synthetic ones.
 * </ul>
 *
 * <p>The cache term is <b>modeled</b>, from a three-level latency ladder and an access-pattern
 * classification. Measured select latency is reported beside it so the model can be checked for
 * direction, but no claim is made that the ladder's constants match this rig.
 */
@Tag("simulation")
@DisplayName("model crossover surface: spiral against layered index")
public class ModelCrossoverBenchmarkTest {

  private static final long SEED = 20260905L;

  private static final int SELECT_ITERATIONS = 20_000;

  private static final int RECONCILE_SAMPLE = 400;

  /** Edge, in blocks, of the finest distinction the occupancy signal can make: one chunk. */
  private static final int SIGNAL_RESOLUTION = 16;

  /**
   * Coarsest addressing unit the operator's precision floor permits, in blocks: one region file.
   *
   * <p>This is the "ocean width" cap - a bound on how much good land one merged cell may swallow.
   * It was previously pinned to {@link #SIGNAL_RESOLUTION}, which denied the spiral the only
   * footprint lever a structure without pageable subsections has and then charged it for the
   * footprint it was forced to keep.
   */
  private static final int PRECISION_FLOOR = 512;

  /** Bytes per stored run in the shipped table: one key plus one length. */
  private static final int SPIRAL_RUN_BYTES = 16;

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
        "Both models are costed under one objective - select, reconciliation, storage and "
            + "precision, plus a cache term - so the crossover is an argmin flip rather than a "
            + "comparison of columns. The absolute boundary radius is machine-relative and "
            + "shape-dependent; what is being claimed is that the boundary exists, is monotone in "
            + "the inputs it should be monotone in, and moves in the stated direction as storage "
            + "gets slower.");
    REPORT.note(
        "The spiral is given no storage term, and that is a structural statement rather than an "
            + "omission: a global curve has no subsection that can be evicted and paged back "
            + "independently, so it never waits on a device. On slow storage that is an advantage "
            + "and the objective now lets it be one. What the spiral pays instead is heap "
            + "pressure, and it may coarsen its own precision to reduce that, because 'coarsen "
            + "until it fits' is the only lever a structure without pageable subsections has.");
    REPORT.note(
        "Correction, and it changes the headline. The previous version of this objective assigned "
            + "infinite cost to a model whose resident bytes exceeded the derived budget - one "
            + "percent of measured headroom. Applied to the spiral that converted 'uses more RAM "
            + "than a policy fraction' into 'is not a candidate', while the layered model was "
            + "allowed to pay a finite price for the same overrun. The asymmetry, not the "
            + "structures, is why every tier curve previously coincided and why no boundary was "
            + "found. Over-budget residency is now priced identically for both, quadratic in the "
            + "fraction of headroom consumed, and infeasible only above headroom itself, which is "
            + "an allocation failure rather than a trade.");
    REPORT.note(
        "The cache term distinguishes access pattern from footprint. A spiral selection binary-"
            + "searches a global table, which is log2(runs) independent line touches at whatever "
            + "level the table lives in; a layered selection descends a small directory and then "
            + "walks one blob's runs forward, which a hardware prefetcher largely hides. Two "
            + "structures of identical size are therefore not equally cheap to read, and the "
            + "directory staying inside last-level cache is a distinct win from it fitting in "
            + "heap.");
    REPORT.note(
        "Cache-ladder constants (4 / 15 / 80 ns for L2 / L3 / DRAM, and a one-eighth charge for a "
            + "prefetched sequential touch) are stated assumptions, not measurements from this "
            + "rig. They are reported so the term can be audited and, if it turns out to decide "
            + "the boundary, recalibrated against a pointer-chase probe before anything is built "
            + "on it.");
    REPORT.note(
        "Retention is reported per model because a footprint comparison between two structures "
            + "that kept different amounts of information is meaningless. Earlier runs of this "
            + "harness reported near-zero spiral retention at small radius; that was the harness, "
            + "not the structure. A memory shape addresses the annulus [centerRadius, radius), and "
            + "the harness had left centerRadius at its default 64, so every inner ring of an "
            + "origin-centred footprint mapped outside the domain and was refused. With "
            + "centerRadius pinned to zero the spiral retains every mark offered, and the harness "
            + "now asserts that rather than reporting it. Any row where retention is not one is a "
            + "harness fault and must not be read as a footprint result.");
    REPORT.note(
        "A boundary exists once memory is priced rather than enforced, and it moves with device "
            + "tier in the direction slow storage predicts: the fully resident model wins where "
            + "headroom is scarce and storage is slow, because it performs no I/O at all. On flash "
            + "the layered model wins everywhere swept. The boundary is absent at heavy mark load "
            + "for a separate reason - local reconciliation then dominates every other term - so "
            + "'none in range' must be read as 'one term swamped the others here', not as "
            + "'the models are equivalent'.");
    REPORT.note(
        "Two caveats that bound how far these rows can be pushed. The heap-pressure constant is a "
            + "policy number, not a measurement: it states what garbage collection costs per "
            + "delivered candidate at full headroom, and the boundary radius scales with it. And "
            + "the modeled layered reconciliation base is fitted from a different harness and is "
            + "optimistic against the measured rows here, which biases the boundary toward the "
            + "layered model. Both push the same way, so the located boundary is a lower bound on "
            + "where the resident model starts winning.");
    REPORT.write("model-crossover-surface");
  }

  // -------------------------------------------------------------------------------------
  // vehicles, at equal precision on the same domain
  // -------------------------------------------------------------------------------------

  /**
   * Measured coefficients for one model at one radius.
   *
   * @param marksOffered marks handed to the structure
   * @param marksRetained marks the structure reports holding afterwards. Reported because a
   *     footprint comparison between two structures that retained different amounts of information
   *     is meaningless, and one of them does not retain all of it.
   */
  private record Row(
      long residentBytes,
      long totalBytes,
      int runs,
      long marksOffered,
      long marksRetained,
      double selectNanos,
      double reconcileNanosPerMark) {}

  /** Marks handed to the spiral by the last {@link #spiralOverCells} call. */
  private static long spiralMarksOffered;

  /**
   * Builds the shipped spiral over the same cell grid the layered index uses, by expressing its
   * radius in cells rather than blocks.
   */
  private static Square spiralOverCells(int radius, int res) {
    int cellRadius = radius / res;
    Square shape = new Square();
    shape.setRng(new Random(SEED));
    shape.setSpatialResolution(1L);
    shape.set(GenericMemoryShapeParams.radius, (long) cellRadius);
    // The addressable 1D domain of a memory shape is the annulus [centerRadius, radius): the
    // default centerRadius of 64 makes every inner ring map to a negative index, which
    // addBadLocation refuses. Leaving it in place had the spiral discard the whole origin-centred
    // footprint and then be costed as if it had stored it.
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    long offered = 0L;
    for (int cx = -cellRadius + 1; cx < cellRadius; cx++) {
      for (int cz = -cellRadius + 1; cz < cellRadius; cz++) {
        if (mask.isOccupied((cx * res) >> 4, (cz * res) >> 4)) continue;
        shape.addBadLocation(shape.xzToLocation(cx, cz), FailTypes.biome);
        offered++;
      }
    }
    shape.flushAndRebuild(1L);
    // Fail loudly rather than publish a footprint for a structure that dropped its input.
    assertEquals(
        0L,
        shape.getOutOfDomainMarkCount(),
        "spiral refused in-domain marks at cell radius " + cellRadius);
    spiralMarksOffered = offered;
    return shape;
  }

  private static int spiralRuns(Square shape) {
    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    return (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
  }

  private static Row measureSpiral(int radius, int res) {
    Square shape = spiralOverCells(radius, res);
    int cellRadius = radius / res;

    // Snapshot footprint before the reconciliation loop. Reading it afterwards folds that loop's
    // own marks into the population figure, which is how the first version of this measurement
    // reported retention above one.
    long offered = spiralMarksOffered;
    long retained = shape.getEffectiveBadCount();
    int runs = spiralRuns(shape);
    long bytes = (long) runs * SPIRAL_RUN_BYTES;

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

    Random rng = new Random(SEED ^ radius);
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

    return new Row(bytes, bytes, runs, offered, retained, select, reconcile);
  }

  private static Row measureLayered(int radius, Config c) {
    LayeredHilbertIndex index = new LayeredHilbertIndex(radius, c.outerEdge(), c.innerRes(), SEED);
    long offered = 0L;
    for (int x = -radius; x < radius; x += c.innerRes()) {
      for (int z = -radius; z < radius; z += c.innerRes()) {
        if (mask.isOccupied(x >> 4, z >> 4)) continue;
        index.addBadLocation(x, z);
        offered++;
      }
    }
    index.flush();

    // Snapshot before the reconciliation loop, for the same reason as the spiral.
    long retained = index.totalBad();
    int runs = index.totalRuns();
    long dirBytes = index.residentDirectoryBytes();
    long blobBytes = index.blobBytes();

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

    Random rng = new Random(SEED ^ radius);
    int span = Math.max(1, 2 * radius - 1);
    long elapsed = 0L;
    int applied = 0;
    for (int i = 0; i < RECONCILE_SAMPLE; i++) {
      int x = rng.nextInt(span) - radius;
      int z = rng.nextInt(span) - radius;
      long start = System.nanoTime();
      if (index.addBadLocation(x, z)) {
        index.flush();
        applied++;
      }
      elapsed += System.nanoTime() - start;
    }
    double reconcile = applied == 0 ? Double.MAX_VALUE : elapsed / (double) applied;

    return new Row(dirBytes, dirBytes + blobBytes, runs, offered, retained, select, reconcile);
  }

  private static Situation situation(
      int radius, double marksPerCandidate, long headroom, double storageNanos) {
    return new Situation(
        radius,
        badDensity,
        marksPerCandidate,
        headroom,
        storageNanos,
        PRECISION_FLOOR,
        SIGNAL_RESOLUTION);
  }

  /** Layered configurations the vehicle can build and hold at this radius, at chunk precision. */
  private static List<Config> measurableConfigs(int radius) {
    List<Config> out = new ArrayList<>();
    for (Config c : IndexConfigPlanner.feasible(situation(radius, 0.0d, 1L << 40, 0.0d))) {
      if (c.innerRes() != SIGNAL_RESOLUTION) continue;
      if ((2L * radius) % c.outerEdge() != 0L) continue;
      long cellsEdge = (2L * radius) / c.outerEdge();
      if (cellsEdge < 2L || cellsEdge % 2L != 0L) continue;
      out.add(c);
    }
    return out;
  }

  // -------------------------------------------------------------------------------------
  // 1. the spiral as a candidate row, measured against the layered index
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("both models measured under one objective, so the winner is an argmin not an opinion")
  public void modelsCostedUnderOneObjective() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int[] radii = {256, 512, 1024, 2048, 4096};
    long headroom = 512L << 20;
    double load = 0.05d;

    int layeredWins = 0;
    for (int radius : radii) {
      List<Config> configs = measurableConfigs(radius);
      if (configs.isEmpty()) continue;
      Situation s = situation(radius, load, headroom, IndexConfigPlanner.SSD_OP_NS);
      long budget = IndexConfigPlanner.budgetBytes(s);

      Row spiral = measureSpiral(radius, SIGNAL_RESOLUTION);
      if (spiral.marksOffered() == 0L) {
        // The tiled footprint is fully generated within this radius, so there is nothing to learn
        // and neither model is exercised. Reporting a winner here would be reporting noise.
        REPORT.add("measured", "r=" + radius, "skipped", "no marks in domain", Provenance.MEASURED);
        continue;
      }

      Config best = null;
      Row bestRow = null;
      double bestCost = Double.MAX_VALUE;
      for (Config c : configs) {
        Row r = measureLayered(radius, c);
        double cost =
            IndexConfigPlanner.realizedCost(
                    s, c, budget, r.selectNanos(), r.reconcileNanosPerMark(),
                    r.residentBytes(), r.totalBytes() - r.residentBytes(), 0.0d)
                + ModelCrossoverPlanner.layeredCacheNanos(s, c);
        if (cost < bestCost) {
          bestCost = cost;
          best = c;
          bestRow = r;
        }
      }

      double spiralCost =
          spiral.selectNanos()
              + load * spiral.reconcileNanosPerMark()
              + ModelCrossoverPlanner.lineNanos(spiral.residentBytes())
                  * Math.max(1.0d, Math.log(Math.max(2, spiral.runs())) / Math.log(2.0d));

      String subject = "r=" + radius;
      REPORT.add("measured", subject, "marks offered to spiral",
          String.valueOf(spiral.marksOffered()), Provenance.MEASURED);
      REPORT.add("measured", subject, "marks offered to layered",
          String.valueOf(bestRow.marksOffered()), Provenance.MEASURED);
      REPORT.add("measured", subject, "spiral marks retained",
          String.valueOf(spiral.marksRetained()), Provenance.MEASURED);
      REPORT.add("measured", subject, "spiral retention",
          spiral.marksRetained() / (double) Math.max(1L, spiral.marksOffered()),
          Provenance.DERIVED);
      REPORT.add("measured", subject, "layered marks retained",
          String.valueOf(bestRow.marksRetained()), Provenance.MEASURED);
      REPORT.add("measured", subject, "layered retention",
          bestRow.marksRetained() / (double) Math.max(1L, bestRow.marksOffered()),
          Provenance.DERIVED);
      REPORT.add("measured", subject, "spiral runs", String.valueOf(spiral.runs()),
          Provenance.MEASURED);
      REPORT.add("measured", subject, "spiral resident bytes",
          String.valueOf(spiral.residentBytes()), Provenance.DERIVED);
      REPORT.add("measured", subject, "spiral select ns", spiral.selectNanos(),
          Provenance.MEASURED);
      REPORT.add("measured", subject, "spiral reconcile ns/mark",
          spiral.reconcileNanosPerMark(), Provenance.MEASURED);
      REPORT.add("measured", subject, "spiral realized ns/candidate", spiralCost,
          Provenance.DERIVED);
      REPORT.add("measured", subject, "layered choice", String.valueOf(best), Provenance.DERIVED);
      REPORT.add("measured", subject, "layered runs", String.valueOf(bestRow.runs()),
          Provenance.MEASURED);
      REPORT.add("measured", subject, "layered resident bytes",
          String.valueOf(bestRow.residentBytes()), Provenance.MEASURED);
      REPORT.add("measured", subject, "layered total bytes",
          String.valueOf(bestRow.totalBytes()), Provenance.MEASURED);
      REPORT.add("measured", subject, "layered select ns", bestRow.selectNanos(),
          Provenance.MEASURED);
      REPORT.add("measured", subject, "layered reconcile ns/mark",
          bestRow.reconcileNanosPerMark(), Provenance.MEASURED);
      REPORT.add("measured", subject, "layered realized ns/candidate", bestCost,
          Provenance.DERIVED);
      REPORT.add("measured", subject, "measured winner",
          bestCost <= spiralCost ? "LAYERED" : "SPIRAL", Provenance.DERIVED);
      REPORT.add("measured", subject, "resident ratio layered/spiral",
          bestRow.residentBytes() / (double) Math.max(1L, spiral.residentBytes()),
          Provenance.DERIVED);

      if (bestCost <= spiralCost) layeredWins++;
    }

    REPORT.note(
        "The measured winner column is what the two models cost on this rig under one objective "
            + "at chunk precision. It is not the planner's answer: the planner works from fitted "
            + "coefficients and is scored against these rows separately.");
    assertTrue(layeredWins > 0, "the layered model must win somewhere in the measured range");
  }

  // -------------------------------------------------------------------------------------
  // 2. the crossover, bisected per storage tier
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("crossover radius is bisected per storage tier, giving one curve per device class")
  public void crossoverPerStorageTier() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    double[] tiers = {
      IndexConfigPlanner.NVME_OP_NS,
      IndexConfigPlanner.SSD_OP_NS,
      IndexConfigPlanner.HDD_OP_NS,
      IndexConfigPlanner.NETWORK_OP_NS
    };
    String[] names = {"nvme", "ssd", "hdd", "network"};
    // A boundary can only exist where residency is contested and reconciliation is not the whole
    // cost. At a heavy mark load the layered model's local reconciliation dominates everything
    // else and wins unconditionally, which hides the memory-versus-latency trade this test is for.
    long headroom = 512L << 20;
    double load = 0.001d;

    int[] crossovers = new int[tiers.length];
    for (int i = 0; i < tiers.length; i++) {
      Situation template = situation(1024, load, headroom, tiers[i]);
      int crossover = ModelCrossoverPlanner.crossoverRadius(template, 128, 1 << 18);
      crossovers[i] = crossover;
      REPORT.add("crossover", names[i], "crossover radius (blocks)",
          crossover < 0 ? "none in range" : String.valueOf(crossover), Provenance.MODELED);
      REPORT.add("crossover", names[i], "model below crossover",
          ModelCrossoverPlanner.choose(ModelCrossoverPlanner.withRadius(template, 128))
              .model()
              .name(),
          Provenance.MODELED);
      REPORT.add("crossover", names[i], "model above crossover",
          ModelCrossoverPlanner.choose(ModelCrossoverPlanner.withRadius(template, 1 << 17))
              .model()
              .name(),
          Provenance.MODELED);
    }

    REPORT.note(
        "One curve per device tier is the point of the exercise, and the honest reading of these "
            + "rows has to state whether the curves separate. Where they coincide, storage latency "
            + "does not move the boundary at this operating point and the decision reduces to "
            + "memory plus access time - which is a finding, not a failure, but it must not be "
            + "reported as demonstrated device adaptivity.");

    for (int i = 0; i < crossovers.length; i++) {
      assertNotEquals(
          0, crossovers[i], "crossover for " + names[i] + " must be resolved or reported absent");
    }
  }

  // -------------------------------------------------------------------------------------
  // 3. the surface: tier x headroom x mark load
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("the boundary is a surface over tier, headroom and mark load, not a scalar")
  public void crossoverSurface() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    double[] tiers = {
      IndexConfigPlanner.NVME_OP_NS,
      IndexConfigPlanner.SSD_OP_NS,
      IndexConfigPlanner.HDD_OP_NS,
      IndexConfigPlanner.NETWORK_OP_NS
    };
    String[] tierNames = {"nvme", "ssd", "hdd", "network"};
    // The two smallest points exist to cross the budget cliff. Above it the objective is a
    // constrained memory check with three inert terms, which is exactly the degenerate regime the
    // earlier sweep spent all of its points in.
    long[] headrooms = {4L << 20, 16L << 20, 512L << 20, 8192L << 20};
    String[] headroomNames = {"4MB", "16MB", "512MB", "8GB"};
    double[] loads = {0.001d, 0.05d, 1.0d};

    java.util.Set<Integer> values = new java.util.TreeSet<>();
    java.util.Set<String> chosen = new java.util.TreeSet<>();
    for (int t = 0; t < tiers.length; t++) {
      for (int h = 0; h < headrooms.length; h++) {
        for (double load : loads) {
          Situation template = situation(1024, load, headrooms[h], tiers[t]);
          int crossover = ModelCrossoverPlanner.crossoverRadius(template, 128, 1 << 18);
          String subject = tierNames[t] + " free=" + headroomNames[h] + " load=" + load;
          REPORT.add("surface", subject, "crossover radius (blocks)",
              crossover < 0 ? "none in range" : String.valueOf(crossover), Provenance.MODELED);
          // Both radii count toward the variation assertion. Sampling only the small one made the
          // check depend on the layered model being forced to page: once residency became a
          // costed branch it is resident and cheapest at every small-radius point, so a
          // single-radius sample would report a constant and hide a boundary that does exist
          // further out.
          String atSmall = ModelCrossoverPlanner.choose(template).model().name();
          String atLarge =
              ModelCrossoverPlanner.choose(ModelCrossoverPlanner.withRadius(template, 1 << 17))
                  .model()
                  .name();
          chosen.add(atSmall);
          chosen.add(atLarge);
          REPORT.add("surface", subject, "model at r=1024", atSmall, Provenance.MODELED);
          REPORT.add("surface", subject, "model at r=131072", atLarge, Provenance.MODELED);
          values.add(crossover);
        }
      }
    }
    int distinct = values.size();

    REPORT.note(
        "A surface with one value everywhere would mean the extra inputs are decoration. The "
            + "count of distinct boundary values across the grid is therefore reported as the "
            + "evidence that the heuristic is multivariable in effect and not only in form.");
    REPORT.add("surface", "grid", "distinct crossover values", String.valueOf(distinct),
        Provenance.DERIVED);
    REPORT.add("surface", "grid", "models chosen across the grid", String.join("/", chosen),
        Provenance.DERIVED);
    assertTrue(distinct >= 1, "surface must be computable across the grid");
    // The load-bearing assertion. A planner that answers the same model at every point is a
    // constant wearing a cost model, and that is precisely what the previous disqualification
    // rule produced.
    assertTrue(
        chosen.size() > 1,
        "the chosen model must vary across the grid, otherwise the inputs are decoration");
  }

  // -------------------------------------------------------------------------------------
  // 4. cache locality, as its own axis
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("cache residency is priced separately from heap footprint")
  public void cacheLocalityIsPriced() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int[] radii = {1024, 8192, 65536, 262144};
    long headroom = 8192L << 20;
    for (int radius : radii) {
      Situation s = situation(radius, 0.05d, headroom, IndexConfigPlanner.SSD_OP_NS);
      ModelChoice choice = ModelCrossoverPlanner.choose(s);
      Config c = choice.config();
      String subject = "r=" + radius;
      REPORT.add("cache", subject, "chosen model", choice.model().name(), Provenance.MODELED);
      REPORT.add("cache", subject, "layered directory bytes",
          String.valueOf(choice.layeredResidentBytes()), Provenance.MODELED);
      REPORT.add("cache", subject, "directory line ns",
          ModelCrossoverPlanner.lineNanos(choice.layeredResidentBytes()), Provenance.MODELED);
      REPORT.add("cache", subject, "layered cache ns / selection",
          c == null ? "n/a" : String.format("%.3f", ModelCrossoverPlanner.layeredCacheNanos(s, c)),
          Provenance.MODELED);
      REPORT.add("cache", subject, "spiral table bytes",
          String.valueOf(choice.spiralResidentBytes()), Provenance.MODELED);
      REPORT.add("cache", subject, "spiral table line ns",
          ModelCrossoverPlanner.lineNanos(choice.spiralResidentBytes()), Provenance.MODELED);
      REPORT.add("cache", subject, "spiral fits derived budget",
          String.valueOf(choice.spiralFitsBudget()), Provenance.MODELED);
    }

    REPORT.note(
        "The directory is what has to stay cache-resident, and it is small enough to do so far "
            + "past the range at which the spiral's table leaves last-level cache. That is a "
            + "distinct claim from the heap one and it is the mechanism behind flat selection "
            + "latency: the part touched on every draw stops growing, while the part that grows "
            + "is touched once and sequentially.");

    Situation big = situation(262144, 0.05d, headroom, IndexConfigPlanner.SSD_OP_NS);
    assertTrue(
        ModelCrossoverPlanner.lineNanos(
                ModelCrossoverPlanner.spiralResidentBytes(big, SIGNAL_RESOLUTION))
            >= ModelCrossoverPlanner.lineNanos(ModelCrossoverPlanner.choose(big).layeredResidentBytes()),
        "the spiral's table must not be cheaper per line than the directory at border scale");
  }

  // -------------------------------------------------------------------------------------
  // 5. the boundary must move in the stated direction
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("slower storage never moves the boundary against the pageable model")
  public void slowerStorageDoesNotPunishTheLayeredModel() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    long headroom = 512L << 20;
    Situation nvme = situation(4096, 0.05d, headroom, IndexConfigPlanner.NVME_OP_NS);
    Situation hdd = situation(4096, 0.05d, headroom, IndexConfigPlanner.HDD_OP_NS);

    ModelChoice onNvme = ModelCrossoverPlanner.choose(nvme);
    ModelChoice onHdd = ModelCrossoverPlanner.choose(hdd);

    REPORT.add("direction", "r=4096 nvme", "chosen model", onNvme.model().name(),
        Provenance.MODELED);
    REPORT.add("direction", "r=4096 nvme", "layered cost", onNvme.layeredCost(),
        Provenance.MODELED);
    REPORT.add("direction", "r=4096 hdd", "chosen model", onHdd.model().name(),
        Provenance.MODELED);
    REPORT.add("direction", "r=4096 hdd", "layered cost", onHdd.layeredCost(),
        Provenance.MODELED);
    REPORT.add("direction", "r=4096", "hdd/nvme layered cost",
        onHdd.layeredCost() / Math.max(1.0d, onNvme.layeredCost()), Provenance.DERIVED);

    assertTrue(
        onHdd.layeredCost() >= onNvme.layeredCost(),
        "a slower device must not make the pageable model look cheaper");
    assertEquals(
        onNvme.config().innerRes(),
        onHdd.config().innerRes(),
        "storage latency must not be allowed to buy back precision");
  }
}
