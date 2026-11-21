package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.yaml.YamlConfig;
import io.github.dailystruggle.yaml.YamlSection;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automated Plane 3 official configuration documentation harvester ({@code leafconfig-ADR-001}
 * Section 1.3) that retrieves and caches preexisting upstream documentation for both server engine
 * files and arbitrary third-party plugin JARs off the main tick thread ({@code S-005}).
 *
 * <p>Supported upstream documentation planes:
 * <ul>
 *   <li><b>Server Engine Specs ({@code PaperMC/docs}):</b> Structured upstream YAML reference
 *       files ({@code server-properties.yml}, {@code bukkit.yml}, {@code spigot.yml},
 *       {@code paper-global.yml}, {@code paper-world-defaults.yml}, {@code commands.yml},
 *       {@code help.yml}, {@code permissions.yml}) carrying official per-key {@code default} and
 *       {@code description} entries.</li>
 *   <li><b>Third-Party Plugin JARs ({@code Modrinth SHA-512} &amp; {@code GitHub Wiki/README}):</b>
 *       Resolves plugin JAR {@code SHA-512} hashes via {@code api.modrinth.com/v2/version_file/<sha512>}
 *       (and {@code plugin.yml} {@code website} URLs) to official project Markdown, GitHub Wiki
 *       configuration pages, and repository default configuration files, matching prose and fenced
 *       comment blocks to dotted configuration keys via Lowest-Common-Ancestor (LCA) suffix
 *       disambiguation.</li>
 * </ul>
 *
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @governedBy leafconfig-ADR-001
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-CFG-ATLAS-002
 * @trace S-004
 * @trace S-005
 */
public final class OfficialConfigDocHarvester {

    private static final Logger LOG = Logger.getLogger(OfficialConfigDocHarvester.class.getName());

    private static final String DEFAULT_PAPER_DOCS_BASE =
            "https://raw.githubusercontent.com/PaperMC/docs/main/src/config/paper/";
    private static final String DEFAULT_MODRINTH_API_BASE =
            "https://api.modrinth.com/v2";
    private static final int DEFAULT_TIMEOUT_MS = 6_000;
    private static final int MAX_HTTP_BYTES = 1_048_576;

    private static final Pattern MARKDOWN_FENCE_RE = Pattern.compile(
            "(?is)```(ya?ml|properties|toml|conf|jsonc?|hocon)?[ \\t]*\\r?\\n(.*?)(?:```|\\z)"
    );
    private static final Pattern MD_JAVADOC_LINK_RE = Pattern.compile(
            "\\[([^\\]]*+)\\]\\(jd:java:([^)\\s]++)\\)"
    );
    private static final Pattern MD_RELATIVE_LINK_RE = Pattern.compile(
            "\\[([^\\]]++)\\]\\((/[^)\\s]++)\\)"
    );
    private static final Pattern MD_INLINE_LINK_RE = Pattern.compile(
            "\\[([^\\]]++)\\]\\((https?://[^)\\s]++)\\)"
    );
    private static final Pattern MD_BULLET_KEY_RE = Pattern.compile(
            "^\\s*+[-*+]\\s*+`([A-Za-z0-9_.-]{2,100})`\\s*+(?:[:\\-—]+|\\([^)]{1,40}\\)\\s*+[:\\-—]*)\\s*+(.{12,600})$"
    );
    private static final Pattern MD_HEADING_KEY_RE = Pattern.compile(
            "^#{2,5}\\s*+`?([A-Za-z0-9_.-]{2,90})`?\\s*+$"
    );
    private static final Pattern MD_TABLE_ROW_RE = Pattern.compile(
            "^\\|\\s*+`?([A-Za-z0-9_.-]{2,90})`?\\s*+\\|(.++)\\|\\s*+$"
    );
    private static final Pattern GITHUB_REPO_RE = Pattern.compile(
            "https?://github\\.com/([A-Za-z0-9_.-]++)/([A-Za-z0-9_.-]++)(?:/([^\\s\"'#?]*+))?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern JSON_STR_FIELD_RE = Pattern.compile(
            "\"([A-Za-z0-9_]++)\"\\s*+:\\s*+\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\""
    );
    private static final Pattern CACHE_SCOPE_BLOCK_RE = Pattern.compile(
            "(?s)\\{\\s*+\"scope\"\\s*+:\\s*+\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"\\s*+,\\s*+\"docs\"\\s*+:\\s*+\\{(.*?)\\}\\s*+\\}"
    );
    private static final Pattern CACHE_DOC_ENTRY_RE = Pattern.compile(
            "\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"\\s*+:\\s*+\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\""
    );

    /** Pluggable off-thread HTTP GET fetcher for deterministic offline testing. */
    @FunctionalInterface
    public interface HttpFetcher {
        String getText(String url, int timeoutMs) throws IOException;
    }

    /**
     * Resolved official documentation record for a single configuration key.
     *
     * @param keyPath       matched dotted configuration key path
     * @param description   clean plain-language upstream prose description
     * @param upstreamDefault optional upstream documented default literal
     * @param sourceUrl     originating upstream documentation URL
     */
    public record OfficialKeyDoc(
            String keyPath,
            String description,
            String upstreamDefault,
            String sourceUrl
    ) {
        public OfficialKeyDoc {
            keyPath = keyPath == null ? "" : keyPath.trim();
            description = description == null ? "" : description.trim();
            upstreamDefault = upstreamDefault == null ? "" : upstreamDefault.trim();
            sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        }
    }

    private final HttpFetcher fetcher;
    private final int timeoutMs;
    private final Map<String, Map<String, String>> docsByScopeCache = new ConcurrentHashMap<>();
    private volatile String lastDiagnostic = "";

    public OfficialConfigDocHarvester() {
        this(OfficialConfigDocHarvester::defaultHttpGet, DEFAULT_TIMEOUT_MS);
    }

    public OfficialConfigDocHarvester(HttpFetcher fetcher, int timeoutMs) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.timeoutMs = Math.max(500, timeoutMs);
    }

    public String lastDiagnostic() {
        return lastDiagnostic;
    }

    public int cachedScopeCount() {
        return docsByScopeCache.size();
    }

    /**
     * Harvests upstream official documentation across all engine and plugin artifacts registered in
     * {@code atlas} and upgrades matching undocumented {@link ServerConfigAtlas.AstConfigNode}s to
     * {@link SchemaInferrer.DocProvenance#OFFICIAL_DOCS}.
     *
     * @param atlas target configuration atlas
     * @return number of configuration nodes enriched with upstream official documentation
     */
    public int enrichAtlas(ServerConfigAtlas atlas) {
        Objects.requireNonNull(atlas, "atlas");
        int upgraded = 0;

        // 1. Engine files via PaperMC/docs structured YAML specs
        Set<String> engineFiles = new LinkedHashSet<>();
        for (ServerConfigAtlas.AstConfigNode n : atlas.nodesBySymbol().values()) {
            if (isEngineConfigNode(n)) {
                engineFiles.add(n.filePath());
            }
        }
        Map<String, Map<String, OfficialKeyDoc>> engineSpecsByFile = new LinkedHashMap<>();
        for (String filePath : engineFiles) {
            String specName = mapEngineFileToPaperSpecName(filePath);
            if (specName != null) {
                engineSpecsByFile.put(filePath, resolvePaperEngineSpec(specName));
            }
        }

        // 2. Third-party plugin JARs with undocumented keys via Modrinth SHA-512 / GitHub Wiki & README
        Map<String, Map<String, OfficialKeyDoc>> pluginDocsByArtifact = new LinkedHashMap<>();
        for (JarConfigScanner.HarvestedArtifact art : atlas.artifacts().values()) {
            if (art.artifactId().startsWith("engine:")) {
                continue;
            }
            if (!hasUndocumentedNodesForArtifact(atlas, art.artifactId())) {
                continue;
            }
            Set<String> targetKeys = collectKeysForArtifact(atlas, art.artifactId());
            Map<String, OfficialKeyDoc> resolved = resolveThirdPartyArtifactDocs(art, targetKeys);
            if (!resolved.isEmpty()) {
                pluginDocsByArtifact.put(art.artifactId(), resolved);
            }
        }

        // 3. Apply matched upstream prose onto AstConfigNodes
        List<String> symbols = new ArrayList<>(atlas.nodesBySymbol().keySet());
        for (String sym : symbols) {
            ServerConfigAtlas.AstConfigNode node = atlas.nodesBySymbol().get(sym);
            if (node == null) {
                continue;
            }
            if (node.schema().provenance() == SchemaInferrer.DocProvenance.AUTHOR_COMMENT
                    || node.commentRestored()
                    || (node.commentText() != null && !node.commentText().isBlank()
                    && node.schema().provenance() == SchemaInferrer.DocProvenance.OFFICIAL_DOCS)) {
                continue;
            }
            OfficialKeyDoc match = null;
            if (isEngineConfigNode(node)) {
                Map<String, OfficialKeyDoc> fileDocs = engineSpecsByFile.get(node.filePath());
                if (fileDocs != null && !fileDocs.isEmpty()) {
                    match = matchKeyDoc(fileDocs, node.keyPath());
                }
            } else {
                Map<String, OfficialKeyDoc> artDocs = pluginDocsByArtifact.get(node.artifactId());
                if (artDocs != null && !artDocs.isEmpty()) {
                    match = matchKeyDoc(artDocs, node.keyPath());
                }
            }
            if (match != null && !match.description().isBlank()) {
                if (atlas.applyOfficialKeyDoc(sym, match)) {
                    upgraded++;
                }
            }
        }
        return upgraded;
    }

    /**
     * Hydrates {@code atlas} strictly from already-cached scopes in memory (zero network I/O).
     */
    public int hydrateCachedAtlasDocs(ServerConfigAtlas atlas) {
        Objects.requireNonNull(atlas, "atlas");
        if (docsByScopeCache.isEmpty()) {
            return 0;
        }
        int upgraded = 0;
        List<String> symbols = new ArrayList<>(atlas.nodesBySymbol().keySet());
        for (String sym : symbols) {
            ServerConfigAtlas.AstConfigNode node = atlas.nodesBySymbol().get(sym);
            if (node == null) {
                continue;
            }
            if (node.schema().provenance() == SchemaInferrer.DocProvenance.AUTHOR_COMMENT
                    || node.commentRestored()) {
                continue;
            }
            Map<String, String> rawMap = null;
            String srcUrl = "";
            if (isEngineConfigNode(node)) {
                String specName = mapEngineFileToPaperSpecName(node.filePath());
                if (specName != null) {
                    rawMap = docsByScopeCache.get("papermc:" + specName);
                    srcUrl = DEFAULT_PAPER_DOCS_BASE + specName;
                }
            } else {
                JarConfigScanner.HarvestedArtifact art = atlas.artifacts().get(node.artifactId());
                String scopeKey = buildArtifactScopeKey(node.artifactId(), art != null ? art.jarSha512() : "");
                rawMap = docsByScopeCache.get(scopeKey);
                srcUrl = art != null ? art.docSourceUrl() : "";
            }
            if (rawMap == null || rawMap.isEmpty()) {
                continue;
            }
            Map<String, OfficialKeyDoc> typed = toOfficialDocMap(rawMap, srcUrl);
            OfficialKeyDoc match = matchKeyDoc(typed, node.keyPath());
            if (match != null && !match.description().isBlank()) {
                if (atlas.applyOfficialKeyDoc(sym, match)) {
                    upgraded++;
                }
            }
        }
        return upgraded;
    }

    static boolean isEngineConfigNode(ServerConfigAtlas.AstConfigNode node) {
        return node != null && node.artifactId() != null && node.artifactId().startsWith("engine:");
    }

    static String mapEngineFileToPaperSpecName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        String f = filePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (f.equals("server.properties") || f.endsWith("/server.properties")) {
            return "server-properties.yml";
        }
        if (f.equals("bukkit.yml")) {
            return "bukkit.yml";
        }
        if (f.equals("spigot.yml") || f.endsWith("/spigot.yml")) {
            return "spigot.yml";
        }
        if (f.equals("commands.yml")) {
            return "commands.yml";
        }
        if (f.equals("help.yml")) {
            return "help.yml";
        }
        if (f.equals("permissions.yml")) {
            return "permissions.yml";
        }
        if (f.equals("config/paper-global.yml") || f.endsWith("/paper-global.yml")) {
            return "paper-global.yml";
        }
        if (f.equals("config/paper-world-defaults.yml")
                || f.endsWith("/paper-world-defaults.yml")
                || f.endsWith("/paper-world.yml")) {
            return "paper-world-defaults.yml";
        }
        return null;
    }

    Map<String, OfficialKeyDoc> resolvePaperEngineSpec(String specFileName) {
        String scopeKey = "papermc:" + specFileName;
        String url = DEFAULT_PAPER_DOCS_BASE + specFileName;
        Map<String, String> cached = docsByScopeCache.get(scopeKey);
        if (cached != null) {
            return toOfficialDocMap(cached, url);
        }
        try {
            String rawYaml = fetcher.getText(url, timeoutMs);
            if (rawYaml == null || rawYaml.isBlank()) {
                return Map.of();
            }
            Map<String, OfficialKeyDoc> parsed = parsePaperDocsYamlSpec(rawYaml, url);
            if (!parsed.isEmpty()) {
                Map<String, String> serializable = new LinkedHashMap<>();
                for (Map.Entry<String, OfficialKeyDoc> e : parsed.entrySet()) {
                    serializable.put(e.getKey(), e.getValue().description());
                }
                docsByScopeCache.put(scopeKey, Collections.unmodifiableMap(serializable));
            }
            return parsed;
        } catch (IOException ex) {
            this.lastDiagnostic = "Upstream PaperMC docs fetch failed for " + specFileName + ": " + ex.getMessage();
            LOG.log(Level.FINE, "[LeafConfig] " + this.lastDiagnostic, ex);
            return Map.of();
        }
    }

    /**
     * Parses a structured {@code PaperMC/docs/src/config/paper/*.yml} document into dotted key
     * descriptions, normalizing relative Paper doc links and inheriting section header notes where helpful.
     */
    public static Map<String, OfficialKeyDoc> parsePaperDocsYamlSpec(String rawYaml, String sourceUrl) {
        if (rawYaml == null || rawYaml.isBlank()) {
            return Map.of();
        }
        YamlConfig yml;
        try {
            yml = YamlConfig.parse(foldMultilineQuotedYamlScalars(rawYaml));
        } catch (RuntimeException ex) {
            return Map.of();
        }
        Map<String, OfficialKeyDoc> out = new LinkedHashMap<>();
        String rootHeader = "";
        if (yml.isConfigurationSection("header")) {
            YamlSection hdr = yml.getConfigurationSection("header");
            if (hdr != null) {
                rootHeader = cleanUpstreamProse(hdr.getString("message", ""));
            }
        }
        for (String topKey : yml.getKeys(false)) {
            if ("header".equals(topKey) || "__meta".equals(topKey) || !yml.isConfigurationSection(topKey)) {
                continue;
            }
            YamlSection child = yml.getConfigurationSection(topKey);
            if (child == null) {
                continue;
            }
            String cleanTop = normalizePaperSpecSegment(topKey);
            extractSectionDocAndWalk(child, cleanTop, rootHeader, sourceUrl, out);
        }
        return out;
    }

    private static void extractSectionDocAndWalk(
            YamlSection section,
            String fullPath,
            String inheritedHeader,
            String sourceUrl,
            Map<String, OfficialKeyDoc> out
    ) {
        Object descObj = section.contains("description") ? section.get("description") : null;
        if (descObj instanceof String descRaw && !descRaw.isBlank() && !section.isConfigurationSection("description")) {
            String cleanedDesc = cleanUpstreamProse(descRaw);
            if (!inheritedHeader.isEmpty() && cleanedDesc.length() < 45 && !cleanedDesc.contains(inheritedHeader)) {
                cleanedDesc = cleanedDesc + " (" + inheritedHeader + ")";
            }
            String defStr = section.getString("default", "");
            if (!fullPath.isEmpty() && !cleanedDesc.isEmpty()) {
                out.put(fullPath, new OfficialKeyDoc(fullPath, cleanedDesc, defStr, sourceUrl));
            }
        }
        walkPaperSpecSection(section, fullPath, inheritedHeader, sourceUrl, out);
    }

    private static void walkPaperSpecSection(
            YamlSection section,
            String prefix,
            String inheritedHeader,
            String sourceUrl,
            Map<String, OfficialKeyDoc> out
    ) {
        if (section == null) {
            return;
        }
        String localHeader = inheritedHeader;
        if (section.isConfigurationSection("header")) {
            YamlSection hdr = section.getConfigurationSection("header");
            if (hdr != null) {
                String msg = hdr.getString("message", "");
                if (msg != null && !msg.isBlank()) {
                    localHeader = cleanUpstreamProse(msg);
                }
            }
        }
        for (String key : section.getKeys(false)) {
            if ("header".equals(key) || "__meta".equals(key) || !section.isConfigurationSection(key)) {
                continue;
            }
            YamlSection child = section.getConfigurationSection(key);
            if (child == null) {
                continue;
            }
            String cleanSegment = normalizePaperSpecSegment(key);
            String fullPath = prefix.isEmpty()
                    ? cleanSegment
                    : (cleanSegment.isEmpty() ? prefix : prefix + "." + cleanSegment);
            extractSectionDocAndWalk(child, fullPath, localHeader, sourceUrl, out);
        }
    }

    private static String normalizePaperSpecSegment(String segment) {
        if (segment == null) {
            return "";
        }
        String s = segment.trim();
        if (s.startsWith("<") && s.endsWith(">")) {
            return "default";
        }
        return s;
    }

    static String cleanUpstreamProse(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("''", "'")
                .replaceAll("\\r?\\n", " ")
                .replaceAll(":::[a-zA-Z]+(?:\\[[^\\]]*\\])?", "")
                .replace(":::", "")
                .replaceAll("\\s+", " ")
                .trim();
        text = MD_JAVADOC_LINK_RE.matcher(text).replaceAll("`$2`");
        text = MD_RELATIVE_LINK_RE.matcher(text).replaceAll("$1 (https://docs.papermc.io$2)");
        text = MD_INLINE_LINK_RE.matcher(text).replaceAll("$1 ($2)");
        text = SchemaInferrer.sanitizeBlanketProse(text);
        return text;
    }

    static String foldMultilineQuotedYamlScalars(String rawYaml) {
        String[] lines = rawYaml.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(rawYaml.length());
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            char openQuote = detectUnclosedYamlQuote(line);
            if (openQuote == 0) {
                out.append(line).append('\n');
                i++;
                continue;
            }
            StringBuilder folded = new StringBuilder(line.trim());
            i++;
            while (i < lines.length) {
                String cont = lines[i].trim();
                folded.append(' ').append(cont);
                i++;
                if (endsWithUnescapedQuote(cont, openQuote)) {
                    break;
                }
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            out.append(" ".repeat(indent)).append(folded).append('\n');
        }
        return out.toString();
    }

    private static char detectUnclosedYamlQuote(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) {
            return 0;
        }
        int colon = t.indexOf(':');
        if (colon < 0 || colon + 1 >= t.length()) {
            return 0;
        }
        String val = t.substring(colon + 1).trim();
        if (val.isEmpty()) {
            return 0;
        }
        char q = val.charAt(0);
        if (q != '"' && q != '\'') {
            return 0;
        }
        if (val.length() >= 2 && endsWithUnescapedQuote(val.substring(1), q)) {
            return 0;
        }
        return q;
    }

    private static boolean endsWithUnescapedQuote(String s, char q) {
        if (s.isEmpty() || s.charAt(s.length() - 1) != q) {
            return false;
        }
        if (q == '\'') {
            int quotes = 0;
            for (int k = s.length() - 1; k >= 0 && s.charAt(k) == '\''; k--) {
                quotes++;
            }
            return (quotes % 2) == 1;
        }
        int slashes = 0;
        for (int k = s.length() - 2; k >= 0 && s.charAt(k) == '\\'; k--) {
            slashes++;
        }
        return (slashes % 2) == 0;
    }

    private Map<String, OfficialKeyDoc> resolveThirdPartyArtifactDocs(
            JarConfigScanner.HarvestedArtifact art,
            Set<String> targetKeys
    ) {
        String scopeKey = buildArtifactScopeKey(art.artifactId(), art.jarSha512());
        Map<String, String> cached = docsByScopeCache.get(scopeKey);
        if (cached != null) {
            return toOfficialDocMap(cached, art.docSourceUrl());
        }

        List<String> markdownSources = new ArrayList<>();
        List<String> configTemplateSources = new ArrayList<>();
        Set<String> candidateUrls = new LinkedHashSet<>();
        String primarySourceUrl = art.docSourceUrl();

        // 1. Embedded JAR Markdown / docs if present
        for (Map.Entry<String, String> e : art.embeddedPlayerDocs().entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).endsWith(".md") && !e.getValue().isBlank()) {
                markdownSources.add(e.getValue());
            }
        }

        // 2. Modrinth SHA-512 version_file -> project lookup (leafconfig-ADR-001 §1.3)
        if (art.jarSha512() != null && !art.jarSha512().isBlank()) {
            try {
                String verJson = fetcher.getText(
                        DEFAULT_MODRINTH_API_BASE + "/version_file/" + art.jarSha512(),
                        timeoutMs
                );
                String projectId = extractJsonStringField(verJson, "project_id");
                if (!projectId.isEmpty()) {
                    String projJson = fetcher.getText(
                            DEFAULT_MODRINTH_API_BASE + "/project/" + projectId,
                            timeoutMs
                    );
                    String body = extractJsonStringField(projJson, "body");
                    if (!body.isBlank()) {
                        markdownSources.add(body);
                    }
                    String wikiUrl = extractJsonStringField(projJson, "wiki_url");
                    String sourceUrl = extractJsonStringField(projJson, "source_url");
                    if (!wikiUrl.isBlank()) {
                        candidateUrls.add(wikiUrl);
                        if (primarySourceUrl.isBlank()) primarySourceUrl = wikiUrl;
                    }
                    if (!sourceUrl.isBlank()) {
                        candidateUrls.add(sourceUrl);
                        if (primarySourceUrl.isBlank()) primarySourceUrl = sourceUrl;
                    }
                }
            } catch (FileNotFoundException ignoredNotOnModrinth) {
                // Unpublished or non-Modrinth plugin JAR; continue to website URL fallback
            } catch (IOException ex) {
                this.lastDiagnostic = "Modrinth SHA-512 lookup failed for " + art.displayName() + ": " + ex.getMessage();
                LOG.log(Level.FINE, "[LeafConfig] " + this.lastDiagnostic, ex);
            }
        }

        if (art.docSourceUrl() != null && !art.docSourceUrl().isBlank()) {
            candidateUrls.add(art.docSourceUrl());
        }

        // 3. Expand GitHub repository / wiki URLs into raw Configuration.md, README.md, and default config.yml candidates
        Set<String> rawDocUrls = expandCandidateRawDocUrls(candidateUrls);
        int fetchedCount = 0;
        for (String rawUrl : rawDocUrls) {
            if (fetchedCount >= 5) {
                break;
            }
            try {
                String content = fetcher.getText(rawUrl, timeoutMs);
                if (content == null || content.isBlank()) {
                    continue;
                }
                fetchedCount++;
                if (rawUrl.endsWith(".yml") || rawUrl.endsWith(".yaml")
                        || rawUrl.endsWith(".toml") || rawUrl.endsWith(".properties")) {
                    configTemplateSources.add(content);
                } else {
                    markdownSources.add(content);
                }
            } catch (IOException ignoredMissingPage) {
                // Candidate wiki/branch path not present on this repo; try next candidate
            }
        }

        Map<String, OfficialKeyDoc> extracted = extractDocsFromMarkdownAndTemplates(
                markdownSources,
                configTemplateSources,
                targetKeys,
                primarySourceUrl
        );
        Map<String, String> serializable = new LinkedHashMap<>();
        for (Map.Entry<String, OfficialKeyDoc> e : extracted.entrySet()) {
            serializable.put(e.getKey(), e.getValue().description());
        }
        // Cache result (including empty map for unpublished JARs) keyed by jarSha512 to avoid repeat 404 probes
        docsByScopeCache.put(scopeKey, Collections.unmodifiableMap(serializable));
        return extracted;
    }

    static Set<String> expandCandidateRawDocUrls(Set<String> urls) {
        Set<String> rawUrls = new LinkedHashSet<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            String trimmed = u.trim();
            if (trimmed.contains("raw.githubusercontent.com/")) {
                rawUrls.add(trimmed);
                continue;
            }
            Matcher gh = GITHUB_REPO_RE.matcher(trimmed);
            if (gh.find()) {
                String owner = gh.group(1);
                String repo = gh.group(2);
                if (repo.endsWith(".git")) {
                    repo = repo.substring(0, repo.length() - 4);
                }
                rawUrls.add("https://raw.githubusercontent.com/wiki/" + owner + "/" + repo + "/Configuration.md");
                rawUrls.add("https://raw.githubusercontent.com/wiki/" + owner + "/" + repo + "/Config.md");
                rawUrls.add("https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.md");
                rawUrls.add("https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/src/main/resources/config.yml");
                rawUrls.add("https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/bukkit/src/main/resources/config.yml");
                rawUrls.add("https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/common/src/main/resources/config.yml");
            }
        }
        return rawUrls;
    }

    /**
     * Extracts per-key prose descriptions from upstream Markdown documents (fenced commented
     * config blocks, bulleted key lists, headings, and tables) and raw commented config templates,
     * disambiguating short keys against {@code targetKeys} via Lowest-Common-Ancestor suffix match.
     */
    public static Map<String, OfficialKeyDoc> extractDocsFromMarkdownAndTemplates(
            List<String> markdownDocs,
            List<String> rawConfigTemplates,
            Set<String> targetKeys,
            String sourceUrl
    ) {
        Map<String, String> rawExtracted = new LinkedHashMap<>();

        if (rawConfigTemplates != null) {
            for (String tpl : rawConfigTemplates) {
                harvestCommentsFromConfigSnippet("config.yml", tpl, rawExtracted);
            }
        }
        if (markdownDocs != null) {
            for (String md : markdownDocs) {
                if (md == null || md.isBlank()) {
                    continue;
                }
                // 1. Fenced ```yml / ```yaml / ```toml / ```properties blocks with inline # comments
                Matcher fence = MARKDOWN_FENCE_RE.matcher(md);
                while (fence.find()) {
                    String lang = fence.group(1) == null ? "yml" : fence.group(1).toLowerCase(Locale.ROOT);
                    String body = fence.group(2);
                    String pseudoFile = switch (lang) {
                        case "properties" -> "config.properties";
                        case "toml" -> "config.toml";
                        case "json", "jsonc", "hocon", "conf" -> "config.conf";
                        default -> "config.yml";
                    };
                    harvestCommentsFromConfigSnippet(pseudoFile, body, rawExtracted);
                }

                // 2. Markdown bullet lists, headings, and tables referencing config keys
                String[] lines = md.split("\\r?\\n");
                String pendingHeadingKey = null;
                StringBuilder headingParagraph = new StringBuilder();
                for (String line : lines) {
                    String trimmed = line.trim();
                    Matcher hm = MD_HEADING_KEY_RE.matcher(trimmed);
                    if (hm.matches()) {
                        flushHeadingDoc(pendingHeadingKey, headingParagraph, rawExtracted);
                        pendingHeadingKey = hm.group(1);
                        headingParagraph.setLength(0);
                        continue;
                    }
                    if (trimmed.startsWith("#")) {
                        flushHeadingDoc(pendingHeadingKey, headingParagraph, rawExtracted);
                        pendingHeadingKey = null;
                        headingParagraph.setLength(0);
                        continue;
                    }
                    Matcher bm = MD_BULLET_KEY_RE.matcher(line);
                    if (bm.matches()) {
                        String k = bm.group(1).trim();
                        String desc = cleanUpstreamProse(bm.group(2));
                        if (desc.length() >= 12) {
                            rawExtracted.putIfAbsent(k, desc);
                        }
                        continue;
                    }
                    Matcher tm = MD_TABLE_ROW_RE.matcher(trimmed);
                    if (tm.matches()) {
                        String k = tm.group(1).trim();
                        if (!k.equalsIgnoreCase("key") && !k.equalsIgnoreCase("option")
                                && !k.equalsIgnoreCase("setting") && !k.startsWith("---")) {
                            String[] cols = tm.group(2).split("\\|");
                            String bestCol = "";
                            for (String col : cols) {
                                String c = cleanUpstreamProse(col.replace("`", ""));
                                if (c.length() > bestCol.length() && !c.matches("^[-:]+$")) {
                                    bestCol = c;
                                }
                            }
                            if (bestCol.length() >= 15) {
                                rawExtracted.putIfAbsent(k, bestCol);
                            }
                        }
                        continue;
                    }
                    if (pendingHeadingKey != null && !trimmed.isEmpty() && !trimmed.startsWith("```")) {
                        if (headingParagraph.length() < 360) {
                            if (headingParagraph.length() > 0) headingParagraph.append(' ');
                            headingParagraph.append(trimmed);
                        }
                    }
                }
                flushHeadingDoc(pendingHeadingKey, headingParagraph, rawExtracted);
            }
        }

        if (rawExtracted.isEmpty()) {
            return Map.of();
        }

        Map<String, OfficialKeyDoc> resolved = new LinkedHashMap<>();
        Set<String> safeTargets = targetKeys == null ? Set.of() : targetKeys;
        for (Map.Entry<String, String> e : rawExtracted.entrySet()) {
            String candidateKey = e.getKey();
            String prose = e.getValue();
            if (prose == null || prose.isBlank()) {
                continue;
            }
            resolved.putIfAbsent(candidateKey, new OfficialKeyDoc(candidateKey, prose, "", sourceUrl));
            for (String target : safeTargets) {
                if (matchesByLcaSuffix(target, candidateKey)) {
                    resolved.putIfAbsent(target, new OfficialKeyDoc(target, prose, "", sourceUrl));
                }
            }
        }
        return resolved;
    }

    private static void flushHeadingDoc(String key, StringBuilder body, Map<String, String> out) {
        if (key == null || body.isEmpty()) {
            return;
        }
        String cleaned = cleanUpstreamProse(body.toString());
        if (cleaned.length() >= 15) {
            out.putIfAbsent(key, cleaned);
        }
    }

    private static void harvestCommentsFromConfigSnippet(
            String pseudoFilePath,
            String snippetText,
            Map<String, String> out
    ) {
        if (snippetText == null || snippetText.isBlank()) {
            return;
        }
        try {
            YamlConfig cfg = ServerConfigAtlas.parseAnyConfigToYaml(pseudoFilePath, snippetText);
            for (String keyPath : cfg.getKeys(true)) {
                if (cfg.isConfigurationSection(keyPath)) {
                    continue;
                }
                String rawComment = cfg.getComment(keyPath);
                String cleaned = SchemaInferrer.cleanAuthorCommentProse(rawComment);
                if (!cleaned.isBlank() && cleaned.length() >= 10) {
                    out.putIfAbsent(keyPath, cleanUpstreamProse(cleaned));
                }
            }
        } catch (RuntimeException ignoredMalformedFence) {
            // Non-config code fence in Markdown; ignore safely
        }
    }

    private static boolean matchesByLcaSuffix(String fullTargetKey, String docKey) {
        if (fullTargetKey == null || docKey == null) {
            return false;
        }
        if (fullTargetKey.equalsIgnoreCase(docKey)) {
            return true;
        }
        String tNorm = normalizeKeyToken(fullTargetKey);
        String dNorm = normalizeKeyToken(docKey);
        if (tNorm.equals(dNorm)) {
            return true;
        }
        if (fullTargetKey.toLowerCase(Locale.ROOT).endsWith("." + docKey.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String tLeaf = normalizeKeyToken(SchemaInferrer.extractLeaf(fullTargetKey));
        return !docKey.contains(".") && tLeaf.length() >= 5 && tLeaf.equals(dNorm);
    }

    static OfficialKeyDoc matchKeyDoc(Map<String, OfficialKeyDoc> docs, String keyPath) {
        if (docs == null || docs.isEmpty() || keyPath == null || keyPath.isBlank()) {
            return null;
        }
        OfficialKeyDoc exact = docs.get(keyPath);
        if (exact != null) {
            return exact;
        }
        String kLow = keyPath.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, OfficialKeyDoc> e : docs.entrySet()) {
            if (e.getKey().equalsIgnoreCase(kLow)) {
                return e.getValue();
            }
        }
        // Handle Paper world-settings.<world>.<rest> -> world-settings.default.<rest>
        String worldNorm = kLow.replaceFirst("^world-settings\\.[^.]++\\.", "world-settings.default.");
        if (!worldNorm.equals(kLow)) {
            for (Map.Entry<String, OfficialKeyDoc> e : docs.entrySet()) {
                if (e.getKey().equalsIgnoreCase(worldNorm)) {
                    return e.getValue();
                }
            }
        }
        // Handle 1-character singular/plural upstream drift (e.g. PaperMC/docs 'accept-transfers' vs Mojang 'accepts-transfers')
        String normTarget = normalizeKeyToken(kLow);
        for (Map.Entry<String, OfficialKeyDoc> e : docs.entrySet()) {
            String normCand = normalizeKeyToken(e.getKey());
            if (normTarget.equals(normCand)
                    || (normTarget.length() >= 10 && Math.abs(normTarget.length() - normCand.length()) == 1
                    && isSinglePluralDeletion(normTarget, normCand))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isSinglePluralDeletion(String a, String b) {
        String longer = a.length() > b.length() ? a : b;
        String shorter = a.length() > b.length() ? b : a;
        int i = 0;
        int j = 0;
        int skipped = 0;
        char skippedChar = 0;
        while (i < longer.length() && j < shorter.length()) {
            if (longer.charAt(i) == shorter.charAt(j)) {
                i++;
                j++;
            } else {
                skipped++;
                skippedChar = longer.charAt(i);
                if (skipped > 1) return false;
                i++;
            }
        }
        if (skipped == 0) {
            skippedChar = longer.charAt(longer.length() - 1);
        }
        return skippedChar == 's';
    }

    private static String normalizeKeyToken(String s) {
        if (s == null) return "";
        return SchemaInferrer.normalizeCamelToHyphen(s).replace("_", "-").toLowerCase(Locale.ROOT);
    }

    private static boolean hasUndocumentedNodesForArtifact(ServerConfigAtlas atlas, String artifactId) {
        for (ServerConfigAtlas.AstConfigNode n : atlas.nodesBySymbol().values()) {
            if (artifactId.equals(n.artifactId())
                    && n.schema().provenance() == SchemaInferrer.DocProvenance.BYTECODE_INFERRED
                    && !n.commentRestored()
                    && SchemaInferrer.cleanAuthorCommentProse(n.commentText()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> collectKeysForArtifact(ServerConfigAtlas atlas, String artifactId) {
        Set<String> keys = new LinkedHashSet<>();
        for (ServerConfigAtlas.AstConfigNode n : atlas.nodesBySymbol().values()) {
            if (artifactId.equals(n.artifactId())) {
                keys.add(n.keyPath());
            }
        }
        return keys;
    }

    private static String buildArtifactScopeKey(String artifactId, String jarSha512) {
        String id = (jarSha512 != null && !jarSha512.isBlank()) ? jarSha512 : artifactId;
        return "artifact:" + id;
    }

    private static Map<String, OfficialKeyDoc> toOfficialDocMap(Map<String, String> rawMap, String sourceUrl) {
        if (rawMap == null || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, OfficialKeyDoc> out = new LinkedHashMap<>(rawMap.size());
        for (Map.Entry<String, String> e : rawMap.entrySet()) {
            out.put(e.getKey(), new OfficialKeyDoc(e.getKey(), e.getValue(), "", sourceUrl));
        }
        return out;
    }

    /** Serializes the content-addressed upstream documentation cache to compact JSON for atomic disk storage. */
    public String serializeCacheToJson() {
        Map<String, Map<String, String>> sortedScopes = new TreeMap<>(docsByScopeCache);
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n  \"version\": 1,\n  \"scopes\": [\n");
        int sIdx = 0;
        for (Map.Entry<String, Map<String, String>> scopeEntry : sortedScopes.entrySet()) {
            if (sIdx++ > 0) sb.append(",\n");
            sb.append("    {\"scope\":\"").append(SchemaInferrer.escapeJson(scopeEntry.getKey())).append("\",\"docs\":{");
            int dIdx = 0;
            Map<String, String> sortedDocs = new TreeMap<>(scopeEntry.getValue());
            for (Map.Entry<String, String> doc : sortedDocs.entrySet()) {
                if (dIdx++ > 0) sb.append(',');
                sb.append('"').append(SchemaInferrer.escapeJson(doc.getKey())).append("\":\"")
                        .append(SchemaInferrer.escapeJson(doc.getValue())).append('"');
            }
            sb.append("}}");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    /** Loads serialized upstream documentation scopes from JSON, rejecting unsafe payloads fail-closed ({@code S-004}). */
    public int loadCacheFromJson(String rawJson) throws IOException {
        if (rawJson == null || rawJson.isBlank()) {
            return 0;
        }
        String trimmed = rawJson.trim();
        if (!trimmed.startsWith("{") || !trimmed.contains("\"scopes\"")) {
            throw new IOException("Invalid official-docs cache JSON header");
        }
        int loadedScopes = 0;
        Matcher sm = CACHE_SCOPE_BLOCK_RE.matcher(trimmed);
        while (sm.find()) {
            String scope = LlmConfigDocSynthesizer.unescapeJsonString(sm.group(1)).trim();
            String docsBody = sm.group(2);
            if (scope.isEmpty()) {
                continue;
            }
            Map<String, String> docs = new LinkedHashMap<>();
            Matcher dm = CACHE_DOC_ENTRY_RE.matcher(docsBody);
            while (dm.find()) {
                String k = LlmConfigDocSynthesizer.unescapeJsonString(dm.group(1)).trim();
                String v = SchemaInferrer.sanitizeBlanketProse(
                        LlmConfigDocSynthesizer.unescapeJsonString(dm.group(2)).trim()
                );
                if (!k.isEmpty() && !v.isEmpty()) {
                    docs.put(k, v);
                }
            }
            docsByScopeCache.put(scope, Collections.unmodifiableMap(docs));
            loadedScopes++;
        }
        return loadedScopes;
    }

    private static String extractJsonStringField(String rawJson, String fieldName) {
        if (rawJson == null || rawJson.isBlank()) {
            return "";
        }
        Matcher m = JSON_STR_FIELD_RE.matcher(rawJson);
        while (m.find()) {
            if (fieldName.equals(m.group(1))) {
                return LlmConfigDocSynthesizer.unescapeJsonString(m.group(2)).trim();
            }
        }
        return "";
    }

    private static String defaultHttpGet(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "LeafConfig-OfficialDocHarvester/0.1 (+https://github.com/dailystruggle/RTP)");
        conn.setRequestProperty("Accept", "application/json, text/plain, text/markdown, */*");
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new FileNotFoundException("HTTP 404 for " + url);
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream(4096)) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_HTTP_BYTES) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
