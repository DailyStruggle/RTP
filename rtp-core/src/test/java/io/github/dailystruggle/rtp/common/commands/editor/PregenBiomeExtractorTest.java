package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.RegionFileReader;
import io.github.dailystruggle.rtp.anvil.RegionFormatRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PregenBiomeExtractorTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("test_world");
        accessor.addWorld(world);
    }

    @Test
    @DisplayName("REQ-RTP-F-013 / ADR-104: PregenBiomeExtractor extracts chunk biome data safely")
    void testExtractionNonNull() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 256L);
        square.set(GenericMemoryShapeParams.centerRadius, 64L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "test_region",
                world,
                square,
                vert,
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false
        );

        Region region = new Region("test_region", settings);

        PregenBiomeExtractor.ExtractionResult result = PregenBiomeExtractor.extract(region, 500);
        assertNotNull(result);
        assertNotNull(result.palette());
        assertFalse(result.palette().isEmpty());
        assertNotNull(result.chunks());
        assertTrue(result.totalGeneratedChunks() >= 0);
    }

    @Test
    @DisplayName("ADR-104 §4.6: region payload carries no per-region land scan; land is world-wide")
    void testVisualizationPayloadHasNoPerRegionLand() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 256L);
        square.set(GenericMemoryShapeParams.centerRadius, 64L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "test_region_viz",
                world,
                square,
                vert,
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false
        );

        Region region = new Region("test_region_viz", settings);

        Map<String, Object> payload = EditorSessionManager.getInstance().generateVisualizationPayload(region);
        assertNotNull(payload);
        assertFalse(payload.containsKey("pregenLand"), "land is surveyed world-wide, never clipped to a region");
        assertTrue(payload.containsKey("walkPathData"), "region geometry layers stay in the payload");
    }

    @Test
    @DisplayName("resolveRegionFolder scopes search to configured world folder and does not use hardcoded paths")
    void testResolveRegionFolderScoping() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 128L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "test_scope_reg",
                world,
                square,
                vert,
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false
        );
        Region region = new Region("test_scope_reg", settings);

        // When no region folder exists for "test_world", should safely return null (not fall back to unrelated folders)
        java.nio.file.Path resolved = PregenBiomeExtractor.resolveRegionFolder(region);
        assertNull(resolved);
    }

    @Test
    @DisplayName("ADR-077: unregistered .linear files are ignored and never sampled as Anvil")
    void unregisteredFormatIgnored() throws IOException {
        RegionFormatRegistry.reset();
        Path dir = Files.createDirectories(tempDir.toPath().resolve("w").resolve("region"));
        Files.write(dir.resolve("r.0.0.linear"), new byte[8192]);

        assertNull(PregenBiomeExtractor.regionFileIn(dir, "r.0.0"), "no reader for .linear, no .mca");
        Files.write(dir.resolve("r.0.0.mca"), new byte[8192]);
        assertEquals(dir.resolve("r.0.0.mca"), PregenBiomeExtractor.regionFileIn(dir, "r.0.0"));

        int[] calls = {0};
        int generated = PregenBiomeExtractor.sampleRegionFile(new byte[8192], ".linear", 0, 0,
                0, 0, 31, 31, null, (cx, cz, b) -> { calls[0]++; return true; });
        assertEquals(0, generated);
        assertEquals(0, calls[0]);
    }

    @Test
    @DisplayName("ADR-077: an addon-registered format is preferred over a stale .mca")
    void registeredFormatPreferred() throws IOException {
        Path dir = Files.createDirectories(tempDir.toPath().resolve("w2").resolve("region"));
        Files.write(dir.resolve("r.0.0.mca"), new byte[8192]);
        Files.write(dir.resolve("r.0.0.linear"), new byte[8192]);
        RegionFormatRegistry.register(".linear", new RegionFileReader() {
            @Override
            public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) {
                return null;
            }

            @Override
            public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
                return false;
            }
        });
        try {
            Path picked = PregenBiomeExtractor.regionFileIn(dir, "r.0.0");
            assertEquals(dir.resolve("r.0.0.linear"), picked);
            assertEquals(".linear", PregenBiomeExtractor.extensionOf(picked));
        } finally {
            RegionFormatRegistry.reset();
        }
    }
}
