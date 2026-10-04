package io.github.dailystruggle.rtp.common.commands.editor;

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
    @DisplayName("ADR-104: EditorSessionManager visualization payload includes pregenLand")
    void testVisualizationPayloadIncludesPregenLand() {
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
        assertTrue(payload.containsKey("pregenLand"), "Payload must contain 'pregenLand' layer");

        @SuppressWarnings("unchecked")
        Map<String, Object> pregenLand = (Map<String, Object>) payload.get("pregenLand");
        assertNotNull(pregenLand);
        assertTrue(pregenLand.containsKey("palette"));
        assertTrue(pregenLand.containsKey("chunks"));
        assertTrue(pregenLand.containsKey("totalGenerated"));
    }
}
