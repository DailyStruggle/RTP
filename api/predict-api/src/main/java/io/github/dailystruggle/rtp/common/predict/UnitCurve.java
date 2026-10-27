package io.github.dailystruggle.rtp.common.predict;

import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Stateless, allocation-free mathematical shaping and envelope curves over the normalized unit
 * interval {@code t in [0.0, 1.0]}.
 *
 * <p>Every static evaluator clamps its input parameter {@code t} to {@code [0.0, 1.0]} (mapping
 * {@code Double.NaN} to {@code 0.0}) and guards against degenerate parameters (such as
 * {@code sigma <= 0.0}, {@code base <= 1.0}, or non-finite values) so callers never receive
 * {@code NaN} or {@code Infinity}.
 *
 * <p>Implements {@link DoubleUnaryOperator} so curve instances can be composed via
 * {@link #andThen(DoubleUnaryOperator)} and passed directly to polar/spiral evaluators such as
 * {@link PolarCurve#spiralOffset(double, double, double, double, DoubleUnaryOperator)}.
 *
 * <p>Zero external dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002, ADR-115).
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 */
@FunctionalInterface
public interface UnitCurve extends DoubleUnaryOperator {

    /**
     * Clamps {@code t} to the unit interval {@code [0.0, 1.0]}.
     * Maps {@code Double.NaN} and negative zero to {@code 0.0}.
     *
     * @param t raw normalized parameter
     * @return clamped value in {@code [0.0, 1.0]}
     */
    static double clamp01(double t) {
        if (Double.isNaN(t) || t <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, t);
    }

    /**
     * Evaluates a linear interpolation {@code start + (end - start) * clamp01(t)}.
     *
     * @param t     unit-interval parameter
     * @param start value at {@code t = 0.0}
     * @param end   value at {@code t = 1.0}
     * @return interpolated value, or {@code 0.0} if {@code start} or {@code end} is non-finite
     */
    static double linear(double t, double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end)) {
            return 0.0;
        }
        double u = clamp01(t);
        if (u <= 0.0) {
            return start;
        }
        if (u >= 1.0) {
            return end;
        }
        return start + (end - start) * u;
    }

    /**
     * Evaluates the power-law shaping curve {@code clamp01(t)^exponent}.
     *
     * @param t        unit-interval parameter
     * @param exponent positive power exponent ({@code <= 0} or non-finite returns {@code 0.0})
     * @return shaped value in {@code [0.0, 1.0]}
     */
    static double power(double t, double exponent) {
        double u = clamp01(t);
        if (!Double.isFinite(exponent) || exponent <= 0.0) {
            return 0.0;
        }
        if (exponent == 1.0 || u <= 0.0 || u >= 1.0) {
            return u;
        }
        return Math.pow(u, exponent);
    }

    /**
     * Evaluates the complementary power curve {@code 1.0 - clamp01(t)^exponent}.
     *
     * @param t        unit-interval parameter
     * @param exponent positive power exponent
     * @return complementary shaped value in {@code [0.0, 1.0]}
     */
    static double complementaryPower(double t, double exponent) {
        return 1.0 - power(t, exponent);
    }

    /**
     * Evaluates a logistic S-curve {@code 1 / (1 + exp(-steepness * (clamp01(t) - midpoint)))}.
     *
     * @param t         unit-interval parameter
     * @param midpoint  inflection point where the curve equals {@code 0.5}
     * @param steepness logistic growth rate
     * @return logistic value in {@code [0.0, 1.0]}, or {@code 0.5} if parameters are non-finite
     */
    static double sigmoid(double t, double midpoint, double steepness) {
        double u = clamp01(t);
        if (!Double.isFinite(midpoint) || !Double.isFinite(steepness)) {
            return 0.5;
        }
        double z = -steepness * (u - midpoint);
        if (z > 700.0) {
            return 0.0;
        }
        if (z < -700.0) {
            return 1.0;
        }
        return 1.0 / (1.0 + Math.exp(z));
    }

    /**
     * Evaluates the cubic Hermite smoothstep S-curve {@code 3t^2 - 2t^3} with zero derivatives at
     * {@code t = 0.0} and {@code t = 1.0}.
     *
     * @param t unit-interval parameter
     * @return smoothstep value in {@code [0.0, 1.0]}
     */
    static double smoothstep(double t) {
        double u = clamp01(t);
        return u * u * (3.0 - 2.0 * u);
    }

    /**
     * Evaluates a symmetric parabolic crown envelope {@code 1 - (2t - 1)^2} peaking at
     * {@code t = 0.5} with value {@code 1.0} and vanishing at {@code t = 0.0} and {@code t = 1.0}.
     *
     * @param t unit-interval parameter
     * @return parabolic crown value in {@code [0.0, 1.0]}
     */
    static double parabolicCrown(double t) {
        double u = clamp01(t);
        double centered = 2.0 * u - 1.0;
        return Math.max(0.0, 1.0 - centered * centered);
    }

    /**
     * Evaluates an unnormalized Gaussian bell envelope
     * {@code exp(-0.5 * ((clamp01(t) - mean) / sigma)^2)}.
     *
     * @param t     unit-interval parameter
     * @param mean  bell peak position
     * @param sigma standard deviation ({@code <= 0} or non-finite returns {@code 0.0})
     * @return Gaussian envelope value in {@code [0.0, 1.0]}
     */
    static double gaussian(double t, double mean, double sigma) {
        if (!Double.isFinite(mean) || !Double.isFinite(sigma) || sigma <= 0.0) {
            return 0.0;
        }
        double u = clamp01(t);
        double z = (u - mean) / sigma;
        return Math.exp(-0.5 * z * z);
    }

    /**
     * Evaluates a normalized logarithmic curve {@code ln(1 + t * (base - 1)) / ln(base)} over
     * {@code [0.0, 1.0]}.
     *
     * <p>When {@code base <= 1.0} or non-finite, gracefully falls back to the continuous limit
     * {@code clamp01(t)}.
     *
     * @param t    unit-interval parameter
     * @param base logarithmic base ({@code > 1.0}; values {@code <= 1.0} fall back to linear)
     * @return logarithmic value in {@code [0.0, 1.0]}
     */
    static double logarithmic(double t, double base) {
        double u = clamp01(t);
        if (!Double.isFinite(base) || base <= 1.0) {
            return u;
        }
        if (u <= 0.0) {
            return 0.0;
        }
        if (u >= 1.0) {
            return 1.0;
        }
        return Math.log1p(u * (base - 1.0)) / Math.log(base);
    }

    /**
     * Evaluates the complementary logarithmic taper {@code 1.0 - logarithmic(t, base)}.
     *
     * @param t    unit-interval parameter
     * @param base logarithmic base ({@code > 1.0})
     * @return tapered value in {@code [0.0, 1.0]}
     */
    static double logarithmicTaper(double t, double base) {
        return 1.0 - logarithmic(t, base);
    }

    /**
     * Evaluates a sinusoidal harmonic modulation
     * {@code amplitude * sin(2 * pi * (clamp01(t) / period) + phase)}.
     *
     * @param t         unit-interval parameter
     * @param amplitude wave amplitude
     * @param period    oscillation period ({@code <= 0} or non-finite returns {@code 0.0})
     * @param phase     phase offset in radians
     * @return harmonic offset in {@code [-|amplitude|, +|amplitude|]}
     */
    static double harmonic(double t, double amplitude, double period, double phase) {
        if (!Double.isFinite(amplitude) || !Double.isFinite(period) || period <= 0.0 || !Double.isFinite(phase)) {
            return 0.0;
        }
        double u = clamp01(t);
        return amplitude * Math.sin((2.0 * Math.PI * u / period) + phase);
    }

    /**
     * Evaluates an offset sinusoidal harmonic modulation
     * {@code offset + amplitude * sin(2 * pi * (clamp01(t) / period) + phase)}.
     *
     * @param t         unit-interval parameter
     * @param offset    baseline offset
     * @param amplitude wave amplitude
     * @param period    oscillation period ({@code <= 0} returns {@code offset})
     * @param phase     phase offset in radians
     * @return modulated value
     */
    static double harmonic(double t, double offset, double amplitude, double period, double phase) {
        double safeOffset = Double.isFinite(offset) ? offset : 0.0;
        return safeOffset + harmonic(t, amplitude, period, phase);
    }

    /**
     * Creates a composable {@link UnitCurve} for {@link #linear(double, double, double)}.
     *
     * @param start value at {@code t = 0.0}
     * @param end   value at {@code t = 1.0}
     * @return linear curve operator
     */
    static UnitCurve ofLinear(double start, double end) {
        return t -> linear(t, start, end);
    }

    /**
     * Creates a composable {@link UnitCurve} for {@link #power(double, double)}.
     *
     * @param exponent positive exponent
     * @return power curve operator
     */
    static UnitCurve ofPower(double exponent) {
        return t -> power(t, exponent);
    }

    /**
     * Creates a composable {@link UnitCurve} for {@link #sigmoid(double, double, double)}.
     *
     * @param midpoint  inflection point
     * @param steepness logistic steepness
     * @return sigmoid curve operator
     */
    static UnitCurve ofSigmoid(double midpoint, double steepness) {
        return t -> sigmoid(t, midpoint, steepness);
    }

    /**
     * Creates a composable {@link UnitCurve} for {@link #gaussian(double, double, double)}.
     *
     * @param mean  bell center
     * @param sigma standard deviation
     * @return Gaussian curve operator
     */
    static UnitCurve ofGaussian(double mean, double sigma) {
        return t -> gaussian(t, mean, sigma);
    }

    /**
     * Creates a composable {@link UnitCurve} for {@link #logarithmic(double, double)}.
     *
     * @param base logarithmic base
     * @return logarithmic curve operator
     */
    static UnitCurve ofLogarithmic(double base) {
        return t -> logarithmic(t, base);
    }

    /**
     * Creates a composable {@link UnitCurve} for {@link #logarithmicTaper(double, double)}.
     *
     * @param base logarithmic base
     * @return logarithmic taper curve operator
     */
    static UnitCurve ofLogarithmicTaper(double base) {
        return t -> logarithmicTaper(t, base);
    }

    @Override
    default UnitCurve andThen(DoubleUnaryOperator after) {
        Objects.requireNonNull(after, "after");
        return t -> after.applyAsDouble(applyAsDouble(t));
    }
}
