package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Config;
import io.github.dailystruggle.rtp.common.benchmark.IndexConfigPlanner.Situation;
import java.util.List;

/**
 * Extends {@link IndexConfigPlanner} from "which configuration of the layered index" to "which
 * model at all", so the crossover between the shipped global spiral and the layered index is
 * computed rather than eyeballed off two byte columns.
 *
 * <p>Three additions over the configuration planner:
 *
 * <ul>
 *   <li><b>The spiral is a candidate.</b> Its cost is evaluated under the same objective, so the
 *       two models are ranked by one number instead of compared axis by axis.
 *   <li><b>Cache locality is priced.</b> The two models differ in access <i>pattern</i>, not just
 *       in footprint: the spiral binary-searches a global run table, which is a random walk of
 *       {@code log2(runs)} independent lines, while the layered index touches a small directory and
 *       then walks one blob's runs sequentially, which the prefetcher hides. Ignoring this charges
 *       both models DRAM latency per line touched and flatters the spiral.
 *   <li><b>The crossover is a surface.</b> {@link #crossoverRadius} bisects radius for a fixed
 *       storage tier, headroom and mark load, so a report can emit one curve per device tier
 *       instead of one scalar.
 * </ul>
 *
 * <p><b>Test scope only.</b> Spiral coefficients are fitted from this repository's own measured
 * rows and are shape-dependent; the value is the shape of the boundary, not the absolute radius.
 */
public final class ModelCrossoverPlanner {

  /** Candidate learned-state models. */
  public enum Model {
    /** Shipped {@code MemoryShape} family: one global run table over Archimedean spiral keys. */
    SPIRAL,
    /** Coarse spiral directory over per-cell Hilbert RLE blobs. */
    LAYERED
  }

  /**
   * Where a model's bulk state lives. Distinguished because it is a <i>choice</i> with a price on
   * both sides, not a consequence of a budget being exceeded.
   *
   * <p>The earlier objective had no such choice: bytes above the budget silently became a miss
   * fraction, so "page it" was imposed rather than selected. On a device where one operation costs
   * milliseconds, holding the bulk resident and paying garbage collection for it can be the cheaper
   * branch, and an objective that cannot express that cannot answer the question.
   */
  public enum Residency {
    /** Whole structure in heap. No storage term, full heap-pressure term. */
    RESIDENT,
    /** Directory in heap, bulk on storage. Storage term applies, heap pressure only on the index. */
    PAGED
  }

  // ---------------------------------------------------------------------------------------
  // spiral coefficients, fitted from layered-index-scaling measured rows
  // ---------------------------------------------------------------------------------------

  /**
   * Runs the spiral stores per mark. Fitted at 0.0161 from the measured rows (r=1024: 1978 modeled
   * against 1972 measured; r=2048: 8045 against 7664), i.e. run-length encoding along the spiral
   * curve costs about one run per 62 marks on this save's clustering.
   */
  private static final double SPIRAL_RUNS_PER_MARK = 0.0161d;

  /** Bytes per stored run at the shipped table's width: one {@code long} key, one length. */
  private static final int SPIRAL_RUN_BYTES_LONG = 16;

  /** Bytes per stored run once keys and lengths are narrowed to {@code int}. */
  private static final int SPIRAL_RUN_BYTES_INT = 8;

  /**
   * Fixed cost of one spiral selection. Larger than the layered base because {@code xzToLocation}
   * and its inverse pay a trigonometric round trip per call, which the coarse directory pays once
   * per <i>region</i> rather than once per cell.
   */
  private static final double SPIRAL_SELECT_BASE_NS = 260.0d;

  /**
   * Reconciliation cost per mark, as {@code A * radius^K}. Fitted from measured spiral ns/mark
   * across r=256..2048; the exponent is the global-curve cost the layered model exists to remove.
   */
  private static final double SPIRAL_RECONCILE_A = 982.0d;

  /** @see #SPIRAL_RECONCILE_A */
  private static final double SPIRAL_RECONCILE_K = 0.783d;

  // ---------------------------------------------------------------------------------------
  // cache locality
  // ---------------------------------------------------------------------------------------

  /** Cache line width in bytes. */
  private static final int LINE_BYTES = 64;

  /** Resident bytes below which a structure is assumed to sit in a core's private cache. */
  private static final long L2_BYTES = 1L << 20;

  /** Resident bytes below which a structure is assumed to sit in shared last-level cache. */
  private static final long L3_BYTES = 32L << 20;

  /** Nanoseconds for a line served from private cache. */
  private static final double L2_LINE_NS = 4.0d;

  /** Nanoseconds for a line served from last-level cache. */
  private static final double L3_LINE_NS = 15.0d;

  /** Nanoseconds for a line served from main memory. */
  private static final double DRAM_LINE_NS = 80.0d;

  /**
   * Fraction of a sequential line touch that is actually paid. A hardware prefetcher hides most of
   * a forward stride, so a sequential run walk must not be charged at random-access latency; a
   * random binary-search probe is charged in full.
   */
  private static final double SEQUENTIAL_DISCOUNT = 0.125d;

  // ---------------------------------------------------------------------------------------
  // heap pressure
  // ---------------------------------------------------------------------------------------

  /**
   * Nanoseconds per delivered candidate attributable to garbage collection when the index has
   * consumed the whole of measured headroom.
   *
   * <p>This constant exists to correct a defect in the first version of this planner, and the
   * defect is worth naming because it invalidated the headline result. A structure whose resident
   * bytes exceeded the derived budget was assigned infinite cost, which converted "uses more RAM
   * than a policy fraction I chose" into "is not a candidate". That is indefensible against a
   * fully resident structure on slow storage: such a structure performs no I/O at all, so a cost
   * model that prices storage in milliseconds should rank it highly rather than eliminate it.
   *
   * <p>Over-budget residency is therefore priced, not forbidden. Only exceeding measured headroom
   * outright remains infeasible, because that is an allocation failure rather than a trade.
   */
  private static final double HEAP_PRESSURE_FULL_NS = 2_000.0d;

  private ModelCrossoverPlanner() {}

  /**
   * Finite cost of holding bytes resident beyond the soft budget, charged identically to both
   * models so neither is advantaged by the shape of the constraint.
   *
   * <p>Zero inside the budget, quadratic in the fraction of the remaining headroom consumed, and
   * infinite only above headroom itself.
   *
   * @param residentBytes bytes the model must keep in heap
   * @param s the situation, for measured headroom
   * @return nanoseconds per delivered candidate
   */
  public static double heapPressureNanos(long residentBytes, Situation s) {
    long headroom = Math.max(1L, s.heapHeadroomBytes());
    if (residentBytes >= headroom) return Double.MAX_VALUE;
    double occupancy = residentBytes / (double) headroom;
    double soft = IndexConfigPlanner.DEFAULT_BUDGET_FRACTION;
    if (occupancy <= soft) return 0.0d;
    double excess = (occupancy - soft) / (1.0d - soft);
    return HEAP_PRESSURE_FULL_NS * excess * excess;
  }

  /**
   * Latency of one line touch against a structure of the given size.
   *
   * @param workingSetBytes bytes the access pattern ranges over
   * @return nanoseconds per line touched
   */
  public static double lineNanos(long workingSetBytes) {
    if (workingSetBytes <= L2_BYTES) return L2_LINE_NS;
    if (workingSetBytes <= L3_BYTES) return L3_LINE_NS;
    return DRAM_LINE_NS;
  }

  /** @return marks the spiral holds at this situation's density and precision */
  public static double spiralMarks(Situation s, int resolutionBlocks) {
    double edge = 2.0d * s.radius() / Math.max(1, resolutionBlocks);
    return edge * edge * s.badDensity();
  }

  /** @return modeled runs in the spiral's global table */
  public static double spiralRuns(Situation s, int resolutionBlocks) {
    return Math.max(1.0d, spiralMarks(s, resolutionBlocks) * SPIRAL_RUNS_PER_MARK);
  }

  /**
   * Resident bytes for the spiral. The whole table is resident by construction: there is no
   * subsection a global curve can page independently, which is the structural difference the
   * crossover measures.
   *
   * @param s the situation
   * @param resolutionBlocks mark granularity, held equal to the layered inner edge for fairness
   * @return bytes
   */
  public static long spiralResidentBytes(Situation s, int resolutionBlocks) {
    return (long) (spiralRuns(s, resolutionBlocks) * spiralRunBytes(s, resolutionBlocks));
  }

  /**
   * Whether the spiral's key space fits a signed 32-bit integer at this addressing unit.
   *
   * <p>The shipped table stores {@code long} keys and {@code long} lengths, which is eight bytes
   * per run of headroom that is only needed when the domain genuinely exceeds two billion cells.
   * The key space is the <i>cell</i> count, not the block count, and the finest cell the shipped
   * shape addresses is <b>one chunk</b> - safety selection places a candidate at the centre of a
   * chunk, so a finer key would carry no distinguishable information. A 16-block cell edge is
   * therefore the full-precision case, not a coarsening, and even it fits {@code int} out to a
   * radius no world border reaches.
   *
   * @param s the situation
   * @param cellEdge blocks per addressed cell; 16 is one chunk and is the finest meaningful value
   * @return true when every key and length is representable as {@code int}
   */
  public static boolean spiralKeysFitInt(Situation s, int cellEdge) {
    double edge = 2.0d * s.radius() / Math.max(1, cellEdge);
    return edge * edge < Integer.MAX_VALUE;
  }

  /**
   * @param s the situation
   * @param cellEdge blocks per addressed cell
   * @return bytes per stored run, narrowed when the key space allows it
   */
  public static int spiralRunBytes(Situation s, int cellEdge) {
    return spiralKeysFitInt(s, cellEdge) ? SPIRAL_RUN_BYTES_INT : SPIRAL_RUN_BYTES_LONG;
  }

  /**
   * Modeled cost per delivered candidate for the spiral, under the same four-term objective the
   * configuration planner uses, plus the cache term.
   *
   * @param s the situation
   * @param resolutionBlocks mark granularity
   * @return nanoseconds per delivered candidate
   */
  public static double spiralCost(Situation s, int resolutionBlocks) {
    double runs = spiralRuns(s, resolutionBlocks);
    double pressure = heapPressureNanos(spiralResidentBytes(s, resolutionBlocks), s);
    if (pressure == Double.MAX_VALUE) return Double.MAX_VALUE;
    long bytes = spiralResidentBytes(s, resolutionBlocks);
    // Binary search over the run table: independent, unpredictable line touches, no prefetch.
    double probes = Math.max(1.0d, log2(runs));
    double cache = probes * lineNanos(bytes);
    double select = SPIRAL_SELECT_BASE_NS + cache;
    double reconcile =
        s.marksPerCandidate() * SPIRAL_RECONCILE_A * Math.pow(s.radius(), SPIRAL_RECONCILE_K);
    // No storage term: the spiral has no independently pageable subsection, so it never waits on a
    // device. On slow storage that is an advantage and the objective must let it be one; what the
    // structure pays instead is heap pressure, charged above.
    double precision =
        IndexConfigPlanner.precisionPenalty(s, new Config(resolutionBlocks, resolutionBlocks));
    return select + reconcile + precision + pressure;
  }

  /**
   * Cache term for the layered index: a Fenwick descent over the directory, then a sequential walk
   * of one blob's runs.
   *
   * @param s the situation
   * @param c the configuration
   * @return nanoseconds per selection attributable to memory hierarchy
   */
  public static double layeredCacheNanos(Situation s, Config c) {
    long dirBytes = IndexConfigPlanner.directoryBytes(s.radius(), c);
    double levels = Math.max(1.0d, log2(IndexConfigPlanner.outerCells(s.radius(), c)));
    double dir = levels * lineNanos(dirBytes);
    double runs = IndexConfigPlanner.runsPerBlob(s, c);
    long blobBytes = (long) (8.0d + runs * 12.0d);
    double lines = Math.max(1.0d, blobBytes / (double) LINE_BYTES);
    double blob = lines * lineNanos(blobBytes) * SEQUENTIAL_DISCOUNT;
    return dir + blob;
  }

  /**
   * @param s the situation
   * @param c the configuration
   * @param budget resident budget applied
   * @return layered cost per delivered candidate, with the cache term added
   */
  public static double layeredCost(Situation s, Config c, long budget) {
    return layeredResidency(s, c, budget).cost();
  }

  /**
   * Residency branch for the layered index, with both sides priced.
   *
   * @param residency the cheaper branch
   * @param residentCost nanoseconds per candidate holding blobs in heap
   * @param pagedCost nanoseconds per candidate holding only the directory in heap
   * @param residentBytes bytes the resident branch would occupy
   */
  public record ResidencyChoice(
      Residency residency, double residentCost, double pagedCost, long residentBytes) {

    /** @return cost of the chosen branch */
    public double cost() {
      return Math.min(residentCost, pagedCost);
    }
  }

  /**
   * Prices "keep the blobs in heap" against "page them from storage" for one configuration.
   *
   * <p>Both branches carry the same select, reconciliation, cache and precision terms; they differ
   * only in which of the two expensive terms applies. Resident pays heap pressure over directory
   * plus blobs and no storage term at all. Paged pays heap pressure over the directory alone and a
   * full storage term, because a blob that is not in heap must be fetched.
   *
   * <p>This is the decision a slow device should dominate, and it is separate from the budget: the
   * budget describes what is comfortable, whereas this describes what is cheaper.
   *
   * @param s the situation
   * @param c the configuration
   * @param budget resident budget, applied to the paged branch's partial residency
   * @return both branch costs and the winner
   */
  public static ResidencyChoice layeredResidency(Situation s, Config c, long budget) {
    long dir = IndexConfigPlanner.directoryBytes(s.radius(), c);
    long total = dir + (long) IndexConfigPlanner.blobBytes(s, c);
    double cache = layeredCacheNanos(s, c);

    // Long.MAX_VALUE budget drives the miss fraction to zero, which is exactly what "resident"
    // means: nothing is fetched.
    double residentPressure = heapPressureNanos(total, s);
    double resident =
        residentPressure == Double.MAX_VALUE
            ? Double.MAX_VALUE
            : IndexConfigPlanner.modeledCost(s, c, Long.MAX_VALUE) + cache + residentPressure;

    double pagedPressure = heapPressureNanos(dir, s);
    double paged =
        pagedPressure == Double.MAX_VALUE
            ? Double.MAX_VALUE
            : IndexConfigPlanner.modeledCost(s, c, Math.min(budget, total)) + cache + pagedPressure;

    Residency choice = resident <= paged ? Residency.RESIDENT : Residency.PAGED;
    return new ResidencyChoice(choice, resident, paged, total);
  }

  /** Chosen model with the cost of each side, so a report can show the margin. */
  public record ModelChoice(
      Model model,
      Config config,
      double layeredCost,
      double spiralCost,
      long layeredResidentBytes,
      long spiralResidentBytes,
      boolean spiralFitsBudget,
      Residency residency,
      int spiralCellEdge,
      int spiralRunBytes) {

    /** @return chosen cost */
    public double cost() {
      return model == Model.LAYERED ? layeredCost : spiralCost;
    }
  }

  /**
   * Chooses a model for the situation.
   *
   * @param s the situation
   * @return the choice
   */
  public static ModelChoice choose(Situation s) {
    return chooseAmong(s, IndexConfigPlanner.feasible(s));
  }

  /**
   * As {@link #choose(Situation)}, over a caller-supplied layered candidate set, so a regret
   * measurement can hold the planner to configurations it can actually measure.
   *
   * @param s the situation
   * @param candidates layered configurations to consider
   * @return the choice
   */
  public static ModelChoice chooseAmong(Situation s, List<Config> candidates) {
    long budget = IndexConfigPlanner.budgetBytes(s);
    Config bestConfig = null;
    double bestLayered = Double.MAX_VALUE;
    for (Config c : candidates) {
      double cost = layeredCost(s, c, budget);
      if (cost < bestLayered) {
        bestLayered = cost;
        bestConfig = c;
      }
    }
    // The spiral chooses its own precision, because "coarsen until it fits" is the only lever a
    // structure without pageable subsections has. Holding it at the layered model's resolution
    // would deny it that lever and then penalise it for the footprint it was forced to keep.
    int res = s.signalResolutionBlocks();
    double spiral = Double.MAX_VALUE;
    for (int candidate : IndexConfigPlanner.SPIRAL_CELL_EDGES) {
      if (candidate < s.signalResolutionBlocks()) continue;
      if (candidate > s.precisionFloorBlocks()) continue;
      double cost = spiralCost(s, candidate);
      if (cost < spiral) {
        spiral = cost;
        res = candidate;
      }
    }
    if (spiral == Double.MAX_VALUE) {
      res = s.signalResolutionBlocks();
      spiral = spiralCost(s, res);
    }
    long spiralBytes = spiralResidentBytes(s, res);
    boolean spiralFits = spiralBytes <= budget;
    long layeredBytes =
        bestConfig == null ? Long.MAX_VALUE : IndexConfigPlanner.directoryBytes(s.radius(), bestConfig);
    Model model = bestLayered <= spiral ? Model.LAYERED : Model.SPIRAL;
    // The spiral has no pageable subsection, so its residency is not a choice; reporting the
    // layered branch keeps the field meaningful for the model that does have one.
    Residency residency =
        bestConfig == null
            ? Residency.RESIDENT
            : layeredResidency(s, bestConfig, budget).residency();
    return new ModelChoice(
        model,
        bestConfig,
        bestLayered,
        spiral,
        layeredBytes,
        spiralBytes,
        spiralFits,
        model == Model.SPIRAL ? Residency.RESIDENT : residency,
        res,
        spiralRunBytes(s, res));
  }

  /**
   * Bisects radius for the boundary where the chosen model flips, holding every other input fixed.
   *
   * @param template situation whose radius is varied; all other fields are held
   * @param lowRadius radius at which the search starts, must favour one model
   * @param highRadius radius at which the search ends
   * @return smallest radius in the range at which {@link Model#LAYERED} is chosen, or {@code -1}
   *     when the model never flips across the range
   */
  public static int crossoverRadius(Situation template, int lowRadius, int highRadius) {
    Model atLow = choose(withRadius(template, lowRadius)).model();
    Model atHigh = choose(withRadius(template, highRadius)).model();
    if (atLow == atHigh) return -1;
    int lo = lowRadius;
    int hi = highRadius;
    while (hi - lo > 1) {
      int mid = lo + (hi - lo) / 2;
      if (choose(withRadius(template, mid)).model() == atLow) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    return hi;
  }

  /**
   * @param s the template
   * @param radius replacement radius
   * @return a copy at the given radius
   */
  public static Situation withRadius(Situation s, int radius) {
    return new Situation(
        radius,
        s.badDensity(),
        s.marksPerCandidate(),
        s.heapHeadroomBytes(),
        s.storageOpNanos(),
        s.precisionFloorBlocks(),
        s.signalResolutionBlocks());
  }

  private static double log2(double v) {
    return Math.log(v) / Math.log(2.0d);
  }
}
