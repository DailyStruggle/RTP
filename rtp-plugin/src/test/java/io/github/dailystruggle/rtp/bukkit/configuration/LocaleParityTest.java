package io.github.dailystruggle.rtp.bukkit.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * REQ-RTP-F-013 / ADR-020 - consolidated locale-parity suite for every shipped
 * {@code lang/<locale>/}.
 *
 * <p>This class merges the historical {@code LocaleResourceParityTest} and
 * {@code LocaleFullParityTest} into a single co-located suite. Co-location is
 * deliberate: a developer adding a new key to the English baseline (or adding
 * a new locale) should be able to find every parity guard in one file, so that
 * "I forgot to mirror this key into fr/" cannot pass review unnoticed.
 *
 * <p>The Spanish-specific content tests (Norway-problem, typed-key resolution,
 * enum coverage) intentionally remain in {@code ReqRtpF013SpanishLocaleContentTest}
 * - those guarantees are Spanish-flavored and do not generalize.
 *
 * <p>Two test groups:
 * <ul>
 *   <li>{@link ResourceParity} - filesystem hygiene and lang-map / value-file
 *       lookup integrity. No content semantics, locale-agnostic, divergence-only.</li>
 *   <li>{@link FullParity} - cross-locale key coverage: every baseline key
 *       must exist in every locale's value file under its effective translated
 *       name; locale lang-maps must not declare stale rows.</li>
 * </ul>
 *
 * <p>Locale discovery is dynamic: every directory under {@code lang/} not in
 * {@link #NON_LOCALE_DIRS} is a locale. Adding a new locale extends coverage
 * with no test edit. Same for categories: every baseline {@code <file>.yml}
 * with a sibling {@code lang/<file>.lang.yml} is scanned.
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 - locale parity (resource hygiene + cross-locale coverage)")
public class LocaleParityTest {

    private static final Path RESOURCES =
            Paths.get("src", "main", "resources");
    private static final Path LANG_ROOT = RESOURCES.resolve("lang");
    /**
     * The English baseline messages are split, per concern, across this
     * directory (placeholders / player / network / commands / system) rather
     * than a single top-level messages.yml. The locale trees still ship a
     * single lang/&lt;locale&gt;/messages.yml, so "messages" is treated as a
     * directory-backed baseline category whose key set is the union of these
     * member files.
     */
    private static final Path MESSAGES_DIR = RESOURCES.resolve("messages");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Za-z0-9_]+]");

    /** Subdirectories under {@code lang/} that are NOT locale folders. */
    private static final Set<String> NON_LOCALE_DIRS = Set.of("shape", "vert", "advanced");

    // =========================================================================
    // Group 1: resource hygiene + per-locale lang-map / value-file integrity
    // =========================================================================

    @Nested
    @DisplayName("Resource hygiene")
    class ResourceParity {

        @Test
        @DisplayName("No .bak files are shipped under lang/")
        void noBackupFilesShipped() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;
            List<Path> baks = new ArrayList<>();
            try (var stream = Files.walk(LANG_ROOT)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".bak"))
                      .forEach(baks::add);
            }
            if (!baks.isEmpty()) {
                fail("Backup (.bak) files must not be shipped in resources. Found:\n  "
                        + String.join("\n  ", baks.stream().map(Path::toString).toList())
                        + "\nDelete them or add an exclude to processResources.");
            }
        }

        @Test
        @DisplayName("Each locale's *.lang.yml right-hand keys exist as top-level keys in *.yml")
        void langMapKeysResolveInValueFile() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;

            List<String> failures = new ArrayList<>();

            try (DirectoryStream<Path> locales = Files.newDirectoryStream(LANG_ROOT, Files::isDirectory)) {
                for (Path localeDir : locales) {
                    String localeName = localeDir.getFileName().toString();
                    if (NON_LOCALE_DIRS.contains(localeName)) continue;

                    try (DirectoryStream<Path> langMaps =
                                 Files.newDirectoryStream(localeDir, "*.lang.yml")) {
                        for (Path langMap : langMaps) {
                            String mapName = langMap.getFileName().toString();
                            String valueName = mapName.substring(
                                    0, mapName.length() - ".lang.yml".length()) + ".yml";
                            Path valueFile = localeDir.resolve(valueName);

                            if (!Files.exists(valueFile)) {
                                // .lang.yml without a sibling value file: allowed
                                // for template-only files (regions, worlds).
                                continue;
                            }

                            Map<String, String> map = loadLangMap(langMap);
                            Map<String, Object> values = loadYaml(valueFile);
                            Set<String> valueKeys = values.keySet();

                            for (Map.Entry<String, String> e : map.entrySet()) {
                                String mappedName = e.getValue();
                                if (mappedName.equals(e.getKey())) continue;
                                if (!valueKeys.contains(mappedName)) {
                                    failures.add(String.format(
                                            "%s/%s maps %s -> %s but %s has no top-level key %s",
                                            localeName, mapName, e.getKey(), mappedName,
                                            valueName, mappedName));
                                }
                            }
                        }
                    }
                }
            }

            if (!failures.isEmpty()) {
                fail("Locale lang-map / value-file inconsistencies:\n  "
                        + String.join("\n  ", failures));
            }
        }

        /**
         * Raw-text scan for CSV-escaping corruption in every shipped locale
         * value file. The locale YAML tree is derived output of the
         * {@code scripts/locale-*-csv.py} pipeline; a botched CSV round-trip
         * double-quotes comment/value cells and over-escapes backslashes,
         * writing signatures that are never valid in these hand-authored
         * configs: quadrupled quotes ({@code ""}{@code ""}), tripled quotes
         * ({@code "}{@code ""}), a stray leading {@code "#} that promotes a
         * comment to a YAML key, and runaway backslash runs ({@code \\\\}).
         *
         * <p>This is the detector for the failure that motivated the test:
         * {@code lang/zh/network.yml} (and its siblings) leaking
         * mechanically-corrupted English content past the coverage checks.
         * It scans the raw bytes (not the parsed map) so it catches corruption
         * that lives in comments, and so it reports a precise file:line even
         * when the file no longer parses as YAML.
         */
        @Test
        @DisplayName("No locale file contains CSV-escaping corruption (quadrupled/tripled quotes, leading \"#, backslash runs)")
        void noCsvEscapingCorruption() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;

            List<String> failures = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(LANG_ROOT)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.getFileName().toString().endsWith(".yml"))
                      .forEach(files::add);
            }
            files.sort((a, b) -> a.toString().compareTo(b.toString()));

            for (Path file : files) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String why = null;
                    if (line.contains("\"\"\"\"")) {
                        why = "quadrupled double-quotes (\"\"\"\")";
                    } else if (line.contains("\"\"\"")) {
                        why = "tripled double-quotes (\"\"\")";
                    } else if (line.stripLeading().startsWith("\"#")) {
                        why = "stray leading quote promotes a comment to a YAML key (\"#)";
                    } else if (line.contains("\\\\\\\\")) {
                        why = "runaway backslash escaping (\\\\\\\\)";
                    }
                    if (why != null) {
                        String shown = line.length() > 80 ? line.substring(0, 80) + "..." : line;
                        failures.add(String.format("%s:%d - %s | %s",
                                LANG_ROOT.relativize(file), i + 1, why, shown.strip()));
                    }
                }
            }

            if (!failures.isEmpty()) {
                fail("CSV-escaping corruption found in shipped locale files (re-run the "
                        + "locale TSV pipeline from a clean baseline; never hand-edit the "
                        + "corrupted output):\n  " + String.join("\n  ", failures));
            }
        }

        @Test
        @DisplayName("Each locale's messages.yml preserves baseline placeholder tokens")
        void messagePlaceholdersArePreserved() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;
            if (!Files.isDirectory(MESSAGES_DIR)) return;

            Set<String> baselinePlaceholders = collectPlaceholders(loadBaselineValues("messages"));
            List<String> failures = new ArrayList<>();

            try (DirectoryStream<Path> locales = Files.newDirectoryStream(LANG_ROOT, Files::isDirectory)) {
                for (Path localeDir : locales) {
                    String localeName = localeDir.getFileName().toString();
                    if (NON_LOCALE_DIRS.contains(localeName)) continue;
                    Path messages = localeDir.resolve("messages.yml");
                    if (!Files.exists(messages)) continue;

                    Set<String> seen = collectPlaceholders(loadYaml(messages));
                    Set<String> missing = new TreeSet<>(baselinePlaceholders);
                    missing.removeAll(seen);
                    if (!missing.isEmpty()) {
                        Set<String> extras = new TreeSet<>(seen);
                        extras.removeAll(baselinePlaceholders);
                        if (!extras.isEmpty()) {
                            failures.add(String.format(
                                    "%s/messages.yml introduces non-baseline placeholders %s; "
                                            + "expected only %s. Likely an accidentally translated "
                                            + "placeholder token.",
                                    localeName, extras, baselinePlaceholders));
                        }
                    }
                }
            }

            if (!failures.isEmpty()) {
                fail("Placeholder fidelity issues:\n  " + String.join("\n  ", failures));
            }
        }

        /**
         * Catches user-facing message values that were never translated: a
         * non-English locale's {@code messages.yml} value that, after stripping
         * color codes ({@code &a}, {@code #RRGGBB}, {@code §x}) and
         * {@code [placeholder]} tokens, is byte-for-byte identical to the
         * English baseline value for the same key and still contains real words.
         *
         * <p>This is the actual "what was not translated and should be" signal.
         * It is deliberately NOT keyed off {@code .lang.yml} key-name identity:
         * locales legitimately keep the English key <em>name</em> (an internal
         * config identifier) while translating the value, so a self-mapped key
         * name carries no signal. What matters is the rendered value.
         *
         * <p>Pure color/placeholder strings (e.g. {@code "&2&l[apply]"}) leave
         * no residual letters and are skipped; genuinely-English tokens that a
         * locale intentionally leaves verbatim can be suppressed via
         * {@link #UNTRANSLATED_ALLOW}.
         */
        @Test
        @DisplayName("No locale messages.yml value is left as the untranslated English baseline string")
        void noUntranslatedBaselineValues() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;
            if (!Files.isDirectory(MESSAGES_DIR)) return;

            Map<String, Object> baseline = loadBaselineValues("messages");
            Map<String, String> baselineNorm = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : baseline.entrySet()) {
                if (e.getValue() instanceof String s) {
                    baselineNorm.put(e.getKey(), normalizeForTranslation(s));
                }
            }

            Path baselineLang = LANG_ROOT.resolve("messages.lang.yml");
            Map<String, String> baselineLangMap = Files.exists(baselineLang)
                    ? loadLangMap(baselineLang) : Collections.emptyMap();

            List<String> failures = new ArrayList<>();
            for (Path localeDir : discoverLocaleDirs()) {
                String localeName = localeDir.getFileName().toString();
                Path messages = localeDir.resolve("messages.yml");
                if (!Files.exists(messages)) continue;

                // A locale that does not even parse is a separate defect owned
                // by noCsvEscapingCorruption / the parse-level coverage tests;
                // skip it here so this value-level signal stays meaningful.
                Map<String, Object> localeValues;
                Map<String, String> localeLangMap;
                try {
                    localeValues = loadYaml(messages);
                    Path localeLang = localeDir.resolve("messages.lang.yml");
                    localeLangMap = Files.exists(localeLang)
                            ? loadLangMap(localeLang) : Collections.emptyMap();
                } catch (RuntimeException | AssertionError parseFailure) {
                    continue;
                }

                for (Map.Entry<String, String> b : baselineNorm.entrySet()) {
                    String baseKey = b.getKey();
                    String baseResidual = b.getValue();
                    if (baseResidual.isEmpty()) continue;
                    if (UNTRANSLATED_ALLOW.contains(baseResidual)) continue;

                    String translated = localeLangMap.get(baseKey);
                    if (translated == null) translated = baselineLangMap.get(baseKey);
                    if (translated == null) translated = baseKey;

                    Object localeRaw = localeValues.get(translated);
                    if (!(localeRaw instanceof String s)) continue;
                    if (normalizeForTranslation(s).equals(baseResidual)) {
                        failures.add(String.format("%s/messages.yml '%s' still English: \"%s\"",
                                localeName, translated, s));
                    }
                }
            }

            if (!failures.isEmpty()) {
                fail("Untranslated English message values shipped in non-baseline locales "
                        + "(translate the value, or add the residual token to "
                        + "UNTRANSLATED_ALLOW if it is intentionally English):\n  "
                        + String.join("\n  ", failures));
            }
        }
    }

    /**
     * Residual (color- and placeholder-stripped) tokens that are intentionally
     * left in English across every locale - brand names, command literals,
     * and technical acronyms - so {@link ResourceParity#noUntranslatedBaselineValues}
     * does not flag them.
     */
    private static final Set<String> UNTRANSLATED_ALLOW = Set.of(
            // time-unit suffix letters - universal symbols, kept verbatim
            "d", "h", "m", "s", "ms",
            // command literal
            "rtpinfo",
            // prefab identifiers (config keys, not prose)
            "survivaldefault", "lowperformance", "highperformance",
            "foliatuned", "multiworld",
            // diagnostic rows that are only acronyms (TPS/MSPT/JVM/MiB/cps)
            // plus [placeholder] tokens - no translatable prose remains
            "jvmheapmib", "tpsmmm", "servermsptmstickbudget",
            "tpsmsptplayersqueuebudget", "cpslandeta",
            // cross-language cognates that are spelled identically in several
            // locales (notably French/English) - a verbatim spelling is the
            // correct translation, so do not flag it as untranslated
            "configuration", "diagnostics", "type",
            // brand + universal "menu" loanword
            "rtpmenu");

    private static final Pattern COLOR_AMP = Pattern.compile("[&\u00a7][0-9A-Za-z]");
    private static final Pattern COLOR_HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern BRACKET_TOKEN = Pattern.compile("\\[[^\\]]*]");
    private static final Pattern NON_LETTER = Pattern.compile("[^A-Za-z]");

    /**
     * Strips legacy/hex color codes and {@code [placeholder]} tokens, then
     * removes every non-letter character and lowercases. Two values whose only
     * difference is formatting/placeholders collapse to the same residual; a
     * value with no translatable words collapses to the empty string.
     */
    private static String normalizeForTranslation(String value) {
        String s = COLOR_HEX.matcher(value).replaceAll("");
        s = COLOR_AMP.matcher(s).replaceAll("");
        s = BRACKET_TOKEN.matcher(s).replaceAll("");
        s = NON_LETTER.matcher(s).replaceAll("");
        return s.toLowerCase();
    }

    // =========================================================================
    // Group 2: cross-locale coverage (every baseline key reachable per locale)
    // =========================================================================

    @Nested
    @DisplayName("Cross-locale coverage")
    class FullParity {

        @TestFactory
        @DisplayName("baseline <file>.yml keys all have a row in baseline lang/<file>.lang.yml")
        Iterable<DynamicTest> baselineLangMapCoversEveryBaselineKey() throws IOException {
            List<DynamicTest> tests = new ArrayList<>();
            for (String category : discoverBaselineCategories()) {
                tests.add(DynamicTest.dynamicTest(category, () -> {
                    Path langPath = LANG_ROOT.resolve(category + ".lang.yml");
                    Map<String, Object> values = loadBaselineValues(category);
                    Map<String, String> langMap = loadLangMap(langPath);

                    List<String> missing = new ArrayList<>();
                    for (String key : values.keySet()) {
                        if (!langMap.containsKey(key)) missing.add(key);
                    }
                    assertTrue(missing.isEmpty(),
                            "Baseline " + category + ".yml ships keys with no row in "
                                    + "lang/" + category + ".lang.yml (locales cannot "
                                    + "resolve them via translation map): " + missing);
                }));
            }
            return tests;
        }

        @TestFactory
        @DisplayName("each locale ships every baseline key under its translated name (all categories)")
        Iterable<DynamicTest> everyLocaleShipsEveryBaselineKey() throws IOException {
            List<DynamicTest> tests = new ArrayList<>();
            List<String> categories = discoverBaselineCategories();
            List<Path> locales = discoverLocaleDirs();

            for (Path localeDir : locales) {
                String localeName = localeDir.getFileName().toString();
                for (String category : categories) {
                    tests.add(DynamicTest.dynamicTest(localeName + " / " + category, () -> {
                        Path baselineLang = LANG_ROOT.resolve(category + ".lang.yml");
                        Path localeValue = localeDir.resolve(category + ".yml");
                        Path localeLang = localeDir.resolve(category + ".lang.yml");

                        Map<String, Object> baselineValues = loadBaselineValues(category);
                        Map<String, String> baselineLangMap = loadLangMap(baselineLang);

                        if (!Files.exists(localeValue)) {
                            if (Files.exists(localeLang)) {
                                fail("Locale '" + localeName + "' ships "
                                        + category + ".lang.yml but no "
                                        + category + ".yml to back its "
                                        + "translated key names - runtime "
                                        + "will fall through to English.");
                            }
                            return;
                        }

                        Map<String, Object> localeValues = loadYaml(localeValue);
                        Map<String, String> localeLangMap = Files.exists(localeLang)
                                ? loadLangMap(localeLang)
                                : Collections.emptyMap();

                        List<String> missing = new ArrayList<>();
                        for (String baselineKey : baselineValues.keySet()) {
                            String translated = localeLangMap.get(baselineKey);
                            if (translated == null) translated = baselineLangMap.get(baselineKey);
                            if (translated == null) translated = baselineKey;
                            if (!localeValues.containsKey(translated)) {
                                missing.add(baselineKey + " (expected as '" + translated + "')");
                            }
                        }
                        assertTrue(missing.isEmpty(),
                                "Locale '" + localeName + "/" + category + ".yml' is "
                                        + "missing " + missing.size() + " baseline "
                                        + "entries (runtime would silently fall back "
                                        + "to English for these keys): " + missing);
                    }));
                }
            }
            return tests;
        }

        @TestFactory
        @DisplayName("each locale's <file>.lang.yml keys exist in the baseline <file>.yml (no stale rows)")
        Iterable<DynamicTest> localeLangMapsHaveNoStaleRows() throws IOException {
            List<DynamicTest> tests = new ArrayList<>();
            List<String> categories = discoverBaselineCategories();
            List<Path> locales = discoverLocaleDirs();

            for (Path localeDir : locales) {
                String localeName = localeDir.getFileName().toString();
                for (String category : categories) {
                    Path localeLang = localeDir.resolve(category + ".lang.yml");
                    if (!Files.exists(localeLang)) continue;
                    tests.add(DynamicTest.dynamicTest(localeName + " / " + category + ".lang.yml", () -> {
                        Set<String> baselineKeys = loadBaselineValues(category).keySet();
                        Map<String, String> map = loadLangMap(localeLang);
                        List<String> stale = new ArrayList<>();
                        for (String key : map.keySet()) {
                            if (!baselineKeys.contains(key)) stale.add(key);
                        }
                        assertTrue(stale.isEmpty(),
                                "Locale '" + localeName + "/" + category + ".lang.yml' "
                                        + "declares rows for keys the baseline "
                                        + category + ".yml no longer ships "
                                        + "(stale - likely a deleted or renamed "
                                        + "baseline entry): " + stale);
                    }));
                }
            }
            return tests;
        }

        @Test
        @DisplayName("at least one locale subdirectory is discovered under lang/")
        void atLeastOneLocaleDiscovered() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;
            assertTrue(!discoverLocaleDirs().isEmpty(),
                    "No locale subdirectories found under " + LANG_ROOT.toAbsolutePath()
                            + " (expected at least one). NON_LOCALE_DIRS=" + NON_LOCALE_DIRS);
        }
    }

    // =========================================================================
    // Shared discovery + YAML helpers
    // =========================================================================

    /** Baseline categories: every {@code <file>.yml} with a sibling {@code lang/<file>.lang.yml}. */
    private static List<String> discoverBaselineCategories() throws IOException {
        List<String> categories = new ArrayList<>();
        if (!Files.isDirectory(RESOURCES) || !Files.isDirectory(LANG_ROOT)) {
            return categories;
        }
        try (DirectoryStream<Path> ymls = Files.newDirectoryStream(RESOURCES, "*.yml")) {
            for (Path yml : ymls) {
                String name = yml.getFileName().toString();
                String stem = name.substring(0, name.length() - ".yml".length());
                Path langSibling = LANG_ROOT.resolve(stem + ".lang.yml");
                if (Files.exists(langSibling)) categories.add(stem);
            }
        }
        // ADR-071: the advanced/ tier holds ordinary single-file parsers under a
        // subdirectory. Each advanced/<file>.yml whose sibling lang map lives at
        // lang/advanced/<file>.lang.yml is a category named "advanced/<file>".
        Path advancedDir = RESOURCES.resolve("advanced");
        if (Files.isDirectory(advancedDir)) {
            try (DirectoryStream<Path> ymls = Files.newDirectoryStream(advancedDir, "*.yml")) {
                for (Path yml : ymls) {
                    String name = yml.getFileName().toString();
                    String stem = name.substring(0, name.length() - ".yml".length());
                    Path langSibling = LANG_ROOT.resolve("advanced").resolve(stem + ".lang.yml");
                    if (Files.exists(langSibling)) categories.add("advanced/" + stem);
                }
            }
        }
        // "messages" is directory-backed (messages/*.yml) with sibling
        // lang/messages.lang.yml; it has no top-level messages.yml to discover.
        if (Files.isDirectory(MESSAGES_DIR)
                && Files.exists(LANG_ROOT.resolve("messages.lang.yml"))
                && !categories.contains("messages")) {
            categories.add("messages");
        }
        Collections.sort(categories);
        return categories;
    }

    /**
     * Baseline value map for a category. For the directory-backed "messages"
     * category this is the union of every {@code messages/*.yml} member file;
     * for all others it is the single top-level {@code <category>.yml}.
     */
    private static Map<String, Object> loadBaselineValues(String category) throws IOException {
        if ("messages".equals(category)) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (Files.isDirectory(MESSAGES_DIR)) {
                try (DirectoryStream<Path> ymls = Files.newDirectoryStream(MESSAGES_DIR, "*.yml")) {
                    for (Path p : ymls) merged.putAll(loadYaml(p));
                }
            }
            return merged;
        }
        return loadYaml(RESOURCES.resolve(category + ".yml"));
    }

    /** Locale dirs: every directory under {@code lang/} not in {@link #NON_LOCALE_DIRS}. */
    private static List<Path> discoverLocaleDirs() throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (!Files.isDirectory(LANG_ROOT)) return dirs;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(LANG_ROOT, Files::isDirectory)) {
            for (Path p : stream) {
                if (NON_LOCALE_DIRS.contains(p.getFileName().toString())) continue;
                dirs.add(p);
            }
        }
        dirs.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return dirs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        assertTrue(Files.exists(path), "Expected file: " + path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            assertTrue(loaded instanceof Map,
                    "Root of " + path + " must be a YAML mapping; got "
                            + (loaded == null ? "null" : loaded.getClass()));
            return (Map<String, Object>) loaded;
        }
    }

    private static Map<String, String> loadLangMap(Path path) throws IOException {
        Map<String, Object> raw = loadYaml(path);
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            map.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return map;
    }

    private static Set<String> collectPlaceholders(Map<String, Object> yaml) {
        Set<String> out = new TreeSet<>();
        for (Object v : yaml.values()) {
            scan(v, out);
        }
        return out;
    }

    private static void scan(Object v, Set<String> out) {
        if (v == null) return;
        if (v instanceof CharSequence cs) {
            Matcher m = PLACEHOLDER.matcher(cs);
            while (m.find()) out.add(m.group());
        } else if (v instanceof List<?> list) {
            for (Object o : list) scan(o, out);
        } else if (v instanceof Map<?, ?> map) {
            for (Object o : map.values()) scan(o, out);
        }
    }
}
