package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Derives the hybrid curve's point edge {@code P} (chunks) from measured inputs or domain radius
 * rather than a pinned constant (ADR-085 criterion C8).
 *
 * <p>Two levers operate on the hybrid key space:
 * <ul>
 *   <li>{@code P} (point edge, chunks): scales key-order locality and full-precision run count.
 *       Costs only {@code expand} step size.
 *   <li>{@code spatialResolution} (coalescing gap): lossy; costs discarded usable ground.
 * </ul>
 *
 * <p>Hard constraint: granularity is a ratio. To guard {@code expand} granularity, the domain must
 * contain at least {@link #MIN_CELLS_PER_EDGE} cells per edge: {@code (2 * radiusChunks) / P >= 64}.
 */
public final class PointEdgeSelector {

  /** Powers of two candidate values for P. */
  public static final int[] CANDIDATES = {1, 2, 4, 8, 16, 32, 64, 128};

  /** Default point edge fallback when domain radius is unconstrained or standard. */
  public static final int DEFAULT_P = 32;

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
   */
  public record Decision(int chosenP, double estimatedRuns, String reason) {}

  /**
   * Transition decision on reload or rebuild.
   */
  public record Transition(int p, boolean losslessRatchet, String reason) {}

  private PointEdgeSelector() {}

  /**
   * Quickly derives the largest admissible power-of-two {@code P} for a given radius in chunks
   * satisfying the hard guard {@code (2 * radiusChunks) / P >= MIN_CELLS_PER_EDGE}, bounded
   * at {@link #DEFAULT_P}.
   *
   * @param radiusChunks addressed half-edge in chunks
   * @return admissible {@code P} in chunks
   */
  public static int derivePFromRadius(long radiusChunks) {
    if (radiusChunks <= 0) return 1;
    // maxAllowedP = (2 * radiusChunks) / MIN_CELLS_PER_EDGE
    long maxAllowedP = (2L * radiusChunks) / MIN_CELLS_PER_EDGE;
    if (maxAllowedP < 1) return 1;

    int chosen = 1;
    for (int candidate : CANDIDATES) {
      if (candidate <= maxAllowedP && candidate <= DEFAULT_P) {
        chosen = candidate;
      }
    }
    return chosen;
  }

  /**
   * Checks whether a candidate {@code P} satisfies the hard minimum-cells-per-edge guard.
   *
   * @param radiusChunks addressed half-edge in chunks
   * @param p candidate point edge in chunks
   * @return true if admissible
   */
  public static boolean isAdmissible(long radiusChunks, int p) {
    if (p <= 0) return false;
    return (2L * radiusChunks) / p >= MIN_CELLS_PER_EDGE;
  }

  /**
   * Samples the domain to estimate run counts and loss across candidate values of {@code P}.
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
      int baseChunkX = brx * BLOCK;
      int baseChunkZ = brz * BLOCK;

      long goodCount = 0;
      for (int lz = 0; lz < BLOCK; lz++) {
        for (int lx = 0; lx < BLOCK; lx++) {
          boolean isB = oracle.isBad(baseChunkX + lx, baseChunkZ + lz);
          bad[lz * BLOCK + lx] = isB;
          if (!isB) goodCount++;
        }
      }

      for (int c = 0; c < numCandidates; c++) {
        int p = CANDIDATES[c];
        long runs = countRunsWithinBlock(bad, p);
        blockRuns[c][b] = runs;
        blockGood[c][b] = goodCount;
        blockLost[c][b] = countLostGoodAtGap2(bad, p);
      }
    }

    List<CandidateEstimate> estimates = new ArrayList<>(numCandidates);
    for (int c = 0; c < numCandidates; c++) {
      int p = CANDIDATES[c];
      boolean admissible = (2L * radiusChunks) / p >= MIN_CELLS_PER_EDGE;

      double meanRuns = mean(blockRuns[c]);
      double seRuns = jackknifeStandardError(blockRuns[c]);
      double upperRuns = meanRuns + 2.0 * seRuns;

      double totalGood = sum(blockGood[c]);
      double totalLost = sum(blockLost[c]);
      double meanLoss = (totalGood == 0) ? 0.0 : (totalLost / (double) totalGood);
      double seLoss = jackknifeRatioSE(blockLost[c], blockGood[c]);
      double upperLoss = meanLoss + 2.0 * seLoss;

      estimates.add(
          new CandidateEstimate(
              p, admissible, meanRuns, upperRuns, meanLoss, upperLoss, sampleBlocks));
    }

    return estimates;
  }

  /**
   * Decides {@code P} from candidate estimates under an allowable loss budget.
   */
  public static Decision decide(
      List<CandidateEstimate> estimates, double maxGoodLossFraction) {
    CandidateEstimate best = null;
    double lowestUpperRuns = Double.MAX_VALUE;

    for (CandidateEstimate est : estimates) {
      if (!est.admissible()) continue;
      if (est.goodLossUpper() > maxGoodLossFraction) continue;

      if (est.runsUpper() < lowestUpperRuns) {
        lowestUpperRuns = est.runsUpper();
        best = est;
      }
    }

    if (best != null) {
      return new Decision(
          best.p(),
          best.runsMean(),
          "Selected P=" + best.p() + " with lowest conservative run estimate "
              + String.format("%.1f", best.runsUpper())
              + " under loss budget " + String.format("%.2f%%", maxGoodLossFraction * 100.0));
    }

    for (CandidateEstimate est : estimates) {
      if (est.admissible()) {
        return new Decision(
            est.p(),
            est.runsMean(),
            "Fallback to minimal admissible P=" + est.p() + " (loss budget exceeded across all candidates)");
      }
    }

    return new Decision(
        1, 0.0, "Domain too small for coarse grid; falling back to P=1 (spiral resolution)");
  }

  /**
   * Decides how to transition on reload when the stored table had point edge {@code storedP}.
   */
  public static Transition decideTransition(int storedP, int targetP) {
    if (storedP == targetP) {
      return new Transition(targetP, true, "P unchanged (" + targetP + "); no transition needed");
    }
    if (targetP > storedP && (targetP % storedP) == 0) {
      return new Transition(
          targetP,
          true,
          "Lossless upward ratchet: target P=" + targetP + " is a multiple of stored P=" + storedP);
    }
    return new Transition(
        targetP,
        false,
        "Incompatible P change from " + storedP + " to " + targetP + "; table discarded and relearned");
  }

  private static long countRunsWithinBlock(boolean[] bad, int p) {
    int pointsPerEdge = BLOCK / Math.min(BLOCK, Math.max(1, p));
    int pointEdge = Math.min(BLOCK, Math.max(1, p));
    int pointArea = pointEdge * pointEdge;

    long runs = 0;
    boolean inRun = false;

    for (int pz = 0; pz < pointsPerEdge; pz++) {
      for (int px = 0; px < pointsPerEdge; px++) {
        for (int h = 0; h < pointArea; h++) {
          int lx = h % pointEdge;
          int lz = h / pointEdge;
          int gx = px * pointEdge + lx;
          int gz = pz * pointEdge + lz;
          boolean isBad = bad[gz * BLOCK + gx];
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

  private static long countLostGoodAtGap2(boolean[] bad, int p) {
    int pointEdge = Math.min(BLOCK, Math.max(1, p));
    int pointsPerEdge = BLOCK / pointEdge;
    int pointArea = pointEdge * pointEdge;

    long lostGood = 0;
    for (int pz = 0; pz < pointsPerEdge; pz++) {
      for (int px = 0; px < pointsPerEdge; px++) {
        int lastBadKey = -999;
        for (int h = 0; h < pointArea; h++) {
          int lx = h % pointEdge;
          int lz = h / pointEdge;
          int gx = px * pointEdge + lx;
          int gz = pz * pointEdge + lz;
          if (bad[gz * BLOCK + gx]) {
            if (lastBadKey >= 0) {
              int gap = h - lastBadKey - 1;
              if (gap > 0 && gap <= 2) {
                lostGood += gap;
              }
            }
            lastBadKey = h;
          }
        }
      }
    }
    return lostGood;
  }

  private static double mean(long[] values) {
    if (values.length == 0) return 0.0;
    return sum(values) / (double) values.length;
  }

  private static long sum(long[] values) {
    long s = 0;
    for (long v : values) s += v;
    return s;
  }

  private static double jackknifeStandardError(long[] values) {
    int n = values.length;
    if (n < 2) return 0.0;
    double fullSum = sum(values);
    double[] leaveOneOut = new double[n];
    double sumLoo = 0.0;
    for (int i = 0; i < n; i++) {
      leaveOneOut[i] = (fullSum - values[i]) / (double) (n - 1);
      sumLoo += leaveOneOut[i];
    }
    double meanLoo = sumLoo / n;
    double varianceSum = 0.0;
    for (int i = 0; i < n; i++) {
      double diff = leaveOneOut[i] - meanLoo;
      varianceSum += diff * diff;
    }
    double se = Math.sqrt(((n - 1.0) / n) * varianceSum);
    return Double.isFinite(se) ? se : 0.0;
  }

  private static double jackknifeRatioSE(long[] numerators, long[] denominators) {
    int n = numerators.length;
    if (n < 2) return 0.0;
    double sumNum = sum(numerators);
    double sumDen = sum(denominators);
    if (sumDen == 0) return 0.0;

    double[] loo = new double[n];
    double sumLoo = 0.0;
    for (int i = 0; i < n; i++) {
      double num = sumNum - numerators[i];
      double den = sumDen - denominators[i];
      loo[i] = (den == 0) ? 0.0 : (num / den);
      sumLoo += loo[i];
    }
    double meanLoo = sumLoo / n;
    double varianceSum = 0.0;
    for (int i = 0; i < n; i++) {
      double diff = loo[i] - meanLoo;
      varianceSum += diff * diff;
    }
    double se = Math.sqrt(((n - 1.0) / n) * varianceSum);
    return Double.isFinite(se) ? se : 0.0;
  }
}
