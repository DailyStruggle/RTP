package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.Objects;

/**
 * Axis-aligned bounding box for volumetric calculations, 3D orthogonal plane projection,
 * and slab ray-box intersection.
 *
 * @governedBy ADR-034
 * @governedBy ADR-115
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

    /**
     * Projects a 3D point orthogonally onto (or inside) the 6 bounding planes of this cuboid.
     */
    public Vector3d projectOntoBounds(double x, double y, double z) {
        return new Vector3d(
                Math.max(minX, Math.min(maxX, x)),
                Math.max(minY, Math.min(maxY, y)),
                Math.max(minZ, Math.min(maxZ, z))
        );
    }

    /**
     * Projects a 3D point orthogonally onto (or inside) the 6 bounding planes of this cuboid.
     */
    public Vector3d projectOntoBounds(Vector3d point) {
        Objects.requireNonNull(point, "point must not be null");
        return projectOntoBounds(point.x(), point.y(), point.z());
    }

    /**
     * Computes the Euclidean distance from {@code (x, y, z)} to its orthogonal projection
     * onto the 6 bounding planes of this cuboid. Returns {@code 0.0} when inside or on the boundary.
     */
    public double distanceToPlaneProjectedBounds(double x, double y, double z) {
        double dx = Math.max(0.0, Math.max(minX - x, x - maxX));
        double dy = Math.max(0.0, Math.max(minY - y, y - maxY));
        double dz = Math.max(0.0, Math.max(minZ - z, z - maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Computes the Euclidean distance from {@code point} to its orthogonal projection
     * onto the 6 bounding planes of this cuboid.
     */
    public double distanceToPlaneProjectedBounds(Vector3d point) {
        Objects.requireNonNull(point, "point must not be null");
        return distanceToPlaneProjectedBounds(point.x(), point.y(), point.z());
    }

    /**
     * Computes the forward 3D slab ray-box intersection distance {@code t >= 0} (in blocks)
     * from {@code origin} along {@code direction}, or {@link Double#POSITIVE_INFINITY} if the ray misses.
     */
    public double rayIntersectionDistance(Vector3d origin, Vector3d direction) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        return rayIntersectionDistance(
                origin.x(), origin.y(), origin.z(),
                direction.x(), direction.y(), direction.z()
        );
    }

    /**
     * Computes the forward 3D slab ray-box intersection distance {@code t >= 0} (in blocks)
     * from {@code (ox, oy, oz)} along {@code (dx, dy, dz)}, or {@link Double#POSITIVE_INFINITY} if the ray misses.
     */
    public double rayIntersectionDistance(double ox, double oy, double oz, double dx, double dy, double dz) {
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq <= 1.0e-12 || !Double.isFinite(lenSq)) {
            return contains(ox, oy, oz) ? 0.0 : Double.POSITIVE_INFINITY;
        }
        double invLen = 1.0 / Math.sqrt(lenSq);
        double ux = dx * invLen;
        double uy = dy * invLen;
        double uz = dz * invLen;

        double tNear = Double.NEGATIVE_INFINITY;
        double tFar = Double.POSITIVE_INFINITY;

        if (Math.abs(ux) < 1.0e-12) {
            if (ox < minX || ox > maxX) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double t1 = (minX - ox) / ux;
            double t2 = (maxX - ox) / ux;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }

        if (Math.abs(uy) < 1.0e-12) {
            if (oy < minY || oy > maxY) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double t1 = (minY - oy) / uy;
            double t2 = (maxY - oy) / uy;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }

        if (Math.abs(uz) < 1.0e-12) {
            if (oz < minZ || oz > maxZ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double t1 = (minZ - oz) / uz;
            double t2 = (maxZ - oz) / uz;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }

        if (tNear > tFar || tFar < 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, tNear);
    }
}
