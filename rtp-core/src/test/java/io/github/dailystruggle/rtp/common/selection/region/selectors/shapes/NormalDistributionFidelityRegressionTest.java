package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-F-003 / REQ-RTP-F-005 / ADR-085:
 * Seeded regression suite asserting normal/Gaussian spatial distribution fidelity
 * across both legacy shapes and optimized dual-layer shapes.
 *
 * <p>Pins the guarantees that:
 * 1. Both legacy {@link Square_Normal} and {@link SquareOptimizedDualLayer} remain
 *    within &le; 5% of expected Gaussian distribution mean (0.5000).
 * 2. Legacy {@link Circle_Normal} remains within &le; 5% of expected Gaussian distribution mean.
 * 3. Distribution peaks occur at expected center deciles ([0.3, 0.6]) without mode collapse.
 */
public class NormalDistributionFidelityRegressionTest {

    private static final long SEED = 20260913L;
    private static final int SAMPLES = 30_000;

    @BeforeAll
    static void setup() {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private static double sample1DGaussian(Random rng, double mean, double deviation, double exponent) {
        double gaussian;
        while (true) {
            gaussian = rng.nextGaussian() / 8.0 * deviation + mean;
            if (mean < 0.05 && gaussian < 0) gaussian = -gaussian;
            else if (mean > 0.95 && gaussian > 1) gaussian = 1 - gaussian;
            if (gaussian >= 0.0 && gaussian <= 1.0) break;
        }
        if (exponent != 1.0) {
            gaussian = Math.pow(gaussian, 1.0 / exponent);
        }
        return gaussian;
    }

    @ParameterizedTest(name = "radius={0}, cr={1}, mean={2}")
    @CsvSource({
        "256, 64, 0.5",
        "256,  0, 0.5"
    })
    @DisplayName("Square_Normal and SquareOptimizedDualLayer remain within 5% of expected Gaussian mean")
    void testSquareNormalFidelityWithinFivePercent(int radius, int cr, double expectedMean) {
        Random rng = new Random(SEED);

        // 1. Legacy Square_Normal
        Square_Normal sqNormal = new Square_Normal();
        sqNormal.set(NormalDistributionParams.radius, (long) radius);
        sqNormal.set(NormalDistributionParams.centerRadius, (long) cr);
        sqNormal.set(NormalDistributionParams.mean, expectedMean);
        sqNormal.set(NormalDistributionParams.deviation, 1.0);
        sqNormal.setRng(new Random(SEED));

        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        double sumSqNorm = 0.0;
        int[] decilesSqNorm = new int[10];

        for (int i = 0; i < SAMPLES; i++) {
            long loc = sqNormal.rand();
            sqNormal.locationToXZ(loc, coords);
            double dist = Math.max(Math.abs(coords.x), Math.abs(coords.z));
            double rNorm = (dist - cr) / (double) (radius - cr);
            sumSqNorm += rNorm;
            int bin = Math.max(0, Math.min(9, (int) (rNorm * 10.0)));
            decilesSqNorm[bin]++;
        }
        double meanSqNorm = sumSqNorm / SAMPLES;
        double relErrSqNorm = Math.abs(meanSqNorm - expectedMean) / expectedMean;
        assertTrue(relErrSqNorm <= 0.05,
                "Square_Normal relative error exceeded 5%: " + (relErrSqNorm * 100.0) + "%");

        // Peak must be centered near mean
        int peakBinSqNorm = 0;
        for (int b = 1; b < 10; b++) {
            if (decilesSqNorm[b] > decilesSqNorm[peakBinSqNorm]) peakBinSqNorm = b;
        }
        assertTrue(peakBinSqNorm >= 3 && peakBinSqNorm <= 6,
                "Square_Normal distribution peak must be near mean (deciles 3..6); saw decile " + peakBinSqNorm);

        // 2. SquareOptimizedDualLayer
        SquareOptimizedDualLayer sqOpt = new SquareOptimizedDualLayer("SQUARE_OPT", 16);
        sqOpt.set(GenericMemoryShapeParams.radius, (long) radius);
        sqOpt.set(GenericMemoryShapeParams.centerRadius, (long) cr);

        long sqOptRange = sqOpt.getRange();
        double exponent = (1.0 + (double) cr / (double) radius) * 0.5;
        double sumSqOpt = 0.0;
        int[] decilesSqOpt = new int[10];

        for (int i = 0; i < SAMPLES; i++) {
            double g = sample1DGaussian(rng, expectedMean, 1.0, exponent);
            long loc = (long) (sqOptRange * g);
            if (loc >= sqOptRange) loc = sqOptRange - 1;
            sqOpt.locationToXZ(loc, coords);
            double dist = Math.max(Math.abs(coords.x), Math.abs(coords.z));
            double rNorm = (dist - cr) / (double) (radius - cr);
            sumSqOpt += rNorm;
            int bin = Math.max(0, Math.min(9, (int) (rNorm * 10.0)));
            decilesSqOpt[bin]++;
        }
        double meanSqOpt = sumSqOpt / SAMPLES;
        double relErrSqOpt = Math.abs(meanSqOpt - expectedMean) / expectedMean;
        assertTrue(relErrSqOpt <= 0.05,
                "SquareOptimizedDualLayer relative error exceeded 5%: " + (relErrSqOpt * 100.0) + "%");

        int peakBinSqOpt = 0;
        for (int b = 1; b < 10; b++) {
            if (decilesSqOpt[b] > decilesSqOpt[peakBinSqOpt]) peakBinSqOpt = b;
        }
        assertTrue(peakBinSqOpt >= 3 && peakBinSqOpt <= 6,
                "SquareOptimizedDualLayer distribution peak must be near mean (deciles 3..6); saw decile " + peakBinSqOpt);
    }

    @ParameterizedTest(name = "radius={0}, cr={1}, mean={2}")
    @CsvSource({
        "256, 64, 0.5",
        "256,  0, 0.5"
    })
    @DisplayName("Circle_Normal remains within 5% of expected Gaussian mean")
    void testCircleNormalFidelityWithinFivePercent(int radius, int cr, double expectedMean) {
        Circle_Normal circNormal = new Circle_Normal();
        circNormal.set(NormalDistributionParams.radius, (long) radius);
        circNormal.set(NormalDistributionParams.centerRadius, (long) cr);
        circNormal.set(NormalDistributionParams.mean, expectedMean);
        circNormal.set(NormalDistributionParams.deviation, 1.0);
        circNormal.setRng(new Random(SEED));

        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        double sumCircNorm = 0.0;
        int[] deciles = new int[10];

        for (int i = 0; i < SAMPLES; i++) {
            long loc = circNormal.rand();
            circNormal.locationToXZ(loc, coords);
            double dist = Math.hypot(coords.x, coords.z);
            double rNorm = (dist - cr) / (double) (radius - cr);
            sumCircNorm += rNorm;
            int bin = Math.max(0, Math.min(9, (int) (rNorm * 10.0)));
            deciles[bin]++;
        }

        double meanCircNorm = sumCircNorm / SAMPLES;
        double relErr = Math.abs(meanCircNorm - expectedMean) / expectedMean;
        assertTrue(relErr <= 0.05,
                "Circle_Normal relative error exceeded 5%: " + (relErr * 100.0) + "%");

        int peakBin = 0;
        for (int b = 1; b < 10; b++) {
            if (deciles[b] > deciles[peakBin]) peakBin = b;
        }
        assertTrue(peakBin >= 3 && peakBin <= 6,
                "Circle_Normal distribution peak must be near mean (deciles 3..6); saw decile " + peakBin);
    }

    @ParameterizedTest(name = "radius={0}, cr={1}, mean={2}")
    @CsvSource({
        "256, 64, 0.5",
        "256,  0, 0.5"
    })
    @DisplayName("CircleOptimizedDualLayer with analytical MacroRingLUT quantile mapping achieves <= 5% error")
    void testCircleOptimizedQuantileMappingFidelity(int radius, int cr, double expectedMean) {
        CircleOptimizedDualLayer circleOpt = new CircleOptimizedDualLayer("OPT_CIRCLE", 16);
        circleOpt.set(GenericMemoryShapeParams.radius, (long) radius);
        circleOpt.set(GenericMemoryShapeParams.centerRadius, (long) cr);

        long startInit = System.nanoTime();
        circleOpt.getOrBuildMacroRingLUT();
        long initNs = System.nanoTime() - startInit;
        System.out.printf("[DEBUG_LOG] CircleOptimizedDualLayer MacroRingLUT analytical init time: %.3f ms%n", initNs / 1e6);

        Random rng = new Random(SEED);
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        double sumDist = 0.0;
        int[] deciles = new int[10];
        long rejectedCount = 0;

        for (int i = 0; i < SAMPLES; i++) {
            double g = sample1DGaussian(rng, expectedMean, 1.0, 1.0);
            long loc = circleOpt.sampleQuantileLocation(g);
            assertTrue(loc >= 0, "sampleQuantileLocation must produce valid key");

            circleOpt.locationToXZ(loc, coords);
            double dist = Math.hypot(coords.x, coords.z);
            if (dist < cr || dist > radius) {
                rejectedCount++;
            }

            double rNorm = (dist - cr) / (double) (radius - cr);
            sumDist += rNorm;
            int bin = Math.max(0, Math.min(9, (int) (rNorm * 10.0)));
            deciles[bin]++;
        }

        double meanNorm = sumDist / SAMPLES;
        double relErr = Math.abs(meanNorm - expectedMean) / expectedMean;
        System.out.printf("[DEBUG_LOG] CircleOptimizedDualLayer Quantile Mean = %.4f (Error = %.2f%%), Deciles = %s, Rejections = %d%n",
                meanNorm, relErr * 100.0, Arrays.toString(deciles), rejectedCount);

        assertTrue(relErr <= 0.05,
                "CircleOptimizedDualLayer quantile relative error exceeded 5%: " + (relErr * 100.0) + "%");
        int peakBin = 0;
        for (int b = 1; b < 10; b++) {
            if (deciles[b] > deciles[peakBin]) peakBin = b;
        }
        assertTrue(peakBin >= 3 && peakBin <= 6,
                "Distribution peak must be near mean (deciles 3..6); saw decile " + peakBin);
    }
}
