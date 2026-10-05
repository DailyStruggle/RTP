package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static contract of the editor page's curve runner (ADR-106 sections 4.3-4.6). Runtime behaviour
 * (hash parity, form edits, escape / loop / fallback reasons) is exercised by the headless probe
 * {@code scripts/editor_curve_probe.py}.
 */
@DisplayName("ADR-106: editor page sandboxes curve helpers and builds the typed settings form")
class EditorCurvePageContractTest {

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        assertTrue(Files.exists(page), "docs/editor/index.html must exist at " + page.toAbsolutePath());
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    @DisplayName("Helpers run only in an opaque-origin sandbox with the ADR CSP, checked by SHA-256")
    void sandboxContract() throws IOException {
        String html = page();
        assertTrue(html.contains("f.setAttribute('sandbox', 'allow-scripts')"), "sandboxed iframe");
        assertFalse(html.contains("allow-same-origin"), "no allow-same-origin anywhere: the frame origin stays opaque");
        assertFalse(html.contains("allow-top-navigation") || html.contains("allow-popups"), "no navigation / popups");
        assertTrue(html.contains("\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' blob:; worker-src blob:\""),
                "runner CSP blocks network");
        assertTrue(html.contains("crypto.subtle.digest('SHA-256'"), "helper digest checked before load");
        assertTrue(html.contains("e.source !== frame.contentWindow"), "replies accepted only from the runner frame");
        assertTrue(html.contains("ingestCurveCode(payload.curveCode)"), "snapshot curveCode ingested");
        assertFalse(html.contains("new Function("), "no Function constructor on the page");

        // Indirect eval exists only in the runner sources copied into the sandbox document
        int start = html.indexOf("function curveEvaluatorFactory(post)");
        int end = html.indexOf("function curveRunnerDocument()");
        assertTrue(start > 0 && end > start, "runner functions present");
        Matcher m = Pattern.compile("\\beval\\s*\\)").matcher(html);
        int evals = 0;
        while (m.find()) {
            evals++;
            assertTrue(m.start() > start && m.start() < end, "eval outside the sandbox runner at offset " + m.start());
        }
        assertEquals(2, evals, "evaluator (helpers) and frame main (runner) only");

        // The runner document literal must not carry raw closing tags: exports splice at </head>
        assertEquals(1, count(html, "</head>"), "one real </head>");
        assertEquals(1, count(html, "</body>"), "one real </body>");
        assertTrue(html.stripTrailing().endsWith("</html>"), "nothing after </html>");
    }

    @Test
    @DisplayName("Settings form and region model use the schema's setting names, not fixed inputs")
    void settingsFormContract() throws IOException {
        String html = page();
        assertTrue(html.contains("function renderShapeSettingsForm(region = regionState)"), "generated settings form");
        assertTrue(html.contains("id=\"shape-settings-form\""), "form host after the shape dropdown");
        assertTrue(html.contains("const SETTING_KINDS = new Set(['distance', 'integer', 'number', 'boolean', 'enum'])"),
                "ADR-106 section 4.3 kinds");
        assertTrue(html.contains("id=\"hud-curve\""), "curve status badge");
        assertTrue(html.contains("function regionGeometryValues(region)"));
        assertTrue(html.contains("settings: { radius: '256c', centerRadius: '64c', centerX: '0', centerZ: '0' }"),
                "canonical setting names for new regions");
        for (String gone : new String[]{"GEOMETRY_KEYS", "acceptedGeometryKeys", "reg-radius", "reg-center-radius",
                "updateUnitHints", "radiusX", "radiusZ", "regionState.radius", "regionState.unit"}) {
            assertFalse(html.contains(gone), "legacy fixed-geometry reference left: " + gone);
        }
    }
}
