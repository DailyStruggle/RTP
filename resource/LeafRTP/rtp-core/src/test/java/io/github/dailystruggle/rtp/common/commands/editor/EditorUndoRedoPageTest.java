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
            "VERTEX_HIT_PX", "REGION_HANDLE_STYLE_KEYS", "SCHEMA_DOC_TAGS", "initialRegionSnapshots",
            "HistoryEntry", "EditorHistoryManager"
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
            "applyShapeSetting", "commitShapeSettingEdit", "isNativeTextEditingTarget", "handleEditorHistoryHotkeys",
            "stripYamlValue", "parseYamlLines", "yamlKeyExtent", "isInheritedReferenceToken",
            "referencedDefaultBlock", "regionNameOfFile", "materializeCurrentReference"
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
            "let configs = {};",
            "let shippedConfigs = {};",
            "let docTrackerDb = {};",
            "let currentConfigFile = '';",
            "let currentActiveKey = 'shape';",
            "let currentRefToken = null;",
            "let execCommandEnabled = true;",
            "function Event(type, init) { this.type = type; this.bubbles = !!(init && init.bubbles); }",
            "const matBtnStub = { textContent: '⚡ Materialize', style: {} };",
            "const cfgEditor = {",
            "  _val: '',",
            "  _undoStack: [],",
            "  _redoStack: [],",
            "  selectionStart: 0,",
            "  selectionEnd: 0,",
            "  setRangeTextCalls: 0,",
            "  inputEvents: 0,",
            "  get value() { return this._val; },",
            "  set value(v) {",
            "    this._val = String(v);",
            "    this._undoStack = [];",
            "    this._redoStack = [];",
            "  },",
            "  initValue(v) {",
            "    this._val = String(v);",
            "    this._undoStack = [];",
            "    this._redoStack = [];",
            "    this.selectionStart = 0;",
            "    this.selectionEnd = 0;",
            "    this.setRangeTextCalls = 0;",
            "    this.inputEvents = 0;",
            "  },",
            "  focus() { document.activeElement = this; },",
            "  setSelectionRange(start, end) {",
            "    this.selectionStart = start;",
            "    this.selectionEnd = end;",
            "  },",
            "  setRangeText(replacement, start, end, selectMode) {",
            "    this.setRangeTextCalls++;",
            "    const s = start !== undefined ? start : this.selectionStart;",
            "    const e = end !== undefined ? end : this.selectionEnd;",
            "    this._undoStack.push({ val: this._val, start: s, end: e });",
            "    this._redoStack = [];",
            "    this._val = this._val.substring(0, s) + replacement + this._val.substring(e);",
            "    if (selectMode === 'end') {",
            "      this.selectionStart = this.selectionEnd = s + replacement.length;",
            "    }",
            "  },",
            "  dispatchEvent(ev) {",
            "    if (ev && ev.type === 'input') {",
            "      this.inputEvents++;",
            "      if (currentConfigFile) configs[currentConfigFile] = this._val;",
            "    }",
            "    return true;",
            "  },",
            "  nativeUndo() {",
            "    if (!this._undoStack.length) return false;",
            "    const prev = this._undoStack.pop();",
            "    this._redoStack.push({ val: this._val, start: this.selectionStart, end: this.selectionEnd });",
            "    this._val = prev.val;",
            "    this.selectionStart = prev.start;",
            "    this.selectionEnd = prev.end;",
            "    this.dispatchEvent(new Event('input', { bubbles: true }));",
            "    return true;",
            "  },",
            "  nativeRedo() {",
            "    if (!this._redoStack.length) return false;",
            "    const next = this._redoStack.pop();",
            "    this._undoStack.push({ val: this._val, start: this.selectionStart, end: this.selectionEnd });",
            "    this._val = next.val;",
            "    this.selectionStart = next.start;",
            "    this.selectionEnd = next.end;",
            "    this.dispatchEvent(new Event('input', { bubbles: true }));",
            "    return true;",
            "  }",
            "};",
            "function setTimeout(fn, ms) { return 1; }",
            "function clearTimeout(id) {}",
            "const calls = [];",
            "const counters = { draws: 0, stages: 0, vertexLists: 0, geomSyncs: 0, gutters: 0, lineTracks: 0 };",
            "const canvas = { style: {} };",
            "const undoBtnStub = { disabled: true, title: '' };",
            "const redoBtnStub = { disabled: true, title: '' };",
            "const document = {",
            "  activeElement: null,",
            "  execCommand: (cmd, showUI, val) => {",
            "    if (!execCommandEnabled || cmd !== 'insertText' || document.activeElement !== cfgEditor) return false;",
            "    const s = cfgEditor.selectionStart || 0;",
            "    const e = cfgEditor.selectionEnd !== undefined ? cfgEditor.selectionEnd : s;",
            "    cfgEditor._undoStack.push({ val: cfgEditor._val, start: s, end: e });",
            "    cfgEditor._redoStack = [];",
            "    cfgEditor._val = cfgEditor._val.substring(0, s) + val + cfgEditor._val.substring(e);",
            "    cfgEditor.selectionStart = cfgEditor.selectionEnd = s + val.length;",
            "    return true;",
            "  },",
            "  getElementById: (id) => {",
            "    if (id === 'btn-undo') return undoBtnStub;",
            "    if (id === 'btn-redo') return redoBtnStub;",
            "    if (id === 'btn-materialize') return matBtnStub;",
            "    if (id === 'hud-shape' || id === 'hud-region' || id === 'hud-coords' || id === 'hud-biome') return { textContent: '' };",
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
            "function applyYamlGeometry(name, text) { counters.geomSyncs++; }",
            "function updateGutterAndDiagnostics() { counters.gutters++; }",
            "function trackCurrentConfigLine() { counters.lineTracks++; }",
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
            "  configs = {};",
            "  shippedConfigs = {};",
            "  docTrackerDb = {};",
            "  currentConfigFile = '';",
            "  currentActiveKey = 'shape';",
            "  currentRefToken = null;",
            "  execCommandEnabled = true;",
            "  cfgEditor.initValue('');",
            "  calls.length = 0;",
            "  counters.draws = counters.stages = counters.vertexLists = counters.geomSyncs = counters.gutters = counters.lineTracks = 0;",
            "  document.activeElement = null;",
            "  updateHistoryToolbarUI();",
            "}",
            "function grip(t, id) { return currentRegionHandles(t).find(h => h.id === id); }",
            "");

    private static Context context;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "..", "..", "docs", "editor", "index.html");
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
        int depth = 0;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ';' && depth == 0) return html.substring(start, i + 1);
        }
        throw new AssertionError("unterminated constant " + name);
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

    @Test
    @DisplayName("RTP-31: materializeCurrentReference preserves native textarea undo/redo via execCommand with setRangeText fallback")
    void referenceMaterializePreservesNativeUndoRedoAndFallsBackToSetRangeText() {
        eval("currentConfigFile = 'regions/default.yml'");
        eval("configs['config.yml'] = 'defaults:\\n  shape:\\n    name: CIRCLE\\n    radius: 512c\\n'");
        eval("const initialYaml = 'world: \"world\"\\nshape: \"@config\"\\nvert: \"@config\"\\n'");
        eval("configs[currentConfigFile] = initialYaml");
        eval("cfgEditor.initValue(initialYaml)");

        // Simulate prior typing in the textarea so the native undo stack already holds an entry
        eval("cfgEditor.focus(); cfgEditor.setSelectionRange(0, 0); document.execCommand('insertText', false, '# header\\n')");
        assertEquals(1, num("cfgEditor._undoStack.length"), "Pre-materialize typing recorded in native undo stack");

        // Place caret on the `shape: "@config"` line and materialize
        eval("cfgEditor.selectionStart = cfgEditor.selectionEnd = cfgEditor.value.indexOf('shape:') + 3");
        eval("materializeCurrentReference()");

        String materialized = str("cfgEditor.value");
        assertTrue(materialized.contains("shape:\n  name: CIRCLE\n  radius: 512c"), "Reference line replaced with referenced default block:\n" + materialized);
        assertFalse(materialized.contains("shape: \"@config\""), "Inherited reference token replaced");
        assertEquals(2, num("cfgEditor._undoStack.length"), "Native undo stack must keep prior typing and add materialization entry (not wiped by direct .value assignment)");
        assertEquals(1, num("cfgEditor.inputEvents"), "materializeCurrentReference must dispatch an input event");
        assertTrue(num("counters.geomSyncs") >= 1, "Region geometry synchronized via applyYamlGeometry");
        assertTrue(num("counters.gutters") >= 1, "Gutter diagnostics refreshed");
        assertTrue(num("counters.stages") >= 1, "Staging diff updated");

        // Native Ctrl+Z undo restores `shape: "@config"`
        assertTrue(bool("cfgEditor.nativeUndo()"), "Native undo must succeed after materialize");
        assertTrue(str("cfgEditor.value").contains("shape: \"@config\""), "Undo restores original @config reference line");

        // Native Ctrl+Z a second time undoes the earlier `# header` typing
        assertTrue(bool("cfgEditor.nativeUndo()"), "Earlier typing before materialize must still be undoable");
        assertEquals(str("initialYaml"), str("cfgEditor.value"));

        // Native Ctrl+Y redo twice restores `# header` and materialized block
        assertTrue(bool("cfgEditor.nativeRedo()"));
        assertTrue(bool("cfgEditor.nativeRedo()"));
        assertEquals(materialized, str("cfgEditor.value"));

        // Fallback path when document.execCommand('insertText') returns false: setRangeText is used
        eval("execCommandEnabled = false");
        eval("cfgEditor.selectionStart = cfgEditor.selectionEnd = cfgEditor.value.indexOf('vert:') + 2");
        eval("docTrackerDb['vert'] = { defaultSnippet: 'vert:\\n  name: LINEAR\\n  minY: 64' }");
        eval("materializeCurrentReference()");
        assertEquals(1, num("cfgEditor.setRangeTextCalls"), "setRangeText fallback must be used when execCommand returns false");
        assertTrue(str("cfgEditor.value").contains("vert:\n  name: LINEAR\n  minY: 64"), "Fallback replaced vert reference");
        assertEquals(3, num("cfgEditor._undoStack.length"), "Fallback via setRangeText must not wipe native undo stack via direct .value assignment");
    }
}
