package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryNames;
import io.github.dailystruggle.rtp.common.factory.FactoryRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-104 / S-004 / S-006: EditorSessionManager payload, validation, apply and snapshot branches")
class EditorSessionManagerBranchCoverageTest {

    private static final String WORLD = "esm_branch_world";

    @TempDir
    Path tempDir;
    private Path pluginDir;
    private final List<String> addedRegions = new ArrayList<>();
    private final List<String> addedShapes = new ArrayList<>();

    /** Shape type with no curve helper (stands in for add-on shapes). */
    static final class NoHelperSquare extends Square {
        NoHelperSquare(String name) {
            super(name);
        }

        @Override
        public String toJavaScript() {
            return null;
        }
    }

    /** Shape whose helper source differs from its parent class under the same registered name. */
    static final class OtherHelperCircle extends CircleOptimizedDualLayer {
        OtherHelperCircle(String name) {
            super(name);
        }

        @Override
        public String toJavaScript() {
            return "/* other helper */" + super.toJavaScript();
        }
    }

    enum Colour { RED, GREEN }

    @BeforeEach
    void setUp() {
        File dir = tempDir.resolve("plugins/RTP").toFile();
        dir.mkdirs();
        RTPTestSetup.install(dir);
        pluginDir = RTPRuntime.serverAccessor().getPluginDirectory().toPath();
        WorldBiomeStore.clearForTests();
    }

    @AfterEach
    void tearDown() {
        if (ActiveSelection.get() != null) addedRegions.forEach(ActiveSelection.get().permRegionLookup::remove);
        Factory<Shape<?>> shapes = shapes();
        addedShapes.forEach(shapes::remove);
        WorldBiomeStore.clearForTests();
        RTPTestSetup.cleanUp();
    }

    @SuppressWarnings("unchecked")
    private static Factory<Shape<?>> shapes() {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) FactoryRegistry.get(FactoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            FactoryRegistry.map().put(FactoryNames.shape, shapes);
        }
        return shapes;
    }

    private static <S extends MemoryShape<GenericMemoryShapeParams>> S generic(S shape, long r, long cr) {
        shape.set(GenericMemoryShapeParams.radius, r);
        shape.set(GenericMemoryShapeParams.centerRadius, cr);
        shape.set(GenericMemoryShapeParams.centerX, 0L);
        shape.set(GenericMemoryShapeParams.centerZ, 0L);
        return shape;
    }

    private static Region region(String name, Shape<?> shape, MockRTPWorld world) {
        RegionSettings settings = new RegionSettings(name, world, shape, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 100L, 0L, 5, 0.0, 1L, "", false);
        return new Region(name, settings, true, null);
    }

    private Region register(String name, Shape<?> shape, MockRTPWorld world) {
        Region r = region(name, shape, world);
        ActiveSelection.get().permRegionLookup.put(name, r);
        addedRegions.add(name);
        return r;
    }

    private static String filesBlockPayload(String filesBlock, String sha) {
        return "{\"version\":1,\"sha256\":\"" + sha + "\",\"files\":" + filesBlock + "}";
    }

    // ---- visualization / walk path / scan delta ----

    @Test
    @DisplayName("Walk path payload: null shape empty; points in block units; Hilbert carries pointChunks")
    void walkPathPayload() {
        EditorSessionManager m = new EditorSessionManager();
        assertTrue(m.generateWalkPathPayload(null).isEmpty());

        Square square = generic(new Square("ESM_WALK_SQUARE"), 64, 0);
        Map<String, Object> data = m.generateWalkPathPayload(square);
        assertEquals("block", data.get("units"));
        assertEquals(square.getRange(), data.get("range"));
        List<?> points = (List<?>) data.get("points");
        assertFalse(points.isEmpty());
        assertTrue(points.size() <= EditorSessionManager.WALK_PATH_MAX_POINTS);
        boolean hilbert = MemoryShape.CURVE_SPIRAL_HILBERT.equals(square.getCurveName());
        assertEquals(hilbert, data.get("isHilbert"));
        assertEquals(hilbert, data.containsKey("pointChunks"));

        CircleOptimizedDualLayer circle = generic(new CircleOptimizedDualLayer("ESM_WALK_CIRCLE"), 200, 20);
        Map<String, Object> cd = m.generateWalkPathPayload(circle);
        if (Boolean.TRUE.equals(cd.get("isHilbert"))) {
            assertTrue(((Number) cd.get("pointChunks")).intValue() > 0);
        }
    }

    @Test
    @DisplayName("Scan delta: null and non-memory regions empty; memory region carries the RLE grid")
    void scanDeltaPayload() {
        EditorSessionManager m = new EditorSessionManager();
        assertTrue(m.generateScanDeltaPayload(null).isEmpty());
        Region shapeless = region("esm_delta_none", null, null);
        Map<String, Object> shapelessDelta = m.generateScanDeltaPayload(shapeless);
        // RegionSettings may substitute a default shape; only a non-memory shape yields an empty delta.
        if (shapeless.shape instanceof MemoryShape<?>) {
            assertEquals("esm_delta_none", shapelessDelta.get("region"));
        } else {
            assertTrue(shapelessDelta.isEmpty());
        }

        Map<String, Object> delta = m.generateScanDeltaPayload(
                region("esm_delta", generic(new Square("ESM_DELTA"), 64, 0), null));
        assertEquals("esm_delta", delta.get("region"));
        assertTrue(delta.containsKey("rleBase64"));
        assertTrue(delta.containsKey("byteSize"));
        assertTrue(delta.containsKey("runCount"));
        assertTrue(delta.containsKey("timestamp"));
    }

    @Test
    @DisplayName("Visualization: helper region without curveCode keeps curve; no-helper region samples biomes and hazards")
    void visualizationBranches() {
        EditorSessionManager m = new EditorSessionManager();

        Map<String, Object> helper = m.generateVisualizationPayload(
                region("esm_vis_helper", generic(new CircleOptimizedDualLayer("ESM_VIS_CIRCLE"), 300, 40), null));
        assertTrue(helper.containsKey("curve"), helper.keySet().toString());
        assertTrue(helper.containsKey("hazardRuns") || helper.containsKey("hazardRunsSkipped"));
        assertFalse(((Map<?, ?>) helper.get("walkPathData")).containsKey("points"), "header only");
        assertTrue(helper.containsKey("biomes"));
        assertEquals("chunk", ((Map<?, ?>) helper.get("heatmap")).get("units"));

        Map<String, Object> plain = m.generateVisualizationPayload(
                region("esm_vis_plain", generic(new NoHelperSquare("ESM_VIS_PLAIN"), 120, 0), null), new TreeMap<>());
        assertFalse(plain.containsKey("curve"));
        Map<?, ?> hazards = (Map<?, ?>) plain.get("hazards");
        for (String k : new String[]{"units", "minX", "minZ", "maxX", "maxZ", "order", "gridSize", "rleBase64",
                "byteSize", "runCount"}) {
            assertTrue(hazards.containsKey(k), k);
        }
        assertTrue(((Map<?, ?>) plain.get("walkPathData")).containsKey("points"));
        assertNotNull(plain.get("biomes"));
    }

    @Test
    @DisplayName("Same helper source under one name is shared; a different source under it falls back")
    void curveCodeSharedAndClash() {
        EditorSessionManager m = new EditorSessionManager();
        Map<String, String> code = new TreeMap<>();
        Map<String, Object> a = m.generateVisualizationPayload(
                region("esm_cc_a", generic(new CircleOptimizedDualLayer("ESM_CC"), 200, 20), null), code);
        Map<String, Object> b = m.generateVisualizationPayload(
                region("esm_cc_b", generic(new CircleOptimizedDualLayer("ESM_CC"), 300, 30), null), code);
        assertTrue(a.containsKey("curve"));
        assertTrue(b.containsKey("curve"), "identical source shares the entry");
        assertEquals(1, code.size());

        Map<String, Object> clash = m.generateVisualizationPayload(
                region("esm_cc_c", generic(new OtherHelperCircle("ESM_CC"), 200, 20), null), code);
        assertFalse(clash.containsKey("curve"));
        assertTrue(clash.containsKey("hazards"));
    }

    @Test
    @DisplayName("Region models cover every registered region; null-free lookup serialises to JSON")
    void regionModelsJson() {
        register("esm_models_a", generic(new Square("ESM_MODELS_A"), 64, 0), null);
        register("esm_models_b", generic(new NoHelperSquare("ESM_MODELS_B"), 64, 0), null);
        EditorSessionManager m = new EditorSessionManager();
        Map<String, Map<String, Object>> models = m.collectAllRegionVisualizationPayloads();
        assertTrue(models.containsKey("esm_models_a"));
        assertTrue(models.containsKey("esm_models_b"));
        JsonObject json = JsonParser.parseString(m.buildRegionModelsJson()).getAsJsonObject();
        assertTrue(json.has("esm_models_a"));
        assertTrue(json.has("esm_models_b"));
    }

    // ---- export / embed ----

    @Test
    @DisplayName("Export with a channel block and a live feed embeds both into the page payload")
    void exportWithChannel() throws IOException {
        EditorSessionManager m = EditorSessionManager.getInstance();
        Path html = tempDir.resolve("export/sub/index.html");
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("relay", "ws://127.0.0.1:1");
        channel.put("id", "esm-channel-id");
        m.exportLocalEditorHtml(html, Map.of("config.yml", "a: 1\n"), false, channel);
        String page = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(page.contains("\"channel\":{\"relay\":\"ws://127.0.0.1:1\",\"id\":\"esm-channel-id\"}"));
        assertFalse(page.contains("\"liveFeed\""));

        m.exportLocalEditorHtml(html, Map.of("config.yml", "a: 2\n"), true, channel);
        page = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(page.contains("\"liveFeed\":{\"path\":\""));
        assertTrue(page.contains("esm-channel-id"));
        assertFalse(Files.exists(html.resolveSibling("index.html.tmp")));

        // Relative target without a parent directory component
        Path cwdLess = Path.of("esm-branch-export-" + System.nanoTime() + ".html");
        try {
            m.exportLocalEditorHtml(cwdLess, Map.of());
            assertTrue(Files.exists(cwdLess));
        } finally {
            Files.deleteIfExists(cwdLess);
        }
        assertThrows(NullPointerException.class, () -> m.exportLocalEditorHtml(null, Map.of()));
    }

    @Test
    @DisplayName("Embedded payload goes before </head>, or first without one; '<' is unicode-escaped")
    void embedPayload() {
        String withHead = EditorSessionManager.embedPayload("<html><head></head><body/></html>", "{\"a\":\"</script>\"}");
        assertTrue(withHead.indexOf(EditorSessionManager.EMBEDDED_PAYLOAD_ELEMENT_ID) < withHead.indexOf("</head>"));
        assertTrue(withHead.contains("\\u003c/script>"));
        String noHead = EditorSessionManager.embedPayload("<body/>", "{}");
        assertTrue(noHead.startsWith("<script type=\"application/json\""));
        assertTrue(noHead.endsWith("<body/>"));
        assertNotNull(EditorSessionManager.editorDataMembers());
        assertSame(EditorSessionManager.editorDataMembers(), EditorSessionManager.editorDataMembers(), "cached");
    }

    // ---- JSON helpers ----

    @Test
    @DisplayName("escapeJson, mapToJson and valueToJson cover every value kind")
    void jsonHelpers() {
        assertEquals("", EditorSessionManager.escapeJson(null));
        assertEquals("\\\\\\\"\\b\\f\\n\\r\\t", EditorSessionManager.escapeJson("\\\"\b\f\n\r\t"));
        assertEquals("{}", EditorSessionManager.mapToJson(null));
        assertEquals("{}", EditorSessionManager.mapToJson(Map.of()));

        assertEquals("null", EditorSessionManager.valueToJson(null));
        assertEquals("3", EditorSessionManager.valueToJson(3));
        assertEquals("true", EditorSessionManager.valueToJson(true));
        assertEquals("\"s\"", EditorSessionManager.valueToJson("s"));
        assertEquals("{\"k\":1}", EditorSessionManager.valueToJson(Map.of("k", 1)));
        assertEquals("[1,\"x\",null]", EditorSessionManager.valueToJson(java.util.Arrays.asList(1, "x", null)));
        assertEquals("[]", EditorSessionManager.valueToJson(List.of()));
        assertEquals("[1,2,3]", EditorSessionManager.valueToJson(new int[]{1, 2, 3}));
        assertEquals("[]", EditorSessionManager.valueToJson(new int[0]));
        assertEquals("[4,5]", EditorSessionManager.valueToJson(new long[]{4L, 5L}));
        assertEquals("[]", EditorSessionManager.valueToJson(new long[0]));
        assertEquals("\"RED\"", EditorSessionManager.valueToJson(Colour.RED));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", 1);
        nested.put("b", List.of(Map.of("c", "d")));
        assertEquals("{\"a\":1,\"b\":[{\"c\":\"d\"}]}", EditorSessionManager.mapToJson(nested));
    }

    @Test
    @DisplayName("describeParam maps each default type; null declaration adds no kind")
    void describeParamTypes() {
        assertEquals("boolean", EditorSessionManager.describeParam(Boolean.TRUE).get("type"));
        assertEquals(true, EditorSessionManager.describeParam(Boolean.TRUE).get("default"));
        for (Object i : new Object[]{1, 2L, (short) 3, (byte) 4, BigInteger.TEN}) {
            assertEquals("integer", EditorSessionManager.describeParam(i).get("type"), String.valueOf(i));
        }
        assertEquals("number", EditorSessionManager.describeParam(1.5).get("type"));
        Map<String, Object> en = EditorSessionManager.describeParam(Colour.GREEN);
        assertEquals("enum", en.get("type"));
        assertEquals("GREEN", en.get("default"));
        assertEquals(List.of("RED", "GREEN"), en.get("options"));
        Map<String, Object> list = EditorSessionManager.describeParam(List.of("a"));
        assertEquals("list", list.get("type"));
        assertFalse(list.containsKey("default"));
        assertEquals("list", EditorSessionManager.describeParam(new int[]{1}).get("type"));
        assertEquals("string", EditorSessionManager.describeParam("txt").get("type"));
        assertEquals("txt", EditorSessionManager.describeParam("txt").get("default"));
        Map<String, Object> none = EditorSessionManager.describeParam(null);
        assertEquals("string", none.get("type"));
        assertFalse(none.containsKey("default"));
        assertFalse(EditorSessionManager.describeParam(Mode.ACCUMULATE, null).containsKey("kind"));
    }

    @Test
    @DisplayName("Canonical files digest is order-independent; withChannel only appends to an object")
    void digestAndWithChannel() {
        String empty = EditorHttpTransport.computeSha256("{}");
        assertEquals(empty, EditorSessionManager.computeCanonicalFilesSha256(null));
        assertEquals(empty, EditorSessionManager.computeCanonicalFilesSha256(Map.of()));
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("b.yml", "x: 1\n");
        ab.put("a.yml", "y: \"q\"\n");
        Map<String, String> ba = new TreeMap<>(ab);
        assertEquals(EditorSessionManager.computeCanonicalFilesSha256(ab),
                EditorSessionManager.computeCanonicalFilesSha256(ba));
        assertEquals(EditorHttpTransport.computeSha256("{\"a.yml\":\"y: \\\"q\\\"\\n\",\"b.yml\":\"x: 1\\n\"}"),
                EditorSessionManager.computeCanonicalFilesSha256(ab));

        assertEquals("{\"a\":1}", EditorSessionManager.withChannel("{\"a\":1}", null));
        assertEquals("[1]", EditorSessionManager.withChannel("[1]", Map.of("id", "x")));
        assertEquals("{\"a\":1,\"channel\":{\"id\":\"x\"}}", EditorSessionManager.withChannel("{\"a\":1}", Map.of("id", "x")));

        String payload = EditorSessionManager.getInstance().createPayloadJson(Map.of("config.yml", "a: 1\n"), null);
        assertFalse(JsonParser.parseString(payload).getAsJsonObject().has("channel"));
        assertThrows(NullPointerException.class,
                () -> EditorSessionManager.getInstance().createPayloadJson((Map<String, String>) null));
    }

    // ---- parse / validate ----

    @Test
    @DisplayName("Malformed payloads are refused with a reason (S-004)")
    void parseRejectsMalformed() {
        EditorSessionManager m = new EditorSessionManager();
        assertThrows(IllegalArgumentException.class, () -> m.parseAndValidatePayload(null));
        assertThrows(IllegalArgumentException.class, () -> m.parseAndValidatePayload("   "));
        IllegalArgumentException badJson = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{not json"));
        assertTrue(badJson.getMessage().startsWith("Invalid payload JSON"), badJson.getMessage());
        IllegalArgumentException array = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("[1,2]"));
        assertTrue(array.getMessage().contains("root must be a JSON object"));
        IllegalArgumentException noFiles = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"version\":1}"));
        assertTrue(noFiles.getMessage().contains("no configuration files"));
        assertThrows(IllegalArgumentException.class, () -> m.parseAndValidatePayload("{\"files\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> m.parseAndValidatePayload("{\"files\":[1]}"));
        IllegalArgumentException nonString = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"files\":{\"a.yml\":5}}"));
        assertTrue(nonString.getMessage().contains("must map a path to string content"));

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload(filesBlockPayload("{\"a.yml\":\"x: 1\"}", "00ff")));
        assertTrue(mismatch.getMessage().contains("SHA-256 mismatch"));

        assertThrows(EditorSessionManager.MissingDigestException.class,
                () -> m.parseAndValidatePayload("{\"sha256\":\"  \",\"files\":{\"a.yml\":\"x: 1\"}}", true));
        assertTrue(new EditorSessionManager.MissingDigestException().getMessage().contains("sha256"));

        IllegalArgumentException traversal = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"files\":{\"../x.yml\":\"a: 1\"}}"));
        assertNotNull(traversal.getMessage());
    }

    @Test
    @DisplayName("Raw files-block digest is accepted; version and timestamp parse from numbers or strings")
    void parseAcceptsRawDigestAndStringFields() {
        EditorSessionManager m = new EditorSessionManager();
        String raw = "{ \"a.yml\" : \"x: 1\\n\" }";
        String sha = EditorHttpTransport.computeSha256(raw);
        assertNotEquals(EditorSessionManager.computeCanonicalFilesSha256(Map.of("a.yml", "x: 1\n")), sha);
        String json = "{\"version\":\"7\",\"pluginVersion\":\"9.9\",\"timestamp\":\"1234\",\"sha256\":\""
                + sha.toUpperCase(java.util.Locale.ROOT) + "\",\"files\":" + raw + "}";
        EditorSessionManager.ParsedPayload p = m.parseAndValidatePayload(json, true);
        assertEquals(7, p.version());
        assertEquals("9.9", p.pluginVersion());
        assertEquals(1234L, p.timestamp());
        assertEquals(sha.toUpperCase(java.util.Locale.ROOT), p.sha256());
        assertEquals(Map.of("a.yml", "x: 1\n"), p.files());
        assertThrows(UnsupportedOperationException.class, () -> p.files().put("b.yml", ""));

        EditorSessionManager.ParsedPayload q = m.parseAndValidatePayload(
                "{\"version\":\"v2\",\"timestamp\":\"later\",\"pluginVersion\":3,\"files\":{\"a.yml\":\"x: 1\"}}");
        assertEquals(1, q.version(), "unparsable version keeps the default");
        assertEquals(0L, q.timestamp());
        assertNull(q.pluginVersion());
        assertNull(q.sha256());

        EditorSessionManager.ParsedPayload r = m.parseAndValidatePayload(
                "{\"version\":3,\"timestamp\":99,\"files\":{\"a.yml\":\"x: 1\"}}");
        assertEquals(3, r.version());
        assertEquals(99L, r.timestamp());

        // Digest present but matching neither form: "files" text absent from the raw JSON is impossible,
        // so the canonical form alone must match
        String canon = EditorSessionManager.computeCanonicalFilesSha256(Map.of("a.yml", "x: 1"));
        assertNotNull(m.parseAndValidatePayload(filesBlockPayload("{\"a.yml\":\"x: 1\"}", canon)).sha256());
    }

    @Test
    @DisplayName("Region files reject negative radius and centerRadius; other files are not geometry-checked")
    void parseValidatesRegionGeometry() {
        EditorSessionManager m = new EditorSessionManager();
        IllegalArgumentException radius = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"files\":{\"regions/default.yml\":\"shape:\\n  radius: -5\\n\"}}"));
        assertTrue(radius.getMessage().contains("radius cannot be negative"), radius.getMessage());
        IllegalArgumentException top = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"files\":{\"regions.yml\":\"radius: -1\\n\"}}"));
        assertTrue(top.getMessage().contains("radius cannot be negative"));
        IllegalArgumentException center = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"files\":{\"myregion.yml\":\"shape:\\n  centerRadius: -2\\n\"}}"));
        assertTrue(center.getMessage().contains("centerRadius cannot be negative"), center.getMessage());
        IllegalArgumentException centerTop = assertThrows(IllegalArgumentException.class,
                () -> m.parseAndValidatePayload("{\"files\":{\"regions/a.yml\":\"centerRadius: -3\\n\"}}"));
        assertTrue(centerTop.getMessage().contains("centerRadius"));

        assertDoesNotThrow(() -> m.parseAndValidatePayload(
                "{\"files\":{\"regions/ok.yml\":\"shape:\\n  radius: 10\\n  centerRadius: 2\\n  name: x\\n"
                        + "radius: text\\ncenterRadius: text\\n\"}}"));
        assertDoesNotThrow(() -> m.parseAndValidatePayload("{\"files\":{\"config.yml\":\"radius: -5\\n\"}}"));
    }

    // ---- collect / apply ----

    @Test
    @DisplayName("collectCurrentConfigs fails closed before init and returns empty without a plugin folder")
    void collectFailClosedAndMissingDir() {
        EditorSessionManager m = new EditorSessionManager();
        Configs configs = ActiveConfigs.get();
        ActiveConfigs.set(null);
        try {
            assertThrows(IllegalStateException.class, m::collectCurrentConfigs);
            assertThrows(IllegalStateException.class, m::createPayloadJson);
        } finally {
            ActiveConfigs.set(configs);
        }
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTPRuntime.serverAccessor();
        RTPRuntime.setServerAccessor(new MockRTPServerAccessor(tempDir.resolve("does-not-exist").toFile()));
        try {
            assertTrue(m.collectCurrentConfigs().isEmpty());
        } finally {
            RTPRuntime.setServerAccessor(accessor);
        }
    }

    @Test
    @DisplayName("collectCurrentConfigs walks the plugin folder, skipping hidden paths and non-YAML")
    void collectWalksPluginFolder() throws IOException {
        Files.createDirectories(pluginDir.resolve("regions"));
        Files.createDirectories(pluginDir.resolve(".hidden"));
        Files.writeString(pluginDir.resolve("regions/esm_walk.yml"), "radius: 10\n", StandardCharsets.UTF_8);
        Files.writeString(pluginDir.resolve(".hidden/secret.yml"), "a: 1\n", StandardCharsets.UTF_8);
        Files.writeString(pluginDir.resolve("regions/.dot.yml"), "a: 1\n", StandardCharsets.UTF_8);
        Files.writeString(pluginDir.resolve("notes.txt"), "plain\n", StandardCharsets.UTF_8);

        Map<String, String> configs = new EditorSessionManager().collectCurrentConfigs();
        assertEquals("radius: 10\n", configs.get("regions/esm_walk.yml"));
        assertFalse(configs.keySet().stream().anyMatch(k -> k.contains(".hidden") || k.contains(".dot")),
                configs.keySet().toString());
        assertFalse(configs.containsKey("notes.txt"));
        String payload = new EditorSessionManager().createPayloadJson();
        assertTrue(payload.contains("regions/esm_walk.yml"));
    }

    @Test
    @DisplayName("applyPayload fails closed before init (S-006)")
    void applyFailClosed() {
        EditorSessionManager m = new EditorSessionManager();
        Configs configs = ActiveConfigs.get();
        ActiveConfigs.set(null);
        try {
            CompletableFuture<Void> f = m.applyPayload("{}");
            assertTrue(f.isCompletedExceptionally());
            ExecutionException e = assertThrows(ExecutionException.class, f::get);
            assertInstanceOf(IllegalStateException.class, e.getCause());
        } finally {
            ActiveConfigs.set(configs);
        }
    }

    @Test
    @DisplayName("applyPayload backs up dirty files only, creates folders and writes atomically")
    void applyWritesAndBacksUp() throws IOException {
        EditorSessionManager m = EditorSessionManager.getInstance();
        Path dirty = pluginDir.resolve("esm_dirty.yml");
        Path same = pluginDir.resolve("esm_same.yml");
        Files.writeString(dirty, "a: 1\n", StandardCharsets.UTF_8);
        Files.writeString(same, "b: 2\n", StandardCharsets.UTF_8);

        Map<String, String> files = new LinkedHashMap<>();
        files.put("esm_dirty.yml", "a: 5\n");
        files.put("esm_same.yml", "b: 2\n");
        files.put("esm_new/deep/fresh.yml", "c: 3\n");
        m.applyPayload(m.createPayloadJson(files)).join();

        assertEquals("a: 5\n", Files.readString(dirty, StandardCharsets.UTF_8));
        assertEquals("a: 1\n", Files.readString(pluginDir.resolve("esm_dirty.yml.bak"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(pluginDir.resolve("esm_same.yml.bak")), "unchanged file gets no backup");
        assertEquals("c: 3\n", Files.readString(pluginDir.resolve("esm_new/deep/fresh.yml"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(pluginDir.resolve("esm_new/deep/fresh.yml.tmp")));
    }

    @Test
    @DisplayName("applyPayload surfaces validation failures through the future (S-004)")
    void applyRejectsInvalid() {
        EditorSessionManager m = EditorSessionManager.getInstance();
        CompletionException e = assertThrows(CompletionException.class,
                () -> m.applyPayload("{\"files\":{\"regions/x.yml\":\"radius: -1\\n\"}}").join());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        CompletionException digest = assertThrows(CompletionException.class,
                () -> m.applyPayload("{\"files\":{\"x.yml\":\"a: 1\\n\"}}", true).join());
        assertInstanceOf(EditorSessionManager.MissingDigestException.class, digest.getCause());
        assertFalse(Files.exists(pluginDir.resolve("x.yml")));
    }

    // ---- telemetry / schema / curve helpers ----

    @Test
    @DisplayName("Telemetry snapshot lists each region with its world and queue sizes")
    void telemetryListsRegions() {
        register("esm_tel_a", generic(new Square("ESM_TEL_A"), 64, 0), new MockRTPWorld(WORLD));
        register("esm_tel_b", generic(new Square("ESM_TEL_B"), 64, 0), null);
        String json = new EditorSessionManager().buildTelemetrySnapshotJson();
        JsonObject t = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(t.has("tps1m"));
        assertTrue(t.has("latTotal"));
        assertTrue(t.get("platform").getAsString().length() > 0);
        Map<String, String> worlds = new HashMap<>();
        t.getAsJsonArray("regions").forEach(e -> worlds.put(
                e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject().get("world").getAsString()));
        assertEquals(WORLD, worlds.get("esm_tel_a"));
        assertEquals("world", worlds.get("esm_tel_b"), "no world falls back to the default name");
    }

    @Test
    @DisplayName("Schema lists registered shapes with typed parameters and curveParams for helper shapes")
    void schemaListsShapes() {
        Factory<Shape<?>> shapes = shapes();
        shapes.add("esm_schema_circle", new Circle("ESM_SCHEMA_CIRCLE"));
        shapes.add("esm_schema_plain", new NoHelperSquare("ESM_SCHEMA_PLAIN"));
        addedShapes.add("esm_schema_circle");
        addedShapes.add("esm_schema_plain");
        JsonObject schema = JsonParser.parseString(new EditorSessionManager().buildSchemaJson()).getAsJsonObject();
        JsonObject shape = schema.getAsJsonObject("shape");
        assertTrue(shape.has("ESM_SCHEMA_CIRCLE"), shape.keySet().toString());
        assertTrue(shape.getAsJsonObject("ESM_SCHEMA_CIRCLE").has("radius"));
        assertTrue(schema.getAsJsonObject("curveParams").has("ESM_SCHEMA_CIRCLE"));
        assertFalse(schema.getAsJsonObject("curveParams").has("ESM_SCHEMA_PLAIN"));
        assertTrue(schema.has("vert"));
    }

    @Test
    @DisplayName("Registered helper shapes add a source and sample; a clashing prior source is kept")
    void registeredCurveHelpers() {
        Factory<Shape<?>> shapes = shapes();
        shapes.add("esm_reg_circle", new CircleOptimizedDualLayer("ESM_REG_CIRCLE"));
        shapes.add("esm_reg_plain", new NoHelperSquare("ESM_REG_PLAIN"));
        addedShapes.add("esm_reg_circle");
        addedShapes.add("esm_reg_plain");

        Map<String, String> sources = new TreeMap<>();
        Map<String, Map<String, Object>> samples = new TreeMap<>();
        EditorSessionManager.addRegisteredCurveHelpers(sources, samples);
        String name = EditorCurveModel.registeredName(new CircleOptimizedDualLayer("ESM_REG_CIRCLE"));
        assertTrue(sources.containsKey(name), sources.keySet().toString());
        assertTrue(samples.containsKey(name));
        assertFalse(sources.containsKey("ESM_REG_PLAIN"));

        Map<String, String> clashing = new TreeMap<>();
        clashing.put(name, "/* someone else */");
        Map<String, Map<String, Object>> noSamples = new TreeMap<>();
        EditorSessionManager.addRegisteredCurveHelpers(clashing, noSamples);
        assertEquals("/* someone else */", clashing.get(name));
        assertFalse(noSamples.containsKey(name));

        JsonObject code = JsonParser.parseString(EditorSessionManager.buildCurveCodeJson(sources, samples)).getAsJsonObject();
        assertTrue(code.getAsJsonObject(name).has("sample"));
        assertTrue(code.getAsJsonObject(name).has("sha256"));
    }

    // ---- world biomes ----

    @Test
    @DisplayName("World biomes embed stored bins per region world: full detail locally, view-first when hosted")
    void worldBiomesEmbedBins() {
        register("esm_land", generic(new Square("ESM_LAND"), 64, 0), new MockRTPWorld(WORLD));
        WorldBiomeStore store = WorldBiomeStore.of(WORLD);
        int[] idx = BiomeBinCodec.sampleIndices(0);
        store.apply(0, 0, 64, 0, idx, Map.of(idx[0], "minecraft:plains"), 1L);
        store.apply(400, 400, 64, 0, idx, Map.of(idx[0], "minecraft:desert"), 1L);
        store.apply(0, 0, -30, BiomeBinCodec.FINEST_LEVEL, new int[]{0}, Map.of(0, "minecraft:deep_dark"), 2L);

        EditorSessionManager m = new EditorSessionManager();
        JsonObject full = JsonParser.parseString(m.buildWorldBiomesJson(true)).getAsJsonObject().getAsJsonObject(WORLD);
        assertNotNull(full);
        assertEquals(2, full.get("bins").getAsInt());
        assertFalse(full.get("truncated").getAsBoolean());
        assertTrue(full.getAsJsonObject("layers").has("64"));
        assertTrue(full.getAsJsonArray("palette").toString().contains("plains"));

        JsonObject coarse = JsonParser.parseString(m.buildWorldBiomesJson(false)).getAsJsonObject().getAsJsonObject(WORLD);
        assertNotNull(coarse);
        assertEquals(2, coarse.get("bins").getAsInt());
        // Far bin (outside every region view) is sent coarse at level 0
        boolean farCoarse = false;
        for (var e : coarse.getAsJsonObject("layers").getAsJsonArray("64")) {
            var row = e.getAsJsonArray();
            if (row.get(0).getAsInt() == 400) farCoarse = row.get(2).getAsInt() == 0;
        }
        assertTrue(farCoarse, coarse.toString());
    }
}
