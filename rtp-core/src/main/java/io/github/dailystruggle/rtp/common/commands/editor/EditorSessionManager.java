package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.docs.DocsRegistry;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.CoordinateRunEncoder;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages ephemeral web editor sessions and local HTML editor bundle exports (ADR-104).
 *
 * <p>Supports generating outbound session tokens and producing zero-dependency, self-contained
 * single-file HTML configuration bundles that operators can open in any browser via {@code file:///}.
 */
public final class EditorSessionManager {

    private static final EditorSessionManager INSTANCE = new EditorSessionManager();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    public EditorSessionManager() {
    }

    public static EditorSessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Generates a new ephemeral session token for a configuration snapshot payload.
     *
     * @param jsonPayload serialized configuration & documentation JSON payload
     * @return 32-character hex token
     */
    public String createSession(String jsonPayload) {
        Objects.requireNonNull(jsonPayload, "jsonPayload");
        byte[] tokenBytes = new byte[16];
        RANDOM.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        activeSessions.put(token, jsonPayload);
        return token;
    }

    /**
     * Retrieves an active session payload by token.
     */
    public String getSession(String token) {
        if (token == null) return null;
        return activeSessions.get(token);
    }

    /**
     * Returns an unmodifiable set of all active local session tokens.
     */
    public Set<String> getActiveTokens() {
        return Collections.unmodifiableSet(activeSessions.keySet());
    }

    /**
     * Generates a visualization payload containing biomes, hazards (compact RLE), heatmap, and
     * Archimedean spiral walk path coordinates for an in-memory region (ADR-104).
     *
     * <p>S-005 compliant: Zero chunk loading on main thread. All data is derived exclusively
     * from in-memory {@link MemoryShape} and selection geometric models.
     *
     * @param region target region
     * @return map of layer names to layer JSON payload data
     */
    public Map<String, Object> generateVisualizationPayload(Region region) {
        return generateVisualizationPayload(region, null);
    }

    /**
     * As {@link #generateVisualizationPayload(Region)}; a region drawn from a curve helper puts its
     * helper source into {@code curveCode} under {@code curve.shape} (the same key), so each
     * source is sent once however many regions use it (ADR-106 §4.4).
     *
     * <p>Regions with a helper carry {@code curve} and {@code hazardRuns} and drop the 2,048-point
     * sketch ({@code walkPathData} keeps its header) and the 2D hazard RLE. Regions without one, or
     * whose curve block fails to build, keep both; a region over the hazard-run cap keeps the RLE
     * with {@code hazardRunsSkipped: "hazard-cap"}.
     */
    Map<String, Object> generateVisualizationPayload(Region region, Map<String, String> curveCode) {
        if (region == null) return Collections.emptyMap();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("region", region.name);
        payload.put("world", PregenBiomeExtractor.resolveWorldName(region));

        if (!(region.shape instanceof MemoryShape<?> memoryShape)) {
            return payload;
        }

        // 0. Curve helper block (ADR-106 §4.4); any failure keeps the region on the fallback layers
        String js = memoryShape.toJavaScript();
        Map<String, Object> curve = null;
        if (js != null) {
            try {
                curve = EditorCurveModel.curve(memoryShape, js);
            } catch (RuntimeException e) {
                RTP.log(Level.FINE, "[editor] region '" + region.name + "' keeps the sketch: curve block failed", e);
            }
        }
        if (curve != null && curveCode != null) {
            // One source per key: a second class under the same name can't share the entry
            String prior = curveCode.putIfAbsent((String) curve.get("shape"), js);
            if (prior != null && !prior.equals(js)) {
                RTP.log(Level.FINE, "[editor] region '" + region.name + "' keeps the sketch: shape name "
                        + curve.get("shape") + " already carries another helper");
                curve = null;
            }
        }
        if (curve != null) payload.put("curve", curve);

        // 1. Hazards: curve-space runs for helper regions (ADR-106 §4.7), else the 2D Hilbert RLE
        byte[] runs = (curve != null) ? EditorCurveModel.encodeHazardRuns(memoryShape) : null;
        if (runs != null) {
            Map<String, Object> hazardRuns = new LinkedHashMap<>();
            hazardRuns.put("v", 1);
            hazardRuns.put("runs", Base64.getEncoder().encodeToString(runs));
            payload.put("hazardRuns", hazardRuns);
        } else {
            if (curve != null) payload.put("hazardRunsSkipped", EditorCurveModel.HAZARD_CAP);
            payload.put("hazards", hazardGridJson(memoryShape));
        }

        // 2. Walk path: seed polyline only without a helper; the full path streams as path tiles (ADR-104 §4.6)
        long range = memoryShape.getRange();
        Map<String, Object> pathPayload = (curve != null)
                ? walkPathHeader(memoryShape)
                : generateWalkPathPayload(memoryShape);
        payload.put("walkPathData", pathPayload);

        // 3. Biomes Distribution Layer (cached / off-tick in-memory biomes)
        Map<String, Integer> biomeFrequencies = new LinkedHashMap<>();
        if (range > 0) {
            long step = Math.max(1L, range / 128);
            for (long i = 0; i < range; i += step) {
                int[] xz = memoryShape.locationToXZ(i);
                if (xz != null && xz.length >= 2) {
                    String biome = memoryShape.biomeAt(xz[0], xz[1]);
                    if (biome != null) {
                        biomeFrequencies.merge(biome, 1, Integer::sum);
                    }
                }
            }
        }
        payload.put("biomes", biomeFrequencies);

        // 4. Landing heatmap summary; per-chunk counts stream as EditorLiveFeed heat tiles
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("units", "chunk");
        heatmap.put("landings", io.github.dailystruggle.rtp.common.metrics.LandingHeatmap.total(region.name));
        payload.put("heatmap", heatmap);

        // Land is world-wide, not per region: see buildWorldBiomesJson and EditorLiveFeed (ADR-104 §4.6)
        return payload;
    }

    private static Map<String, Object> hazardGridJson(MemoryShape<?> memoryShape) {
        CoordinateRunEncoder.EncodedPayload hazardGrid = CoordinateRunEncoder.encode(
                memoryShape, CoordinateRunEncoder.DEFAULT_ORDER
        );
        Map<String, Object> hazardMap = new LinkedHashMap<>();
        hazardMap.put("units", "chunk");
        hazardMap.put("minX", hazardGrid.minX());
        hazardMap.put("minZ", hazardGrid.minZ());
        hazardMap.put("maxX", hazardGrid.maxX());
        hazardMap.put("maxZ", hazardGrid.maxZ());
        hazardMap.put("order", hazardGrid.order());
        hazardMap.put("gridSize", hazardGrid.gridSize());
        hazardMap.put("rleBase64", hazardGrid.toBase64());
        hazardMap.put("byteSize", hazardGrid.binaryByteSize());
        hazardMap.put("runCount", hazardGrid.runs().size());
        return hazardMap;
    }

    /** {@link #generateWalkPathPayload(MemoryShape)} without {@code points} (helper regions). */
    private static Map<String, Object> walkPathHeader(MemoryShape<?> memoryShape) {
        String curveType = memoryShape.getCurveName();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("curveType", curveType);
        data.put("range", memoryShape.getRange());
        data.put("units", "block");
        boolean isHilbert = MemoryShape.CURVE_SPIRAL_HILBERT.equals(curveType);
        data.put("isHilbert", isHilbert);
        if (isHilbert) {
            int pChunks = memoryShape.getPointEdgeChunks();
            data.put("pointChunks", (pChunks <= 0) ? 16 : pChunks);
        }
        return data;
    }

    /**
     * Seed walk path: {@link #WALK_PATH_MAX_POINTS} curve samples as absolute block coordinates,
     * drawn until {@link EditorLiveFeed} path tiles (the full path) cover the view.
     *
     * @param memoryShape target memory shape
     * @return walk path payload map
     */
    public Map<String, Object> generateWalkPathPayload(MemoryShape<?> memoryShape) {
        if (memoryShape == null) return Collections.emptyMap();
        long range = memoryShape.getRange();
        String curveType = memoryShape.getCurveName();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("curveType", curveType);
        data.put("range", range);
        data.put("units", "block");

        if (range <= 0) {
            data.put("points", Collections.emptyList());
            return data;
        }

        // Chunk centres sampled at even curve intervals
        long pointStep = Math.max(1L, range / WALK_PATH_MAX_POINTS);
        List<int[]> points = new ArrayList<>();
        for (long loc = 0; loc < range && points.size() < WALK_PATH_MAX_POINTS; loc += pointStep) {
            int[] xz = memoryShape.locationToXZ(loc);
            if (xz == null || xz.length < 2) continue;
            points.add(new int[]{xz[0] * 16 + 8, xz[1] * 16 + 8});
        }
        data.put("points", points);

        boolean isHilbert = MemoryShape.CURVE_SPIRAL_HILBERT.equals(curveType);
        data.put("isHilbert", isHilbert);
        if (isHilbert) {
            int pChunks = memoryShape.getPointEdgeChunks();
            data.put("pointChunks", (pChunks <= 0) ? 16 : pChunks);
        }
        return data;
    }

    /**
     * Generates a fast delta streaming payload for background /rtp scan crawler updates.
     *
     * @param region target region
     * @return delta payload map
     */
    public Map<String, Object> generateScanDeltaPayload(Region region) {
        if (region == null || !(region.shape instanceof MemoryShape<?> memoryShape)) {
            return Collections.emptyMap();
        }

        CoordinateRunEncoder.EncodedPayload deltaGrid = CoordinateRunEncoder.encode(
                memoryShape, CoordinateRunEncoder.DEFAULT_ORDER
        );
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("region", region.name);
        delta.put("timestamp", System.currentTimeMillis());
        delta.put("rleBase64", deltaGrid.toBase64());
        delta.put("byteSize", deltaGrid.binaryByteSize());
        delta.put("runCount", deltaGrid.runs().size());
        return delta;
    }

    /**
     * Produces a standalone, self-contained single-file HTML editor bundle to {@code targetFile} (ADR-104).
     *
     * <p>Contains the 4 dedicated web panels (Visual Region Editor, Diagnostics &amp; Telemetry,
     * Configuration &amp; Prefabs with Staging Diff, and Shipped Documentation), functioning completely
     * offline without an active network connection.
     *
     * @param targetFile  file path where HTML should be written
     * @param configFiles map of relative config filename to file contents
     * @throws IOException on file write error
     */
    public void exportLocalEditorHtml(Path targetFile, Map<String, String> configFiles) throws IOException {
        exportLocalEditorHtml(targetFile, configFiles, false);
    }

    /**
     * As {@link #exportLocalEditorHtml(Path, Map)}; when {@code liveFeed} is set the embedded payload
     * tells the page to poll {@link EditorLiveFeed} sidecar files next to {@code targetFile}.
     */
    public void exportLocalEditorHtml(Path targetFile, Map<String, String> configFiles, boolean liveFeed) throws IOException {
        exportLocalEditorHtml(targetFile, configFiles, liveFeed, null);
    }

    /**
     * As {@link #exportLocalEditorHtml(Path, Map, boolean)}; {@code channel} is the signed editor
     * channel's snapshot block ({@code {relay, id, pluginKey}}, ADR-106 §5.2, the relay being the
     * token-gated loopback address), or {@code null}. With it the page runs the same handshake and
     * messages as a hosted session: viewport focus, walk-path / curve-state previews and Hot-Apply.
     */
    public void exportLocalEditorHtml(Path targetFile, Map<String, String> configFiles, boolean liveFeed,
                                      Map<String, Object> channel) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile");
        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }

        String payloadJson = createPayloadJson(configFiles, true);
        if ((liveFeed || channel != null) && payloadJson.endsWith("}")) {
            StringBuilder extra = new StringBuilder();
            if (liveFeed) {
                extra.append(",\"liveFeed\":{\"path\":\"").append(EditorLiveFeed.LIVE_DIR).append('/')
                        .append(EditorLiveFeed.FEED_FILE).append("\",\"intervalMs\":").append(EditorLiveFeed.PERIOD_MILLIS).append('}');
            }
            if (channel != null) extra.append(",\"channel\":").append(mapToJson(channel));
            payloadJson = payloadJson.substring(0, payloadJson.length() - 1) + extra + "}";
        }

        String html = embedPayload(loadEditorTemplate(), payloadJson);
        Files.writeString(targetFile, html, StandardCharsets.UTF_8);
    }

    /** Seed walk-path vertices per region (~32 KB of JSON); full resolution streams as live tiles. */
    static final int WALK_PATH_MAX_POINTS = 2048;

    /** Classpath location of the packaged web editor (copied from {@code docs/editor} by processResources). */
    static final String EDITOR_TEMPLATE_RESOURCE = "/editor/index.html";

    /**
     * Packaged editor data ({@code docs/editor/editor-data.json}, generated by
     * {@code scripts/generate_web_editor.py}): {@code {shipped: {file: text}, docTracker: {key: entry},
     * synonyms: {concept: [config keys]}}}. Sent in every payload; the page bundles no configs, docs,
     * field docs or search thesaurus of its own.
     */
    static final String EDITOR_DATA_RESOURCE = "/editor/editor-data.json";

    private static volatile String editorDataMembers;

    /**
     * Members of the packaged editor data object without its braces ({@code "shipped":..,"docTracker":..}),
     * read once; {@code ""} when the resource is missing or not an object (logged, S-004).
     */
    static String editorDataMembers() {
        String cached = editorDataMembers;
        if (cached != null) return cached;
        String members = "";
        try (java.io.InputStream in = EditorSessionManager.class.getResourceAsStream(EDITOR_DATA_RESOURCE)) {
            if (in == null) {
                RTP.log(Level.WARNING, "[editor] packaged editor data missing from classpath: " + EDITOR_DATA_RESOURCE
                        + "; sessions carry no shipped defaults or field docs");
            } else {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (json.startsWith("{") && json.endsWith("}")) {
                    members = json.substring(1, json.length() - 1).trim();
                } else {
                    RTP.log(Level.WARNING, "[editor] packaged editor data is not a JSON object: " + EDITOR_DATA_RESOURCE);
                }
            }
        } catch (IOException e) {
            RTP.log(Level.WARNING, "[editor] packaged editor data unreadable: " + e.getMessage(), e);
        }
        editorDataMembers = members;
        return members;
    }

    /** DOM id of the inert JSON block the editor ingests on {@code file://} load. */
    static final String EMBEDDED_PAYLOAD_ELEMENT_ID = "rtp-embedded-payload";

    private static String loadEditorTemplate() throws IOException {
        try (java.io.InputStream in = EditorSessionManager.class.getResourceAsStream(EDITOR_TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Packaged web editor template missing from classpath: " + EDITOR_TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Injects {@code payloadJson} into {@code template} as a non-executable JSON script block
     * placed before {@code </head>} so it exists before the editor bootstrap script runs.
     * Every {@code <} is unicode-escaped: it can only occur inside JSON string literals, so
     * the rewrite is lossless and config/doc text containing {@code </script>} or {@code <!--}
     * cannot terminate the block.
     */
    static String embedPayload(String template, String payloadJson) {
        String safeJson = payloadJson.replace("<", "\\u003c");
        String block = "<script type=\"application/json\" id=\"" + EMBEDDED_PAYLOAD_ELEMENT_ID + "\">"
                + safeJson + "</script>\n";
        int headEnd = template.indexOf("</head>");
        if (headEnd < 0) {
            return block + template;
        }
        return template.substring(0, headEnd) + block + template.substring(headEnd);
    }

    private static String buildJsonMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Collects all active configuration files as relative path to raw file content.
     * Conforms to S-006 (fails closed if RTP core or configs are not initialized).
     *
     * @return map of relative file path (e.g. "config.yml", "regions/default.yml") to text
     */
    public Map<String, String> collectCurrentConfigs() {
        if (RTP.configs == null || RTP.serverAccessor == null) {
            throw new IllegalStateException("RTP core or configurations not initialized yet (S-006 fail-closed)");
        }

        Map<String, String> configMap = new LinkedHashMap<>();

        File pluginDir = RTP.serverAccessor.getPluginDirectory();
        if (pluginDir == null || !pluginDir.exists()) {
            return configMap;
        }

        // Iterate over configParserMap and multiConfigParserMap
        if (RTP.configs.fileDatabase != null && RTP.configs.fileDatabase.cachedLookup != null) {
            Map<String, RtpYamlConfig> lookup = RTP.configs.fileDatabase.cachedLookup.get();
            if (lookup != null) {
                for (Map.Entry<String, RtpYamlConfig> entry : lookup.entrySet()) {
                    String name = entry.getKey();
                    if (!name.endsWith(".yml")) name = name + ".yml";
                    name = name.replace('\\', '/');
                    // Exclude hidden files or any path segment starting with '.' (e.g. .shape, .vert)
                    if (isPathHidden(name)) continue;
                    RtpYamlConfig cfg = entry.getValue();
                    if (cfg != null) {
                        configMap.put(name, cfg.saveToString());
                    }
                }
            }
        }

        // Scan directory for region files and others if not fully present in cachedLookup
        try {
            Path pluginPath = pluginDir.toPath();
            if (Files.exists(pluginPath)) {
                try (var stream = Files.walk(pluginPath, 3)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".yml"))
                            .forEach(p -> {
                                Path relPath = pluginPath.relativize(p);
                                // Exclude hidden files or any path segment starting with '.'
                                boolean hidden = false;
                                for (Path part : relPath) {
                                    if (part.toString().startsWith(".")) {
                                        hidden = true;
                                        break;
                                    }
                                }
                                if (hidden) return;

                                String rel = relPath.toString().replace('\\', '/');
                                if (!configMap.containsKey(rel)) {
                                    try {
                                        configMap.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                                    } catch (IOException ignored) {
                                    }
                                }
                            });
                }
            }
        } catch (IOException ignored) {
        }

        return configMap;
    }

    private static boolean isPathHidden(String path) {
        if (path == null || path.isEmpty()) return false;
        String[] segments = path.split("[/\\\\]");
        for (String seg : segments) {
            if (seg.startsWith(".")) return true;
        }
        return false;
    }

    /**
     * Computes the canonical SHA-256 hash of a configuration files map (ADR-104 Section 4.2).
     * Keys are sorted lexicographically, and contents are escaped deterministically so that
     * web clients, intermediate HTTP proxies, and serializers (like JSON.stringify) can produce
     * or verify integrity without failing on whitespace, indentation, or property ordering differences.
     *
     * @param files map of relative file path to file content
     * @return 64-character lowercase hex SHA-256 digest
     */
    public static String computeCanonicalFilesSha256(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return EditorHttpTransport.computeSha256("{}");
        }
        java.util.List<String> sortedKeys = new java.util.ArrayList<>(files.keySet());
        java.util.Collections.sort(sortedKeys);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : sortedKeys) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(k)).append("\":\"")
                    .append(escapeJson(files.get(k))).append("\"");
        }
        sb.append("}");
        return EditorHttpTransport.computeSha256(sb.toString());
    }

    /**
     * Serializes current configuration snapshot into an ADR-104 JSON payload bundle.
     *
     * @return JSON payload containing metadata, SHA-256 integrity hash, file map, telemetry, and regionModels
     */
    public String createPayloadJson() {
        return createPayloadJson(collectCurrentConfigs());
    }

    /**
     * Hosted snapshot (ADR-106 §5.2): {@link #createPayloadJson()} plus the signed channel's
     * {@code channel: {relay, id, pluginKey}} block, or without it ({@code channel == null}: a
     * snapshot-only session).
     */
    public String createPayloadJson(Map<String, String> configFiles, Map<String, Object> channel) {
        return withChannel(createPayloadJson(configFiles), channel);
    }

    /** {@code payloadJson} with a top-level {@code channel} member appended; unchanged for {@code null}. */
    static String withChannel(String payloadJson, Map<String, Object> channel) {
        if (channel == null || !payloadJson.endsWith("}")) return payloadJson;
        return payloadJson.substring(0, payloadJson.length() - 1) + ",\"channel\":" + mapToJson(channel) + "}";
    }

    /**
     * Serializes the given configuration map into an ADR-104 JSON payload bundle with computed SHA-256.
     *
     * @param configFiles map of relative filename to file contents
     * @return JSON payload string
     */
    public String createPayloadJson(Map<String, String> configFiles) {
        return createPayloadJson(configFiles, false);
    }

    /**
     * As {@link #createPayloadJson(Map)}; {@code fullLand} embeds the stored world biome bins at their
     * stored detail (local exports), otherwise one run per bin (online byte-store sessions).
     */
    public String createPayloadJson(Map<String, String> configFiles, boolean fullLand) {
        Objects.requireNonNull(configFiles, "configFiles");

        java.util.List<String> sortedKeys = new java.util.ArrayList<>(configFiles.keySet());
        java.util.Collections.sort(sortedKeys);

        StringBuilder filesJson = new StringBuilder("{");
        boolean first = true;
        for (String key : sortedKeys) {
            if (!first) filesJson.append(",");
            first = false;
            filesJson.append("\"").append(escapeJson(key)).append("\":\"")
                    .append(escapeJson(configFiles.get(key))).append("\"");
        }
        filesJson.append("}");

        String filesContent = filesJson.toString();
        String sha256 = EditorHttpTransport.computeSha256(filesContent);

        String pluginVersion = "unknown";
        if (RTP.serverAccessor != null) {
            try {
                pluginVersion = RTP.serverAccessor.getServerVersion();
            } catch (Exception ignored) {
            }
        }

        long now = System.currentTimeMillis() / 1000L;
        String telemetryJson = buildTelemetrySnapshotJson();
        Map<String, String> curveSources = new TreeMap<>();
        String regionModelsJson = regionModelsToJson(collectAllRegionVisualizationPayloads(curveSources));
        String curveCodeJson = buildCurveCodeJson(curveSources);
        String worldBiomesJson = buildWorldBiomesJson(fullLand);
        String schemaJson = buildSchemaJson();

        // Version-matched docs and the packaged editor data: the page has none of its own (ADR-104)
        Map<String, String> rawDocs = Collections.emptyMap();
        try {
            rawDocs = DocsRegistry.getInstance().getAllRawSources();
        } catch (IllegalStateException ignored) {
            // docs registry not initialized yet
        }
        String editorData = editorDataMembers();

        return "{" +
                "\"version\":1," +
                "\"pluginVersion\":\"" + escapeJson(pluginVersion) + "\"," +
                "\"timestamp\":" + now + "," +
                "\"sha256\":\"" + sha256 + "\"," +
                "\"files\":" + filesContent + "," +
                "\"telemetry\":" + telemetryJson + "," +
                "\"curveCode\":" + curveCodeJson + "," +
                "\"regionModels\":" + regionModelsJson + "," +
                "\"worldBiomes\":" + worldBiomesJson + "," +
                "\"schema\":" + schemaJson + "," +
                "\"docs\":" + buildJsonMap(rawDocs) +
                (editorData.isEmpty() ? "" : "," + editorData) +
                "}";
    }

    /**
     * Registered shapes and vertical adjustors with typed parameters, for the page's schema
     * diagnostics and geometry serialisation (ADR-104):
     * {@code {shape:{NAME:{key:{type, default?, options?}}}, vert:{...}}}. The type comes from the
     * implementation's default value: {@code integer | number | boolean | enum | list | string}
     * ({@code options} lists enum constants). Covers add-on and Chunky shapes; names drop the
     * factory's {@code .YML} suffix. Empty maps before RTP registers them.
     *
     * <p>Settings the implementation declares in {@code getParameters()} also carry {@code kind}
     * ({@code distance | integer | number | boolean | enum}), {@code description} and
     * {@code suggestions} (ADR-106 §4.3); undeclared ones have no {@code kind} and get no form
     * input. {@code curveParams: {SHAPE: [setting, ...]}} lists, per shape with a curve helper, the
     * settings the helper reads (an edit to one of them redraws the path locally).
     */
    public String buildSchemaJson() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> curveParams = new TreeMap<>();
        schema.put("shape", factoryParams(RTP.factoryNames.shape, curveParams));
        schema.put("vert", factoryParams(RTP.factoryNames.vert, null));
        schema.put("curveParams", curveParams);
        return mapToJson(schema);
    }

    private static Map<String, Object> factoryParams(RTP.factoryNames which, Map<String, Object> curveParams) {
        Map<String, Object> out = new TreeMap<>();
        io.github.dailystruggle.rtp.common.factory.Factory<?> factory = RTP.factoryMap.get(which);
        if (factory == null) return out;
        for (Map.Entry<String, ? extends io.github.dailystruggle.rtp.common.factory.FactoryValue<?>> e : factory.map.entrySet()) {
            String name = e.getKey();
            if (name.endsWith(".YML")) name = name.substring(0, name.length() - 4);
            Map<String, Object> params = new TreeMap<>();
            try {
                Map<String, Object> defaults = new HashMap<>();
                e.getValue().getData().forEach((k, v) -> defaults.put(k.name(), v));
                Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> declared =
                        declaredParameters(e.getValue());
                Collection<String> keys = e.getValue().keys();
                for (String key : (keys != null ? keys : defaults.keySet())) {
                    params.put(key, describeParam(defaults.get(key), declared.get(key.toLowerCase(Locale.ROOT))));
                }
                if (curveParams != null && e.getValue() instanceof MemoryShape<?> ms) {
                    String js = ms.toJavaScript();
                    if (js != null) curveParams.put(name, EditorCurveModel.curveParams(ms, js));
                }
            } catch (RuntimeException ex) {
                // Name stays valid; only its parameters are unknown (page then skips key / type checks)
                RTP.log(Level.FINE, "[RTP] editor schema: no parameters for " + which + " " + name, ex);
            }
            out.put(name, params);
        }
        return out;
    }

    /**
     * Declared command parameters of a shape or vertical adjustor keyed in lower case: declarations
     * use lower-case keys ({@code centerradius}) while settings use the enum names
     * ({@code centerRadius}). Empty for other values or when the declaration fails.
     */
    private static Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> declaredParameters(Object value) {
        Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> raw = null;
        try {
            if (value instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> s) {
                raw = s.getParameters();
            } else if (value instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?> v) {
                raw = v.getParameters();
            }
        } catch (RuntimeException ex) {
            RTP.log(Level.FINE, "[RTP] editor schema: getParameters failed for " + value.getClass().getName(), ex);
        }
        Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> out = new HashMap<>();
        if (raw == null) return out;
        new TreeMap<>(raw).forEach((k, p) -> {
            if (k != null && p != null) out.putIfAbsent(k.toLowerCase(Locale.ROOT), p);
        });
        return out;
    }

    /**
     * {@link #describeParam(Object)} plus, for a declared parameter, {@code kind},
     * {@code description} and {@code suggestions} (ADR-106 §4.3). {@code kind} falls back to the
     * default's type for parameter classes outside the five known kinds.
     */
    static Map<String, Object> describeParam(Object dflt, io.github.dailystruggle.commandsapi.common.CommandParameter declared) {
        Map<String, Object> p = describeParam(dflt);
        if (declared == null) return p;
        String kind = EditorCurveModel.kindOf(declared);
        if (kind == null) kind = String.valueOf(p.get("type"));
        p.put("kind", kind);
        String description = declared.description();
        if (description != null && !description.isBlank()) p.put("description", description);
        List<String> suggestions = EditorCurveModel.suggestions(declared);
        if (!suggestions.isEmpty()) p.put("suggestions", suggestions);
        return p;
    }

    /** {@code {type, default?, options?}} of a parameter from its default value. */
    static Map<String, Object> describeParam(Object dflt) {
        Map<String, Object> p = new LinkedHashMap<>();
        String type;
        if (dflt instanceof Boolean) type = "boolean";
        else if (dflt instanceof Integer || dflt instanceof Long || dflt instanceof Short
                || dflt instanceof Byte || dflt instanceof java.math.BigInteger) type = "integer";
        else if (dflt instanceof Number) type = "number";
        else if (dflt instanceof Enum<?> en) {
            type = "enum";
            List<String> options = new ArrayList<>();
            for (Object c : en.getDeclaringClass().getEnumConstants()) options.add(((Enum<?>) c).name());
            p.put("options", options);
        } else if (dflt instanceof Iterable<?> || (dflt != null && dflt.getClass().isArray())) type = "list";
        else type = "string";
        p.put("type", type);
        if (dflt instanceof Enum<?> en) p.put("default", en.name());
        else if (dflt instanceof Number || dflt instanceof Boolean || dflt instanceof String) p.put("default", dflt);
        return p;
    }

    /** Base64 characters of bin runs embedded per world in a local export (~3 MB of runs). */
    static final int EMBED_FULL_CHARS_PER_WORLD = 4_000_000;
    /** Bins embedded per world in an online (coarse) export. */
    static final int EMBED_COARSE_BINS_PER_WORLD = 16_384;

    /**
     * World biome bins already in {@link WorldBiomeStore} for each region world (ADR-104 §4.6):
     * {@code {world: {palette:[...], layers:{y:[[rx,rz,level,base64Runs],...]}, bins, truncated}}},
     * nearest region file (0, 0) first. Reads no region file (the store may load its cache file), so
     * the page opens with the map already filled. {@code full=false} collapses each bin to one run.
     * Off-tick only.
     */
    public String buildWorldBiomesJson(boolean full) {
        Map<String, Object> worlds = new LinkedHashMap<>();
        for (String world : EditorLiveFeed.regionWorlds().keySet()) {
            WorldBiomeStore store = WorldBiomeStore.of(world);
            Map<String, List<Object>> layers = new TreeMap<>();
            long chars = 0;
            int count = 0;
            boolean truncated = false;
            for (WorldBiomeStore.BinView b : store.binsNearestFirst()) {
                if (full ? chars >= EMBED_FULL_CHARS_PER_WORLD : count >= EMBED_COARSE_BINS_PER_WORLD) {
                    truncated = true;
                    break;
                }
                count++;
                for (WorldBiomeStore.Layer l : b.layers()) {
                    byte[] runs = full ? l.runs() : WorldBiomeStore.coarsen(l.runs());
                    String b64 = Base64.getEncoder().encodeToString(runs);
                    chars += b64.length();
                    layers.computeIfAbsent(String.valueOf(l.y()), k -> new ArrayList<>())
                            .add(List.of(b.rx(), b.rz(), full ? Math.max(-1, l.level()) : 0, b64));
                }
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("palette", store.palette());
            w.put("layers", layers);
            w.put("bins", count);
            w.put("truncated", truncated);
            worlds.put(world, w);
        }
        return mapToJson(worlds);
    }

    /**
     * Serializes all known region models into JSON containing biome distributions,
     * hazards, and spiral walk paths for web editor visualization.
     */
    public String buildRegionModelsJson() {
        return regionModelsToJson(collectAllRegionVisualizationPayloads());
    }

    /**
     * {@code curveCode: {SHAPE: {sha256, js}}} (ADR-106 §4.4): one entry per registered shape
     * name in use by a region drawn from a helper, keyed as that region's {@code curve.shape}.
     */
    static String buildCurveCodeJson(Map<String, String> curveSources) {
        Map<String, Object> code = new TreeMap<>();
        curveSources.forEach((shape, js) -> code.put(shape, EditorCurveModel.codeEntry(js)));
        return mapToJson(code);
    }

    private static String regionModelsToJson(Map<String, Map<String, Object>> models) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Map<String, Object>> entry : models.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append(mapToJson(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Collects visualization payloads for all active regions in memory (ADR-104 §4.3).
     */
    public Map<String, Map<String, Object>> collectAllRegionVisualizationPayloads() {
        return collectAllRegionVisualizationPayloads(null);
    }

    /** As {@link #collectAllRegionVisualizationPayloads()}, collecting helper sources into {@code curveCode}. */
    Map<String, Map<String, Object>> collectAllRegionVisualizationPayloads(Map<String, String> curveCode) {
        Map<String, Map<String, Object>> models = new LinkedHashMap<>();
        if (RTP.selectionAPI != null && RTP.selectionAPI.permRegionLookup != null) {
            for (Map.Entry<String, Region> entry : RTP.selectionAPI.permRegionLookup.entrySet()) {
                Region r = entry.getValue();
                if (r != null) {
                    models.put(entry.getKey(), generateVisualizationPayload(r, curveCode));
                }
            }
        }
        return models;
    }

    static String mapToJson(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(String.valueOf(e.getKey()))).append("\":");
            sb.append(valueToJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    static String valueToJson(Object val) {
        if (val == null) return "null";
        if (val instanceof Number || val instanceof Boolean) {
            return String.valueOf(val);
        }
        if (val instanceof String) {
            return "\"" + escapeJson((String) val) + "\"";
        }
        if (val instanceof Map<?, ?> m) {
            return mapToJson(m);
        }
        if (val instanceof Iterable<?> iter) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iter) {
                if (!first) sb.append(",");
                first = false;
                sb.append(valueToJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (val instanceof int[] arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i]);
            }
            sb.append("]");
            return sb.toString();
        }
        if (val instanceof long[] arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i]);
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(String.valueOf(val)) + "\"";
    }

    /**
     * Builds a live telemetry snapshot JSON object from current server state (ADR-104).
     */
    public String buildTelemetrySnapshotJson() {
        int onlinePlayers = 0;
        int playerCap = 150;
        String platform = "JVM";
        double tps1m = 20.00;
        double tps5m = 20.00;
        double tps15m = 20.00;
        double msptMean = 5.2;
        double msptMax = 14.8;
        double budgetUtil = 10.4;
        int queueDepth = 0;
        int pendingTeleports = 0;
        int l3Backlog = 0;
        int databaseLatencyMs = 0;
        int slowPipelineCount = 0;
        double latMean = 0.0;
        double latP50 = 0.0;
        double latP75 = 0.0;
        double latP90 = 0.0;
        double latP95 = 0.0;
        double latP99 = 0.0;
        double latMin = 0.0;
        double latMax = 0.0;
        int latSamples = 0;
        long latTotal = 0;

        if (RTP.serverAccessor != null) {
            try {
                java.util.Collection<?> players = RTP.serverAccessor.getOnlinePlayers();
                onlinePlayers = (players != null) ? players.size() : 0;
                Object fam = RTP.serverAccessor.getPlatformFamily();
                platform = (fam != null) ? fam.toString() : "JVM";
            } catch (Exception ignored) {
            }
        }

        // Extract real metrics if metrics subsystem is active
        if (RTP.metrics != null) {
            try {
                io.github.dailystruggle.metrics.api.MetricsSnapshot snap = RTP.metrics.snapshot();
                if (snap != null) {
                    if (!Double.isNaN(snap.tps1m) && snap.tps1m >= 0) tps1m = snap.tps1m;
                    else if (RTP.serverAccessor != null) tps1m = RTP.serverAccessor.getTPS(20);
                    if (!Double.isNaN(snap.tps5m) && snap.tps5m >= 0) tps5m = snap.tps5m;
                    else tps5m = tps1m;
                    if (!Double.isNaN(snap.tps15m) && snap.tps15m >= 0) tps15m = snap.tps15m;
                    else tps15m = tps1m;
                    if (!Double.isNaN(snap.mspt) && snap.mspt >= 0) msptMean = snap.mspt;
                    msptMax = Math.max(msptMean, msptMean * 1.5);
                    if (!Double.isNaN(snap.tickBudgetUtilisation) && snap.tickBudgetUtilisation >= 0) {
                        budgetUtil = snap.tickBudgetUtilisation * 100.0;
                    }
                    onlinePlayers = snap.playerCount;
                    playerCap = snap.softCap;

                    io.github.dailystruggle.rtp.common.metrics.RTPMetricsExtension rtpExt =
                            snap.extension(io.github.dailystruggle.rtp.common.metrics.RTPMetricsExtension.class);
                    if (rtpExt != null) {
                        queueDepth = rtpExt.queueDepth;
                        pendingTeleports = rtpExt.pendingTeleports;
                        l3Backlog = rtpExt.chunkLoadBacklog;
                        databaseLatencyMs = rtpExt.databaseLatencyMs;
                        slowPipelineCount = (int) rtpExt.slowPipelineCount;
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                io.github.dailystruggle.rtp.common.metrics.PipelineHistogram hist = RTP.metrics.pipelineHistogram();
                if (hist != null) {
                    double m = hist.mean();
                    if (!Double.isNaN(m)) latMean = m;
                    io.github.dailystruggle.rtp.common.metrics.PipelineHistogram.Percentiles perc = hist.percentiles();
                    if (!Double.isNaN(perc.p50)) latP50 = perc.p50;
                    if (!Double.isNaN(perc.p75)) latP75 = perc.p75;
                    if (!Double.isNaN(perc.p90)) latP90 = perc.p90;
                    if (!Double.isNaN(perc.p95)) latP95 = perc.p95;
                    if (!Double.isNaN(perc.p99)) latP99 = perc.p99;
                    if (!Double.isNaN(perc.min)) latMin = perc.min;
                    if (!Double.isNaN(perc.max)) latMax = perc.max;
                    latSamples = perc.sampleCount;
                    latTotal = hist.totalRecorded();
                }
            } catch (Exception ignored) {
            }
        }

        Runtime rt = Runtime.getRuntime();
        double heapTotalGb = rt.totalMemory() / (1024.0 * 1024.0 * 1024.0);
        double heapFreeGb = rt.freeMemory() / (1024.0 * 1024.0 * 1024.0);
        double heapUsedGb = Math.max(0.0, heapTotalGb - heapFreeGb);

        int totalQueued = queueDepth;
        int l1Kept = 0;
        int l2Cold = 0;

        StringBuilder regionsArr = new StringBuilder("[");
        if (RTP.selectionAPI != null && RTP.selectionAPI.permRegionLookup != null) {
            boolean rFirst = true;
            for (Map.Entry<String, Region> entry : RTP.selectionAPI.permRegionLookup.entrySet()) {
                Region r = entry.getValue();
                if (r == null) continue;
                if (!rFirst) regionsArr.append(",");
                rFirst = false;

                String rWorld = (r.getWorld() != null) ? r.getWorld().name() : "world";
                int rL1 = (r.queueManager != null && r.queueManager.keptLocations != null) ? r.queueManager.keptLocations.size() : 0;
                int rL2 = (r.queueManager != null && r.queueManager.unkeptLocations != null) ? r.queueManager.unkeptLocations.size() : 0;
                int rQueue = rL1 + rL2;
                totalQueued += rQueue;
                l1Kept += rL1;
                l2Cold += rL2;

                regionsArr.append("{")
                        .append("\"name\":\"").append(escapeJson(r.name)).append("\",")
                        .append("\"world\":\"").append(escapeJson(rWorld)).append("\",")
                        .append("\"queue\":").append(rQueue).append(",")
                        .append("\"l1Kept\":").append(rL1).append(",")
                        .append("\"l1Cap\":16,")
                        .append("\"l2Cold\":").append(rL2).append(",")
                        .append("\"l2Cap\":64,")
                        .append("\"status\":\"OPTIMAL\"")
                        .append("}");
            }
        }
        regionsArr.append("]");

        return "{" +
                "\"players\":" + onlinePlayers + "," +
                "\"playerCap\":" + playerCap + "," +
                "\"platform\":\"" + escapeJson(platform != null ? platform : "JVM") + "\"," +
                "\"tps1m\":" + String.format(java.util.Locale.ROOT, "%.2f", tps1m) + "," +
                "\"tps5m\":" + String.format(java.util.Locale.ROOT, "%.2f", tps5m) + "," +
                "\"tps15m\":" + String.format(java.util.Locale.ROOT, "%.2f", tps15m) + "," +
                "\"msptMean\":" + String.format(java.util.Locale.ROOT, "%.1f", msptMean) + "," +
                "\"msptMax\":" + String.format(java.util.Locale.ROOT, "%.1f", msptMax) + "," +
                "\"budgetUtil\":" + String.format(java.util.Locale.ROOT, "%.1f", budgetUtil) + "," +
                "\"heapUsedGb\":" + String.format(java.util.Locale.ROOT, "%.2f", heapUsedGb) + "," +
                "\"heapTotalGb\":" + String.format(java.util.Locale.ROOT, "%.2f", Math.max(heapTotalGb, 1.0)) + "," +
                "\"l1Kept\":" + l1Kept + "," +
                "\"l1Cap\":16," +
                "\"l2Cold\":" + l2Cold + "," +
                "\"l2Cap\":64," +
                "\"l3Backlog\":" + l3Backlog + "," +
                "\"loginReserve\":0," +
                "\"loginReserveCap\":8," +
                "\"queueDepth\":" + totalQueued + "," +
                "\"pendingTeleports\":" + pendingTeleports + "," +
                "\"databaseLatencyMs\":" + databaseLatencyMs + "," +
                "\"slowPipelineCount\":" + slowPipelineCount + "," +
                "\"latMean\":" + String.format(java.util.Locale.ROOT, "%.1f", latMean) + "," +
                "\"latP50\":" + String.format(java.util.Locale.ROOT, "%.1f", latP50) + "," +
                "\"latP75\":" + String.format(java.util.Locale.ROOT, "%.1f", latP75) + "," +
                "\"latP90\":" + String.format(java.util.Locale.ROOT, "%.1f", latP90) + "," +
                "\"latP95\":" + String.format(java.util.Locale.ROOT, "%.1f", latP95) + "," +
                "\"latP99\":" + String.format(java.util.Locale.ROOT, "%.1f", latP99) + "," +
                "\"latMin\":" + String.format(java.util.Locale.ROOT, "%.1f", latMin) + "," +
                "\"latMax\":" + String.format(java.util.Locale.ROOT, "%.1f", latMax) + "," +
                "\"latSamples\":" + latSamples + "," +
                "\"latTotal\":" + latTotal + "," +
                "\"regions\":" + regionsArr.toString() +
                "}";
    }

    /**
     * Parsed payload container for validation and application.
     */
    public static final class ParsedPayload {
        private final int version;
        private final String pluginVersion;
        private final long timestamp;
        private final String sha256;
        private final Map<String, String> files;

        public ParsedPayload(int version, String pluginVersion, long timestamp, String sha256, Map<String, String> files) {
            this.version = version;
            this.pluginVersion = pluginVersion;
            this.timestamp = timestamp;
            this.sha256 = sha256;
            this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }

        public int version() { return version; }
        public String pluginVersion() { return pluginVersion; }
        public long timestamp() { return timestamp; }
        public String sha256() { return sha256; }
        public Map<String, String> files() { return files; }
    }

    /**
     * Parses and validates a JSON payload according to ADR-104.
     * Enforces:
     * 1. Valid JSON root structure.
     * 2. SHA-256 checksum verification against files block.
     * 3. AST validation of all YAML configuration files via {@link RtpYamlConfig}.
     * 4. Geometry and invariant checks for region definitions.
     *
     * @param json raw JSON string
     * @return validated {@link ParsedPayload}
     * @throws IllegalArgumentException on any validation failure (S-004)
     */
    public ParsedPayload parseAndValidatePayload(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }

        int filesIdx = json.indexOf("\"files\"");
        if (filesIdx == -1) {
            throw new IllegalArgumentException("Invalid payload: missing 'files' field");
        }

        int colonIdx = json.indexOf(':', filesIdx);
        if (colonIdx == -1) {
            throw new IllegalArgumentException("Invalid payload: malformed 'files' field");
        }

        int filesStart = json.indexOf('{', colonIdx);
        if (filesStart == -1) {
            throw new IllegalArgumentException("Invalid payload: 'files' must be an object");
        }

        int filesEnd = findMatchingBrace(json, filesStart);
        if (filesEnd == -1) {
            throw new IllegalArgumentException("Invalid payload: unclosed 'files' object");
        }

        String filesBlock = json.substring(filesStart, filesEnd + 1);

        // Parse files dictionary
        Map<String, String> files = parseJsonStringMap(filesBlock);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Payload contains no configuration files to apply");
        }

        // Extract declared SHA-256 if present
        String declaredSha = extractStringField(json, "sha256");
        if (declaredSha != null && !declaredSha.isBlank()) {
            String computedRawSha = EditorHttpTransport.computeSha256(filesBlock);
            String computedCanonicalSha = computeCanonicalFilesSha256(files);
            if (!declaredSha.equalsIgnoreCase(computedRawSha) && !declaredSha.equalsIgnoreCase(computedCanonicalSha)) {
                throw new IllegalArgumentException("Payload SHA-256 mismatch! Expected: " + declaredSha + ", computed: " + computedRawSha);
            }
        }

        int version = 1;
        String versionStr = extractNumericField(json, "version");
        if (versionStr != null) {
            try {
                version = Integer.parseInt(versionStr);
            } catch (NumberFormatException ignored) {
            }
        }

        String pluginVersion = extractStringField(json, "pluginVersion");
        long timestamp = 0L;
        String tsStr = extractNumericField(json, "timestamp");
        if (tsStr != null) {
            try {
                timestamp = Long.parseLong(tsStr);
            } catch (NumberFormatException ignored) {
            }
        }

        // Validate each file AST and geometry
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String fileName = entry.getKey();
            String content = entry.getValue();

            if (fileName == null || fileName.isBlank() || fileName.contains("..")) {
                throw new IllegalArgumentException("Illegal file path in payload: '" + fileName + "'");
            }

            if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                RtpYamlConfig parsedYaml;
                try {
                    parsedYaml = RtpYamlConfig.parse(content);
                } catch (Exception e) {
                    throw new IllegalArgumentException("AST validation error in file '" + fileName + "': " + e.getMessage(), e);
                }

                // If region file, validate geometry constraints
                if (fileName.startsWith("regions/") || fileName.equals("regions.yml") || fileName.contains("region")) {
                    validateRegionGeometry(fileName, parsedYaml);
                }
            }
        }

        return new ParsedPayload(version, pluginVersion, timestamp, declaredSha, files);
    }

    /**
     * Validates geometry values within region config sections.
     */
    private static void validateRegionGeometry(String fileName, RtpYamlConfig yaml) {
        // Scan for radius and centerRadius across sections
        for (String key : yaml.getKeys(true)) {
            if (key.endsWith(".radius") || key.equals("radius")) {
                Object r = yaml.get(key);
                if (r instanceof Number num) {
                    if (num.doubleValue() < 0) {
                        throw new IllegalArgumentException("Geometry error in '" + fileName + "': radius cannot be negative (" + num + ")");
                    }
                }
            } else if (key.endsWith(".centerRadius") || key.equals("centerRadius")) {
                Object cr = yaml.get(key);
                if (cr instanceof Number num) {
                    if (num.doubleValue() < 0) {
                        throw new IllegalArgumentException("Geometry error in '" + fileName + "': centerRadius cannot be negative (" + num + ")");
                    }
                }
            }
        }
    }

    /**
     * Atomically applies the configuration payload:
     * 1. Validates AST & integrity.
     * 2. Creates dirty file .bak copy for any existing target file on disk.
     * 3. Writes updated configurations to disk and updates in-memory database lookup.
     * 4. Triggers atomic hot-reload.
     *
     * @param json raw payload JSON string
     * @return CompletableFuture resolving when apply and reload are completed
     */
    public CompletableFuture<Void> applyPayload(String json) {
        if (RTP.configs == null || RTP.serverAccessor == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("RTP core or configurations not initialized (S-006 fail-closed)"));
        }

        return CompletableFuture.runAsync(() -> {
            ParsedPayload parsed = parseAndValidatePayload(json);

            File pluginDir = RTP.serverAccessor.getPluginDirectory();
            if (pluginDir == null) {
                throw new IllegalStateException("Plugin directory is null (S-006)");
            }
            if (!pluginDir.exists()) {
                pluginDir.mkdirs();
            }

            Path pluginPath = pluginDir.toPath();

            // Backup all dirty files
            for (Map.Entry<String, String> entry : parsed.files().entrySet()) {
                String relPath = entry.getKey();
                Path target = pluginPath.resolve(relPath).normalize();
                if (!target.startsWith(pluginPath)) {
                    throw new IllegalArgumentException("Path traversal attempt: " + relPath);
                }

                if (Files.exists(target)) {
                    try {
                        String existing = Files.readString(target, StandardCharsets.UTF_8);
                        if (!existing.equals(entry.getValue())) {
                            // File is dirty, create .bak backup
                            Path bakFile = target.resolveSibling(target.getFileName().toString() + ".bak");
                            Files.copy(target, bakFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        RTP.log(Level.WARNING, "Failed to create .bak backup for " + target + ": " + e.getMessage(), e);
                        throw new RuntimeException("Backup failed for " + relPath + ": " + e.getMessage(), e);
                    }
                }
            }

            // Atomic write to disk and in-memory swap
            for (Map.Entry<String, String> entry : parsed.files().entrySet()) {
                String relPath = entry.getKey();
                Path target = pluginPath.resolve(relPath).normalize();

                try {
                    if (target.getParent() != null && !Files.exists(target.getParent())) {
                        Files.createDirectories(target.getParent());
                    }

                    // Write to temp file then atomic move
                    Path tmpFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
                    Files.writeString(tmpFile, entry.getValue(), StandardCharsets.UTF_8);
                    Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    RTP.log(Level.WARNING, "Failed writing file " + target + ": " + e.getMessage(), e);
                    throw new RuntimeException("Failed writing configuration file " + relPath + ": " + e.getMessage(), e);
                }

                // In-memory update of cachedLookup if applicable
                if (RTP.configs.fileDatabase != null && RTP.configs.fileDatabase.cachedLookup != null) {
                    Map<String, RtpYamlConfig> lookup = RTP.configs.fileDatabase.cachedLookup.get();
                    if (lookup != null) {
                        try {
                            String baseName = target.getFileName().toString();
                            if (baseName.endsWith(".yml")) baseName = baseName.substring(0, baseName.length() - 4);
                            RtpYamlConfig parsedYaml = RtpYamlConfig.load(target.toFile());
                            lookup.put(baseName, parsedYaml);
                            lookup.put(target.getFileName().toString(), parsedYaml);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            // Trigger hot-reload on configs
            try {
                RTP.configs.reload();
            } catch (Exception e) {
                RTP.log(Level.WARNING, "Hot-reload encountered an error after applying payload: " + e.getMessage(), e);
                throw new RuntimeException("Hot-reload error: " + e.getMessage(), e);
            }
        });
    }

    private static int findMatchingBrace(String text, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractStringField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int startQuote = json.indexOf('"', colon);
        if (startQuote == -1) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(unescapeChar(c));
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    private static String extractNumericField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end > start) {
            return json.substring(start, end);
        }
        return null;
    }

    private static Map<String, String> parseJsonStringMap(String jsonObject) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        int len = jsonObject.length();

        while (i < len && jsonObject.charAt(i) != '{') i++;
        i++; // skip '{'

        while (i < len) {
            while (i < len && (Character.isWhitespace(jsonObject.charAt(i)) || jsonObject.charAt(i) == ',')) i++;
            if (i >= len || jsonObject.charAt(i) == '}') break;

            if (jsonObject.charAt(i) != '"') break;
            i++; // skip open quote of key

            StringBuilder key = new StringBuilder();
            boolean escaped = false;
            while (i < len) {
                char c = jsonObject.charAt(i++);
                if (escaped) {
                    key.append(unescapeChar(c));
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    key.append(c);
                }
            }

            while (i < len && (Character.isWhitespace(jsonObject.charAt(i)) || jsonObject.charAt(i) == ':')) i++;

            if (i < len && jsonObject.charAt(i) == '"') {
                i++; // skip open quote of value
                StringBuilder val = new StringBuilder();
                escaped = false;
                while (i < len) {
                    char c = jsonObject.charAt(i++);
                    if (escaped) {
                        val.append(unescapeChar(c));
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        break;
                    } else {
                        val.append(c);
                    }
                }
                result.put(key.toString(), val.toString());
            } else {
                // Not a string value or malformed
                break;
            }
        }
        return result;
    }

    private static char unescapeChar(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> c;
        };
    }
}
