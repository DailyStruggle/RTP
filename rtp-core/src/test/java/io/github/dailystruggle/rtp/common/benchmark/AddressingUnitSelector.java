package io.github.dailystruggle.rtp.common.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decides, from sampled world knowledge rather than from a radius-keyed table, how coarse the
 * learned state's addressing unit may be made without exceeding a stated accuracy loss.
 *
 * <p>Coarsening the unit is a <b>lossy compression of the learned state</b>: a cell is bad when any
 * chunk inside it is bad, so a coarse table cannot admit a good chunk that shares a cell with a bad
 * one. The quantity an operator is entitled to a bound on is therefore the share of <i>usable</i>
 * ground the compression throws away, and that is what this class estimates.
 *
 * <h2>Why the earlier metric was the wrong one</h2>
 *
 * A previous harness reported over-exclusion as {@code coarse excluded area / chunk-precision
 * excluded area}. That divides by the bad area, which is not a fixed quantity: it moves with the
 * domain, so the ratio moves even when the terrain and the unit do not. It also cannot be read as
 * accuracy - a world that is 1% bad and one that is 90% bad give wildly different ratios for
 * identical loss of usable ground. The metric used here is
 *
 * <pre>goodLoss = (good chunks at full precision - good chunks surviving the coarse unit)
 *            / good chunks at full precision</pre>
 *
 * which is bounded in {@code [0, 1]}, is scale-free, and is directly the "how over-eager is this"
 * number. It is a property of feature perimeter against cell edge, so it should be approximately
 * <b>invariant in radius</b> over statistically similar terrain - a prediction the accompanying
 * benchmark tests rather than assumes.
 *
 * <h2>Estimation</h2>
 *
 * One sampling pass serves every candidate unit. Region-aligned 32x32-chunk blocks are drawn
 * uniformly from the addressed domain; each yields 1 024 chunk facts, and every candidate unit from
 * 1 to 32 chunks partitions that same block, so all units are estimated from identical evidence and
 * their comparison carries no sampling difference. Cost is one block read per 1 024 facts regardless
 * of the unit under test, which is what makes this affordable at init.
 *
 * <p>Blocks are the sampling unit, not chunks, because chunks within a block are strongly
 * correlated - terrain clusters, which is the entire reason run-length encoding works here. Treating
 * 1 024 correlated facts as 1 024 independent observations would understate the interval by roughly
 * the square root of the cluster size. The ratio estimator's variance is therefore taken by
 * <b>jackknife over blocks</b>, and the decision uses the upper end of the interval: a sampling
 * error that lands on the permissive side of the cap is a silent accuracy loss, which is the class
 * of fault {@code S-004} exists to prevent.
 *
 * <h2>Test scope only</h2>
 *
 * ADR-080 opt-in tier, excluded from {@code build}. Nothing here is registered or referenced by
 * shipped code; the D-005 gate is closed.
 */
public final class AddressingUnitSelector {

  /** Candidate units, in chunks per cell edge. 1 is full precision, 32 is one Anvil region file. */
  public static final int[] UNITS = {1, 2, 4, 8, 16, 32};

  /** Chunks per region file edge, and therefore the sampling block edge. */
  private static final int BLOCK = 32;

  /** Minimum cells per domain edge, so {@code expand} granularity stays fine against the border. */
  public static final int MIN_CELLS_PER_EDGE = 64;

  /** Chunk-precision knowledge of the world. Supplied by persisted learned state or a prefilter. */
  public interface Oracle {

    /**
     * @param cx chunk x
     * @param cz chunk z
     * @return true when this chunk is known or believed unusable
     */
    boolean isBad(int cx, int cz);
  }

  /**
   * Estimated accuracy cost of one candidate unit.
   *
   * @param cellChunks candidate unit, chunks per cell edge
   * @param goodLoss point estimate of the share of usable chunks the unit discards
   * @param goodLossUpper upper end of the jackknife interval, the value the decision uses
   * @param blocks sampling blocks contributing evidence
   * @param goodChunks usable chunks observed at full precision
   */
  public record Estimate(
      int cellChunks, double goodLoss, double goodLossUpper, int blocks, long goodChunks) {}

  /**
   * The chosen unit and why.
   *
   * @param cellChunks unit to address the learned state with
   * @param goodLossUpper bound the choice was made against
   * @param reason human-readable justification, for the log line an operator will read
   */
  public record Decision(int cellChunks, double goodLossUpper, String reason) {}

  private AddressingUnitSelector() {}

  // -------------------------------------------------------------------------------------
  // estimation
  // -------------------------------------------------------------------------------------

  /**
   * Samples the domain once and estimates the accuracy cost of every candidate unit.
   *
   * @param oracle chunk-precision knowledge
   * @param radiusChunks addressed radius, in chunks
   * @param blocks sampling blocks to draw; each supplies 1 024 chunk facts
   * @param seed deterministic sampling seed
   * @return one estimate per candidate unit, finest first
   */
  public static List<Estimate> estimate(Oracle oracle, int radiusChunks, int blocks, long seed) {
    int blockRadius = Math.max(1, radiusChunks / BLOCK);
    Random rng = new Random(seed);

    // Per-unit, per-block accumulators. Kept separately so the jackknife can drop one block.
    int n = UNITS.length;
    long[][] goodPerBlock = new long[n][blocks];
    long[][] lostPerBlock = new long[n][blocks];
    boolean[] bad = new boolean[BLOCK * BLOCK];

    for (int b = 0; b < blocks; b++) {
      int brx = rng.nextInt(2 * blockRadius) - blockRadius;
      int brz = rng.nextInt(2 * blockRadius) - blockRadius;
      int baseCx = brx * BLOCK;
      int baseCz = brz * BLOCK;
      for (int dx = 0; dx < BLOCK; dx++) {
        for (int dz = 0; dz < BLOCK; dz++) {
          bad[dx * BLOCK + dz] = oracle.isBad(baseCx + dx, baseCz + dz);
        }
      }
      for (int u = 0; u < n; u++) {
        int e = UNITS[u];
        int cellsPerEdge = BLOCK / e;
        for (int ox = 0; ox < cellsPerEdge; ox++) {
          for (int oz = 0; oz < cellsPerEdge; oz++) {
            int good = 0;
            boolean anyBad = false;
            for (int dx = 0; dx < e; dx++) {
              for (int dz = 0; dz < e; dz++) {
                if (bad[(ox * e + dx) * BLOCK + (oz * e + dz)]) anyBad = true;
                else good++;
              }
            }
            goodPerBlock[u][b] += good;
            // A cell with any bad chunk is excluded whole, so every good chunk inside it is lost.
            if (anyBad) lostPerBlock[u][b] += good;
          }
        }
      }
    }

    List<Estimate> out = new ArrayList<>(n);
    for (int u = 0; u < n; u++) {
      out.add(ratioWithJackknife(UNITS[u], goodPerBlock[u], lostPerBlock[u]));
    }
    return out;
  }

  /**
   * Ratio estimator {@code sum(lost) / sum(good)} with a delete-one-block jackknife interval.
   *
   * <p>Jackknife rather than a binomial interval because the observations are clustered: 1 024
   * chunks from one region are not 1 024 independent draws, and the whole premise of this work is
   * that they are correlated. The upper end is the point estimate plus two standard errors, floored
   * at the point estimate so a degenerate sample can never report a bound below what was observed.
   */
  private static Estimate ratioWithJackknife(int cellChunks, long[] good, long[] lost) {
    int blocks = good.length;
    long goodSum = 0L;
    long lostSum = 0L;
    for (int b = 0; b < blocks; b++) {
      goodSum += good[b];
      lostSum += lost[b];
    }
    if (goodSum == 0L) {
      // No usable ground observed: nothing can be lost, but nothing is known either, so the bound
      // is reported as total loss rather than as zero.
      return new Estimate(cellChunks, 0.0d, 1.0d, blocks, 0L);
    }
    double point = lostSum / (double) goodSum;
    if (blocks < 2) return new Estimate(cellChunks, point, 1.0d, blocks, goodSum);

    double[] partial = new double[blocks];
    double mean = 0.0d;
    for (int b = 0; b < blocks; b++) {
      long g = goodSum - good[b];
      long l = lostSum - lost[b];
      partial[b] = g == 0L ? point : l / (double) g;
      mean += partial[b];
    }
    mean /= blocks;
    double acc = 0.0d;
    for (double p : partial) {
      double d = p - mean;
      acc += d * d;
    }
    double variance = acc * (blocks - 1) / (double) blocks;
    double upper = Math.min(1.0d, Math.max(point, point + 2.0d * Math.sqrt(variance)));
    return new Estimate(cellChunks, point, upper, blocks, goodSum);
  }

  // -------------------------------------------------------------------------------------
  // decision
  // -------------------------------------------------------------------------------------

  /**
   * Picks the coarsest unit whose accuracy bound is inside the cap and whose grid leaves the domain
   * enough cells to expand through.
   *
   * <p>No time-versus-memory weighting is applied, and that is a measured decision rather than an
   * omission: inside this cap both resources fall monotonically with the unit, so the coarsest
   * admissible unit is the argmin and a marginal-efficiency rule strictly loses by stopping short of
   * it (ADR-084 section 14c). The cap is the whole decision.
   *
   * @param estimates output of {@link #estimate}
   * @param radiusChunks addressed radius, in chunks
   * @param cap largest tolerable share of usable ground discarded, e.g. {@code 0.20}
   * @return the unit to use, never coarser than the guard allows and never finer than full precision
   */
  public static Decision decide(List<Estimate> estimates, int radiusChunks, double cap) {
    Decision chosen = new Decision(1, 0.0d, "full precision: no coarser unit was admissible");
    for (Estimate e : estimates) {
      if (radiusChunks / e.cellChunks() < MIN_CELLS_PER_EDGE) {
        // Granularity is a ratio. One region file is 0.5% of a 100 km border and 50% of a 1 km one,
        // and below this guard expand quantizes visibly and the domain degenerates toward one cell.
        continue;
      }
      if (e.goodLossUpper() > cap) continue;
      chosen =
          new Decision(
              e.cellChunks(),
              e.goodLossUpper(),
              "coarsest unit inside the accuracy cap: bound "
                  + String.format("%.4f", e.goodLossUpper())
                  + " <= "
                  + String.format("%.4f", cap));
    }
    return chosen;
  }

  /**
   * Applies the decision to a table that already exists, which is the startup, reload and rebuild
   * case rather than the first-run case.
   *
   * <p>Three rules, in order:
   *
   * <ol>
   *   <li><b>Precision violation wins.</b> If the stored unit is no longer inside the cap, change
   *       regardless of cost - the alternative is continuing to discard ground the operator did not
   *       agree to lose.
   *   <li><b>Coarsening is a ratchet.</b> A coarser admissible unit is adopted only when it is a
   *       multiple of the stored one, because then the existing table folds up losslessly and no
   *       learned state is discarded. A coarser unit that is not a multiple would require relearning
   *       and is refused.
   *   <li><b>Refinement is not free and is not chased.</b> Going finer cannot be derived from a
   *       coarse table - the information was destroyed - so it means discarding learned state. It is
   *       done only under rule 1.
   * </ol>
   *
   * @param storedUnit unit the persisted table is addressed in
   * @param estimates output of {@link #estimate}
   * @param radiusChunks addressed radius, in chunks
   * @param cap accuracy cap
   * @return the unit to run with, and whether learned state survives
   */
  public static Transition transition(
      int storedUnit, List<Estimate> estimates, int radiusChunks, double cap) {
    Decision target = decide(estimates, radiusChunks, cap);
    double storedBound = 1.0d;
    for (Estimate e : estimates) {
      if (e.cellChunks() == storedUnit) storedBound = e.goodLossUpper();
    }
    boolean storedAdmissible =
        storedBound <= cap && radiusChunks / storedUnit >= MIN_CELLS_PER_EDGE;

    if (!storedAdmissible) {
      boolean foldable = storedUnit != 0 && target.cellChunks() % storedUnit == 0;
      return new Transition(
          target.cellChunks(),
          foldable,
          "stored unit "
              + storedUnit
              + " is outside the cap (bound "
              + String.format("%.4f", storedBound)
              + "); moving to "
              + target.cellChunks()
              + (foldable ? " by folding the table upward" : " and relearning"));
    }
    if (target.cellChunks() > storedUnit && target.cellChunks() % storedUnit == 0) {
      return new Transition(
          target.cellChunks(),
          true,
          "coarsening " + storedUnit + " -> " + target.cellChunks() + ", folded losslessly");
    }
    return new Transition(storedUnit, true, "stored unit " + storedUnit + " retained");
  }

  /**
   * @param cellChunks unit to run with
   * @param learnedStateSurvives true when the existing table can be reused, by folding if needed
   * @param reason justification for the log line
   */
  public record Transition(int cellChunks, boolean learnedStateSurvives, String reason) {}

  // -------------------------------------------------------------------------------------
  // exhaustive reference, for scoring the sampled rule
  // -------------------------------------------------------------------------------------

  /**
   * Exhaustive accuracy cost of one unit over the whole domain. Affordable only in a benchmark; it
   * exists so the sampled estimate can be scored against ground truth rather than against itself.
   *
   * @return share of usable chunks the unit discards
   */
  public static double exhaustiveGoodLoss(Oracle oracle, int radiusChunks, int cellChunks) {
    int cellRadius = radiusChunks / cellChunks;
    long good = 0L;
    long lost = 0L;
    for (int cx = -cellRadius; cx < cellRadius; cx++) {
      for (int cz = -cellRadius; cz < cellRadius; cz++) {
        int inCell = 0;
        boolean anyBad = false;
        for (int dx = 0; dx < cellChunks; dx++) {
          for (int dz = 0; dz < cellChunks; dz++) {
            if (oracle.isBad(cx * cellChunks + dx, cz * cellChunks + dz)) anyBad = true;
            else inCell++;
          }
        }
        good += inCell;
        if (anyBad) lost += inCell;
      }
    }
    return good == 0L ? 0.0d : lost / (double) good;
  }
}
