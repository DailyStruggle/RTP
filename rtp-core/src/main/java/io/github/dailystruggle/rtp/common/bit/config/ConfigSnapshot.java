package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only parse of a configuration directory for config PBIT (ADR-121 sections 6 and 7).
 *
 * <p>Invariant: building a snapshot never writes to disk, never constructs a {@code ConfigParser}
 * and never changes static state, so a rejected candidate leaves the running config untouched.
 * Paths are relative to the root with {@code /} separators. Dotfiles ({@code .x.lang.yml}) are
 * read as rename maps, not as documents.
 */
public final class ConfigSnapshot {
    private static final int MAX_DEPTH = 4;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "lang", "logs", "reports", "backups", "database", "cache", "docs", "libraries");
    private static final Pattern ROTATED_BACKUP = Pattern.compile(".*\\.(?:old\\d*|bak.*|tmp)$");

    /** One parsed YAML file; {@link #canonical()} maps localized top-level keys to enum names. */
    public record Document(String path, RtpYamlConfig yaml, Map<String, String> canonical) {
        public Document {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(yaml, "yaml");
            canonical = canonical == null ? Map.of() : Map.copyOf(canonical);
        }

        /** Top-level values keyed by canonical (English enum) name; nested maps are left as parsed. */
        public Map<String, Object> values() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : yaml.getValues(false).entrySet()) {
                out.put(canonical.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue());
            }
            return out;
        }

        /** Top-level value for a canonical key, or {@code null}. */
        public Object get(String canonicalKey) {
            return values().get(canonicalKey);
        }

        /** Localized on-disk spelling of a canonical key (identity when no rename map applies). */
        public String localizedKey(String canonicalKey) {
            for (Map.Entry<String, String> entry : canonical.entrySet()) {
                if (entry.getValue().equals(canonicalKey)) return entry.getKey();
            }
            return canonicalKey;
        }

        /** File name without directories and without the {@code .yml} extension. */
        public String stem() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
        }
    }

    private final Path root;
    private final Map<String, String> texts;
    private final Map<String, String> readFailures;
    private final Map<String, Document> documents;
    private final Map<String, String> unparsable;
    private final Map<String, String> unparsableKeys;
    private final Set<String> presentFiles;

    private ConfigSnapshot(Path root, Map<String, String> texts, Map<String, String> readFailures,
                           Map<String, Document> documents, Map<String, String> unparsable,
                           Map<String, String> unparsableKeys, Set<String> presentFiles) {
        this.root = root;
        this.texts = Collections.unmodifiableMap(new TreeMap<>(texts));
        this.readFailures = Collections.unmodifiableMap(new TreeMap<>(readFailures));
        this.documents = Collections.unmodifiableMap(new TreeMap<>(documents));
        this.unparsable = Collections.unmodifiableMap(new TreeMap<>(unparsable));
        this.unparsableKeys = Collections.unmodifiableMap(new TreeMap<>(unparsableKeys));
        this.presentFiles = Collections.unmodifiableSet(new TreeSet<>(presentFiles));
    }

    /** Snapshot of the YAML files under {@code root}; a missing root yields an empty snapshot. */
    public static ConfigSnapshot read(Path root) {
        Map<String, String> texts = new TreeMap<>();
        Map<String, String> unreadable = new TreeMap<>();
        Set<String> present = new TreeSet<>();
        if (root != null && Files.isDirectory(root)) {
            Path base = root.toAbsolutePath().normalize();
            try (Stream<Path> walk = Files.walk(base, MAX_DEPTH)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !inSkippedDirectory(base, path))
                        .forEach(path -> {
                            String rel = relative(base, path);
                            present.add(rel);
                            if (!isYaml(rel)) return;
                            try {
                                texts.put(rel, Files.readString(path, StandardCharsets.UTF_8));
                            } catch (IOException | RuntimeException e) {
                                unreadable.put(rel, "unreadable: " + e.getMessage());
                            }
                        });
            } catch (IOException | RuntimeException e) {
                unreadable.put("", "directory walk failed: " + e.getMessage());
            }
        }
        return build(root, texts, unreadable, present);
    }

    /** Snapshot built only from in-memory texts (headless runs and tests). */
    public static ConfigSnapshot of(Map<String, String> texts) {
        Map<String, String> copy = new TreeMap<>();
        if (texts != null) texts.forEach((path, text) -> copy.put(normalise(path), text));
        return build(null, copy, Map.of(), copy.keySet());
    }

    /** Candidate with {@code overlay} texts replacing or adding files; {@code this} is unchanged. */
    public ConfigSnapshot withOverlay(Map<String, String> overlay) {
        if (overlay == null || overlay.isEmpty()) return this;
        Map<String, String> merged = new TreeMap<>(texts);
        Map<String, String> failures = new TreeMap<>(readFailures);
        Set<String> present = new TreeSet<>(presentFiles);
        overlay.forEach((path, text) -> {
            String rel = normalise(path);
            merged.put(rel, text == null ? "" : text);
            failures.remove(rel);
            present.add(rel);
        });
        return build(root, merged, failures, present);
    }

    /** Raw text of a YAML file in this snapshot (documents, rename maps and unparsable files). */
    public Optional<String> text(String path) {
        return Optional.ofNullable(texts.get(normalise(path)));
    }

    private static ConfigSnapshot build(Path root, Map<String, String> texts, Map<String, String> failures,
                                        Set<String> present) {
        Map<String, String> unparsable = new TreeMap<>(failures);
        Map<String, String> unparsableKeys = new TreeMap<>();
        Map<String, RtpYamlConfig> parsed = new TreeMap<>();
        Map<String, String> langTexts = new TreeMap<>();
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            String path = entry.getKey();
            if (!isYaml(path)) continue;
            if (isLangMap(path)) {
                langTexts.put(path, entry.getValue());
                continue;
            }
            try {
                parsed.put(path, RtpYamlConfig.parse(entry.getValue() == null ? "" : entry.getValue()));
            } catch (RuntimeException e) {
                unparsable.put(path, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (e instanceof RtpYamlParseException parseException) {
                    unparsableKeys.put(path, parseException.messageKey());
                }
            }
        }
        Map<String, Map<String, String>> langMaps = new TreeMap<>();
        for (Map.Entry<String, String> entry : langTexts.entrySet()) {
            try {
                Map<String, String> reverse = new LinkedHashMap<>();
                RtpYamlConfig.parse(entry.getValue() == null ? "" : entry.getValue()).getValues(true)
                        .forEach((canonical, localized) -> {
                            if (localized != null) reverse.put(String.valueOf(localized), canonical);
                        });
                langMaps.put(entry.getKey(), reverse);
            } catch (RuntimeException e) {
                unparsable.put(entry.getKey(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (e instanceof RtpYamlParseException parseException) {
                    unparsableKeys.put(entry.getKey(), parseException.messageKey());
                }
            }
        }
        Map<String, Document> documents = new TreeMap<>();
        parsed.forEach((path, yaml) -> documents.put(path, new Document(path, yaml, langMapFor(path, langMaps))));
        return new ConfigSnapshot(root, texts, failures, documents, unparsable, unparsableKeys, present);
    }

    /** ADR-076 lookup: {@code <dir>/.<stem>.lang.yml}, then {@code <parent>/.<dir>.lang.yml}. */
    private static Map<String, String> langMapFor(String path, Map<String, Map<String, String>> langMaps) {
        int slash = path.lastIndexOf('/');
        String dir = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = path.substring(slash + 1);
        String stem = name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
        Map<String, String> own = langMaps.get(dir + "." + stem + ".lang.yml");
        if (own != null) return own;
        if (!dir.isEmpty()) {
            String trimmed = dir.substring(0, dir.length() - 1);
            int parentSlash = trimmed.lastIndexOf('/');
            String parent = parentSlash < 0 ? "" : trimmed.substring(0, parentSlash + 1);
            String leaf = trimmed.substring(parentSlash + 1);
            Map<String, String> shared = langMaps.get(parent + "." + leaf + ".lang.yml");
            if (shared != null) return shared;
        }
        return Map.of();
    }

    public Optional<Path> root() {
        return Optional.ofNullable(root);
    }

    public Map<String, Document> documents() {
        return documents;
    }

    public Optional<Document> document(String path) {
        return Optional.ofNullable(documents.get(normalise(path)));
    }

    /** Documents directly inside {@code directory} (no recursion), e.g. {@code definitions/regions}. */
    public Map<String, Document> documentsIn(String directory) {
        String prefix = normalise(directory);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix = prefix + "/";
        Map<String, Document> out = new TreeMap<>();
        for (Map.Entry<String, Document> entry : documents.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0) out.put(path, entry.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    /** YAML files that could not be read or parsed, with the parser message. */
    public Map<String, String> unparsable() {
        return unparsable;
    }

    /** Stable YAML parser message keys for files that failed parsing. */
    public Map<String, String> unparsableKeys() {
        return unparsableKeys;
    }

    /** True when any file (YAML or not) exists at {@code path} in this snapshot. */
    public boolean exists(String path) {
        return presentFiles.contains(normalise(path));
    }

    public Set<String> files() {
        return presentFiles;
    }

    private static boolean inSkippedDirectory(Path base, Path path) {
        Path rel = base.relativize(path);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            if (SKIPPED_DIRECTORIES.contains(rel.getName(i).toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isYaml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") && !ROTATED_BACKUP.matcher(lower).matches();
    }

    private static boolean isLangMap(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.startsWith(".") || name.endsWith(".lang.yml");
    }

    private static String relative(Path base, Path path) {
        return base.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static String normalise(String path) {
        if (path == null) return "";
        String out = path.replace('\\', '/');
        while (out.startsWith("./")) out = out.substring(2);
        while (out.startsWith("/")) out = out.substring(1);
        return out;
    }
}
