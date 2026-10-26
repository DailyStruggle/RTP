package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Web Editor Cross-Lingual Translation & Search Execution Test (ADR-104)")
class WebEditorSearchExecutionTest {

    @Test
    @DisplayName("Verify Levenshtein distance, multilingual synonyms, and scoring rank order")
    void testSearchEngineLogicDirectly() throws Exception {
        Path docsEditor = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(docsEditor)) {
            docsEditor = Paths.get("..", "docs", "editor", "index.html");
        }
        assertTrue(Files.exists(docsEditor));
        String html = Files.readString(docsEditor);

        // Extract script portion
        int scriptStart = html.indexOf("<script>");
        int scriptEnd = html.lastIndexOf("</script>");
        assertTrue(scriptStart != -1 && scriptEnd != -1);
        String js = html.substring(scriptStart + "<script>".length(), scriptEnd);

        // Remove DOM/browser event registrations and init calls that fail in headless JS engine
        js = js.replace("populateConfigSidebar();", "// populateConfigSidebar();");
        js = js.replace("selectConfig(currentConfigFile);", "// selectConfig(currentConfigFile);");
        js = js.replace("populateDocSidebar();", "// populateDocSidebar();");
        js = js.replace("selectDoc(currentDocFile);", "// selectDoc(currentDocFile);");
        js = js.replace("changeRegionProfile('survival_spawn');", "// changeRegionProfile();");
        js = js.replace("changeRegionProfile('default');", "// changeRegionProfile();");
        js = js.replace("updateDiagnosticsUI(currentMetrics);", "// updateDiagnosticsUI();");
        js = js.replace("setTimeout(resizeCanvas, 60);", "// setTimeout;");
        js = js.replace("initWebSocket();", "// initWebSocket();");
        js = js.replace("window.addEventListener", "// window.addEventListener");

        // Provide minimal mock browser/DOM globals if needed
        String preamble = "var window = { addEventListener: function(){} };"
                + "var document = { getElementById: function(){ return null; }, querySelectorAll: function(){ return []; } };"
                + "var navigator = { onLine: true };\n";

        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");
        if (engine == null) {
            runSearchVerificationViaNode(docsEditor);
            return;
        }

        engine.eval(preamble + js);
        Invocable invocable = (Invocable) engine;

        // 1. Test computeLevenshtein
        Object distObj = invocable.invokeFunction("computeLevenshtein", "raduis", "radius");
        int dist = ((Number) distObj).intValue();
        assertTrue(dist <= 2, "Typo 'raduis' to 'radius' must have Levenshtein distance <= 2");

        // 2. Test executeConfigSearch with Spanish query 'dinero'
        Object resultsSpanish = invocable.invokeFunction("executeConfigSearch", "dinero");
        assertTrue(resultsSpanish instanceof List);
        List<?> hitsSpanish = (List<?>) resultsSpanish;
        assertFalse(hitsSpanish.isEmpty(), "Searching 'dinero' must return results via multilingual thesaurus");

        // Check top result for 'dinero' is economy / price
        Map<?, ?> topHitSpanish = (Map<?, ?>) hitsSpanish.get(0);
        String topFile = (String) topHitSpanish.get("file");
        assertTrue(topFile.contains("economy"), "Top hit for 'dinero' must be in economy.yml");

        // 3. Test executeConfigSearch with German query 'geld'
        List<?> hitsGerman = (List<?>) invocable.invokeFunction("executeConfigSearch", "geld");
        assertFalse(hitsGerman.isEmpty(), "Searching 'geld' must return results");

        // 5. Test 'rad' matching 'radius' and similar phrases (bounds, border, distance, range)
        List<?> hitsRad = (List<?>) invocable.invokeFunction("executeConfigSearch", "rad");
        assertFalse(hitsRad.isEmpty(), "Searching 'rad' must return hits for radius and related shapes");
        boolean foundRadius = hitsRad.stream().anyMatch(h -> {
            Map<?, ?> map = (Map<?, ?>) h;
            String key = (String) map.get("key");
            return "radius".equalsIgnoreCase(key) || "centerRadius".equalsIgnoreCase(key);
        });
        assertTrue(foundRadius, "Searching 'rad' must find 'radius' or 'centerRadius'");

        // 6. Test similar phrases taking user to radius (e.g. 'border', 'bounds', 'distance')
        List<?> hitsBorder = (List<?>) invocable.invokeFunction("executeConfigSearch", "border");
        assertFalse(hitsBorder.isEmpty(), "Searching 'border' must return results");
        boolean borderFoundRadius = hitsBorder.stream().anyMatch(h -> {
            Map<?, ?> map = (Map<?, ?>) h;
            String key = (String) map.get("key");
            return "radius".equalsIgnoreCase(key) || "centerRadius".equalsIgnoreCase(key) || "worldBorderOverride".equalsIgnoreCase(key);
        });
        assertTrue(borderFoundRadius, "Searching 'border' must connect to radius or bounds");

        // 7. Test content-aware matching helper 'matchConfigContent'
        Object synsObj = invocable.invokeFunction("getMatchingSynonyms", List.of("rad"));
        Object matchContentRes = invocable.invokeFunction("matchConfigContent", "config.yml", "rad", synsObj);
        assertTrue(matchContentRes instanceof Map);
        Map<?, ?> matchMap = (Map<?, ?>) matchContentRes;
        assertTrue(Boolean.TRUE.equals(matchMap.get("matched")), "File content with radius must match 'rad' query");
        List<?> matchedKeysList = (List<?>) matchMap.get("matchedKeys");
        assertTrue(matchedKeysList.contains("radius") || matchedKeysList.contains("centerRadius"),
                "Matched keys must contain radius or centerRadius");
    }

    private static void runSearchVerificationViaNode(Path docsEditor) throws Exception {
        Process checkNode;
        try {
            checkNode = new ProcessBuilder("node", "-v").redirectErrorStream(true).start();
            if (!checkNode.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || checkNode.exitValue() != 0) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        Path editorDataPath = docsEditor.resolveSibling("editor-data.json");
        assertTrue(Files.exists(editorDataPath), "editor-data.json must exist beside index.html");
        String nodeScript = """
                const fs = require("fs");
                const vm = require("vm");
                const html = fs.readFileSync(process.argv[1], "utf8");
                const editorData = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
                const s0 = html.indexOf("<script>");
                const s1 = html.lastIndexOf("</script>");
                const js = html.slice(s0 + "<script>".length, s1);
                function makeStubEl() {
                  return new Proxy({
                    value: "", innerHTML: "", textContent: "", style: {}, dataset: {}, attributes: [], childNodes: [],
                    classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
                    addEventListener(){}, removeEventListener(){}, appendChild(){}, removeChild(){}, replaceChild(){}, remove(){},
                    setAttribute(){}, removeAttribute(){}, getAttribute(){ return ""; },
                    querySelector(){ return makeStubEl(); }, querySelectorAll(){ return []; },
                    getBoundingClientRect(){ return { width: 800, height: 600, left: 0, top: 0 }; },
                    getContext(){ return new Proxy({}, { get: () => () => ({ width: 10, addColorStop(){} }) }); }
                  }, { get(t, p) { return p in t ? t[p] : () => {}; } });
                }
                const elCache = new Map();
                const sandbox = {
                  window: { location: { search: "", hash: "", pathname: "/RTP/editor/", protocol: "https:", host: "dailystruggle.github.io" }, addEventListener: () => {}, devicePixelRatio: 1 },
                  document: {
                    getElementById: (id) => { if (id === "rtp-embedded-payload") return null; if (!elCache.has(id)) elCache.set(id, makeStubEl()); return elCache.get(id); },
                    querySelector: () => makeStubEl(), querySelectorAll: () => [], createElement: () => makeStubEl(),
                    createDocumentFragment: () => makeStubEl(), createTextNode: (t) => ({ nodeType: 3, textContent: t }),
                    addEventListener: () => {}, body: makeStubEl(), readyState: "complete"
                  },
                  DOMParser: class { parseFromString(str) { return { body: { innerHTML: str, childNodes: [], querySelectorAll: () => [] } }; } },
                  navigator: { onLine: false, clipboard: { writeText: async () => {} } },
                  history: { replaceState: () => {} },
                  localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
                  performance: { now: () => Date.now() },
                  requestAnimationFrame: () => 0, cancelAnimationFrame: () => {},
                  URLSearchParams, TextEncoder, TextDecoder, Uint8Array, ArrayBuffer, DataView,
                  atob: (b64) => Buffer.from(b64, "base64").toString("binary"),
                  btoa: (bin) => Buffer.from(bin, "binary").toString("base64"),
                  console, setTimeout: () => 0, clearTimeout: () => {}, setInterval: () => 0, clearInterval: () => {},
                  fetch: async () => ({ ok: false })
                };
                sandbox.globalThis = sandbox;
                sandbox.self = sandbox.window;
                vm.createContext(sandbox);
                vm.runInContext(js + "\\nglobalThis.__ingest = ingestSessionPayload; globalThis.__search = executeConfigSearch; globalThis.__lev = computeLevenshtein;", sandbox);
                sandbox.__ingest(editorData);
                if (sandbox.__lev("raduis", "radius") > 2) { console.error("levenshtein failed"); process.exit(1); }
                const checks = [
                  ["dinero", "price"], ["geld", "price"], ["rad", "radius"], ["border", "worldBorderOverride"],
                  ["maxSlope", "maxSlope"], ["slopeRadius", "slopeRadius"], ["maxHillAngle", "maxHillAngle"],
                  ["JumpAdjustor", "maxY"], ["TickBudget", "syncAllottedTime"], ["ClaimIntegrations", "rerollWorldGuard"],
                  ["ACTADDON-0001", "strictActions"], ["retrasoTeletransporte", "teleportDelay"]
                ];
                for (const [q, exp] of checks) {
                  const hits = sandbox.__search(q);
                  const ok = hits.some(h => h.key.toLowerCase().includes(exp.toLowerCase()) || h.file.toLowerCase().includes(exp.toLowerCase()));
                  if (!ok) { console.error("Search failed for " + q + " expected " + exp + " got " + JSON.stringify(hits.slice(0,3))); process.exit(2); }
                }
                console.log("ALL_12_SEARCH_QUERIES_PASSED");
                """;
        Process proc = new ProcessBuilder("node", "-e", nodeScript,
                docsEditor.toAbsolutePath().toString(),
                editorDataPath.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "Node search execution timed out");
        assertTrue(proc.exitValue() == 0 && output.contains("ALL_12_SEARCH_QUERIES_PASSED"),
                "Node V8 search execution failed: " + output);
    }
}
