package io.github.dailystruggle.rtp.common.predict;

import java.util.function.DoubleUnaryOperator;

/**
 * Parametric polar-spiral, multi-harmonic cross-section, and annular ring mapping primitives
 * ({@code (r(t), theta(t)) -> (dx, dz)}).
 *
 * <p>Shared by spatial region selectors ({@code Circle}, {@code Circle_Normal}, ADR-001) and
 * procedural organic geometry generators (spiral trunks, root buttresses, and multi-harmonic
 * canopy cross-sections).
 *
 * <p>Zero external dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002, ADR-115).
 */
public final class PolarCurve {

    private static final double TWO_PI = 2.0 * Math.PI;

    private PolarCurve() {
    }

    /**
     * Computes the polar angle {@code initialAngleRad + 2 * pi * turns * clamp01(t)} along a
     * spiral path.
     *
     * @param t               unit-interval progress in {@code [0.0, 1.0]}
     * @param turns           number of full revolutions over {@code t in [0.0, 1.0]}
     * @param initialAngleRad starting angle at {@code t = 0.0} in radians
     * @return angle in radians
     */
    public static double spiralAngle(double t, double turns, double initialAngleRad) {
        double u = UnitCurve.clamp01(t);
        double safeTurns = Double.isFinite(turns) ? turns : 0.0;
        double safeInit = Double.isFinite(initialAngleRad) ? initialAngleRad : 0.0;
        return safeInit + TWO_PI * safeTurns * u;
    }

    /**
     * Maps normalized progress {@code t in [0.0, 1.0]} along a parametric polar spiral to
     * Cartesian offsets {@code (dx, dz)}.
     *
     * <p>The radial distance is {@code r(t) = maxReach * max(0.0, reachCurve.applyAsDouble(clamp01(t)))}
     * and the polar angle is {@code theta(t) = initialAngleRad + 2 * pi * turns * clamp01(t)}.
     *
     * @param t               unit-interval progress in {@code [0.0, 1.0]}
     * @param maxReach        maximum radial displacement in blocks/units ({@code <= 0} yields {@code (0, 0)})
     * @param turns           number of spiral revolutions across {@code [0.0, 1.0]}
     * @param initialAngleRad starting azimuth angle in radians
     * @param reachCurve      radial envelope curve over {@code [0.0, 1.0]} (if {@code null}, defaults to linear {@code t})
     * @return two-element array {@code [dx, dz]}
     */
    public static double[] spiralOffset(
            double t,
            double maxReach,
            double turns,
            double initialAngleRad,
            DoubleUnaryOperator reachCurve) {
        if (!Double.isFinite(maxReach) || maxReach <= 0.0) {
            return new double[] {0.0, 0.0};
        }
        double u = UnitCurve.clamp01(t);
        double envelope = (reachCurve != null) ? reachCurve.applyAsDouble(u) : u;
        if (!Double.isFinite(envelope) || envelope <= 0.0) {
            return new double[] {0.0, 0.0};
        }
        double r = maxReach * envelope;
        double theta = spiralAngle(u, turns, initialAngleRad);
        return new double[] {r * Math.cos(theta), r * Math.sin(theta)};
    }

    /**
     * Maps normalized progress {@code t in [0.0, 1.0]} along a linear-reach Archimedean spiral to
     * Cartesian offsets {@code [dx, dz]}.
     *
     * @param t               unit-interval progress in {@code [0.0, 1.0]}
     * @param maxReach        maximum radial displacement at {@code t = 1.0}
     * @param turns           number of spiral revolutions
     * @param initialAngleRad starting azimuth angle in radians
     * @return two-element array {@code [dx, dz]}
     */
    public static double[] spiralOffset(double t, double maxReach, double turns, double initialAngleRad) {
        return spiralOffset(t, maxReach, turns, initialAngleRad, UnitCurve::clamp01);
    }

    /**
     * Evaluates a multi-harmonic perturbed polar radius
     * {@code max(0, baseRadius * (1 + sum_i amplitudes[i] * sin(frequencies[i] * angleRad + phases[i])))}.
     *
     * @param baseRadius  unperturbed radius {@code r0} ({@code <= 0} returns {@code 0.0})
     * @param angleRad    polar azimuth angle {@code theta} in radians
     * @param frequencies harmonic angular frequencies {@code k_i}
     * @param amplitudes  relative harmonic amplitudes {@code a_i}
     * @param phases      harmonic phase offsets {@code phi_i} in radians (may be {@code null} for zero phase)
     * @return perturbed radius {@code >= 0.0}
     */
    public static double harmonicRadius(
            double baseRadius,
            double angleRad,
            double[] frequencies,
            double[] amplitudes,
            double[] phases) {
        if (!Double.isFinite(baseRadius) || baseRadius <= 0.0) {
            return 0.0;
        }
        if (!Double.isFinite(angleRad) || frequencies == null || amplitudes == null) {
            return baseRadius;
        }
        int count = Math.min(frequencies.length, amplitudes.length);
        double modulation = 0.0;
        for (int i = 0; i < count; i++) {
            double freq = frequencies[i];
            double amp = amplitudes[i];
            double phase = (phases != null && i < phases.length) ? phases[i] : 0.0;
            if (Double.isFinite(freq) && Double.isFinite(amp) && Double.isFinite(phase)) {
                modulation += amp * Math.sin(freq * angleRad + phase);
            }
        }
        return Math.max(0.0, baseRadius * (1.0 + modulation));
    }

    /**
     * Allocation-free 3-harmonic polar radius evaluator matching the canonical organic lobing
     * profile {@code baseRadius * (1 + 0.20*sin(3*theta + phase0) + 0.13*sin(5*theta + phase1) + 0.09*sin(2*theta + phase2))}.
     *
     * @param baseRadius unperturbed radius {@code r0} ({@code <= 0} returns {@code 0.0})
     * @param angleRad   polar azimuth angle {@code theta} in radians
     * @param phase0     phase offset for the 3-lobe harmonic in radians
     * @param phase1     phase offset for the 5-lobe harmonic in radians
     * @param phase2     phase offset for the 2-lobe harmonic in radians
     * @return perturbed radius {@code >= 0.0}
     */
    public static double harmonicRadius(
            double baseRadius,
            double angleRad,
            double phase0,
            double phase1,
            double phase2) {
        if (!Double.isFinite(baseRadius) || baseRadius <= 0.0) {
            return 0.0;
        }
        if (!Double.isFinite(angleRad)) {
            return baseRadius;
        }
        double p0 = Double.isFinite(phase0) ? phase0 : 0.0;
        double p1 = Double.isFinite(phase1) ? phase1 : 0.0;
        double p2 = Double.isFinite(phase2) ? phase2 : 0.0;
        double modulation =
                0.20 * Math.sin(3.0 * angleRad + p0)
                        + 0.13 * Math.sin(5.0 * angleRad + p1)
                        + 0.09 * Math.sin(2.0 * angleRad + p2);
        return Math.max(0.0, baseRadius * (1.0 + modulation));
    }

    /**
     * Computes the continuous annular ring radius {@code sqrt(locationIndex / pi + innerRadiusSquared)}
     * for an area-indexed 1D spiral location (ADR-001).
     *
     * @param locationIndex      1D area-scaled location index ({@code >= 0})
     * @param innerRadiusSquared squared inner exclusion radius {@code centerRadius^2} ({@code >= 0})
     * @return continuous radial distance from center {@code >= 0.0}
     */
    public static double annularRingRadius(double locationIndex, double innerRadiusSquared) {
        double safeLoc = (Double.isFinite(locationIndex) && locationIndex > 0.0) ? locationIndex : 0.0;
        double safeInnerSq = (Double.isFinite(innerRadiusSquared) && innerRadiusSquared > 0.0) ? innerRadiusSquared : 0.0;
        return Math.sqrt(safeLoc / Math.PI + safeInnerSq);
    }
}
