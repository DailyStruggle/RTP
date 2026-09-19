package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Time and allocation cost of the biome-recall draw, old gather-per-attempt form versus the cached
 * prefix-sum form. Both forms are reimplemented here over the same generated run tables, so the
 * measurement is of the draw path only - no region, shape or scheduler state is involved.
 *
 * <p>Allocation is read from {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}, which
 * counts bytes allocated by this thread whether or not they survive a collection. That is the
 * figure that matters here: the old form's garbage was short-lived by construction, so retained
 * heap would show nothing while GC pressure per attempt was the actual cost.
 */
class BiomeRecallDrawCostTest {

  private static final int BIOMES = 8;

  /** Run tables for {@code biomes} biomes of {@code runs} runs each: keys plus prefix sums. */
  private static long[][][] tables(int biomes, int runs, long seed) {
    Random rng = new Random(seed);
    long[][] keys = new long[biomes][runs];
    long[][] sums = new long[biomes][runs];
    for (int b = 0; b < biomes; b++) {
      long key = 0L;
      long acc = 0L;
      for (int k = 0; k < runs; k++) {
        key += 1L + rng.nextInt(64);
        long width = 1L + rng.nextInt(8);
        keys[b][k] = key;
        acc += width;
        sums[b][k] = acc;
        key += width;
      }
    }
    return new long[][][] {keys, sums};
  }

  /**
   * The pre-change per-attempt path: re-derive widths, build the flattened boxed run list and the
   * per-biome grouping, accumulate the ADR-062 weights, then draw.
   */
  private static long oldAttempt(long[][] keysIn, long[][] sumsIn) {
    List<Map.Entry<Long, Long>> flat = new ArrayList<>();
    List<long[][]> perBiome = new ArrayList<>();
    List<Double> perBiomeWeights = new ArrayList<>();
    List<Long> perBiomeRuns = new ArrayList<>();
    Set<String> recordedNames = new HashSet<>();
    for (int b = 0; b < keysIn.length; b++) {
      long[] keys = keysIn[b];
      long[] sums = sumsIn[b];
      long[] widths = new long[keys.length];
      for (int k = 0; k < keys.length; k++) {
        widths[k] = sums[k] - ((k > 0) ? sums[k - 1] : 0L);
        flat.add(new AbstractMap.SimpleEntry<>(keys[k], widths[k]));
      }
      perBiome.add(new long[][] {keys, widths});
      perBiomeWeights.add(1.0d);
      perBiomeRuns.add((long) keys.length);
      recordedNames.add("BIOME_" + b);
    }
    double[] weights = new double[perBiome.size()];
    for (int wi = 0; wi < weights.length; wi++) {
      double gf = PregenTask.grayFraction(perBiomeRuns.get(wi), PregenTask.GRAY_SPACE_MIN_RUNS);
      weights[wi] = perBiomeWeights.get(wi) * (1.0d - gf);
    }
    return PregenTask.drawWeightedBiome(perBiome, weights);
  }

  /** The post-change per-attempt path: tables already gathered, draw straight off prefix sums. */
  private static long newAttempt(long[][] keys, long[][] sums, int count, double[] weights) {
    return PregenTask.drawWeightedBiome(keys, sums, count, weights);
  }

  private static long allocatedBytes() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (bean instanceof com.sun.management.ThreadMXBean sun) {
      return sun.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    return -1L;
  }

  /** Min-of-trials ns per attempt, so scheduler noise biases toward the slower claim, not ours. */
  private static double nsPerAttempt(Runnable op, int iterations, int trials) {
    for (int w = 0; w < iterations; w++) op.run();
    double best = Double.MAX_VALUE;
    for (int t = 0; t < trials; t++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < iterations; i++) op.run();
      double ns = (System.nanoTime() - t0) / (double) iterations;
      if (ns < best) best = ns;
    }
    return best;
  }

  private static double bytesPerAttempt(Runnable op, int iterations) {
    for (int w = 0; w < 1_000; w++) op.run();
    long a0 = allocatedBytes();
    for (int i = 0; i < iterations; i++) op.run();
    long a1 = allocatedBytes();
    return (a1 - a0) / (double) iterations;
  }

  @Test
  @DisplayName("cached prefix-sum draw costs less time per attempt than the per-attempt gather")
  void timePerAttempt() {
    for (int runs : new int[] {64, 1024, 8192}) {
      long[][][] t = tables(BIOMES, runs, 1234L + runs);
      long[][] keys = t[0];
      long[][] sums = t[1];
      double[] weights = new double[BIOMES];
      java.util.Arrays.fill(weights, 1.0d);

      int iterations = Math.max(200, 400_000 / runs);
      double oldNs = nsPerAttempt(() -> oldAttempt(keys, sums), iterations, 5);
      double newNs = nsPerAttempt(() -> newAttempt(keys, sums, BIOMES, weights), iterations, 5);

      System.out.printf(
          "[DEBUG_LOG] time: biomes=%d runs/biome=%d totalRuns=%d"
              + " old=%.1f ns/attempt new=%.1f ns/attempt ratio=%.3fx%n",
          BIOMES, runs, BIOMES * runs, oldNs, newNs, newNs / oldNs);

      assertTrue(
          newNs < oldNs,
          "cached draw must not be slower at " + runs + " runs/biome: " + newNs + " vs " + oldNs);
    }
  }

  @Test
  @DisplayName("cached prefix-sum draw allocates a bounded amount per attempt, not O(runs)")
  void allocationPerAttempt() {
    if (allocatedBytes() < 0L) return; // no per-thread allocation counter on this JVM
    double[] oldBytes = new double[3];
    double[] newBytes = new double[3];
    int[] sizes = {64, 1024, 8192};
    for (int s = 0; s < sizes.length; s++) {
      int runs = sizes[s];
      long[][][] t = tables(BIOMES, runs, 4321L + runs);
      long[][] keys = t[0];
      long[][] sums = t[1];
      double[] weights = new double[BIOMES];
      java.util.Arrays.fill(weights, 1.0d);

      int iterations = Math.max(200, 200_000 / runs);
      oldBytes[s] = bytesPerAttempt(() -> oldAttempt(keys, sums), iterations);
      newBytes[s] = bytesPerAttempt(() -> newAttempt(keys, sums, BIOMES, weights), iterations);

      System.out.printf(
          "[DEBUG_LOG] alloc: biomes=%d runs/biome=%d totalRuns=%d"
              + " old=%.0f B/attempt (%.1f B/run) new=%.0f B/attempt ratio=%.4fx%n",
          BIOMES,
          runs,
          BIOMES * runs,
          oldBytes[s],
          oldBytes[s] / (BIOMES * runs),
          newBytes[s],
          newBytes[s] / Math.max(1.0d, oldBytes[s]));
    }

    // Old form scales with the recorded table; new form must be flat in it.
    assertTrue(
        oldBytes[2] > oldBytes[0] * 8.0d,
        "old gather should allocate proportionally to run count: " + oldBytes[0] + " -> "
            + oldBytes[2]);
    assertTrue(
        newBytes[2] < Math.max(64.0d, newBytes[0] * 2.0d),
        "cached draw allocation must not grow with run count: " + newBytes[0] + " -> "
            + newBytes[2]);
    assertTrue(
        newBytes[2] * 100.0d < oldBytes[2],
        "cached draw should allocate <1% of the old gather at 8192 runs/biome: " + newBytes[2]
            + " vs " + oldBytes[2]);
  }
}
