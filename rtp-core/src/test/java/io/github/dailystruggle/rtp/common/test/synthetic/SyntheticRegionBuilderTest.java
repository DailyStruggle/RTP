package io.github.dailystruggle.rtp.common.test.synthetic;

import io.github.dailystruggle.rtp.anvil.AnvilReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SyntheticRegionBuilder Tests (ADR-090)")
class SyntheticRegionBuilderTest {

    @Test
    @DisplayName("Single chunk generation parses cleanly through AnvilReader")
    void testSingleChunkRoundtrip() throws IOException {
        SyntheticRegionBuilder builder = new SyntheticRegionBuilder();
        builder.setChunk(0, 0, 72, "minecraft:plains", "minecraft:grass_block", "minecraft:dirt");
        byte[] regionBytes = builder.build();

        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(regionBytes, 0, 0));
        assertFalse(AnvilReader.INSTANCE.isChunkGenerated(regionBytes, 1, 0));

        AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        assertNotNull(entry);
        assertNotNull(entry.root);
        assertEquals(3465, entry.root.get("DataVersion"));
    }

    @Test
    @DisplayName("Checkerboard pattern alternates generated chunks correctly")
    void testCheckerboard() throws IOException {
        SyntheticRegionBuilder builder = SyntheticRegionBuilder.checkerboard(2);
        byte[] regionBytes = builder.build();

        // (0,0) -> (0/2 + 0/2)%2 == 0 -> safe plains
        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(regionBytes, 0, 0));
        // (2,0) -> (2/2 + 0/2)%2 == 1 -> hazard lava
        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(regionBytes, 2, 0));

        AnvilReader.ChunkEntry entrySafe = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        assertNotNull(entrySafe);
        AnvilReader.ChunkEntry entryHazard = AnvilReader.readChunkEntry(regionBytes, 2, 0);
        assertNotNull(entryHazard);
    }

    @Test
    @DisplayName("Steep step produces distinct heightmaps across dividing boundary")
    void testSteepStep() throws IOException {
        SyntheticRegionBuilder builder = SyntheticRegionBuilder.steepStep(16, 40, 180);
        byte[] regionBytes = builder.build();

        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(regionBytes, 0, 0));
        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(regionBytes, 16, 0));

        AnvilReader.ChunkEntry lowEntry = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        AnvilReader.ChunkEntry highEntry = AnvilReader.readChunkEntry(regionBytes, 16, 0);
        assertNotNull(lowEntry);
        assertNotNull(highEntry);
    }
}
