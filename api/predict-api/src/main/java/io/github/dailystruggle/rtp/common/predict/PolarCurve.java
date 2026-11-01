package io.github.dailystruggle.rtp.common.predict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Parametric polar-spiral, spherical/spheroidal envelope, multi-harmonic cross-section, annular
 * ring, and 6-connected parametric curve discretization primitives.
 *
 * <p>Shared by spatial region selectors ({@code Circle}, {@code Circle_Normal}, ADR-001) and
 * procedural organic geometry generators (spiral trunks, root buttresses, branch fans, and
 * multi-harmonic canopy cross-sections).
 *
 * <p>Zero external dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002, ADR-115).
 *
 * @governedBy ADR-001
 * @governedBy ADR-087
 * @governedBy ADR-115
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

    /**
     * Computes the horizontal cross-section radius of an oblate or prolate spheroid at vertical
     * offset {@code dy} from its equator using {@link UnitCurve#circularArc(double)}:
     * {@code equatorRadius * sqrt(max(0, 1 - (|dy| / semiAxisY)^2))}.
     *
     * @param equatorRadius equatorial radius at {@code dy = 0} ({@code <= 0} returns {@code 0.0})
     * @param dy            vertical offset from equator
     * @param semiAxisY     vertical semi-axis height ({@code <= 0} returns {@code 0.0} when {@code dy != 0})
     * @return slice radius {@code >= 0.0}
     */
    public static double spheroidSliceRadius(double equatorRadius, double dy, double semiAxisY) {
        if (!Double.isFinite(equatorRadius) || equatorRadius <= 0.0 || !Double.isFinite(dy)) {
            return 0.0;
        }
        if (dy == 0.0) {
            return equatorRadius;
        }
        if (!Double.isFinite(semiAxisY) || semiAxisY <= 0.0) {
            return 0.0;
        }
        double ratio = Math.abs(dy) / semiAxisY;
        if (ratio >= 1.0) {
            return 0.0;
        }
        return equatorRadius * UnitCurve.circularArc(ratio);
    }

    /**
     * Computes the vertically scaled Euclidean radial distance
     * {@code sqrt(dx^2 + (dy / max(0.01, verticalScale))^2 + dz^2)} for evaluating ellipsoidal
     * envelopes.
     *
     * @param dx            X offset from center
     * @param dy            Y offset from center
     * @param dz            Z offset from center
     * @param verticalScale vertical semi-axis scale factor (clamped to {@code >= 0.01})
     * @return scaled radial distance {@code >= 0.0}
     */
    public static double ellipsoidDistance(double dx, double dy, double dz, double verticalScale) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return Double.POSITIVE_INFINITY;
        }
        double scale = (Double.isFinite(verticalScale) && verticalScale > 0.01) ? verticalScale : 0.01;
        double dyScaled = dy / scale;
        return Math.sqrt(dx * dx + dyScaled * dyScaled + dz * dz);
    }

    /**
     * Maps 3D spherical coordinates {@code (radius, elevationRad, azimuthRad)} to Cartesian
     * offsets {@code [dx, dy, dz]} where {@code dx = r * cos(el) * sin(az)},
     * {@code dy = r * sin(el)}, and {@code dz = r * cos(el) * cos(az)}.
     *
     * @param radius       radial length ({@code <= 0} returns {@code [0, 0, 0]})
     * @param elevationRad pitch/elevation angle from the XZ plane in radians
     * @param azimuthRad   yaw/azimuth angle in the XZ plane in radians
     * @return three-element array {@code [dx, dy, dz]}
     */
    public static double[] sphericalOffset(double radius, double elevationRad, double azimuthRad) {
        if (!Double.isFinite(radius)
                || radius <= 0.0
                || !Double.isFinite(elevationRad)
                || !Double.isFinite(azimuthRad)) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double cosEl = Math.cos(elevationRad);
        return new double[] {
            radius * cosEl * Math.sin(azimuthRad),
            radius * Math.sin(elevationRad),
            radius * cosEl * Math.cos(azimuthRad)
        };
    }

    /**
     * Computes the centered angular fan offset {@code stepAngle * (index - (count - 1) / 2.0)} for
     * splitting a parent heading into {@code count} symmetric offshoots.
     *
     * @param index     zero-based offshoot index in {@code [0, count)}
     * @param count     total number of offshoots ({@code <= 1} returns {@code 0.0})
     * @param stepAngle angular separation between adjacent offshoots (degrees or radians)
     * @return angular offset centered around {@code 0.0}
     */
    public static double fanAngleOffset(int index, int count, double stepAngle) {
        if (count <= 1 || !Double.isFinite(stepAngle)) {
            return 0.0;
        }
        return stepAngle * (index - (count - 1) / 2.0);
    }

    /**
     * Rotates discrete planar coordinates {@code (x, z)} by {@code quadrants} 90-degree
     * counter-clockwise steps ({@code (x, z) -> (-z, x)} per quadrant).
     *
     * @param x         planar X coordinate
     * @param z         planar Z coordinate
     * @param quadrants number of 90-degree rotations (any integer, normalized modulo 4)
     * @return two-element array {@code [rotatedX, rotatedZ]}
     */
    public static int[] quadrantRotateXZ(int x, int z, int quadrants) {
        int q = ((quadrants % 4) + 4) % 4;
        int nx = x;
        int nz = z;
        for (int i = 0; i < q; i++) {
            int temp = nx;
            nx = -nz;
            nz = temp;
        }
        return new int[] {nx, nz};
    }

    /**
     * Samples a 3D parametric curve {@code (xCurve(t), yCurve(t), zCurve(t))} across
     * {@code t in [0.0, 1.0]} in {@code steps} increments and bridges consecutive rounded lattice
     * points one axis at a time ({@code X}, then {@code Y}, then {@code Z}) so every step in the
     * returned path is 6-connected (face-adjacent).
     *
     * @param steps  number of curve subdivisions (clamped to {@code >= 1})
     * @param xCurve parametric X curve over {@code [0.0, 1.0]}
     * @param yCurve parametric Y curve over {@code [0.0, 1.0]}
     * @param zCurve parametric Z curve over {@code [0.0, 1.0]}
     * @return ordered 6-connected sequence of {@code int[]{x, y, z}} lattice points
     */
    public static List<int[]> trace6ConnectedCurve(
            int steps,
            DoubleUnaryOperator xCurve,
            DoubleUnaryOperator yCurve,
            DoubleUnaryOperator zCurve) {
        Objects.requireNonNull(xCurve, "xCurve");
        Objects.requireNonNull(yCurve, "yCurve");
        Objects.requireNonNull(zCurve, "zCurve");
        int n = Math.max(1, steps);
        List<int[]> path = new ArrayList<>(n * 2 + 1);
        int lx = (int) Math.round(xCurve.applyAsDouble(0.0));
        int ly = (int) Math.round(yCurve.applyAsDouble(0.0));
        int lz = (int) Math.round(zCurve.applyAsDouble(0.0));
        path.add(new int[] {lx, ly, lz});

        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            int nx = (int) Math.round(xCurve.applyAsDouble(t));
            int ny = (int) Math.round(yCurve.applyAsDouble(t));
            int nz = (int) Math.round(zCurve.applyAsDouble(t));
            while (lx != nx || ly != ny || lz != nz) {
                if (lx != nx) {
                    lx += Integer.signum(nx - lx);
                } else if (ly != ny) {
                    ly += Integer.signum(ny - ly);
                } else {
                    lz += Integer.signum(nz - lz);
                }
                path.add(new int[] {lx, ly, lz});
            }
        }
        return path;
    }

    /**
     * Discretizes the 3D linear segment from {@code (x0, y0, z0)} to {@code (x1, y1, z1)} into a
     * 6-connected (face-adjacent) sequence of {@code int[]{x, y, z}} lattice points.
     *
     * @param x0 start X coordinate
     * @param y0 start Y coordinate
     * @param z0 start Z coordinate
     * @param x1 end X coordinate
     * @param y1 end Y coordinate
     * @param z1 end Z coordinate
     * @return ordered 6-connected sequence of {@code int[]{x, y, z}} lattice points
     */
    public static List<int[]> trace6ConnectedLine(
            double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int steps =
                (int) Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.max(Math.abs(dz), 1.0));
        return trace6ConnectedCurve(
                steps, t -> x0 + dx * t, t -> y0 + dy * t, t -> z0 + dz * t);
    }

    /**
     * Traces an orthogonal path from {@code (x0, y0, z0)} (exclusive) toward {@code (x1, y1, z1)}
     * (exclusive) by advancing one unit step per iteration along whichever axis currently has the
     * largest remaining absolute delta ({@code |dx| >= |dy| && |dx| >= |dz|}, then {@code |dy| >= |dz|},
     * else {@code z}).
     *
     * @param x0 start X coordinate (excluded from returned list)
     * @param y0 start Y coordinate (excluded from returned list)
     * @param z0 start Z coordinate (excluded from returned list)
     * @param x1 target X coordinate (excluded from returned list)
     * @param y1 target Y coordinate (excluded from returned list)
     * @param z1 target Z coordinate (excluded from returned list)
     * @return ordered interior 6-connected {@code int[]{x, y, z}} steps between start and target
     */
    public static List<int[]> traceSteepestOrthogonalLine(
            int x0, int y0, int z0, int x1, int y1, int z1) {
        int manhattan = Math.abs(x1 - x0) + Math.abs(y1 - y0) + Math.abs(z1 - z0);
        if (manhattan <= 1) {
            return List.of();
        }
        List<int[]> path = new ArrayList<>(manhattan - 1);
        int x = x0;
        int y = y0;
        int z = z0;
        while (x != x1 || y != y1 || z != z1) {
            int dx = x1 - x;
            int dy = y1 - y;
            int dz = z1 - z;
            int adx = Math.abs(dx);
            int ady = Math.abs(dy);
            int adz = Math.abs(dz);
            if (adx >= ady && adx >= adz) {
                x += Integer.signum(dx);
            } else if (ady >= adz) {
                y += Integer.signum(dy);
            } else {
                z += Integer.signum(dz);
            }
            if (x == x1 && y == y1 && z == z1) {
                break;
            }
            path.add(new int[] {x, y, z});
        }
        return path;
    }
}
