package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-104: editor schema diagnostics source registered shapes / adjustors; map view keeps its scale")
class EditorSchemaDiagnosticsTest {

    @Test
    @DisplayName("Payload schema lists registered shapes and vertical adjustors with typed parameters")
    @SuppressWarnings("unchecked")
    void payloadSchemaListsRegistries() {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapes);
        }
        Factory<VerticalAdjustor<?>> verts = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        if (verts == null) {
            verts = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.vert, verts);
        }
        // Stand-in for an add-on / Chunky shape registered under its own name
        shapes.add("chunky_schema_test", new Square());
        boolean addedLinear = !verts.contains("LINEAR");
        if (addedLinear) verts.add("LINEAR", new LinearAdjustor(new ArrayList<>()));
        try {
            String schema = new EditorSessionManager().buildSchemaJson();
            assertTrue(schema.startsWith("{\"shape\":{"), schema);
            assertTrue(schema.contains("\"CHUNKY_SCHEMA_TEST\":{"), "add-on shape name without .YML suffix: " + schema);
            assertTrue(schema.matches("(?s).*\"CHUNKY_SCHEMA_TEST\":\\{.*?\"radius\":\\{\"type\":\"integer\".*"), "shape parameter typed: " + schema);
            assertTrue(schema.matches("(?s).*\"CHUNKY_SCHEMA_TEST\":\\{.*?\"mode\":\\{\"options\":\\[[^\\]]*\"ACCUMULATE\"[^\\]]*\\],\"type\":\"enum\".*"), "enum parameter lists options: " + schema);
            assertTrue(schema.matches("(?s).*\"vert\":\\{.*\"LINEAR\":\\{.*?\"minY\":\\{\"type\":\"integer\".*"), "adjustor parameter typed: " + schema);

            String payload = new EditorSessionManager().createPayloadJson(Map.of("config.yml", "teleportDelay: 2\n"));
            assertTrue(payload.contains("\"schema\":{\"shape\":{"), "payload carries schema");
        } finally {
            shapes.remove("chunky_schema_test");
            if (addedLinear) verts.remove("LINEAR");
        }
    }

    @Test
    @DisplayName("Parameter type follows the implementation's default value")
    void describeParamTypes() {
        assertTrue(EditorSessionManager.describeParam(true).get("type").equals("boolean"));
        assertTrue(EditorSessionManager.describeParam(256L).get("type").equals("integer"));
        assertTrue(EditorSessionManager.describeParam(1.0).get("type").equals("number"));
        assertTrue(EditorSessionManager.describeParam("auto").get("type").equals("string"));
        assertTrue(EditorSessionManager.describeParam(new ArrayList<>()).get("type").equals("list"));
        Map<String, Object> en = EditorSessionManager.describeParam(java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(en.get("type").equals("enum") && en.get("default").equals("SECONDS")
                && ((java.util.List<?>) en.get("options")).contains("MINUTES"), en.toString());
    }

    @Test
    @DisplayName("docs/editor/index.html wires schema diagnostics, jump links and a frozen view scale")
    void editorPageWiring() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        String html = Files.readString(page);

        // Registry names come from the session payload, not a hard-coded list
        assertTrue(html.contains("serverSchema = payload.schema"), "page must ingest payload.schema");
        assertTrue(html.contains("function diagnoseYaml(file, text)"), "path-aware schema diagnostics");
        assertTrue(html.contains("function collectSchemaIssues()"), "staged-file diagnostics for the diff summary");
        assertTrue(html.contains("schema error(s) in staged files"), "commit confirms schema errors");
        assertTrue(html.contains("cfgInputTimer = setTimeout("), "diagnostics debounced");
        // Jump links resolve lines through the YAML model, not text search
        assertTrue(html.contains("function findRegionYamlLine(text, vertexIndex)"), "line-precise Jump to YAML");
        assertTrue(html.contains("function regionNameOfFile(file)"), "View on Map limited to region files");
        // View scale is frozen between fits so vertex drags never rescale the map
        assertTrue(html.contains("function applyViewFit(cssW, cssH)"), "frozen view fit");
        assertTrue(html.contains("if (e.button === 1) e.preventDefault();"), "middle-click autoscroll suppressed");
        // Doc tracker resolves keys by their block (shape.name vs vert.name)
        assertTrue(html.contains("function docTrackerLookup(key, parentPath)"), "block-qualified doc lookup");
        // Canvas geometry is patched into the shape block (hand edits kept), typed by the registry
        assertTrue(html.contains("function patchRegionShape(text, region)"), "in-place shape block patch");
        assertTrue(html.contains("registryParam(blockOf.kind, blockOf.name, r.key)"), "server-typed parameter checks");
        assertTrue(!html.contains("radiusX: ${"), "no radiusX / radiusZ keys written");
    }
}
