package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral generator of 3D point geometries for volumetric particle displays.
 */
public final class GeometryPointGenerator {

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
}
