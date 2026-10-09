package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Web Editor Staging Diff and WebSocket Hot-Apply Tests (ADR-104 Phase 3)")
class WebEditorStagingDiffAndWebSocketTest {

    @Test
    @DisplayName("docs/editor/index.html contains rich YAML diff, ADR-034 geometry validation, and WebSocket client")
    void testDocsEditorHtmlContents() throws IOException {
        Path docsEditor = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(docsEditor)) {
            docsEditor = Paths.get("..", "docs", "editor", "index.html");
        }
        assertTrue(Files.exists(docsEditor), "docs/editor/index.html must exist at " + docsEditor.toAbsolutePath());

        String html = Files.readString(docsEditor);

        // Dark-contrast Catppuccin Mocha palette
        assertTrue(html.contains("--base: #1e1e2e") || html.contains("--dark: #11111b"), "Must use Catppuccin Mocha palette");
        assertTrue(html.contains("--text: #cdd6f4"), "Must use Catppuccin text token");
        assertTrue(html.contains("--accent: #89b4fa"), "Must use Catppuccin accent token");
        assertTrue(html.contains("--green: #a6e3a1"), "Must use Catppuccin green token");
        assertTrue(html.contains("--red: #f38ba8"), "Must use Catppuccin red token");

        // Client-side YAML diff generator
        assertTrue(html.contains("generateYamlDiff"), "Must have generateYamlDiff function");
        assertTrue(html.contains("diff-del"), "Must format deletions with diff-del class");
        assertTrue(html.contains("diff-add"), "Must format additions with diff-add class");
        assertTrue(html.contains("computeCurrentRegionYaml"), "Must convert region state to YAML for diffing");

        // ADR-034 polygon self-intersection and world-border violation checker
        assertTrue(html.contains("validateGeometry"), "Must have validateGeometry function");
        assertTrue(html.contains("segmentsIntersect"), "Must have segmentsIntersect function");
        assertTrue(html.contains("ADR-034"), "Must reference ADR-034 non-self-intersection");
        assertTrue(html.contains("WORLD_BORDER_MAX"), "Must check against world border boundary");
        assertTrue(html.contains("collinear"), "Must check collinear vertices");

        // Signed editor channel (ADR-106 §5) and hot-apply commit over it
        assertTrue(html.contains("initWebSocket"), "Must register the channel message handlers");
        assertTrue(html.contains("var EditorChannelClient"), "Must have the signed EditorChannelClient");
        assertTrue(html.contains("hotApplyWebSocket"), "Must have hotApplyWebSocket commit function");
        assertTrue(html.contains("EditorChannelClient.send('apply', body)"), "Hot-Apply must travel signed over the channel");
        assertTrue(html.contains("/rtp-editor-ws"), "Must accept the loopback /rtp-editor-ws relay address");
        assertFalse(html.contains("mutation_broadcast") || html.contains("config_update"),
                "Unsigned config-overwrite broadcasts must not be accepted (ADR-106 §5.2)");
        assertFalse(html.contains("new WebSocket(`"), "No socket outside the signed channel client");

        // Copy-to-clipboard fallback
        assertTrue(html.contains("copyApplyCmd"), "Must provide copyApplyCmd fallback");
        assertTrue(html.contains("/rtp editor apply token="), "Must formulate /rtp editor apply token= command");

        // Unambiguous sidebar display and subcategory pills (avoid duplicate default.yml under definitions)
        assertTrue(html.contains("getConfigDisplay"), "Must have getConfigDisplay function");
        assertTrue(html.contains("regions/default.yml"), "Must format definitions/regions/default.yml with relative subpath");
        assertTrue(html.contains("worlds/default.yml"), "Must format definitions/worlds/default.yml with relative subpath");

        // No external script/css CDN dependencies (pure zero-dependency vanilla JS/HTML5/CSS)
        assertFalse(html.contains("<script src=\"http"), "Must be pure zero-dependency vanilla JS");
        assertFalse(html.contains("<link rel=\"stylesheet\" href=\"http"), "Must be pure zero-dependency vanilla CSS");

        // ADR-104 Section 4.5: Intelligent Configuration Discovery & Hybrid Semantic Search Engine
        assertTrue(html.contains("search-modal-overlay"), "Must have omnibox/palette modal container");
        assertTrue(html.contains("CONFIG_SYNONYMS"), "Must look up the domain thesaurus dictionary");
        assertTrue(html.contains("computeLevenshtein"), "Must implement Levenshtein fuzzy distance matching");
        assertTrue(html.contains("executeConfigSearch"), "Must implement multi-dimensional config search engine");
        assertTrue(html.contains("triggerRemoteSynonymExpansion"), "Must implement progressive online term expansion");
        assertTrue(html.contains("/^[a-z][a-z\\-]{1,23}$/"), "Must enforce strict regex token whitelisting against code/SQL injection");

        // Multilingual thesaurus (Spanish, French, German, Polish): sent by the plugin, not bundled
        assertTrue(html.contains("const CONFIG_SYNONYMS = {};"), "index.html must not bundle the thesaurus");
        assertTrue(html.contains("payload.synonyms"), "index.html must take the thesaurus from the payload");
        assertFalse(html.contains("dinero:"), "index.html must not bundle synonym entries");
        String data = Files.readString(docsEditor.resolveSibling("editor-data.json"));
        for (String token : new String[] {"\"dinero\"", "\"argent\"", "\"geld\"", "\"pieniadze\"", "\"distancia\"", "\"abstand\""}) {
            assertTrue(data.contains(token), "editor-data.json synonyms must include " + token);
        }
        assertTrue(html.contains("Doc Match"), "Must support doc description matching");

        // MultiConfigParser directory breakdown and Add/Remove capabilities
        assertTrue(html.contains("Region Definitions"), "Must categorize into Region Definitions");
        assertTrue(html.contains("World Routing"), "Must categorize into World Routing");
        assertTrue(html.contains("Custom Effects"), "Must categorize into Custom Effects");
        assertTrue(html.contains("promptAddConfigFile"), "Must provide promptAddConfigFile");
        assertTrue(html.contains("removeConfigFile"), "Must provide removeConfigFile");
        assertTrue(html.contains("isConfigGuarded"), "Must enforce removal guards on default configs");
        assertTrue(html.contains("createConfigTemplate"), "Must generate boilerplate template for added configs");
        assertTrue(html.contains("cfg-btn-add"), "Must render + Add buttons for multi-config directories");
        assertTrue(html.contains("cfg-btn-delete"), "Must render delete buttons with removal guard handling");

        // Visual Region Editor Add & Remove Region Controls
        assertTrue(html.contains("Selected Region:"), "Must rename 'Selected Profile Preset:' to 'Selected Region:'");
        assertFalse(html.contains("Selected Profile Preset:"), "Must not contain legacy 'Selected Profile Preset:' label");
        assertTrue(html.contains("btn-remove-region"), "Must have button to remove regions");
        assertTrue(html.contains("__add_region__"), "Must have add region option in region select dropdown");
        assertTrue(html.contains("handleRegionSelectChange"), "Must have handleRegionSelectChange function");
        assertTrue(html.contains("promptAddNewRegion"), "Must have promptAddNewRegion function");
        assertTrue(html.contains("removeCurrentRegion"), "Must have removeCurrentRegion function");
        assertTrue(html.contains("syncRegionProfiles"), "Must have syncRegionProfiles function");

        // ADR-104 Section 4.1 & Shipped Documentation Catalog: complete documentation embedding and navigation
        assertTrue(html.contains("populateDocSidebar"), "Must provide populateDocSidebar for document navigation");
        assertTrue(html.contains("selectDoc"), "Must provide selectDoc for viewing manuals");
        assertTrue(html.contains("categorizeDoc"), "Must provide categorizeDoc for section grouping");
        assertTrue(html.contains("getDocDisplay"), "Must provide getDocDisplay for doc formatting");
        assertTrue(html.contains("filterDocs"), "Must provide filterDocs for doc search");
        assertTrue(html.contains("doc-filter-input"), "Must have doc filter input element");

        // Docs come from the session payload (the plugin's DocsRegistry); the page bundles none
        assertTrue(html.contains("payload.docs"), "Must ingest docs from the session payload");
        assertTrue(html.contains("const defaultDocs = {};"), "Must not bundle documentation");
        assertFalse(html.contains("\"admin/QUICK_START.md\""), "Must not embed admin/QUICK_START.md");

        // ADR-104 §4.3 & ADR-084: Chunk-resolution pregenerated land map & throttled streaming
        assertTrue(html.contains("chk-pregen"), "Must have chk-pregen checkbox layer control");
        assertTrue(html.contains("function ingestLandChunkRows"), "Pushed pregen chunk batches merge into location-keyed land tiles");
        assertTrue(html.contains("BIOME_COLORS"), "Must define BIOME_COLORS desaturated cartography palette");
        // ADR-104 §4.6: frame budget holds because only viewport tiles are drawn and cached
        assertTrue(html.contains("function drawWorldLand") && html.contains("function requestVisibleTiles"),
                "Land detail must load per visible tile");
        assertTrue(html.contains("function evictTiles") && html.contains("evictTiles(atlasCache, MAX_ATLASES)"),
                "Off-screen bin atlases must be evicted under a fixed cache bound");
        assertTrue(html.contains("ATLAS_PAINT_BUDGET_MS"), "Atlas painting must run under a per-frame budget");
    }

    @Test
    @DisplayName("Verify semantic translation and docTrackerDb description matching logic in search engine")
    void testSemanticTranslationAndDocDescriptionMatching() throws IOException {
        Path docsEditor = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(docsEditor)) {
            docsEditor = Paths.get("..", "docs", "editor", "index.html");
        }
        assertTrue(Files.exists(docsEditor));
        String html = Files.readString(docsEditor);

        // Verify that remote expansion synonyms check doc descriptions
        assertTrue(html.contains("d.includes(syn) || dt.includes(syn)"),
                "Search engine must match expanded semantic synonyms against doc descriptions");

        // Verify that remote expansion synonyms check config keys and thesaurus
        assertTrue(html.contains("CONFIG_SYNONYMS[w.toLowerCase()]"),
                "Search engine must expand remote words through domain thesaurus");

        // Verify multi-tier scoring weights
        assertTrue(html.contains("score += 120"), "Exact key match must have highest score tier (+120)");
        assertTrue(html.contains("score += 80"), "Prefix key match must have secondary score tier (+80)");
        assertTrue(html.contains("score += 65"), "Thesaurus synonym match must have strong score tier (+65)");
        assertTrue(html.contains("score += 25") || html.contains("score += 20"), "Doc description match must have doc score tier (+20/+25)");
    }

    @Test
    @DisplayName("docs/editor/editor-data.json (sent by the plugin) mirrors all default configuration files; the page bundles none")
    void testDocsEditorMirrorsDefaultConfiguration() throws IOException {
        Path docsEditor = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(docsEditor)) {
            docsEditor = Paths.get("..", "docs", "editor", "index.html");
        }
        assertTrue(Files.exists(docsEditor));
        String page = Files.readString(docsEditor);
        assertTrue(page.contains("const defaultConfigs = {};"), "index.html must not bundle configs");
        assertTrue(page.contains("payload.shipped"), "index.html must take shipped defaults from the payload");
        Path data = docsEditor.resolveSibling("editor-data.json");
        assertTrue(Files.exists(data), "docs/editor/editor-data.json must exist (scripts/generate_web_editor.py)");
        String html = Files.readString(data);
        assertTrue(html.contains("\"shipped\"") && html.contains("\"docTracker\""), "editor-data.json carries shipped + docTracker");

        Path resourcesDir = Paths.get("rtp-plugin", "src", "main", "resources");
        if (!Files.exists(resourcesDir)) {
            resourcesDir = Paths.get("..", "rtp-plugin", "src", "main", "resources");
        }
        assertTrue(Files.exists(resourcesDir), "resources directory must exist");

        Path finalResDir = resourcesDir;
        // Every authentic shipped configuration YAML file on disk must be present in editor defaultConfigs
        try (var stream = Files.walk(resourcesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("plugin.yml"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/lang/"))
                    .forEach(p -> {
                        String rel = finalResDir.relativize(p).toString().replace('\\', '/');
                        assertTrue(
                                html.contains("\"" + rel + "\""),
                                "docs/editor/editor-data.json must mirror default config file from disk: " + rel
                        );
                    });
        }

        // Verify definitions files specifically: must be differentiated and present
        assertTrue(html.contains("\"definitions/regions/default.yml\""));
        assertTrue(html.contains("\"definitions/worlds/default.yml\""));
        assertTrue(html.contains("\"definitions/effects/default.yml\""));
    }

    @Test
    @DisplayName("docs/editor/index.html reformats markdown docs into rich HTML and provides automatic official site page pulling")
    void testDocsEditorHtmlReformattingAndSitePulling() throws IOException {
        Path docsEditor = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(docsEditor)) {
            docsEditor = Paths.get("..", "docs", "editor", "index.html");
        }
        assertTrue(Files.exists(docsEditor));
        String html = Files.readString(docsEditor);

        // Markdown to HTML formatting features in editor
        assertTrue(html.contains("renderMarkdownToHtml"), "Must implement client-side renderMarkdownToHtml");
        assertTrue(html.contains("markdown-body"), "Must include .markdown-body CSS class for rich HTML rendering");
        assertTrue(html.contains("admonition"), "Must support Material-mirrored admonition callouts");
        assertTrue(html.contains("source-badge"), "Must display source badge indicating content origin");

        // Official site page pulling (automatic)
        assertTrue(html.contains("resolveSiteUrlForDoc"), "Must have resolveSiteUrlForDoc to map doc keys to site URLs");
        assertTrue(html.contains("fetchOfficialSitePage"), "Must have fetchOfficialSitePage to extract official article content");
        assertTrue(html.contains("OFFICIAL SITE"), "Must indicate official site origin in badge");
        assertTrue(html.contains("PACKED DOCS"), "Must support packed docs fallback");
        assertTrue(html.contains("md-content__inner") || html.contains("md-content"), "Must extract MkDocs Material content structure");

        // Region editor contextual doc drawer below the bar
        assertTrue(html.contains("region-doc-drawer"), "Must provide contextual doc drawer below region editor canvas");
        assertTrue(html.contains("toggleRegionDocDrawer"), "Must provide toggle control for region doc drawer");
        assertTrue(html.contains("renderRegionDocContent"), "Must render region doc content below the map bar");
    }

    @Test
    @DisplayName("EditorSessionManager exports local HTML containing staging diff, validation, and hot-apply features")
    void testEditorSessionManagerExportedBundle(@TempDir Path tempDir) throws IOException {
        EditorSessionManager manager = new EditorSessionManager();
        Path exportedFile = tempDir.resolve("editor_exported.html");

        Map<String, String> configs = Map.of(
                "config.yml", "teleport:\n  radius: 5000\n  shape:\n    name: CIRCLE\n",
                "definitions/regions/default.yml", "world: \"[0]\"\nshape:\n  name: CIRCLE\n  radius: 5000\n  centerRadius: 1000\n",
                "definitions/worlds/default.yml", "requirePermission: false\n",
                "regions/survival_spawn.yml", "world: world\nshape:\n  name: CIRCLE\n  radius: 5000\n  centerRadius: 1000\n"
        );

        manager.exportLocalEditorHtml(exportedFile, configs);
        assertTrue(Files.exists(exportedFile));

        String html = Files.readString(exportedFile);

        assertTrue(html.contains("generateYamlDiff"));
        assertTrue(html.contains("validateGeometry"));
        assertTrue(html.contains("segmentsIntersect"));
        assertTrue(html.contains("ADR-034"));
        assertTrue(html.contains("hotApplyWebSocket"));
        assertTrue(html.contains("copyApplyCmd"));
        assertTrue(html.contains("WORLD_BORDER_MAX"));
        assertTrue(html.contains("getConfigDisplay"));
        assertTrue(html.contains("regions/default.yml"));
        assertTrue(html.contains("worlds/default.yml"));
        assertTrue(html.contains("renderMarkdownToHtml"));
        assertTrue(html.contains("region-doc-drawer"));
        assertTrue(html.contains("toggleRegionDocDrawer"));
    }
}
