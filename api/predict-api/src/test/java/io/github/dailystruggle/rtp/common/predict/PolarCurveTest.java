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

    @Test
    @DisplayName("spheroidSliceRadius, ellipsoidDistance, sphericalOffset, fanAngleOffset, and quadrantRotateXZ match analytic geometry")
    void sphericalAndEllipsoidalPrimitives() {
        assertEquals(10.0, PolarCurve.spheroidSliceRadius(10.0, 0.0, 5.0), 1e-12);
        assertEquals(8.0, PolarCurve.spheroidSliceRadius(10.0, 3.0, 5.0), 1e-12);
        assertEquals(8.0, PolarCurve.spheroidSliceRadius(10.0, -3.0, 5.0), 1e-12);
        assertEquals(0.0, PolarCurve.spheroidSliceRadius(10.0, 5.0, 5.0), 1e-12);
        assertEquals(0.0, PolarCurve.spheroidSliceRadius(0.0, 0.0, 5.0), 1e-12);

        assertEquals(5.0, PolarCurve.ellipsoidDistance(3.0, 2.0, 0.0, 0.5), 1e-12); // dyScaled = 2 / 0.5 = 4 -> hypot(3, 4) = 5

        double[] vec = PolarCurve.sphericalOffset(10.0, 0.0, Math.PI / 2.0);
        assertEquals(10.0, vec[0], 1e-9);
        assertEquals(0.0, vec[1], 1e-9);
        assertEquals(0.0, vec[2], 1e-9);
        assertArrayEquals(new double[] {0.0, 0.0, 0.0}, PolarCurve.sphericalOffset(0.0, 0.5, 0.5), 1e-12);

        assertEquals(-15.0, PolarCurve.fanAngleOffset(0, 3, 15.0), 1e-12);
        assertEquals(0.0, PolarCurve.fanAngleOffset(1, 3, 15.0), 1e-12);
        assertEquals(15.0, PolarCurve.fanAngleOffset(2, 3, 15.0), 1e-12);
        assertEquals(0.0, PolarCurve.fanAngleOffset(0, 1, 15.0), 1e-12);

        assertArrayEquals(new int[] {-4, 3}, PolarCurve.quadrantRotateXZ(3, 4, 1));
        assertArrayEquals(new int[] {-3, -4}, PolarCurve.quadrantRotateXZ(3, 4, 2));
        assertArrayEquals(new int[] {4, -3}, PolarCurve.quadrantRotateXZ(3, 4, 3));
        assertArrayEquals(new int[] {3, 4}, PolarCurve.quadrantRotateXZ(3, 4, 4));
    }

    @Test
    @DisplayName("trace6ConnectedLine and traceSteepestOrthogonalLine produce strictly 6-connected lattice paths")
    void trace6ConnectedAndSteepestOrthogonalPaths() {
        var line = PolarCurve.trace6ConnectedLine(0.0, 0.0, 0.0, 4.0, 3.0, -2.0);
        assertFalse(line.isEmpty());
        assertArrayEquals(new int[] {0, 0, 0}, line.get(0));
        assertArrayEquals(new int[] {4, 3, -2}, line.get(line.size() - 1));
        for (int i = 1; i < line.size(); i++) {
            int[] a = line.get(i - 1);
            int[] b = line.get(i);
            int manhattan = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]) + Math.abs(b[2] - a[2]);
            assertEquals(1, manhattan, "Every step in trace6ConnectedLine must be face-adjacent");
        }

        var interior = PolarCurve.traceSteepestOrthogonalLine(0, 0, 0, 3, 2, 1);
        assertEquals(5, interior.size()); // Manhattan distance 6 -> 5 interior points
        int[] prev = {0, 0, 0};
        for (int[] step : interior) {
            int manhattan = Math.abs(step[0] - prev[0]) + Math.abs(step[1] - prev[1]) + Math.abs(step[2] - prev[2]);
            assertEquals(1, manhattan);
            prev = step;
        }
        int finalStep = Math.abs(3 - prev[0]) + Math.abs(2 - prev[1]) + Math.abs(1 - prev[2]);
        assertEquals(1, finalStep);
        assertTrue(PolarCurve.traceSteepestOrthogonalLine(0, 0, 0, 1, 0, 0).isEmpty());
    }
}
