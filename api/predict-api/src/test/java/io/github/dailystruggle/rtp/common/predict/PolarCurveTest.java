package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit verification for {@link PolarCurve}.
 *
 * @governedBy ADR-001
 * @governedBy ADR-115
 */
@DisplayName("REQ-PRED-F-012: PolarCurve Spiral, Harmonic Cross-Section, and Annular Ring Primitives")
class PolarCurveTest {

    @Test
    @DisplayName("spiralOffset maps unit progress and reach curves to exact Cartesian offsets")
    void spiralOffsetGeometryAndGuards() {
        // t = 0 -> r = 0 -> (0, 0)
        double[] start = PolarCurve.spiralOffset(0.0, 10.0, 1.0, 0.0);
        assertEquals(0.0, start[0], 1e-12);
        assertEquals(0.0, start[1], 1e-12);

        // t = 0.25 with 1 turn from angle 0 -> angle = pi/2, r = 2.5 -> (0, 2.5)
        double[] quarter = PolarCurve.spiralOffset(0.25, 10.0, 1.0, 0.0);
        assertEquals(0.0, quarter[0], 1e-9);
        assertEquals(2.5, quarter[1], 1e-9);

        // t = 0.5 with 1 turn from angle 0 -> angle = pi, r = 5.0 -> (-5.0, 0)
        double[] half = PolarCurve.spiralOffset(0.5, 10.0, 1.0, 0.0);
        assertEquals(-5.0, half[0], 1e-9);
        assertEquals(0.0, half[1], 1e-9);

        // t = 1.0 with 1 turn from angle 0 -> angle = 2*pi, r = 10.0 -> (10.0, 0)
        double[] full = PolarCurve.spiralOffset(1.0, 10.0, 1.0, 0.0);
        assertEquals(10.0, full[0], 1e-9);
        assertEquals(0.0, full[1], 1e-9);

        // Custom smoothstep reach curve
        double[] smooth = PolarCurve.spiralOffset(0.5, 8.0, 0.5, 0.0, UnitCurve::smoothstep);
        assertEquals(4.0, Math.hypot(smooth[0], smooth[1]), 1e-9);

        // Degenerate maxReach <= 0 or negative envelope returns [0, 0]
        assertArrayEquals(new double[] {0.0, 0.0}, PolarCurve.spiralOffset(0.5, 0.0, 1.0, 0.0), 1e-12);
        assertArrayEquals(new double[] {0.0, 0.0}, PolarCurve.spiralOffset(0.5, -5.0, 1.0, 0.0), 1e-12);
        assertArrayEquals(
                new double[] {0.0, 0.0},
                PolarCurve.spiralOffset(0.5, 10.0, 1.0, 0.0, t -> -1.0),
                1e-12);
    }

    @Test
    @DisplayName("harmonicRadius 3-harmonic overload matches general array overload and clamps negative radii")
    void harmonicRadiusParityAndBounds() {
        double[] freqs = {3.0, 5.0, 2.0};
        double[] amps = {0.20, 0.13, 0.09};
        double[] phases = {0.4, 1.1, 2.3};

        for (int i = 0; i <= 16; i++) {
            double theta = (2.0 * Math.PI * i) / 16.0;
            double fromArray = PolarCurve.harmonicRadius(10.0, theta, freqs, amps, phases);
            double fromFast = PolarCurve.harmonicRadius(10.0, theta, phases[0], phases[1], phases[2]);
            assertEquals(fromArray, fromFast, 1e-12, "3-harmonic fast overload must match array evaluator");
            assertTrue(fromFast >= 10.0 * (1.0 - 0.42) && fromFast <= 10.0 * (1.0 + 0.42));
        }

        // Periodicity over 2*pi
        assertEquals(
                PolarCurve.harmonicRadius(8.0, 0.35, 0.1, 0.2, 0.3),
                PolarCurve.harmonicRadius(8.0, 0.35 + 2.0 * Math.PI, 0.1, 0.2, 0.3),
                1e-9);

        // Extreme negative modulation clamps at 0.0
        assertEquals(
                0.0,
                PolarCurve.harmonicRadius(5.0, -Math.PI / 2.0, new double[] {1.0}, new double[] {2.0}, null),
                1e-12);

        // Degenerate guards
        assertEquals(0.0, PolarCurve.harmonicRadius(0.0, 1.0, 0.0, 0.0, 0.0), 1e-12);
        assertEquals(0.0, PolarCurve.harmonicRadius(-3.0, 1.0, freqs, amps, phases), 1e-12);
        assertEquals(6.0, PolarCurve.harmonicRadius(6.0, Double.NaN, 0.0, 0.0, 0.0), 1e-12);
        assertEquals(6.0, PolarCurve.harmonicRadius(6.0, 1.0, null, amps, phases), 1e-12);
    }

    @Test
    @DisplayName("annularRingRadius inverts area-indexed circle locations to radial distance")
    void annularRingRadiusInversion() {
        double innerR = 64.0;
        double innerSq = innerR * innerR;
        assertEquals(innerR, PolarCurve.annularRingRadius(0.0, innerSq), 1e-12);

        double outerR = 256.0;
        double maxLoc = (outerR * outerR - innerSq) * Math.PI;
        assertEquals(outerR, PolarCurve.annularRingRadius(maxLoc, innerSq), 1e-9);

        // Degenerate negative and NaN guards
        assertEquals(0.0, PolarCurve.annularRingRadius(-100.0, -50.0), 1e-12);
        assertEquals(innerR, PolarCurve.annularRingRadius(Double.NaN, innerSq), 1e-12);
    }
}
