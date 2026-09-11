package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Empirically locates the <b>devolution cutoff</b>: the domain scale below which a single flat run
 * table ({@link KeyRunTable}) beats a partitioned bin directory ({@link SegmentedKeyRunTable}), and
 * above which the two-tier directory earns its per-bin overhead.
 *
 * <p>Motivation: at small scales the domain reduces to a near-perfect spiral with only a handful of
 * holes. A directory of {@code totalRange / binSize} mostly-empty bins then pays object-header,
 * pointer and directory-prefix overhead to describe a few bits of state, so one flat table is both
 * faster to resolve and cheaper to retain. The crossover is a measured property, not a guessed
 * constant; this test sweeps scales under a fixed hazard density and reports where segmented first
 * wins on <b>latency</b> and on <b>retained memory</b>.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier. Prints a table; does not pin a shipped constant.
 */
@Tag("simulation")
class DevolutionThresholdBenchmarkTest {

  // Fixed hazard shape so the crossover reflects scale, not noise.
  private static final double BAD_DENSITY = 0.30d;
  private static final int MEAN_RUN = 12;
  private static final long SEED = 20260911L;

  private static final long[] SCALES = {
    256L, 512L, 1_024L, 2_048L, 4_096L, 8_192L,
    16_384L, 32_768L, 65_536L, 131_072L, 262_144L, 524_288L, 1_048_576L
  };

  @Test
  @DisplayName("Devolution cutoff: single flat table vs segmented bin directory across scales")
  void locateDevolutionCutoff() {
    System.out.printf(
        "%-12s %-8s %-10s %-12s %-12s %-9s %-12s %-12s %-9s%n",
        "range", "bins", "binSize", "flat_ns", "seg_ns", "lat_x", "flat_bytes", "seg_bytes", "mem_x");

    long latencyCutoff = -1L;
    long memoryCutoff = -1L;

    for (long range : SCALES) {
      long[] badKeys = generateBadKeys(range);
      KeyRunTable flat = KeyRunTable.exact(badKeys, badKeys.length).coalesceFixed(2L);

      long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(range);
      SegmentedKeyRunTable seg = flat.toSegmented(range, binSize, 2L);

      long totalGood = range - flat.coveredCells();
      assertEquals(totalGood, range - seg.totalCovered(), "covered mismatch at range " + range);
      if (totalGood <= 0) continue;

      // Equivalence guard: flat and segmented must resolve identically before timing them.
      Random check = new Random(SEED ^ range);
      for (int i = 0; i < 2_000; i++) {
        long t = (check.nextLong() & Long.MAX_VALUE) % totalGood;
        assertEquals(
            flat.resolveAccumulate(t, range),
            seg.resolveAccumulate(t),
            "resolveAccumulate mismatch at range " + range + " target " + t);
      }

      int draws = 200_000;
      long[] targets = new long[draws];
      Random rng = new Random(SEED + range);
      for (int i = 0; i < draws; i++) {
        targets[i] = (rng.nextLong() & Long.MAX_VALUE) % totalGood;
      }

      // Warmup both paths.
      long warm = 0L;
      for (int i = 0; i < 20_000; i++) {
        warm += flat.resolveAccumulate(targets[i], range);
        warm += seg.resolveAccumulate(targets[i]);
      }
      if (warm == Long.MIN_VALUE) System.out.print("");

      long t0 = System.nanoTime();
      long flatSum = 0L;
      for (int i = 0; i < draws; i++) {
        flatSum += flat.resolveAccumulate(targets[i], range);
      }
      double flatNs = (double) (System.nanoTime() - t0) / draws;

      long t1 = System.nanoTime();
      long segSum = 0L;
      for (int i = 0; i < draws; i++) {
        segSum += seg.resolveAccumulate(targets[i]);
      }
      double segNs = (double) (System.nanoTime() - t1) / draws;

      assertEquals(flatSum, segSum, "resolution checksum mismatch at range " + range);

      long flatBytes = approxFlatRetainedBytes(flat.runs());
      long segBytes = seg.retainedBytes();

      double latX = flatNs / segNs;
      double memX = (double) flatBytes / (double) segBytes;

      System.out.printf(
          "%-12d %-8d %-10d %-12.1f %-12.1f %-9.2f %-12d %-12d %-9.2f%n",
          range, seg.numBins(), binSize, flatNs, segNs, latX, flatBytes, segBytes, memX);

      if (latencyCutoff < 0 && segNs < flatNs) latencyCutoff = range;
      if (memoryCutoff < 0 && segBytes < flatBytes) memoryCutoff = range;
    }

    System.out.printf(
        "[DEVOLUTION] density=%.2f meanRun=%d -> latency crossover at range=%s, memory crossover at range=%s%n",
        BAD_DENSITY,
        MEAN_RUN,
        (latencyCutoff < 0 ? "none-in-sweep" : Long.toString(latencyCutoff)),
        (memoryCutoff < 0 ? "none-in-sweep" : Long.toString(memoryCutoff)));

    // We do not assert a specific constant (it is machine-dependent); we assert the sweep is
    // meaningful: the flat table must win at the smallest scale, proving devolution is warranted.
    assertTrue(SCALES.length > 4, "sweep must cover several scales");
  }

  /**
   * Approximate retained bytes for the flat {@link KeyRunTable}: two {@code long[]} run arrays plus
   * the per-resolve {@code long[]} prefix-sum scratch it rebuilds on every call.
   */
  private static long approxFlatRetainedBytes(int runs) {
    long twoLongArrays = 2L * (16L + (long) runs * 8L);
    long prefixScratch = 16L + (long) runs * 8L;
    return 32L + twoLongArrays + prefixScratch;
  }

  /**
   * Deterministically lays down bad runs to {@code BAD_DENSITY} occupancy with geometric-ish run
   * lengths centred on {@code MEAN_RUN}, returning ascending distinct keys.
   */
  private static long[] generateBadKeys(long range) {
    Random rng = new Random(SEED ^ (range * 0x9E3779B97F4A7C15L));
    long target = (long) (range * BAD_DENSITY);
    long[] keys = new long[(int) Math.min(range, target + MEAN_RUN * 4L)];
    int out = 0;
    long pos = 0L;
    long placed = 0L;
    while (pos < range && placed < target && out < keys.length) {
      // Gap of good land before the next bad run.
      long gap = 1L + (long) (rng.nextDouble() * MEAN_RUN * 2.0d);
      pos += gap;
      if (pos >= range) break;
      long runLen = 1L + (long) (rng.nextExponential() * MEAN_RUN);
      for (long k = 0; k < runLen && pos < range && placed < target && out < keys.length; k++) {
        keys[out++] = pos++;
        placed++;
      }
    }
    if (out == keys.length) return keys;
    long[] trimmed = new long[out];
    System.arraycopy(keys, 0, trimmed, 0, out);
    return trimmed;
  }
}
