package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * In-memory registry and cache of lowered Markdown documentation models (ADR-045, ADR-104).
 *
 * <p>Populated asynchronously on startup and reload via {@link #rebuild(Path, DocsLoweringOptions)}.
 * Queries are constant-time in-memory lookups.
 * Also supports exporting a standalone self-contained single-file HTML bundle for local offline browsing.
 */
public final class DocsRegistry {

    private static final DocsRegistry INSTANCE = new DocsRegistry();

    private final AtomicReference<Map<String, MenuModel>> cache =
            new AtomicReference<>(Collections.emptyMap());

    private final AtomicReference<Map<String, String>> rawSourceCache =
            new AtomicReference<>(Collections.emptyMap());

    private volatile boolean initialized = false;

    public DocsRegistry() {
    }

    public static DocsRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Schedules a rebuild of the shared registry from {@code <dataFolder>/docs} on
     * {@link RTP#scheduler} (file I/O only, never the main thread). Platforms call this after
     * their synchronous {@code extractDocs}; {@code /rtp reload} calls it again. Never throws:
     * every failure is logged at WARNING (S-004).
     *
     * @param dataFolder plugin / mod data folder holding the extracted {@code docs/} tree
     */
    public static void rebuildFromDataFolder(File dataFolder) {
        if (dataFolder == null) {
            RTP.log(Level.WARNING, "[docs] no data folder; shipped docs not indexed");
            return;
        }
        Path docsRoot = new File(dataFolder, "docs").toPath();
        if (RTP.scheduler == null) {
            RTP.log(Level.WARNING, "[docs] scheduler not ready; shipped docs at " + docsRoot + " not indexed");
            return;
        }
        RTP.scheduler.runTaskAsynchronously(() -> INSTANCE.rebuildOrWarn(docsRoot, DocsLoweringOptions.defaults()));
    }

    /**
     * {@link #rebuild} that logs instead of throwing. A missing folder still initializes an
     * empty registry (synthetic index only) so readers get an empty set, not "not ready".
     *
     * @return {@code true} if the rebuild completed
     */
    boolean rebuildOrWarn(Path docsRoot, DocsLoweringOptions options) {
        if (!Files.isDirectory(docsRoot)) {
            RTP.log(Level.WARNING, "[docs] docs folder missing at " + docsRoot
                    + " (extraction failed or skipped); /rtp docs and the editor carry no shipped docs");
        }
        try {
            rebuild(docsRoot, options);
            RTP.log(Level.FINE, "[docs] indexed " + rawSourceCache.get().size() + " docs from " + docsRoot);
            return true;
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.WARNING, "[docs] failed to index shipped docs at " + docsRoot + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retrieves the cached {@link MenuModel} for {@code relpath}.
     *
     * @param relpath normalized relative documentation path
     * @return the lowered menu model, or {@code null} if not found
     * @throws IllegalStateException if queried before the initial rebuild completed
     */
    public MenuModel get(String relpath) {
        if (!initialized) {
            throw new IllegalStateException("DocsRegistry has not completed initial rebuild");
        }
        Objects.requireNonNull(relpath, "relpath");
        String normalized = normalizeRelpath(relpath);
        return cache.get().get(normalized);
    }

    /**
     * Returns an unmodifiable view of all cached relpaths and models.
     */
    public Map<String, MenuModel> getAll() {
        if (!initialized) {
            throw new IllegalStateException("DocsRegistry has not completed initial rebuild");
        }
        return Collections.unmodifiableMap(cache.get());
    }

    /**
     * Returns an unmodifiable view of all cached raw Markdown sources.
     */
    public Map<String, String> getAllRawSources() {
        if (!initialized) {
            throw new IllegalStateException("DocsRegistry has not completed initial rebuild");
        }
        return Collections.unmodifiableMap(rawSourceCache.get());
    }

    /**
     * Asynchronously rebuilds the registry by scanning {@code docsRoot}.
     *
     * @param docsRoot directory containing Markdown documentation files
     * @param options  lowering options
     * @throws IOException on directory walk failure
     */
    public void rebuild(Path docsRoot, DocsLoweringOptions options) throws IOException {
        Objects.requireNonNull(docsRoot, "docsRoot");
        Objects.requireNonNull(options, "options");

        Map<String, MenuModel> nextModels = new ConcurrentHashMap<>();
        Map<String, String> nextRawSources = new ConcurrentHashMap<>();

        if (Files.isDirectory(docsRoot)) {
            Files.walkFileTree(docsRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (!fileName.endsWith(".md")) {
                        return FileVisitResult.CONTINUE;
                    }

                    Path rel = docsRoot.relativize(file);
                    String relpath = normalizeRelpath(rel.toString());

                    // Filter developer docs if disabled
                    if (!options.exposeDeveloperDocs()) {
                        if (relpath.startsWith("dev/") || relpath.startsWith("adr/") || relpath.startsWith("architecture/")) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    if (attrs.size() > options.maxFileBytes()) {
                        nextModels.put(relpath, new MenuModel(
                                fileName,
                                List.of(new MenuPage(List.of(
                                        MenuLine.of(new MenuFragment("&c[Documentation file too large: " + attrs.size() + " bytes]", null, null))
                                )))
                        ));
                        return FileVisitResult.CONTINUE;
                    }

                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    nextRawSources.put(relpath, raw);
                    MenuModel model = MarkdownToMenuModel.lower(fileName, raw, options);
                    nextModels.put(relpath, model);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Build root index if missing
        if (!nextModels.containsKey("index")) {
            nextModels.put("index", buildSyntheticIndex(nextModels));
        }

        this.cache.set(Map.copyOf(nextModels));
        this.rawSourceCache.set(Map.copyOf(nextRawSources));
        this.initialized = true;
    }

    /**
     * Exports a completely self-contained, single-file HTML bundle to {@code targetFile} (ADR-104).
     *
     * @param targetFile output HTML path
     * @throws IOException on file write error
     */
    public void exportLocalHtmlBundle(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile");
        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("<title>RTP - Operator & Developer Documentation (Offline Portable)</title>\n")
                .append("<style>\n")
                .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; margin: 0; display: flex; height: 100vh; background: #1e1e2e; color: #cdd6f4; }\n")
                .append("#sidebar { width: 320px; background: #181825; border-right: 1px solid #313244; overflow-y: auto; padding: 16px; box-sizing: border-box; }\n")
                .append("#sidebar h2 { font-size: 1.1rem; color: #89b4fa; margin-top: 0; padding-bottom: 8px; border-bottom: 1px solid #313244; }\n")
                .append(".doc-link { display: block; padding: 6px 10px; margin: 4px 0; border-radius: 4px; color: #a6adc8; text-decoration: none; font-size: 0.9rem; cursor: pointer; }\n")
                .append(".doc-link:hover, .doc-link.active { background: #313244; color: #cdd6f4; }\n")
                .append("#content { flex: 1; overflow-y: auto; padding: 32px 48px; box-sizing: border-box; background: #1e1e2e; }\n")
                .append("pre { background: #11111b; padding: 16px; border-radius: 8px; overflow-x: auto; font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; border: 1px solid #313244; }\n")
                .append("code { font-family: 'JetBrains Mono', monospace; background: #313244; padding: 2px 6px; border-radius: 4px; font-size: 0.85em; }\n")
                .append("h1, h2, h3 { color: #89b4fa; }\n")
                .append("hr { border: 0; border-top: 1px solid #313244; margin: 24px 0; }\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<div id=\"sidebar\">\n<h2>📖 RTP Shipped Docs</h2>\n<div id=\"links\"></div>\n</div>\n")
                .append("<div id=\"content\">\n<div id=\"viewer\"><h1>Select a document from the sidebar</h1></div>\n</div>\n")
                .append("<script>\n")
                .append("const docs = ").append(buildJsonDocsPayload()).append(";\n")
                .append("const linksDiv = document.getElementById('links');\n")
                .append("const viewer = document.getElementById('viewer');\n")
                .append("for (const path of Object.keys(docs).sort()) {\n")
                .append("  const a = document.createElement('a');\n")
                .append("  a.className = 'doc-link';\n")
                .append("  a.textContent = path;\n")
                .append("  a.onclick = () => {\n")
                .append("    document.querySelectorAll('.doc-link').forEach(el => el.classList.remove('active'));\n")
                .append("    a.classList.add('active');\n")
                .append("    viewer.innerHTML = '<pre style=\"white-space: pre-wrap;\">' + escapeHtml(docs[path]) + '</pre>';\n")
                .append("  };\n")
                .append("  linksDiv.appendChild(a);\n")
                .append("}\n")
                .append("function escapeHtml(text) {\n")
                .append("  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');\n")
                .append("}\n")
                .append("if (Object.keys(docs).length > 0) {\n")
                .append("  linksDiv.firstChild.click();\n")
                .append("}\n")
                .append("</script>\n</body>\n</html>\n");

        Files.writeString(targetFile, html.toString(), StandardCharsets.UTF_8);
    }

    private String buildJsonDocsPayload() {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : rawSourceCache.get().entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static MenuModel buildSyntheticIndex(Map<String, MenuModel> models) {
        List<MenuLine> lines = new ArrayList<>();
        lines.add(MenuLine.of(new MenuFragment("&1&lRTP Documentation Index", null, null)));
        lines.add(MenuLine.of(new MenuFragment("&7Click any document below to open:", null, null)));
        lines.add(new MenuLine(List.of()));

        List<String> sorted = new ArrayList<>(models.keySet());
        Collections.sort(sorted);

        for (String key : sorted) {
            if ("index".equals(key)) continue;
            lines.add(new MenuLine(List.of(
                    new MenuFragment("&0\u2022 ", null, null),
                    new MenuFragment("&1" + key, "Open " + key, new MenuAction.RunRtpCommand(new String[]{"docs", key}))
            )));
        }

        return new MenuModel("Documentation Index", List.of(new MenuPage(List.copyOf(lines))));
    }

    private static String normalizeRelpath(String relpath) {
        String normalized = relpath.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
