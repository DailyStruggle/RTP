package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Key-set parity between the English baseline resources and every bundled {@code lang/<locale>/}
 * tree (REQ-RTP-F-013, ADR-020, ADR-076; ADR-121 section 6).
 *
 * <p>Pure over a {@link ResourceReader}: the same comparison runs over the runtime classpath
 * (config PBIT) and over {@code src/main/resources} ({@code LocaleParityTest}). Layout: a category
 * is {@code <dir>/<stem>} with value file {@code <dir>/<stem>.yml} and co-located rename map
 * {@code <dir>/.<stem>.lang.yml}; locale mirrors live under {@code lang/<locale>/}. YAML is read
 * with the runtime parser, so the comparison matches what {@code ConfigParser} sees. Never writes.
 */
public final class MessageKeyParity {
    public static final String LANG_ROOT = "lang";
    public static final String MESSAGES_DIR = "advanced/messages";
    /** Members of the split messages tree, one per {@code rtp-api} message enum. */
    public static final List<String> MESSAGE_FILES = List.of("commands", "network", "placeholders", "player", "system");
    /** Directories under {@code lang/} that are not locales. */
    public static final Set<String> NON_LOCALE_DIRS = Set.of("shape", "vert", "advanced");

    private MessageKeyParity() {}

    /** Read-only view of a resource tree; paths use {@code /} and are relative to the tree root. */
    public interface ResourceReader {
        /** UTF-8 text of a file, or empty when absent or unreadable. */
        Optional<String> read(String path);

        /** Direct children of {@code directory}; sub-directories end with {@code /}. Empty when unlistable. */
        List<String> list(String directory);

        default boolean exists(String path) {
            return read(path).isPresent();
        }

        default boolean isDirectory(String path) {
            return !list(path).isEmpty();
        }

        /** Filesystem tree rooted at {@code root} (e.g. {@code src/main/resources}). */
        static ResourceReader directory(Path root) {
            Objects.requireNonNull(root, "root");
            return new ResourceReader() {
                @Override
                public Optional<String> read(String path) {
                    Path file = root.resolve(path);
                    if (!Files.isRegularFile(file)) return Optional.empty();
                    try {
                        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                }

                @Override
                public List<String> list(String directory) {
                    Path dir = directory.isEmpty() ? root : root.resolve(directory);
                    if (!Files.isDirectory(dir)) return List.of();
                    List<String> out = new ArrayList<>();
                    try (Stream<Path> children = Files.list(dir)) {
                        children.forEach(child -> out.add(child.getFileName().toString()
                                + (Files.isDirectory(child) ? "/" : "")));
                    } catch (IOException e) {
                        return List.of();
                    }
                    out.sort(null);
                    return out;
                }

                @Override
                public boolean isDirectory(String path) {
                    return Files.isDirectory(path.isEmpty() ? root : root.resolve(path));
                }
            };
        }

        /**
         * Resources visible to {@code loader} (jar or exploded directories). Listing is best effort:
         * an unknown URL protocol yields no children, never an exception.
         */
        static ResourceReader classpath(ClassLoader loader) {
            Objects.requireNonNull(loader, "loader");
            return new ResourceReader() {
                @Override
                public Optional<String> read(String path) {
                    try (var in = loader.getResourceAsStream(path)) {
                        return in == null ? Optional.empty()
                                : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                }

                @Override
                public List<String> list(String directory) {
                    Set<String> out = new TreeSet<>();
                    try {
                        Enumeration<URL> urls = loader.getResources(directory);
                        while (urls.hasMoreElements()) listUrl(urls.nextElement(), directory, out);
                    } catch (IOException | RuntimeException e) {
                        return List.copyOf(out);
                    }
                    return List.copyOf(out);
                }
            };
        }

        /** In-memory tree (tests and headless fixtures). */
        static ResourceReader of(Map<String, String> files) {
            Map<String, String> copy = new TreeMap<>();
            if (files != null) files.forEach((path, text) -> copy.put(ConfigSnapshot.normalise(path), text));
            return new ResourceReader() {
                @Override
                public Optional<String> read(String path) {
                    return Optional.ofNullable(copy.get(ConfigSnapshot.normalise(path)));
                }

                @Override
                public List<String> list(String directory) {
                    String prefix = ConfigSnapshot.normalise(directory);
                    if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix = prefix + "/";
                    Set<String> out = new TreeSet<>();
                    for (String path : copy.keySet()) {
                        if (!path.startsWith(prefix)) continue;
                        String rest = path.substring(prefix.length());
                        int slash = rest.indexOf('/');
                        out.add(slash < 0 ? rest : rest.substring(0, slash + 1));
                    }
                    return List.copyOf(out);
                }
            };
        }
    }

    /** Kind of a key-set divergence; each maps to one {@code RTP_MESSAGES_*} code. */
    public enum GapKind {
        /** Baseline value key without a row in the baseline rename map. */
        BASELINE_UNMAPPED,
        /** Locale ships a rename map but no value file; runtime falls back to English. */
        LOCALE_VALUES_MISSING,
        /** Locale value file lacks a baseline key under its translated name. */
        LOCALE_KEY_MISSING,
        /** Locale rename map row for a key the baseline no longer ships. */
        LOCALE_STALE_ROW,
        /** Non-identity rename map target absent from the sibling value file. */
        LOCALE_UNRESOLVED_ROW
    }

    /**
     * One divergence. {@code locale} is empty for baseline gaps; {@code path} is the offending
     * resource; {@code expected} is the translated key name where one applies.
     */
    public record Gap(GapKind kind, String locale, String category, String path, String key, String expected) {}

    // --- pure key-set comparisons -------------------------------------------------------------

    /** Effective on-disk name of {@code baselineKey}: locale map, then baseline map, then identity. */
    public static String translatedKey(String baselineKey, Map<String, String> localeLangMap,
                                       Map<String, String> baselineLangMap) {
        String translated = localeLangMap == null ? null : localeLangMap.get(baselineKey);
        if (translated == null && baselineLangMap != null) translated = baselineLangMap.get(baselineKey);
        return translated == null ? baselineKey : translated;
    }

    /** Baseline keys with no row in the baseline rename map, in baseline order. */
    public static List<String> unmappedBaselineKeys(Collection<String> baselineKeys, Map<String, String> baselineLangMap) {
        List<String> missing = new ArrayList<>();
        for (String key : baselineKeys) {
            if (!baselineLangMap.containsKey(key)) missing.add(key);
        }
        return missing;
    }

    /** Baseline key -> expected translated name, for every baseline key the locale value file lacks. */
    public static Map<String, String> missingLocaleKeys(Collection<String> baselineKeys, Map<String, String> baselineLangMap,
                                                        Map<String, String> localeLangMap, Set<String> localeValueKeys) {
        Map<String, String> missing = new LinkedHashMap<>();
        for (String key : baselineKeys) {
            String translated = translatedKey(key, localeLangMap, baselineLangMap);
            if (!localeValueKeys.contains(translated)) missing.put(key, translated);
        }
        return missing;
    }

    /** Rename-map rows whose canonical key the baseline does not ship. */
    public static List<String> staleLangRows(Set<String> baselineKeys, Map<String, String> localeLangMap) {
        List<String> stale = new ArrayList<>();
        for (String key : localeLangMap.keySet()) {
            if (!baselineKeys.contains(key)) stale.add(key);
        }
        return stale;
    }

    /** Canonical -> mapped name for non-identity rows whose target is not a top-level value key. */
    public static Map<String, String> unresolvedLangRows(Map<String, String> langMap, Set<String> valueKeys) {
        Map<String, String> unresolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : langMap.entrySet()) {
            if (row.getValue().equals(row.getKey())) continue;
            if (!valueKeys.contains(row.getValue())) unresolved.put(row.getKey(), row.getValue());
        }
        return unresolved;
    }

    // --- layout --------------------------------------------------------------------------------

    /** Co-located rename map of a baseline category ({@code <dir>/.<stem>.lang.yml}). */
    public static String baselineLangPath(String category) {
        int slash = category.lastIndexOf('/');
        if (slash < 0) return "." + category + ".lang.yml";
        return category.substring(0, slash + 1) + "." + category.substring(slash + 1) + ".lang.yml";
    }

    public static String localeValuePath(String locale, String category) {
        return LANG_ROOT + "/" + locale + "/" + category + ".yml";
    }

    public static String localeLangPath(String locale, String category) {
        return LANG_ROOT + "/" + locale + "/" + baselineLangPath(category);
    }

    /** Categories of the split messages tree ({@code advanced/messages/<file>}). */
    public static List<String> messageCategories() {
        List<String> out = new ArrayList<>();
        for (String file : MESSAGE_FILES) out.add(MESSAGES_DIR + "/" + file);
        return out;
    }

    /** Locale directory names under {@code lang/} (excluding {@link #NON_LOCALE_DIRS}), sorted. */
    public static List<String> locales(ResourceReader reader) {
        List<String> out = new ArrayList<>();
        for (String child : reader.list(LANG_ROOT)) {
            if (!child.endsWith("/")) continue;
            String name = child.substring(0, child.length() - 1);
            if (!NON_LOCALE_DIRS.contains(name)) out.add(name);
        }
        out.sort(null);
        return out;
    }

    /**
     * Baseline categories: every {@code <stem>.yml} at the root, in {@code advanced/} and in
     * {@code advanced/messages/} with a co-located {@code .<stem>.lang.yml}. Empty when the tree has
     * no {@code lang/} directory.
     */
    public static List<String> categories(ResourceReader reader) {
        List<String> out = new ArrayList<>();
        if (!reader.isDirectory(LANG_ROOT)) return out;
        for (String dir : List.of("", "advanced", MESSAGES_DIR)) {
            for (String child : reader.list(dir)) {
                if (!child.endsWith("/") && !child.startsWith(".") && child.endsWith(".yml")
                        && !child.endsWith(".lang.yml")) {
                    String stem = child.substring(0, child.length() - ".yml".length());
                    String category = dir.isEmpty() ? stem : dir + "/" + stem;
                    if (reader.exists(baselineLangPath(category)) && !out.contains(category)) out.add(category);
                }
            }
        }
        out.sort(null);
        return out;
    }

    // --- parsing -------------------------------------------------------------------------------

    /**
     * Top-level map of a YAML resource, or empty when the resource is absent.
     *
     * @throws IllegalArgumentException when the resource exists but does not parse
     */
    public static Optional<Map<String, Object>> topLevel(ResourceReader reader, String path) {
        Optional<String> text = reader.read(path);
        if (text.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new LinkedHashMap<>(RtpYamlConfig.parse(text.get()).getValues(false)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + " does not parse: " + e.getMessage(), e);
        }
    }

    /** Ordered top-level keys of a YAML resource; empty set when absent. */
    public static Set<String> keys(ResourceReader reader, String path) {
        return topLevel(reader, path).<Set<String>>map(map -> new LinkedHashSet<>(map.keySet()))
                .orElseGet(LinkedHashSet::new);
    }

    /** Rename map ({@code canonical -> localized}) of a {@code .lang.yml}; empty when absent. */
    public static Map<String, String> langMap(ResourceReader reader, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        topLevel(reader, path).ifPresent(map -> map.forEach((key, value) -> out.put(key, String.valueOf(value))));
        return out;
    }

    // --- whole-tree comparison -----------------------------------------------------------------

    /** Every divergence of {@code categories} across all locales of {@code reader}. */
    public static List<Gap> gaps(ResourceReader reader, Collection<String> categories) {
        List<Gap> gaps = new ArrayList<>();
        List<String> locales = locales(reader);
        for (String category : categories) {
            String baselinePath = category + ".yml";
            if (!reader.exists(baselinePath)) continue;
            Set<String> baselineKeys = keys(reader, baselinePath);
            Map<String, String> baselineLangMap = langMap(reader, baselineLangPath(category));
            for (String key : unmappedBaselineKeys(baselineKeys, baselineLangMap)) {
                gaps.add(new Gap(GapKind.BASELINE_UNMAPPED, "", category, baselineLangPath(category), key, key));
            }
            for (String locale : locales) gaps.addAll(localeGaps(reader, locale, category, baselineKeys, baselineLangMap));
        }
        return gaps;
    }

    /** Divergences of one locale's mirror of one category against the given baseline. */
    public static List<Gap> localeGaps(ResourceReader reader, String locale, String category,
                                       Set<String> baselineKeys, Map<String, String> baselineLangMap) {
        List<Gap> gaps = new ArrayList<>();
        String valuePath = localeValuePath(locale, category);
        String langPath = localeLangPath(locale, category);
        boolean hasLang = reader.exists(langPath);
        if (!reader.exists(valuePath)) {
            if (hasLang) gaps.add(new Gap(GapKind.LOCALE_VALUES_MISSING, locale, category, valuePath, "", ""));
            return gaps;
        }
        Set<String> valueKeys = keys(reader, valuePath);
        Map<String, String> localeLangMap = hasLang ? langMap(reader, langPath) : Map.of();
        missingLocaleKeys(baselineKeys, baselineLangMap, localeLangMap, valueKeys).forEach((key, expected) ->
                gaps.add(new Gap(GapKind.LOCALE_KEY_MISSING, locale, category, valuePath, key, expected)));
        if (hasLang) {
            for (String key : staleLangRows(baselineKeys, localeLangMap)) {
                gaps.add(new Gap(GapKind.LOCALE_STALE_ROW, locale, category, langPath, key, ""));
            }
            unresolvedLangRows(localeLangMap, valueKeys).forEach((key, mapped) ->
                    gaps.add(new Gap(GapKind.LOCALE_UNRESOLVED_ROW, locale, category, langPath, key, mapped)));
        }
        return gaps;
    }

    private static void listUrl(URL url, String directory, Set<String> out) throws IOException {
        String protocol = url.getProtocol();
        if ("jar".equals(protocol)) {
            String path = url.getPath();
            int bang = path.indexOf('!');
            if (bang < 0 || !path.startsWith("file:")) return;
            String jarPath = URLDecoder.decode(path.substring("file:".length(), bang), StandardCharsets.UTF_8);
            String prefix = directory.endsWith("/") ? directory : directory + "/";
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(prefix) || name.length() == prefix.length()) continue;
                    String rest = name.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    out.add(slash < 0 ? rest : rest.substring(0, slash + 1));
                }
            }
        } else if ("file".equals(protocol)) {
            File dir;
            try {
                dir = new File(url.toURI());
            } catch (URISyntaxException e) {
                return;
            }
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File child : children) out.add(child.getName() + (child.isDirectory() ? "/" : ""));
        }
    }
}
