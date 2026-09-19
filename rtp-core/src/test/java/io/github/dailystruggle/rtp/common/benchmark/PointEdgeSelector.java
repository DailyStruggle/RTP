package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Derives the hybrid curve's point edge {@code P} (chunks) from measured inputs rather than a
 * pinned constant (ADR-085 criterion C8).
 *
 * <p>Two levers operate on the hybrid key space:
 * <ul>
 *   <li>{@code P} (point edge, chunks): scales key-order locality and full-precision run count.
 *       Costs only {@code expand} step size.
 *   <li>{@code spatialResolution} (coalescing gap): lossy; costs discarded usable ground.
 * </ul>
 *
 * <p>Why a selector is needed: coarsening is <b>not monotone</b> in {@code P}. A coarse grid is a
 * different ordering of the plane, so {@code P} cannot be fitted through an exponent or assumed
 * to improve monotonically with point size. Instead, candidate values of {@code P} are evaluated
 * <b>point by point</b> over powers of two.
 *
 * <p>Hard constraint: granularity is a ratio. To guard {@code expand} granularity, the domain must
 * contain at least {@link #MIN_CELLS_PER_EDGE} cells per edge: {@code (2 * radiusChunks) / P >= 64}.
 *
 * <p>Sampling uses a delete-one-block jackknife estimator with a conservative <b>upper bound</b>
 * (mean + 2 * SE) so that sampling noise does not pick an unstable setting.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
public final class PointEdgeSelector {

  /** Powers of two candidate values for P. */
  public static final int[] CANDIDATES = {1, 2, 4, 8, 16, 32, 64, 128};

  /** Hard constraint: minimum coarse cells per edge across the full domain window. */
  public static final int MIN_CELLS_PER_EDGE = 64;

  /** Sampling block edge in chunks (1 Anvil region = 32x32 chunks = 1024 chunks). */
  public static final int BLOCK = 32;

  /** Oracle supplying chunk usability. */
  @FunctionalInterface
  public interface OccupancyOracle {
    boolean isBad(int cx, int cz);
  }

  /**
   * Estimated performance of a candidate {@code P}.
   *
   * @param p candidate point edge in chunks
   * @param admissible whether (2 * radius) / p >= MIN_CELLS_PER_EDGE
   * @param runsMean sample estimate of full-precision run count
   * @param runsUpper conservative upper bound (jackknife mean + 2*SE)
   * @param goodLossMean estimated usable ground discarded at spatialResolution = 2
   * @param goodLossUpper conservative upper bound of discarded ground
   * @param blocksEvaluated number of sample blocks evaluated
   */
  public record CandidateEstimate(
      int p,
      boolean admissible,
      double runsMean,
      double runsUpper,
      double goodLossMean,
      double goodLossUpper,
      int blocksEvaluated) {}

  /**
   * Final decision for {@code P}.
   *
   * @param chosenP chosen point edge in chunks
   * @param estimatedRuns estimated runs at chosen P
   * @param reason explanation
   */
  public record Decision(int chosenP, double estimatedRuns, String reason) {}

  /**
   * Transition decision on reload or rebuild.
   *
   * @param p point edge to run with
   * @param losslessRatchet whether existing learned state folds up losslessly
   * @param reason explanation
   */
  public record Transition(int p, boolean losslessRatchet, String reason) {}

  private PointEdgeSelector() {}

  /**
   * Samples the domain to estimate run counts and loss across candidate values of {@code P}.
   *
   * @param oracle chunk occupancy oracle
   * @param radiusChunks addressed half-edge in chunks
   * @param sampleBlocks number of sample blocks (32x32 chunks each)
   * @param seed deterministic seed
   * @return list of candidate estimates for all candidates in {@link #CANDIDATES}
   */
  public static List<CandidateEstimate> estimate(
      OccupancyOracle oracle, int radiusChunks, int sampleBlocks, long seed) {
    int blockRadius = Math.max(1, radiusChunks / BLOCK);
    Random rng = new Random(seed);

    int numCandidates = CANDIDATES.length;
    long[][] blockRuns = new long[numCandidates][sampleBlocks];
    long[][] blockGood = new long[numCandidates][sampleBlocks];
    long[][] blockLost = new long[numCandidates][sampleBlocks];

    boolean[] bad = new boolean[BLOCK * BLOCK];

    for (int b = 0; b < sampleBlocks; b++) {
      int brx = rng.nextInt(2 * blockRadius) - blockRadius;
      int brz = rng.nextInt(2 * blockRadius) - blockRadius;
      int baseCx = brx * BLOCK;
      int baseCz = brz * BLOCK;

      for (int dx = 0; dx < BLOCK; dx++) {
        for (int dz = 0; dz < BLOCK; dz++) {
          bad[dx * BLOCK + dz] = oracle.isBad(baseCx + dx, baseCz + dz);
        }
      }

      for (int c = 0; c < numCandidates; c++) {
        int p = CANDIDATES[c];
        boolean admissible = (2 * radiusChunks) / p >= MIN_CELLS_PER_EDGE;
        if (!admissible) continue;

        // Count runs inside the block for this candidate P using Hilbert traversal within points.
        // If P <= BLOCK, each block holds (BLOCK / P)^2 points.
        // If P > BLOCK, the block is a fraction of a point.
        long runsInBlock = countRunsInBlock(bad, p);
        blockRuns[c][b] = runsInBlock;

        // Also estimate coalescing loss at min resolution (gap = 2)
        long[] lossStats = countLossInBlock(bad, p, 2);
        blockGood[c][b] = lossStats[0];
        blockLost[c][b] = lossStats[1];
      }
    }

    List<CandidateEstimate> estimates = new ArrayList<>(numCandidates);
    for (int c = 0; c < numCandidates; c++) {
      int p = CANDIDATES[c];
      boolean admissible = (2 * radiusChunks) / p >= MIN_CELLS_PER_EDGE;
      if (!admissible) {
        estimates.add(new CandidateEstimate(p, false, Double.MAX_VALUE, Double.MAX_VALUE, 1.0, 1.0, 0));
        continue;
      }

      // Jackknife for run count
      double[] runStats = jackknifeMeanAndUpper(blockRuns[c]);
      // Jackknife for loss ratio
      double[] lossStats = jackknifeRatioAndUpper(blockGood[c], blockLost[c]);

      estimates.add(
          new CandidateEstimate(
              p,
              true,
              runStats[0],
              runStats[1],
              lossStats[0],
              lossStats[1],
              sampleBlocks));
    }

    return estimates;
  }

  /**
   * Decides the optimal {@code P} that minimizes run count subject to the hard granularity guard.
   *
   * @param estimates candidate estimates from {@link #estimate}
   * @param radiusChunks addressed half-edge in chunks
   * @return decision
   */
  public static Decision decide(List<CandidateEstimate> estimates, int radiusChunks) {
    int bestP = 1;
    double bestRunsUpper = Double.MAX_VALUE;
    String bestReason = "fallback to P=1";

    for (CandidateEstimate est : estimates) {
      if (!est.admissible()) continue;
      // Hard guard: at least MIN_CELLS_PER_EDGE coarse cells per edge
      if ((2 * radiusChunks) / est.p() < MIN_CELLS_PER_EDGE) continue;

      if (est.runsUpper() < bestRunsUpper) {
        bestRunsUpper = est.runsUpper();
        bestP = est.p();
        bestReason =
            "argmin of conservative upper-bound runs ("
                + String.format("%.1f", est.runsUpper())
                + ") among admissible candidates";
      }
    }

    return new Decision(bestP, bestRunsUpper, bestReason);
  }

  /**
   * Handles transitions across reload or domain changes.
   *
   * <p>A transition is a <b>lossless ratchet</b> when the new P is a multiple of the stored P,
   * because an exact Hilbert table addressed at edge {@code P_old} can be aggregated losslessly
   * into points of edge {@code P_new = k * P_old}. If not a multiple, learned state cannot be
   * cleanly folded and must be relearned.
   *
   * @param storedP previously stored P
   * @param targetDecision newly chosen decision
   * @return transition
   */
  public static Transition transition(int storedP, Decision targetDecision) {
    int targetP = targetDecision.chosenP();
    if (storedP == targetP) {
      return new Transition(storedP, true, "retained existing P=" + storedP);
    }

    if (storedP > 0 && targetP > storedP && (targetP % storedP == 0)) {
      return new Transition(
          targetP,
          true,
          "coarsening P=" + storedP + " -> " + targetP + " (lossless upward fold)");
    }

    return new Transition(
        targetP,
        false,
        "switching P=" + storedP + " -> " + targetP + " (not a multiple; relearn required)");
  }

  /**
   * Brute-force argmin oracle: evaluates all admissible candidates on ground-truth window
   * and returns the exact argmin P and minimum run count.
   *
   * @param oracle occupancy oracle
   * @param radiusChunks half-edge in chunks
   * @return oracle decision
   */
  public static Decision exhaustiveArgmin(OccupancyOracle oracle, int radiusChunks) {
    int bestP = 1;
    long minRuns = Long.MAX_VALUE;

    for (int p : CANDIDATES) {
      if ((2 * radiusChunks) / p < MIN_CELLS_PER_EDGE) continue;

      long runs = countExhaustiveRuns(oracle, radiusChunks, p);
      if (runs < minRuns) {
        minRuns = runs;
        bestP = p;
      }
    }

    return new Decision(bestP, minRuns, "brute force ground truth argmin");
  }

  /**
   * Counts exact runs over the full window for a given P.
   */
  public static long countExhaustiveRuns(OccupancyOracle oracle, int radiusChunks, int p) {
    if (p == 1) {
      // Plain spiral
      io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square spiral =
          new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square("TEST_SPIRAL");
      spiral.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.radius, (long) radiusChunks);
      KeySpaceRunEncoder encoder = new KeySpaceRunEncoder();
      encoder.encode(spiral, radiusChunks, (cx, cz) -> !oracle.isBad(cx, cz));
      return encoder.runsAt(1L); // resolution 1 is full precision / no coalescing gaps bridged > 2
    }

    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(radiusChunks, p, true);
    KeySpaceRunEncoder encoder = new KeySpaceRunEncoder();
    encoder.encode(hybrid, radiusChunks, (cx, cz) -> !oracle.isBad(cx, cz));
    return encoder.runsAt(1L);
  }

  private static long countRunsInBlock(boolean[] bad, int p) {
    // Within a 32x32 block:
    // If p <= 32, we partition into points of size p x p.
    long runs = 0;
    int pointsPerEdge = Math.max(1, BLOCK / p);
    int pActual = Math.min(p, BLOCK);
    int order = Integer.numberOfTrailingZeros(pActual);

    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    for (int px = 0; px < pointsPerEdge; px++) {
      for (int pz = 0; pz < pointsPerEdge; pz++) {
        boolean inRun = false;
        long cells = (long) pActual * pActual;
        for (long k = 0; k < cells; k++) {
          int[] d = SpiralHilbertSquare.hilbertCoords(k, order);
          int lx = px * pActual + d[0];
          int lz = pz * pActual + d[1];
          boolean isBad = bad[lx * BLOCK + lz];
          if (isBad) {
            if (!inRun) {
              runs++;
              inRun = true;
            }
          } else {
            inRun = false;
          }
        }
      }
    }
    return runs;
  }

  private static long[] countLossInBlock(boolean[] bad, int p, int gap) {
    long good = 0;
    long lost = 0;
    int pointsPerEdge = Math.max(1, BLOCK / p);
    int pActual = Math.min(p, BLOCK);
    int order = Integer.numberOfTrailingZeros(pActual);

    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    for (int px = 0; px < pointsPerEdge; px++) {
      for (int pz = 0; pz < pointsPerEdge; pz++) {
        long cells = (long) pActual * pActual;
        long lastBad = -1;
        long usableInGap = 0;

        for (long k = 0; k < cells; k++) {
          int[] d = SpiralHilbertSquare.hilbertCoords(k, order);
          int lx = px * pActual + d[0];
          int lz = pz * pActual + d[1];
          boolean isBad = bad[lx * BLOCK + lz];
          if (!isBad) {
            good++;
            usableInGap++;
          } else {
            if (lastBad >= 0) {
              long dist = k - lastBad;
              if (dist <= gap + 1) {
                lost += usableInGap;
              }
            }
            lastBad = k;
            usableInGap = 0;
          }
        }
      }
    }
    return new long[] {good, lost};
  }

  private static double[] jackknifeMeanAndUpper(long[] values) {
    int n = values.length;
    if (n == 0) return new double[] {Double.MAX_VALUE, Double.MAX_VALUE};
    long sum = 0;
    for (long v : values) sum += v;
    double mean = (double) sum / n;
    if (n < 2) return new double[] {mean, mean};

    double acc = 0;
    for (long v : values) {
      double partialMean = (double) (sum - v) / (n - 1);
      double diff = partialMean - mean;
      acc += diff * diff;
    }
    double variance = acc * (n - 1) / n;
    double se = Math.sqrt(variance);
    return new double[] {mean, mean + 2.0 * se};
  }

  private static double[] jackknifeRatioAndUpper(long[] good, long[] lost) {
    int n = good.length;
    long totalGood = 0;
    long totalLost = 0;
    for (int i = 0; i < n; i++) {
      totalGood += good[i];
      totalLost += lost[i];
    }
    if (totalGood == 0) return new double[] {1.0, 1.0};
    double point = (double) totalLost / totalGood;
    if (n < 2) return new double[] {point, 1.0};

    double[] partial = new double[n];
    double sumPartial = 0;
    for (int i = 0; i < n; i++) {
      long g = totalGood - good[i];
      long l = totalLost - lost[i];
      partial[i] = (g == 0) ? point : (double) l / g;
      sumPartial += partial[i];
    }
    double meanPartial = sumPartial / n;
    double acc = 0;
    for (double p : partial) {
      double diff = p - meanPartial;
      acc += diff * diff;
    }
    double variance = acc * (n - 1) / (double) n;
    double se = Math.sqrt(variance);
    double upper = Math.min(1.0, Math.max(point, point + 2.0 * se));
    return new double[] {point, upper};
  }
}
