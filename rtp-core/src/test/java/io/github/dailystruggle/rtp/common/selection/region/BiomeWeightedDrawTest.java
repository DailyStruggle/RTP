package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the ADR-062 biome-probability weighted draw
 * ({@link PregenTask#drawWeightedBiome(List)}).
 *
 * <p>The legacy biome-recall draw flattens every recorded run across the requested
 * biomes and picks one uniformly, so a common biome occupying many runs drowns out
 * a rare biome occupying few. ADR-062's weighted draw instead picks the target
 * biome with equal probability among those present in memory, then a run within it
 * weighted by width. These tests pin that equal-per-biome behaviour and the
 * within-biome width weighting, while staying bounded (a finite weighted pick, no
 * reroll).
 */
class BiomeWeightedDrawTest {

  /** {@code {keys, widths}} parallel-array pair for one biome. */
  private static long[][] biome(long[] keys, long[] widths) {
    return new long[][] {keys, widths};
  }

  @Test
  @DisplayName("a rare biome is steered toward roughly equally with a common biome")
  void rareBiomeGetsEqualShare() {
    // Common biome: 1000 single-wide runs at locations 0..999.
    long[] commonKeys = new long[1000];
    long[] commonWidths = new long[1000];
    for (int k = 0; k < 1000; k++) {
      commonKeys[k] = k;
      commonWidths[k] = 1L;
    }
    // Rare biome: a single run far away at location 1_000_000 (width 1).
    long[] rareKeys = {1_000_000L};
    long[] rareWidths = {1L};

    List<long[][]> perBiome = new ArrayList<>();
    perBiome.add(biome(commonKeys, commonWidths));
    perBiome.add(biome(rareKeys, rareWidths));

    int trials = 20_000;
    int rare = 0;
    for (int t = 0; t < trials; t++) {
      long l = PregenTask.drawWeightedBiome(perBiome);
      if (l >= 1_000_000L) {
        rare++;
      }
    }

    double rareFraction = rare / (double) trials;
    // Equal-per-biome would give ~0.5; the legacy uniform-over-runs draw would give
    // ~1/1001 (< 0.001). Assert we are clearly in the equal-per-biome regime.
    assertTrue(
        rareFraction > 0.4 && rareFraction < 0.6,
        "rare-biome share should be ~0.5 under equal-per-biome weighting, was " + rareFraction);
  }

  @Test
  @DisplayName("within a chosen biome, wider runs are picked proportionally more often")
  void widthWeightedWithinBiome() {
    // Single biome with two runs: a narrow run (width 1) at 0 and a wide run
    // (width 999) at 1000. Picks within the biome are width-weighted, so ~99.9%
    // should land in the wide run's [1000, 1999) range.
    long[] keys = {0L, 1000L};
    long[] widths = {1L, 999L};
    List<long[][]> perBiome = new ArrayList<>();
    perBiome.add(biome(keys, widths));

    int trials = 20_000;
    int wide = 0;
    for (int t = 0; t < trials; t++) {
      long l = PregenTask.drawWeightedBiome(perBiome);
      if (l >= 1000L) {
        wide++;
      }
    }
    double wideFraction = wide / (double) trials;
    assertTrue(
        wideFraction > 0.95,
        "wide run should dominate width-weighted within-biome draw, was " + wideFraction);
  }

  @Test
  @DisplayName("ADR-062 Phase 2: configured per-biome weights bias the biome pick proportionally")
  void configuredWeightsBiasBiomePick() {
    // Two biomes, each a single width-1 run. Biome A at 0, biome B at 1_000_000.
    long[] aKeys = {0L};
    long[] aWidths = {1L};
    long[] bKeys = {1_000_000L};
    long[] bWidths = {1L};
    List<long[][]> perBiome = new ArrayList<>();
    perBiome.add(biome(aKeys, aWidths));
    perBiome.add(biome(bKeys, bWidths));
    // Weight B 3x relative to A -> B should be picked ~75% of the time.
    double[] weights = {1.0d, 3.0d};

    int trials = 20_000;
    int b = 0;
    for (int t = 0; t < trials; t++) {
      if (PregenTask.drawWeightedBiome(perBiome, weights) >= 1_000_000L) {
        b++;
      }
    }
    double bFraction = b / (double) trials;
    assertTrue(
        bFraction > 0.70 && bFraction < 0.80,
        "weight-3 biome should be picked ~0.75 of the time, was " + bFraction);
  }

  @Test
  @DisplayName("ADR-062 Phase 2: a zero weight suppresses a biome entirely")
  void zeroWeightSuppressesBiome() {
    long[] aKeys = {0L};
    long[] aWidths = {1L};
    long[] bKeys = {1_000_000L};
    long[] bWidths = {1L};
    List<long[][]> perBiome = new ArrayList<>();
    perBiome.add(biome(aKeys, aWidths));
    perBiome.add(biome(bKeys, bWidths));
    double[] weights = {1.0d, 0.0d};

    for (int t = 0; t < 5_000; t++) {
      assertTrue(
          PregenTask.drawWeightedBiome(perBiome, weights) < 1_000_000L,
          "zero-weight biome must never be selected");
    }
  }

  @Test
  @DisplayName("ADR-062 Phase 2: all-zero weights fall back to an equal-probability pick")
  void allZeroWeightsFallBackToEqual() {
    long[] aKeys = {0L};
    long[] aWidths = {1L};
    long[] bKeys = {1_000_000L};
    long[] bWidths = {1L};
    List<long[][]> perBiome = new ArrayList<>();
    perBiome.add(biome(aKeys, aWidths));
    perBiome.add(biome(bKeys, bWidths));
    double[] weights = {0.0d, 0.0d};

    int trials = 20_000;
    int b = 0;
    for (int t = 0; t < trials; t++) {
      if (PregenTask.drawWeightedBiome(perBiome, weights) >= 1_000_000L) {
        b++;
      }
    }
    double bFraction = b / (double) trials;
    assertTrue(
        bFraction > 0.4 && bFraction < 0.6,
        "all-zero weights should fall back to equal pick (~0.5), was " + bFraction);
  }

  @Test
  @DisplayName("a single zero-width run still resolves to its key (degenerate fallback)")
  void degenerateZeroWidth() {
    long[] keys = {4242L};
    long[] widths = {0L};
    List<long[][]> perBiome = new ArrayList<>();
    perBiome.add(biome(keys, widths));
    assertEquals(4242L, PregenTask.drawWeightedBiome(perBiome));
  }

  // --- ADR-062 Phase 3: gray-space deferral / exploration probability ---

  @Test
  @DisplayName("Phase 3: grayFraction ramps from 1.0 (unrecorded) to 0.0 (well-recorded)")
  void grayFractionRamp() {
    long min = PregenTask.GRAY_SPACE_MIN_RUNS;
    assertEquals(0.0d, PregenTask.grayFraction(min, min), 1e-9, "at threshold -> fully recall");
    assertEquals(0.0d, PregenTask.grayFraction(min + 5, min), 1e-9, "above threshold -> fully recall");
    assertEquals(1.0d, PregenTask.grayFraction(0L, min), 1e-9, "unrecorded -> fully gray");
    // A single recorded run defers most of its weight to exploration.
    assertEquals((double) (min - 1) / (double) min, PregenTask.grayFraction(1L, min), 1e-9);
    // A disabled threshold never defers.
    assertEquals(0.0d, PregenTask.grayFraction(1L, 0L), 1e-9);
  }

  @Test
  @DisplayName("Phase 3: graySpaceProbability is the gray share of total weight")
  void graySpaceProbabilityShares() {
    assertEquals(0.0d, PregenTask.graySpaceProbability(10.0d, 0.0d), 1e-9, "no gray -> never explore");
    assertEquals(0.5d, PregenTask.graySpaceProbability(1.0d, 1.0d), 1e-9, "equal -> 50/50");
    assertEquals(1.0d, PregenTask.graySpaceProbability(0.0d, 5.0d), 1e-9, "all gray -> always explore");
    // Higher gray weight (e.g. a heavily-weighted unrecorded biome) raises the
    // exploration probability, so a weight cannot amplify single-run clustering.
    double low = PregenTask.graySpaceProbability(1.0d, 1.0d);
    double high = PregenTask.graySpaceProbability(1.0d, 5.0d);
    assertTrue(high > low, "more gray weight must increase exploration probability");
  }
}
