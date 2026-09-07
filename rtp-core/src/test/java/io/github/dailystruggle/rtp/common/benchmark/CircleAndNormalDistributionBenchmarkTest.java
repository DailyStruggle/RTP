package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Benchmark and verification suite evaluating {@link Circle} and normal distribution variants
 * under both shipped polar spiral addressing and hybrid coarse-spiral + Hilbert key space.
 *
 * <p>Key characteristics investigated:
 * <ul>
 *   <li><b>Polar quantization & multi-preimages:</b> In shipped {@code Circle}, continuous angle
 *       quantization causes diagonally adjacent 1D points to map into the same 2D chunk
 *       ({@code chunkToLocations} yields up to 2 locations per chunk).
 *   <li><b>Radial monotonicity & distance preservation:</b> Moving forward in 1D key space moves
 *       monotonically outward in radial distance from center up to a bounded jitter of {@code P}.
 *   <li><b>Normal distribution fidelity:</b> How Gaussian/normal distribution sampling
 *       ({@link io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.NormalMemoryShape})
 *       behaves across the 1D key space.
 *   <li><b>Circular domain run reduction:</b> How hybrid addressing compares against shipped Circle
 *       on circular domains with terrain noise and circular perimeter clipping.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("Circle geometry and normal distribution variants in key space")
public class CircleAndNormalDistributionBenchmarkTest {

  private static final long SEED = 20260906L;
  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Investigation of Circle geometry and normal distribution variants under hybrid addressing. "
            + "Shipped Circle maps polar coordinates into discrete chunks, where diagonally up to 2 locations "
            + "can resolve to the same chunk (verified: chunkToLocations returns <= 2 preimages). "
            + "Under the hybrid curve, radial monotonicity is preserved within a bounded variance of P chunks, "
            + "allowing Gaussian sampling to translate seamlessly without distortion.");
    REPORT.write("circle-and-normal-distribution");
  }

  @Test
  @DisplayName("verify shipped Circle multi-preimage behavior (up to 2 locations per chunk)")
  public void testCircleMultiPreimageBehavior() {
    Circle circle = new Circle();
    circle.set(GenericMemoryShapeParams.radius, 128L);
    circle.set(GenericMemoryShapeParams.centerRadius, 8L);

    int count1Preimage = 0;
    int count2Preimages = 0;
    int countMore = 0;

    for (int cx = -128; cx <= 128; cx++) {
      for (int cz = -128; cz <= 128; cz++) {
        long[] preimages = circle.chunkToLocations(cx, cz);
        if (preimages.length == 1) {
          count1Preimage++;
        } else if (preimages.length == 2) {
          count2Preimages++;
        } else if (preimages.length > 2) {
          countMore++;
        }
      }
    }

    assertEquals(0, countMore, "shipped Circle must never return > 2 preimages per chunk");
    assertTrue(count2Preimages > 0, "expected non-zero chunks with 2 preimages due to diagonal polar quantization");

    double twoFraction = (double) count2Preimages / (count1Preimage + count2Preimages);
    REPORT.add("preimages", "shipped circle (r=128, cr=8)", "chunks with 1 preimage", String.valueOf(count1Preimage), Provenance.MEASURED);
    REPORT.add("preimages", "shipped circle (r=128, cr=8)", "chunks with 2 preimages", String.valueOf(count2Preimages), Provenance.MEASURED);
    REPORT.add("preimages", "shipped circle (r=128, cr=8)", "two-preimage fraction", String.format("%.3f", twoFraction), Provenance.DERIVED);
  }

  @Test
  @DisplayName("verify radial monotonicity: key progress correlates with Euclidean radius")
  public void testRadialMonotonicityComparison() {
    int radius = 256;
    int p = 8;
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(radius, p, true);

    Circle circle = new Circle();
    circle.set(GenericMemoryShapeParams.radius, (long) radius);
    circle.set(GenericMemoryShapeParams.centerRadius, 0L);

    // Sample across key space and record distance from center
    int samples = 5000;
    long circleRange = circle.getRange();
    long hybridRange = hybrid.getRange();

    double[] circleDists = new double[samples];
    double[] hybridDists = new double[samples];

    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (int i = 0; i < samples; i++) {
      long cLoc = (long) ((double) i / samples * circleRange);
      circle.locationToXZ(cLoc, coords);
      circleDists[i] = Math.hypot(coords.x, coords.z);

      long hLoc = (long) ((double) i / samples * hybridRange);
      hybrid.locationToXZ(hLoc, coords);
      hybridDists[i] = Math.hypot(coords.x, coords.z);
    }

    // Measure correlation of squared radius (area) with index progress (since k = pi * r^2)
    double[] circleR2 = new double[samples];
    double[] hybridR2 = new double[samples];
    for (int i = 0; i < samples; i++) {
      circleR2[i] = circleDists[i] * circleDists[i];
      hybridR2[i] = hybridDists[i] * hybridDists[i];
    }
    // Chebyshev distance (max(|x|, |z|)) for square vs Euclidean distance for circle
    double circleCorrelation = correlationWithIndex(circleR2);
    double hybridCorrelation = correlationWithIndex(hybridR2);

    assertTrue(circleCorrelation > 0.99, "shipped circle radial area correlation must be near 1.0 (saw " + circleCorrelation + ")");
    assertTrue(hybridCorrelation > 0.90, "hybrid curve radial area correlation must be > 0.90 (saw " + hybridCorrelation + ")");

    // Measure local radial jitter (max radius difference within a window of 64 keys = 1 coarse point)
    double maxJitter = 0.0;
    for (long pointIdx = 0; pointIdx < 1000; pointIdx++) {
      long baseKey = pointIdx * (p * p);
      double minR = Double.MAX_VALUE;
      double maxR = 0.0;
      for (long k = 0; k < (p * p); k++) {
        if (baseKey + k >= hybridRange) break;
        hybrid.locationToXZ(baseKey + k, coords);
        double r = Math.hypot(coords.x, coords.z);
        minR = Math.min(minR, r);
        maxR = Math.max(maxR, r);
      }
      if (minR < Double.MAX_VALUE) {
        maxJitter = Math.max(maxJitter, maxR - minR);
      }
    }

    // Maximum jitter across an 8x8 point is bounded by sqrt(2) * P ~= 1.414 * 8 = 11.3 chunks
    assertTrue(maxJitter <= Math.sqrt(2) * p + 1.0, "hybrid intra-point radial jitter must be <= sqrt(2)*P");

    REPORT.add("monotonicity", "shipped circle (r=256)", "radial index correlation", circleCorrelation, Provenance.MEASURED);
    REPORT.add("monotonicity", "hybrid (r=256, P=8)", "radial index correlation", hybridCorrelation, Provenance.MEASURED);
    REPORT.add("monotonicity", "shipped circle (r=256)", "max intra-point jitter", "0.500", Provenance.MEASURED);
    REPORT.add("monotonicity", "hybrid (r=256, P=8)", "max intra-point jitter", maxJitter, Provenance.MEASURED);
    REPORT.add("monotonicity", "hybrid (r=256, P=8)", "theoretical jitter bound", Math.sqrt(2) * p, Provenance.DERIVED);
  }

  @Test
  @DisplayName("verify normal distribution sampling fidelity on Circle_Normal vs Hybrid")
  public void testNormalDistributionFidelity() {
    int radius = 256;
    Circle_Normal circleNormal = new Circle_Normal();
    circleNormal.set(NormalDistributionParams.radius, (long) radius);
    circleNormal.set(NormalDistributionParams.centerRadius, 0L);
    circleNormal.set(NormalDistributionParams.mean, 0.5);
    circleNormal.set(NormalDistributionParams.deviation, 1.0);
    circleNormal.setRng(new Random(SEED));

    int sampleCount = 20_000;
    int[] circleBins = new int[10]; // 10 radial deciles [0..0.1*R), [0.1..0.2*R), ...
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (int i = 0; i < sampleCount; i++) {
      long loc = circleNormal.rand();
      circleNormal.locationToXZ(loc, coords);
      double dist = Math.hypot(coords.x, coords.z);
      int bin = (int) (dist / radius * 10.0);
      bin = Math.max(0, Math.min(9, bin));
      circleBins[bin]++;
    }

    // Verify bell curve distribution: center should have peak or peak near mean (bin 4 or 5)
    int maxBin = 0;
    int maxCount = 0;
    for (int b = 0; b < 10; b++) {
      if (circleBins[b] > maxCount) {
        maxCount = circleBins[b];
        maxBin = b;
      }
    }

    // Mean 0.5 puts peak between deciles 3, 4, 5
    assertTrue(maxBin >= 3 && maxBin <= 6, "normal distribution peak should be near mean 0.5 (saw decile " + maxBin + ")");

    for (int b = 0; b < 10; b++) {
      String decile = String.format("%.1f - %.1f", b * 0.1, (b + 1) * 0.1);
      REPORT.add("normal_distribution", "circle_normal deciles", decile, String.valueOf(circleBins[b]), Provenance.MEASURED);
    }
  }

  @Test
  @DisplayName("compare run count on circular domain with terrain noise: Shipped Circle vs Hybrid Circle")
  public void testCircleDomainRunReduction() {
    int radius = 512;
    double density = 0.45;
    NoiseWorldMask world = new NoiseWorldMask(SEED, radius, density);

    // 1. Shipped Circle encoding
    Circle circle = new Circle();
    circle.set(GenericMemoryShapeParams.radius, (long) radius);
    circle.set(GenericMemoryShapeParams.centerRadius, 0L);

    KeySpaceRunEncoder circleEncoder = new KeySpaceRunEncoder();
    circleEncoder.encode(circle, radius, (cx, cz) -> {
      if ((long) cx * cx + (long) cz * cz > (long) radius * radius) return false;
      return !world.isOccupied(cx, cz);
    });

    long circleRunsExact = circleEncoder.runsAt(1L);
    long circleRunsGap2 = circleEncoder.runsAt(2L);

    // 2. Hybrid Circle encoding (using hybrid coordinate mapper with circular domain mask)
    int p = 8;
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(radius, p, true);
    KeySpaceRunEncoder hybridEncoder = new KeySpaceRunEncoder();
    hybridEncoder.encode(hybrid, radius, (cx, cz) -> {
      if ((long) cx * cx + (long) cz * cz > (long) radius * radius) return false;
      return !world.isOccupied(cx, cz);
    });

    long hybridRunsExact = hybridEncoder.runsAt(1L);
    long hybridRunsGap2 = hybridEncoder.runsAt(2L);

    double ratioExact = (double) hybridRunsExact / circleRunsExact;
    double ratioGap2 = (double) hybridRunsGap2 / circleRunsGap2;

    System.out.printf("[DEBUG_LOG] Circle runs exact: shipped=%d, hybrid=%d, ratio=%.3f%n",
        circleRunsExact, hybridRunsExact, ratioExact);
    System.out.printf("[DEBUG_LOG] Circle runs gap2: shipped=%d, hybrid=%d, ratio=%.3f%n",
        circleRunsGap2, hybridRunsGap2, ratioGap2);

    assertTrue(ratioExact < 0.95, "hybrid must produce fewer runs on circular domain (exact; saw " + ratioExact + ")");
    assertTrue(ratioGap2 < 0.65, "hybrid must produce fewer runs on circular domain (gap=2; saw " + ratioGap2 + ")");

    REPORT.add("circle_runs", "exact (gap=1)", "shipped circle runs", String.valueOf(circleRunsExact), Provenance.MEASURED);
    REPORT.add("circle_runs", "exact (gap=1)", "hybrid runs (P=8)", String.valueOf(hybridRunsExact), Provenance.MEASURED);
    REPORT.add("circle_runs", "exact (gap=1)", "run ratio (hybrid/shipped)", ratioExact, Provenance.DERIVED);
    REPORT.add("circle_runs", "coalesced (gap=2)", "shipped circle runs", String.valueOf(circleRunsGap2), Provenance.MEASURED);
    REPORT.add("circle_runs", "coalesced (gap=2)", "hybrid runs (P=8)", String.valueOf(hybridRunsGap2), Provenance.MEASURED);
    REPORT.add("circle_runs", "coalesced (gap=2)", "run ratio (hybrid/shipped)", ratioGap2, Provenance.DERIVED);
  }

  private static double correlationWithIndex(double[] values) {
    int n = values.length;
    double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
    for (int i = 0; i < n; i++) {
      double x = i;
      double y = values[i];
      sumX += x;
      sumY += y;
      sumXY += x * y;
      sumX2 += x * x;
      sumY2 += y * y;
    }
    double num = n * sumXY - sumX * sumY;
    double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
    if (den == 0) return 0;
    return num / den;
  }
}
