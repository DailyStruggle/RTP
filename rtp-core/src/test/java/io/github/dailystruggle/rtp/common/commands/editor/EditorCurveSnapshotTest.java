package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CurveHash;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-106: typed shape settings, curve blocks, curveCode and hazard runs in the editor snapshot")
class EditorCurveSnapshotTest {

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

    /** A shape type with no helper anywhere in its hierarchy (stands in for Chunky / add-ons). */
    static final class NoHelperSquare extends Square {
        NoHelperSquare(String name) {
            super(name);
        }

        @Override
        public String toJavaScript() {
            return null;
        }
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

    private static JsonObject json(Map<String, Object> model) {
        return JsonParser.parseString(EditorSessionManager.mapToJson(model)).getAsJsonObject();
    }

    // ---------------------------------------------------------------------------------------------
    // Schema (§4.3)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Schema settings carry kind, description and suggestions from getParameters(); curveParams per helper shape")
    void schemaCarriesTypedSettings() {
        Factory<Shape<?>> shapes = shapes();
        shapes.add("curve_schema_circle", new CircleOptimizedDualLayer());
        shapes.add("curve_schema_nohelper", new NoHelperSquare("CURVE_SCHEMA_NOHELPER"));
        try {
            JsonObject schema = JsonParser.parseString(new EditorSessionManager().buildSchemaJson()).getAsJsonObject();
            JsonObject circle = schema.getAsJsonObject("shape").getAsJsonObject("CURVE_SCHEMA_CIRCLE");
            assertNotNull(circle, schema.toString());

            JsonObject radius = circle.getAsJsonObject("radius");
            assertEquals("integer", radius.get("type").getAsString(), "ADR-104 type kept");
            assertEquals("distance", radius.get("kind").getAsString());
            assertEquals("outer radius of region", radius.get("description").getAsString());
            JsonArray sugg = radius.getAsJsonArray("suggestions");
            assertEquals("64", sugg.get(0).getAsString(), "numbers first, ascending: " + sugg);
            List<String> s = new ArrayList<>();
            sugg.forEach(e -> s.add(e.getAsString()));
            assertTrue(s.containsAll(List.of("64", "128", "256", "512", "1024")), s.toString());
            assertTrue(s.indexOf("1024") < s.indexOf("128c"), "unit suggestions after numbers: " + s);

            // Lower-case declaration keys map onto the camelCase setting names
            assertEquals("distance", circle.getAsJsonObject("centerRadius").get("kind").getAsString());
            assertEquals("distance", circle.getAsJsonObject("centerX").get("kind").getAsString());
            assertEquals("integer", circle.getAsJsonObject("uniquePlacements").get("kind").getAsString());
            assertEquals("number", circle.getAsJsonObject("weight").get("kind").getAsString());
            assertEquals("boolean", circle.getAsJsonObject("expand").get("kind").getAsString());
            JsonObject mode = circle.getAsJsonObject("mode");
            assertEquals("enum", mode.get("kind").getAsString());
            assertTrue(mode.getAsJsonArray("suggestions").toString().contains("ACCUMULATE"), mode.toString());

            JsonObject curveParams = schema.getAsJsonObject("curveParams");
            List<String> cp = new ArrayList<>();
            curveParams.getAsJsonArray("CURVE_SCHEMA_CIRCLE").forEach(e -> cp.add(e.getAsString()));
            assertTrue(cp.containsAll(List.of("radius", "centerRadius", "centerX", "centerZ")), cp.toString());
            assertFalse(curveParams.has("CURVE_SCHEMA_NOHELPER"), "no helper, no curveParams");

            // A shape without a helper still gets its typed form
            JsonObject noHelper = schema.getAsJsonObject("shape").getAsJsonObject("CURVE_SCHEMA_NOHELPER");
            assertEquals("distance", noHelper.getAsJsonObject("radius").get("kind").getAsString());
        } finally {
            shapes.remove("curve_schema_circle");
            shapes.remove("curve_schema_nohelper");
        }
    }

    @Test
    @DisplayName("Undeclared settings carry no kind; declared values are normalised for the helper")
    void describeAndNormalise() {
        assertFalse(EditorSessionManager.describeParam(256L, null).containsKey("kind"));
        assertEquals(16L, EditorCurveModel.normalise("16c"));
        assertEquals(64L, EditorCurveModel.normalise("1024b"));
        assertEquals(0L, EditorCurveModel.normalise("0"));
        assertEquals(2L, EditorCurveModel.normalise(2.0));
        assertEquals(1.5, EditorCurveModel.normalise(1.5));
        assertEquals(256, EditorCurveModel.normalise(256));
        assertEquals(Boolean.TRUE, EditorCurveModel.normalise("true"));
        assertEquals(Boolean.FALSE, EditorCurveModel.normalise(false));
        assertEquals("ACCUMULATE", EditorCurveModel.normalise(Mode.ACCUMULATE));
        assertEquals("~", EditorCurveModel.normalise("~"));
        assertEquals("auto", EditorCurveModel.normalise("auto"));
    }

    // ---------------------------------------------------------------------------------------------
    // Region models (§4.4, §4.7)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Helper regions carry curve {shape, params, state, hash} and runs, and drop the sketch and 2D RLE")
    void helperRegionCarriesCurveBlock() {
        CircleOptimizedDualLayer circle = generic(new CircleOptimizedDualLayer("CURVE_TEST_CIRCLE"), 300, 40, -1200, 75);
        Region r = region("curve_circle_reg", circle);
        Map<String, String> code = new TreeMap<>();
        Map<String, Object> model = new EditorSessionManager().generateVisualizationPayload(r, code);
        JsonObject m = json(model);

        JsonObject curve = m.getAsJsonObject("curve");
        assertNotNull(curve, m.toString());
        assertEquals("CURVE_TEST_CIRCLE", curve.get("shape").getAsString());
        JsonObject params = curve.getAsJsonObject("params");
        assertEquals(300, params.get("radius").getAsLong());
        assertEquals(40, params.get("centerRadius").getAsLong());
        assertEquals(-1200, params.get("centerX").getAsLong());
        assertEquals(75, params.get("centerZ").getAsLong());
        JsonObject state = curve.getAsJsonObject("state");
        assertEquals(circle.getPointEdgeChunks(), state.get("p").getAsInt());
        assertEquals(circle.getEffectiveRadius(), state.get("rEff").getAsLong());
        assertEquals(CurveHash.of(circle), curve.get("hash").getAsString());
        assertTrue(curve.get("hash").getAsString().matches("[0-9a-f]{8}"));

        assertEquals(circle.toJavaScript(), code.get("CURVE_TEST_CIRCLE"), "helper source collected under curve.shape");
        assertFalse(m.has("hazards"), "2D RLE dropped for helper regions");
        assertEquals(1, m.getAsJsonObject("hazardRuns").get("v").getAsInt());
        assertEquals("", m.getAsJsonObject("hazardRuns").get("runs").getAsString(), "no hazards learned yet");
        JsonObject walk = m.getAsJsonObject("walkPathData");
        assertFalse(walk.has("points"), "2,048-point sketch dropped");
        assertTrue(walk.get("isHilbert").getAsBoolean());
        assertEquals(circle.getRange(), walk.get("range").getAsLong());

        // ADR-106 §6: ~100-200 bytes per region for the curve block
        int curveBytes = curve.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(curveBytes < 300, "curve block " + curveBytes + " bytes: " + curve);
    }

    @Test
    @DisplayName("Regions without a helper (Chunky, add-ons) keep the sketch and the 2D RLE, with no curve block")
    void noHelperRegionKeepsFallback() {
        MemoryShape<?> shape = generic(new NoHelperSquare("CURVE_TEST_NOHELPER"), 120, 10, 0, 0);
        Map<String, String> code = new TreeMap<>();
        JsonObject m = json(new EditorSessionManager().generateVisualizationPayload(region("curve_fallback_reg", shape), code));
        assertFalse(m.has("curve"), m.toString());
        assertFalse(m.has("hazardRuns"));
        assertTrue(m.has("hazards"));
        assertTrue(m.getAsJsonObject("walkPathData").getAsJsonArray("points").size() > 1);
        assertTrue(code.isEmpty(), "no curveCode entry");
    }

    @Test
    @DisplayName("curveCode holds each helper once per shape name, however many regions use it, with its SHA-256")
    void curveCodeDeduplicated() {
        EditorSessionManager manager = new EditorSessionManager();
        Map<String, String> code = new TreeMap<>();
        manager.generateVisualizationPayload(region("dedup_a", generic(new CircleOptimizedDualLayer("CURVE_DEDUP_CIRCLE"), 200, 20, 0, 0)), code);
        manager.generateVisualizationPayload(region("dedup_b", generic(new CircleOptimizedDualLayer("CURVE_DEDUP_CIRCLE"), 500, 64, 900, -900)), code);
        manager.generateVisualizationPayload(region("dedup_c", generic(new SquareOptimizedDualLayer("CURVE_DEDUP_SQUARE"), 100, 0, 0, 0)), code);
        manager.generateVisualizationPayload(region("dedup_d", generic(new NoHelperSquare("CURVE_DEDUP_NONE"), 100, 0, 0, 0)), code);
        assertEquals(List.of("CURVE_DEDUP_CIRCLE", "CURVE_DEDUP_SQUARE"), new ArrayList<>(code.keySet()));

        String curveCodeJson = EditorSessionManager.buildCurveCodeJson(code);
        JsonObject cc = JsonParser.parseString(curveCodeJson).getAsJsonObject();
        String js = new CircleOptimizedDualLayer().toJavaScript();
        JsonObject entry = cc.getAsJsonObject("CURVE_DEDUP_CIRCLE");
        assertEquals(js, entry.get("js").getAsString());
        assertEquals(EditorHttpTransport.computeSha256(js), entry.get("sha256").getAsString());
        assertTrue(entry.get("sha256").getAsString().matches("[0-9a-f]{64}"));
        int occurrences = curveCodeJson.split("\"sha256\"", -1).length - 1;
        assertEquals(2, occurrences, "one source per shape name");

        // A second class under an already used name can't share that entry: it falls back
        Map<String, Object> clash = manager.generateVisualizationPayload(
                region("dedup_e", generic(new Circle("CURVE_DEDUP_CIRCLE"), 100, 10, 0, 0)), code);
        assertFalse(clash.containsKey("curve"));
        assertTrue(clash.containsKey("hazards"));
        assertEquals(js, code.get("CURVE_DEDUP_CIRCLE"), "first source kept");
    }

    @Test
    @DisplayName("The full payload carries a top-level curveCode object")
    void payloadHasCurveCode() {
        String payload = new EditorSessionManager().createPayloadJson(Map.of("config.yml", "teleportDelay: 2\n"));
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        assertTrue(root.has("curveCode") && root.get("curveCode").isJsonObject(), "curveCode present");
        assertTrue(root.has("regionModels"));
        assertTrue(root.getAsJsonObject("schema").has("curveParams"));
    }

    // ---------------------------------------------------------------------------------------------
    // Hazard runs (§4.7)
    // ---------------------------------------------------------------------------------------------

    private static long[] t(long start, long len, long value) {
        return new long[]{start, len, value};
    }

    private static void assertRuns(List<long[]> expected, byte[] runs) {
        List<long[]> actual = EditorCurveModel.decodeHazardRuns(runs);
        assertEquals(expected.size(), actual.size(), "run count");
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), "run " + i);
    }

    @Test
    @DisplayName("Runs are [deltaStart, len, cause+1] varints; uniquePlacement is filtered, overlaps clipped")
    void hazardRunEncoding() {
        int biome = LocationGenerator.FailTypes.biome.ordinal();
        int unique = LocationGenerator.FailTypes.uniquePlacement.ordinal();
        int border = LocationGenerator.FailTypes.worldBorder.ordinal();
        int misc = LocationGenerator.FailTypes.misc.ordinal();
        long[] keys = {10, 20, 40, 42, 300};
        long[] sums = {5, 8, 12, 17, 217}; // lengths 5, 3, 4, 5, 200
        byte[] causes = {(byte) biome, (byte) unique, (byte) border, (byte) border};
        byte[] runs = EditorCurveModel.encodeHazardRuns(keys, sums, causes);
        assertRuns(List.of(
                t(10, 5, biome + 1),
                t(40, 4, border + 1),
                t(44, 3, border + 1),  // [42, 47) clipped to start after [40, 44)
                t(300, 200, misc + 1)  // cause missing -> misc
        ), runs);
        // First triple is relative to 0: 10, 5, biome+1 (one byte each); 200 needs two varint bytes
        assertEquals(10, runs[0]);
        assertEquals(5, runs[1]);
        assertEquals(1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 2 + 2 + 1, runs.length, "~3 bytes per run");

        assertEquals(0, EditorCurveModel.encodeHazardRuns(new long[0], new long[0], new byte[0]).length);
        assertEquals(0, EditorCurveModel.encodeHazardRuns(new long[]{5}, new long[]{3}, new byte[]{(byte) unique}).length,
                "spacing marks only -> empty layer");
    }

    @Test
    @DisplayName("Runs over the 256 KiB cap are refused, so the region keeps the 2D layer")
    void hazardRunCap() {
        int n = 100_000; // ~3 bytes each -> ~300 KB
        long[] keys = new long[n];
        long[] sums = new long[n];
        byte[] causes = new byte[n];
        for (int i = 0; i < n; i++) {
            keys[i] = i * 10L;
            sums[i] = (i + 1) * 2L;
        }
        assertNull(EditorCurveModel.encodeHazardRuns(keys, sums, causes));
        int fit = EditorCurveModel.MAX_HAZARD_RUN_BYTES / 3;
        byte[] ok = EditorCurveModel.encodeHazardRuns(
                java.util.Arrays.copyOf(keys, fit), java.util.Arrays.copyOf(sums, fit), java.util.Arrays.copyOf(causes, fit));
        assertNotNull(ok);
        assertTrue(ok.length <= EditorCurveModel.MAX_HAZARD_RUN_BYTES);
    }

    @Test
    @DisplayName("A region's learned bad runs appear in hazardRuns at their curve positions, spacing marks hidden")
    void regionHazardRunsFromSnapshot() {
        Square square = generic(new Square("CURVE_RUNS_SQUARE"), 64, 0, 0, 0);
        long a = square.xzToLocation(5, 6);
        long b = square.xzToLocation(-20, 11);
        long c = square.xzToLocation(30, -30);
        square.addBadLocation(a, LocationGenerator.FailTypes.biome);
        square.addBadLocation(b, LocationGenerator.FailTypes.uniquePlacement);
        square.addBadLocation(c, LocationGenerator.FailTypes.worldBorder);
        square.flushAndRebuild(1L);

        JsonObject m = json(new EditorSessionManager().generateVisualizationPayload(region("curve_runs_reg", square), new TreeMap<>()));
        byte[] runs = Base64.getDecoder().decode(m.getAsJsonObject("hazardRuns").get("runs").getAsString());
        List<long[]> decoded = EditorCurveModel.decodeHazardRuns(runs);
        Map<Long, Long> causeAt = new TreeMap<>();
        for (long[] run : decoded) {
            for (long p = run[0]; p < run[0] + run[1]; p++) causeAt.put(p, run[2]);
        }
        assertEquals(LocationGenerator.FailTypes.biome.ordinal() + 1L, causeAt.get(a), decoded.toString());
        assertEquals(LocationGenerator.FailTypes.worldBorder.ordinal() + 1L, causeAt.get(c));
        assertFalse(causeAt.containsKey(b), "uniquePlacement filtered");
        for (long[] run : decoded) {
            assertNotEquals(LocationGenerator.FailTypes.uniquePlacement.ordinal() + 1L, run[2]);
        }
    }
}
