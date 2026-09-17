package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkColumnProbeTest {

    @Test
    @DisplayName("default methods blockAt(x, z, y) and isAirAt evaluate correctly")
    void testDefaults() {
        ChunkColumnProbe probe = new ChunkColumnProbe() {
            @Override public int chunkX() { return 1; }
            @Override public int chunkZ() { return 2; }
            @Override public int minY() { return -64; }
            @Override public int maxY() { return 320; }
            @Override public OptionalInt heightmapTopY() { return OptionalInt.of(64); }
            @Override
            public String blockAt(int y) {
                if (y == 0) return "minecraft:air";
                if (y == 1) return "cave_air";
                if (y == 2) return "mod:void_air";
                if (y == 3) return "minecraft:stone";
                return null;
            }
            @Override
            public String biomeAt(int y) {
                return "minecraft:plains";
            }
        };

        assertEquals(1, probe.chunkX());
        assertEquals(2, probe.chunkZ());
        assertEquals(-64, probe.minY());
        assertEquals(320, probe.maxY());
        assertEquals(OptionalInt.of(64), probe.heightmapTopY());
        assertEquals("minecraft:plains", probe.biomeAt(10));

        // blockAt(x, z, y) delegates to blockAt(y)
        assertEquals("minecraft:air", probe.blockAt(5, 5, 0));
        assertEquals("minecraft:stone", probe.blockAt(2, 2, 3));

        // isAirAt(y)
        assertTrue(probe.isAirAt(0));
        assertTrue(probe.isAirAt(1));
        assertTrue(probe.isAirAt(2));
        assertFalse(probe.isAirAt(3));
        assertFalse(probe.isAirAt(4));

        // isAirAt(x, z, y)
        assertTrue(probe.isAirAt(5, 5, 0));
        assertTrue(probe.isAirAt(5, 5, 1));
        assertTrue(probe.isAirAt(5, 5, 2));
        assertFalse(probe.isAirAt(5, 5, 3));
        assertFalse(probe.isAirAt(5, 5, 4));
    }
}
