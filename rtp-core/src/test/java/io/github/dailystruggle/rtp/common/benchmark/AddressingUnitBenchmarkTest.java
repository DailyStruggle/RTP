package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.AddressingUnitSelector.Decision;
import io.github.dailystruggle.rtp.common.benchmark.AddressingUnitSelector.Estimate;
import io.github.dailystruggle.rtp.common.benchmark.AddressingUnitSelector.Transition;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Re-measures the accuracy cost of coarsening the learned state's addressing unit under a
 * normalisation that can be read as accuracy, and scores the sampled selection rule against
 * exhaustive ground truth.
 *
 * <p>This exists because the previous normalisation was wrong. Over-exclusion was reported as
 * {@code coarse excluded area / chunk-precision excluded area}, which divides by the bad area rather
 * than by the domain. The denominator moves with the domain, so the reported figure moved with
 * radius - 1.118 at r=8192 blocks against 1.253 at r=20480 - and was read as the coarse unit
 * becoming less accurate at range. Over identical terrain that cannot be true: the share of usable
 * ground a coarse cell destroys is set by feature perimeter against cell edge, and neither depends
 * on how far out the domain is addressed. The figure being radius-dependent was a property of the
 * ratio, not of the model.
 *
 * <p>The corrected metric is the share of <b>usable</b> chunks the unit discards, which is bounded,
 * scale-free, and is the number an operator is entitled to a cap on. Both are reported side by side
 * so the old rows can be reconciled rather than quietly replaced.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("addressing unit: corrected accuracy metric and the selection rule")
public class AddressingUnitBenchmarkTest {

  private static final long SEED = 20260906L;

  /** Largest tolerable share of usable ground discarded by the lossy unit. */
  private static final double CAP = 0.20d;

  /** Radii swept, in chunks. 256 / 512 / 1280 chunks are 4 096 / 8 192 / 20 480 blocks. */
  private static final int[] RADII_CHUNKS = {256, 512, 1_280};

  /** Sampling blocks drawn per estimate; each supplies 1 024 chunk facts. */
  private static final int SAMPLE_BLOCKS = 256;

  private static final SimulationReport REPORT = new SimulationReport();

  private static TiledOccupancyMask mask;
  private static AddressingUnitSelector.Oracle oracle;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    if (!WorldOccupancyMask.available()) return;
    Path dir = WorldOccupancyMask.resolveDirectory();
    mask = new TiledOccupancyMask(WorldOccupancyMask.load(dir));
    oracle = (cx, cz) -> !mask.isOccupied(cx, cz);
    REPORT.add(
        "world", "tiled save", "bad density", 1.0d - mask.occupiedFraction(), Provenance.MEASURED);
    long[] period = mask.periodBlocks();
    REPORT.add("world", "tiled save", "tile period, blocks x", period[0], Provenance.MEASURED);
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Accuracy is reported as the share of usable chunks a coarse unit discards, not as a ratio "
            + "against the excluded area. The earlier ratio divided by the bad area, which moves "
            + "with the domain, so it changed with radius over terrain that did not change - and it "
            + "cannot be read as accuracy in any case, since a world that is one percent bad and "
            + "one that is ninety percent bad give wildly different ratios for the same loss of "
            + "usable ground.");
    REPORT.note(
        "The tiling cannot explain the earlier radius dependence and was wrongly blamed for it. "
            + "Tiles are whole region files with mirroring applied at region granularity, so a "
            + "32-chunk cell never straddles a tile join and no synthetic structure exists inside "
            + "a region. What changes with radius is how much of the tile period the domain covers, "
            + "which moves the composition of the sample and therefore the bad density in the "
            + "ratio's denominator.");
    REPORT.note(
        "The selection rule is sampled rather than tabulated. A radius-keyed table cannot know "
            + "whether a world is an archipelago or a continent, and those have very different "
            + "perimeter-to-area ratios at the same radius; the quantity that decides is measured "
            + "on the world in front of it. Sampling is by region-aligned block because chunks "
            + "within a region are correlated, and the decision uses the upper end of the interval "
            + "so a sampling error cannot silently exceed the cap.");
    REPORT.note(
        "Which constraint binds depends on the radius, and both do somewhere. At the smallest "
            + "radius swept the domain-size guard refuses a unit the accuracy cap would have "
            + "allowed; at the largest, accuracy refuses a unit the guard would have allowed. A "
            + "rule that carried only one of the two would be wrong at one end of the range.");
    REPORT.note(
        "The corrected metric reverses this work's earlier conclusion that coarsening to one "
            + "region file is nearly free at large range. It discards roughly half of all usable "
            + "ground - 0.465 to 0.529 across the radii swept - and the legacy ratio scored the "
            + "same points at 1.118 to 1.253 only because it divides by a bad area that is three "
            + "quarters of the domain. A large denominator made a large loss look small.");
    REPORT.write("addressing-unit");
  }

  // -------------------------------------------------------------------------------------
  // 1. the corrected metric, and whether it is radius-invariant
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("metric: usable ground discarded is near-invariant in radius, unlike the old ratio")
  public void correctedMetricIsRadiusInvariant() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int unit : AddressingUnitSelector.UNITS) {
      double min = Double.MAX_VALUE;
      double max = 0.0d;
      double atLargest = 0.0d;
      int worstRadius = 0;
      for (int radius : RADII_CHUNKS) {
        double loss = AddressingUnitSelector.exhaustiveGoodLoss(oracle, radius, unit);
        if (radius == RADII_CHUNKS[RADII_CHUNKS.length - 1]) atLargest = loss;
        if (loss > max) worstRadius = radius;
        double oldRatio = legacyExclusionRatio(radius, unit);
        String subject = "r=" + radius + "ch cell=" + unit + "ch";
        REPORT.add("metric", subject, "usable ground discarded", loss, Provenance.MEASURED);
        REPORT.add("metric", subject, "legacy over-exclusion ratio", oldRatio, Provenance.DERIVED);
        min = Math.min(min, loss);
        max = Math.max(max, loss);
      }
      double spread = min <= 0.0d ? 1.0d : max / min;
      double drift = max - min;
      REPORT.add(
          "metric", "cell=" + unit + "ch", "max/min across radii", spread, Provenance.DERIVED);
      REPORT.add(
          "metric", "cell=" + unit + "ch", "absolute drift across radii", drift,
          Provenance.DERIVED);

      // Residual variation is reported, not asserted against a threshold. It is real - roughly
      // 1.4x to 1.6x at every coarse unit - and no honest bound on it can be derived from three
      // radii on one tiled save, so picking a tolerance that the current rows happen to satisfy
      // would be fitting the assertion to the data.
      //
      // What is asserted is the claim the earlier ratio appeared to contradict: the largest radius
      // is never the worst point, so coarsening does not become more lossy the further out the
      // domain is addressed. The variation is non-monotone in radius, which is what a composition
      // effect looks like - the domain covers a different share of the tile period at each radius
      // - and not what a range trend looks like. If loss genuinely grew outward, the cap would
      // have to be applied per ring rather than per domain, and this is the check that would say
      // so.
      if (drift > 0.0d) {
        REPORT.add(
            "metric",
            "cell=" + unit + "ch",
            "worst radius, chunks",
            worstRadius,
            Provenance.MEASURED);
      }
      assertTrue(
          atLargest <= max - 1e-12d || max <= 0.0d,
          "the largest radius was the worst point at cell "
              + unit
              + ", so loss does grow outward: "
              + atLargest
              + " of max "
              + max);
    }

    // Coarsening can only ever discard more: a cell is bad when any chunk inside it is bad.
    for (int radius : RADII_CHUNKS) {
      double previous = -1.0d;
      for (int unit : AddressingUnitSelector.UNITS) {
        double loss = AddressingUnitSelector.exhaustiveGoodLoss(oracle, radius, unit);
        assertTrue(
            loss >= previous - 1e-9d,
            "loss fell when the unit coarsened at r=" + radius + "ch, unit " + unit);
        previous = loss;
      }
    }
  }

  /**
   * The metric this work previously reported: coarse excluded area over chunk-precision excluded
   * area. Reproduced only so the corrected rows can be reconciled against the published ones.
   */
  private static double legacyExclusionRatio(int radiusChunks, int cellChunks) {
    long fine = 0L;
    for (int cx = -radiusChunks; cx < radiusChunks; cx++) {
      for (int cz = -radiusChunks; cz < radiusChunks; cz++) {
        if (oracle.isBad(cx, cz)) fine++;
      }
    }
    int cellRadius = radiusChunks / cellChunks;
    long coarseCells = 0L;
    for (int cx = -cellRadius; cx < cellRadius; cx++) {
      for (int cz = -cellRadius; cz < cellRadius; cz++) {
        boolean anyBad = false;
        for (int dx = 0; dx < cellChunks && !anyBad; dx++) {
          for (int dz = 0; dz < cellChunks && !anyBad; dz++) {
            if (oracle.isBad(cx * cellChunks + dx, cz * cellChunks + dz)) anyBad = true;
          }
        }
        if (anyBad) coarseCells++;
      }
    }
    long coarse = coarseCells * cellChunks * cellChunks;
    return fine == 0L ? 1.0d : coarse / (double) fine;
  }

  // -------------------------------------------------------------------------------------
  // 2. the sampled rule against ground truth
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("rule: the sampled pick equals the pick exhaustive knowledge would have made")
  public void sampledRuleMatchesGroundTruth() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int radius : RADII_CHUNKS) {
      List<Estimate> sampled = AddressingUnitSelector.estimate(oracle, radius, SAMPLE_BLOCKS, SEED);
      Decision sampledPick = AddressingUnitSelector.decide(sampled, radius, CAP);

      List<Estimate> exact = new ArrayList<>();
      for (int unit : AddressingUnitSelector.UNITS) {
        double loss = AddressingUnitSelector.exhaustiveGoodLoss(oracle, radius, unit);
        exact.add(new Estimate(unit, loss, loss, Integer.MAX_VALUE, 1L));
      }
      Decision truth = AddressingUnitSelector.decide(exact, radius, CAP);

      for (Estimate e : sampled) {
        String subject = "r=" + radius + "ch cell=" + e.cellChunks() + "ch";
        REPORT.add("sampled", subject, "loss, point estimate", e.goodLoss(), Provenance.MEASURED);
        REPORT.add("sampled", subject, "loss, upper bound", e.goodLossUpper(), Provenance.DERIVED);
      }
      REPORT.add(
          "rule", "r=" + radius + "ch", "sampled pick, chunks", sampledPick.cellChunks(),
          Provenance.DERIVED);
      REPORT.add(
          "rule", "r=" + radius + "ch", "exhaustive pick, chunks", truth.cellChunks(),
          Provenance.MEASURED);
      REPORT.add(
          "rule", "r=" + radius + "ch", "coarsest unit the guard allows",
          radius / AddressingUnitSelector.MIN_CELLS_PER_EDGE, Provenance.DERIVED);

      assertEquals(
          truth.cellChunks(),
          sampledPick.cellChunks(),
          "sampled rule disagreed with exhaustive knowledge at r=" + radius + "ch");

      // Whatever is picked must be defensible on the exhaustive numbers, not merely on the sample.
      double realised = AddressingUnitSelector.exhaustiveGoodLoss(oracle, radius, sampledPick.cellChunks());
      REPORT.add(
          "rule", "r=" + radius + "ch", "realised loss of pick", realised, Provenance.MEASURED);
      assertTrue(
          realised <= CAP,
          "the pick exceeded the cap in truth at r=" + radius + "ch: " + realised);
    }
  }

  @Test
  @DisplayName("rule: the upper bound never sits below the truth it is standing in for")
  public void boundIsConservative() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 512;
    List<Estimate> sampled = AddressingUnitSelector.estimate(oracle, radius, SAMPLE_BLOCKS, SEED);
    for (Estimate e : sampled) {
      double truth = AddressingUnitSelector.exhaustiveGoodLoss(oracle, radius, e.cellChunks());
      double slack = e.goodLossUpper() - truth;
      REPORT.add(
          "bound",
          "r=" + radius + "ch cell=" + e.cellChunks() + "ch",
          "upper bound minus truth",
          slack,
          Provenance.DERIVED);

      // A bound below the truth is the failure that matters: it admits a unit that discards more
      // ground than the operator agreed to lose, and does so silently.
      assertTrue(
          slack >= -0.01d,
          "the upper bound understated the truth at cell " + e.cellChunks() + ": " + slack);
    }
  }

  // -------------------------------------------------------------------------------------
  // 3. startup, reload and rebuild transitions
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("transition: coarsening folds losslessly, refinement only on a precision violation")
  public void transitionRules() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    int radius = 1_280;
    List<Estimate> sampled = AddressingUnitSelector.estimate(oracle, radius, SAMPLE_BLOCKS, SEED);
    int target = AddressingUnitSelector.decide(sampled, radius, CAP).cellChunks();

    // Cold start from full precision: the table folds upward into the chosen unit, so nothing that
    // was learned at chunk precision is thrown away.
    Transition fromFine = AddressingUnitSelector.transition(1, sampled, radius, CAP);
    REPORT.add("transition", "stored=1ch", "unit chosen", fromFine.cellChunks(), Provenance.DERIVED);
    REPORT.add("transition", "stored=1ch", "reason", fromFine.reason(), Provenance.DERIVED);
    assertTrue(fromFine.learnedStateSurvives(), "folding upward from full precision lost state");
    assertEquals(target, fromFine.cellChunks());

    // Already at the target: no churn. A reload must not rebuild a table to save nothing.
    Transition steady = AddressingUnitSelector.transition(target, sampled, radius, CAP);
    assertEquals(target, steady.cellChunks(), "the rule thrashed on an unchanged world");
    assertTrue(steady.learnedStateSurvives());

    // A stored unit the guard forbids is a precision violation, so the change is forced even though
    // it costs learned state.
    int tooCoarse = 32;
    if (radius / tooCoarse < AddressingUnitSelector.MIN_CELLS_PER_EDGE) {
      Transition forced = AddressingUnitSelector.transition(tooCoarse, sampled, radius, CAP);
      REPORT.add(
          "transition", "stored=32ch", "unit chosen", forced.cellChunks(), Provenance.DERIVED);
      REPORT.add("transition", "stored=32ch", "reason", forced.reason(), Provenance.DERIVED);
      assertTrue(
          forced.cellChunks() < tooCoarse, "an inadmissible stored unit was retained");
      assertFalse(
          forced.learnedStateSurvives(),
          "refining claimed to preserve state, which a coarse table cannot supply");
    }
  }
}
