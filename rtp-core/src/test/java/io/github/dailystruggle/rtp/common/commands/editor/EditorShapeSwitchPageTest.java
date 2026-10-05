package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shape switching on the editor page (ADR-104 / ADR-106): the page's shape-block reader and writer,
 * lifted from docs/editor/index.html and run in GraalJS against the schema {@link
 * EditorSessionManager#buildSchemaJson()} builds from the registered shapes' {@code keys()}. A switch
 * writes only {@code name} plus the keys the new shape reports (POLYGON: mode, vertices, weight,
 * uniquePlacements), whether the block was local or an inherited {@code "@config"} reference;
 * vertices carry an explicit chunk unit and read back unchanged; preview requests carry raw text.
 */
@DisplayName("ADR-106: editor shape switch writes only the new shape's keys with unit-suffixed vertices")
class EditorShapeSwitchPageTest {

    private static final String[] PAGE_CONSTS = {"SCHEMA_DOC_TAGS", "DISTANCE_UNIT_ALIAS", "DISTANCE_TOKEN_RE"};
    private static final String[] PAGE_FUNCTIONS = {"isInheritedReferenceToken", "registryNames", "registryHas",
            "registryParams", "registryKeys", "stripYamlValue", "parseYamlLines", "regionFileFor", "normaliseSetting",
            "settingKeyOf", "settingValue", "yamlScalar", "parseVertexCoord", "shapeTakesKey",
            "regionGeometryValues", "readRegionGeometry", "yamlKeyExtent", "patchRegionShape",
            "walkPathShapeRequest"};

    /** Session schema as the server builds it (shape keys from each implementation's keys()). */
    private static String serverSchema;

    private static final String LOCAL_CIRCLE = """
            world: "world"

            shape:
              name: "CIRCLE"
              mode: "ACCUMULATE"
              # @type: distance
              radius: "256 c"
              centerRadius: 16
              centerX: 0
              centerZ: 0
              weight: 1,5
              uniquePlacements: "auto"
              expand: false

            vert: "@config"
            """;

    private static final String INHERITED = """
            world: "world"
            shape: "@config"
            vert: "@config"
            """;

    private static final String CONFIG = """
            defaults:
              shape:
                name: CIRCLE
                mode: ACCUMULATE
                radius: 4096
                centerRadius: 0
                centerX: 0
                centerZ: 0
                weight: 1.0
                uniquePlacements: auto
                expand: false
              vert: "@config"
            """;

    private static final String LOCAL_POLYGON = """
            shape:
              name: POLYGON
              mode: ACCUMULATE
              vertices:
                - [-125, 187]
                - [2000b, 187c]
                - [125, -187]
              weight: 1.0
            vert: "@config"
            """;

    /** Reads `text`, switches the model to each shape in turn (writing after each), returns the last state. */
    private static final String DRIVER = """
            (function (text, shapesJson, verticesJson, settingsJson, schemaJson) {
              serverSchema = schemaJson ? JSON.parse(schemaJson) : null;
              configs = { 'config.yml': CONFIG_TEXT };
              shippedConfigs = {};
              const g = readRegionGeometry(text);
              const region = { name: 'r', shape: g.shape, settings: settingsJson ? JSON.parse(settingsJson) : g.settings, vertices: g.vertices };
              let out = text;
              for (const shape of JSON.parse(shapesJson)) {
                region.shape = shape;
                if (shape === 'POLYGON' && verticesJson) region.vertices = JSON.parse(verticesJson);
                out = patchRegionShape(out, region);
              }
              configs[regionFileFor('r')] = out;
              const keys = parseYamlLines(out).filter(r => r.key && r.path.length === 1 && r.path[0] === 'shape').map(r => r.key);
              return JSON.stringify({ out, keys, back: readRegionGeometry(out), walk: walkPathShapeRequest(region), settings: region.settings });
            })""";

    private static final Set<String> POLYGON_KEYS = Set.of("name", "mode", "vertices", "weight", "uniqueplacements");
    private static final Pattern VERTEX_LINE = Pattern.compile("^ {4}- \\[-?\\d+c, -?\\d+c]$");
    private static final String SQUARE = "[[-125,187],[125,187],[125,-187],[-125,-187]]";

    private static Context context;
    private static Value driver;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    /** Source of a top-level page function, from its declaration to the matching closing brace. */
    private static String functionSource(String html, String name) {
        int start = html.indexOf("\nfunction " + name + "(");
        assertTrue(start >= 0, "page function missing: " + name);
        int open = html.indexOf('{', html.indexOf(')', start));
        int depth = 0;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return html.substring(start + 1, i + 1);
        }
        throw new AssertionError("unbalanced braces in " + name);
    }

    /** A top-level `const NAME = ...;` statement (brackets balanced, so multi-line initialisers work). */
    private static String constSource(String html, String name) {
        int start = html.indexOf("\nconst " + name + " =");
        assertTrue(start >= 0, "page constant missing: " + name);
        int depth = 0;
        for (int i = start + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ';' && depth == 0) return html.substring(start + 1, i + 1);
        }
        throw new AssertionError("unterminated constant " + name);
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void startEngine() throws IOException {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapes);
        }
        boolean addCircle = !shapes.contains("CIRCLE");
        boolean addPolygon = !shapes.contains("POLYGON");
        if (addCircle) shapes.add("CIRCLE", new Circle("CIRCLE"));
        if (addPolygon) shapes.add("POLYGON", new Polygon());
        try {
            serverSchema = new EditorSessionManager().buildSchemaJson();
        } finally {
            if (addCircle) shapes.remove("CIRCLE");
            if (addPolygon) shapes.remove("POLYGON");
        }

        String html = page();
        StringBuilder src = new StringBuilder("var serverSchema = null, configs = {}, shippedConfigs = {};\n");
        src.append("const CONFIG_TEXT = ").append(new com.google.gson.Gson().toJson(CONFIG)).append(";\n");
        for (String c : PAGE_CONSTS) src.append(constSource(html, c)).append('\n');
        for (String fn : PAGE_FUNCTIONS) src.append(functionSource(html, fn)).append('\n');
        context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        context.eval("js", src.toString());
        driver = context.eval("js", DRIVER);
    }

    @AfterAll
    static void stopEngine() {
        if (context != null) context.close();
    }

    private static JsonObject run(String text, String shapes, String vertices, String settings, String schema) {
        return JsonParser.parseString(driver.execute(text, shapes, vertices, settings, schema).asString()).getAsJsonObject();
    }

    private static List<String> keys(JsonObject r) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : r.getAsJsonArray("keys")) out.add(e.getAsString());
        return out;
    }

    private static void assertPolygonBlock(JsonObject r, String label) {
        String out = r.get("out").getAsString();
        List<String> keys = keys(r);
        Set<String> seen = new HashSet<>();
        for (String k : keys) {
            String lk = k.toLowerCase(Locale.ROOT);
            assertTrue(POLYGON_KEYS.contains(lk), label + ": POLYGON block keeps irrelevant key '" + k + "':\n" + out);
            assertTrue(seen.add(lk), label + ": duplicate key '" + k + "':\n" + out);
        }
        assertTrue(seen.contains("name") && seen.contains("vertices"), label + ": name + vertices written:\n" + out);
        assertTrue(out.contains("\n  name: \"POLYGON\""), label + ": name POLYGON:\n" + out);
        assertTrue(out.contains("\nshape:\n"), label + ": `shape:` carries no inline value:\n" + out);
        assertFalse(out.contains("shape: \"@config\""), label + ": inherited reference replaced:\n" + out);
        long vertexLines = out.lines().filter(l -> VERTEX_LINE.matcher(l).matches()).count();
        assertEquals(4, vertexLines, label + ": vertices as `    - [xc, zc]`:\n" + out);
        JsonObject back = r.getAsJsonObject("back");
        assertTrue(back.get("local").getAsBoolean(), label + ": written block is local");
        assertEquals("POLYGON", back.get("shape").getAsString(), label);
        assertEquals(JsonParser.parseString(SQUARE), back.get("vertices"), label + ": vertices round-trip in chunks");
        assertTrue(out.contains("\nvert: \"@config\""), label + ": other keys untouched:\n" + out);
    }

    @Test
    @DisplayName("Polygon.keys() reaches the session schema as mode, vertices, weight, uniquePlacements")
    void schemaCarriesPolygonKeys() {
        JsonObject poly = JsonParser.parseString(serverSchema).getAsJsonObject().getAsJsonObject("shape").getAsJsonObject("POLYGON");
        assertNotNull(poly, serverSchema);
        Set<String> keys = new HashSet<>();
        for (String k : poly.keySet()) keys.add(k.toLowerCase(Locale.ROOT));
        assertEquals(Set.of("mode", "vertices", "weight", "uniqueplacements"), keys, serverSchema);
    }

    @Test
    @DisplayName("local CIRCLE block switched to POLYGON keeps only the keys Polygon reports")
    void localCircleToPolygon() {
        JsonObject r = run(LOCAL_CIRCLE, "[\"POLYGON\"]", SQUARE, null, serverSchema);
        assertPolygonBlock(r, "local");
        assertFalse(r.get("out").getAsString().contains("# @type: distance"), "removed key takes its comment block");
    }

    @Test
    @DisplayName("inherited \"@config\" shape switched to POLYGON becomes a local block with only POLYGON keys")
    void inheritedToPolygon() {
        assertPolygonBlock(run(INHERITED, "[\"POLYGON\"]", SQUARE, null, serverSchema), "inherited");
    }

    @Test
    @DisplayName("keys present in the file but absent from the canvas model are removed too")
    void staleModelKeysRemoved() {
        assertPolygonBlock(run(LOCAL_CIRCLE, "[\"POLYGON\"]", SQUARE, "{}", serverSchema), "stale model");
    }

    @Test
    @DisplayName("without a session schema the page prunes nothing but still writes unit-suffixed vertices")
    void noSchemaPrunesNothing() {
        JsonObject r = run(LOCAL_CIRCLE, "[\"POLYGON\"]", SQUARE, null, null);
        String out = r.get("out").getAsString();
        List<String> keys = keys(r);
        assertTrue(keys.containsAll(List.of("name", "radius", "centerRadius", "expand", "vertices")), out);
        assertEquals(keys.size(), new HashSet<>(keys).size(), "no duplicate keys:\n" + out);
        assertEquals(4, out.lines().filter(l -> VERTEX_LINE.matcher(l).matches()).count(), out);
    }

    @Test
    @DisplayName("vertices read with no unit, c and b suffixes")
    void verticesReadWithUnits() {
        JsonObject r = run(LOCAL_POLYGON, "[]", null, null, null);
        assertEquals(JsonParser.parseString("[[-125,187],[125,187],[125,-187]]"), r.getAsJsonObject("back").get("vertices"));
    }

    @Test
    @DisplayName("POLYGON switched to CIRCLE drops vertices and invents no geometry keys")
    void polygonToCircle() {
        JsonObject r = run(LOCAL_POLYGON, "[\"CIRCLE\"]", null, null, serverSchema);
        String out = r.get("out").getAsString();
        List<String> keys = keys(r);
        assertFalse(keys.contains("vertices"), "vertices removed:\n" + out);
        assertFalse(out.contains("- ["), "no vertex items left:\n" + out);
        assertTrue(out.contains("\n  name: \"CIRCLE\""), out);
        assertTrue(keys.containsAll(List.of("mode", "weight")), "keys CIRCLE also takes are kept:\n" + out);
        assertFalse(keys.contains("radius"), "radius comes from the shape's defaults, not the page:\n" + out);
        assertEquals(keys.size(), new HashSet<>(keys).size(), "no duplicate keys:\n" + out);
    }

    @Test
    @DisplayName("CIRCLE -> POLYGON -> CIRCLE restores the circle keys without duplicates or vertices")
    void switchBackAndForth() {
        JsonObject r = run(LOCAL_CIRCLE, "[\"POLYGON\",\"CIRCLE\"]", SQUARE, null, serverSchema);
        String out = r.get("out").getAsString();
        List<String> keys = keys(r);
        assertEquals(keys.size(), new HashSet<>(keys).size(), "no duplicate keys:\n" + out);
        assertFalse(keys.contains("vertices"), out);
        assertTrue(keys.containsAll(List.of("name", "radius", "centerRadius", "expand")), out);
        assertTrue(out.contains("\n  radius: \"256 c\""), "original radius text kept:\n" + out);
    }

    @Test
    @DisplayName("walk-path / curve-state request carries raw setting text and numeric chunk vertices")
    void walkPathRequestRaw() {
        JsonObject circle = run(LOCAL_CIRCLE, "[]", null, null, serverSchema).getAsJsonObject("walk");
        assertEquals("256 c", circle.get("radius").getAsString());
        assertEquals("1,5", circle.get("weight").getAsString());
        assertEquals("auto", circle.get("uniquePlacements").getAsString());
        assertEquals("CIRCLE", circle.get("name").getAsString());
        assertFalse(circle.has("vertices"));

        JsonObject poly = run(LOCAL_CIRCLE, "[\"POLYGON\"]", SQUARE, null, serverSchema).getAsJsonObject("walk");
        assertEquals("POLYGON", poly.get("name").getAsString());
        assertEquals(JsonParser.parseString(SQUARE), poly.get("vertices"));
        for (String k : poly.keySet()) assertTrue(POLYGON_KEYS.contains(k.toLowerCase(Locale.ROOT)), "walk request keeps '" + k + "': " + poly);
        JsonArray first = poly.getAsJsonArray("vertices").get(0).getAsJsonArray();
        assertTrue(first.get(0).getAsJsonPrimitive().isNumber(), "vertices sent as numbers");
    }
}
