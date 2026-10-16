package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-077 Ground-Truth Parity Gate.
 *
 * <p>Verifies that {@link LinearRegionReader} decodes real-world server-produced region data
 * converted via the canonical reference tool (LinearRegionFileFormatTools) with 100% structural
 * and semantic parity compared to {@link AnvilReader}.</p>
 */
@DisplayName("LinearRealDataParityTest - Differential Parity Against Real Minecraft Data")
class LinearRealDataParityTest {

    private static byte[] loadFixture(String resourcePath) throws IOException {
        try (InputStream in = LinearRealDataParityTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                fail("Real fixture not found on classpath: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private static Stream<Arguments> realFixtures() {
        return Stream.of(
                Arguments.of("1_20_R1", 3465),
                Arguments.of("1_21_R1", 4671),
                Arguments.of("26_1_R1", 4788));
    }

    private static Stream<String> realFixtureDirs() {
        return realFixtures().map(a -> (String) a.get()[0]);
    }

    private static AnvilReader.ChunkEntry[] decodeOriginEntries(String dirName) throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/" + dirName + "/r.0.0.mca");
        byte[] linearBytes = loadFixture("/anvil/real/" + dirName + "/r.0.0.linear");
        AnvilReader.ChunkEntry mcaEntry = AnvilReader.readChunkEntry(mcaBytes, 0, 0);
        AnvilReader.ChunkEntry linearEntry = LinearRegionReader.INSTANCE.readChunk(linearBytes, 0, 0);
        assertNotNull(mcaEntry, "MCA chunk entry (0,0) must not be null");
        assertNotNull(linearEntry, "Linear chunk entry (0,0) must not be null");
        return new AnvilReader.ChunkEntry[] {mcaEntry, linearEntry};
    }

    @ParameterizedTest(name = "real fixture {0} chunk allocation matches between MCA and Linear")
    @MethodSource("realFixtureDirs")
    @DisplayName("Real Linear fixtures match AnvilReader chunk allocation status")
    void realLinearMatchesAnvilAllocation(String dirName) throws IOException {
        byte[] mcaBytes = loadFixture("/anvil/real/" + dirName + "/r.0.0.mca");
        byte[] linearBytes = loadFixture("/anvil/real/" + dirName + "/r.0.0.linear");

        LinearRegionReader linearReader = LinearRegionReader.INSTANCE;

        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(mcaBytes, 0, 0), "MCA chunk (0,0) must be generated");
        assertTrue(linearReader.isChunkGenerated(linearBytes, 0, 0), "Linear chunk (0,0) must be generated");
        assertTrue(linearReader.isChunkGenerated(linearBytes, linearBytes.length, 0, 0));

        assertFalse(linearReader.isChunkGenerated(linearBytes, 1, 0), "Linear chunk (1,0) should not be generated");
        assertFalse(linearReader.isChunkGenerated(linearBytes, 0, 1), "Linear chunk (0,1) should not be generated");
        assertFalse(linearReader.isChunkGenerated(linearBytes, 31, 31), "Linear chunk (31,31) should not be generated");

        // Unallocated chunk returns null
        assertNull(linearReader.readChunk(linearBytes, 1, 0));
        assertNull(linearReader.readChunk(linearBytes, 0, 1));
        assertNull(linearReader.readChunk(linearBytes, 31, 31));
    }

    @ParameterizedTest(name = "real fixture {0} (DataVersion={1}) decodes identically between MCA and Linear")
    @MethodSource("realFixtures")
    @DisplayName("Real Linear fixtures match AnvilReader NBT structure")
    void realLinearMatchesAnvilNbtStructure(String dirName, int expectedDataVersion) throws IOException {
        AnvilReader.ChunkEntry[] entries = decodeOriginEntries(dirName);
        AnvilReader.ChunkEntry mcaEntry = entries[0];
        AnvilReader.ChunkEntry linearEntry = entries[1];

        int mcaDv = AnvilReader.getDataVersion(mcaEntry.root);
        int linearDv = AnvilReader.getDataVersion(linearEntry.root);

        assertEquals(expectedDataVersion, linearDv, "Linear DataVersion must match expected");
        assertEquals(mcaDv, linearDv, "Linear DataVersion must match Anvil DataVersion");

        assertEquals(mcaEntry.root.get("xPos"), linearEntry.root.get("xPos"), "xPos parity");
        assertEquals(mcaEntry.root.get("zPos"), linearEntry.root.get("zPos"), "zPos parity");

        Nbt.NbtList mcaSections = AnvilReader.getSections(mcaEntry.root);
        Nbt.NbtList linearSections = AnvilReader.getSections(linearEntry.root);
        assertNotNull(linearSections, "Linear sections list must not be null");
        assertEquals(mcaSections.items.size(), linearSections.items.size(), "Sections count parity");

        long[] mcaHeightmap = AnvilReader.getMotionBlockingNoLeaves(mcaEntry.root);
        long[] linearHeightmap = AnvilReader.getMotionBlockingNoLeaves(linearEntry.root);
        assertNotNull(linearHeightmap, "Linear heightmap must not be null");
        assertNotNull(mcaHeightmap, "MCA heightmap must not be null");
        assertEquals(mcaHeightmap.length, linearHeightmap.length, "Heightmap length parity");
    }

    @ParameterizedTest(name = "real fixture {0} typed chunk view matches between MCA and Linear")
    @MethodSource("realFixtureDirs")
    @DisplayName("Real Linear fixtures match AnvilReader typed chunk view")
    void realLinearMatchesAnvilChunkView(String dirName) throws IOException {
        AnvilReader.ChunkEntry[] entries = decodeOriginEntries(dirName);
        AnvilReader.ChunkEntry mcaEntry = entries[0];
        AnvilReader.ChunkEntry linearEntry = entries[1];

        AnvilChunkView mcaView = AnvilReader.toView(mcaEntry.root);
        AnvilChunkView linearView = AnvilReader.toView(linearEntry.root);

        assertNotNull(linearView, "Linear ChunkView must not be null");
        assertEquals(mcaView.minHeight(), linearView.minHeight(), "minHeight parity");
        assertEquals(mcaView.sections().size(), linearView.sections().size(), "Emitted sections count parity");

        for (PaletteSection s : mcaView.sections()) {
            assertTrue(linearView.hasSection(s.sectionY()), () -> "Linear view missing section Y=" + s.sectionY());
        }

        // Verify block states across center column from min to max section Y
        int minY = mcaView.minHeight();
        int maxY = minY + (mcaView.sections().size() * 16) - 1;
        for (int y = minY; y <= maxY; y++) {
            final int curY = y;
            String mcaBlock = mcaView.blockIdAt(8, curY, 8);
            String linearBlock = linearView.blockIdAt(8, curY, 8);
            assertEquals(mcaBlock, linearBlock, () -> "Block mismatch at y=" + curY);
        }

        // Verify biomes parity
        assertEquals(mcaView.getBiomesPresent(), linearView.getBiomesPresent(), "Biomes set parity");
    }

    @Test
    @DisplayName("CBIT tripwires enforce corrupt Linear file rejection on real fixture")
    void cbitTripwiresOnRealData() throws IOException {
        byte[] linearBytes = loadFixture("/anvil/real/1_20_R1/r.0.0.linear");
        LinearRegionReader reader = LinearRegionReader.INSTANCE;

        // T1: Corrupt superblock signature
        byte[] badHeader = Arrays.copyOf(linearBytes, linearBytes.length);
        badHeader[0] ^= (byte) 0xFF;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badHeader, 0, 0));
        assertFalse(reader.isChunkGenerated(badHeader, 0, 0));

        // T2: Corrupt footer signature
        byte[] badFooter = Arrays.copyOf(linearBytes, linearBytes.length);
        badFooter[badFooter.length - 1] ^= (byte) 0xFF;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(badFooter, 0, 0));

        // T3: Truncated file buffer
        byte[] truncated = Arrays.copyOf(linearBytes, 20);
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(truncated, 0, 0));
        assertFalse(reader.isChunkGenerated(truncated, 0, 0));

        // Coordinate out of bounds
        assertThrows(IllegalArgumentException.class, () -> reader.readChunk(linearBytes, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> reader.readChunk(linearBytes, 32, 0));
    }

    @Test
    @DisplayName("Multi-chunk real world region file differential parity (1024 chunks)")
    void multiChunkRealWorldParity() throws IOException {
        Path mcaPath = Paths.get("testdata-world/overworld/region/r.-1.-1.mca");
        Path linearPath = Paths.get("scripts/tmp_r.-1.-1.linear");
        if (!Files.isRegularFile(mcaPath) || !Files.isRegularFile(linearPath)) {
            return; // Skip if local test corpus is not present
        }

        byte[] mcaBytes = Files.readAllBytes(mcaPath);
        byte[] linearBytes = Files.readAllBytes(linearPath);
        LinearRegionReader linearReader = LinearRegionReader.INSTANCE;

        // 1. Check all 1024 chunk positions for generation parity
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                boolean mcaGen = AnvilReader.INSTANCE.isChunkGenerated(mcaBytes, cx, cz);
                boolean linearGen = linearReader.isChunkGenerated(linearBytes, cx, cz);
                assertEquals(mcaGen, linearGen, "isChunkGenerated mismatch at (" + cx + "," + cz + ")");
            }
        }

        // 2. Sample chunks across the file and assert deep structural parity
        int sampled = 0;
        for (int cz = 0; cz < 32; cz += 4) {
            for (int cx = 0; cx < 32; cx += 4) {
                if (linearReader.isChunkGenerated(linearBytes, cx, cz)) {
                    AnvilReader.ChunkEntry mcaEntry = AnvilReader.readChunkEntry(mcaBytes, cx, cz);
                    AnvilReader.ChunkEntry linearEntry = linearReader.readChunk(linearBytes, cx, cz);

                    assertNotNull(mcaEntry);
                    assertNotNull(linearEntry);
                    assertEquals(AnvilReader.getDataVersion(mcaEntry.root), AnvilReader.getDataVersion(linearEntry.root));
                    assertEquals(mcaEntry.root.get("xPos"), linearEntry.root.get("xPos"));
                    assertEquals(mcaEntry.root.get("zPos"), linearEntry.root.get("zPos"));

                    AnvilChunkView mcaView = AnvilReader.toView(mcaEntry.root);
                    AnvilChunkView linearView = AnvilReader.toView(linearEntry.root);
                    assertEquals(mcaView.minHeight(), linearView.minHeight());
                    assertEquals(mcaView.sections().size(), linearView.sections().size());
                    assertEquals(mcaView.getBiomesPresent(), linearView.getBiomesPresent());
                    sampled++;
                }
            }
        }
        assertTrue(sampled > 0, "Must have sampled at least one generated chunk");
    }
}
