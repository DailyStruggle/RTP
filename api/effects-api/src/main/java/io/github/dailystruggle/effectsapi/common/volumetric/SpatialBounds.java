package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.Objects;

/**
 * Axis-aligned bounding box for volumetric calculations.
 */
public record SpatialBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public SpatialBounds {
        if (minX > maxX) {
            double t = minX; minX = maxX; maxX = t;
        }
        if (minY > maxY) {
            double t = minY; minY = maxY; maxY = t;
        }
        if (minZ > maxZ) {
            double t = minZ; minZ = maxZ; maxZ = t;
        }
    }

    public static SpatialBounds of(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new SpatialBounds(x1, y1, z1, x2, y2, z2);
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public double depth() {
        return maxZ - minZ;
    }

    public Vector3d center() {
        return new Vector3d(
                (minX + maxX) / 2.0,
                (minY + maxY) / 2.0,
                (minZ + maxZ) / 2.0
        );
    }

    public Vector3d min() {
        return new Vector3d(minX, minY, minZ);
    }

    public Vector3d max() {
        return new Vector3d(maxX, maxY, maxZ);
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(Vector3d point) {
        Objects.requireNonNull(point, "point must not be null");
        return contains(point.x(), point.y(), point.z());
    }
}
