package io.github.dailystruggle.rtp.common.commands.editor;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
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
 * ADR-104 / ADR-106 Web Editor Undo/Redo History Engine contract tests.
 * Validates bounded memento history stacks, drag transaction coalescing,
 * shape switching reversal, polygon vertex manipulation, and hotkey routing
 * with native text editing focus isolation.
 */
@DisplayName("ADR-106: Web Editor Undo/Redo History System contract tests")
class EditorUndoRedoPageTest {

    private static final String[] PAGE_CONSTS = {
            "DISTANCE_UNIT_ALIAS", "DISTANCE_TOKEN_RE", "REGION_HANDLE_PX", "REGION_HANDLE_HIT_PX",
            "VERTEX_HIT_PX", "REGION_HANDLE_STYLE_KEYS", "initialRegionSnapshots", "HistoryEntry", "EditorHistoryManager"
    };

    private static final String[] PAGE_FUNCTIONS = {
            "normaliseSetting", "settingKeyOf", "settingValue", "settingChunks", "getRegionSpan", "regionCenterBlocks",
            "polygonAabb", "segmentsIntersect", "regionShapeFamily", "formatLikeExisting", "clampHandleChunks",
            "snapChunks", "handleSnapStepChunks", "handleDragChunks", "regionHandleLayout", "pointInPolygon",
            "regionHitTest", "translatePolygon", "polygonSelfIntersects", "scalePolygon", "regionHandleLabel",
            "currentRegionHandles", "canvasHitTest", "shapeSettingRow", "setRegionHandleSetting", "stageHandleSetting",
            "setPolygonVertices", "startRegionHandleDrag", "dragRegionHandle", "endRegionHandleDrag",
            "cloneRegionSnapshot", "recordInitialRegionSnapshot", "applyHistorySnapshot", "updateHistoryToolbarUI",
            "resetStaging", "parseVertexCoord", "updateVertexCoord", "commitVertexCoordEdit",
            "addPolygonVertex", "removePolygonVertex", "updateRegionShape", "shapeSettingError",
            "applyShapeSetting", "commitShapeSettingEdit", "isNativeTextEditingTarget", "handleEditorHistoryHotkeys"
    };

    private static final String STUBS = String.join("\n",
            "const regionProfiles = {",
            "  default: { name: 'default', shape: 'CIRCLE', settings: { radius: '256c', centerRadius: '64c', centerX: '0', centerZ: '0' }, vertices: [[-125, 187], [125, 187], [125, -187], [-125, -187]] },",
            "  poly: { name: 'poly', shape: 'POLYGON', settings: { centerX: '0', centerZ: '0' }, vertices: [[0, 0], [10, 0], [10, 10], [0, 10]] }",
            "};",
            "let regionState = regionProfiles.default;",
            "let activeGridSnap = 16;",
            "let regionHandleDrag = null;",
            "let hoverRegionHandle = '';",
            "let draggingVertex = -1;",
            "let selectedVertex = -1;",
            "let shapeSettingTimer = null;",
            "let shapeSettingStartSnapshot = null;",
            "let vertexCoordTimer = null;",
            "let vertexCoordStartSnapshot = null;",
            "let schemaStub = {};",
            "function setTimeout(fn, ms) { return 1; }",
            "function clearTimeout(id) {}",
            "const calls = [];",
            "const counters = { draws: 0, stages: 0, vertexLists: 0 };",
            "const canvas = { style: {} };",
            "const undoBtnStub = { disabled: true, title: '' };",
            "const redoBtnStub = { disabled: true, title: '' };",
            "const document = {",
            "  activeElement: null,",
            "  getElementById: (id) => {",
            "    if (id === 'btn-undo') return undoBtnStub;",
            "    if (id === 'btn-redo') return redoBtnStub;",
            "    if (id === 'hud-shape' || id === 'hud-region' || id === 'hud-coords') return { textContent: '' };",
            "    if (id === 'reg-shape') return { value: '' };",
            "    if (id === 'poly-points-count') return { textContent: '' };",
            "    if (id === 'polygon-vertices-list') return { innerHTML: '' };",
            "    if (id === 'shape-settings-form') return { textContent: '', appendChild: () => {}, querySelectorAll: () => [] };",
            "    return null;",
            "  }",
            "};",
            "function shapeSettingSchema(shape, key) { return schemaStub[String(key).toLowerCase()] || null; }",
            "function drawMap() { counters.draws++; }",
            "function updateStagingDiff() { counters.stages++; }",
            "function stageRegionGeometry(region) { counters.stages++; return 'definitions/regions/' + region.name + '.yml'; }",
            "function syncPolygonAABB() {}",
            "function renderPolygonVerticesList() { counters.vertexLists++; }",
            "function syncShapeSpecificControls(region) {}",
            "function renderShapeSettingsForm(region) {}",
            "function populateShapeSelect(selected) {}",
            "function updateJumpToYamlButton() {}",
            "function changeRegionProfile(name) {",
            "  if (regionProfiles[name]) {",
            "    regionState = regionProfiles[name];",
            "  }",
            "}",
            "function resetTestState() {",
            "  EditorHistoryManager.clear();",
            "  EditorHistoryManager.setMaxStack(50);",
            "  regionProfiles.default = { name: 'default', shape: 'CIRCLE', settings: { radius: '256c', centerRadius: '64c', centerX: '0', centerZ: '0' }, vertices: [[-125, 187], [125, 187], [125, -187], [-125, -187]] };",
            "  regionProfiles.poly = { name: 'poly', shape: 'POLYGON', settings: { centerX: '0', centerZ: '0' }, vertices: [[0, 0], [10, 0], [10, 10], [0, 10]] };",
            "  regionState = regionProfiles.default;",
            "  initialRegionSnapshots.default = cloneRegionSnapshot(regionProfiles.default);",
            "  initialRegionSnapshots.poly = cloneRegionSnapshot(regionProfiles.poly);",
            "  activeGridSnap = 16;",
            "  regionHandleDrag = null;",
            "  hoverRegionHandle = '';",
            "  draggingVertex = -1;",
            "  selectedVertex = -1;",
            "  schemaStub = {};",
            "  calls.length = 0;",
            "  counters.draws = counters.stages = counters.vertexLists = 0;",
            "  document.activeElement = null;",
            "  updateHistoryToolbarUI();",
            "}",
            "function grip(t, id) { return currentRegionHandles(t).find(h => h.id === id); }",
            "");

    private static Context context;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

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

    private static String functionSource(String html, String name) {
        int start = html.indexOf("function " + name + "(");
        assertTrue(start >= 0, "page function missing: " + name);
        assertEquals(-1, html.indexOf("function " + name + "(", start + 1), "page function declared once: " + name);
        return braced(html, start, html.indexOf(')', start), name);
    }

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
        for (String c : PAGE_CONSTS) {
            src.append(constSource(html, c)).append('\n');
        }
        for (String fn : PAGE_FUNCTIONS) {
            src.append(functionSource(html, fn)).append('\n');
        }
        context = Context.newBuilder("js")
                .allowAllAccess(true)
                .build();
        context.eval("js", src.toString());
    }

    @AfterAll
    static void stopEngine() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    @BeforeEach
    void resetState() {
        context.eval("js", "resetTestState()");
    }

    private static Value eval(String js) {
        return context.eval("js", js);
    }

    private static boolean bool(String js) {
        return eval(js).asBoolean();
    }

    private static int num(String js) {
        return eval(js).asInt();
    }

    private static String str(String js) {
        return eval(js).asString();
    }

    @Test
    @DisplayName("Transaction coalescing: dragging a vertex across multiple frames produces exactly one undo entry")
    void vertexDragCoalescing() {
        eval("regionState = regionProfiles.poly");
        eval("EditorHistoryManager.clear()");

        // Simulate mousedown on vertex 0
        eval("draggingVertex = 0; selectedVertex = 0; EditorHistoryManager.beginGesture('Drag Vertex', regionState)");
        assertEquals(0, num("EditorHistoryManager.undoStack.length"));

        // Simulate high-frequency mousemove events across multiple points
        eval("regionState.vertices[0] = [1, 2]; drawMap()");
        eval("regionState.vertices[0] = [3, 4]; drawMap()");
        eval("regionState.vertices[0] = [5, 6]; drawMap()");
        assertEquals(0, num("EditorHistoryManager.undoStack.length"), "Intermediate drag frames must not create history entries");

        // Simulate mouseup
        eval("EditorHistoryManager.commitGesture(regionState); draggingVertex = -1");
        assertEquals(1, num("EditorHistoryManager.undoStack.length"), "Exactly one undo entry on mouseup");
        assertTrue(bool("EditorHistoryManager.canUndo()"));
        assertFalse(bool("EditorHistoryManager.canRedo()"));
        assertEquals("Drag Vertex", str("EditorHistoryManager.getUndoDesc()"));
        assertEquals("[[5,6],[10,0],[10,10],[0,10]]", str("JSON.stringify(regionState.vertices)"));

        // Perform Undo
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals(0, num("EditorHistoryManager.undoStack.length"));
        assertEquals(1, num("EditorHistoryManager.redoStack.length"));
        assertTrue(bool("EditorHistoryManager.canRedo()"));
        assertEquals("[[0,0],[10,0],[10,10],[0,10]]", str("JSON.stringify(regionState.vertices)"), "Undo restores vertex to start coordinate");

        // Perform Redo
        assertTrue(bool("EditorHistoryManager.redo()"));
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));
        assertEquals(0, num("EditorHistoryManager.redoStack.length"));
        assertEquals("[[5,6],[10,0],[10,10],[0,10]]", str("JSON.stringify(regionState.vertices)"), "Redo restores dragged coordinate");
    }

    @Test
    @DisplayName("Transaction coalescing: handle drag scales region with intermediate points and records one entry")
    void handleDragCoalescing() {
        String t = "{ wox: 0, woy: 0, scale: 0.1 }";
        eval("var h = grip(" + t + ", 'outer:radius:x+'); startRegionHandleDrag(h, h.sx, h.sy, " + t + ")");
        assertEquals(0, num("EditorHistoryManager.undoStack.length"));

        // Move through intermediate coordinates
        eval("dragRegionHandle(4168, 8)");
        eval("dragRegionHandle(4200, 8)");
        eval("dragRegionHandle(4300, 8)");
        assertEquals(0, num("EditorHistoryManager.undoStack.length"), "Intermediate mouse movements must not push history");

        // Mouseup ends drag
        assertTrue(bool("endRegionHandleDrag()"));
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));
        assertEquals("Adjust Region Handle", str("EditorHistoryManager.getUndoDesc()"));

        // Undo restores radius
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals("256c", str("regionState.settings.radius"));

        // Redo restores dragged value
        assertTrue(bool("EditorHistoryManager.redo()"));
        assertNotEquals("256c", str("regionState.settings.radius"));
    }

    @Test
    @DisplayName("Zero-delta drag: clicking a vertex or grip without moving does not create an undo entry")
    void zeroDeltaDragProducesNoHistory() {
        eval("regionState = regionProfiles.poly");
        eval("EditorHistoryManager.clear()");

        // Click and release without changing coordinates
        eval("EditorHistoryManager.beginGesture('Drag Vertex', regionState)");
        eval("EditorHistoryManager.commitGesture(regionState)");
        assertEquals(0, num("EditorHistoryManager.undoStack.length"), "Zero delta must not record history");
        assertFalse(bool("EditorHistoryManager.canUndo()"));
    }

    @Test
    @DisplayName("Shape switch: switching from CIRCLE to POLYGON and undoing restores settings and geometry")
    void shapeSwitchUndoRedo() {
        assertEquals("CIRCLE", str("regionState.shape"));
        assertEquals("256c", str("regionState.settings.radius"));

        // Switch to POLYGON
        eval("updateRegionShape('POLYGON')");
        assertEquals("POLYGON", str("regionState.shape"));
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));
        assertEquals("Switch Shape to POLYGON", str("EditorHistoryManager.getUndoDesc()"));

        // Undo switch
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals("CIRCLE", str("regionState.shape"));
        assertEquals("256c", str("regionState.settings.radius"));

        // Redo switch
        assertTrue(bool("EditorHistoryManager.redo()"));
        assertEquals("POLYGON", str("regionState.shape"));
    }

    @Test
    @DisplayName("Polygon vertex modification: adding and removing vertices reverses cleanly")
    void polygonVertexAddRemoveUndo() {
        eval("regionState = regionProfiles.poly");
        eval("EditorHistoryManager.clear()");
        assertEquals(4, num("regionState.vertices.length"));

        // Add a vertex
        eval("addPolygonVertex()");
        assertEquals(5, num("regionState.vertices.length"));
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));
        assertEquals("Add Vertex", str("EditorHistoryManager.getUndoDesc()"));

        // Remove vertex at index 1
        eval("removePolygonVertex(1)");
        assertEquals(4, num("regionState.vertices.length"));
        assertEquals(2, num("EditorHistoryManager.undoStack.length"));
        assertEquals("Remove Vertex", str("EditorHistoryManager.getUndoDesc()"));

        // Undo vertex removal
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals(5, num("regionState.vertices.length"));
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));

        // Undo vertex addition
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals(4, num("regionState.vertices.length"));
        assertEquals(0, num("EditorHistoryManager.undoStack.length"));
        assertEquals("[[0,0],[10,0],[10,10],[0,10]]", str("JSON.stringify(regionState.vertices)"));
    }

    @Test
    @DisplayName("Stack limits and branch invalidation: capped at 50, and new edits discard redo stack")
    void stackLimitsAndBranchInvalidation() {
        eval("regionState = regionProfiles.poly");
        eval("EditorHistoryManager.clear()");

        // Perform 60 consecutive discrete edits
        for (int i = 0; i < 60; i++) {
            eval("EditorHistoryManager.push(new HistoryEntry('Edit " + i + "', 'poly', { shape: 'POLYGON', settings: { step: '" + i + "' }, vertices: [] }, { shape: 'POLYGON', settings: { step: '" + (i + 1) + "' }, vertices: [] }))");
        }
        assertEquals(50, num("EditorHistoryManager.undoStack.length"), "Stack must be capped at 50");

        // Undo 3 actions
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals(47, num("EditorHistoryManager.undoStack.length"));
        assertEquals(3, num("EditorHistoryManager.redoStack.length"));
        assertTrue(bool("EditorHistoryManager.canRedo()"));

        // Perform a new edit while redo stack is non-empty
        eval("EditorHistoryManager.push(new HistoryEntry('Branch Action', 'poly', { shape: 'POLYGON', settings: {}, vertices: [] }, { shape: 'POLYGON', settings: { branched: 'true' }, vertices: [] }))");
        assertEquals(48, num("EditorHistoryManager.undoStack.length"));
        assertEquals(0, num("EditorHistoryManager.redoStack.length"), "New action must invalidate redo stack");
        assertFalse(bool("EditorHistoryManager.canRedo()"));

        // Test custom maxStack depth
        eval("EditorHistoryManager.setMaxStack(10)");
        assertEquals(10, num("EditorHistoryManager.undoStack.length"), "setMaxStack must evict surplus history entries");
    }

    @Test
    @DisplayName("Revert button reversibility: restoring baseline is recorded as an undoable action")
    void revertButtonReversibility() {
        // Mutate settings
        eval("regionState.settings.radius = '512c'");
        eval("regionState.settings.centerX = '100'");
        assertNotEquals(str("JSON.stringify(initialRegionSnapshots.default)"), str("JSON.stringify(cloneRegionSnapshot(regionState))"));

        // Click Revert
        eval("resetStaging()");
        assertEquals("256c", str("regionState.settings.radius"), "Baseline radius restored");
        assertEquals("0", str("regionState.settings.centerX"), "Baseline center restored");
        assertEquals(1, num("EditorHistoryManager.undoStack.length"), "Revert pushed onto undo stack");
        assertEquals("Revert to baseline", str("EditorHistoryManager.getUndoDesc()"));

        // Undo the revert
        assertTrue(bool("EditorHistoryManager.undo()"));
        assertEquals("512c", str("regionState.settings.radius"), "Pre-revert radius restored");
        assertEquals("100", str("regionState.settings.centerX"), "Pre-revert center restored");

        // Redo the revert
        assertTrue(bool("EditorHistoryManager.redo()"));
        assertEquals("256c", str("regionState.settings.radius"), "Revert re-applied");
    }

    @Test
    @DisplayName("Hotkey routing and native text focus isolation: inputs/textarea are bypassed")
    void hotkeyRoutingAndFocusGuard() {
        eval("EditorHistoryManager.push(new HistoryEntry('Sample', 'default', { shape: 'CIRCLE', settings: { radius: '256c' }, vertices: [] }, { shape: 'CIRCLE', settings: { radius: '512c' }, vertices: [] }))");
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));

        // Native text targets must NOT trigger history undo/redo
        assertTrue(bool("isNativeTextEditingTarget({ tagName: 'TEXTAREA' })"));
        assertTrue(bool("isNativeTextEditingTarget({ tagName: 'INPUT', type: 'text' })"));
        assertTrue(bool("isNativeTextEditingTarget({ tagName: 'INPUT', type: 'search' })"));
        assertTrue(bool("isNativeTextEditingTarget({ tagName: 'INPUT', type: 'number' })"));
        assertTrue(bool("isNativeTextEditingTarget({ isContentEditable: true })"));
        assertFalse(bool("isNativeTextEditingTarget({ tagName: 'CANVAS' })"));
        assertFalse(bool("isNativeTextEditingTarget({ tagName: 'DIV' })"));
        assertFalse(bool("isNativeTextEditingTarget({ tagName: 'BUTTON' })"));

        // Hotkey when focused in textarea: event bypassed, undo not triggered
        boolean bypassed = bool("handleEditorHistoryHotkeys({ ctrlKey: true, key: 'z', target: { tagName: 'TEXTAREA' }, preventDefault: () => {} })");
        assertFalse(bypassed, "Textarea must bypass custom hotkey");
        assertEquals(1, num("EditorHistoryManager.undoStack.length"), "Stack must remain untouched");

        // Hotkey when focused in canvas: event intercepted, undo triggered
        boolean handled = bool("handleEditorHistoryHotkeys({ ctrlKey: true, key: 'z', target: { tagName: 'CANVAS' }, preventDefault: () => {} })");
        assertTrue(handled, "Canvas hotkey must trigger undo");
        assertEquals(0, num("EditorHistoryManager.undoStack.length"));
        assertEquals(1, num("EditorHistoryManager.redoStack.length"));

        // Redo via Ctrl+Y
        boolean redoneY = bool("handleEditorHistoryHotkeys({ ctrlKey: true, key: 'y', target: { tagName: 'CANVAS' }, preventDefault: () => {} })");
        assertTrue(redoneY, "Ctrl+Y must trigger redo");
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));

        // Redo via Ctrl+Shift+Z
        eval("EditorHistoryManager.undo()");
        boolean redoneShiftZ = bool("handleEditorHistoryHotkeys({ ctrlKey: true, shiftKey: true, key: 'z', target: { tagName: 'CANVAS' }, preventDefault: () => {} })");
        assertTrue(redoneShiftZ, "Ctrl+Shift+Z must trigger redo");
        assertEquals(1, num("EditorHistoryManager.undoStack.length"));
    }

    @Test
    @DisplayName("Toolbar buttons: enabled states and action tooltip descriptors update reactively")
    void toolbarUIStateUpdates() {
        // Initially clean
        eval("updateHistoryToolbarUI()");
        assertTrue(bool("undoBtnStub.disabled"), "Undo disabled initially");
        assertTrue(bool("redoBtnStub.disabled"), "Redo disabled initially");

        // Add action
        eval("EditorHistoryManager.push(new HistoryEntry('Move Grip', 'default', { shape: 'CIRCLE', settings: {}, vertices: [] }, { shape: 'CIRCLE', settings: { centerX: '10' }, vertices: [] }))");
        assertFalse(bool("undoBtnStub.disabled"), "Undo button enabled when undoStack > 0");
        assertTrue(bool("redoBtnStub.disabled"), "Redo button remains disabled");
        assertTrue(str("undoBtnStub.title").contains("Undo: Move Grip (Ctrl+Z)"));

        // Undo action
        eval("EditorHistoryManager.undo()");
        assertTrue(bool("undoBtnStub.disabled"), "Undo button disabled after empty stack");
        assertFalse(bool("redoBtnStub.disabled"), "Redo button enabled when redoStack > 0");
        assertTrue(str("redoBtnStub.title").contains("Redo: Move Grip (Ctrl+Y)"));
    }
}
