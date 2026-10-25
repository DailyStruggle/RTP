package io.github.dailystruggle.rtp.common.predict;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Deterministic and {@link ThreadLocalRandom}-backed continuous distribution samplers over
 * {@code [0.0, 1.0)} and integer index ranges {@code [0, range)}.
 *
 * <p>Provides:
 * <ul>
 *   <li>Power-law radial/weight-biased sampling ({@link #samplePowerLaw(double, double)},
 *       {@link #samplePowerLawIndex(long, double, DoubleSupplier)}).</li>
 *   <li>Two-sided rejection-folded truncated Gaussian sampling with radial area-exponent
 *       correction ({@link #sampleTruncatedNormal(double, double, double, DoubleSupplier, DoubleSupplier)}),
 *       matching {@code NormalMemoryShape.sample} (ADR-001, ADR-009).</li>
 *   <li>Deterministic 64-bit FNV-1a + SplitMix64 seed derivation
 *       ({@link #variantSeed(String, long)}).</li>
 * </ul>
 *
 * <p>Zero external dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002, ADR-115).
 */
public final class DistributionSampler {

    /**
     * Upper bound on Gaussian rejection attempts before falling back to the uniform source,
     * preserving the bounded-iteration invariant (ADR-001).
     */
    public static final int MAX_REJECTION_ATTEMPTS = 64;

    private static final long FNV1A_64_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV1A_64_PRIME = 0x00000100000001B3L;
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long SPLITMIX_MUL_1 = 0xBF58476D1CE4E5B9L;
    private static final long SPLITMIX_MUL_2 = 0x94D049BB133111EBL;

    private DistributionSampler() {
    }

    /**
     * Transforms a uniform variate {@code u in [0.0, 1.0)} via the power law {@code u^exponent}.
     *
     * <p>When {@code exponent == 1.0}, returns {@code u} directly (uniform distribution).
     * Non-finite or non-positive exponents fall back to uniform {@code clamp01(u)}.
     *
     * @param u        uniform draw in {@code [0.0, 1.0]}
     * @param exponent positive power-law weight exponent
     * @return power-law sample in {@code [0.0, 1.0]}
     */
    public static double samplePowerLaw(double u, double exponent) {
        double clamped = UnitCurve.clamp01(u);
        if (!Double.isFinite(exponent) || exponent <= 0.0 || exponent == 1.0) {
            return clamped;
        }
        return Math.pow(clamped, exponent);
    }

    /**
     * Draws a power-law sample in {@code [0.0, 1.0)} using {@link ThreadLocalRandom}.
     *
     * @param exponent positive power-law weight exponent
     * @return power-law sample in {@code [0.0, 1.0)}
     */
    public static double samplePowerLaw(double exponent) {
        return samplePowerLaw(ThreadLocalRandom.current().nextDouble(), exponent);
    }

    /**
     * Samples a discrete index in {@code [0, range)} biased by {@code u^exponent}.
     *
     * @param range     exclusive upper bound (returns {@code 0L} when {@code <= 0})
     * @param exponent  positive power-law weight exponent
     * @param uniform01 source of uniform variates in {@code [0.0, 1.0)}
     * @return sampled index in {@code [0, max(0, range - 1)]}
     */
    public static long samplePowerLawIndex(long range, double exponent, DoubleSupplier uniform01) {
        Objects.requireNonNull(uniform01, "uniform01");
        if (range <= 0L) {
            return 0L;
        }
        double sample = samplePowerLaw(uniform01.getAsDouble(), exponent);
        long index = (long) (range * sample);
        return Math.min(range - 1L, Math.max(0L, index));
    }

    /**
     * Samples a discrete index in {@code [0, range)} biased by {@code u^exponent} using
     * {@link ThreadLocalRandom}.
     *
     * @param range    exclusive upper bound
     * @param exponent positive power-law weight exponent
     * @return sampled index in {@code [0, max(0, range - 1)]}
     */
    public static long samplePowerLawIndex(long range, double exponent) {
        return samplePowerLawIndex(range, exponent, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Computes the annular 1D-to-2D radial area correction exponent
     * {@code (1.0 + innerRadius / outerRadius) * 0.5} in {@code [0.5, 1.0]}.
     *
     * @param innerRadius inner exclusion radius ({@code centerRadius})
     * @param outerRadius outer boundary radius ({@code radius})
     * @return corrective exponent in {@code [0.5, 1.0]}
     */
    public static double radialAreaExponent(double innerRadius, double outerRadius) {
        if (!Double.isFinite(innerRadius) || !Double.isFinite(outerRadius) || outerRadius <= 0.0) {
            return 0.5;
        }
        double ratio = UnitCurve.clamp01(innerRadius / outerRadius);
        return (1.0 + ratio) * 0.5;
    }

    /**
     * Draws a continuous sample in {@code [0.0, 1.0]} from a two-sided rejection-folded normal
     * distribution scaled by {@code stdDev / 8.0}, centered at {@code mean}, and raised to
     * {@code 1.0 / radialExponent}.
     *
     * <p>Preserves 100% mathematical and RNG-consumption parity with
     * {@code NormalMemoryShape.sample} while bounding rejection iterations to
     * {@link #MAX_REJECTION_ATTEMPTS} before falling back to {@code uniform01}.
     *
     * @param mean           normalized mean in {@code [0.0, 1.0)}
     * @param stdDev         relative standard deviation ({@code >= 0.0})
     * @param radialExponent 1D-to-2D area exponent (e.g. {@code (1 + cr / r) * 0.5}; {@code 1.0}
     *                       for unadjusted)
     * @param gaussianSource standard normal {@code N(0, 1)} supplier (e.g. {@code rng::nextGaussian})
     * @param uniform01      fallback uniform {@code [0, 1)} supplier if rejection cap is reached
     * @return sampled scalar in {@code [0.0, 1.0]}
     */
    public static double sampleTruncatedNormal(
            double mean,
            double stdDev,
            double radialExponent,
            DoubleSupplier gaussianSource,
            DoubleSupplier uniform01) {
        Objects.requireNonNull(gaussianSource, "gaussianSource");

        double m;
        if (!Double.isFinite(mean)) {
            m = 0.5;
        } else if (mean >= 0.0 && mean < 1.0) {
            m = mean;
        } else {
            m = Math.abs(mean) % 1.0;
        }
        double deviation = (!Double.isFinite(stdDev)) ? 1.0 : Math.abs(stdDev);

        double gaussian = Double.NaN;
        for (int attempt = 0; attempt < MAX_REJECTION_ATTEMPTS; attempt++) {
            gaussian = gaussianSource.getAsDouble();
            gaussian = gaussian / 8.0;
            gaussian = gaussian * deviation;
            gaussian = gaussian + m;

            if (m < 0.05 && gaussian < 0.0) {
                gaussian = -gaussian;
            } else if (m > 0.95 && gaussian > 1.0) {
                gaussian = 1.0 - gaussian;
            }

            if (gaussian >= 0.0 && gaussian <= 1.0) {
                break;
            }
        }

        if (!(gaussian >= 0.0 && gaussian <= 1.0)) {
            gaussian = (uniform01 != null) ? UnitCurve.clamp01(uniform01.getAsDouble()) : UnitCurve.clamp01(m);
        }

        if (Double.isFinite(radialExponent) && radialExponent > 0.0 && radialExponent != 1.0) {
            gaussian = Math.pow(gaussian, 1.0 / radialExponent);
        } else if (radialExponent == 1.0) {
            gaussian = Math.pow(gaussian, 1.0);
        }

        return gaussian;
    }

    /**
     * Draws a truncated normal sample in {@code [0.0, 1.0]} using {@link ThreadLocalRandom}.
     *
     * @param mean           normalized mean in {@code [0.0, 1.0)}
     * @param stdDev         relative standard deviation
     * @param radialExponent 1D-to-2D area exponent
     * @return sampled scalar in {@code [0.0, 1.0]}
     */
    public static double sampleTruncatedNormal(double mean, double stdDev, double radialExponent) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return sampleTruncatedNormal(mean, stdDev, radialExponent, rng::nextGaussian, rng::nextDouble);
    }

    /**
     * Derives a deterministic, well-mixed 64-bit seed from a string namespace key and a numeric
     * variant index using FNV-1a 64-bit hashing followed by SplitMix64 (Stafford variant 13)
     * avalanche mixing.
     *
     * @param namespaceKey identifier string (e.g. species, world, or region key; {@code null} treated as empty)
     * @param variantIndex variant or instance ordinal
     * @return mixed 64-bit seed suitable for initializing a deterministic PRNG
     */
    public static long variantSeed(String namespaceKey, long variantIndex) {
        long hash = FNV1A_64_OFFSET_BASIS;
        if (namespaceKey != null) {
            for (int i = 0; i < namespaceKey.length(); i++) {
                hash ^= namespaceKey.charAt(i);
                hash *= FNV1A_64_PRIME;
            }
        }
        long z = hash ^ (variantIndex * GOLDEN_GAMMA);
        z += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * SPLITMIX_MUL_1;
        z = (z ^ (z >>> 27)) * SPLITMIX_MUL_2;
        return z ^ (z >>> 31);
    }
}
