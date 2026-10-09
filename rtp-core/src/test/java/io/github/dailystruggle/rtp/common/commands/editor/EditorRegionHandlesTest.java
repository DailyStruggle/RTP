package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canvas move / resize grips of the region editor (docs/editor/index.html): the page's own helpers,
 * lifted from the page and run in GraalJS with DOM / staging stubs. Grip edits keep the value's unit
 * style, clamp the hole below the outer edge, scale / translate polygons by whole chunks, and stage
 * through applyShapeSetting (the typed-edit path) with previews deferred to mouseup.
 */
@DisplayName("ADR-106: editor canvas grips move / resize regions through the shared settings path")
class EditorRegionHandlesTest {

    private static final String[] PAGE_FUNCTIONS = {
            "normaliseSetting", "settingKeyOf", "settingValue", "settingChunks", "getRegionSpan", "regionCenterBlocks",
            "polygonAabb", "segmentsIntersect", "regionShapeFamily", "formatLikeExisting", "clampHandleChunks",
            "snapChunks", "handleSnapStepChunks", "handleDragChunks", "regionHandleLayout", "pointInPolygon",
            "regionHitTest", "translatePolygon", "polygonSelfIntersects", "scalePolygon", "regionHandleLabel",
            "currentRegionHandles", "canvasHitTest", "shapeSettingRow", "setRegionHandleSetting", "stageHandleSetting",
            "setPolygonVertices", "startRegionHandleDrag", "dragRegionHandle", "endRegionHandleDrag"};

    /** Page globals the helpers touch: no DOM, no channel; staging calls are recorded. */
    private static final String STUBS = String.join("\n",
            "let regionState = { shape: 'CIRCLE', settings: {} };",
            "let activeGridSnap = 16;",
            "let regionHandleDrag = null;",
            "let hoverRegionHandle = '';",
            "let schemaStub = {};",
            "const calls = [];",
            "const counters = { draws: 0, stages: 0, vertexLists: 0 };",
            "const canvas = { style: {} };",
            "const document = { getElementById: () => null };",
            "function shapeSettingSchema(shape, key) { return schemaStub[String(key).toLowerCase()] || null; }",
            "function applyShapeSetting(key, value, row) {",
            "  calls.push({ key, value, live: regionHandleDrag !== null });",
            "  if (!regionState.settings) regionState.settings = {};",
            "  regionState.settings[settingKeyOf(regionState, key)] = value;",
            "  counters.draws++; counters.stages++;",
            "}",
            "function drawMap() { counters.draws++; }",
            "function updateStagingDiff() { counters.stages++; }",
            "function syncPolygonAABB() {}",
            "function renderPolygonVerticesList() { counters.vertexLists++; }",
            "function reset(region) { regionState = region; regionHandleDrag = null; activeGridSnap = 16; schemaStub = {};",
            "  calls.length = 0; counters.draws = counters.stages = counters.vertexLists = 0; }",
            "function grip(t, id) { return currentRegionHandles(t).find(h => h.id === id); }",
            "");

    private static Context context;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    /** Source from {@code start} to the brace matching the first '{' at or after {@code from}. */
    private static String braced(String html, int start, int from, String what) {
        int open = html.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return html.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced braces in " + what);
    }

    /** Source of a top-level page function, from its declaration to the matching closing brace. */
    private static String functionSource(String html, String name) {
        int start = html.indexOf("function " + name + "(");
        assertTrue(start >= 0, "page function missing: " + name);
        assertEquals(-1, html.indexOf("function " + name + "(", start + 1), "page function declared once: " + name);
        return braced(html, start, html.indexOf(')', start), name);
    }

    /** A top-level {@code const NAME = ...;} declaration (object / IIFE initialisers brace-matched). */
    private static String constSource(String html, String name) {
        int start = html.indexOf("const " + name + " = ");
        assertTrue(start >= 0, "page constant missing: " + name);
        int eol = html.indexOf('\n', start);
        String line = html.substring(start, eol);
        if (line.trim().endsWith(";")) return line;
        String body = braced(html, start, start, name);
        return html.substring(start, html.indexOf(';', start + body.length()) + 1);
    }

    @BeforeAll
    static void startEngine() throws IOException {
        String html = page();
        StringBuilder src = new StringBuilder(STUBS);
        for (String c : new String[]{"DISTANCE_UNIT_ALIAS", "DISTANCE_TOKEN_RE", "REGION_HANDLE_PX", "REGION_HANDLE_HIT_PX",
                "VERTEX_HIT_PX", "REGION_HANDLE_STYLE_KEYS"}) {
            src.append(constSource(html, c)).append('\n');
        }
        for (String fn : PAGE_FUNCTIONS) src.append(functionSource(html, fn)).append('\n');
        context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        context.eval("js", src.toString());
    }

    @AfterAll
    static void stopEngine() {
        if (context != null) context.close();
    }

    @BeforeEach
    void resetState() {
        context.eval("js", "reset({ shape: 'CIRCLE', settings: {} })");
    }

    private static String str(String expr) {
        return context.eval("js", expr).asString();
    }

    private static double num(String expr) {
        return context.eval("js", expr).asDouble();
    }

    private static boolean bool(String expr) {
        return context.eval("js", expr).asBoolean();
    }

    private static JsonObject obj(String expr) {
        return JsonParser.parseString(str("JSON.stringify(" + expr + ")")).getAsJsonObject();
    }

    private static JsonArray arr(String expr) {
        return JsonParser.parseString(str("JSON.stringify(" + expr + ")")).getAsJsonArray();
    }

    @Test
    @DisplayName("formatLikeExisting keeps the written unit: blocks, chunks, unitless, exact other units, fallback style")
    void formatKeepsUnitStyle() {
        assertEquals("4160b", str("formatLikeExisting('4096b', 260)"), "blocks stay blocks");
        assertEquals("-4160b", str("formatLikeExisting('-4096b', -260)"), "signed blocks");
        assertEquals("20c", str("formatLikeExisting('16c', 20)"), "chunks stay chunks");
        assertEquals("300", str("formatLikeExisting('256', 300)"), "unitless stays unitless (chunks)");
        assertEquals("300", str("formatLikeExisting(256, 300)"), "YAML number stays unitless");
        assertEquals("192 blocks", str("formatLikeExisting(undefined, 12, '4096 blocks')"), "absent key copies a sibling's style");
        assertEquals("5", str("formatLikeExisting(undefined, 5, undefined)"), "no style: unitless chunks");
        assertEquals("4.16km", str("formatLikeExisting('2km', 260)"), "exact other unit kept");
        assertEquals("100c", str("formatLikeExisting('1mi', 100)"), "inexact unit falls back to chunks");
        assertEquals("8r", str("formatLikeExisting('2r', 256)"), "region files");
        assertEquals("256b", str("formatLikeExisting('4096b', 16.3)"), "values are written as whole chunks");
        // Every written value parses back to the same whole chunk count
        for (String existing : new String[]{"'4096b'", "'16c'", "'256'", "'2km'", "'1mi'", "'3 chunks'", "'2r'"}) {
            for (int chunks : new int[]{1, 7, 260, 4096}) {
                assertEquals(chunks, num("normaliseSetting(formatLikeExisting(" + existing + ", " + chunks + "))"), 1e-9,
                        existing + " -> " + chunks + " chunks round-trips");
            }
        }
    }

    @Test
    @DisplayName("clampHandleChunks: radius >= 1 and above the hole, centerRadius in [0, radius - 1], extents >= 1")
    void clampRules() {
        assertEquals(65, num("clampHandleChunks('outer', 10, 64)"), "outer stays above the hole");
        assertEquals(1, num("clampHandleChunks('outer', 0, 0)"), "radius >= 1");
        assertEquals(7, num("clampHandleChunks('outer', 3, 6.25)"), "fractional (block-valued) hole");
        assertEquals(300, num("clampHandleChunks('outer', 300, 64)"));
        assertEquals(255, num("clampHandleChunks('inner', 300, 256)"), "hole below the outer edge");
        assertEquals(0, num("clampHandleChunks('inner', -5, 256)"), "hole >= 0");
        assertEquals(6, num("clampHandleChunks('inner', 10, 6.25)"), "hole below a fractional outer edge");
        assertEquals(1, num("clampHandleChunks('extent', 0, 0)"), "width / height >= 1");
        assertEquals(32, num("snapChunks(37, 32)"), "region-file snap grid");
        assertEquals(37, num("snapChunks(37.4, 1)"), "whole chunks");
    }

    @Test
    @DisplayName("Layout: one move grip, edge grips per shape in screen px, hole grips dropped when they would cover the centre")
    void layoutPerShape() {
        String t = "{ wox: 400, woy: 300, scale: 0.05 }";
        JsonArray circle = arr("regionHandleLayout('CIRCLE', { rBlocks: 4096, crBlocks: 1024 }, { x: 8, z: 8 }, " + t + ")");
        assertEquals(9, circle.size(), "move + 4 outer + 4 inner");
        JsonObject move = circle.get(0).getAsJsonObject();
        assertEquals("move", move.get("kind").getAsString());
        assertEquals(400.4, move.get("sx").getAsDouble(), 1e-9);
        JsonObject east = circle.get(1).getAsJsonObject();
        assertEquals("outer:radius:x+", east.get("id").getAsString());
        assertEquals(400.4 + 204.8, east.get("sx").getAsDouble(), 1e-9, "radius 256c at 0.05 px/block");
        assertEquals(256, east.get("chunks").getAsDouble(), 1e-9);
        assertEquals(64, east.get("limit").getAsDouble(), 1e-9, "outer clamps against the hole");
        assertEquals("radial", east.get("metric").getAsString());

        JsonArray tinyHole = arr("regionHandleLayout('SQUARE_OPTIMIZED_DUAL_LAYER', { rBlocks: 4096, crBlocks: 100 }, { x: 8, z: 8 }, " + t + ")");
        assertEquals(5, tinyHole.size(), "5 px hole: no inner grips, the move grip stays reachable");

        JsonArray ellipse = arr("regionHandleLayout('ELLIPSE', { rxBlocks: 4096, rzBlocks: 2048, crxBlocks: 1024, crzBlocks: 512 }, { x: 8, z: 8 }, " + t + ")");
        assertTrue(ellipse.toString().contains("\"outer:radius2:z+\""), "radius2 on the z axis");
        assertTrue(ellipse.toString().contains("\"inner:centerRadius2:z-\""), "centerRadius2 on the z axis");

        JsonArray rect = arr("regionHandleLayout('RECTANGLE', { rxBlocks: 2048, rzBlocks: 1024, crBlocks: 0 }, { x: 8, z: 8 }, " + t + ")");
        assertEquals(9, rect.size(), "move + 4 edges + 4 corners");
        JsonObject width = rect.get(1).getAsJsonObject();
        assertEquals("width", width.get("key").getAsString());
        assertEquals(256, width.get("chunks").getAsDouble(), 1e-9, "width is the full extent (2 x half)");
        assertEquals(2, width.get("mult").getAsInt());

        JsonArray poly = arr("regionHandleLayout('POLYGON', { rBlocks: 1600 }, { x: 8, z: 8 }, " + t + ")");
        assertEquals(5, poly.size(), "move + 4 bounding-square scale grips");
        assertEquals("scale", poly.get(1).getAsJsonObject().get("role").getAsString());

        assertEquals(0, arr("regionHandleLayout('ADDON_SHAPE', { rBlocks: 4096 }, { x: 8, z: 8 }, " + t + ")").size());
        assertEquals(1, arr("regionHandleLayout('ADDON_SHAPE', { rBlocks: 4096 }, { x: 8, z: 8 }, " + t + ", { movable: true })").size(),
                "add-on shapes with centerX: move only");
    }

    @Test
    @DisplayName("Hit-test priority: vertex > resize grip > move grip > inside polygon > pan")
    void hitTestPriority() {
        String handles = "[{ id: 'move', kind: 'move', sx: 100, sy: 100 }, { id: 'r', kind: 'resize', sx: 108, sy: 100 }]";
        String poly = "[[50, 50], [150, 50], [150, 150], [50, 150]]";
        assertEquals("vertex", obj("regionHitTest(104, 100, [[104, 100]], " + handles + ", " + poly + ")").get("type").getAsString());
        JsonObject resize = obj("regionHitTest(101, 100, [], " + handles + ", " + poly + ")");
        assertEquals("r", resize.getAsJsonObject("handle").get("id").getAsString(), "resize wins over an overlapping move grip");
        assertEquals("move", obj("regionHitTest(92, 100, [], " + handles + ", " + poly + ")")
                .getAsJsonObject("handle").get("id").getAsString());
        JsonObject inside = obj("regionHitTest(60, 140, [], " + handles + ", " + poly + ")");
        assertEquals("inside", inside.get("type").getAsString());
        assertEquals("move", inside.getAsJsonObject("handle").get("id").getAsString(), "inside drags the move grip");
        assertEquals("pan", obj("regionHitTest(10, 10, [], " + handles + ", " + poly + ")").get("type").getAsString());
        assertEquals("pan", obj("regionHitTest(60, 140, [], " + handles + ", null)").get("type").getAsString(),
                "no inside-move for radial shapes");
    }

    @Test
    @DisplayName("Polygon translate / scale by whole chunks; collapsing or self-intersecting scales are refused")
    void polygonScaleAndTranslate() {
        String square = "[[0, 0], [10, 0], [10, 10], [0, 10]]";
        assertEquals("[[3,-2],[13,-2],[13,8],[3,8]]", str("JSON.stringify(translatePolygon(" + square + ", 3, -2))"));
        assertEquals("[[-5,-5],[15,-5],[15,15],[-5,15]]", str("JSON.stringify(scalePolygon(" + square + ", 5, 5, 2))"));
        assertTrue(bool("scalePolygon(" + square + ", 5, 5, 0.01) === null"), "collapse to one point refused");
        assertTrue(bool("scalePolygon([[0, 0], [10, 0], [5, 1]], 5, 0.5, 0.05) === null"), "fewer than 3 distinct points refused");
        String notch = "[[0, 0], [10, 0], [10, 10], [5, 1], [0, 10]]";
        assertFalse(bool("polygonSelfIntersects(" + notch + ")"), "notched start polygon is simple");
        assertTrue(bool("scalePolygon(" + notch + ", 5, 5, 0.1) === null"), "rounding that folds the notch onto a vertex refused");
        String bowtie = "[[0, 0], [10, 10], [10, 0], [0, 10]]";
        assertTrue(bool("polygonSelfIntersects(" + bowtie + ")"));
        assertFalse(bool("scalePolygon(" + bowtie + ", 5, 5, 2) === null"), "an already crossing polygon may still be scaled");
        assertEquals(312.5, num("handleDragChunks({ metric: 'radial' }, 3008, 4008, { x: 8, z: 8 })"), 1e-9, "radial distance");
        assertEquals(200, num("handleDragChunks({ axis: 'x', metric: 'axis', mult: 2 }, 1608, -9999, { x: 8, z: 8 })"), 1e-9,
                "full extent along x");
        assertEquals("radius 260c (4160 b)", str("regionHandleLabel({ kind: 'resize', key: 'radius', axis: 'x', chunks: 260 })"));
    }

    @Test
    @DisplayName("Radius drag stages '4160b' through applyShapeSetting live, re-stages on mouseup, hole clamped below it")
    void circleDragStagesThroughSharedPath() {
        context.eval("js", "reset({ name: 'default', shape: 'CIRCLE', settings: { radius: '4096b', centerRadius: '64' } })");
        String t = "{ wox: 0, woy: 0, scale: 0.1 }";
        context.eval("js", "var h = grip(" + t + ", 'outer:radius:x+'); startRegionHandleDrag(h, h.sx, h.sy, " + t + ")");
        assertTrue(bool("regionHandleDrag !== null && regionHandleDrag.id === 'outer:radius:x+'"));
        assertEquals("ew-resize", str("canvas.style.cursor"));
        context.eval("js", "dragRegionHandle(4168, 8)");
        assertEquals("[{\"key\":\"radius\",\"value\":\"4160b\",\"live\":true}]", str("JSON.stringify(calls)"),
                "live edit through applyShapeSetting, unit style kept");
        context.eval("js", "dragRegionHandle(4170, 8)");
        assertEquals(1, num("calls.length"), "same whole-chunk value: no re-stage per mousemove");

        assertTrue(bool("endRegionHandleDrag()"));
        assertTrue(bool("regionHandleDrag === null"));
        assertEquals("{\"key\":\"radius\",\"value\":\"4160b\",\"live\":false}", str("JSON.stringify(calls[1])"),
                "mouseup re-stages the final value with the drag cleared");
        assertEquals("4160b", str("regionState.settings.radius"));

        // Hole grip: clamped below the (new) outer edge, unitless as written
        context.eval("js", "calls.length = 0; var c = grip(" + t + ", 'inner:centerRadius:x+'); startRegionHandleDrag(c, c.sx, c.sy, " + t + ")");
        context.eval("js", "dragRegionHandle(99999, 8); endRegionHandleDrag()");
        assertEquals("259", str("regionState.settings.centerRadius"), "centerRadius < radius");

        // Integer-kind schema keys stay unitless even when a sibling uses blocks
        context.eval("js", "schemaStub = { centerx: { kind: 'integer' } }; regionState.settings.centerZ = '0b'; calls.length = 0;"
                + "var m = grip(" + t + ", 'move'); startRegionHandleDrag(m, m.sx, m.sy, " + t + "); dragRegionHandle(8 + 160, 8 + 320)");
        assertEquals("10", str("regionState.settings.centerX"), "integer kind: unitless chunks");
        assertEquals("320b", str("regionState.settings.centerZ"), "blocks style kept");
        context.eval("js", "endRegionHandleDrag()");
    }

    @Test
    @DisplayName("Polygon: move grip translates vertices (and a set centerX), scale grip scales them about the centre")
    void polygonDragUsesVertexPath() {
        context.eval("js", "reset({ name: 'poly', shape: 'POLYGON', vertices: [[0, 0], [10, 0], [10, 10], [0, 10]], settings: { centerX: '16c' } })");
        String t = "{ wox: 0, woy: 0, scale: 1 }";
        context.eval("js", "var m = grip(" + t + ", 'move'); startRegionHandleDrag(m, m.sx, m.sy, " + t + ")");
        assertEquals(88, num("grip(" + t + ", 'move').sx"), 1e-9, "polygon centre = bounding-box centre");
        context.eval("js", "dragRegionHandle(88 + 160, 88 - 32)");
        assertEquals("[[10,-2],[20,-2],[20,8],[10,8]]", str("JSON.stringify(regionState.vertices)"));
        assertEquals("26c", str("regionState.settings.centerX"), "set centerX follows the vertices");
        assertTrue(bool("regionState.settings.centerZ === undefined"), "unset centerZ stays unset");
        context.eval("js", "endRegionHandleDrag()");
        assertEquals(1, num("counters.vertexLists"), "mouseup refreshes the vertex list");

        context.eval("js", "reset({ name: 'poly', shape: 'POLYGON', vertices: [[0, 0], [10, 0], [10, 10], [0, 10]], settings: {} })");
        context.eval("js", "var s = grip(" + t + ", 'scale:radius:x+'); startRegionHandleDrag(s, s.sx, s.sy, " + t + ")");
        assertEquals(176, num("s.sx"), 1e-9, "bounding-square half 5.5c from the centre");
        context.eval("js", "dragRegionHandle(264, 88)");
        assertEquals("[[-5,-5],[15,-5],[15,15],[-5,15]]", str("JSON.stringify(regionState.vertices)"), "x2 about the centre");
        context.eval("js", "dragRegionHandle(89, 88)");
        assertEquals("[[-5,-5],[15,-5],[15,15],[-5,15]]", str("JSON.stringify(regionState.vertices)"),
                "collapsing scale keeps the last valid polygon");
        assertTrue(num("counters.stages") >= 1, "vertex edits staged via updateStagingDiff");
        context.eval("js", "endRegionHandleDrag()");
        assertEquals(0, num("calls.length"), "polygon scale writes vertices, not settings");
    }

    @Test
    @DisplayName("Page wiring: mouseup ends grip drags, previews wait for mouseup, grips drawn and hit-tested from one layout")
    void pageWiring() throws IOException {
        String html = page();
        int up = html.indexOf("window.addEventListener('mouseup'");
        assertTrue(up >= 0, "window mouseup listener");
        String mouseup = html.substring(up, html.indexOf("});", up));
        assertTrue(mouseup.contains("endRegionHandleDrag();"), "mouseup ends a grip drag");

        String end = functionSource(html, "endRegionHandleDrag");
        int cleared = end.indexOf("regionHandleDrag = null;");
        int restage = end.indexOf("setRegionHandleSetting(key, chunks, true)");
        assertTrue(cleared >= 0 && restage > cleared, "drag cleared before the final re-stage, so it may ask for previews");
        assertTrue(end.contains("drawMap();") && end.contains("updateStagingDiff();"), "mouseup redraws and stages");

        String set = functionSource(html, "setRegionHandleSetting");
        assertTrue(set.contains("applyShapeSetting(key, value, row);"), "grip edits use the typed-edit path");

        for (String fn : new String[]{"requestWalkPath", "requestCurveState"}) {
            assertTrue(functionSource(html, fn).contains("if (regionHandleDrag) return;"), fn + " waits for mouseup");
        }
        for (String fn : new String[]{"dragRegionHandle", "startRegionHandleDrag", "setRegionHandleSetting"}) {
            String src = functionSource(html, fn);
            assertFalse(src.contains("EditorChannelClient") || src.contains("requestWalkPath(") || src.contains("requestCurveState("),
                    fn + " sends nothing over the rate-limited channel");
        }

        assertTrue(functionSource(html, "drawMap").contains("drawRegionHandles(wox, woy, scale, cssW, cssH);"), "grips drawn every frame");
        int down = html.indexOf("canvas.addEventListener('mousedown'");
        String mousedown = html.substring(down, html.indexOf("\n});", down));
        assertTrue(mousedown.contains("canvasHitTest(mx, my, t)"), "mousedown hit-tests the drawn layout");
        assertTrue(mousedown.contains("startRegionHandleDrag(hit.handle, mx, my, t)"));
        assertTrue(functionSource(html, "drawRegionHandles").contains("currentRegionHandles("), "drawing shares the layout");
        assertTrue(functionSource(html, "canvasHitTest").contains("currentRegionHandles(t)"), "hit-testing shares the layout");
    }
}
