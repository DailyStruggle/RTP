package io.github.dailystruggle.bytecode;

import io.github.dailystruggle.yaml.YamlConfig;
import io.github.dailystruggle.yaml.YamlSection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Three-plane configuration and metadata harvester ({@code leafconfig-ADR-001} Section 1).
 *
 * <p>Operates strictly off the main tick thread ({@code S-005}) to:
 * <ol>
 *   <li><b>Plane 1 (Embedded JAR Introspection):</b> Read-only {@link ZipFile} scan of
 *       {@code plugins/*.jar} and {@code mods/*.jar} for loader descriptors
 *       ({@code plugin.yml}, {@code paper-plugin.yml}, {@code bungee.yml},
 *       {@code velocity-plugin.json}, {@code fabric.mod.json},
 *       {@code META-INF/neoforge.mods.toml}), pristine default configuration files and comments,
 *       and zero-classloading {@link BytecodeConfigScanner} {@code READ_BY_CLASS} / enum edges.</li>
 *   <li><b>Plane 2 (Live Server Disk Working Tree):</b> Scans live engine and plugin configuration
 *       files on disk, computes {@code jar_default_value} vs. {@code live_disk_value} diffs, and
 *       restores author comments stripped by third-party {@code JavaPlugin#saveConfig()} calls.</li>
 * </ol>
 *
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @governedBy leafconfig-ADR-001
 * @governedBy leafconfig-ADR-002
 * @trace REQ-CFG-ATLAS-001
 * @trace REQ-CFG-ATLAS-003
 */
public final class JarConfigScanner {

    private static final int MAX_RESOURCE_BYTES = 1024 * 1024; // 1 MiB cap per embedded config/class
    private static final int MAX_CLASSES_PER_JAR = 4096;

    private static final Set<String> DESCRIPTOR_FILES = Set.of(
            "plugin.yml",
            "paper-plugin.yml",
            "bungee.yml",
            "velocity-plugin.json",
            "fabric.mod.json",
            "META-INF/neoforge.mods.toml",
            "META-INF/mods.toml"
    );

    private static final Pattern JSON_ID_RE = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_NAME_RE = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_VERSION_RE = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_HOMEPAGE_RE = Pattern.compile("\"(?:homepage|url|website)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_MAIN_RE = Pattern.compile("\"main\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_ENTRYPOINT_RE =
            Pattern.compile("\"(?:main|server|client)\"\\s*:\\s*\\[\\s*(?:\\{[^}]*\"value\"\\s*:\\s*)?\"([A-Za-z0-9_$.]+)(?:::[^\"]*)?\"");
    private static final Pattern TOML_MOD_ID_RE = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_DISPLAY_NAME_RE = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_VERSION_RE = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");

    /** Reload orchestration strategy for an installed artifact ({@code leafconfig-ADR-002} Section 5). */
    public enum ReloadStrategy {
        NATIVE_SPI,
        COMMAND_DISPATCH,
        BUKKIT_FILECONFIG_INJECT,
        DEFER_UNTIL_SHUTDOWN
    }

    /**
     * Structured command metadata harvested from {@code plugin.yml} or {@code paper-plugin.yml}
     * in the single-pass JAR scan ({@code leafguide-ADR-001}).
     *
     * @param name              canonical top-level command label (without leading slash)
     * @param pluginDisplayName owning plugin or mod display name
     * @param description       author-supplied command description
     * @param usage             usage template (e.g. {@code /rtp [world]})
     * @param aliases           registered command aliases
     * @param permission        declared permission node, or empty if undeclared
     * @param permissionDefault declared permission default ({@code true}, {@code op}, {@code not op}, {@code false}, or empty)
     */
    public record CommandDescriptor(
            String name,
            String pluginDisplayName,
            String description,
            String usage,
            List<String> aliases,
            String permission,
            String permissionDefault
    ) {
        public CommandDescriptor {
            Objects.requireNonNull(name, "name");
            pluginDisplayName = pluginDisplayName == null ? "" : pluginDisplayName;
            description = description == null ? "" : description;
            usage = (usage == null || usage.isBlank()) ? "/" + name : usage;
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            permission = permission == null ? "" : permission;
            permissionDefault = permissionDefault == null ? "" : permissionDefault;
        }
    }

    /**
     * Harvested artifact metadata and Plane 1 resources from a single plugin/mod JAR or engine baseline.
     *
     * @param artifactId           canonical artifact identifier (e.g. {@code plugin:leafrtp}, {@code engine:paper})
     * @param displayName          human-readable plugin or engine name
     * @param version              version string
     * @param loaderFamily         loader family ({@code paper}, {@code bukkit}, {@code velocity}, {@code fabric}, {@code neoforge}, {@code engine})
     * @param jarSha512            hex SHA-512 digest of the source JAR, or empty for engine baselines
     * @param docSourceUrl         website or documentation URL from descriptor
     * @param commands             registered top-level command names
     * @param permissions          declared permission nodes
     * @param dependencies         declared plugin dependencies ({@code depend} + {@code softdepend})
     * @param reloadStrategy       classified reload strategy ({@code leafconfig-ADR-002} §5)
     * @param embeddedConfigs      map of relative config path to pristine Plane 1 file content
     * @param readByClassIndex     map of config key path to consuming Java class names ({@code READ_BY_CLASS})
     * @param discoveredEnums      map of enum binary class name to declared enum constant names
     * @param savesConfigOnDisable whether any class in the JAR calls {@code saveConfig()} inside {@code onDisable()}
     * @param keyCallSiteIndex     map of config key path to bytecode call-site descriptors
     * @param commandDescriptors   structured command metadata harvested from descriptor files
     * @param embeddedPlayerDocs   embedded player-facing book/guide resources (Patchouli, Lavender, {@code book.txt}, {@code rules.txt})
     * @param declaredMainClass    declared main or entrypoint binary class name from the loader descriptor, or empty if undeclared
     * @param mainClassPresent     {@code true} when {@code declaredMainClass} is empty or its {@code .class} entry exists inside the archive
     * @param scannedClassCount    number of non-shaded {@code .class} entries scanned inside the archive
     */
    public record HarvestedArtifact(
            String artifactId,
            String displayName,
            String version,
            String loaderFamily,
            String jarSha512,
            String docSourceUrl,
            List<String> commands,
            List<String> permissions,
            List<String> dependencies,
            ReloadStrategy reloadStrategy,
            Map<String, String> embeddedConfigs,
            Map<String, Set<String>> readByClassIndex,
            Map<String, List<String>> discoveredEnums,
            boolean savesConfigOnDisable,
            Map<String, List<BytecodeConfigScanner.KeyCallSite>> keyCallSiteIndex,
            List<CommandDescriptor> commandDescriptors,
            Map<String, String> embeddedPlayerDocs,
            String declaredMainClass,
            boolean mainClassPresent,
            int scannedClassCount
    ) {
        public HarvestedArtifact(
                String artifactId,
                String displayName,
                String version,
                String loaderFamily,
                String jarSha512,
                String docSourceUrl,
                List<String> commands,
                List<String> permissions,
                List<String> dependencies,
                ReloadStrategy reloadStrategy,
                Map<String, String> embeddedConfigs,
                Map<String, Set<String>> readByClassIndex,
                Map<String, List<String>> discoveredEnums,
                boolean savesConfigOnDisable,
                Map<String, List<BytecodeConfigScanner.KeyCallSite>> keyCallSiteIndex,
                List<CommandDescriptor> commandDescriptors,
                Map<String, String> embeddedPlayerDocs
        ) {
            this(
                    artifactId,
                    displayName,
                    version,
                    loaderFamily,
                    jarSha512,
                    docSourceUrl,
                    commands,
                    permissions,
                    dependencies,
                    reloadStrategy,
                    embeddedConfigs,
                    readByClassIndex,
                    discoveredEnums,
                    savesConfigOnDisable,
                    keyCallSiteIndex,
                    commandDescriptors,
                    embeddedPlayerDocs,
                    "",
                    true,
                    0
            );
        }

        public HarvestedArtifact(
                String artifactId,
                String displayName,
                String version,
                String loaderFamily,
                String jarSha512,
                String docSourceUrl,
                List<String> commands,
                List<String> permissions,
                List<String> dependencies,
                ReloadStrategy reloadStrategy,
                Map<String, String> embeddedConfigs,
                Map<String, Set<String>> readByClassIndex,
                Map<String, List<String>> discoveredEnums,
                boolean savesConfigOnDisable
        ) {
            this(
                    artifactId,
                    displayName,
                    version,
                    loaderFamily,
                    jarSha512,
                    docSourceUrl,
                    commands,
                    permissions,
                    dependencies,
                    reloadStrategy,
                    embeddedConfigs,
                    readByClassIndex,
                    discoveredEnums,
                    savesConfigOnDisable,
                    Map.of(),
                    List.of(),
                    Map.of(),
                    "",
                    true,
                    0
            );
        }

        public HarvestedArtifact(
                String artifactId,
                String displayName,
                String version,
                String loaderFamily,
                String jarSha512,
                String docSourceUrl,
                List<String> commands,
                List<String> permissions,
                List<String> dependencies,
                ReloadStrategy reloadStrategy,
                Map<String, String> embeddedConfigs,
                Map<String, Set<String>> readByClassIndex,
                Map<String, List<String>> discoveredEnums,
                boolean savesConfigOnDisable,
                Map<String, List<BytecodeConfigScanner.KeyCallSite>> keyCallSiteIndex
        ) {
            this(
                    artifactId,
                    displayName,
                    version,
                    loaderFamily,
                    jarSha512,
                    docSourceUrl,
                    commands,
                    permissions,
                    dependencies,
                    reloadStrategy,
                    embeddedConfigs,
                    readByClassIndex,
                    discoveredEnums,
                    savesConfigOnDisable,
                    keyCallSiteIndex,
                    List.of(),
                    Map.of(),
                    "",
                    true,
                    0
            );
        }

        public HarvestedArtifact {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(displayName, "displayName");
            version = version == null ? "unknown" : version;
            loaderFamily = loaderFamily == null ? "bukkit" : loaderFamily;
            jarSha512 = jarSha512 == null ? "" : jarSha512;
            docSourceUrl = docSourceUrl == null ? "" : docSourceUrl;
            commands = commands == null ? List.of() : List.copyOf(commands);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            reloadStrategy = reloadStrategy == null ? ReloadStrategy.BUKKIT_FILECONFIG_INJECT : reloadStrategy;
            embeddedConfigs = embeddedConfigs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(embeddedConfigs));
            readByClassIndex = readByClassIndex == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(readByClassIndex));
            discoveredEnums = discoveredEnums == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(discoveredEnums));
            keyCallSiteIndex = keyCallSiteIndex == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(keyCallSiteIndex));
            commandDescriptors = commandDescriptors == null ? List.of() : List.copyOf(commandDescriptors);
            embeddedPlayerDocs = embeddedPlayerDocs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(embeddedPlayerDocs));
            declaredMainClass = declaredMainClass == null ? "" : declaredMainClass.trim();
            scannedClassCount = Math.max(0, scannedClassCount);
        }
    }

    private JarConfigScanner() {
    }

    /**
     * Scans a plugin or mod {@code .jar} archive off-thread using read-only {@link ZipFile} and
     * {@link BytecodeConfigScanner} without loading any class.
     *
     * @param jarPath path to the {@code .jar} file
     * @return harvested Plane 1 artifact record
     * @throws IOException if the archive cannot be read
     */
    public static HarvestedArtifact scanJar(Path jarPath) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        String sha512 = computeSha512Hex(jarPath);

        Map<String, String> descriptors = new LinkedHashMap<>();
        Map<String, String> embeddedConfigs = new LinkedHashMap<>();
        Map<String, String> embeddedPlayerDocs = new LinkedHashMap<>();
        List<BytecodeConfigScanner.ClassScanResult> classResults = new ArrayList<>();
        Map<String, List<String>> enumMap = new LinkedHashMap<>();
        Set<String> allClassBinaryNames = new LinkedHashSet<>();
        boolean savesOnDisable = false;
        boolean hasDatapackOrAssetPayload = false;
        int scannedClasses = 0;

        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.endsWith(".class") && name.length() > 6) {
                    allClassBinaryNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
                } else if (name.startsWith("data/") || name.startsWith("assets/") || "pack.mcmeta".equals(name)) {
                    hasDatapackOrAssetPayload = true;
                } else if (name.endsWith(".jar")
                        && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) {
                    scannedClasses += scanNestedJarClassNames(zip, entry, allClassBinaryNames);
                }
                long size = entry.getSize();
                if (size > MAX_RESOURCE_BYTES) {
                    continue;
                }

                if (DESCRIPTOR_FILES.contains(name)) {
                    byte[] bytes = readBoundedBytes(zip, entry);
                    if (bytes != null) {
                        descriptors.put(name, new String(bytes, StandardCharsets.UTF_8));
                    }
                } else if (isCandidatePlayerDocResource(name)) {
                    byte[] bytes = readBoundedBytes(zip, entry);
                    if (bytes != null) {
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        embeddedPlayerDocs.put(name, text);
                        if (isCandidateConfigResource(name)) {
                            embeddedConfigs.put(name, text);
                        }
                    }
                } else if (isCandidateConfigResource(name)) {
                    byte[] bytes = readBoundedBytes(zip, entry);
                    if (bytes != null) {
                        embeddedConfigs.put(name, new String(bytes, StandardCharsets.UTF_8));
                    }
                } else if (name.endsWith(".class") && scannedClasses < MAX_CLASSES_PER_JAR && !isShadedLibraryClass(name)) {
                    byte[] bytes = readBoundedBytes(zip, entry);
                    if (bytes != null) {
                        scannedClasses++;
                        BytecodeConfigScanner.ClassScanResult res = BytecodeConfigScanner.scanClassBytes(bytes);
                        if (res != null) {
                            classResults.add(res);
                            if (res.isEnum() && !res.enumConstants().isEmpty()) {
                                enumMap.put(res.className(), res.enumConstants());
                            }
                            if (res.hasSaveConfigOnDisable()) {
                                savesOnDisable = true;
                            }
                        }
                    }
                }
            }
        }

        DescriptorMeta meta = parseDescriptorMetadata(descriptors, jarPath.getFileName().toString());
        Map<String, Set<String>> readByClass = BytecodeConfigScanner.buildReadByClassIndex(classResults);
        Map<String, List<BytecodeConfigScanner.KeyCallSite>> keyCallSites = BytecodeConfigScanner.buildKeyCallSiteIndex(classResults);
        ReloadStrategy reloadStrategy = classifyReloadStrategy(meta, savesOnDisable);
        String declaredMain = meta.declaredMainClass == null ? "" : meta.declaredMainClass.trim();
        boolean mainPresent = declaredMain.isEmpty() || allClassBinaryNames.contains(declaredMain.replace('/', '.'));
        if (scannedClasses == 0 && hasDatapackOrAssetPayload && !descriptors.isEmpty() && declaredMain.isEmpty()) {
            scannedClasses = 1;
        }

        return new HarvestedArtifact(
                meta.artifactId,
                meta.displayName,
                meta.version,
                meta.loaderFamily,
                sha512,
                meta.website,
                meta.commands,
                meta.permissions,
                meta.dependencies,
                reloadStrategy,
                embeddedConfigs,
                readByClass,
                enumMap,
                savesOnDisable,
                keyCallSites,
                meta.commandDescriptors,
                embeddedPlayerDocs,
                declaredMain,
                mainPresent,
                scannedClasses
        );
    }

    /**
     * Restores author comments from a pristine Plane 1 YAML template onto a live Plane 2 disk YAML
     * document whose comments were stripped by {@code JavaPlugin#saveConfig()} ({@code leafconfig-ADR-001} §1 &amp; §4).
     *
     * <p>Preserves all live disk scalar/list values and any keys added on disk while re-attaching
     * missing block comments from {@code jarDefaultYaml}.
     *
     * @param jarDefaultYaml pristine YAML content from inside the plugin JAR
     * @param liveDiskYaml   live YAML content on disk (potentially comment-stripped)
     * @return merged YAML string with live values and restored author comments
     */
    public static String restoreStrippedComments(String jarDefaultYaml, String liveDiskYaml) {
        return restoreStrippedComments("config.yml", jarDefaultYaml, liveDiskYaml);
    }

    /**
     * Format-aware comment restoration across YAML, TOML, Properties, JSONC/JSON5/HOCON, and strict JSON
     * ({@code REQ-CFG-ATLAS-003}).
     *
     * <p>Strict {@code .json} files are returned unchanged on disk so they remain 100% RFC 8259 compliant,
     * whereas {@code .jsonc}, {@code .json5}, and {@code .conf} files receive {@code //} line comments and
     * {@code .toml}/{@code .properties} files receive {@code #} line comments.
     *
     * @param filePath       relative configuration file path (used to determine format rules)
     * @param jarDefaultText pristine template content from inside the plugin or mod JAR
     * @param liveDiskText   live configuration content on disk
     * @return merged configuration text with live values and restored author comments
     */
    public static String restoreStrippedComments(String filePath, String jarDefaultText, String liveDiskText) {
        if (liveDiskText == null || liveDiskText.isBlank()) {
            return jarDefaultText == null ? "" : jarDefaultText;
        }
        if (jarDefaultText == null || jarDefaultText.isBlank()) {
            return liveDiskText;
        }
        String lowPath = filePath == null ? "config.yml" : filePath.toLowerCase(Locale.ROOT);
        if (lowPath.endsWith(".json")) {
            // Strict RFC 8259 JSON does not permit // or # comments on disk
            return liveDiskText;
        }
        if (lowPath.endsWith(".jsonc") || lowPath.endsWith(".json5") || lowPath.endsWith(".conf")
                || lowPath.endsWith(".toml") || lowPath.endsWith(".properties")) {
            Map<String, List<String>> defaultComments = extractFormatCommentsByKeyPath(lowPath, jarDefaultText);
            if (defaultComments.isEmpty()) {
                return liveDiskText;
            }
            return injectMissingFormatComments(lowPath, defaultComments, liveDiskText, new int[1]);
        }
        try {
            YamlConfig defaults = YamlConfig.parse(jarDefaultText);
            YamlConfig live = YamlConfig.parse(liveDiskText);
            for (String key : defaults.getKeys(true)) {
                if (!live.contains(key)) {
                    continue;
                }
                String liveComment = live.getComment(key);
                String defaultComment = defaults.getComment(key);
                if ((liveComment == null || liveComment.isBlank())
                        && defaultComment != null && !defaultComment.isBlank()) {
                    live.setComment(key, defaultComment);
                }
            }
            return live.saveToString();
        } catch (RuntimeException e) {
            return liveDiskText;
        }
    }

    /**
     * Counts how many comments would be restored on {@code liveDiskYaml} from {@code jarDefaultYaml}.
     *
     * @param jarDefaultYaml pristine Plane 1 YAML
     * @param liveDiskYaml   live Plane 2 YAML
     * @return number of keys in {@code liveDiskYaml} missing comments that exist in {@code jarDefaultYaml}
     */
    public static int countRestorableComments(String jarDefaultYaml, String liveDiskYaml) {
        return countRestorableComments("config.yml", jarDefaultYaml, liveDiskYaml);
    }

    /**
     * Format-aware count of comments that can be restored onto {@code liveDiskText} from {@code jarDefaultText}
     * ({@code REQ-CFG-ATLAS-003}).
     *
     * <p>Returns {@code 0} for strict {@code .json} files so RFC 8259 validity is preserved on disk.
     */
    public static int countRestorableComments(String filePath, String jarDefaultText, String liveDiskText) {
        if (jarDefaultText == null || jarDefaultText.isBlank() || liveDiskText == null || liveDiskText.isBlank()) {
            return 0;
        }
        String lowPath = filePath == null ? "config.yml" : filePath.toLowerCase(Locale.ROOT);
        if (lowPath.endsWith(".json")) {
            return 0;
        }
        if (lowPath.endsWith(".jsonc") || lowPath.endsWith(".json5") || lowPath.endsWith(".conf")
                || lowPath.endsWith(".toml") || lowPath.endsWith(".properties")) {
            Map<String, List<String>> defaultComments = extractFormatCommentsByKeyPath(lowPath, jarDefaultText);
            if (defaultComments.isEmpty()) {
                return 0;
            }
            int[] count = new int[1];
            injectMissingFormatComments(lowPath, defaultComments, liveDiskText, count);
            return count[0];
        }
        try {
            YamlConfig defaults = YamlConfig.parse(jarDefaultText);
            YamlConfig live = YamlConfig.parse(liveDiskText);
            int count = 0;
            for (String key : defaults.getKeys(true)) {
                if (!live.contains(key)) continue;
                String liveComment = live.getComment(key);
                String defComment = defaults.getComment(key);
                if ((liveComment == null || liveComment.isBlank()) && defComment != null && !defComment.isBlank()) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Map<String, List<String>> extractFormatCommentsByKeyPath(String lowPath, String rawText) {
        boolean isJsonLike = lowPath.endsWith(".jsonc") || lowPath.endsWith(".json5") || lowPath.endsWith(".conf");
        boolean isToml = lowPath.endsWith(".toml");
        Map<String, List<String>> commentsByKey = new LinkedHashMap<>();
        List<String> pathStack = new ArrayList<>();
        String currentSection = "";
        List<String> pendingComments = new ArrayList<>();
        boolean inBlockComment = false;

        for (String line : rawText.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!isJsonLike) {
                    pendingComments.clear();
                }
                continue;
            }
            if (inBlockComment) {
                int endIdx = trimmed.indexOf("*/");
                String body = endIdx >= 0 ? trimmed.substring(0, endIdx) : trimmed;
                String cleaned = stripCommentLeader(body);
                if (!cleaned.isEmpty()) {
                    pendingComments.add(cleaned);
                }
                if (endIdx >= 0) {
                    inBlockComment = false;
                }
                continue;
            }
            if (isJsonLike && trimmed.startsWith("/*")) {
                int endIdx = trimmed.indexOf("*/", 2);
                String body = endIdx >= 0 ? trimmed.substring(2, endIdx) : trimmed.substring(2);
                String cleaned = stripCommentLeader(body);
                if (!cleaned.isEmpty()) {
                    pendingComments.add(cleaned);
                }
                if (endIdx < 0) {
                    inBlockComment = true;
                }
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || (!isJsonLike && !isToml && trimmed.startsWith("!"))) {
                String cleaned = stripCommentLeader(trimmed);
                if (!cleaned.isEmpty()) {
                    pendingComments.add(cleaned);
                }
                continue;
            }
            if (isToml && trimmed.startsWith("[") && trimmed.contains("]")) {
                int close = trimmed.lastIndexOf(']');
                currentSection = trimmed.substring(0, close + 1)
                        .replaceAll("^\\[+|\\]+$", "")
                        .trim()
                        .replace("\"", "")
                        .replace("'", "");
                pendingComments.clear();
                continue;
            }
            if (isJsonLike && (trimmed.startsWith("}") || trimmed.startsWith("]"))) {
                if (trimmed.startsWith("}") && !pathStack.isEmpty()) {
                    pathStack.remove(pathStack.size() - 1);
                }
                pendingComments.clear();
                continue;
            }
            ParsedFormatLine parsed = parseFormatKeyLine(trimmed, isJsonLike, isToml);
            if (parsed == null) {
                continue;
            }
            String fullKey;
            if (isJsonLike) {
                fullKey = pathStack.isEmpty() ? parsed.key : (String.join(".", pathStack) + "." + parsed.key);
            } else if (isToml) {
                fullKey = currentSection.isEmpty() ? parsed.key : (currentSection + "." + parsed.key);
            } else {
                fullKey = parsed.key;
            }
            if (!pendingComments.isEmpty()) {
                commentsByKey.put(fullKey, new ArrayList<>(pendingComments));
                pendingComments.clear();
            }
            if (isJsonLike && parsed.opensObject) {
                pathStack.add(parsed.key);
            }
        }
        return commentsByKey;
    }

    private static String injectMissingFormatComments(
            String lowPath,
            Map<String, List<String>> defaultComments,
            String liveDiskText,
            int[] restoredCountOut
    ) {
        boolean isJsonLike = lowPath.endsWith(".jsonc") || lowPath.endsWith(".json5") || lowPath.endsWith(".conf");
        boolean isToml = lowPath.endsWith(".toml");
        String commentPrefix = isJsonLike ? "// " : "# ";
        String lineSep = liveDiskText.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = liveDiskText.split("\r?\n", -1);
        StringBuilder out = new StringBuilder(liveDiskText.length() + 256);
        List<String> pathStack = new ArrayList<>();
        String currentSection = "";
        boolean hasPrecedingLiveComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                hasPrecedingLiveComment = true;
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            if (trimmed.isEmpty()) {
                hasPrecedingLiveComment = false;
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            if (isJsonLike && trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlockComment = true;
                }
                hasPrecedingLiveComment = true;
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || (!isJsonLike && !isToml && trimmed.startsWith("!"))) {
                hasPrecedingLiveComment = true;
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            if (isToml && trimmed.startsWith("[") && trimmed.contains("]")) {
                int close = trimmed.lastIndexOf(']');
                currentSection = trimmed.substring(0, close + 1)
                        .replaceAll("^\\[+|\\]+$", "")
                        .trim()
                        .replace("\"", "")
                        .replace("'", "");
                hasPrecedingLiveComment = false;
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            if (isJsonLike && (trimmed.startsWith("}") || trimmed.startsWith("]"))) {
                if (trimmed.startsWith("}") && !pathStack.isEmpty()) {
                    pathStack.remove(pathStack.size() - 1);
                }
                hasPrecedingLiveComment = false;
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            ParsedFormatLine parsed = parseFormatKeyLine(trimmed, isJsonLike, isToml);
            if (parsed != null) {
                String fullKey;
                if (isJsonLike) {
                    fullKey = pathStack.isEmpty() ? parsed.key : (String.join(".", pathStack) + "." + parsed.key);
                } else if (isToml) {
                    fullKey = currentSection.isEmpty() ? parsed.key : (currentSection + "." + parsed.key);
                } else {
                    fullKey = parsed.key;
                }
                List<String> defCommentLines = defaultComments.get(fullKey);
                if (!hasPrecedingLiveComment && !parsed.hasInlineComment
                        && defCommentLines != null && !defCommentLines.isEmpty()) {
                    String indent = leadingIndent(line);
                    for (String cmt : defCommentLines) {
                        out.append(indent).append(commentPrefix).append(cmt).append(lineSep);
                    }
                    restoredCountOut[0]++;
                }
                hasPrecedingLiveComment = false;
                if (isJsonLike && parsed.opensObject) {
                    pathStack.add(parsed.key);
                }
                appendLine(out, line, i < lines.length - 1, lineSep);
                continue;
            }
            hasPrecedingLiveComment = false;
            appendLine(out, line, i < lines.length - 1, lineSep);
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String line, boolean hasMore, String lineSep) {
        out.append(line);
        if (hasMore) {
            out.append(lineSep);
        }
    }

    private static String leadingIndent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String stripCommentLeader(String rawComment) {
        String s = rawComment.trim();
        while (s.startsWith("//") || s.startsWith("#") || s.startsWith("!") || s.startsWith("*")) {
            if (s.startsWith("//")) {
                s = s.substring(2).trim();
            } else {
                s = s.substring(1).trim();
            }
        }
        return s;
    }

    private record ParsedFormatLine(String key, boolean opensObject, boolean hasInlineComment) {
    }

    private static ParsedFormatLine parseFormatKeyLine(String trimmed, boolean isJsonLike, boolean isToml) {
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return null;
        }
        int sepIdx = -1;
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle) {
                if (isToml) {
                    if (c == '=') {
                        sepIdx = i;
                        break;
                    }
                } else if (c == ':' || c == '=') {
                    sepIdx = i;
                    break;
                }
            }
        }
        if (sepIdx <= 0) {
            return null;
        }
        String rawKey = trimmed.substring(0, sepIdx).trim();
        if (rawKey.length() >= 2 && ((rawKey.startsWith("\"") && rawKey.endsWith("\""))
                || (rawKey.startsWith("'") && rawKey.endsWith("'")))) {
            rawKey = rawKey.substring(1, rawKey.length() - 1);
        }
        if (rawKey.isEmpty()) {
            return null;
        }
        String afterSep = trimmed.substring(sepIdx + 1).trim();
        boolean hasInlineComment = false;
        String valueWithoutComment = afterSep;
        boolean valDouble = false;
        boolean valSingle = false;
        for (int i = 0; i < afterSep.length(); i++) {
            char c = afterSep.charAt(i);
            if (c == '\\' && i + 1 < afterSep.length()) {
                i++;
                continue;
            }
            if (c == '"' && !valSingle) {
                valDouble = !valDouble;
            } else if (c == '\'' && !valDouble) {
                valSingle = !valSingle;
            } else if (!valDouble && !valSingle) {
                if (isJsonLike && c == '/' && i + 1 < afterSep.length() && afterSep.charAt(i + 1) == '/') {
                    hasInlineComment = true;
                    valueWithoutComment = afterSep.substring(0, i).trim();
                    break;
                } else if (c == '#') {
                    hasInlineComment = true;
                    valueWithoutComment = afterSep.substring(0, i).trim();
                    break;
                }
            }
        }
        boolean opensObject = valueWithoutComment.endsWith("{");
        return new ParsedFormatLine(rawKey, opensObject, hasInlineComment);
    }

    private static boolean isCandidatePlayerDocResource(String entryName) {
        String low = entryName.toLowerCase(Locale.ROOT);
        if (low.startsWith("assets/") && (low.contains("/patchouli_books/") || low.contains("/lavender/entries/")
                || low.contains("/guidebook/") || low.contains("/lexicon/"))) {
            return low.endsWith(".json") || low.endsWith(".md") || low.endsWith(".txt");
        }
        if (low.startsWith("META-INF/") || low.startsWith("net/") || low.startsWith("com/")
                || low.startsWith("org/") || low.startsWith("io/")) {
            return false;
        }
        String fileName = low.contains("/") ? low.substring(low.lastIndexOf('/') + 1) : low;
        return "book.txt".equals(fileName)
                || "rules.txt".equals(fileName)
                || "motd.txt".equals(fileName)
                || "info.txt".equals(fileName)
                || "help.txt".equals(fileName)
                || "custom.txt".equals(fileName)
                || (low.startsWith("books/") && (low.endsWith(".yml") || low.endsWith(".yaml") || low.endsWith(".txt")));
    }

    private static boolean isCandidateConfigResource(String entryName) {
        String lowEntry = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (lowEntry.startsWith("meta-inf/") || lowEntry.startsWith("lang/") || lowEntry.startsWith("locale/")
                || lowEntry.startsWith("locales/") || lowEntry.startsWith("translations/") || lowEntry.startsWith("i18n/")
                || lowEntry.startsWith("editor/") || lowEntry.startsWith("workspace/")
                || lowEntry.startsWith("assets/") || lowEntry.startsWith("data/") || lowEntry.startsWith("patchouli_books/")
                || lowEntry.contains("/patchouli_books/") || lowEntry.contains("/lang/") || lowEntry.contains("/locale/")
                || lowEntry.contains("/locales/") || lowEntry.contains("/translations/") || lowEntry.contains("/i18n/")
                || lowEntry.contains("/editor/") || lowEntry.contains("/workspace/")
                || lowEntry.startsWith("net/") || lowEntry.startsWith("com/")
                || lowEntry.startsWith("org/") || lowEntry.startsWith("io/")) {
            return false;
        }
        String fileName = entryName.contains("/") ? entryName.substring(entryName.lastIndexOf('/') + 1) : entryName;
        String low = fileName.toLowerCase(Locale.ROOT);
        if (low.startsWith(".") || DESCRIPTOR_FILES.contains(entryName) || DESCRIPTOR_FILES.contains(fileName)
                || "pack.mcmeta".equals(low) || "fabric.mod.json".equals(low) || "quilt.mod.json".equals(low)
                || "plugin.json".equals(low) || "paper-plugin.json".equals(low) || "bungee.json".equals(low)
                || "velocity-plugin.json".equals(low) || "manifest.json".equals(low) || "version.json".equals(low)
                || "package.json".equals(low) || "tsconfig.json".equals(low) || "editor-data.json".equals(low)) {
            return false;
        }
        int dotIdx = low.indexOf('.');
        boolean isLocaleStem = (dotIdx == 5 || dotIdx == 6) && (low.charAt(2) == '_' || (dotIdx == 6 && low.charAt(3) == '_'));
        if (low.endsWith(".accesswidener")
                || (low.endsWith(".json") && (low.contains("mixin") || low.contains("refmap")))
                || (low.endsWith(".properties") && (isLocaleStem || low.startsWith("messages_") || low.startsWith("lang_")))) {
            return false;
        }
        return low.endsWith(".yml")
                || low.endsWith(".yaml")
                || low.endsWith(".toml")
                || low.endsWith(".properties")
                || low.endsWith(".json")
                || low.endsWith(".jsonc")
                || low.endsWith(".json5")
                || low.endsWith(".conf");
    }

    private static boolean isShadedLibraryClass(String entryName) {
        return entryName.startsWith("META-INF/")
                || entryName.startsWith("org/slf4j/")
                || entryName.startsWith("com/google/")
                || entryName.startsWith("org/yaml/")
                || entryName.startsWith("org/jetbrains/")
                || entryName.startsWith("kotlin/")
                || entryName.startsWith("io/netty/");
    }

    private static ReloadStrategy classifyReloadStrategy(DescriptorMeta meta, boolean savesOnDisable) {
        String lowerId = meta.artifactId.toLowerCase(Locale.ROOT);
        if (lowerId.startsWith("plugin:leaf")) {
            return ReloadStrategy.NATIVE_SPI;
        }
        if (savesOnDisable) {
            return ReloadStrategy.DEFER_UNTIL_SHUTDOWN;
        }
        if (!meta.commands.isEmpty()) {
            return ReloadStrategy.COMMAND_DISPATCH;
        }
        return ReloadStrategy.BUKKIT_FILECONFIG_INJECT;
    }

    private record DescriptorMeta(
            String artifactId,
            String displayName,
            String version,
            String loaderFamily,
            String website,
            List<String> commands,
            List<String> permissions,
            List<String> dependencies,
            List<CommandDescriptor> commandDescriptors,
            String declaredMainClass
    ) {
    }

    private static DescriptorMeta parseDescriptorMetadata(Map<String, String> descriptors, String fallbackJarName) {
        String yamlDesc = descriptors.getOrDefault("paper-plugin.yml", descriptors.get("plugin.yml"));
        if (yamlDesc == null) {
            yamlDesc = descriptors.get("bungee.yml");
        }
        if (yamlDesc != null) {
            try {
                YamlConfig yml = YamlConfig.parse(yamlDesc);
                String rawName = yml.getString("name", stripJarExtension(fallbackJarName));
                String version = yml.getString("version", "1.0.0");
                String website = yml.getString("website", "");
                String declaredMain = yml.getString("main", "");
                if (declaredMain == null || declaredMain.isBlank()) {
                    declaredMain = yml.getString("bootstrapper", "");
                }
                if (declaredMain == null || declaredMain.isBlank()) {
                    declaredMain = yml.getString("loader", "");
                }
                String loader = descriptors.containsKey("paper-plugin.yml") ? "paper"
                        : (descriptors.containsKey("bungee.yml") ? "bungee" : "bukkit");

                List<String> perms = new ArrayList<>();
                Map<String, String> permDefaults = new LinkedHashMap<>();
                YamlSection permSec = yml.getConfigurationSection("permissions");
                if (permSec != null) {
                    for (String permKey : permSec.getKeys(false)) {
                        perms.add(permKey);
                        String defVal = permSec.getString(permKey + ".default", "");
                        if (defVal != null && !defVal.isBlank()) {
                            permDefaults.put(permKey, defVal.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }

                List<String> commands = new ArrayList<>();
                List<CommandDescriptor> commandDescriptors = new ArrayList<>();
                YamlSection cmdSec = yml.getConfigurationSection("commands");
                if (cmdSec != null) {
                    for (String cmdName : cmdSec.getKeys(false)) {
                        commands.add(cmdName);
                        String desc = cmdSec.getString(cmdName + ".description", "");
                        String usage = cmdSec.getString(cmdName + ".usage", "/" + cmdName);
                        List<String> aliases = new ArrayList<>(cmdSec.getStringList(cmdName + ".aliases"));
                        if (aliases.isEmpty()) {
                            String singleAlias = cmdSec.getString(cmdName + ".aliases", "");
                            if (singleAlias != null && !singleAlias.isBlank() && !singleAlias.startsWith("[")) {
                                aliases.add(singleAlias.trim());
                            }
                        }
                        String perm = cmdSec.getString(cmdName + ".permission", "");
                        String permDef = "";
                        if (perm != null && !perm.isBlank()) {
                            permDef = permDefaults.getOrDefault(perm, "");
                        }
                        if (permDef.isEmpty()) {
                            permDef = cmdSec.getString(cmdName + ".permission-default", "");
                        }
                        commandDescriptors.add(new CommandDescriptor(
                                cmdName,
                                rawName,
                                desc == null ? "" : desc,
                                usage == null ? "/" + cmdName : usage,
                                aliases,
                                perm == null ? "" : perm,
                                permDef == null ? "" : permDef
                        ));
                    }
                }

                Set<String> deps = new LinkedHashSet<>();
                deps.addAll(yml.getStringList("depend"));
                deps.addAll(yml.getStringList("softdepend"));

                String slug = normalizeSlug(rawName);
                return new DescriptorMeta(
                        "plugin:" + slug,
                        rawName,
                        version,
                        loader,
                        website,
                        commands,
                        perms,
                        new ArrayList<>(deps),
                        commandDescriptors,
                        declaredMain == null ? "" : declaredMain.trim()
                );
            } catch (RuntimeException ignored) {
            }
        }

        if (descriptors.containsKey("velocity-plugin.json")) {
            String json = descriptors.get("velocity-plugin.json");
            String id = extractRegex(JSON_ID_RE, json, stripJarExtension(fallbackJarName));
            String name = extractRegex(JSON_NAME_RE, json, id);
            String ver = extractRegex(JSON_VERSION_RE, json, "1.0.0");
            String url = extractRegex(JSON_HOMEPAGE_RE, json, "");
            String mainCls = extractRegex(JSON_MAIN_RE, json, "");
            return new DescriptorMeta("plugin:" + normalizeSlug(id), name, ver, "velocity", url, List.of(), List.of(), List.of(), List.of(), mainCls);
        }

        if (descriptors.containsKey("fabric.mod.json")) {
            String json = descriptors.get("fabric.mod.json");
            String id = extractRegex(JSON_ID_RE, json, stripJarExtension(fallbackJarName));
            String name = extractRegex(JSON_NAME_RE, json, id);
            String ver = extractRegex(JSON_VERSION_RE, json, "1.0.0");
            String url = extractRegex(JSON_HOMEPAGE_RE, json, "");
            String mainCls = extractRegex(FABRIC_ENTRYPOINT_RE, json, "");
            return new DescriptorMeta("mod:" + normalizeSlug(id), name, ver, "fabric", url, List.of(), List.of(), List.of(), List.of(), mainCls);
        }

        String toml = descriptors.getOrDefault("META-INF/neoforge.mods.toml", descriptors.get("META-INF/mods.toml"));
        if (toml != null) {
            String id = extractRegex(TOML_MOD_ID_RE, toml, stripJarExtension(fallbackJarName));
            String name = extractRegex(TOML_DISPLAY_NAME_RE, toml, id);
            String ver = extractRegex(TOML_VERSION_RE, toml, "1.0.0");
            return new DescriptorMeta("mod:" + normalizeSlug(id), name, ver, "neoforge", "", List.of(), List.of(), List.of(), List.of(), "");
        }

        String fallbackName = stripJarExtension(fallbackJarName);
        return new DescriptorMeta(
                "plugin:" + normalizeSlug(fallbackName),
                fallbackName,
                "unknown",
                "bukkit",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );
    }

    private static String extractRegex(Pattern pattern, String input, String fallback) {
        if (input == null) return fallback;
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static String stripJarExtension(String fileName) {
        if (fileName == null) return "unknown";
        String base = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        int dashVer = base.indexOf('-');
        return dashVer > 0 ? base.substring(0, dashVer) : base;
    }

    static String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static int scanNestedJarClassNames(ZipFile zip, ZipEntry nestedJarEntry, Set<String> outBinaryNames) {
        int count = 0;
        try (InputStream rawIn = zip.getInputStream(nestedJarEntry);
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(rawIn)) {
            ZipEntry sub;
            while ((sub = zis.getNextEntry()) != null && count < MAX_CLASSES_PER_JAR) {
                if (!sub.isDirectory()) {
                    String subName = sub.getName();
                    if (subName.endsWith(".class") && subName.length() > 6) {
                        outBinaryNames.add(subName.substring(0, subName.length() - 6).replace('/', '.'));
                        if (!isShadedLibraryClass(subName)) {
                            count++;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return count;
    }

    private static byte[] readBoundedBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > MAX_RESOURCE_BYTES) {
                    return null;
                }
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    static String computeSha512Hex(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }
}
