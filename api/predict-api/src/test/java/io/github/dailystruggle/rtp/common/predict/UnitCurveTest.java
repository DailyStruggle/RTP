package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit verification for {@link UnitCurve}.
 *
 * @governedBy ADR-115
 */
@DisplayName("REQ-PRED-F-010: UnitCurve Unit-Interval Shaping and Envelope Primitives")
class UnitCurveTest {

    @Test
    @DisplayName("clamp01 clamps out-of-range, negative zero, Infinity, and NaN inputs into [0, 1]")
    void clamp01BoundaryAndNaNGuards() {
        assertEquals(0.0, UnitCurve.clamp01(Double.NaN), 1e-12);
        assertEquals(0.0, UnitCurve.clamp01(Double.NEGATIVE_INFINITY), 1e-12);
        assertEquals(0.0, UnitCurve.clamp01(-10.0), 1e-12);
        assertEquals(0.0, UnitCurve.clamp01(-0.0), 1e-12);
        assertEquals(0.0, Double.compare(0.0, UnitCurve.clamp01(-0.0)));
        assertEquals(0.0, UnitCurve.clamp01(0.0), 1e-12);
        assertEquals(0.5, UnitCurve.clamp01(0.5), 1e-12);
        assertEquals(1.0, UnitCurve.clamp01(1.0), 1e-12);
        assertEquals(1.0, UnitCurve.clamp01(2.5), 1e-12);
        assertEquals(1.0, UnitCurve.clamp01(Double.POSITIVE_INFINITY), 1e-12);
    }

    @Test
    @DisplayName("linear interpolates monotonically and clamps t to [0, 1]")
    void linearInterpolationAndGuards() {
        assertEquals(2.0, UnitCurve.linear(-1.0, 2.0, 10.0), 1e-12);
        assertEquals(2.0, UnitCurve.linear(0.0, 2.0, 10.0), 1e-12);
        assertEquals(6.0, UnitCurve.linear(0.5, 2.0, 10.0), 1e-12);
        assertEquals(10.0, UnitCurve.linear(1.0, 2.0, 10.0), 1e-12);
        assertEquals(10.0, UnitCurve.linear(5.0, 2.0, 10.0), 1e-12);

        // Decreasing linear curve
        assertEquals(10.0, UnitCurve.linear(0.0, 10.0, 2.0), 1e-12);
        assertEquals(6.0, UnitCurve.linear(0.5, 10.0, 2.0), 1e-12);
        assertEquals(2.0, UnitCurve.linear(1.0, 10.0, 2.0), 1e-12);

        // Non-finite guards
        assertEquals(0.0, UnitCurve.linear(0.5, Double.NaN, 10.0), 1e-12);
        assertEquals(0.0, UnitCurve.linear(0.5, 2.0, Double.POSITIVE_INFINITY), 1e-12);
    }

    @ParameterizedTest(name = "exponent={0}")
    @CsvSource({"0.5", "1.0", "2.0", "4.0"})
    @DisplayName("power and complementaryPower are monotonic and sum to 1.0")
    void powerAndComplementaryPowerMonotonicity(double exponent) {
        assertEquals(0.0, UnitCurve.power(0.0, exponent), 1e-12);
        assertEquals(1.0, UnitCurve.power(1.0, exponent), 1e-12);
        assertEquals(Math.pow(0.5, exponent), UnitCurve.power(0.5, exponent), 1e-12);

        double prev = UnitCurve.power(0.0, exponent);
        for (int i = 1; i <= 20; i++) {
            double t = i / 20.0;
            double cur = UnitCurve.power(t, exponent);
            assertTrue(cur >= prev, "power curve must be monotonically non-decreasing");
            assertEquals(1.0, cur + UnitCurve.complementaryPower(t, exponent), 1e-12);
            prev = cur;
        }
    }

    @Test
    @DisplayName("power guards against non-positive and NaN exponents")
    void powerDegenerateExponentGuards() {
        assertEquals(0.0, UnitCurve.power(0.5, 0.0), 1e-12);
        assertEquals(0.0, UnitCurve.power(0.5, -2.0), 1e-12);
        assertEquals(0.0, UnitCurve.power(0.5, Double.NaN), 1e-12);
        assertEquals(0.0, UnitCurve.power(0.5, Double.POSITIVE_INFINITY), 1e-12);
    }

    @Test
    @DisplayName("sigmoid equals 0.5 at midpoint, is monotonic, and guards extreme steepness")
    void sigmoidMonotonicityAndBounds() {
        assertEquals(0.5, UnitCurve.sigmoid(0.5, 0.5, 10.0), 1e-12);
        assertEquals(0.5, UnitCurve.sigmoid(0.3, 0.5, 0.0), 1e-12);
        assertEquals(0.5, UnitCurve.sigmoid(0.5, Double.NaN, 10.0), 1e-12);
        assertEquals(0.5, UnitCurve.sigmoid(0.5, 0.5, Double.NaN), 1e-12);

        double prev = UnitCurve.sigmoid(0.0, 0.5, 8.0);
        for (int i = 1; i <= 20; i++) {
            double cur = UnitCurve.sigmoid(i / 20.0, 0.5, 8.0);
            assertTrue(cur > prev, "sigmoid with positive steepness must be strictly increasing");
            assertTrue(cur >= 0.0 && cur <= 1.0);
            prev = cur;
        }

        // Extreme steepness saturation without overflow
        assertEquals(0.0, UnitCurve.sigmoid(0.0, 0.5, 5000.0), 1e-12);
        assertEquals(1.0, UnitCurve.sigmoid(1.0, 0.5, 5000.0), 1e-12);
    }

    @Test
    @DisplayName("smoothstep and parabolicCrown satisfy boundary, midpoint, and symmetry invariants")
    void smoothstepAndParabolicCrown() {
        assertEquals(0.0, UnitCurve.smoothstep(-0.5), 1e-12);
        assertEquals(0.0, UnitCurve.smoothstep(0.0), 1e-12);
        assertEquals(0.5, UnitCurve.smoothstep(0.5), 1e-12);
        assertEquals(1.0, UnitCurve.smoothstep(1.0), 1e-12);
        assertEquals(1.0, UnitCurve.smoothstep(1.5), 1e-12);

        assertEquals(0.0, UnitCurve.parabolicCrown(-0.5), 1e-12);
        assertEquals(0.0, UnitCurve.parabolicCrown(0.0), 1e-12);
        assertEquals(1.0, UnitCurve.parabolicCrown(0.5), 1e-12);
        assertEquals(0.0, UnitCurve.parabolicCrown(1.0), 1e-12);
        assertEquals(0.0, UnitCurve.parabolicCrown(1.5), 1e-12);

        double prevSmooth = 0.0;
        for (int i = 1; i <= 20; i++) {
            double t = i / 20.0;
            double s = UnitCurve.smoothstep(t);
            assertTrue(s >= prevSmooth, "smoothstep must be monotonically non-decreasing");
            assertEquals(1.0, s + UnitCurve.smoothstep(1.0 - t), 1e-12, "smoothstep antisymmetry around 0.5");
            assertEquals(
                    UnitCurve.parabolicCrown(t),
                    UnitCurve.parabolicCrown(1.0 - t),
                    1e-12,
                    "parabolicCrown symmetry around 0.5");
            prevSmooth = s;
        }
    }

    @Test
    @DisplayName("gaussian peaks at mean, decays symmetrically, and guards degenerate sigma <= 0")
    void gaussianEnvelopeAndDegenerateSigmaGuards() {
        assertEquals(1.0, UnitCurve.gaussian(0.5, 0.5, 0.2), 1e-12);
        assertEquals(Math.exp(-0.5), UnitCurve.gaussian(0.7, 0.5, 0.2), 1e-12);
        assertEquals(
                UnitCurve.gaussian(0.3, 0.5, 0.2),
                UnitCurve.gaussian(0.7, 0.5, 0.2),
                1e-12,
                "Gaussian must be symmetric around mean");

        // Degenerate sigma <= 0 and NaN guards
        assertEquals(0.0, UnitCurve.gaussian(0.5, 0.5, 0.0), 1e-12);
        assertEquals(0.0, UnitCurve.gaussian(0.5, 0.5, -0.25), 1e-12);
        assertEquals(0.0, UnitCurve.gaussian(0.5, 0.5, Double.NaN), 1e-12);
        assertEquals(0.0, UnitCurve.gaussian(0.5, Double.NaN, 0.2), 1e-12);
    }

    @Test
    @DisplayName("logarithmic and logarithmicTaper are monotonic for base > 1 and guard base <= 1")
    void logarithmicAndTaperWithDegenerateBaseGuards() {
        double base = 10.0;
        assertEquals(0.0, UnitCurve.logarithmic(0.0, base), 1e-12);
        assertEquals(1.0, UnitCurve.logarithmic(1.0, base), 1e-12);
        assertTrue(UnitCurve.logarithmic(0.5, base) > 0.5, "logarithmic curve for base > 1 is concave");

        double prev = 0.0;
        for (int i = 1; i <= 20; i++) {
            double t = i / 20.0;
            double v = UnitCurve.logarithmic(t, base);
            assertTrue(v >= prev, "logarithmic must be monotonically non-decreasing");
            assertEquals(1.0, v + UnitCurve.logarithmicTaper(t, base), 1e-12);
            prev = v;
        }

        // Degenerate base <= 1 and NaN guards fall back to linear clamp01(t)
        assertEquals(0.25, UnitCurve.logarithmic(0.25, 1.0), 1e-12);
        assertEquals(0.75, UnitCurve.logarithmicTaper(0.25, 1.0), 1e-12);
        assertEquals(0.5, UnitCurve.logarithmic(0.5, 0.5), 1e-12);
        assertEquals(0.5, UnitCurve.logarithmic(0.5, -3.0), 1e-12);
        assertEquals(0.5, UnitCurve.logarithmic(0.5, Double.NaN), 1e-12);
    }

    @Test
    @DisplayName("harmonic evaluates sinusoidal oscillation and guards non-positive periods")
    void harmonicOscillationAndGuards() {
        assertEquals(0.0, UnitCurve.harmonic(0.0, 2.0, 1.0, 0.0), 1e-12);
        assertEquals(2.0, UnitCurve.harmonic(0.25, 2.0, 1.0, 0.0), 1e-12);
        assertEquals(0.0, UnitCurve.harmonic(0.5, 2.0, 1.0, 0.0), 1e-12);
        assertEquals(-2.0, UnitCurve.harmonic(0.75, 2.0, 1.0, 0.0), 1e-12);
        assertEquals(3.0, UnitCurve.harmonic(0.25, 1.0, 2.0, 1.0, 0.0), 1e-12);

        // Degenerate period <= 0 and non-finite guards
        assertEquals(0.0, UnitCurve.harmonic(0.25, 2.0, 0.0, 0.0), 1e-12);
        assertEquals(0.0, UnitCurve.harmonic(0.25, 2.0, -1.0, 0.0), 1e-12);
        assertEquals(0.0, UnitCurve.harmonic(0.25, Double.NaN, 1.0, 0.0), 1e-12);
        assertEquals(1.5, UnitCurve.harmonic(0.25, 1.5, 2.0, 0.0, 0.0), 1e-12);
    }

    @Test
    @DisplayName("Composable UnitCurve factory operators and andThen chaining match static evaluators")
    void composableOperators() {
        UnitCurve power2 = UnitCurve.ofPower(2.0);
        UnitCurve scale10 = UnitCurve.ofLinear(0.0, 10.0);
        UnitCurve chained = power2.andThen(scale10);

        assertEquals(2.5, chained.applyAsDouble(0.5), 1e-12);
        assertEquals(UnitCurve.sigmoid(0.5, 0.5, 4.0), UnitCurve.ofSigmoid(0.5, 4.0).applyAsDouble(0.5), 1e-12);
        assertEquals(UnitCurve.gaussian(0.5, 0.5, 0.25), UnitCurve.ofGaussian(0.5, 0.25).applyAsDouble(0.5), 1e-12);
        assertEquals(UnitCurve.logarithmic(0.5, 4.0), UnitCurve.ofLogarithmic(4.0).applyAsDouble(0.5), 1e-12);
        assertEquals(UnitCurve.logarithmicTaper(0.5, 4.0), UnitCurve.ofLogarithmicTaper(4.0).applyAsDouble(0.5), 1e-12);
    }
}
