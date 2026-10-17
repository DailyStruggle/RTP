package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Captures a snapshot built by the plugin's own serializers (schema, curveCode, region models with
 * curve blocks and hazard runs) for the headless page probe {@code scripts/editor_curve_probe.py}.
 * The page must reproduce the Java CurveHash of these regions from the shipped helpers.
 */
@DisplayName("ADR-106: captured editor snapshot for the headless curve probe")
class EditorProbePayloadFixtureTest {

    static final Path OUT = Path.of("build", "editor-probe", "payload.json");

    @TempDir
    Path tempDir;

    @BeforeEach
    void accessor() {
        RTP.serverAccessor = new MockRTPServerAccessor(tempDir.toFile());
    }

    @AfterEach
    void clearAccessor() {
        RTP.serverAccessor = null;
    }

    @SuppressWarnings("unchecked")
    private static Factory<Shape<?>> shapes() {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapes);
        }
        return shapes;
    }

    private static Region region(String name, MemoryShape<?> shape) {
        RegionSettings settings = new RegionSettings(name, null, shape, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 100L, 0L, 5, 0.0, 1L, "", false);
        return new Region(name, settings, true, null);
    }

    private static <S extends MemoryShape<GenericMemoryShapeParams>> S generic(S shape, long r, long cr, long cx, long cz) {
        shape.set(GenericMemoryShapeParams.radius, r);
        shape.set(GenericMemoryShapeParams.centerRadius, cr);
        shape.set(GenericMemoryShapeParams.centerX, cx);
        shape.set(GenericMemoryShapeParams.centerZ, cz);
        return shape;
    }

    private static String regionYaml(String shape, long r, long cr, long cx, long cz) {
        return "world: \"[0]\"\nshape:\n  name: \"" + shape + "\"\n  radius: " + r + "\n  centerRadius: " + cr
                + "\n  centerX: " + cx + "\n  centerZ: " + cz + "\n";
    }

    @Test
    @DisplayName("Writes build/editor-probe/payload.json with verified-ready helper regions")
    void writeProbePayload() throws Exception {
        Factory<Shape<?>> shapes = shapes();
        shapes.add("probe_circle", new CircleOptimizedDualLayer("PROBE_CIRCLE"));
        shapes.add("probe_square", new SquareOptimizedDualLayer("PROBE_SQUARE"));
        try {
            EditorSessionManager manager = new EditorSessionManager();
            CircleOptimizedDualLayer circle = generic(new CircleOptimizedDualLayer("PROBE_CIRCLE"), 300, 40, -12, 7);
            long range = circle.getRange();
            circle.addBadLocation(range / 3, LocationGenerator.FailTypes.biome);
            circle.addBadLocation(range / 2, LocationGenerator.FailTypes.uniquePlacement);
            circle.addBadLocation(2 * range / 3, LocationGenerator.FailTypes.worldBorder);
            circle.flushAndRebuild(1L);
            SquareOptimizedDualLayer square = generic(new SquareOptimizedDualLayer("PROBE_SQUARE"), 200, 16, 30, -30);

            Map<String, String> code = new TreeMap<>();
            Map<String, Object> models = new LinkedHashMap<>();
            models.put("default", manager.generateVisualizationPayload(region("default", circle), code));
            models.put("probe_square", manager.generateVisualizationPayload(region("probe_square", square), code));

            Map<String, Object> files = new LinkedHashMap<>();
            files.put("regions/default.yml", regionYaml("PROBE_CIRCLE", 300, 40, -12, 7));
            files.put("regions/probe_square.yml", regionYaml("PROBE_SQUARE", 200, 16, 30, -30));

            String payload = "{\"version\":1,\"files\":" + EditorSessionManager.mapToJson(files)
                    + ",\"schema\":" + manager.buildSchemaJson()
                    + ",\"curveCode\":" + EditorSessionManager.buildCurveCodeJson(code)
                    + ",\"regionModels\":" + EditorSessionManager.mapToJson(models) + "}";

            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            assertTrue(root.getAsJsonObject("curveCode").has("PROBE_CIRCLE"));
            assertTrue(root.getAsJsonObject("curveCode").has("PROBE_SQUARE"));
            JsonObject def = root.getAsJsonObject("regionModels").getAsJsonObject("default");
            assertEquals("PROBE_CIRCLE", def.getAsJsonObject("curve").get("shape").getAsString());
            assertFalse(def.getAsJsonObject("hazardRuns").get("runs").getAsString().isEmpty(), "hazard runs present");
            assertTrue(root.getAsJsonObject("schema").getAsJsonObject("shape").has("PROBE_CIRCLE"));

            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, payload, StandardCharsets.UTF_8);
            assertTrue(Files.size(OUT) > 0);
        } finally {
            shapes.remove("probe_circle");
            shapes.remove("probe_square");
        }
    }
}
