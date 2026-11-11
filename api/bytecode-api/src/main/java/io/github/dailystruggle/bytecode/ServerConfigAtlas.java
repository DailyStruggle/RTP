package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.search.FuzzySearchEngine;
import io.github.dailystruggle.search.OnlineQueryExpander;
import io.github.dailystruggle.search.SearchThesaurus;
import io.github.dailystruggle.yaml.YamlConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-memory cross-plugin configuration AST, entity-binding, conflict, and search atlas
 * ({@code leafconfig-ADR-001} Section 3 &amp; Section 4).
 *
 * <p>Indexes {@link JarConfigScanner.HarvestedArtifact} Plane 1 JAR baselines alongside Plane 2
 * live disk configuration files into {@link AstConfigNode}, {@link AstEntityBinding},
 * {@link AstConfigEdge}, and {@link SpatialGizmoDescriptor} records, and serializes the unified
 * Web IDE workspace payload.
 *
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @governedBy leafconfig-ADR-001
 * @governedBy leafconfig-ADR-002
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-CFG-ATLAS-002
 * @trace REQ-CFG-ATLAS-003
 */
public final class ServerConfigAtlas {

    /** Cross-file / cross-plugin AST edge predicate ({@code leafconfig-ADR-001} Section 3.4). */
    public enum EdgePredicate {
        INHERITS_FROM_KEY,
        COUPLED_BOUND,
        CONFLICTS_WITH_KEY,
        READ_BY_CLASS,
        REMEDIATED_BY
    }

    /** Cross-file entity binding kind ({@code leafconfig-ADR-001} Section 3.3). */
    public enum EntityKind {
        WORLD,
        REGION,
        PERMISSION_GROUP,
        MATERIAL,
        BIOME,
        FILE_REF
    }

    /** Role of a config node in an entity binding. */
    public enum BindingRole {
        DEFINES,
        REFERENCES
    }

    /**
     * Indexed configuration AST leaf node (`ast_config_nodes` table parity).
     *
     * @param symbolId        canonical symbol ID ({@code cfg:<artifact_id>:<file_path>#<key_path>})
     * @param artifactId      owning artifact ID (e.g. {@code plugin:leafrtp}, {@code engine:paper})
     * @param filePath        relative configuration file path
     * @param keyPath         dotted key path
     * @param parentPrefix    dotted parent prefix, or empty string for top-level keys
     * @param leafName        unqualified leaf key name
     * @param jarDefaultValue pristine Plane 1 default value as string, or {@code null}
     * @param liveDiskValue   live Plane 2 value as string
     * @param isModified      whether {@code liveDiskValue} differs from {@code jarDefaultValue}
     * @param commentText     resolved comment prose (from live disk or restored from Plane 1 JAR)
     * @param commentRestored whether the comment was missing on disk and restored from the Plane 1 JAR
     * @param schema          inferred schema, provenance, reload tier, and spatial gizmo role
     * @param readByClasses   Java classes discovered reading this key via Plane 1 bytecode scanning
     */
    public record AstConfigNode(
            String symbolId,
            String artifactId,
            String filePath,
            String keyPath,
            String parentPrefix,
            String leafName,
            String jarDefaultValue,
            String liveDiskValue,
            boolean isModified,
            String commentText,
            boolean commentRestored,
            SchemaInferrer.InferredSchema schema,
            List<String> readByClasses,
            SchemaInferrer.InvestigatoryDocResult investigatoryDoc
    ) {
        public AstConfigNode(
                String symbolId,
                String artifactId,
                String filePath,
                String keyPath,
                String parentPrefix,
                String leafName,
                String jarDefaultValue,
                String liveDiskValue,
                boolean isModified,
                String commentText,
                boolean commentRestored,
                SchemaInferrer.InferredSchema schema,
                List<String> readByClasses
        ) {
            this(
                    symbolId,
                    artifactId,
                    filePath,
                    keyPath,
                    parentPrefix,
                    leafName,
                    jarDefaultValue,
                    liveDiskValue,
                    isModified,
                    commentText,
                    commentRestored,
                    schema,
                    readByClasses,
                    SchemaInferrer.synthesizeInvestigatoryDoc(
                            artifactId,
                            filePath,
                            keyPath,
                            liveDiskValue,
                            commentText,
                            schema,
                            readByClasses,
                            List.of(),
                            1,
                            null
                    )
            );
        }

        public AstConfigNode {
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(filePath, "filePath");
            Objects.requireNonNull(keyPath, "keyPath");
            parentPrefix = parentPrefix == null ? "" : parentPrefix;
            leafName = leafName == null ? keyPath : leafName;
            commentText = commentText == null ? "" : commentText;
            Objects.requireNonNull(schema, "schema");
            readByClasses = readByClasses == null ? List.of() : List.copyOf(readByClasses);
            if (investigatoryDoc == null) {
                investigatoryDoc = SchemaInferrer.synthesizeInvestigatoryDoc(
                        artifactId,
                        filePath,
                        keyPath,
                        liveDiskValue,
                        commentText,
                        schema,
                        readByClasses,
                        List.of(),
                        1,
                        null
                );
            }
        }

        /**
         * Returns whether this configuration node holds a secret, credential, token, or world seed
         * that must be redacted from external LLM prompts and public knowledge exports.
         */
        public boolean secret() {
            if (SECRET_KEY_RE.matcher(keyPath).find()) {
                return true;
            }
            String val = liveDiskValue != null ? liveDiskValue.trim() : "";
            return isHighEntropySecretValue(val);
        }
    }

    private static final java.util.regex.Pattern SECRET_KEY_RE = java.util.regex.Pattern.compile(
            "(?i)(?:secret|password|passwd|token|private[-_]?key|api[-_]?key|webhook|credential|forwarding[-_]?secret|(?<![a-z])seed(?![a-z])|(?<![a-z])salt(?![a-z]))"
    );

    private static boolean isHighEntropySecretValue(String s) {
        if (s == null || s.length() < 28 || s.contains(" ") || s.contains("/")) {
            return false;
        }
        int digits = 0;
        int upper = 0;
        int lower = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) digits++;
            else if (Character.isUpperCase(c)) upper++;
            else if (Character.isLowerCase(c)) lower++;
        }
        return digits >= 4 && upper >= 4 && lower >= 4;
    }

    /**
     * Cross-file entity reference or definition (`ast_entity_bindings` table parity).
     */
    public record AstEntityBinding(
            EntityKind entityKind,
            String entityValue,
            String symbolId,
            BindingRole role
    ) {
        public AstEntityBinding {
            Objects.requireNonNull(entityKind, "entityKind");
            Objects.requireNonNull(entityValue, "entityValue");
            Objects.requireNonNull(symbolId, "symbolId");
            Objects.requireNonNull(role, "role");
        }
    }

    /**
     * Directed AST edge between configuration keys, Java classes, or lag symptoms (`ast_edges` parity).
     */
    public record AstConfigEdge(
            String sourceId,
            EdgePredicate predicate,
            String targetId,
            String detail
    ) {
        public AstConfigEdge {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(targetId, "targetId");
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Interactive 2D Map gizmo synthesized from a plugin's spatial configuration keys.
     *
     * @param gizmoId    unique gizmo identifier ({@code <artifactId>:<filePath>#<scopePrefix>})
     * @param artifactId owning plugin/engine artifact ID
     * @param filePath   config file path
     * @param scopeKey   parent YAML section path grouping the spatial coordinates
     * @param worldName  associated world name (defaulting to {@code world})
     * @param centerX    center X block coordinate
     * @param centerZ    center Z block coordinate
     * @param radius     outer radius in blocks ({@code 0} for point gizmos)
     * @param innerRadius inner radius in blocks ({@code 0} when solid)
     * @param keyMapping map of gizmo role ({@code CENTER_X}, {@code CENTER_Z}, {@code RADIUS}, etc.) to dotted YAML key path
     */
    public record SpatialGizmoDescriptor(
            String gizmoId,
            String artifactId,
            String filePath,
            String scopeKey,
            String worldName,
            double centerX,
            double centerZ,
            double radius,
            double innerRadius,
            Map<String, String> keyMapping
    ) {
        public SpatialGizmoDescriptor {
            Objects.requireNonNull(gizmoId, "gizmoId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(filePath, "filePath");
            scopeKey = scopeKey == null ? "" : scopeKey;
            worldName = (worldName == null || worldName.isBlank()) ? "world" : worldName;
            keyMapping = keyMapping == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(keyMapping));
        }
    }

    /**
     * Ranked search hit returned by {@link #search(String, Map, int)}.
     */
    public record ConfigSearchHit(
            AstConfigNode node,
            int score,
            List<String> reasons
    ) {
        public ConfigSearchHit {
            Objects.requireNonNull(node, "node");
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    private final Map<String, JarConfigScanner.HarvestedArtifact> artifacts = new LinkedHashMap<>();
    private final Map<String, AstConfigNode> nodesBySymbol = new LinkedHashMap<>();
    private final Map<String, SchemaInferrer.InvestigatoryDocResult> investigatoryCache = new LinkedHashMap<>();
    private final List<AstEntityBinding> entityBindings = new ArrayList<>();
    private final List<AstConfigEdge> edges = new ArrayList<>();
    private final List<SpatialGizmoDescriptor> spatialGizmos = new ArrayList<>();
    private LlmConfigDocSynthesizer.LlmHookConfig activeLlmHookConfig = LlmConfigDocSynthesizer.LlmHookConfig.disabled();

    public LlmConfigDocSynthesizer.LlmHookConfig activeLlmHookConfig() {
        return activeLlmHookConfig;
    }

    public void setActiveLlmHookConfig(LlmConfigDocSynthesizer.LlmHookConfig llmHookConfig) {
        this.activeLlmHookConfig = llmHookConfig == null ? LlmConfigDocSynthesizer.LlmHookConfig.disabled() : llmHookConfig;
    }

    /**
     * Registers a harvested artifact and indexes its Plane 1 defaults alongside Plane 2 live disk YAML files.
     *
     * @param artifact     harvested Plane 1 artifact metadata and defaults
     * @param liveDiskYaml map of relative file path to live disk YAML content (may be empty)
     */
    public void registerArtifact(
            JarConfigScanner.HarvestedArtifact artifact,
            Map<String, String> liveDiskYaml
    ) {
        Objects.requireNonNull(artifact, "artifact");
        artifacts.put(artifact.artifactId(), artifact);

        Map<String, String> safeLive = liveDiskYaml == null ? Map.of() : liveDiskYaml;
        Set<String> allFiles = new LinkedHashSet<>();
        allFiles.addAll(artifact.embeddedConfigs().keySet());
        allFiles.addAll(safeLive.keySet());

        for (String relPath : allFiles) {
            if (!isSupportedConfigFile(relPath)) {
                continue;
            }
            if (relPath.startsWith("defaults/") && safeLive.containsKey(relPath.substring("defaults/".length()))) {
                continue;
            }
            String defaultText = artifact.embeddedConfigs().get(relPath);
            if (defaultText == null) {
                defaultText = artifact.embeddedConfigs().get("defaults/" + relPath);
            }
            String liveText = safeLive.getOrDefault(relPath, defaultText);
            if (liveText == null || liveText.isBlank()) {
                continue;
            }
            indexYamlFile(artifact, relPath, defaultText, liveText);
        }

        boolean hasAnyIndexedNode = nodesBySymbol.values().stream()
                .anyMatch(n -> artifact.artifactId().equals(n.artifactId()));
        if (!artifact.keyCallSiteIndex().isEmpty()) {
            indexBytecodeOnlyKeys(artifact, hasAnyIndexedNode);
        }

        rebuildCrossFileEdgesAndSpatialGizmos(artifact.artifactId());
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ServerConfigAtlas.class.getName());

    private static volatile ServerConfigAtlas latestSharedAtlas;

    /**
     * Returns the most recently scanned {@link ServerConfigAtlas} in this JVM process, allowing
     * sibling plugins such as {@code LeafGuide} to consume {@code LeafConfig}'s single-pass JAR
     * scan without re-opening archive files ({@code leafguide-ADR-001} Section 1).
     */
    public static ServerConfigAtlas latestSharedAtlas() {
        return latestSharedAtlas;
    }

    /** Publishes a scanned {@link ServerConfigAtlas} for in-process sibling consumers. */
    public static void publishSharedAtlas(ServerConfigAtlas atlas) {
        latestSharedAtlas = atlas;
    }

    public static final List<String> DEFAULT_ENGINE_CONFIG_FILES = List.of(
            "server.properties", "bukkit.yml", "spigot.yml", "paper-global.yml", "paper-world-defaults.yml",
            "purpur.yml", "pufferfish.yml", "folia.yml", "leaf.yml", "leaves.yml",
            "commands.yml", "permissions.yml", "help.yml", "velocity.toml", "bungeecord.yml", "waterfall.yml",
            "config/paper-global.yml", "config/paper-world-defaults.yml",
            "config/leaf-global.yml", "config/leaf-world-defaults.yml",
            "config/folia-global.yml", "config/folia-world-defaults.yml"
    );

    private static final Set<String> NON_WORLD_ROOT_DIRS = Set.of(
            "plugins", "mods", "config", "logs", "libraries", "versions", "cache", "crash-reports", "bundler", "debug"
    );

    /**
     * Scans a server root directory (`server.properties`, `spigot.yml`, `paper-global.yml`,
     * `plugins/*.jar`, and `plugins/<Plugin>/*.yml`) off-thread into a populated {@link ServerConfigAtlas}.
     *
     * @param serverRoot server working directory
     * @return populated config atlas
     * @throws IOException if directory traversal fails
     */
    public static ServerConfigAtlas scanServerDirectory(Path serverRoot) throws IOException {
        return scanServerDirectory(serverRoot, DEFAULT_ENGINE_CONFIG_FILES);
    }

    /**
     * Scans a server root directory using a caller-supplied list of candidate engine configuration files.
     *
     * @param serverRoot           server working directory
     * @param candidateEngineNames relative paths of engine YAML files to harvest
     * @return populated config atlas
     * @throws IOException if directory traversal fails
     */
    public static ServerConfigAtlas scanServerDirectory(Path serverRoot, List<String> candidateEngineNames) throws IOException {
        Objects.requireNonNull(serverRoot, "serverRoot");
        List<String> engineNames = (candidateEngineNames == null || candidateEngineNames.isEmpty())
                ? DEFAULT_ENGINE_CONFIG_FILES
                : candidateEngineNames;
        ServerConfigAtlas atlas = new ServerConfigAtlas();

        // 1. Scan engine YAML/properties/TOML/JSON files at root, config/, and per-world directories
        Map<String, String> engineFiles = new LinkedHashMap<>();
        for (String name : engineNames) {
            Path p = serverRoot.resolve(name);
            if (Files.isRegularFile(p)) {
                engineFiles.put(name, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        if (candidateEngineNames == null || candidateEngineNames == DEFAULT_ENGINE_CONFIG_FILES) {
            try (Stream<Path> rootStream = Files.list(serverRoot)) {
                List<Path> rootEntries = rootStream.sorted().toList();
                for (Path entry : rootEntries) {
                    String fn = entry.getFileName().toString();
                    String low = fn.toLowerCase(Locale.ROOT);
                    if (Files.isRegularFile(entry)) {
                        if (!fn.startsWith(".")
                                && (low.endsWith(".yml") || low.endsWith(".yaml")
                                || low.endsWith(".properties") || low.endsWith(".toml") || low.endsWith(".conf"))
                                && !engineFiles.containsKey(fn)) {
                            engineFiles.put(fn, Files.readString(entry, StandardCharsets.UTF_8));
                        }
                    } else if (Files.isDirectory(entry) && !fn.startsWith(".") && !NON_WORLD_ROOT_DIRS.contains(low)) {
                        for (String worldCfg : List.of("paper-world.yml", "spigot.yml", "purpur.yml", "leaf-world.yml")) {
                            Path wp = entry.resolve(worldCfg);
                            if (Files.isRegularFile(wp)) {
                                String relKey = fn + "/" + worldCfg;
                                engineFiles.putIfAbsent(relKey, Files.readString(wp, StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }
        }
        if (!engineFiles.isEmpty()) {
            JarConfigScanner.HarvestedArtifact engineArtifact = new JarConfigScanner.HarvestedArtifact(
                    "engine:paper",
                    "Paper / Spigot Engine",
                    "latest",
                    "engine",
                    "",
                    "https://docs.papermc.io/",
                    List.of("paper", "spigot"),
                    List.of(),
                    List.of(),
                    JarConfigScanner.ReloadStrategy.DEFER_UNTIL_SHUTDOWN,
                    engineFiles,
                    Map.of(),
                    Map.of(),
                    false
            );
            atlas.registerArtifact(engineArtifact, engineFiles);
        }

        // 2. Scan plugins/*.jar and corresponding plugins/<PluginName>/**/* plus standalone plugin folders
        Path pluginsDir = serverRoot.resolve("plugins");
        if (Files.isDirectory(pluginsDir)) {
            Set<Path> claimedPluginDirs = new LinkedHashSet<>();
            try (Stream<Path> stream = Files.list(pluginsDir)) {
                List<Path> jars = stream
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .toList();
                for (Path jar : jars) {
                    JarConfigScanner.HarvestedArtifact art = JarConfigScanner.scanJar(jar);
                    Path dataDir = resolvePluginDataDirectory(pluginsDir, art, jar);
                    if (dataDir != null) {
                        claimedPluginDirs.add(dataDir.toAbsolutePath().normalize());
                    }
                    Map<String, String> liveFiles = readLiveYamlTree(dataDir);
                    atlas.registerArtifact(art, liveFiles);
                }
            }
            try (Stream<Path> dirStream = Files.list(pluginsDir)) {
                List<Path> subDirs = dirStream
                        .filter(Files::isDirectory)
                        .filter(d -> {
                            String dn = d.getFileName().toString();
                            String low = dn.toLowerCase(Locale.ROOT);
                            return !dn.startsWith(".")
                                    && !"update".equals(low)
                                    && !"bstats".equals(low)
                                    && !"spark".equals(low)
                                    && !claimedPluginDirs.contains(d.toAbsolutePath().normalize());
                        })
                        .sorted()
                        .toList();
                for (Path unclaimedDir : subDirs) {
                    String folderName = unclaimedDir.getFileName().toString();
                    String artifactId = "plugin:" + folderName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                    if (atlas.artifacts().containsKey(artifactId)) {
                        continue;
                    }
                    Map<String, String> liveFiles = readLiveYamlTree(unclaimedDir);
                    if (!liveFiles.isEmpty()) {
                        JarConfigScanner.HarvestedArtifact folderArtifact = new JarConfigScanner.HarvestedArtifact(
                                artifactId,
                                folderName,
                                "disk",
                                "paper",
                                "",
                                "",
                                List.of(),
                                List.of(),
                                List.of(),
                                JarConfigScanner.ReloadStrategy.BUKKIT_FILECONFIG_INJECT,
                                liveFiles,
                                Map.of(),
                                Map.of(),
                                false
                        );
                        atlas.registerArtifact(folderArtifact, liveFiles);
                    }
                }
            }
        }

        // 3. Scan mods/*.jar (if present) in the same single pass for mod configs & embedded guidebooks
        Path modsDir = serverRoot.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            Path configDir = serverRoot.resolve("config");
            try (Stream<Path> stream = Files.list(modsDir)) {
                List<Path> jars = stream
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .toList();
                for (Path jar : jars) {
                    JarConfigScanner.HarvestedArtifact art = JarConfigScanner.scanJar(jar);
                    Path modDataDir = configDir.resolve(art.displayName());
                    Map<String, String> modLiveFiles = new LinkedHashMap<>(readLiveYamlTree(modDataDir));
                    if (Files.isDirectory(configDir)) {
                        for (String embeddedKey : art.embeddedConfigs().keySet()) {
                            String cleanKey = embeddedKey.startsWith("defaults/")
                                    ? embeddedKey.substring("defaults/".length())
                                    : embeddedKey;
                            Path candidate = configDir.resolve(cleanKey);
                            if (Files.isRegularFile(candidate)) {
                                modLiveFiles.putIfAbsent(cleanKey, Files.readString(candidate, StandardCharsets.UTF_8));
                            }
                        }
                    }
                    atlas.registerArtifact(art, modLiveFiles);
                }
            }
        }
        latestSharedAtlas = atlas;
        return atlas;
    }

    private static Path resolvePluginDataDirectory(
            Path pluginsDir,
            JarConfigScanner.HarvestedArtifact art,
            Path jarPath
    ) throws IOException {
        Path direct = pluginsDir.resolve(art.displayName());
        if (Files.isDirectory(direct)) {
            return direct;
        }
        String displayNorm = art.displayName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String idStem = art.artifactId().replaceFirst("^plugin:", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String jarFileName = jarPath.getFileName().toString();
        String jarStem = stripVersionSuffix(jarFileName.replaceFirst("(?i)\\.jar$", ""))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        try (Stream<Path> s = Files.list(pluginsDir)) {
            for (Path candidate : s.filter(Files::isDirectory).sorted().toList()) {
                String folderNorm = candidate.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (folderNorm.isEmpty()) continue;
                if (folderNorm.equals(displayNorm) || folderNorm.equals(idStem) || folderNorm.equals(jarStem)) {
                    return candidate;
                }
            }
        }
        return direct;
    }
    public static boolean isSupportedConfigFile(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return false;
        }
        String low = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (low.startsWith("lang/") || low.startsWith("locale/") || low.startsWith("locales/")
                || low.startsWith("translations/") || low.startsWith("i18n/")
                || low.startsWith("editor/") || low.startsWith("workspace/")
                || low.contains("/lang/") || low.contains("/locale/") || low.contains("/locales/")
                || low.contains("/translations/") || low.contains("/i18n/")
                || low.contains("/editor/") || low.contains("/workspace/")) {
            return false;
        }
        String fileName = low.contains("/") ? low.substring(low.lastIndexOf('/') + 1) : low;
        int dotIdx = fileName.indexOf('.');
        boolean isLocaleStem = (dotIdx == 5 || dotIdx == 6)
                && (fileName.charAt(2) == '_' || (dotIdx == 6 && fileName.charAt(3) == '_'));
        if ("editor-data.json".equals(fileName) || "usercache.json".equals(fileName)
                || "whitelist.json".equals(fileName) || "ops.json".equals(fileName)
                || "banned-players.json".equals(fileName) || "banned-ips.json".equals(fileName)
                || "version_history.json".equals(fileName)
                || isLocaleStem
                || (fileName.endsWith(".properties") && (fileName.startsWith("messages_") || fileName.startsWith("lang_")))) {
            return false;
        }
        return fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".toml")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".json")
                || fileName.endsWith(".jsonc")
                || fileName.endsWith(".json5")
                || fileName.endsWith(".conf");
    }
    private static Map<String, String> readLiveYamlTree(Path dataDir) throws IOException {
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dataDir, 4)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return isSupportedConfigFile(n) && !n.startsWith(".");
                    })
                    .sorted()
                    .toList();
            for (Path f : files) {
                String rel = dataDir.relativize(f).toString().replace('\\', '/');
                if (!isSupportedConfigFile(rel)) continue;
                out.put(rel, Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * Alias for {@link #parseConfigDocumentToYaml(String, String)}.
     */
    public static YamlConfig parseAnyConfigToYaml(String filePath, String rawText) {
        return parseConfigDocumentToYaml(filePath, rawText);
    }

    /**
     * Parses a `.yml`/`.yaml`, `.toml`, `.properties`, `.json`, `.jsonc`, `.json5`, or `.conf` configuration text
     * into a normalized {@link YamlConfig} tree with preserved block comments for unified AST schema inference.
     */
    public static YamlConfig parseConfigDocumentToYaml(String filePath, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return YamlConfig.empty(null);
        }
        String low = filePath == null ? "config.yml" : filePath.toLowerCase(Locale.ROOT);
        if (low.endsWith(".properties")) {
            return parsePropertiesToYaml(rawText);
        }
        if (low.endsWith(".toml")) {
            return parseTomlToYaml(rawText);
        }
        if (low.endsWith(".json") || low.endsWith(".jsonc") || low.endsWith(".json5") || low.endsWith(".conf")) {
            return parseJsonOrHoconToYaml(rawText);
        }
        return YamlConfig.parse(rawText);
    }

    private static YamlConfig parseJsonOrHoconToYaml(String rawText) {
        YamlConfig cfg = YamlConfig.empty(null);
        String[] lines = rawText.split("\r?\n", -1);
        List<String> pathStack = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();
        boolean inBlockComment = false;
        String activeArrayKey = null;
        List<Object> activeArrayItems = new ArrayList<>();
        List<String> activeArrayComments = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (inBlockComment) {
                int endIdx = trimmed.indexOf("*/");
                String cmtBody = endIdx >= 0 ? trimmed.substring(0, endIdx) : trimmed;
                String cleaned = cleanJsonCommentProse(cmtBody);
                if (!cleaned.isEmpty()) {
                    pendingComments.add("# " + cleaned);
                }
                if (endIdx < 0) {
                    continue;
                }
                inBlockComment = false;
                trimmed = trimmed.substring(endIdx + 2).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
            }
            while (trimmed.startsWith("/*")) {
                int endIdx = trimmed.indexOf("*/", 2);
                String cmtBody = endIdx >= 0 ? trimmed.substring(2, endIdx) : trimmed.substring(2);
                String cleaned = cleanJsonCommentProse(cmtBody);
                if (!cleaned.isEmpty()) {
                    pendingComments.add("# " + cleaned);
                }
                if (endIdx < 0) {
                    inBlockComment = true;
                    trimmed = "";
                    break;
                }
                trimmed = trimmed.substring(endIdx + 2).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                String cmt = trimmed.startsWith("//") ? ("#" + trimmed.substring(2)) : trimmed;
                pendingComments.add(cmt);
                continue;
            }
            String[] codeAndComment = splitUnquotedJsonLineComment(trimmed);
            trimmed = codeAndComment[0].trim();
            String inlineComment = codeAndComment[1];
            if (!inlineComment.isEmpty()) {
                String cmt = inlineComment.startsWith("//")
                        ? ("#" + inlineComment.substring(2))
                        : (inlineComment.startsWith("#") ? inlineComment : ("# " + inlineComment));
                pendingComments.add(cmt);
            }
            if (trimmed.isEmpty() || "{".equals(trimmed)) {
                continue;
            }

            if (activeArrayKey != null) {
                if (trimmed.startsWith("]")) {
                    cfg.set(activeArrayKey, new ArrayList<>(activeArrayItems));
                    if (!activeArrayComments.isEmpty()) {
                        cfg.setComment(activeArrayKey, String.join("\n", activeArrayComments));
                    }
                    activeArrayKey = null;
                    activeArrayItems.clear();
                    activeArrayComments.clear();
                    pendingComments.clear();
                    continue;
                }
                String elem = trimmed.endsWith(",") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
                if (!elem.isEmpty() && !elem.startsWith("{") && !elem.startsWith("}")) {
                    activeArrayItems.add(parseLooseScalarOrList(elem));
                }
                continue;
            }

            if (trimmed.equals("}") || trimmed.equals("},") || trimmed.equals("]") || trimmed.equals("],")) {
                if (trimmed.startsWith("}") && !pathStack.isEmpty()) {
                    pathStack.remove(pathStack.size() - 1);
                }
                pendingComments.clear();
                continue;
            }
            String[] activeArrayKeyHolder = new String[]{null};
            parseJsonOrHoconKeyValueSegment(
                    trimmed,
                    pathStack,
                    pendingComments,
                    cfg,
                    activeArrayKeyHolder,
                    activeArrayItems,
                    activeArrayComments
            );
            if (activeArrayKeyHolder[0] != null) {
                activeArrayKey = activeArrayKeyHolder[0];
            }
        }
        return cfg;
    }

    private static void parseJsonOrHoconKeyValueSegment(
            String segment,
            List<String> pathStack,
            List<String> pendingComments,
            YamlConfig cfg,
            String[] activeArrayKeyHolder,
            List<Object> activeArrayItems,
            List<String> activeArrayComments
    ) {
        int sepIdx = findUnquotedJsonSeparator(segment);
        if (sepIdx <= 0) {
            return;
        }
        String rawKey = unquoteJsonToken(segment.substring(0, sepIdx).trim());
        String afterSep = segment.substring(sepIdx + 1).trim();
        if (afterSep.endsWith(",")) {
            afterSep = afterSep.substring(0, afterSep.length() - 1).trim();
        }
        if (rawKey.isEmpty()) {
            return;
        }
        String fullKey = pathStack.isEmpty() ? rawKey : (String.join(".", pathStack) + "." + rawKey);
        if ("{".equals(afterSep)) {
            cfg.createSection(fullKey);
            if (!pendingComments.isEmpty()) {
                cfg.setComment(fullKey, String.join("\n", pendingComments));
                pendingComments.clear();
            }
            pathStack.add(rawKey);
            return;
        }
        if ("{}".equals(afterSep)) {
            cfg.createSection(fullKey);
            pendingComments.clear();
            return;
        }
        if (afterSep.startsWith("{") && afterSep.endsWith("}")) {
            cfg.createSection(fullKey);
            if (!pendingComments.isEmpty()) {
                cfg.setComment(fullKey, String.join("\n", pendingComments));
                pendingComments.clear();
            }
            String inner = afterSep.substring(1, afterSep.length() - 1).trim();
            if (!inner.isEmpty()) {
                pathStack.add(rawKey);
                for (String entryPart : splitTopLevelCommas(inner)) {
                    String partTrimmed = entryPart.trim();
                    if (!partTrimmed.isEmpty()) {
                        parseJsonOrHoconKeyValueSegment(
                                partTrimmed,
                                pathStack,
                                pendingComments,
                                cfg,
                                activeArrayKeyHolder,
                                activeArrayItems,
                                activeArrayComments
                        );
                    }
                }
                pathStack.remove(pathStack.size() - 1);
            }
            return;
        }
        if ("[".equals(afterSep)) {
            if (activeArrayKeyHolder != null) {
                activeArrayKeyHolder[0] = fullKey;
                activeArrayItems.clear();
                activeArrayComments.clear();
                activeArrayComments.addAll(pendingComments);
            }
            pendingComments.clear();
            return;
        }
        cfg.set(fullKey, parseLooseScalarOrList(afterSep));
        if (!pendingComments.isEmpty()) {
            cfg.setComment(fullKey, String.join("\n", pendingComments));
            pendingComments.clear();
        }
    }

    private static String cleanJsonCommentProse(String raw) {
        String s = raw.trim();
        while (s.startsWith("*") || s.startsWith("/") || s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static String unquoteJsonToken(String token) {
        if (token.length() >= 2 && ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'")))) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private static int findUnquotedJsonSeparator(String s) {
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle && (c == ':' || c == '=')) {
                return i;
            }
        }
        return -1;
    }

    private static String[] splitUnquotedJsonLineComment(String s) {
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle) {
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    return new String[]{s.substring(0, i), s.substring(i)};
                }
                if (c == '#') {
                    return new String[]{s.substring(0, i), s.substring(i)};
                }
            }
        }
        return new String[]{s, ""};
    }

    private static List<String> splitTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        boolean inDouble = false;
        boolean inSingle = false;
        int braceDepth = 0;
        int bracketDepth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth = Math.max(0, braceDepth - 1);
                else if (c == '[') bracketDepth++;
                else if (c == ']') bracketDepth = Math.max(0, bracketDepth - 1);
                else if (c == ',' && braceDepth == 0 && bracketDepth == 0) {
                    parts.add(s.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start <= s.length()) {
            parts.add(s.substring(start));
        }
        return parts;
    }

    private static YamlConfig parsePropertiesToYaml(String rawText) {
        YamlConfig cfg = YamlConfig.empty(null);
        String[] lines = rawText.split("\r?\n", -1);
        List<String> pendingComments = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                pendingComments.clear();
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                pendingComments.add("#" + trimmed.substring(1));
                continue;
            }
            int sepIdx = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '\\' && i + 1 < trimmed.length()) {
                    i++;
                    continue;
                }
                if (c == '=' || c == ':') {
                    sepIdx = i;
                    break;
                }
            }
            if (sepIdx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, sepIdx).trim();
            String val = trimmed.substring(sepIdx + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            cfg.set(key, parseLooseScalarOrList(val));
            if (!pendingComments.isEmpty()) {
                cfg.setComment(key, String.join("\n", pendingComments));
                pendingComments.clear();
            }
        }
        return cfg;
    }

    private static YamlConfig parseTomlToYaml(String rawText) {
        YamlConfig cfg = YamlConfig.empty(null);
        String[] lines = rawText.split("\r?\n", -1);
        List<String> pendingComments = new ArrayList<>();
        String currentSection = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                pendingComments.clear();
                continue;
            }
            if (trimmed.startsWith("#")) {
                pendingComments.add(trimmed);
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.contains("]")) {
                int close = trimmed.lastIndexOf(']');
                String header = stripBrackets(trimmed.substring(0, close + 1)).trim();
                currentSection = header.replace("\"", "").replace("'", "");
                pendingComments.clear();
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String rawKey = trimmed.substring(0, eq).trim().replace("\"", "").replace("'", "");
            String afterEq = trimmed.substring(eq + 1).trim();
            String valOnly = stripUnquotedComment(afterEq).trim();
            String inlineComment = afterEq.substring(stripUnquotedComment(afterEq).length()).trim();
            if (!inlineComment.isEmpty() && inlineComment.startsWith("#")) {
                pendingComments.add(inlineComment);
            }
            if (rawKey.isEmpty()) {
                continue;
            }
            String fullKey = currentSection.isEmpty() ? rawKey : (currentSection + "." + rawKey);
            cfg.set(fullKey, parseLooseScalarOrList(valOnly));
            if (!pendingComments.isEmpty()) {
                cfg.setComment(fullKey, String.join("\n", pendingComments));
                pendingComments.clear();
            }
        }
        return cfg;
    }

    private static String stripUnquotedComment(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle && (i == 0 || s.charAt(i - 1) != '\\')) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) return s.substring(0, i);
        }
        return s;
    }

    private static Object parseLooseScalarOrList(String rawVal) {
        if (rawVal == null) {
            return "";
        }
        String t = rawVal.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        if (t.startsWith("[") && t.endsWith("]")) {
            String inner = t.substring(1, t.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            List<Object> items = new ArrayList<>();
            for (String part : inner.split(",")) {
                items.add(parseLooseScalarOrList(part.trim()));
            }
            return items;
        }
        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        String numClean = t.replace("_", "");
        if (numClean.matches("[-+]?\\d{1,18}")) {
            long lv = Long.parseLong(numClean);
            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) {
                return (int) lv;
            }
            return lv;
        }
        if (numClean.matches("[-+]?\\d{0,18}\\.\\d{1,18}([eE][-+]?\\d{1,3})?")) {
            return Double.parseDouble(numClean);
        }
        return t;
    }

    private void indexYamlFile(
            JarConfigScanner.HarvestedArtifact artifact,
            String filePath,
            String defaultYamlText,
            String liveYamlText
    ) {
        YamlConfig defCfg;
        YamlConfig liveCfg;
        try {
            defCfg = defaultYamlText != null && !defaultYamlText.isBlank()
                    ? parseConfigDocumentToYaml(filePath, defaultYamlText)
                    : YamlConfig.empty(null);
            liveCfg = parseConfigDocumentToYaml(filePath, liveYamlText);
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "Failed to parse config file " + filePath + " for artifact " + artifact.artifactId(), e);
            return;
        }

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(defCfg.getKeys(true));
        allKeys.addAll(liveCfg.getKeys(true));

        for (String keyPath : allKeys) {
            if (liveCfg.isConfigurationSection(keyPath) || defCfg.isConfigurationSection(keyPath)) {
                continue;
            }
            Object defValObj = defCfg.contains(keyPath) ? defCfg.get(keyPath) : null;
            Object liveValObj = liveCfg.contains(keyPath) ? liveCfg.get(keyPath) : defValObj;

            String defValStr = formatScalarOrList(defValObj);
            String liveValStr = formatScalarOrList(liveValObj);
            boolean modified = defValStr != null && !Objects.equals(defValStr, liveValStr);

            String liveComment = liveCfg.getComment(keyPath);
            String defComment = defCfg.getComment(keyPath);
            boolean restored = (liveComment == null || liveComment.isBlank())
                    && defComment != null && !defComment.isBlank();
            String effectiveComment = restored ? defComment : (liveComment != null ? liveComment : "");
            List<String> commentLines = effectiveComment.isEmpty()
                    ? List.of()
                    : Arrays.asList(effectiveComment.split("\n"));

            Set<String> consumers = findConsumersForKey(artifact.readByClassIndex(), keyPath);
            List<BytecodeConfigScanner.KeyCallSite> callSites = findCallSitesForKey(artifact.keyCallSiteIndex(), keyPath);
            Object effectiveValObj = liveValObj != null ? liveValObj : defValObj;
            List<String> bytecodeOptions = resolveMatchingEnumConstants(
                    artifact.discoveredEnums(),
                    callSites,
                    keyPath,
                    effectiveValObj,
                    !consumers.isEmpty()
            );

            SchemaInferrer.InferredSchema schema = SchemaInferrer.infer(
                    keyPath,
                    effectiveValObj,
                    commentLines,
                    bytecodeOptions,
                    !artifact.docSourceUrl().isBlank()
            );

            int dot = keyPath.lastIndexOf('.');
            String parentPrefix = dot >= 0 ? keyPath.substring(0, dot) : "";
            String leafName = dot >= 0 ? keyPath.substring(dot + 1) : keyPath;
            String symbolId = "cfg:" + artifact.artifactId() + ":" + filePath + "#" + keyPath;

            SchemaInferrer.InvestigatoryDocResult cachedInv = investigatoryCache.get(symbolId);
            int initialPass = cachedInv != null ? cachedInv.investigationPass() : 1;
            SchemaInferrer.InvestigatoryDocResult invDoc = SchemaInferrer.synthesizeInvestigatoryDoc(
                    artifact.displayName(),
                    filePath,
                    keyPath,
                    liveValStr != null ? liveValStr : defValStr,
                    defValStr,
                    effectiveComment,
                    schema,
                    new ArrayList<>(consumers),
                    callSites,
                    initialPass,
                    cachedInv
            );
            investigatoryCache.put(symbolId, invDoc);

            AstConfigNode node = new AstConfigNode(
                    symbolId,
                    artifact.artifactId(),
                    filePath,
                    keyPath,
                    parentPrefix,
                    leafName,
                    defValStr,
                    liveValStr,
                    modified,
                    effectiveComment,
                    restored,
                    schema,
                    new ArrayList<>(consumers),
                    invDoc
            );
            nodesBySymbol.put(symbolId, node);

            // Emit READ_BY_CLASS edges
            for (String cls : consumers) {
                edges.add(new AstConfigEdge(symbolId, EdgePredicate.READ_BY_CLASS, "cls:" + cls, "Bytecode constant-pool consumer"));
            }

            // Emit entity bindings & @<file> inheritance edges
            if (liveValStr != null && liveValStr.startsWith("@") && liveValStr.length() > 1) {
                String refTarget = liveValStr.substring(1).trim();
                entityBindings.add(new AstEntityBinding(EntityKind.FILE_REF, refTarget, symbolId, BindingRole.REFERENCES));
                edges.add(new AstConfigEdge(
                        symbolId,
                        EdgePredicate.INHERITS_FROM_KEY,
                        "cfg:" + artifact.artifactId() + ":" + refTarget + ".yml#" + keyPath,
                        "Inherits default via @" + refTarget
                ));
            } else if ("server:worlds".equals(schema.referenceSource()) && liveValStr != null && !liveValStr.isBlank()) {
                entityBindings.add(new AstEntityBinding(EntityKind.WORLD, liveValStr, symbolId, BindingRole.REFERENCES));
            } else if ("minecraft:material".equals(schema.referenceSource()) && liveValStr != null && !liveValStr.isBlank()) {
                entityBindings.add(new AstEntityBinding(EntityKind.MATERIAL, liveValStr, symbolId, BindingRole.REFERENCES));
            } else if ("minecraft:biome".equals(schema.referenceSource()) && liveValStr != null && !liveValStr.isBlank()) {
                entityBindings.add(new AstEntityBinding(EntityKind.BIOME, liveValStr, symbolId, BindingRole.REFERENCES));
            }
        }
    }

    private void indexBytecodeOnlyKeys(
            JarConfigScanner.HarvestedArtifact artifact,
            boolean hasExistingYamlNodes
    ) {
        String filePath = "config.yml";
        Set<String> existingKeysLower = new LinkedHashSet<>();
        Set<String> existingLeavesLower = new LinkedHashSet<>();
        if (hasExistingYamlNodes) {
            for (AstConfigNode n : nodesBySymbol.values()) {
                if (artifact.artifactId().equals(n.artifactId())) {
                    existingKeysLower.add(n.keyPath().toLowerCase(Locale.ROOT));
                    existingLeavesLower.add(n.leafName().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (var entry : artifact.keyCallSiteIndex().entrySet()) {
            String keyPath = entry.getKey();
            List<BytecodeConfigScanner.KeyCallSite> callSites = entry.getValue();
            if (callSites == null || callSites.isEmpty()) {
                continue;
            }
            // Only index keys backed by a typed config value getter or hierarchical dotted/hyphenated path
            BytecodeConfigScanner.KeyCallSite primary = callSites.get(0);
            String getter = primary.getterMethod();
            if (!isValueGetterMethod(getter)) {
                continue;
            }
            if (!keyPath.contains(".") && !keyPath.contains("-") && !keyPath.contains("_") && primary.codeFallbackValue() == null) {
                continue;
            }
            if (hasExistingYamlNodes) {
                String kLow = keyPath.toLowerCase(Locale.ROOT);
                String leafLow = SchemaInferrer.extractLeaf(keyPath).toLowerCase(Locale.ROOT);
                if (!keyPath.contains(".") || primary.codeFallbackValue() == null
                        || existingKeysLower.contains(kLow) || existingLeavesLower.contains(leafLow)) {
                    continue;
                }
            }

            String fallbackStr = null;
            for (BytecodeConfigScanner.KeyCallSite cs : callSites) {
                if (cs.codeFallbackValue() != null && !cs.codeFallbackValue().isBlank()) {
                    fallbackStr = cs.codeFallbackValue();
                    break;
                }
            }
            Object sampleObj = parseBytecodeFallbackObject(getter, fallbackStr);
            String valStr = formatScalarOrList(sampleObj);

            Set<String> consumers = findConsumersForKey(artifact.readByClassIndex(), keyPath);
            for (BytecodeConfigScanner.KeyCallSite cs : callSites) {
                consumers.add(cs.className());
            }
            List<String> bytecodeOptions = "getString".equals(getter)
                    ? resolveMatchingEnumConstants(artifact.discoveredEnums(), callSites, keyPath, sampleObj, !consumers.isEmpty())
                    : List.of();

            SchemaInferrer.InferredSchema schema = SchemaInferrer.infer(
                    keyPath,
                    sampleObj,
                    List.of(),
                    bytecodeOptions,
                    !artifact.docSourceUrl().isBlank()
            );

            int dot = keyPath.lastIndexOf('.');
            String parentPrefix = dot >= 0 ? keyPath.substring(0, dot) : "";
            String leafName = dot >= 0 ? keyPath.substring(dot + 1) : keyPath;
            String symbolId = "cfg:" + artifact.artifactId() + ":" + filePath + "#" + keyPath;

            SchemaInferrer.InvestigatoryDocResult cachedInv = investigatoryCache.get(symbolId);
            int initialPass = cachedInv != null ? cachedInv.investigationPass() : 1;
            SchemaInferrer.InvestigatoryDocResult invDoc = SchemaInferrer.synthesizeInvestigatoryDoc(
                    artifact.displayName(),
                    filePath,
                    keyPath,
                    valStr,
                    valStr,
                    "",
                    schema,
                    new ArrayList<>(consumers),
                    callSites,
                    initialPass,
                    cachedInv
            );
            investigatoryCache.put(symbolId, invDoc);

            AstConfigNode node = new AstConfigNode(
                    symbolId,
                    artifact.artifactId(),
                    filePath,
                    keyPath,
                    parentPrefix,
                    leafName,
                    valStr,
                    valStr,
                    false,
                    "",
                    false,
                    schema,
                    new ArrayList<>(consumers),
                    invDoc
            );
            nodesBySymbol.put(symbolId, node);

            for (String cls : consumers) {
                edges.add(new AstConfigEdge(symbolId, EdgePredicate.READ_BY_CLASS, "cls:" + cls, "Bytecode constant-pool consumer"));
            }
        }
    }

    private static boolean isValueGetterMethod(String getter) {
        if (getter == null) return false;
        return switch (getter) {
            case "getBoolean", "getInt", "getLong", "getDouble", "getString",
                    "getStringList", "getIntegerList", "getDoubleList", "getFloatList",
                    "getList", "getConfigValue", "getNumber", "getOrDefault",
                    "getDuration", "resolveScalar", "parseSecondsDurationMillis",
                    "parseDurationSetting", "resolveCapacity", "resolveCapacityInt",
                    "fieldOf", "optionalFieldOf" -> true;
            default -> false;
        };
    }

    private static Object parseBytecodeFallbackObject(String getter, String fallbackStr) {
        if ("getBoolean".equals(getter)) {
            if ("true".equalsIgnoreCase(fallbackStr) || "1".equals(fallbackStr)) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
        if ("getInt".equals(getter) || "getLong".equals(getter)
                || "resolveCapacity".equals(getter) || "resolveCapacityInt".equals(getter)) {
            if (fallbackStr != null) {
                try {
                    return Long.parseLong(fallbackStr.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return 0L;
        }
        if ("getDouble".equals(getter) || "getNumber".equals(getter)) {
            if (fallbackStr != null) {
                try {
                    return Double.parseDouble(fallbackStr.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return 0.0;
        }
        if (getter != null && getter.endsWith("List")) {
            return fallbackStr != null && !fallbackStr.isBlank() ? List.of(fallbackStr) : List.of();
        }
        return fallbackStr != null ? fallbackStr : "";
    }

    private static List<String> resolveMatchingEnumConstants(
            Map<String, List<String>> discoveredEnums,
            String keyPath,
            Object sampleVal,
            boolean hasConsumers
    ) {
        return resolveMatchingEnumConstants(discoveredEnums, List.of(), keyPath, sampleVal, hasConsumers);
    }

    private static List<String> resolveMatchingEnumConstants(
            Map<String, List<String>> discoveredEnums,
            List<BytecodeConfigScanner.KeyCallSite> callSites,
            String keyPath,
            Object sampleVal,
            boolean hasConsumers
    ) {
        String sampleStr = sampleVal instanceof String s ? s.trim() : "";
        if (callSites != null && !callSites.isEmpty()) {
            Set<String> matchedLiterals = new LinkedHashSet<>();
            for (BytecodeConfigScanner.KeyCallSite site : callSites) {
                for (String guard : site.guardPredicates()) {
                    if (guard.startsWith("enum: ")) {
                        String targetEnum = guard.substring("enum: ".length()).trim();
                        if (discoveredEnums != null && !discoveredEnums.isEmpty()) {
                            List<String> exact = discoveredEnums.get(targetEnum);
                            if (exact != null && !exact.isEmpty()) {
                                return exact;
                            }
                            int tDot = Math.max(targetEnum.lastIndexOf('.'), targetEnum.lastIndexOf('$'));
                            String targetSimple = (tDot >= 0 ? targetEnum.substring(tDot + 1) : targetEnum).toLowerCase(Locale.ROOT);
                            for (var entry : discoveredEnums.entrySet()) {
                                String k = entry.getKey();
                                int kDot = Math.max(k.lastIndexOf('.'), k.lastIndexOf('$'));
                                String kSimple = (kDot >= 0 ? k.substring(kDot + 1) : k).toLowerCase(Locale.ROOT);
                                if (kSimple.equals(targetSimple) && !entry.getValue().isEmpty()) {
                                    return entry.getValue();
                                }
                            }
                        }
                        List<String> externalEnum = SchemaInferrer.resolveKnownExternalEnumConstants(targetEnum, sampleStr);
                        if (!externalEnum.isEmpty()) {
                            return externalEnum;
                        }
                    } else if (guard.startsWith("match == ")) {
                        String lit = guard.substring("match == ".length()).trim();
                        if (!lit.isEmpty()) {
                            matchedLiterals.add(lit);
                        }
                    }
                }
            }
            if (!matchedLiterals.isEmpty()) {
                if (!sampleStr.isEmpty()
                        && sampleStr.matches("^[A-Za-z][A-Za-z0-9_.:-]{1,39}$")
                        && !"true".equalsIgnoreCase(sampleStr)
                        && !"false".equalsIgnoreCase(sampleStr)
                        && !"null".equalsIgnoreCase(sampleStr)
                        && matchedLiterals.stream().noneMatch(m -> m.equalsIgnoreCase(sampleStr))) {
                    matchedLiterals.add(sampleStr);
                }
                if (matchedLiterals.size() >= 2) {
                    return List.copyOf(matchedLiterals);
                }
            }
        }
        if (!hasConsumers || discoveredEnums == null || discoveredEnums.isEmpty()) {
            return List.of();
        }
        if (!(sampleVal instanceof String strVal)) {
            return List.of();
        }
        String trimmed = strVal.trim();
        for (List<String> consts : discoveredEnums.values()) {
            if (consts.size() < 2 || consts.size() > 16) continue;
            for (String c : consts) {
                if (c.equalsIgnoreCase(trimmed)) {
                    return consts;
                }
            }
        }
        String normLeaf = SchemaInferrer.extractLeaf(keyPath).replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (normLeaf.length() >= 4) {
            for (var entry : discoveredEnums.entrySet()) {
                List<String> consts = entry.getValue();
                if (consts.size() < 2 || consts.size() > 16) continue;
                String enumName = entry.getKey();
                int lastDot = Math.max(enumName.lastIndexOf('.'), enumName.lastIndexOf('$'));
                String simpleEnum = (lastDot >= 0 ? enumName.substring(lastDot + 1) : enumName).toLowerCase(Locale.ROOT);
                if (simpleEnum.equals(normLeaf) || simpleEnum.endsWith(normLeaf) || normLeaf.endsWith(simpleEnum)) {
                    return consts;
                }
            }
        }
        return List.of();
    }

    private static Set<String> findConsumersForKey(Map<String, Set<String>> readByClassIndex, String keyPath) {
        Set<String> out = new LinkedHashSet<>();
        if (readByClassIndex.isEmpty()) return out;
        if (readByClassIndex.containsKey(keyPath)) {
            out.addAll(readByClassIndex.get(keyPath));
        }
        String leaf = SchemaInferrer.extractLeaf(keyPath);
        if (readByClassIndex.containsKey(leaf)) {
            out.addAll(readByClassIndex.get(leaf));
        }
        return out;
    }

    private static List<BytecodeConfigScanner.KeyCallSite> findCallSitesForKey(
            Map<String, List<BytecodeConfigScanner.KeyCallSite>> callSiteIndex,
            String keyPath
    ) {
        if (callSiteIndex == null || callSiteIndex.isEmpty()) return List.of();
        List<BytecodeConfigScanner.KeyCallSite> out = new ArrayList<>();
        if (callSiteIndex.containsKey(keyPath)) {
            out.addAll(callSiteIndex.get(keyPath));
        }
        String leaf = SchemaInferrer.extractLeaf(keyPath);
        if (!leaf.equals(keyPath) && callSiteIndex.containsKey(leaf)) {
            for (BytecodeConfigScanner.KeyCallSite site : callSiteIndex.get(leaf)) {
                if (!out.contains(site)) {
                    out.add(site);
                }
            }
        }
        return out;
    }

    /**
     * Progressively investigates or upgrades a single configuration node to {@code targetPass}
     * ({@code 1..3}), storing the updated {@link SchemaInferrer.InvestigatoryDocResult} in the
     * atlas cache so subsequent clicks immediately serve the higher-confidence dossier.
     *
     * @param symbolId   canonical node symbol ID
     * @param targetPass desired AST investigation pass ({@code 1}, {@code 2}, or {@code 3})
     * @return updated {@link AstConfigNode}, or {@code null} if {@code symbolId} is unknown
     */
    public AstConfigNode investigateNode(String symbolId, int targetPass) {
        AstConfigNode current = nodesBySymbol.get(symbolId);
        if (current == null) {
            return null;
        }
        JarConfigScanner.HarvestedArtifact art = artifacts.get(current.artifactId());
        String displayName = art != null ? art.displayName() : current.artifactId();
        List<BytecodeConfigScanner.KeyCallSite> sites = art != null
                ? findCallSitesForKey(art.keyCallSiteIndex(), current.keyPath())
                : List.of();

        SchemaInferrer.InvestigatoryDocResult updatedDoc = SchemaInferrer.synthesizeInvestigatoryDoc(
                displayName,
                current.filePath(),
                current.keyPath(),
                current.liveDiskValue() != null ? current.liveDiskValue() : current.jarDefaultValue(),
                current.jarDefaultValue(),
                current.commentText(),
                current.schema(),
                current.readByClasses(),
                sites,
                targetPass,
                current.investigatoryDoc()
        );
        investigatoryCache.put(symbolId, updatedDoc);
        AstConfigNode upgraded = new AstConfigNode(
                current.symbolId(),
                current.artifactId(),
                current.filePath(),
                current.keyPath(),
                current.parentPrefix(),
                current.leafName(),
                current.jarDefaultValue(),
                current.liveDiskValue(),
                current.isModified(),
                current.commentText(),
                current.commentRestored(),
                current.schema(),
                current.readByClasses(),
                updatedDoc
        );
        nodesBySymbol.put(symbolId, upgraded);
        return upgraded;
    }

    /**
     * Re-evaluates all undocumented configuration nodes whose current confidence score is below
     * {@code confidenceThreshold} (or marked {@code needsFurtherInvestigation}) by advancing them
     * to the next deeper AST investigation pass ({@code Pass 1 -> Pass 2 -> Pass 3}).
     *
     * <p>Nodes that already have authoritative documentation ({@code OFFICIAL_DOCS} or
     * {@code AUTHOR_COMMENT}) are never re-evaluated.
     *
     * @param confidenceThreshold confidence threshold in {@code [0.0, 1.0]} (e.g. {@code 0.82})
     * @return number of nodes whose AST investigation pass or confidence was upgraded
     */
    public int reEvaluateLowConfidenceNodes(double confidenceThreshold) {
        int upgradedCount = 0;
        List<String> symbols = new ArrayList<>(nodesBySymbol.keySet());
        for (String sym : symbols) {
            AstConfigNode node = nodesBySymbol.get(sym);
            if (node == null) continue;
            SchemaInferrer.InvestigatoryDocResult doc = node.investigatoryDoc();
            if (doc == null || doc.confidenceTier() == SchemaInferrer.ConfidenceTier.VERIFIED_DOCS) {
                continue;
            }
            if ((doc.needsFurtherInvestigation() || doc.confidenceScore() < confidenceThreshold)
                    && doc.investigationPass() < 3) {
                int nextPass = doc.investigationPass() + 1;
                investigateNode(sym, nextPass);
                upgradedCount++;
            }
        }
        return upgradedCount;
    }

    /**
     * Synthesizes a concise player-terms description for a single undocumented configuration node
     * using the supplied {@link LlmConfigDocSynthesizer} grounded on Pass 3 bytecode AST context.
     *
     * @param symbolId    canonical node symbol ID
     * @param synthesizer configured LLM doc synthesizer
     * @return upgraded {@link AstConfigNode}, or existing node if documented or LLM is disabled
     */
    public AstConfigNode synthesizeNodeWithLlm(String symbolId, LlmConfigDocSynthesizer synthesizer) {
        Objects.requireNonNull(synthesizer, "synthesizer");
        this.activeLlmHookConfig = synthesizer.config();
        AstConfigNode current = nodesBySymbol.get(symbolId);
        if (current == null) {
            return null;
        }
        if (current.schema().provenance() == SchemaInferrer.DocProvenance.OFFICIAL_DOCS
                || current.schema().provenance() == SchemaInferrer.DocProvenance.AUTHOR_COMMENT
                || current.commentRestored()) {
            return current;
        }
        if (current.investigatoryDoc().investigationPass() < 3) {
            current = investigateNode(symbolId, 3);
        }
        AstConfigNode llmUpgraded = synthesizer.upgradeNodeWithLlm(current);
        nodesBySymbol.put(symbolId, llmUpgraded);
        investigatoryCache.put(symbolId, llmUpgraded.investigatoryDoc());
        return llmUpgraded;
    }

    /**
     * Synthesizes concise player-terms descriptions for up to {@code maxNodes} undocumented
     * configuration keys across the atlas using the supplied {@link LlmConfigDocSynthesizer}.
     *
     * @param synthesizer configured LLM doc synthesizer
     * @param maxNodes    maximum number of undocumented nodes to synthesize in this batch
     * @return number of nodes successfully upgraded to {@code LLM_SYNTHESIZED}
     */
    public int synthesizeUndocumentedNodesWithLlm(LlmConfigDocSynthesizer synthesizer, int maxNodes) {
        Objects.requireNonNull(synthesizer, "synthesizer");
        this.activeLlmHookConfig = synthesizer.config();
        if (synthesizer.config().provider() == LlmConfigDocSynthesizer.Provider.OFF || maxNodes <= 0) {
            return 0;
        }
        int count = 0;
        List<String> symbols = new ArrayList<>(nodesBySymbol.keySet());
        for (String sym : symbols) {
            if (count >= maxNodes) {
                break;
            }
            AstConfigNode node = nodesBySymbol.get(sym);
            if (node == null) continue;
            if (node.schema().provenance() == SchemaInferrer.DocProvenance.OFFICIAL_DOCS
                    || node.schema().provenance() == SchemaInferrer.DocProvenance.AUTHOR_COMMENT
                    || node.schema().provenance() == SchemaInferrer.DocProvenance.LLM_SYNTHESIZED
                    || node.commentRestored()) {
                continue;
            }
            AstConfigNode upgraded = synthesizeNodeWithLlm(sym, synthesizer);
            if (upgraded != null && upgraded.schema().provenance() == SchemaInferrer.DocProvenance.LLM_SYNTHESIZED) {
                count++;
            }
        }
        return count;
    }

    private void rebuildCrossFileEdgesAndSpatialGizmos(String artifactId) {
        // 1. Synthesize SpatialGizmoDescriptors for sections containing coordinate/radius keys
        Map<String, List<AstConfigNode>> byScope = new LinkedHashMap<>();
        for (AstConfigNode node : nodesBySymbol.values()) {
            if (!node.artifactId().equals(artifactId)) continue;
            if (node.schema().spatialGizmo() == SchemaInferrer.SpatialGizmoKind.NONE) continue;
            String scopeKey = node.filePath() + "#" + node.parentPrefix();
            byScope.computeIfAbsent(scopeKey, k -> new ArrayList<>()).add(node);
        }

        spatialGizmos.removeIf(g -> g.artifactId().equals(artifactId));
        for (var entry : byScope.entrySet()) {
            List<AstConfigNode> group = entry.getValue();
            Double cx = null, cz = null, radius = null, innerRadius = null;
            Double minX = null, maxX = null, minZ = null, maxZ = null;
            String worldName = "world";
            Map<String, String> keyMap = new LinkedHashMap<>();
            AstConfigNode first = group.get(0);

            for (AstConfigNode n : group) {
                SchemaInferrer.SpatialGizmoKind kind = n.schema().spatialGizmo();
                keyMap.put(kind.name(), n.keyPath());
                switch (kind) {
                    case WORLD_REFERENCE -> {
                        if (n.liveDiskValue() != null && !n.liveDiskValue().isBlank()) {
                            worldName = n.liveDiskValue();
                        }
                    }
                    case CENTER_X, SPAWN_COORDINATE -> {
                        if (n.leafName().toLowerCase(Locale.ROOT).endsWith("x")) {
                            cx = parseDoubleNumeric(n.liveDiskValue());
                        } else if (n.leafName().toLowerCase(Locale.ROOT).endsWith("z")) {
                            cz = parseDoubleNumeric(n.liveDiskValue());
                        }
                    }
                    case CENTER_Z -> cz = parseDoubleNumeric(n.liveDiskValue());
                    case RADIUS -> radius = parseDoubleNumeric(n.liveDiskValue());
                    case INNER_RADIUS -> innerRadius = parseDoubleNumeric(n.liveDiskValue());
                    case MIN_X -> minX = parseDoubleNumeric(n.liveDiskValue());
                    case MAX_X -> maxX = parseDoubleNumeric(n.liveDiskValue());
                    case MIN_Z -> minZ = parseDoubleNumeric(n.liveDiskValue());
                    case MAX_Z -> maxZ = parseDoubleNumeric(n.liveDiskValue());
                    default -> {
                    }
                }
            }

            if (cx == null && minX != null && maxX != null) {
                cx = (minX + maxX) / 2.0;
                if (radius == null) radius = Math.abs(maxX - minX) / 2.0;
            }
            if (cz == null && minZ != null && maxZ != null) {
                cz = (minZ + maxZ) / 2.0;
                if (radius == null) radius = Math.abs(maxZ - minZ) / 2.0;
            }

            if (radius != null || (cx != null && cz != null)) {
                String gizmoId = artifactId + ":" + first.filePath() + "#"
                        + (first.parentPrefix().isEmpty() ? "root" : first.parentPrefix());
                spatialGizmos.add(new SpatialGizmoDescriptor(
                        gizmoId,
                        artifactId,
                        first.filePath(),
                        first.parentPrefix(),
                        worldName,
                        cx != null ? cx : 0.0,
                        cz != null ? cz : 0.0,
                        radius != null ? radius : 0.0,
                        innerRadius != null ? innerRadius : 0.0,
                        keyMap
                ));
            }
        }

        // 2. Detect cross-plugin sleep / teleport / view-distance coupled & conflicting keys
        detectKnownCrossPluginEdges();
    }

    private void detectKnownCrossPluginEdges() {
        List<AstConfigNode> sleepKeys = new ArrayList<>();
        List<AstConfigNode> viewDistKeys = new ArrayList<>();
        List<AstConfigNode> queueKeys = new ArrayList<>();

        for (AstConfigNode n : nodesBySymbol.values()) {
            String low = n.keyPath().toLowerCase(Locale.ROOT);
            if (low.contains("sleep") && (low.contains("percent") || low.contains("quorum") || low.contains("proportion"))) {
                sleepKeys.add(n);
            }
            if (low.endsWith("view-distance") || low.endsWith("viewdistance")) {
                viewDistKeys.add(n);
            }
            if (low.endsWith("queuesize") || low.endsWith("queue-size") || low.endsWith("activechunkcap")) {
                queueKeys.add(n);
            }
        }

        if (sleepKeys.size() >= 2) {
            AstConfigNode a = sleepKeys.get(0);
            for (int i = 1; i < sleepKeys.size(); i++) {
                AstConfigNode b = sleepKeys.get(i);
                if (!a.artifactId().equals(b.artifactId())) {
                    addUniqueEdge(new AstConfigEdge(
                            a.symbolId(),
                            EdgePredicate.CONFLICTS_WITH_KEY,
                            b.symbolId(),
                            "Overlapping sleep quorum mechanic across " + a.artifactId() + " and " + b.artifactId()
                    ));
                }
            }
        }
        for (AstConfigNode vd : viewDistKeys) {
            for (AstConfigNode q : queueKeys) {
                addUniqueEdge(new AstConfigEdge(
                        vd.symbolId(),
                        EdgePredicate.COUPLED_BOUND,
                        q.symbolId(),
                        "Chunk view-distance scales quadratically with async chunk queue pressure"
                ));
            }
        }
    }

    private void addUniqueEdge(AstConfigEdge candidate) {
        for (AstConfigEdge existing : edges) {
            if (existing.sourceId().equals(candidate.sourceId())
                    && existing.predicate() == candidate.predicate()
                    && existing.targetId().equals(candidate.targetId())) {
                return;
            }
        }
        edges.add(candidate);
    }

    private static final Set<String> SEARCH_STOPWORDS = Set.of(
            "what", "why", "how", "does", "do", "is", "are", "the", "a", "an", "in", "on", "to", "for", "of",
            "with", "when", "where", "can", "i", "my", "server", "make", "change", "set", "configure", "config",
            "setting", "settings", "value", "values", "by", "from", "it", "pls", "please", "help", "me", "stop",
            "fix", "broken", "work", "working", "dont", "cant", "wont", "players", "player", "turn"
    );

    private static final Map<String, List<String>> BUILT_IN_NOVICE_SYNONYMS = Map.ofEntries(
            Map.entry("lag", List.of("cooldown", "interval", "batch", "queue", "mspt", "timeout", "wake", "sleep", "unload")),
            Map.entry("laggy", List.of("cooldown", "interval", "batch", "queue", "mspt", "timeout")),
            Map.entry("slow", List.of("cooldown", "interval", "delay", "queue", "period", "timeout")),
            Map.entry("freeze", List.of("timeout", "mspt", "syncloading", "cooldown")),
            Map.entry("hopper", List.of("hopper", "transfer", "batch", "cooldown", "inventory")),
            Map.entry("hoppr", List.of("hopper", "transfer", "batch", "cooldown")),
            Map.entry("funnel", List.of("hopper", "transfer", "batch")),
            Map.entry("pvp", List.of("combat", "bleed", "damage", "pvp", "tag", "blocked", "combattimeout")),
            Map.entry("fight", List.of("combat", "bleed", "damage", "pvp", "tag", "combattimeout")),
            Map.entry("bleed", List.of("bleed", "combat", "damage", "chance")),
            Map.entry("blead", List.of("bleed", "combat", "damage", "chance")),
            Map.entry("blood", List.of("bleed", "combat", "damage")),
            Map.entry("timer", List.of("duration", "timeout", "cooldown", "delay", "interval", "seconds", "ticks")),
            Map.entry("wait", List.of("delay", "cooldown", "duration", "timeout", "ticks", "seconds")),
            Map.entry("move", List.of("canceldistance", "cancel", "move")),
            Map.entry("claim", List.of("claims", "minimumwidth", "initialblocks", "griefprevention", "worldguard")),
            Map.entry("grief", List.of("claims", "griefprevention", "worldguard", "enforcement")),
            Map.entry("shovel", List.of("claims", "initialblocks", "minimumwidth", "griefprevention")),
            Map.entry("border", List.of("radius", "center", "enforcement", "worldguard", "border")),
            Map.entry("raduis", List.of("radius", "wakeradiusblocks", "sleepradiusblocks", "maxbrushradius")),
            Map.entry("cooldwon", List.of("cooldown", "transfercooldownticks", "reloadcooldownticks", "teleportcooldown")),
            Map.entry("dealy", List.of("delay", "teleportdelay")),
            Map.entry("timout", List.of("timeout", "combattimeoutseconds")),
            Map.entry("spwan", List.of("spawn", "spawnintervalticks", "spawnmode")),
            Map.entry("pickax", List.of("pickaxe", "superpickaxe")),
            Map.entry("brush", List.of("brush", "maxbrushradius", "limits")),
            Map.entry("wand", List.of("wand", "navigationwand", "wanditem")),
            Map.entry("papi", List.of("placeholderapi", "cloud", "sorting", "dateformat")),
            Map.entry("farm", List.of("wakeradiusblocks", "sleepradiusblocks", "rehibernate", "hysteresis", "governor"))
    );

    private static final Map<String, List<String>> NOVICE_COMPOUND_SPLITS = Map.ofEntries(
            Map.entry("tpdelay", List.of("teleport", "delay", "teleportdelay")),
            Map.entry("tpcooldown", List.of("teleport", "cooldown", "teleportcooldown")),
            Map.entry("tpradius", List.of("teleport", "radius")),
            Map.entry("netherroof", List.of("nether", "roof", "maxy")),
            Map.entry("hopperlag", List.of("hopper", "cooldown", "batch")),
            Map.entry("pvptimer", List.of("pvp", "combat", "duration", "timeout")),
            Map.entry("claimblocks", List.of("claims", "initialblocks", "minimumwidth")),
            Map.entry("worldborder", List.of("world", "border", "radius", "enforcement")),
            Map.entry("brushsize", List.of("brush", "radius", "limits")),
            Map.entry("wakeradius", List.of("wake", "radius", "wakeradiusblocks")),
            Map.entry("sleepradius", List.of("sleep", "radius", "sleepradiusblocks"))
    );

    private static final Set<String> INFORMAL_BOOL_TRUE = Set.of(
            "yes", "y", "ye", "yep", "yup", "on", "enabled", "enable", "1", "true!"
    );

    private static final Set<String> INFORMAL_BOOL_FALSE = Set.of(
            "no", "n", "nope", "off", "disabled", "disable", "0", "none", "false!"
    );

    /**
     * Outcome of coercing and validating a single informal configuration value against an {@link AstConfigNode}.
     *
     * @param originalValue raw user-supplied value
     * @param coercedValue  normalized schema-compliant value
     * @param wasCoerced    true if the input was modified during coercion
     * @param valid         true if the resulting value satisfies the node's schema
     * @param notes         human-readable explanations of any coercions or range clamps applied
     */
    public record CoercedConfigInput(
            String originalValue,
            String coercedValue,
            boolean wasCoerced,
            boolean valid,
            List<String> notes
    ) {
        public CoercedConfigInput {
            originalValue = originalValue == null ? "" : originalValue;
            coercedValue = coercedValue == null ? "" : coercedValue;
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * Outcome of sanitizing and coercing a multi-line YAML configuration edit.
     *
     * @param coercedYaml normalized YAML text ready for persistence
     * @param wasCoerced  true if any syntax or scalar value was coerced
     * @param notes       human-readable explanations of all coercions applied
     */
    public record CoercedYamlEdit(
            String coercedYaml,
            boolean wasCoerced,
            List<String> notes
    ) {
        public CoercedYamlEdit {
            coercedYaml = coercedYaml == null ? "" : coercedYaml;
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * Auto-coerces an informal user input (e.g. {@code "yes"}/{@code "no"}, {@code "2 seconds"} on a tick key,
     * {@code "50%"} on a decimal probability key, {@code "5,000"} with commas, or a case/typo-mismatched enum)
     * against a symbol ID or dotted/leaf key name in the atlas.
     */
    public CoercedConfigInput coerceInput(String symbolIdOrKey, String rawInput) {
        if (symbolIdOrKey == null || symbolIdOrKey.isBlank()) {
            return new CoercedConfigInput(rawInput, rawInput == null ? "" : rawInput.trim(), false, true, List.of());
        }
        AstConfigNode node = nodesBySymbol.get(symbolIdOrKey);
        if (node == null) {
            String lookupLow = symbolIdOrKey.trim().toLowerCase(Locale.ROOT);
            for (AstConfigNode cand : nodesBySymbol.values()) {
                if (cand.symbolId().equalsIgnoreCase(lookupLow)
                        || cand.keyPath().equalsIgnoreCase(lookupLow)
                        || cand.leafName().equalsIgnoreCase(lookupLow)
                        || cand.symbolId().toLowerCase(Locale.ROOT).endsWith("#" + lookupLow)) {
                    node = cand;
                    break;
                }
            }
        }
        return coerceInputForNode(node, rawInput);
    }

    /**
     * Auto-coerces an informal user input against a resolved {@link AstConfigNode} and its {@link SchemaInferrer.InferredSchema}.
     */
    public CoercedConfigInput coerceInputForNode(AstConfigNode node, String rawInput) {
        if (rawInput == null) {
            return new CoercedConfigInput("", "", false, false, List.of("Empty input value."));
        }
        List<String> notes = new ArrayList<>();
        String cleaned = rawInput.trim();
        // Strip surrounding smart quotes or standard quotes and trailing semicolons/commas
        cleaned = stripSurroundingQuotes(cleaned).trim();
        if (cleaned.length() > 1 && (cleaned.endsWith(";") || cleaned.endsWith(","))) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        String low = cleaned.toLowerCase(Locale.ROOT);
        String keyLabel = node != null ? node.keyPath() : "setting";
        String type = (node != null && node.schema() != null && node.schema().inferredType() != null)
                ? node.schema().inferredType().toLowerCase(Locale.ROOT)
                : "";
        String unit = (node != null && node.schema() != null && node.schema().unit() != null)
                ? node.schema().unit().toLowerCase(Locale.ROOT)
                : "";
        String defVal = (node != null && node.jarDefaultValue() != null) ? node.jarDefaultValue().trim() : "";
        List<String> options = (node != null && node.schema() != null && node.schema().options() != null)
                ? node.schema().options()
                : List.of();
        Double rangeMin = (node != null && node.schema() != null) ? node.schema().rangeMin() : null;
        Double rangeMax = (node != null && node.schema() != null) ? node.schema().rangeMax() : null;

        // 1. Boolean coercion (`yes`/`no`/`on`/`off`/`enabled`/`disabled`/`1`/`0`)
        boolean isBool = "boolean".equals(type)
                || "true".equalsIgnoreCase(defVal)
                || "false".equalsIgnoreCase(defVal);
        if (isBool) {
            if ("true".equals(low) || "false".equals(low)) {
                boolean changed = !cleaned.equals(low);
                if (changed) {
                    notes.add("Normalized boolean casing '" + rawInput.trim() + "' -> '" + low + "' for " + keyLabel + ".");
                }
                return new CoercedConfigInput(rawInput, low, changed, true, notes);
            }
            if (INFORMAL_BOOL_TRUE.contains(low)) {
                notes.add("Auto-coerced informal boolean '" + rawInput.trim() + "' -> 'true' for " + keyLabel + ".");
                return new CoercedConfigInput(rawInput, "true", true, true, notes);
            }
            if (INFORMAL_BOOL_FALSE.contains(low)) {
                notes.add("Auto-coerced informal boolean '" + rawInput.trim() + "' -> 'false' for " + keyLabel + ".");
                return new CoercedConfigInput(rawInput, "false", true, true, notes);
            }
            notes.add("Expected boolean ('true' or 'false') for " + keyLabel + ", got '" + rawInput.trim() + "'.");
            return new CoercedConfigInput(rawInput, cleaned, false, false, notes);
        }

        // 2. Enum `@options` coercion (case-insensitive + FuzzySearchEngine typo tolerance)
        if (!options.isEmpty()) {
            for (String opt : options) {
                if (opt.equals(cleaned)) {
                    return new CoercedConfigInput(rawInput, opt, false, true, notes);
                }
                if (opt.equalsIgnoreCase(cleaned)) {
                    notes.add("Normalized enum casing '" + rawInput.trim() + "' -> '" + opt + "' for " + keyLabel + ".");
                    return new CoercedConfigInput(rawInput, opt, true, true, notes);
                }
            }
            FuzzySearchEngine.FuzzyLookupResult<String> fuzzyEnum = FuzzySearchEngine.resolveCandidate(cleaned, options, 3);
            if (fuzzyEnum.hasMatch() && fuzzyEnum.match() != null) {
                String matched = fuzzyEnum.match();
                notes.add("Auto-corrected enum typo '" + rawInput.trim() + "' -> '" + matched + "' for " + keyLabel
                        + " (valid options: " + options + ").");
                return new CoercedConfigInput(rawInput, matched, true, true, notes);
            }
            notes.add("Value '" + rawInput.trim() + "' is not one of the valid options " + options + " for " + keyLabel + ".");
            return new CoercedConfigInput(rawInput, cleaned, false, false, notes);
        }

        // 3. Numeric coercion (thousand commas, unit suffixes `seconds` <-> `ticks`, `%` -> decimal probability, range clamping)
        boolean isInt = "integer".equals(type) || "int".equals(type) || "long".equals(type);
        boolean isDouble = "double".equals(type) || "float".equals(type) || "number".equals(type);
        java.util.regex.Matcher numMatcher = java.util.regex.Pattern.compile(
                "^(-?\\d{1,3}(?:,\\d{3})+|-?\\d+(?:\\.\\d+)?)\\s*(%|ms|milliseconds?|ticks?|t|s|sec|seconds?|m|min|minutes?|blocks?|b|chunks?|items?|chars?)?$",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(cleaned);

        if (numMatcher.matches() && (isInt || isDouble || numMatcher.group(2) != null)) {
            String rawNumPart = numMatcher.group(1).replace(",", "");
            String unitSuffix = numMatcher.group(2) != null ? numMatcher.group(2).trim().toLowerCase(Locale.ROOT) : "";
            try {
                double val = Double.parseDouble(rawNumPart);
                if (!Double.isFinite(val)) {
                    notes.add("Rejected non-finite numeric value '" + rawInput.trim() + "' for " + keyLabel + ".");
                    return new CoercedConfigInput(rawInput, defVal.isEmpty() ? "0" : defVal, false, false, notes);
                }
                String keyLow = keyLabel.toLowerCase(Locale.ROOT);
                boolean expectsTicks = "ticks".equals(unit) || keyLow.endsWith("ticks") || keyLow.endsWith("-ticks");
                boolean expectsSeconds = "seconds".equals(unit) || "s".equals(unit)
                        || keyLow.endsWith("seconds") || keyLow.endsWith("-seconds")
                        || keyLow.endsWith("delay") || keyLow.endsWith("cooldown");
                boolean expectsProbability = isDouble
                        && (keyLow.contains("chance") || keyLow.contains("probability") || keyLow.contains("ratio"))
                        && ((rangeMax != null && rangeMax <= 1.0) || (!defVal.isEmpty() && parseSafeDouble(defVal, 2.0) <= 1.0));
                boolean expectsNonNegative = expectsTicks || expectsSeconds || expectsProbability
                        || keyLow.contains("timeout") || keyLow.contains("minimumwidth")
                        || keyLow.contains("minimumarea") || keyLow.contains("initialblocks")
                        || keyLow.contains("duration") || keyLow.contains("cooldown")
                        || keyLow.contains("interval") || keyLow.contains("period")
                        || keyLow.contains("max-items") || keyLow.contains("cap");

                if (!unitSuffix.isEmpty()) {
                    if ((unitSuffix.equals("s") || unitSuffix.startsWith("sec")) && expectsTicks) {
                        val = val * 20.0;
                        notes.add("Converted '" + rawInput.trim() + "' -> '" + Math.round(val) + "' ticks (1s = 20 ticks) for " + keyLabel + ".");
                    } else if ((unitSuffix.equals("m") || unitSuffix.startsWith("min")) && expectsTicks) {
                        val = val * 1200.0;
                        notes.add("Converted '" + rawInput.trim() + "' -> '" + Math.round(val) + "' ticks (1 min = 1200 ticks) for " + keyLabel + ".");
                    } else if ((unitSuffix.equals("t") || unitSuffix.startsWith("tick")) && expectsSeconds && !expectsTicks) {
                        val = val / 20.0;
                        notes.add("Converted '" + rawInput.trim() + "' -> '" + formatNumber(val, isInt) + "' seconds (20 ticks = 1s) for " + keyLabel + ".");
                    } else if (unitSuffix.equals("%") && expectsProbability && val > 1.0) {
                        val = val / 100.0;
                        notes.add("Converted percentage '" + rawInput.trim() + "' -> '" + formatNumber(val, false) + "' decimal probability for " + keyLabel + ".");
                    } else {
                        notes.add("Stripped inline unit '" + unitSuffix + "' from '" + rawInput.trim() + "' for " + keyLabel + ".");
                    }
                } else if (numMatcher.group(1).contains(",")) {
                    notes.add("Removed thousand separators from '" + rawInput.trim() + "' for " + keyLabel + ".");
                }

                Double effectiveMin = rangeMin != null ? rangeMin : (expectsProbability ? 0.0 : (expectsNonNegative ? 0.0 : null));
                Double effectiveMax = rangeMax != null ? rangeMax : (expectsProbability ? 1.0 : (isInt ? (double) Integer.MAX_VALUE : null));
                if (effectiveMin != null && val < effectiveMin) {
                    notes.add("Clamped " + keyLabel + " from " + formatNumber(val, isInt) + " to minimum allowed " + formatNumber(effectiveMin, isInt) + ".");
                    val = effectiveMin;
                }
                if (effectiveMax != null && val > effectiveMax) {
                    notes.add("Clamped " + keyLabel + " from " + formatNumber(val, isInt) + " to maximum allowed " + formatNumber(effectiveMax, isInt) + ".");
                    val = effectiveMax;
                }

                String formatted = formatNumber(val, isInt);
                boolean changed = !formatted.equals(rawInput.trim());
                return new CoercedConfigInput(rawInput, formatted, changed || !notes.isEmpty(), true, notes);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        if ((isInt || isDouble) && !cleaned.startsWith("@")) {
            notes.add("Expected " + type + " for " + keyLabel + ", got '" + rawInput.trim() + "'.");
            return new CoercedConfigInput(rawInput, cleaned, false, false, notes);
        }

        return new CoercedConfigInput(rawInput, cleaned, !cleaned.equals(rawInput.trim()), true, notes);
    }

    private static final java.util.regex.Pattern KV_LINE_RE = java.util.regex.Pattern.compile(
            "^(\\s*+)([A-Za-z0-9_.-]++)\\s*+(:|=)\\s*+([^#\\r\\n]*+)(#[^\\r\\n]*+)?$"
    );

    /**
     * Sanitizes and auto-coerces a raw YAML configuration text for a given artifact and file path:
     * replaces leading tab indentation with 2 spaces, converts {@code key = value} lines into
     * {@code key: value}, and runs {@link #coerceInputForNode(AstConfigNode, String)} on every
     * scalar setting in the file.
     */
    public CoercedYamlEdit coerceYamlText(String artifactId, String filePath, String rawYamlText) {
        if (rawYamlText == null || rawYamlText.isEmpty()) {
            return new CoercedYamlEdit("", false, List.of());
        }
        String normFile = filePath == null ? "config.yml" : filePath.replace('\\', '/');
        String shortFile = normFile.replaceFirst("^(?:plugins|mods)/[^/]+/", "");
        String lowFile = normFile.toLowerCase(Locale.ROOT);
        boolean isToml = lowFile.endsWith(".toml");
        boolean isProps = lowFile.endsWith(".properties");
        boolean isJsonLike = lowFile.endsWith(".json")
                || lowFile.endsWith(".jsonc")
                || lowFile.endsWith(".json5")
                || lowFile.endsWith(".conf");
        if (isJsonLike) {
            return coerceJsonLikeText(artifactId, normFile, shortFile, lowFile, rawYamlText);
        }
        String currentTomlSection = "";
        List<String> notes = new ArrayList<>();
        String[] lines = rawYamlText.split("\r?\n", -1);
        StringBuilder out = new StringBuilder(rawYamlText.length() + 64);
        Deque<int[]> indentStack = new ArrayDeque<>();
        Deque<String> keyStack = new ArrayDeque<>();
        boolean modified = false;
        boolean notedTabs = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("\t") && !isProps) {
                String detabbed = detabLeadingIndent(line);
                if (!detabbed.equals(line)) {
                    line = detabbed;
                    modified = true;
                    if (!notedTabs) {
                        notedTabs = true;
                        notes.add("Replaced leading Tab indentation with 2 spaces per level in " + normFile + ".");
                    }
                }
            }
            String trimmed = line.trim();
            if (isToml && trimmed.startsWith("[") && trimmed.contains("]")) {
                int close = trimmed.lastIndexOf(']');
                currentTomlSection = stripBrackets(trimmed.substring(0, close + 1)).trim()
                        .replace("\"", "").replace("'", "");
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || (isProps && trimmed.startsWith("!")) || trimmed.startsWith("-")) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            java.util.regex.Matcher m = KV_LINE_RE.matcher(line);
            if (!m.matches()) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            String indentStr = m.group(1);
            int indent = indentStr.length();
            String key = m.group(2);
            String sep = m.group(3);
            String rawVal = m.group(4);
            String valPart = rawVal.trim();
            int trailingWsStart = rawVal.length();
            while (trailingWsStart > 0 && Character.isWhitespace(rawVal.charAt(trailingWsStart - 1))) {
                trailingWsStart--;
            }
            String commentPart = m.group(5) != null ? (rawVal.substring(trailingWsStart) + m.group(5)) : "";

            if (!isToml && !isProps) {
                while (!indentStack.isEmpty() && indentStack.peekLast()[0] >= indent) {
                    indentStack.pollLast();
                    keyStack.pollLast();
                }

                if (valPart.isEmpty()) {
                    if ("=".equals(sep)) {
                        modified = true;
                        notes.add("Converted '" + key + " =' section header to '" + key + ":' in " + normFile + ".");
                    }
                    indentStack.addLast(new int[]{indent});
                    keyStack.addLast(key);
                    out.append(indentStr).append(key).append(':').append(commentPart);
                    if (i < lines.length - 1) out.append('\n');
                    continue;
                }
            }

            String fullKeyPath = isToml
                    ? (currentTomlSection.isEmpty() ? key : (currentTomlSection + "." + key))
                    : (isProps ? key : (keyStack.isEmpty() ? key : String.join(".", keyStack) + "." + key));
            AstConfigNode matchedNode = findNodeForFileAndKey(artifactId, shortFile, normFile, fullKeyPath, key);
            String finalVal = valPart;
            if (matchedNode != null) {
                String unquotedInput = valPart;
                boolean wasDoubleQuoted = isToml && valPart.length() >= 2 && valPart.startsWith("\"") && valPart.endsWith("\"");
                if (wasDoubleQuoted) {
                    unquotedInput = valPart.substring(1, valPart.length() - 1);
                }
                CoercedConfigInput coerced = coerceInputForNode(matchedNode, unquotedInput);
                if (coerced.wasCoerced()) {
                    finalVal = wasDoubleQuoted ? ("\"" + coerced.coercedValue() + "\"") : coerced.coercedValue();
                    modified = true;
                    notes.addAll(coerced.notes());
                } else if (!coerced.valid()) {
                    notes.addAll(coerced.notes());
                    String safeFallback = (matchedNode.liveDiskValue() != null && !matchedNode.liveDiskValue().isBlank())
                            ? matchedNode.liveDiskValue()
                            : matchedNode.jarDefaultValue();
                    if (safeFallback != null && !safeFallback.isBlank() && !safeFallback.equals(unquotedInput)) {
                        finalVal = wasDoubleQuoted ? ("\"" + safeFallback + "\"") : safeFallback;
                        modified = true;
                        notes.add("Retained safe value '" + safeFallback + "' for " + matchedNode.keyPath()
                                + " after rejecting invalid input '" + valPart + "'.");
                    }
                }
            }
            if (isToml) {
                out.append(indentStr).append(key).append(" = ").append(finalVal).append(commentPart);
            } else if (isProps) {
                out.append(indentStr).append(key).append("=").append(finalVal).append(commentPart);
            } else {
                if ("=".equals(sep)) {
                    modified = true;
                    notes.add("Converted '" + key + " = " + valPart + "' to YAML '" + key + ": " + finalVal + "' in " + normFile + ".");
                }
                out.append(indentStr).append(key).append(": ").append(finalVal).append(commentPart);
            }
            if (i < lines.length - 1) out.append('\n');
        }

        return new CoercedYamlEdit(out.toString(), modified, notes);
    }

    private CoercedYamlEdit coerceJsonLikeText(
            String artifactId,
            String normFile,
            String shortFile,
            String lowFile,
            String rawText
    ) {
        List<String> notes = new ArrayList<>();
        String[] lines = rawText.split("\r?\n", -1);
        StringBuilder out = new StringBuilder(rawText.length() + 64);
        Deque<String> keyStack = new ArrayDeque<>();
        boolean modified = false;
        boolean notedTabs = false;
        boolean inBlockComment = false;
        boolean isHocon = lowFile.endsWith(".conf");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("\t")) {
                String detabbed = detabLeadingIndent(line);
                if (!detabbed.equals(line)) {
                    line = detabbed;
                    modified = true;
                    if (!notedTabs) {
                        notedTabs = true;
                        notes.add("Replaced leading Tab indentation with 2 spaces per level in " + normFile + ".");
                    }
                }
            }
            String trimmed = line.trim();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlockComment = true;
                }
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || "{".equals(trimmed)) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            if (trimmed.startsWith("}")) {
                if (!keyStack.isEmpty()) {
                    keyStack.pollLast();
                }
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            int indentLen = 0;
            while (indentLen < line.length() && (line.charAt(indentLen) == ' ' || line.charAt(indentLen) == '\t')) {
                indentLen++;
            }
            String indentStr = line.substring(0, indentLen);
            String body = line.substring(indentLen);
            int sepIdx = findUnquotedJsonSeparator(body);
            if (sepIdx <= 0) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            String rawKeyToken = body.substring(0, sepIdx).trim();
            String key = unquoteJsonToken(rawKeyToken);
            if (key.isEmpty()) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            String afterSepRaw = body.substring(sepIdx + 1);
            String[] valAndComment = splitJsonValueAndInlineComment(afterSepRaw);
            String valWithComma = valAndComment[0].trim();
            String inlineCommentWithWs = valAndComment[1];

            if ("{".equals(valWithComma)) {
                keyStack.addLast(key);
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            if (valWithComma.startsWith("{") || valWithComma.startsWith("[")) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            boolean hasTrailingComma = valWithComma.endsWith(",");
            String rawScalar = hasTrailingComma
                    ? valWithComma.substring(0, valWithComma.length() - 1).trim()
                    : valWithComma;
            boolean wasDoubleQuoted = rawScalar.length() >= 2 && rawScalar.startsWith("\"") && rawScalar.endsWith("\"");
            String unquotedInput = wasDoubleQuoted ? rawScalar.substring(1, rawScalar.length() - 1) : rawScalar;

            String fullKeyPath = keyStack.isEmpty() ? key : (String.join(".", keyStack) + "." + key);
            AstConfigNode matchedNode = findNodeForFileAndKey(artifactId, shortFile, normFile, fullKeyPath, key);
            if (matchedNode == null) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            String coercedScalar = null;
            CoercedConfigInput coerced = coerceInputForNode(matchedNode, unquotedInput);
            if (coerced.wasCoerced()) {
                coercedScalar = coerced.coercedValue();
                modified = true;
                notes.addAll(coerced.notes());
            } else if (!coerced.valid()) {
                notes.addAll(coerced.notes());
                String safeFallback = (matchedNode.liveDiskValue() != null && !matchedNode.liveDiskValue().isBlank())
                        ? matchedNode.liveDiskValue()
                        : matchedNode.jarDefaultValue();
                if (safeFallback != null && !safeFallback.isBlank() && !safeFallback.equals(unquotedInput)) {
                    coercedScalar = safeFallback;
                    modified = true;
                    notes.add("Retained safe value '" + safeFallback + "' for " + matchedNode.keyPath()
                            + " after rejecting invalid input '" + rawScalar + "'.");
                }
            }

            if (coercedScalar != null) {
                String formattedKey = (!isHocon || (rawKeyToken.startsWith("\"") && rawKeyToken.endsWith("\"")))
                        ? ("\"" + key + "\"")
                        : rawKeyToken;
                String formattedVal = formatCoercedJsonScalar(matchedNode, coercedScalar, wasDoubleQuoted);
                out.append(indentStr)
                        .append(formattedKey)
                        .append(": ")
                        .append(formattedVal)
                        .append(hasTrailingComma ? "," : "")
                        .append(inlineCommentWithWs);
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return new CoercedYamlEdit(out.toString(), modified, notes);
    }

    private static String formatCoercedJsonScalar(AstConfigNode node, String coercedScalar, boolean wasDoubleQuoted) {
        String type = node.schema().inferredType();
        if ("integer".equals(type) || "float".equals(type) || "number".equals(type) || "boolean".equals(type)) {
            return coercedScalar;
        }
        if ("string".equals(type) || "enum".equals(type) || wasDoubleQuoted) {
            return "\"" + SchemaInferrer.escapeJson(coercedScalar) + "\"";
        }
        return coercedScalar;
    }

    private static String[] splitJsonValueAndInlineComment(String afterSepRaw) {
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < afterSepRaw.length(); i++) {
            char c = afterSepRaw.charAt(i);
            if (c == '\\' && i + 1 < afterSepRaw.length()) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle) {
                if ((c == '/' && i + 1 < afterSepRaw.length() && afterSepRaw.charAt(i + 1) == '/') || c == '#') {
                    int wsStart = i;
                    while (wsStart > 0 && (afterSepRaw.charAt(wsStart - 1) == ' ' || afterSepRaw.charAt(wsStart - 1) == '\t')) {
                        wsStart--;
                    }
                    return new String[]{afterSepRaw.substring(0, wsStart), afterSepRaw.substring(wsStart)};
                }
            }
        }
        return new String[]{afterSepRaw, ""};
    }

    private AstConfigNode findNodeForFileAndKey(String artifactId, String shortFile, String normFile, String fullKeyPath, String leafKey) {
        if (artifactId != null && !artifactId.isBlank()) {
            AstConfigNode exact = nodesBySymbol.get("cfg:" + artifactId + ":" + shortFile + "#" + fullKeyPath);
            if (exact != null) return exact;
        }
        AstConfigNode leafFallback = null;
        int leafMatches = 0;
        for (AstConfigNode n : nodesBySymbol.values()) {
            if (artifactId != null && !artifactId.isBlank() && !n.artifactId().equalsIgnoreCase(artifactId)) {
                continue;
            }
            boolean fileMatches = n.filePath().equalsIgnoreCase(shortFile) || n.filePath().equalsIgnoreCase(normFile);
            if (!fileMatches) {
                continue;
            }
            if (n.keyPath().equalsIgnoreCase(fullKeyPath)) {
                return n;
            }
            if (n.leafName().equalsIgnoreCase(leafKey)) {
                leafFallback = n;
                leafMatches++;
            }
        }
        return leafMatches == 1 ? leafFallback : null;
    }

    private static String detabLeadingIndent(String line) {
        int idx = 0;
        StringBuilder sb = new StringBuilder(line.length() + 4);
        while (idx < line.length() && (line.charAt(idx) == ' ' || line.charAt(idx) == '\t')) {
            if (line.charAt(idx) == '\t') {
                sb.append("  ");
            } else {
                sb.append(' ');
            }
            idx++;
        }
        sb.append(line.substring(idx));
        return sb.toString();
    }

    private static double parseSafeDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stripVersionSuffix(String stem) {
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (c == '-' || c == '_' || c == 'v' || c == 'V') {
                int j = i + 1;
                while (j < stem.length() && (stem.charAt(j) == '-' || stem.charAt(j) == '_' || stem.charAt(j) == 'v' || stem.charAt(j) == 'V')) {
                    j++;
                }
                if (j < stem.length() && Character.isDigit(stem.charAt(j))) {
                    return stem.substring(0, i);
                }
            }
        }
        return stem;
    }

    private static String stripBrackets(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && raw.charAt(start) == '[') {
            start++;
        }
        while (end > start && raw.charAt(end - 1) == ']') {
            end--;
        }
        return raw.substring(start, end);
    }

    private static String stripSurroundingQuotes(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && isQuoteChar(raw.charAt(start))) {
            start++;
        }
        while (end > start && isQuoteChar(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(start, end);
    }

    private static boolean isQuoteChar(char c) {
        return c == '\'' || c == '"' || c == '“' || c == '”' || c == '‘' || c == '’';
    }

    private static String formatNumber(double val, boolean asInt) {
        if (asInt || (val == Math.rint(val) && Math.abs(val) < 1e12 && String.valueOf(val).endsWith(".0"))) {
            if (asInt) {
                return String.valueOf(Math.round(val));
            }
        }
        String s = String.format(Locale.ROOT, "%.4f", val);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        s = s.substring(0, end);
        if (!asInt && !s.contains(".")) {
            s = s + ".0";
        }
        return s;
    }

    /**
     * Multi-dimensional cross-plugin configuration search combining exact/prefix key matching,
     * {@link FuzzySearchEngine} Levenshtein typo tolerance, built-in novice/colloquial synonym expansion,
     * run-on compound splitting, comment/doc prose, and {@code READ_BY_CLASS} bytecode consumer matching
     * ({@code leafconfig-ADR-001} §4.1, {@code leafrtp-ADR-080} §4.5).
     *
     * @param query    raw user search query
     * @param synonyms optional concept-to-keys synonym dictionary
     * @param limit    maximum number of ranked hits to return
     * @return ranked list of configuration search hits
     */
    public List<ConfigSearchHit> search(String query, Map<String, List<String>> synonyms, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        SearchThesaurus sharedThesaurus = SearchThesaurus.defaultInstance();
        OnlineQueryExpander onlineExpander = OnlineQueryExpander.defaultInstance();
        long nowMs = System.currentTimeMillis();
        String qNorm = FuzzySearchEngine.normalize(query);
        String qLow = query.trim().toLowerCase(Locale.ROOT);
        String phraseAugmented = sharedThesaurus.normalizePhrases(qLow);
        String[] rawTokens = phraseAugmented.replaceAll("[^\\p{L}a-z0-9_.-]+", " ").trim().split("\\s+");

        List<String> tokens = new ArrayList<>();
        for (String rt : rawTokens) {
            if (rt.isEmpty() || SEARCH_STOPWORDS.contains(rt)) continue;
            if (!tokens.contains(rt)) {
                tokens.add(rt);
            }
            List<String> split = NOVICE_COMPOUND_SPLITS.get(rt);
            if (split != null) {
                for (String sp : split) {
                    if (!tokens.contains(sp)) tokens.add(sp);
                }
            }
            for (String sp : sharedThesaurus.splitCompoundToken(rt)) {
                if (!tokens.contains(sp)) tokens.add(sp);
            }
        }
        if (tokens.isEmpty()) {
            for (String rt : rawTokens) {
                if (!rt.isEmpty() && !tokens.contains(rt)) tokens.add(rt);
            }
        }

        Map<String, Set<String>> stage2SynonymsByToken = new LinkedHashMap<>();
        Set<String> expandedTargets = new LinkedHashSet<>();
        if (synonyms != null) {
            for (var entry : synonyms.entrySet()) {
                String concept = entry.getKey().toLowerCase(Locale.ROOT);
                if (concept.contains(qLow) || qLow.contains(concept) || tokens.contains(concept)) {
                    expandedTargets.addAll(entry.getValue());
                }
            }
        }
        for (String tok : tokens) {
            Set<String> tokSyns = new LinkedHashSet<>();
            List<String> builtIn = BUILT_IN_NOVICE_SYNONYMS.get(tok);
            if (builtIn != null) {
                tokSyns.addAll(builtIn);
                expandedTargets.addAll(builtIn);
            }
            Set<String> sharedSyns = sharedThesaurus.expandTokens(List.of(tok));
            tokSyns.addAll(sharedSyns);
            expandedTargets.addAll(sharedSyns);
            stage2SynonymsByToken.put(tok, tokSyns);
        }

        List<ConfigSearchHit> scored = new ArrayList<>();
        for (AstConfigNode node : nodesBySymbol.values()) {
            int score = 0;
            List<String> reasons = new ArrayList<>();

            FuzzySearchEngine.ScoredCandidate baseScore = FuzzySearchEngine.scoreCandidateCascade(
                    query,
                    node.filePath(),
                    node.leafName(),
                    node.liveDiskValue(),
                    onlineExpander,
                    nowMs
            );
            if (baseScore.score() > 0) {
                score += baseScore.score();
                for (FuzzySearchEngine.MatchReason r : baseScore.reasons()) {
                    reasons.add(r.type() + ": " + r.text());
                }
            }

            // Full dotted path or artifact match
            String pathLow = node.keyPath().toLowerCase(Locale.ROOT);
            String leafLow = node.leafName().toLowerCase(Locale.ROOT);
            String artLow = node.artifactId().toLowerCase(Locale.ROOT);
            if (pathLow.equals(qLow) || leafLow.equals(qLow)) {
                score += 120;
                reasons.add("exact-path");
            } else if (pathLow.contains(qLow) && qLow.length() >= 3) {
                score += 55;
                reasons.add("path-substring");
            }
            if (artLow.contains(qLow) || node.filePath().toLowerCase(Locale.ROOT).contains(qLow)) {
                score += 35;
                reasons.add("artifact/file");
            }

            // Per-token Local-First Cascade: Stage 1 (local key/doc 1.00x) -> Stage 2 (thesaurus/pivot 0.60x) -> Stage 3 (online 0.40x)
            String[] keySegments = pathLow.split("[._-]+");
            SchemaInferrer.InvestigatoryDocResult inv = node.investigatoryDoc();
            String docBlob = ((inv != null ? (inv.playerSummary() + " " + inv.synthesizedSummary() + " "
                    + String.join(" ", inv.subsystemTags()) + " " + String.join(" ", inv.fieldFlows()) + " "
                    + String.join(" ", inv.methodCallSites())) : "")
                    + " " + (node.commentText() != null ? node.commentText() : "")
                    + " " + (node.schema() != null ? String.join(" ", node.schema().options()) : "")).toLowerCase(Locale.ROOT);

            String leafNorm = FuzzySearchEngine.normalize(node.leafName());
            String pathNorm = FuzzySearchEngine.normalize(node.keyPath());

            int matchedTokenCount = 0;
            for (String tok : tokens) {
                if (tok.length() < 2) continue;
                boolean tokKeyMatched = false;
                boolean tokMatched = false;
                if (tok.length() >= 3) {
                    for (String seg : keySegments) {
                        if (seg.equals(tok)) {
                            score += 65;
                            reasons.add("key-token:" + seg);
                            tokKeyMatched = true;
                            tokMatched = true;
                            break;
                        }
                        if (seg.length() >= 4 && tok.length() >= 4 && (seg.contains(tok) || tok.contains(seg))) {
                            score += 45;
                            reasons.add("key-stem:" + seg);
                            tokKeyMatched = true;
                            tokMatched = true;
                            break;
                        }
                        if (seg.length() >= 4 && tok.length() >= 4 && FuzzySearchEngine.isFuzzyMatch(tok, seg)) {
                            score += 40;
                            reasons.add("fuzzy-token:" + tok + "~" + seg);
                            tokKeyMatched = true;
                            tokMatched = true;
                            break;
                        }
                    }
                    if (artLow.contains(tok)) {
                        score += 45;
                        reasons.add("plugin-token:" + tok);
                        tokMatched = true;
                    }
                    if (docBlob.contains(tok)) {
                        score += 30;
                        reasons.add("ast-doc:" + tok);
                        tokMatched = true;
                    }
                }

                // Stage 2: Thesaurus & Cross-Lingual Pivot fallback (0.60x) ONLY when Stage 1 key did not match this token
                boolean stage2Matched = false;
                if (!tokKeyMatched) {
                    Set<String> tokSyns = stage2SynonymsByToken.getOrDefault(tok, Set.of());
                    for (String syn : tokSyns) {
                        String synNorm = FuzzySearchEngine.normalize(syn);
                        if (synNorm.isEmpty() || synNorm.equals(tok)) continue;
                        if (leafNorm.equals(synNorm) || pathNorm.contains(synNorm)) {
                            score += 48; // 80 * 0.60 cascade discount
                            reasons.add("synonym:" + syn);
                            stage2Matched = true;
                            tokMatched = true;
                            break;
                        } else if (docBlob.contains(syn.toLowerCase(Locale.ROOT))) {
                            score += 21; // 35 * 0.60 cascade discount
                            reasons.add("synonym-doc:" + syn);
                            stage2Matched = true;
                            tokMatched = true;
                            break;
                        }
                    }
                }

                // Stage 3: File-Cached TTL Online Expansion fallback (0.40x) ONLY when Stages 1 and 2 both missed
                if (!tokKeyMatched && !stage2Matched && onlineExpander != null) {
                    for (String remote : onlineExpander.getCachedOrScheduleAsync(tok, nowMs)) {
                        String remNorm = FuzzySearchEngine.normalize(remote);
                        if (remNorm.isEmpty()) continue;
                        if (leafNorm.equals(remNorm) || pathNorm.contains(remNorm)) {
                            score += 32; // 80 * 0.40 cascade discount
                            reasons.add("online:" + remote);
                            tokMatched = true;
                            break;
                        } else if (docBlob.contains(remote.toLowerCase(Locale.ROOT))) {
                            score += 14; // 35 * 0.40 cascade discount
                            reasons.add("online-doc:" + remote);
                            tokMatched = true;
                            break;
                        }
                    }
                }

                if (tokMatched) {
                    matchedTokenCount++;
                }
            }
            if (matchedTokenCount >= 2) {
                score += matchedTokenCount * 25;
            }

            // Bytecode READ_BY_CLASS consumer match
            for (String cls : node.readByClasses()) {
                String clsLow = cls.toLowerCase(Locale.ROOT);
                String clsNorm = FuzzySearchEngine.normalize(cls);
                if (clsLow.contains(qLow) || (!qNorm.isEmpty() && clsNorm.contains(qNorm))) {
                    score += 75;
                    reasons.add("read-by-class:" + cls);
                    break;
                }
                for (String tok : tokens) {
                    if (tok.length() >= 3 && clsLow.contains(tok)) {
                        score += 35;
                        reasons.add("class-token:" + cls);
                        break;
                    }
                }
            }

            if (score > 0) {
                scored.add(new ConfigSearchHit(node, score, reasons));
            }
        }

        scored.sort(Comparator.comparingInt(ConfigSearchHit::score).reversed()
                .thenComparing(h -> h.node().symbolId()));
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    public Map<String, JarConfigScanner.HarvestedArtifact> artifacts() {
        return Collections.unmodifiableMap(artifacts);
    }

    public Map<String, AstConfigNode> nodesBySymbol() {
        return Collections.unmodifiableMap(nodesBySymbol);
    }

    public List<AstEntityBinding> entityBindings() {
        return Collections.unmodifiableList(entityBindings);
    }

    public List<AstConfigEdge> edges() {
        return Collections.unmodifiableList(edges);
    }

    public List<SpatialGizmoDescriptor> spatialGizmos() {
        return Collections.unmodifiableList(spatialGizmos);
    }

    public Map<String, SchemaInferrer.InvestigatoryDocResult> investigatoryCache() {
        return Collections.unmodifiableMap(investigatoryCache);
    }

    /**
     * Serializes the atlas summary and AST investigatory documentation dossiers into a JSON object
     * suitable for embedding in the Web IDE session payload.
     */
    public String toWebIdePayloadJson() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"artifacts\":[");
        int aIdx = 0;
        for (JarConfigScanner.HarvestedArtifact art : artifacts.values()) {
            if (aIdx++ > 0) sb.append(",");
            int modifiedCount = 0;
            for (AstConfigNode n : nodesBySymbol.values()) {
                if (n.artifactId().equals(art.artifactId()) && n.isModified()) {
                    modifiedCount++;
                }
            }
            sb.append("{\"artifactId\":\"").append(SchemaInferrer.escapeJson(art.artifactId())).append("\"")
                    .append(",\"displayName\":\"").append(SchemaInferrer.escapeJson(art.displayName())).append("\"")
                    .append(",\"version\":\"").append(SchemaInferrer.escapeJson(art.version())).append("\"")
                    .append(",\"loaderFamily\":\"").append(SchemaInferrer.escapeJson(art.loaderFamily())).append("\"")
                    .append(",\"reloadStrategy\":\"").append(art.reloadStrategy().name()).append("\"")
                    .append(",\"modifiedCount\":").append(modifiedCount)
                    .append("}");
        }
        sb.append("],\"spatialGizmos\":[");
        for (int i = 0; i < spatialGizmos.size(); i++) {
            if (i > 0) sb.append(",");
            SpatialGizmoDescriptor g = spatialGizmos.get(i);
            JarConfigScanner.HarvestedArtifact art = artifacts.get(g.artifactId());
            String prefixedGizmoFile = g.filePath();
            if (art != null && !prefixedGizmoFile.startsWith("plugins/") && !prefixedGizmoFile.startsWith("config/")) {
                if (g.artifactId().startsWith("plugin:") && !"plugin:leafrtp".equals(g.artifactId())) {
                    prefixedGizmoFile = "plugins/" + art.displayName() + "/" + g.filePath();
                } else if (g.artifactId().startsWith("mod:")) {
                    prefixedGizmoFile = "config/" + g.filePath();
                }
            }
            sb.append("{\"gizmoId\":\"").append(SchemaInferrer.escapeJson(g.gizmoId())).append("\"")
                    .append(",\"artifactId\":\"").append(SchemaInferrer.escapeJson(g.artifactId())).append("\"")
                    .append(",\"filePath\":\"").append(SchemaInferrer.escapeJson(prefixedGizmoFile)).append("\"")
                    .append(",\"scopeKey\":\"").append(SchemaInferrer.escapeJson(g.scopeKey())).append("\"")
                    .append(",\"world\":\"").append(SchemaInferrer.escapeJson(g.worldName())).append("\"")
                    .append(",\"centerX\":").append(g.centerX())
                    .append(",\"centerZ\":").append(g.centerZ())
                    .append(",\"radius\":").append(g.radius())
                    .append(",\"innerRadius\":").append(g.innerRadius())
                    .append("}");
        }
        sb.append("],\"astNodes\":{");
        int nIdx = 0;
        for (AstConfigNode n : nodesBySymbol.values()) {
            if (nIdx++ > 0) sb.append(",");
            JarConfigScanner.HarvestedArtifact art = artifacts.get(n.artifactId());
            String prefixedFile = n.filePath();
            if (art != null && !prefixedFile.startsWith("plugins/") && !prefixedFile.startsWith("config/")) {
                if (n.artifactId().startsWith("plugin:") && !"plugin:leafrtp".equals(n.artifactId())) {
                    prefixedFile = "plugins/" + art.displayName() + "/" + n.filePath();
                } else if (n.artifactId().startsWith("mod:")) {
                    prefixedFile = "config/" + n.filePath();
                }
            }
            String nodeLookupKey = prefixedFile + "#" + n.keyPath();
            SchemaInferrer.InvestigatoryDocResult inv = n.investigatoryDoc();
            String dispName = art != null ? art.displayName() : n.artifactId();
            List<BytecodeConfigScanner.KeyCallSite> sites = art != null
                    ? findCallSitesForKey(art.keyCallSiteIndex(), n.keyPath())
                    : List.of();
            String valStr = n.liveDiskValue() != null ? n.liveDiskValue() : n.jarDefaultValue();
            String defStr = n.jarDefaultValue();
            SchemaInferrer.InvestigatoryDocResult p1 = SchemaInferrer.synthesizeInvestigatoryDoc(
                    dispName, n.filePath(), n.keyPath(), valStr, defStr, n.commentText(), n.schema(), n.readByClasses(), sites, 1, null);
            SchemaInferrer.InvestigatoryDocResult p2 = SchemaInferrer.synthesizeInvestigatoryDoc(
                    dispName, n.filePath(), n.keyPath(), valStr, defStr, n.commentText(), n.schema(), n.readByClasses(), sites, 2, p1);
            SchemaInferrer.InvestigatoryDocResult p3 = (n.schema().provenance() == SchemaInferrer.DocProvenance.LLM_SYNTHESIZED || inv.investigationPass() >= 3)
                    ? inv
                    : SchemaInferrer.synthesizeInvestigatoryDoc(
                            dispName, n.filePath(), n.keyPath(), valStr, defStr, n.commentText(), n.schema(), n.readByClasses(), sites, 3, p2);
            sb.append("\"").append(SchemaInferrer.escapeJson(nodeLookupKey)).append("\":{")
                    .append("\"symbolId\":\"").append(SchemaInferrer.escapeJson(n.symbolId())).append("\"")
                    .append(",\"artifactId\":\"").append(SchemaInferrer.escapeJson(n.artifactId())).append("\"")
                    .append(",\"filePath\":\"").append(SchemaInferrer.escapeJson(prefixedFile)).append("\"")
                    .append(",\"keyPath\":\"").append(SchemaInferrer.escapeJson(n.keyPath())).append("\"")
                    .append(",\"leafName\":\"").append(SchemaInferrer.escapeJson(n.leafName())).append("\"")
                    .append(",\"jarDefaultValue\":").append(n.jarDefaultValue() == null ? "null" : ("\"" + SchemaInferrer.escapeJson(n.jarDefaultValue()) + "\""))
                    .append(",\"liveDiskValue\":").append(n.liveDiskValue() == null ? "null" : ("\"" + SchemaInferrer.escapeJson(n.liveDiskValue()) + "\""))
                    .append(",\"isModified\":").append(n.isModified())
                    .append(",\"commentRestored\":").append(n.commentRestored())
                    .append(",\"inferredType\":\"").append(SchemaInferrer.escapeJson(n.schema().inferredType())).append("\"")
                    .append(",\"unit\":").append(n.schema().unit() == null ? "null" : ("\"" + SchemaInferrer.escapeJson(n.schema().unit()) + "\""))
                    .append(",\"rangeMin\":").append(n.schema().rangeMin() != null && Double.isFinite(n.schema().rangeMin()) ? n.schema().rangeMin() : "null")
                    .append(",\"rangeMax\":").append(n.schema().rangeMax() != null && Double.isFinite(n.schema().rangeMax()) ? n.schema().rangeMax() : "null")
                    .append(",\"provenance\":\"").append(n.schema().provenance().name()).append("\"")
                    .append(",\"llmProvider\":\"").append(SchemaInferrer.escapeJson(activeLlmHookConfig.provider().name())).append("\"")
                    .append(",\"llmModel\":\"").append(SchemaInferrer.escapeJson(activeLlmHookConfig.model())).append("\"")
                    .append(",\"reloadTier\":\"").append(n.schema().reloadTier().name()).append("\"")
                    .append(",\"spatialGizmo\":\"").append(n.schema().spatialGizmo().name()).append("\"")
                    .append(",\"options\":").append(toJsonStringArray(n.schema().options()))
                    .append(",\"readByClasses\":").append(toJsonStringArray(n.readByClasses()))
                    .append(",\"investigationPass\":").append(inv.investigationPass())
                    .append(",\"confidenceScore\":").append(inv.confidenceScore())
                    .append(",\"confidenceTier\":\"").append(inv.confidenceTier().name()).append("\"")
                    .append(",\"confidenceHistory\":").append(inv.confidenceHistory().toString())
                    .append(",\"needsFurtherInvestigation\":").append(inv.needsFurtherInvestigation())
                    .append(",\"synthesizedSummary\":\"").append(SchemaInferrer.escapeJson(inv.synthesizedSummary())).append("\"")
                    .append(",\"playerSummary\":\"").append(SchemaInferrer.escapeJson(inv.playerSummary())).append("\"")
                    .append(",\"whatChanges\":").append(toJsonStringArray(inv.whatChanges()))
                    .append(",\"evidenceChain\":").append(toJsonStringArray(inv.evidenceChain()))
                    .append(",\"methodCallSites\":").append(toJsonStringArray(inv.methodCallSites()))
                    .append(",\"fieldFlows\":").append(toJsonStringArray(inv.fieldFlows()))
                    .append(",\"subsystemTags\":").append(toJsonStringArray(inv.subsystemTags()))
                    .append(",\"_passSnapshots\":[null,")
                    .append(toPassSnapshotJson(p1)).append(",")
                    .append(toPassSnapshotJson(p2)).append(",")
                    .append(toPassSnapshotJson(p3)).append("]")
                    .append("}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String toPassSnapshotJson(SchemaInferrer.InvestigatoryDocResult d) {
        return "{\"investigationPass\":" + d.investigationPass()
                + ",\"confidenceScore\":" + d.confidenceScore()
                + ",\"confidenceTier\":\"" + d.confidenceTier().name() + "\""
                + ",\"confidenceHistory\":" + d.confidenceHistory().toString()
                + ",\"needsFurtherInvestigation\":" + d.needsFurtherInvestigation()
                + ",\"synthesizedSummary\":\"" + SchemaInferrer.escapeJson(d.synthesizedSummary()) + "\""
                + ",\"playerSummary\":\"" + SchemaInferrer.escapeJson(d.playerSummary()) + "\""
                + ",\"whatChanges\":" + toJsonStringArray(d.whatChanges())
                + ",\"evidenceChain\":" + toJsonStringArray(d.evidenceChain())
                + "}";
    }

    private static String toJsonStringArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(SchemaInferrer.escapeJson(items.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatScalarOrList(Object val) {
        if (val == null) return null;
        if (val instanceof Collection<?> col) {
            return col.toString();
        }
        return String.valueOf(val);
    }

    private static Double parseDoubleNumeric(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().replaceAll("(?i)(blocks|b|chunks|c|regions|r)$", "").trim();
        try {
            double v = Double.parseDouble(cleaned);
            if (raw.toLowerCase(Locale.ROOT).endsWith("c") || raw.toLowerCase(Locale.ROOT).endsWith("chunks")) {
                v *= 16.0;
            } else if (raw.toLowerCase(Locale.ROOT).endsWith("r") || raw.toLowerCase(Locale.ROOT).endsWith("regions")) {
                v *= 512.0;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
