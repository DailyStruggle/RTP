package io.github.dailystruggle.rtp.api.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChunkColumnProbe adapter tests")
class ChunkColumnProbeTest {

    @Test
    void testChunkColumnProbeOf() {
        assertNull(ChunkColumnProbe.of(null));

        io.github.dailystruggle.rtp.anvil.ChunkColumnProbe baseProbe = new io.github.dailystruggle.rtp.anvil.ChunkColumnProbe() {
            @Override public int chunkX() { return 5; }
            @Override public int chunkZ() { return 7; }
            @Override public int minY() { return -64; }
            @Override public int maxY() { return 320; }
            @Override public OptionalInt heightmapTopY() { return OptionalInt.of(100); }
            @Override public String blockAt(int y) { return "minecraft:stone"; }
            @Override public String blockAt(int lx, int lz, int y) { return "minecraft:dirt"; }
            @Override public String biomeAt(int y) { return "minecraft:plains"; }
            @Override public boolean isAirAt(int y) { return y > 100; }
            @Override public boolean isAirAt(int lx, int lz, int y) { return y > 100; }
        };

        ChunkColumnProbe wrapped = ChunkColumnProbe.of(baseProbe);
        assertNotNull(wrapped);
        assertEquals(5, wrapped.chunkX());
        assertEquals(7, wrapped.chunkZ());
        assertEquals(-64, wrapped.minY());
        assertEquals(320, wrapped.maxY());
        assertTrue(wrapped.heightmapTopY().isPresent());
        assertEquals(100, wrapped.heightmapTopY().getAsInt());
        assertEquals("minecraft:stone", wrapped.blockAt(50));
        assertEquals("minecraft:dirt", wrapped.blockAt(1, 2, 50));
        assertEquals("minecraft:plains", wrapped.biomeAt(50));
        assertFalse(wrapped.isAirAt(50));
        assertTrue(wrapped.isAirAt(101));
        assertFalse(wrapped.isAirAt(1, 2, 50));
        assertTrue(wrapped.isAirAt(1, 2, 101));

        // Identity check when already ChunkColumnProbe
        assertSame(wrapped, ChunkColumnProbe.of(wrapped));
    }
}
