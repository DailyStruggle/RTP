package io.github.dailystruggle.effectsapi.common.volumetric;

import java.util.Objects;

/**
 * Immutable 3D vector for platform-neutral particle and geometry calculations.
 */
public record Vector3d(double x, double y, double z) {

    public static final Vector3d ZERO = new Vector3d(0, 0, 0);

    public Vector3d add(double dx, double dy, double dz) {
        return new Vector3d(x + dx, y + dy, z + dz);
    }

    public Vector3d add(Vector3d other) {
        Objects.requireNonNull(other, "other must not be null");
        return new Vector3d(x + other.x, y + other.y, z + other.z);
    }

    public Vector3d subtract(Vector3d other) {
        Objects.requireNonNull(other, "other must not be null");
        return new Vector3d(x - other.x, y - other.y, z - other.z);
    }

    public Vector3d multiply(double scalar) {
        return new Vector3d(x * scalar, y * scalar, z * scalar);
    }

    public double distanceSquared(Vector3d other) {
        Objects.requireNonNull(other, "other must not be null");
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vector3d other) {
        return Math.sqrt(distanceSquared(other));
    }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(double ox, double oy, double oz) {
        return Math.sqrt(distanceSquared(ox, oy, oz));
    }
}
