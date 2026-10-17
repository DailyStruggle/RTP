package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Region world selection on the editor page (ADR-104 §4.6): the World dropdown lists the worlds
 * with a detected {@code definitions/worlds/<name>.yml} (never {@code default.yml}), keeps an
 * index token such as {@code [0]} listed with the world the server resolved, and a choice rewrites
 * only the region file's top-level {@code world:} key. Page functions lifted from
 * docs/editor/index.html and run in GraalJS against a minimal DOM.
 */
@DisplayName("ADR-104: editor World dropdown lists detected world configs and writes the region's world key")
class EditorWorldSelectPageTest {

    private static final String[] PAGE_CONSTS = {"SCHEMA_DOC_TAGS"};
    private static final String[] PAGE_FUNCTIONS = {"isInheritedReferenceToken", "stripYamlValue", "parseYamlLines",
            "yamlKeyExtent", "yamlScalarAt", "yamlSetScalar", "regionFileFor", "detectedWorldNames", "isWorldToken",
            "readRegionWorld", "applyYamlWorld", "setRegionWorld", "syncWorldSelect"};

    private static final String REGION = """
            # nether hub
            world: "[0]"
            worldBorderOverride: false

            shape:
              name: "CIRCLE"
              radius: 256c
            """;

    /** DOM stub: one select whose options are recorded; computeCurrentRegionYaml is not reached. */
    private static final String PRELUDE = """
            var configs = {}, shippedConfigs = {}, currentConfigFile = '', cfgEditor = null, regionState = null;
            var worldSelect = { options: [], value: '', appendChild(o) { this.options.push(o); },
              set textContent(v) { this.options = []; } };
            var document = { getElementById: (id) => id === 'reg-world-select' ? worldSelect : null,
              createElement: () => ({ value: '', textContent: '' }) };
            function computeCurrentRegionYaml(r) { throw new Error('region file exists'); }
            """;

    private static final String DRIVER = """
            (function (regionText, choice) {
              configs = {
                'config.yml': 'version: 1',
                'definitions/worlds/default.yml': 'region: default',
                'definitions/worlds/world_the_end.yml': 'region: default',
                'definitions/worlds/world.yml': 'region: default',
                'definitions/worlds/world_nether.yml': 'region: default',
                'definitions/regions/nether.yml': regionText
              };
              regionState = { name: 'nether', shape: 'CIRCLE', settings: {}, vertices: [], world: 'world' };
              applyYamlWorld(regionState, regionText);
              syncWorldSelect();
              const beforeOpts = worldSelect.options.map(o => [o.value, o.textContent]);
              const beforeValue = worldSelect.value;
              if (choice) setRegionWorld(regionState, choice);
              syncWorldSelect();
              return JSON.stringify({ names: detectedWorldNames(), beforeOpts, beforeValue,
                afterOpts: worldSelect.options.map(o => o.value), afterValue: worldSelect.value,
                world: regionState.world, text: configs['definitions/regions/nether.yml'] });
            })""";

    private static Context context;
    private static Value driver;

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

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
    static void startEngine() throws IOException {
        String html = page();
        StringBuilder src = new StringBuilder(PRELUDE);
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

    private static JsonObject run(String text, String choice) {
        return JsonParser.parseString(driver.execute(text, choice).asString()).getAsJsonObject();
    }

    @Test
    @DisplayName("the page has a World dropdown wired to updateRegionWorld")
    void pageHasWorldSelect() throws IOException {
        String html = page();
        assertTrue(html.contains("id=\"reg-world-select\""), "World select missing");
        assertTrue(html.contains("onchange=\"updateRegionWorld(this.value)\""), "World select not wired");
    }

    @Test
    @DisplayName("options are the detected world config files, default.yml excluded, token kept with its resolved world")
    void optionsFromWorldConfigs() {
        JsonObject r = run(REGION, null);
        assertEquals("[\"world\",\"world_nether\",\"world_the_end\"]", r.get("names").toString());
        assertEquals("[[\"[0]\",\"[0] (world)\"],[\"world\",\"world\"],[\"world_nether\",\"world_nether\"],"
                + "[\"world_the_end\",\"world_the_end\"]]", r.get("beforeOpts").toString());
        assertEquals("[0]", r.get("beforeValue").getAsString());
        assertEquals("world", r.get("world").getAsString(), "a token keeps the server-resolved world");
    }

    @Test
    @DisplayName("choosing the nether rewrites only the world key and moves the region model")
    void choosingWorldWritesKey() {
        JsonObject r = run(REGION, "world_nether");
        String text = r.get("text").getAsString();
        assertEquals("world_nether", r.get("world").getAsString());
        assertEquals("world_nether", r.get("afterValue").getAsString());
        assertEquals("[\"world\",\"world_nether\",\"world_the_end\"]", r.get("afterOpts").toString(),
                "a named, detected world needs no extra option");
        assertTrue(text.contains("\nworld: \"world_nether\"\n"), text);
        assertEquals(REGION.replace("world: \"[0]\"", "world: \"world_nether\""), text, "every other line kept");
    }

    @Test
    @DisplayName("a region file naming a world selects it; an undetected world stays listed")
    void namedWorldFromFile() {
        JsonObject named = run(REGION.replace("\"[0]\"", "world_the_end"), null);
        assertEquals("world_the_end", named.get("world").getAsString());
        assertEquals("world_the_end", named.get("beforeValue").getAsString());

        JsonObject missing = run(REGION.replace("\"[0]\"", "skyblock"), null);
        assertEquals("skyblock", missing.get("beforeValue").getAsString());
        assertEquals("[\"skyblock\",\"skyblock (no world config detected)\"]",
                missing.getAsJsonArray("beforeOpts").get(0).toString());
    }

    @Test
    @DisplayName("a region file without a world key gets one")
    void missingWorldKeyInserted() {
        JsonObject r = run("shape:\n  name: \"CIRCLE\"\n", "world_nether");
        String text = r.get("text").getAsString();
        assertTrue(text.contains("world: \"world_nether\""), text);
        assertTrue(text.startsWith("shape:\n  name: \"CIRCLE\"\n"), text);
    }
}
