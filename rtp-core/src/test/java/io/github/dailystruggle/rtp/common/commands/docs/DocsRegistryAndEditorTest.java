package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.rtp.common.commands.editor.EditorSessionManager;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DocsRegistry and EditorSessionManager tests (ADR-045, ADR-104)")
class DocsRegistryAndEditorTest {

    @Test
    @DisplayName("Pre-initialization throws IllegalStateException (S-006)")
    void preInitThrows() {
        DocsRegistry registry = new DocsRegistry();
        assertThrows(IllegalStateException.class, () -> registry.get("index"));
        assertThrows(IllegalStateException.class, registry::getAll);
    }

    @Test
    @DisplayName("Rebuild indexes markdown files and builds synthetic index")
    void rebuildIndexesMarkdownFiles(@TempDir Path tempDir) throws IOException {
        DocsRegistry registry = new DocsRegistry();

        Path sub = tempDir.resolve("admin");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("QUICK_START.md"), "# Quick Start\nWelcome to RTP!");

        Path dev = tempDir.resolve("dev");
        Files.createDirectories(dev);
        Files.writeString(dev.resolve("INTERNAL.md"), "# Developer Internal\nSecrets.");

        // Without developer docs
        registry.rebuild(tempDir, DocsLoweringOptions.defaults());
        assertNotNull(registry.get("admin/QUICK_START.md"));
        assertTrue(registry.getAll().containsKey("index"));
        assertFalse(registry.getAll().containsKey("dev/INTERNAL.md"));

        // With developer docs
        registry.rebuild(tempDir, new DocsLoweringOptions(56, 48, true, 262144L));
        assertNotNull(registry.get("dev/INTERNAL.md"));
    }

    @Test
    @DisplayName("Local HTML export produces readable self-contained page")
    void localHtmlExport(@TempDir Path tempDir) throws IOException {
        DocsRegistry registry = new DocsRegistry();
        Files.writeString(tempDir.resolve("GUIDE.md"), "# Guide\nContent here.");
        registry.rebuild(tempDir, DocsLoweringOptions.defaults());

        Path htmlOutput = tempDir.resolve("out").resolve("index.html");
        registry.exportLocalHtmlBundle(htmlOutput);

        assertTrue(Files.exists(htmlOutput));
        String html = Files.readString(htmlOutput);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("GUIDE.md"));
    }

    @Test
    @DisplayName("EditorSessionManager creates ephemeral tokens and exports editor HTML")
    void editorSessionAndExport(@TempDir Path tempDir) throws IOException {
        EditorSessionManager manager = new EditorSessionManager();
        String token = manager.createSession("{\"test\": true}");
        assertNotNull(token);
        assertTrue(token.length() >= 16);
        assertNotNull(manager.getSession(token));

        Path editorHtml = tempDir.resolve("editor.html");
        manager.exportLocalEditorHtml(editorHtml, Map.of("config.yml", "teleport:\n  radius: 5000"));
        assertTrue(Files.exists(editorHtml));
        String content = Files.readString(editorHtml);
        assertTrue(content.contains("config.yml"));
        assertTrue(content.contains("radius: 5000"));
        assertTrue(content.contains("config-doc-tracker"));
        assertTrue(content.contains("Contextual Doc Tracker"));
        assertTrue(content.contains("trackCurrentConfigLine"));
        assertTrue(content.contains("decodeHilbertRle"));
        assertTrue(content.contains("chk-hazards"));
        assertTrue(content.contains("chk-spiral"));
        assertTrue(content.contains("generateYamlDiff"));
        assertTrue(content.contains("validateGeometry"));
        assertTrue(content.contains("segmentsIntersect"));
        assertTrue(content.contains("hotApplyWebSocket"));
        assertTrue(content.contains("ADR-034"));
        assertTrue(content.contains("copyApplyCmd"));
        assertTrue(content.contains("metric-tps"));
        assertTrue(content.contains("metric-mspt"));
        assertTrue(content.contains("metric-budget"));
        assertTrue(content.contains("metric-players"));
        assertTrue(content.contains("metric-heap"));
        assertTrue(content.contains("metric-platform"));
        assertTrue(content.contains("metric-l1"));
        assertTrue(content.contains("metric-l2"));
        assertTrue(content.contains("metric-l3"));
        assertTrue(content.contains("metric-login-reserve"));
        assertTrue(content.contains("metric-queue-depth"));
        assertTrue(content.contains("metric-pending-teleports"));
        assertTrue(content.contains("metric-lat-mean"));
        assertTrue(content.contains("metric-lat-p50"));
        assertTrue(content.contains("metric-lat-p90"));
        assertTrue(content.contains("metric-lat-p99"));
        assertTrue(content.contains("metric-spatial-eff"));
        assertTrue(content.contains("metric-ticket-leaks"));
        assertTrue(content.contains("metric-chunk-backlog"));
        assertTrue(content.contains("metric-io-threads"));
        assertTrue(content.contains("metric-db-latency"));
        assertTrue(content.contains("metric-slow-pipelines"));
        assertTrue(content.contains("metric-queue-warnings"));
        assertTrue(content.contains("region-metrics-table"));
        assertTrue(content.contains("updateDiagnosticsUI"));
    }

    @Test
    @DisplayName("Visualization layer payload generator emits biomes, hazards, heatmap, and walk path (ADR-104 Phase 4)")
    void visualizationPayloadGeneration(@TempDir Path tempDir) {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(tempDir.toFile());
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;
        try {
            EditorSessionManager manager = new EditorSessionManager();
            io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square square =
                    new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square("TEST_VIZ_SQUARE");
            square.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.radius, 1000L);
            square.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.centerRadius, 100L);
            square.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.centerX, 0L);
            square.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.centerZ, 0L);

            RegionSettings settings = new RegionSettings(
                    "viz_test_reg",
                    null,
                    square,
                    new LinearAdjustor(new ArrayList<>()),
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
            Region region = new Region("viz_test_reg", settings, true, null);

            Map<String, Object> payload = manager.generateVisualizationPayload(region);
            assertNotNull(payload);
            assertEquals("viz_test_reg", payload.get("region"));
            assertTrue(payload.containsKey("hazards"));
            assertTrue(payload.containsKey("spiralWalkPath"));
            assertTrue(payload.containsKey("walkPathData"));
            assertTrue(payload.containsKey("biomes"));
            assertTrue(payload.containsKey("heatmap"));

            @SuppressWarnings("unchecked")
            Map<String, Object> walkPathData = (Map<String, Object>) payload.get("walkPathData");
            assertNotNull(walkPathData);
            assertEquals(Square.CURVE_SPIRAL, walkPathData.get("curveType"));
            assertFalse((Boolean) walkPathData.get("isHilbert"));
            assertTrue(walkPathData.containsKey("bins"));

            @SuppressWarnings("unchecked")
            Map<String, Object> hazards = (Map<String, Object>) payload.get("hazards");
            assertNotNull(hazards.get("rleBase64"));
            assertTrue(((Number) hazards.get("byteSize")).intValue() < 5120);
            // Verify bounds are derived from shape coordinate extents rather than location count
            assertTrue((Integer) hazards.get("maxX") < 2000);
            assertTrue((Integer) hazards.get("minX") > -2000);

            Map<String, Object> delta = manager.generateScanDeltaPayload(region);
            assertNotNull(delta);
            assertEquals("viz_test_reg", delta.get("region"));
            assertNotNull(delta.get("rleBase64"));
        } finally {
            io.github.dailystruggle.rtp.common.RTP.serverAccessor = null;
        }
    }

    @Test
    @DisplayName("Dual-layer Hilbert shape generates oriented Hilbert bins in walk path payload")
    void hilbertWalkPathPayloadGeneration(@TempDir Path tempDir) {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(tempDir.toFile());
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;
        try {
            EditorSessionManager manager = new EditorSessionManager();
            io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer hilbertSquare =
                    new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer("TEST_HILBERT_SQUARE");
            hilbertSquare.set(GenericMemoryShapeParams.radius, 256L);
            hilbertSquare.set(GenericMemoryShapeParams.centerRadius, 16L);

            RegionSettings settings = new RegionSettings(
                    "hilbert_reg",
                    null,
                    hilbertSquare,
                    new LinearAdjustor(new ArrayList<>()),
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
            Region region = new Region("hilbert_reg", settings, true, null);

            Map<String, Object> payload = manager.generateVisualizationPayload(region);
            assertNotNull(payload);

            @SuppressWarnings("unchecked")
            Map<String, Object> walkPathData = (Map<String, Object>) payload.get("walkPathData");
            assertNotNull(walkPathData);
            assertEquals("SPIRAL_HILBERT", walkPathData.get("curveType"));
            assertTrue((Boolean) walkPathData.get("isHilbert"));
            assertNotNull(walkPathData.get("pointChunks"));
            assertTrue(walkPathData.containsKey("bins"));
        } finally {
            io.github.dailystruggle.rtp.common.RTP.serverAccessor = null;
        }
    }
}
