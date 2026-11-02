package io.github.dailystruggle.rtp.common.predict;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Deterministic and {@link ThreadLocalRandom}-backed continuous and discrete distribution samplers
 * over {@code [0.0, 1.0)} and integer index ranges {@code [0, range)}.
 *
 * <p>Provides:
 * <ul>
 *   <li>Power-law radial/weight-biased sampling ({@link #samplePowerLaw(double, double)},
 *       {@link #samplePowerLawIndex(long, double, DoubleSupplier)}).</li>
 *   <li>Two-sided rejection-folded truncated Gaussian sampling with radial area-exponent
 *       correction ({@link #sampleTruncatedNormal(double, double, double, DoubleSupplier, DoubleSupplier)}),
 *       matching {@code NormalMemoryShape.sample} (ADR-001, ADR-009).</li>
 *   <li>Discrete weighted inverse-CDF index sampling ({@link #sampleWeightedIndex(int[], IntUnaryOperator)}),
 *       LCM pool weight spreading ({@link #spreadPoolWeight(int, int)}), and bounded pool weight
 *       normalization ({@link #normalizePoolWeights(int[], int)}).</li>
 *   <li>Deterministic 64-bit FNV-1a + SplitMix64 seed derivation ({@link #variantSeed(String, long)})
 *       and 2D jittered structure-grid cell sampling ({@link #gridCellSample(int, int, int, long, int)}).</li>
 * </ul>
 *
 * <p>Zero external dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002, ADR-115).
 *
 * @governedBy ADR-001
 * @governedBy ADR-009
 * @governedBy ADR-115
 */
public final class DistributionSampler {

    /**
     * Upper bound on Gaussian rejection attempts before falling back to the uniform source,
     * preserving the bounded-iteration invariant (ADR-001).
     */
    public static final int MAX_REJECTION_ATTEMPTS = 64;

    /**
     * Least common multiple of {@code 1..10} ({@code 2520}), used to spread a parent definition's
     * placement weight evenly across {@code count} variants without rounding bias.
     */
    public static final int LCM_1_TO_10 = 2520;

    private static final long FNV1A_64_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV1A_64_PRIME = 0x00000100000001B3L;
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long SPLITMIX_MUL_1 = 0xBF58476D1CE4E5B9L;
    private static final long SPLITMIX_MUL_2 = 0x94D049BB133111EBL;
    private static final long GRID_CELL_PRIME_X = 341873128712L;
    private static final long GRID_CELL_PRIME_Z = 132897987541L;

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

    /**
     * Samples a discrete index in {@code [0, weights.length)} with probability proportional to
     * {@code max(0, weights[i])} using inverse-CDF roulette-wheel selection.
     *
     * @param weights       non-negative candidate weights
     * @param boundedRandom random integer generator mapping {@code bound -> [0, bound)} (e.g. {@code rng::nextInt})
     * @return selected index in {@code [0, weights.length)}, or {@code -1} if {@code weights} is
     *         {@code null}, empty, or has zero total positive weight
     */
    public static int sampleWeightedIndex(int[] weights, IntUnaryOperator boundedRandom) {
        Objects.requireNonNull(boundedRandom, "boundedRandom");
        if (weights == null || weights.length == 0) {
            return -1;
        }
        int totalWeight = 0;
        for (int w : weights) {
            if (w > 0) {
                totalWeight += w;
            }
        }
        if (totalWeight <= 0) {
            return -1;
        }
        int r = boundedRandom.applyAsInt(totalWeight);
        int current = 0;
        for (int i = 0; i < weights.length; i++) {
            int w = Math.max(0, weights[i]);
            current += w;
            if (r < current) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /**
     * Computes the per-variant pool weight {@code max(1, max(1, weight) * 2520 / max(1, variantCount))}
     * so that {@code variantCount} variants together carry total weight proportional to
     * {@code weight} regardless of how many variants are baked.
     *
     * @param weight       parent definition placement weight ({@code >= 1})
     * @param variantCount number of variants splitting the parent weight ({@code >= 1})
     * @return scaled per-variant weight {@code >= 1}
     */
    public static int spreadPoolWeight(int weight, int variantCount) {
        return Math.max(1, Math.max(1, weight) * LCM_1_TO_10 / Math.max(1, variantCount));
    }

    /**
     * Scales an array of positive pool weights proportionally so the maximum weight fits within
     * {@code [1, maxWeight]} while clamping every entry to {@code >= 1}.
     *
     * @param weights   raw pool weights (may be {@code null} or empty)
     * @param maxWeight inclusive upper bound (e.g. {@code 150} for Minecraft template pools)
     * @return normalized weights in {@code [1, max(1, maxWeight)]}
     */
    public static int[] normalizePoolWeights(int[] weights, int maxWeight) {
        if (weights == null || weights.length == 0) {
            return new int[0];
        }
        int cap = Math.max(1, maxWeight);
        int highest = 1;
        for (int w : weights) {
            if (w > highest) {
                highest = w;
            }
        }
        int[] out = new int[weights.length];
        if (highest <= cap) {
            for (int i = 0; i < weights.length; i++) {
                out[i] = Math.max(1, weights[i]);
            }
            return out;
        }
        double scale = (double) cap / highest;
        for (int i = 0; i < weights.length; i++) {
            int scaled = (int) Math.round(weights[i] * scale);
            out[i] = Math.min(cap, Math.max(1, scaled));
        }
        return out;
    }

    /**
     * Computes the deterministic jittered target chunk coordinates {@code [targetChunkX, targetChunkZ]}
     * for the structure-grid cell of size {@code spacing} containing {@code (chunkX, chunkZ)}.
     *
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param spacing   grid cell spacing in chunks (clamped to {@code >= 1})
     * @param worldSeed world seed
     * @param salt      per-structure or per-species salt
     * @return two-element array {@code [targetChunkX, targetChunkZ]} inside the grid cell
     */
    public static int[] gridCellSample(int chunkX, int chunkZ, int spacing, long worldSeed, int salt) {
        int s = Math.max(1, spacing);
        int cellX = Math.floorDiv(chunkX, s);
        int cellZ = Math.floorDiv(chunkZ, s);
        long cellSeed = worldSeed + cellX * GRID_CELL_PRIME_X + cellZ * GRID_CELL_PRIME_Z + salt;
        long mixX = mix64(cellSeed + GOLDEN_GAMMA);
        long mixZ = mix64(cellSeed + (GOLDEN_GAMMA << 1));
        int jitterX = (int) Long.remainderUnsigned(mixX, s);
        int jitterZ = (int) Long.remainderUnsigned(mixZ, s);
        return new int[] {
            cellX * s + jitterX,
            cellZ * s + jitterZ
        };
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * SPLITMIX_MUL_1;
        z = (z ^ (z >>> 27)) * SPLITMIX_MUL_2;
        return z ^ (z >>> 31);
    }
}
