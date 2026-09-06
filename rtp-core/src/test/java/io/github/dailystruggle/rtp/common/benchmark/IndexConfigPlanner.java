package io.github.dailystruggle.rtp.common.benchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Cost-based planner that chooses a learned-state index configuration from the situation rather
 * than from a configured constant.
 *
 * <p>The decision is not "which structure is smallest". It is <b>how much RAM this range costs and
 * how much is left to spend on time</b>, so the objective mixes four terms in one unit - nanoseconds
 * per delivered candidate - and applies the memory limit as a constraint derived from measured heap
 * headroom rather than as an operator setting:
 *
 * <pre>
 *   cost(c) = tSelect(c) + marksPerCandidate * tReconcile(c)
 *           + missFraction(c) * storageOpNanos / candidatesPerRead(c)
 *           + precisionPenalty(c)
 *   subject to residentDirectoryBytes(c) &lt;= budget
 * </pre>
 *
 * <p>Three properties of that objective are deliberate and are the reason it does not collapse into
 * a single-factor rule:
 *
 * <ul>
 *   <li><b>Storage latency raises the memory budget instead of lowering it.</b> A slow device makes
 *       {@code missFraction * storageOpNanos} dominate, so the planner spends more RAM to keep the
 *       structure resident. On a fast device the same term is cheap and paging wins. The budget is
 *       therefore an output, and a machine running off spinning rust is not asked to sacrifice RAM.
 *   <li><b>Granularity is a ratio.</b> An outer cell is the expansion step, so {@code
 *       outerEdge / radius} is what matters: 512 blocks is half of a 1 km radius and half a percent
 *       of a 100 km one. The same absolute edge is therefore rejected at one scale and free at
 *       another.
 *   <li><b>Coarsening is priced, not free.</b> Dropping low-order Hilbert bits saves bytes by
 *       over-excluding good area, so the objective charges for the excluded fraction; otherwise the
 *       planner would always pick the coarsest precision available.
 * </ul>
 *
 * <p><b>Test scope only.</b> Nothing here is registered or referenced by shipped code. The
 * coefficients are fitted from this repository's own benchmark rows and are machine-relative; the
 * planner's value is the shape of the decision, not the absolute nanoseconds.
 */
public final class IndexConfigPlanner {

  // ---------------------------------------------------------------------------------------
  // fitted coefficients, from layered-index-scaling and model-calibration rows
  // ---------------------------------------------------------------------------------------

  /** Fixed cost of one selection: rng, Fenwick descent base, Hilbert inverse. */
  private static final double SELECT_BASE_NS = 110.0d;

  /** Marginal cost per Fenwick level, i.e. per doubling of outer cell count. */
  private static final double SELECT_PER_LEVEL_NS = 1.5d;

  /**
   * Marginal cost of walking one run while locating the k-th good inner cell. Nearly zero in
   * measurement: the walk is sequential over two primitive arrays, so it prefetches.
   */
  private static final double SELECT_PER_RUN_NS = 0.1d;

  /** Fixed cost of rebuilding one blob: staging, sort setup, Fenwick update. */
  private static final double RECONCILE_BASE_NS = 240.0d;

  /**
   * Marginal reconciliation cost per outer cell in the directory.
   *
   * <p>This term is what makes the cost curve U-shaped and is therefore the reason a heuristic is
   * needed at all: a fine outer grid is cheap per blob but expensive per mark, because a mark walks
   * and dirties structures sized by the whole directory. Measured at 0.27 ns per outer cell, it
   * dominates below a 128-block outer edge and is negligible above it.
   *
   * <p>Honest caveat: part of this is memory locality over the vehicle's array-of-arrays layout, so
   * a production directory with a compact blocked layout would pay less. The term is therefore an
   * upper bound on the fine-grid penalty, which biases the planner toward coarser cells.
   */
  private static final double RECONCILE_PER_OUTER_CELL_NS = 0.27d;

  /** Marginal cost per run in the merge pass of one blob rebuild. */
  private static final double RECONCILE_PER_RUN_NS = 6.2d;

  /**
   * Runs stored per occupied blob, as a fraction of the bad inner cells in it. Measured at 0.35
   * across two decades of blob size: run-length encoding of chunk-granular occupancy saves about
   * a factor of three, and does <b>not</b> reduce to a perimeter law, because a real save's
   * generated area is ragged at chunk scale rather than smooth.
   */
  private static final double RUNS_PER_BAD_CELL = 0.35d;

  /** Blocks per chunk. The finest cell any addressing scheme here can distinguish. */
  public static final int CHUNK_BLOCKS = 16;

  /**
   * Measured over-exclusion ratio against a one-chunk grid, indexed
   * {@code [domain radius][cell edge in chunks]} over {@link #OVER_EXCLUSION_RADII} and {@link
   * #OVER_EXCLUSION_CELL_CHUNKS}, from the coarsened-spiral rows on the tiled real save.
   *
   * <p>A fitted per-doubling constant was used here previously and is withdrawn. It was fitted from
   * a sweep of sub-chunk cell edges, which do not exist - a candidate is placed at the centre of a
   * chunk, so one chunk is the finest distinguishable cell and every finer edge stores the same
   * information in more bytes. The replacement is a lookup over measured points, because the cost
   * of a coarse cell is a ratio of cell edge to the scale of the marked features and no single
   * exponent fits it: one region file per cell costs 5.284x at a 2 048-block radius and 1.118x at
   * 8 192, which is the same absolute cell being ruinous at one scale and nearly free at another.
   */
  private static final double[][] OVER_EXCLUSION = {
    {1.000d, 1.118d, 1.361d, 1.838d, 3.044d, 5.284d},
    {1.000d, 1.004d, 1.012d, 1.027d, 1.065d, 1.118d},
    {1.000d, 1.008d, 1.023d, 1.052d, 1.121d, 1.253d},
  };

  /** Radii, in blocks, at which {@link #OVER_EXCLUSION} was measured. */
  private static final int[] OVER_EXCLUSION_RADII = {2_048, 8_192, 20_480};

  /** Cell edges, in chunks, at which {@link #OVER_EXCLUSION} was measured. */
  private static final int[] OVER_EXCLUSION_CELL_CHUNKS = {1, 2, 4, 8, 16, 32};

  /**
   * Policy weight converting one unit of over-excluded domain fraction into nanoseconds. This is
   * the only frankly normative number in the objective: it states how much time the operator is
   * willing to spend to avoid excluding good land. Set so that excluding one percent of the domain
   * costs about the same as one SSD read per candidate.
   */
  private static final double PRECISION_WEIGHT_NS = 800_000.0d;

  /**
   * Draws a paged blob is expected to serve before eviction. Caps the amortization of one storage
   * operation, since claiming a read is amortized over every cell it contains assumes a residency
   * window no cache policy guarantees.
   */
  private static final double BLOB_RESIDENCY_DRAWS = 256.0d;

  /** Fraction of measured heap headroom the index may occupy before the constraint binds. */
  public static final double DEFAULT_BUDGET_FRACTION = 0.01d;

  /**
   * Largest tolerable ratio of expansion step to radius. One outer cell is one expansion step, so
   * this is the quantization a growing range would show, and a domain must be at least 16 outer
   * cells across.
   *
   * <p>Set from measurement rather than taste. A stricter 1/32 was tried first and it dominated the
   * objective at small radius - at a 1 km radius it admitted only two outer edges and excluded the
   * measured optimum outright, which turns a cost model into a constant.
   */
  public static final double MAX_EXPAND_RATIO = 1.0d / 8.0d;

  /** Per-operation storage latency by device class, in nanoseconds. */
  public static final double NVME_OP_NS = 120_000.0d;

  /** @see #NVME_OP_NS */
  public static final double SSD_OP_NS = 500_000.0d;

  /** @see #NVME_OP_NS */
  public static final double HDD_OP_NS = 9_000_000.0d;

  /**
   * Per-operation latency for state held on another host - a network filesystem or a remote
   * database, as network mode implies. Round-trip plus query dominates, so this is an order above
   * spinning rust and two above flash.
   *
   * @see #NVME_OP_NS
   */
  public static final double NETWORK_OP_NS = 50_000_000.0d;

  /**
   * Candidate outer-cell edges, in blocks, i.e. 1 to 64 chunks. 512 blocks is one Anvil region
   * file, and is the only structurally privileged value: an outer cell then aligns with the unit
   * the storage layer fetches.
   */
  public static final int[] OUTER_EDGES = {16, 32, 64, 128, 256, 512, 1024};

  /**
   * Inner-cell edge, in blocks. Fixed at one chunk, and not a lever.
   *
   * <p>The inner Hilbert curve is a curve <i>over the chunks inside an outer cell</i>. There is no
   * curve inside a chunk, because safety selection places a candidate at the chunk centre, so a
   * sub-chunk cell carries no distinguishable information. Earlier versions of this planner swept
   * {@code {1, 2, 4, 8, 16}} blocks and treated the result as a precision-versus-bytes lever;
   * those four finer points are the same physical resolution as the fifth, so every figure derived
   * from them overstated inner cell counts by up to 256x and is withdrawn.
   *
   * <p>Coarsening above one chunk is still available - it is {@link #OUTER_EDGES} for the layered
   * model and {@link #SPIRAL_CELL_EDGES} for a global curve, both priced by {@link
   * #precisionPenalty}.
   */
  public static final int[] INNER_RESOLUTIONS = {CHUNK_BLOCKS};

  /**
   * Candidate addressing units, in blocks, for a global curve with no pageable subsection: one
   * chunk up to one region file. Coarsening is the only footprint lever such a structure has, so
   * the objective must offer it the range.
   */
  public static final int[] SPIRAL_CELL_EDGES = {16, 32, 64, 128, 256, 512};

  private IndexConfigPlanner() {}

  // ---------------------------------------------------------------------------------------
  // inputs and outputs
  // ---------------------------------------------------------------------------------------

  /**
   * Situation the planner reacts to.
   *
   * @param radius half-edge of the domain in blocks
   * @param badDensity fraction of the domain expected to be marked bad
   * @param marksPerCandidate marks reconciled per delivered candidate; the reconciliation load
   * @param heapHeadroomBytes measured free heap, not a configured ceiling
   * @param storageOpNanos observed per-operation storage latency
   * @param precisionFloorBlocks coarsest inner cell the operator's precision floor permits, e.g.
   *     an ocean-width cap on how much good land one merge may swallow
   * @param signalResolutionBlocks edge, in blocks, of the finest distinction the incoming marks can
   *     actually make. This is {@link #CHUNK_BLOCKS} for every real caller: a candidate is placed
   *     at the centre of a chunk, so one chunk is the finest cell that carries information. Any
   *     smaller value is a fiction and inflates every cell count derived from it.
   */
  public record Situation(
      int radius,
      double badDensity,
      double marksPerCandidate,
      long heapHeadroomBytes,
      double storageOpNanos,
      int precisionFloorBlocks,
      int signalResolutionBlocks) {}

  /**
   * One configuration of the layered index.
   *
   * @param outerEdge blocks per outer cell edge
   * @param innerRes blocks per inner cell edge
   */
  public record Config(int outerEdge, int innerRes) {
    @Override
    public String toString() {
      return "outer=" + outerEdge + ",inner=" + innerRes;
    }
  }

  /**
   * Planner output.
   *
   * @param config chosen configuration
   * @param modeledCostNanos modeled cost per delivered candidate
   * @param budgetBytes resident budget the constraint was applied with
   * @param modeledResidentBytes modeled directory footprint of the choice
   * @param fullyResident whether blobs are modeled to fit alongside the directory
   * @param rationale which term decided it, for the report
   */
  public record Plan(
      Config config,
      double modeledCostNanos,
      long budgetBytes,
      long modeledResidentBytes,
      boolean fullyResident,
      String rationale) {}

  // ---------------------------------------------------------------------------------------
  // planning
  // ---------------------------------------------------------------------------------------

  /**
   * @param s the situation
   * @return every configuration that is structurally legal at this radius, before costing
   */
  public static List<Config> feasible(Situation s) {
    List<Config> out = new ArrayList<>();
    for (int outer : OUTER_EDGES) {
      // The domain is padded up to a whole even number of outer cells rather than required to
      // divide evenly; demanding exact division left a 100 km radius with two legal edges and made
      // granularity an accident of arithmetic.
      if (outerCellsEdge(s.radius(), outer) < 2L) continue;
      for (int inner : INNER_RESOLUTIONS) {
        if (inner > outer || outer % inner != 0) continue;
        if (inner > s.precisionFloorBlocks()) continue;
        out.add(new Config(outer, inner));
      }
    }
    return out;
  }

  /** @return resident budget in bytes, derived from measured headroom */
  public static long budgetBytes(Situation s) {
    return (long) (s.heapHeadroomBytes() * DEFAULT_BUDGET_FRACTION);
  }

  /**
   * Chooses the lowest-modeled-cost configuration subject to the budget and granularity
   * constraints, relaxing constraints in a stated order rather than failing.
   *
   * @param s the situation
   * @return the plan
   */
  public static Plan plan(Situation s) {
    return planAmong(s, feasible(s));
  }

  /**
   * As {@link #plan(Situation)}, over a caller-supplied candidate set. Exists so a regret
   * measurement can hold the planner to the same candidates it is able to measure, rather than
   * comparing a planned configuration against an optimum that was never evaluated.
   *
   * @param s the situation
   * @param all candidates to choose among
   * @return the plan
   */
  public static Plan planAmong(Situation s, List<Config> all) {
    long budget = budgetBytes(s);
    if (all.isEmpty()) {
      throw new IllegalStateException("no legal configuration at radius " + s.radius());
    }

    Plan best = pick(s, all, budget, true, "budget and granularity honoured");
    if (best != null) return best;
    best = pick(s, all, budget, false, "granularity relaxed: no coarse cell fits the budget");
    if (best != null) return best;
    return pick(s, all, Long.MAX_VALUE, false, "budget relaxed: headroom below directory floor");
  }

  private static Plan pick(
      Situation s, List<Config> all, long budget, boolean enforceExpand, String rationale) {
    Config bestConfig = null;
    double bestCost = Double.MAX_VALUE;
    boolean bestFits = false;
    long bestResident = 0L;
    for (Config c : all) {
      if (enforceExpand && c.outerEdge() / (double) s.radius() > MAX_EXPAND_RATIO) continue;
      long resident = directoryBytes(s.radius(), c);
      if (resident > budget) continue;
      double cost = modeledCost(s, c, budget);
      if (cost < bestCost) {
        bestCost = cost;
        bestConfig = c;
        bestResident = resident;
        bestFits = resident + blobBytes(s, c) <= budget;
      }
    }
    return bestConfig == null
        ? null
        : new Plan(bestConfig, bestCost, budget, bestResident, bestFits, rationale);
  }

  // ---------------------------------------------------------------------------------------
  // cost model
  // ---------------------------------------------------------------------------------------

  /** @return outer cells along one edge, padded up to an even count so the coarse spiral centres */
  public static long outerCellsEdge(int radius, int outerEdge) {
    long edge = (2L * radius + outerEdge - 1) / outerEdge;
    return edge + (edge % 2L);
  }

  /** @return outer cells at this radius and outer edge */
  public static long outerCells(int radius, Config c) {
    long edge = outerCellsEdge(radius, c.outerEdge());
    return edge * edge;
  }

  /** @return modeled resident directory bytes; matches the vehicle's 8 bytes per outer cell */
  public static long directoryBytes(int radius, Config c) {
    return outerCells(radius, c) * 8L;
  }

  /** @return inner cells per outer cell */
  public static long innerCells(Config c) {
    long edge = (long) c.outerEdge() / c.innerRes();
    return edge * edge;
  }

  /**
   * Modeled runs stored in one blob.
   *
   * <p>Basis: measured, not assumed. A perimeter law was expected and is not what the data shows -
   * runs scale linearly with the bad cells in a blob at a fitted ratio of {@link
   * #RUNS_PER_BAD_CELL}.
   */
  public static double runsPerBlob(Situation s, Config c) {
    double badInner = s.badDensity() * innerCells(c);
    return Math.max(1.0d, badInner * RUNS_PER_BAD_CELL);
  }

  /** @return modeled serialized bytes across every blob */
  public static double blobBytes(Situation s, Config c) {
    return outerCells(s.radius(), c) * (8.0d + runsPerBlob(s, c) * 12.0d);
  }

  /** @return modeled nanoseconds to deliver one candidate, the planner's objective */
  public static double modeledCost(Situation s, Config c, long budget) {
    double levels = Math.max(1.0d, log2(outerCells(s.radius(), c)));
    double runs = runsPerBlob(s, c);
    double select = SELECT_BASE_NS + levels * SELECT_PER_LEVEL_NS + runs * SELECT_PER_RUN_NS;
    double reconcile =
        s.marksPerCandidate()
            * (RECONCILE_BASE_NS
                + outerCells(s.radius(), c) * RECONCILE_PER_OUTER_CELL_NS
                + runs * RECONCILE_PER_RUN_NS);

    double blobs = blobBytes(s, c);
    double forBlobs = Math.max(0.0d, budget - directoryBytes(s.radius(), c));
    double missFraction = blobs <= forBlobs ? 0.0d : 1.0d - forBlobs / blobs;
    double served = Math.min(BLOB_RESIDENCY_DRAWS, innerCells(c) * (1.0d - s.badDensity()));
    double io = missFraction * s.storageOpNanos() / Math.max(1.0d, served);

    return select + reconcile + io + precisionPenalty(s, c);
  }

  /**
   * Cost charged for over-exclusion.
   *
   * <p>Charged only for coarsening beyond the resolution the incoming marks can distinguish. Below
   * that edge coarsening is free by construction: the mask cannot tell two cells apart, so merging
   * them excludes nothing that was known to be good.
   *
   * @param s the situation
   * @param c the configuration
   * @return nanoseconds per delivered candidate
   */
  public static double precisionPenalty(Situation s, Config c) {
    double excess = s.badDensity() * overExclusionExcess(s.radius(), c.innerRes());
    return excess * PRECISION_WEIGHT_NS;
  }

  /**
   * Over-excluded fraction beyond a one-chunk grid, read from the nearest measured point in {@link
   * #OVER_EXCLUSION} in log space on both axes. Nearest rather than interpolated on purpose: the
   * measured rows are three radii apart by a factor of four and no law fits between them, so an
   * interpolated value would look more precise than the evidence supports.
   *
   * @param radius domain half-edge in blocks
   * @param cellEdgeBlocks addressing unit in blocks; at or below one chunk this is zero, since the
   *     mask cannot distinguish two such cells and merging them excludes nothing known to be good
   * @return excess as a fraction, e.g. {@code 0.118} for a measured ratio of {@code 1.118}
   */
  public static double overExclusionExcess(int radius, int cellEdgeBlocks) {
    double cellChunks = cellEdgeBlocks / (double) CHUNK_BLOCKS;
    if (cellChunks <= 1.0d) return 0.0d;
    int ri = nearestIndexLog(OVER_EXCLUSION_RADII, radius);
    double[] row = OVER_EXCLUSION[ri];
    int ci = nearestIndexLog(OVER_EXCLUSION_CELL_CHUNKS, (int) Math.round(cellChunks));
    return Math.max(0.0d, row[ci] - 1.0d);
  }

  private static int nearestIndexLog(int[] points, int value) {
    int best = 0;
    double bestGap = Double.MAX_VALUE;
    for (int i = 0; i < points.length; i++) {
      double gap = Math.abs(log2(Math.max(1, points[i])) - log2(Math.max(1, value)));
      if (gap < bestGap) {
        bestGap = gap;
        best = i;
      }
    }
    return best;
  }

  /**
   * Same objective as {@link #modeledCost}, evaluated on measured quantities instead of fitted
   * ones. This is the oracle a regret measurement compares against: identical weights, so any gap
   * is attributable to the cost model's coefficients rather than to a different objective.
   *
   * @param s the situation
   * @param c the configuration measured
   * @param budget resident budget applied
   * @param selectNanos measured nanoseconds per selection
   * @param reconcileNanosPerMark measured nanoseconds per mark reconciled
   * @param directoryBytes measured resident directory bytes
   * @param blobBytes measured serialized blob bytes
   * @param excessExcludedFraction measured fraction of the domain excluded <i>beyond</i> what the
   *     finest configuration excluded, i.e. the over-exclusion coarsening actually caused
   * @return realized nanoseconds per delivered candidate
   */
  public static double realizedCost(
      Situation s,
      Config c,
      long budget,
      double selectNanos,
      double reconcileNanosPerMark,
      long directoryBytes,
      double blobBytes,
      double excessExcludedFraction) {
    double reconcile = s.marksPerCandidate() * reconcileNanosPerMark;
    double forBlobs = Math.max(0.0d, budget - directoryBytes);
    double missFraction = blobBytes <= forBlobs ? 0.0d : 1.0d - forBlobs / blobBytes;
    double served = Math.min(BLOB_RESIDENCY_DRAWS, innerCells(c) * (1.0d - s.badDensity()));
    double io = missFraction * s.storageOpNanos() / Math.max(1.0d, served);
    double precision = Math.max(0.0d, excessExcludedFraction) * PRECISION_WEIGHT_NS;
    return selectNanos + reconcile + io + precision;
  }

  private static double log2(double v) {
    return Math.log(v) / Math.log(2.0d);
  }

  /**
   * Hysteresis gate. A planner that re-evaluates every pulse and switches on any improvement
   * rebuilds the whole structure to save nanoseconds, so a challenger must win by a margin and keep
   * winning.
   *
   * @param incumbentCost cost of the configuration currently in use
   * @param challengerCost cost of the newly planned configuration
   * @param margin required relative improvement, e.g. {@code 0.20}
   * @return true when the switch is worth performing
   */
  public static boolean shouldSwitch(double incumbentCost, double challengerCost, double margin) {
    return challengerCost < incumbentCost * (1.0d - margin);
  }
}
