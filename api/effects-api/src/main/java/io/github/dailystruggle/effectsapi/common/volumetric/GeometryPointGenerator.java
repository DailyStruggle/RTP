package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-neutral generator of 3D point geometries for volumetric particle displays.
 *
 * @governedBy ADR-034
 * @governedBy ADR-115
 */
public final class GeometryPointGenerator {

    private static final int MAX_APERTURE_POINTS = 32;

    private GeometryPointGenerator() {
    }

    /**
     * Generates a collection of points based on the selected {@link GeometryStyle}.
     *
     * @param bounds bounding box of the volume
     * @param style geometry style
     * @param step distance/step between generated points along lines/faces
     * @param animationTick tick counter or phase parameter for dynamic styles (e.g. SWIRL, PULSE)
     * @return generated 3D points
     */
    public static List<Vector3d> generate(SpatialBounds bounds, GeometryStyle style, double step, long animationTick) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(style, "style must not be null");
        if (step <= 0.05) step = 0.5; // Guard against infinite loops or excessive granularity

        return switch (style) {
            case OUTLINE -> generateOutline(bounds, step);
            case CORNER_BRACKETS -> generateCornerBrackets(bounds, step);
            case BEAM -> generateBeam(bounds, step, animationTick);
            case SWIRL -> generateSwirl(bounds, step, animationTick);
            case PULSE -> generatePulse(bounds, step, animationTick);
            case DUST_WALL -> generateDustWall(bounds, step);
        };
    }

    /**
     * Generates points along all 12 edges of the cuboid bounding box.
     */
    public static List<Vector3d> generateOutline(SpatialBounds bounds, double step) {
        List<Vector3d> points = new ArrayList<>();
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minY = bounds.minY();
        double maxY = bounds.maxY();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        // 4 X-parallel edges
        for (double x = minX; x <= maxX; x += step) {
            points.add(new Vector3d(x, minY, minZ));
            points.add(new Vector3d(x, maxY, minZ));
            points.add(new Vector3d(x, minY, maxZ));
            points.add(new Vector3d(x, maxY, maxZ));
        }

        // 4 Y-parallel edges
        for (double y = minY; y <= maxY; y += step) {
            points.add(new Vector3d(minX, y, minZ));
            points.add(new Vector3d(maxX, y, minZ));
            points.add(new Vector3d(minX, y, maxZ));
            points.add(new Vector3d(maxX, y, maxZ));
        }

        // 4 Z-parallel edges
        for (double z = minZ; z <= maxZ; z += step) {
            points.add(new Vector3d(minX, minY, z));
            points.add(new Vector3d(maxX, minY, z));
            points.add(new Vector3d(minX, maxY, z));
            points.add(new Vector3d(maxX, maxY, z));
        }

        return Collections.unmodifiableList(points);
    }

    /**
     * Generates vertical corner pillars/beams and a central beacon column.
     */
    public static List<Vector3d> generateBeam(SpatialBounds bounds, double step, long animationTick) {
        List<Vector3d> points = new ArrayList<>();
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minY = bounds.minY();
        double maxY = bounds.maxY();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();
        Vector3d center = bounds.center();

        // Offset beam slightly by animation tick for upward movement
        double phase = (animationTick * 0.2) % step;

        for (double y = minY + phase; y <= maxY; y += step) {
            // 4 vertical corner columns
            points.add(new Vector3d(minX, y, minZ));
            points.add(new Vector3d(maxX, y, minZ));
            points.add(new Vector3d(minX, y, maxZ));
            points.add(new Vector3d(maxX, y, maxZ));
            // Center column
            points.add(new Vector3d(center.x(), y, center.z()));
        }

        return Collections.unmodifiableList(points);
    }

    /**
     * Generates a spiral / helical path ascending along the bounds.
     */
    public static List<Vector3d> generateSwirl(SpatialBounds bounds, double step, long animationTick) {
        List<Vector3d> points = new ArrayList<>();
        Vector3d center = bounds.center();
        double radiusX = bounds.width() / 2.0;
        double radiusZ = bounds.depth() / 2.0;
        if (radiusX <= 0) radiusX = 1.0;
        if (radiusZ <= 0) radiusZ = 1.0;

        double minY = bounds.minY();
        double height = bounds.height();
        if (height <= 0) height = 1.0;

        // Number of spiral points
        int count = Math.max(8, (int) (height / (step * 0.5)));
        double rotationOffset = (animationTick * 0.1) * Math.PI * 2.0;

        for (int i = 0; i <= count; i++) {
            double fraction = (double) i / count;
            double y = minY + fraction * height;
            double angle = rotationOffset + fraction * Math.PI * 4.0; // 2 full revolutions

            double x = center.x() + Math.cos(angle) * radiusX;
            double z = center.z() + Math.sin(angle) * radiusZ;
            points.add(new Vector3d(x, y, z));
        }

        return Collections.unmodifiableList(points);
    }

    /**
     * Generates concentric expanding pulse rings / shells from the volume center.
     */
    public static List<Vector3d> generatePulse(SpatialBounds bounds, double step, long animationTick) {
        List<Vector3d> points = new ArrayList<>();
        Vector3d center = bounds.center();
        double maxRadius = Math.max(bounds.width(), bounds.depth()) / 2.0;
        if (maxRadius <= 0) maxRadius = 1.0;

        // Cycle through radius from 0 to maxRadius
        double cycle = ((animationTick % 40) / 40.0);
        double currentRadius = Math.max(0.2, cycle * maxRadius);

        int ringSegments = Math.max(8, (int) (2 * Math.PI * currentRadius / step));
        double angleStep = (2 * Math.PI) / ringSegments;

        // Bottom ring, center ring, top ring
        double[] heights = {bounds.minY(), center.y(), bounds.maxY()};
        for (double y : heights) {
            for (int i = 0; i < ringSegments; i++) {
                double angle = i * angleStep;
                double x = center.x() + Math.cos(angle) * currentRadius;
                double z = center.z() + Math.sin(angle) * currentRadius;
                points.add(new Vector3d(x, y, z));
            }
        }

        return Collections.unmodifiableList(points);
    }

    /**
     * Generates planar particle grids across the 4 perimeter vertical walls of the bounding box.
     */
    public static List<Vector3d> generateDustWall(SpatialBounds bounds, double step) {
        List<Vector3d> points = new ArrayList<>();
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minY = bounds.minY();
        double maxY = bounds.maxY();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        // North & South walls (Z = minZ, Z = maxZ)
        for (double x = minX; x <= maxX; x += step) {
            for (double y = minY; y <= maxY; y += step) {
                points.add(new Vector3d(x, y, minZ));
                points.add(new Vector3d(x, y, maxZ));
            }
        }

        // East & West walls (X = minX, X = maxX, skipping already covered corners)
        for (double z = minZ + step; z < maxZ; z += step) {
            for (double y = minY; y <= maxY; y += step) {
                points.add(new Vector3d(minX, y, z));
                points.add(new Vector3d(maxX, y, z));
            }
        }

        return Collections.unmodifiableList(points);
    }

    /**
     * Generates minimal 3-axis corner bracket ticks across the 8 cuboid vertices (at most 24 points).
     * Each vertex emits 3 orthogonal arm points clamped within the cuboid edge lengths so unit
     * {@code 1x1x1} bounds never produce out-of-bounds or colliding points.
     */
    public static List<Vector3d> generateCornerBrackets(SpatialBounds bounds, double bracketLength) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        double effLen = bracketLength <= 0.05 ? 0.5 : bracketLength;
        double dx = Math.min(effLen, Math.max(0.0, bounds.width() * 0.45));
        double dy = Math.min(effLen, Math.max(0.0, bounds.height() * 0.45));
        double dz = Math.min(effLen, Math.max(0.0, bounds.depth() * 0.45));

        double[] xs = {bounds.minX(), bounds.maxX()};
        double[] ys = {bounds.minY(), bounds.maxY()};
        double[] zs = {bounds.minZ(), bounds.maxZ()};

        Set<Vector3d> points = new LinkedHashSet<>(24);
        for (int xi = 0; xi < 2; xi++) {
            double vx = xs[xi];
            double sx = (xi == 0) ? 1.0 : -1.0;
            for (int yi = 0; yi < 2; yi++) {
                double vy = ys[yi];
                double sy = (yi == 0) ? 1.0 : -1.0;
                for (int zi = 0; zi < 2; zi++) {
                    double vz = zs[zi];
                    double sz = (zi == 0) ? 1.0 : -1.0;
                    points.add(new Vector3d(vx + sx * dx, vy, vz));
                    points.add(new Vector3d(vx, vy + sy * dy, vz));
                    points.add(new Vector3d(vx, vy, vz + sz * dz));
                }
            }
        }
        return List.copyOf(points);
    }

    /**
     * Generates perimeter edge points only on bounding planes where the viewer's orthogonal
     * plane projection lies within {@code maxPlaneDistance} and {@code apertureRadius}.
     * Capped to at most {@value #MAX_APERTURE_POINTS} points.
     */
    public static List<Vector3d> generatePlaneProjectedAperture(
            SpatialBounds bounds,
            Vector3d viewerPos,
            double maxPlaneDistance,
            double apertureRadius,
            double step
    ) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(viewerPos, "viewerPos must not be null");
        if (maxPlaneDistance <= 0.0 || apertureRadius <= 0.0) {
            return List.of();
        }
        double distToHull = bounds.distanceToPlaneProjectedBounds(viewerPos);
        if (distToHull > maxPlaneDistance) {
            return List.of();
        }
        double effStep = step <= 0.1 ? 0.5 : step;
        double radiusSq = apertureRadius * apertureRadius;
        Set<Vector3d> points = new LinkedHashSet<>();

        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minY = bounds.minY();
        double maxY = bounds.maxY();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();
        double px = viewerPos.x();
        double py = viewerPos.y();
        double pz = viewerPos.z();

        // -X face (x = minX)
        double dMinX = minX - px;
        if (dMinX >= 0.0 && dMinX <= maxPlaneDistance) {
            Vector3d q = new Vector3d(minX, py, pz);
            collectFacePerimeterPointsX(minX, minY, maxY, minZ, maxZ, q, radiusSq, effStep, points);
        }
        // +X face (x = maxX)
        double dMaxX = px - maxX;
        if (dMaxX >= 0.0 && dMaxX <= maxPlaneDistance) {
            Vector3d q = new Vector3d(maxX, py, pz);
            collectFacePerimeterPointsX(maxX, minY, maxY, minZ, maxZ, q, radiusSq, effStep, points);
        }
        // -Y face (y = minY)
        double dMinY = minY - py;
        if (dMinY >= 0.0 && dMinY <= maxPlaneDistance) {
            Vector3d q = new Vector3d(px, minY, pz);
            collectFacePerimeterPointsY(minY, minX, maxX, minZ, maxZ, q, radiusSq, effStep, points);
        }
        // +Y face (y = maxY)
        double dMaxY = py - maxY;
        if (dMaxY >= 0.0 && dMaxY <= maxPlaneDistance) {
            Vector3d q = new Vector3d(px, maxY, pz);
            collectFacePerimeterPointsY(maxY, minX, maxX, minZ, maxZ, q, radiusSq, effStep, points);
        }
        // -Z face (z = minZ)
        double dMinZ = minZ - pz;
        if (dMinZ >= 0.0 && dMinZ <= maxPlaneDistance) {
            Vector3d q = new Vector3d(px, py, minZ);
            collectFacePerimeterPointsZ(minZ, minX, maxX, minY, maxY, q, radiusSq, effStep, points);
        }
        // +Z face (z = maxZ)
        double dMaxZ = pz - maxZ;
        if (dMaxZ >= 0.0 && dMaxZ <= maxPlaneDistance) {
            Vector3d q = new Vector3d(px, py, maxZ);
            collectFacePerimeterPointsZ(maxZ, minX, maxX, minY, maxY, q, radiusSq, effStep, points);
        }

        if (points.size() <= MAX_APERTURE_POINTS) {
            return List.copyOf(points);
        }
        List<Vector3d> list = new ArrayList<>(points);
        return Collections.unmodifiableList(list.subList(0, MAX_APERTURE_POINTS));
    }

    private static void collectFacePerimeterPointsX(
            double x, double minY, double maxY, double minZ, double maxZ,
            Vector3d q, double radiusSq, double step, Set<Vector3d> out
    ) {
        for (double y = minY; y <= maxY; y += step) {
            addIfWithin(new Vector3d(x, y, minZ), q, radiusSq, out);
            addIfWithin(new Vector3d(x, y, maxZ), q, radiusSq, out);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            addIfWithin(new Vector3d(x, minY, z), q, radiusSq, out);
            addIfWithin(new Vector3d(x, maxY, z), q, radiusSq, out);
        }
    }

    private static void collectFacePerimeterPointsY(
            double y, double minX, double maxX, double minZ, double maxZ,
            Vector3d q, double radiusSq, double step, Set<Vector3d> out
    ) {
        for (double x = minX; x <= maxX; x += step) {
            addIfWithin(new Vector3d(x, y, minZ), q, radiusSq, out);
            addIfWithin(new Vector3d(x, y, maxZ), q, radiusSq, out);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            addIfWithin(new Vector3d(minX, y, z), q, radiusSq, out);
            addIfWithin(new Vector3d(maxX, y, z), q, radiusSq, out);
        }
    }

    private static void collectFacePerimeterPointsZ(
            double z, double minX, double maxX, double minY, double maxY,
            Vector3d q, double radiusSq, double step, Set<Vector3d> out
    ) {
        for (double x = minX; x <= maxX; x += step) {
            addIfWithin(new Vector3d(x, minY, z), q, radiusSq, out);
            addIfWithin(new Vector3d(x, maxY, z), q, radiusSq, out);
        }
        for (double y = minY; y <= maxY; y += step) {
            addIfWithin(new Vector3d(minX, y, z), q, radiusSq, out);
            addIfWithin(new Vector3d(maxX, y, z), q, radiusSq, out);
        }
    }

    private static void addIfWithin(Vector3d pt, Vector3d center, double radiusSq, Set<Vector3d> out) {
        if (out.size() < MAX_APERTURE_POINTS && pt.distanceSquared(center) <= radiusSq) {
            out.add(pt);
        }
    }
}
