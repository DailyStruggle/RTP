package io.github.dailystruggle.rtp.common.predict;

/**
 * Stateless, allocation-free 3D Improved Perlin gradient noise evaluator built on
 * {@link UnitCurve#smootherstep(double)} and {@link UnitCurve#linear(double, double, double)}.
 *
 * <p>Evaluates classic 3D Perlin noise over the canonical 256-entry permutation lattice,
 * interpolating 12-edge directional gradients with Perlin's quintic Hermite fade curve
 * {@code 6t^5 - 15t^4 + 10t^3}.
 *
 * <p>Zero external dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002, ADR-115).
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 */
public final class PerlinNoise {

    private static final int[] P = new int[512];

    static {
        int[] permutation = {
            151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
            140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
            247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
            57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
            74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
            60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
            65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
            200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
            52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
            207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
            119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
            129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
            218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
            81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
            184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
            222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
        };
        for (int i = 0; i < 256; i++) {
            P[256 + i] = P[i] = permutation[i];
        }
    }

    private PerlinNoise() {
    }

    /**
     * Evaluates Perlin's quintic fade curve {@link UnitCurve#smootherstep(double)}.
     *
     * @param t unit-interval fractional coordinate
     * @return quintic fade value in {@code [0.0, 1.0]}
     */
    public static double fade(double t) {
        return UnitCurve.smootherstep(t);
    }

    /**
     * Evaluates linear interpolation {@code a + t * (b - a)}.
     *
     * @param t interpolation factor in {@code [0.0, 1.0]}
     * @param a value at {@code t = 0.0}
     * @param b value at {@code t = 1.0}
     * @return interpolated value
     */
    public static double lerp(double t, double a, double b) {
        return UnitCurve.linear(t, a, b);
    }

    /**
     * Computes the dot product between the pseudo-random 12-edge gradient vector selected by
     * {@code hash & 15} and the offset vector {@code (x, y, z)}.
     *
     * @param hash lattice permutation hash
     * @param x    relative X offset from lattice corner
     * @param y    relative Y offset from lattice corner
     * @param z    relative Z offset from lattice corner
     * @return directional gradient dot product
     */
    public static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v;
        if (h < 4) {
            v = y;
        } else if (h == 12 || h == 14) {
            v = x;
        } else {
            v = z;
        }
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /**
     * Evaluates 3D Improved Perlin gradient noise at {@code (x, y, z)}.
     *
     * <p>Non-finite coordinates return {@code 0.0}. Integer lattice points always evaluate to
     * {@code 0.0}.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return continuous gradient noise value in {@code [-1.0, 1.0]}
     */
    public static double noise(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return 0.0;
        }
        double fx = Math.floor(x);
        double fy = Math.floor(y);
        double fz = Math.floor(z);
        int ix = ((int) fx) & 255;
        int iy = ((int) fy) & 255;
        int iz = ((int) fz) & 255;
        double rx = x - fx;
        double ry = y - fy;
        double rz = z - fz;
        double u = fade(rx);
        double v = fade(ry);
        double w = fade(rz);
        int a = P[ix] + iy;
        int aa = P[a] + iz;
        int ab = P[a + 1] + iz;
        int b = P[ix + 1] + iy;
        int ba = P[b] + iz;
        int bb = P[b + 1] + iz;
        return lerp(
                w,
                lerp(
                        v,
                        lerp(u, grad(P[aa], rx, ry, rz), grad(P[ba], rx - 1.0, ry, rz)),
                        lerp(u, grad(P[ab], rx, ry - 1.0, rz), grad(P[bb], rx - 1.0, ry - 1.0, rz))),
                lerp(
                        v,
                        lerp(u, grad(P[aa + 1], rx, ry, rz - 1.0), grad(P[ba + 1], rx - 1.0, ry, rz - 1.0)),
                        lerp(u, grad(P[ab + 1], rx, ry - 1.0, rz - 1.0), grad(P[bb + 1], rx - 1.0, ry - 1.0, rz - 1.0))));
    }
}
