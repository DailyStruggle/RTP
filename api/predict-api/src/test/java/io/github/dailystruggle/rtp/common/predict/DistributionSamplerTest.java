package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit verification for {@link DistributionSampler}.
 *
 * @governedBy ADR-001
 * @governedBy ADR-009
 * @governedBy ADR-115
 */
@DisplayName("REQ-PRED-F-011: DistributionSampler Power-Law, Truncated Normal, and Seed Mixer")
class DistributionSamplerTest {

    @Test
    @DisplayName("samplePowerLaw matches analytical moments E[u^alpha] = 1 / (1 + alpha) and guards edge inputs")
    void samplePowerLawMomentsAndGuards() {
        assertEquals(0.0, DistributionSampler.samplePowerLaw(0.0, 2.0), 1e-12);
        assertEquals(0.25, DistributionSampler.samplePowerLaw(0.5, 2.0), 1e-12);
        assertEquals(1.0, DistributionSampler.samplePowerLaw(1.0, 2.0), 1e-12);
        assertEquals(0.5, DistributionSampler.samplePowerLaw(0.5, 1.0), 1e-12);
        assertEquals(0.5, DistributionSampler.samplePowerLaw(0.5, 0.0), 1e-12);
        assertEquals(0.5, DistributionSampler.samplePowerLaw(0.5, -3.0), 1e-12);
        assertEquals(0.5, DistributionSampler.samplePowerLaw(0.5, Double.NaN), 1e-12);

        Random rng = new Random(42L);
        int n = 20_000;
        double alpha = 3.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += DistributionSampler.samplePowerLaw(rng.nextDouble(), alpha);
        }
        double sampleMean = sum / n;
        double analyticalMean = 1.0 / (1.0 + alpha); // 0.25
        assertEquals(analyticalMean, sampleMean, 0.01);

        double tlrSample = DistributionSampler.samplePowerLaw(2.0);
        assertTrue(tlrSample >= 0.0 && tlrSample <= 1.0);
    }

    @Test
    @DisplayName("samplePowerLawIndex stays strictly within [0, range) and respects weight bias")
    void samplePowerLawIndexBoundsAndBias() {
        assertEquals(0L, DistributionSampler.samplePowerLawIndex(0L, 2.0, () -> 0.5));
        assertEquals(0L, DistributionSampler.samplePowerLawIndex(-10L, 2.0, () -> 0.5));
        assertEquals(0L, DistributionSampler.samplePowerLawIndex(100L, 2.0, () -> 0.0));
        assertEquals(99L, DistributionSampler.samplePowerLawIndex(100L, 2.0, () -> 1.0));
        assertEquals(25L, DistributionSampler.samplePowerLawIndex(100L, 2.0, () -> 0.5));

        long tlrIndex = DistributionSampler.samplePowerLawIndex(50L, 1.5);
        assertTrue(tlrIndex >= 0L && tlrIndex < 50L);
    }

    @Test
    @DisplayName("radialAreaExponent maps inner/outer radius ratio to [0.5, 1.0]")
    void radialAreaExponentBounds() {
        assertEquals(0.5, DistributionSampler.radialAreaExponent(0.0, 256.0), 1e-12);
        assertEquals(0.625, DistributionSampler.radialAreaExponent(64.0, 256.0), 1e-12);
        assertEquals(1.0, DistributionSampler.radialAreaExponent(256.0, 256.0), 1e-12);
        assertEquals(0.5, DistributionSampler.radialAreaExponent(64.0, 0.0), 1e-12);
        assertEquals(0.5, DistributionSampler.radialAreaExponent(Double.NaN, 256.0), 1e-12);
    }

    @Test
    @DisplayName("sampleTruncatedNormal matches NormalMemoryShape moments, boundary folding, and bounded fallback")
    void sampleTruncatedNormalMomentsAndFolding() {
        Random rng = new Random(20260913L);
        int samples = 20_000;
        double sum = 0.0;
        for (int i = 0; i < samples; i++) {
            double v = DistributionSampler.sampleTruncatedNormal(0.5, 1.0, 1.0, rng::nextGaussian, rng::nextDouble);
            assertTrue(v >= 0.0 && v <= 1.0, "Sample must be in [0, 1]");
            sum += v;
        }
        double mean = sum / samples;
        assertEquals(0.5, mean, 0.01, "Unadjusted truncated normal mean should match 0.5 within 0.01");

        // Low-mean (< 0.05) negative draw folds to positive
        double foldedLow = DistributionSampler.sampleTruncatedNormal(
                0.02, 1.0, 1.0, () -> -0.8, () -> 0.5); // -0.8 / 8 * 1 + 0.02 = -0.08 -> folded to +0.08
        assertEquals(0.08, foldedLow, 1e-12);

        // Bounded rejection cap falls back to uniform01 when gaussianSource always overflows
        AtomicInteger gaussianCalls = new AtomicInteger();
        double fallback = DistributionSampler.sampleTruncatedNormal(
                0.5,
                1.0,
                0.5,
                () -> {
                    gaussianCalls.incrementAndGet();
                    return 100.0;
                },
                () -> 0.5);
        assertEquals(DistributionSampler.MAX_REJECTION_ATTEMPTS, gaussianCalls.get());
        assertEquals(0.25, fallback, 1e-12); // 0.5^(1 / 0.5) = 0.25

        double tlrNormal = DistributionSampler.sampleTruncatedNormal(0.5, 1.0, 0.625);
        assertTrue(tlrNormal >= 0.0 && tlrNormal <= 1.0);
    }

    @Test
    @DisplayName("variantSeed is deterministic, collision-free across variants, and exhibits avalanche bit diffusion")
    void variantSeedDeterminismAndAvalanche() {
        long s1 = DistributionSampler.variantSeed("oak_forest", 0L);
        long s2 = DistributionSampler.variantSeed("oak_forest", 0L);
        assertEquals(s1, s2, "variantSeed must be deterministic for identical inputs");

        long sNext = DistributionSampler.variantSeed("oak_forest", 1L);
        long sOtherKey = DistributionSampler.variantSeed("birch_forest", 0L);
        long sNullKey = DistributionSampler.variantSeed(null, 0L);
        assertNotEquals(s1, sNext);
        assertNotEquals(s1, sOtherKey);
        assertNotEquals(s1, sNullKey);

        Set<Long> unique = new HashSet<>();
        int totalBitFlips = 0;
        long prev = DistributionSampler.variantSeed("canopy", 0L);
        unique.add(prev);
        for (int i = 1; i <= 256; i++) {
            long cur = DistributionSampler.variantSeed("canopy", i);
            assertTrue(unique.add(cur), "Seeds across 256 sequential indices must be unique");
            totalBitFlips += Long.bitCount(prev ^ cur);
            prev = cur;
        }
        double avgHammingDistance = totalBitFlips / 256.0;
        assertTrue(
                avgHammingDistance >= 24.0 && avgHammingDistance <= 40.0,
                "Expected ~32-bit avalanche Hamming distance, got " + avgHammingDistance);
    }
}
