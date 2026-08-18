package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Parity gate for {@link AnvilReader#readColumnProbe}.
 *
 * <p>Guards the contract stated in
 * {@link ColumnProbe}'s javadoc: for every center-column world-Y, the probe returns the
 * same block identifier, biome identifier, and surface Y as the full-parse
 * {@link AnvilChunkView} would. Any divergence is a bug in the selective-parser filter
 * (most likely a missing or mis-pathed keep rule in
 * {@code AnvilReader#columnProbeDecision}).
 *
 * <p>Run across every {@code DataVersion} the anvil module claims to support - if only
 * one fixture is covered, a version-specific section-layout drift would slip through.
 */
class ColumnProbeParityTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1_20_R1", "1_21_R1", "26_1_R1"})
    @DisplayName("readColumnProbe yields the same answers as readChunkView at the center column for every Y")
    void probeParityAgainstFullView(String dirName) throws IOException {
        byte[] regionBytes = loadRealFixture(dirName);

        AnvilChunkView view = AnvilReader.readChunkView(regionBytes, 0, 0);
        assertNotNull(view, () -> "real fixture " + dirName + " must carry chunk (0,0)");

        // Pick a Y window covering every section the view decoded. Sections are stored
        // in on-disk order; take the min/max section-Y to avoid relying on the order.
        int minSectionY = Integer.MAX_VALUE;
        int maxSectionY = Integer.MIN_VALUE;
        for (PaletteSection s : view.sections()) {
            if (s.sectionY() < minSectionY) minSectionY = s.sectionY();
            if (s.sectionY() > maxSectionY) maxSectionY = s.sectionY();
        }
        assertNotNull(view.motionBlockingNoLeaves(),
                () -> "real fixture " + dirName + " must carry MOTION_BLOCKING_NO_LEAVES");
        if (minSectionY == Integer.MAX_VALUE) {
            fail("real fixture " + dirName + " has no decoded sections — unexpected for a populated chunk");
        }
        int minY = minSectionY * 16;
        int maxY = maxSectionY * 16 + 15;

        ColumnProbe probe = AnvilReader.readColumnProbe(regionBytes, 0, 0, minY, maxY);
        assertNotNull(probe, () -> "probe must be produced for the same chunk as the view");

        // Heightmap top at (8,8) must match AnvilChunkView's surface height.
        int viewSurface = view.getSurfaceHeight(ColumnProbe.CENTER_LOCAL_X, ColumnProbe.CENTER_LOCAL_Z);
        assertAll("heightmap parity for " + dirName,
                () -> assertEquals(true, probe.hasHeightmap(),
                        "fixture has MOTION_BLOCKING_NO_LEAVES, so probe must expose it"),
                () -> assertEquals(viewSurface, probe.heightmapTopY(),
                        "heightmap top at (8,8) must match"));

        // Per-Y block + biome identifier parity across the whole window.
        for (int y = minY; y <= maxY; y++) {
            final int yFinal = y;
            String vBlock = view.blockIdAt(
                    ColumnProbe.CENTER_LOCAL_X, y, ColumnProbe.CENTER_LOCAL_Z);
            String pBlock = probe.blockAt(y);
            assertEquals(vBlock, pBlock,
                    () -> "block parity at y=" + yFinal + " in " + dirName);

            String vBiome = view.getBiomeAt(
                    ColumnProbe.CENTER_LOCAL_X, y, ColumnProbe.CENTER_LOCAL_Z);
            String pBiome = probe.biomeAt(y);
            assertEquals(vBiome, pBiome,
                    () -> "biome parity at y=" + yFinal + " in " + dirName);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1_20_R1", "1_21_R1", "26_1_R1"})
    @DisplayName("readColumnProbe respects the [minY,maxY] window: queries outside return null")
    void probeWindowBounds(String dirName) throws IOException {
        byte[] regionBytes = loadRealFixture(dirName);
        AnvilChunkView view = AnvilReader.readChunkView(regionBytes, 0, 0);
        assertNotNull(view);

        // Choose a small window anchored at the heightmap's surface so we definitely hit
        // at least one populated section and have out-of-window Ys on both sides.
        int surface = view.getSurfaceHeight(ColumnProbe.CENTER_LOCAL_X, ColumnProbe.CENTER_LOCAL_Z);
        int minY = surface - 8;
        int maxY = surface + 8;
        ColumnProbe probe = AnvilReader.readColumnProbe(regionBytes, 0, 0, minY, maxY);
        assertNotNull(probe);

        assertEquals(null, probe.blockAt(minY - 1),
                "blockAt below minY must be null");
        assertEquals(null, probe.blockAt(maxY + 1),
                "blockAt above maxY must be null");
        assertEquals(null, probe.biomeAt(minY - 1),
                "biomeAt below minY must be null");
        assertEquals(null, probe.biomeAt(maxY + 1),
                "biomeAt above maxY must be null");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("readColumnProbe returns null for an empty chunk slot")
    void probeReturnsNullForAbsentChunk() throws IOException {
        // Build a region where only chunk (0,0) exists; probing (1,0) must return null.
        byte[] regionBytes = loadRealFixture("1_20_R1");
        ColumnProbe absent = AnvilReader.readColumnProbe(regionBytes, 5, 5, 0, 16);
        // chunk (5,5) may or may not be present in the trimmed fixture. Guard by checking
        // the underlying readChunk first - if that's null, readColumnProbe must be too.
        AnvilReader.ChunkEntry entry = AnvilReader.readChunk(regionBytes, 5, 5);
        if (entry == null) {
            assertEquals(null, absent, "probe must return null when chunk slot is empty");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("readColumnProbe rejects inverted [minY,maxY]")
    void probeRejectsInvertedWindow() throws IOException {
        byte[] regionBytes = loadRealFixture("1_20_R1");
        try {
            AnvilReader.readColumnProbe(regionBytes, 0, 0, 100, 0);
            fail("inverted window must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // ---------------------------------------------------------------------------- helpers

    private static byte[] loadRealFixture(String dirName) throws IOException {
        String resource = "/anvil/real/" + dirName + "/r.0.0.mca";
        try (InputStream in = ColumnProbeParityTest.class.getResourceAsStream(resource)) {
            if (in == null) fail("Real fixture not found on classpath: " + resource);
            return in.readAllBytes();
        }
    }

}
