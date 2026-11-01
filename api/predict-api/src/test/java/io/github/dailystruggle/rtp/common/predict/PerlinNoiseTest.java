package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit verification for {@link PerlinNoise}.
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 */
@DisplayName("REQ-PRED-F-013: PerlinNoise 3D Gradient Noise Invariants")
class PerlinNoiseTest {

    @Test
    @DisplayName("noise vanishes at integer lattice points, stays within [-1, 1], and is continuous")
    void latticeZeroBoundsAndContinuity() {
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    assertEquals(0.0, PerlinNoise.noise(x, y, z), 1e-12);
                }
            }
        }

        for (int i = 0; i < 200; i++) {
            double x = i * 0.137 - 5.0;
            double y = i * 0.271 + 1.3;
            double z = i * 0.419 - 2.7;
            double v = PerlinNoise.noise(x, y, z);
            assertTrue(v >= -1.0 && v <= 1.0, "Perlin noise must stay within [-1, 1], got " + v);
            double nearby = PerlinNoise.noise(x + 1e-4, y - 1e-4, z + 1e-4);
            assertEquals(v, nearby, 1e-3, "Perlin noise must be locally Lipschitz continuous");
        }

        assertEquals(0.0, PerlinNoise.noise(Double.NaN, 1.0, 2.0), 1e-12);
        assertEquals(0.0, PerlinNoise.noise(1.0, Double.POSITIVE_INFINITY, 2.0), 1e-12);
        assertEquals(0.5, PerlinNoise.fade(0.5), 1e-12);
        assertEquals(6.0, PerlinNoise.lerp(0.5, 2.0, 10.0), 1e-12);
    }
}
