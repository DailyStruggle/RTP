package io.github.dailystruggle.rtp.bukkit.configuration;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.common.bit.config.MessageKeyParity;
import io.github.dailystruggle.rtp.common.bit.config.MessagesConfigCheck;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@link MessageKeyParity#NON_LOCALE_DIRS} is a locale. Adding a new locale extends coverage
 * with no test edit. Same for categories: every baseline {@code <file>.yml}
 * with a sibling {@code lang/<file>.lang.yml} is scanned.
 *
 * <p>Key-set computation (discovery, translated names, missing / stale / unresolved rows) is
 * delegated to {@link MessageKeyParity}, the same code the {@code messages} config PBIT runs over
 * the runtime classpath (ADR-121 section 6).
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 - locale parity (resource hygiene + cross-locale coverage)")
public class LocaleParityTest {

    private static final Path RESOURCES =
            Paths.get("src", "main", "resources");
    private static final Path LANG_ROOT = RESOURCES.resolve("lang");
    private static final MessageKeyParity.ResourceReader READER =
            MessageKeyParity.ResourceReader.directory(RESOURCES);
    /**
     * The English baseline messages are split, per concern, across this
     * directory (placeholders / player / network / commands / system). Each
     * member file is its own category {@code advanced/messages/<file>} with a
     * co-located dotfile rename map {@code .<file>.lang.yml} (ADR-076); the
     * per-locale value + map trees mirror this under
     * {@code lang/<locale>/advanced/messages/}. The flat per-locale
     * {@code lang/<locale>/messages.yml} value file is retained as the union of
     * these members and drives the placeholder / untranslated value scans.
     */
    private static final Path MESSAGES_DIR = RESOURCES.resolve("advanced").resolve("messages");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Za-z0-9_]+]");

    /** Subdirectories under {@code lang/} that are NOT locale folders. */
    private static final Set<String> NON_LOCALE_DIRS = MessageKeyParity.NON_LOCALE_DIRS;

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

                    List<Path> langMaps = new ArrayList<>();
                    try (var stream = Files.walk(localeDir)) {
                        stream.filter(Files::isRegularFile)
                              .filter(p -> p.getFileName().toString().endsWith(".lang.yml"))
                              .forEach(langMaps::add);
                    }
                    langMaps.sort((a, b) -> a.toString().compareTo(b.toString()));

                    for (Path langMap : langMaps) {
                        String mapName = langMap.getFileName().toString();
                        // ADR-076: maps are co-located dotfile siblings
                        // (`.config.lang.yml`); the backing value file drops
                        // the leading dot (`config.yml`). The legacy monolithic
                        // `messages.lang.yml` has no leading dot and is handled
                        // by the same stripping (no-op).
                        String stem = mapName.substring(
                                0, mapName.length() - ".lang.yml".length());
                        if (stem.startsWith(".")) stem = stem.substring(1);
                        String valueName = stem + ".yml";
                        Path valueFile = langMap.getParent().resolve(valueName);

                        Map<String, String> map = MessageKeyParity.langMap(READER, rel(langMap));

                        // Never rename the `version` sentinel key in any .lang.yml map.
                        String versionTarget = map.get("version");
                        if (versionTarget != null && !"version".equals(versionTarget)) {
                            failures.add(String.format(
                                    "%s maps version -> %s (version sentinel must remain 'version')",
                                    rel(langMap), versionTarget));
                        }

                        if (!Files.exists(valueFile)) {
                            // .lang.yml without a sibling value file: allowed
                            // for template-only files (regions, worlds).
                            continue;
                        }

                        Set<String> valueKeys = MessageKeyParity.keys(READER, rel(valueFile));

                        MessageKeyParity.unresolvedLangRows(map, valueKeys).forEach((key, mappedName) ->
                                failures.add(String.format(
                                        "%s maps %s -> %s but %s has no top-level key %s",
                                        rel(langMap), key, mappedName,
                                        rel(valueFile), mappedName)));

                        Set<String> mappedTargets = new TreeSet<>(map.values());
                        for (String vk : valueKeys) {
                            if ("version".equals(vk)) continue;
                            if (!mappedTargets.contains(vk)) {
                                failures.add(String.format(
                                        "%s has orphan top-level key '%s' not referenced by %s",
                                        rel(valueFile), vk, rel(langMap)));
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
        @DisplayName("Each locale's advanced/messages tree preserves baseline placeholder tokens")
        void messagePlaceholdersArePreserved() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;
            if (!Files.isDirectory(MESSAGES_DIR)) return;

            List<String> failures = new ArrayList<>();

            for (String fileStem : MessageKeyParity.MESSAGE_FILES) {
                Path baseValueFile = MESSAGES_DIR.resolve(fileStem + ".yml");
                Path baseLangFile = MESSAGES_DIR.resolve("." + fileStem + ".lang.yml");
                if (!Files.exists(baseValueFile)) continue;

                Map<String, Object> baseValues = loadYaml(baseValueFile);
                Map<String, String> baseLang = Files.exists(baseLangFile)
                        ? loadLangMap(baseLangFile)
                        : Collections.emptyMap();

                // Collect the set of known placeholder tokens in the English baseline for this file
                // (excluding literal bracketed UI labels such as [confirm], [cancel], [Refresh], etc.).
                Set<String> filePlaceholderVocab = new TreeSet<>();
                for (Map.Entry<String, Object> e : baseValues.entrySet()) {
                    if ("placeholders".equals(e.getKey())) continue;
                    Set<String> tokens = new TreeSet<>();
                    scan(e.getValue(), tokens);
                    for (String t : tokens) {
                        if (!NON_PLACEHOLDER_BRACKET_LABELS.contains(t)) {
                            filePlaceholderVocab.add(t);
                        }
                    }
                }

                for (Path localeDir : discoverLocaleDirs()) {
                    String localeName = localeDir.getFileName().toString();
                    Path locMsgDir = localeDir.resolve("advanced").resolve("messages");
                    Path locValueFile = locMsgDir.resolve(fileStem + ".yml");
                    Path locLangFile = locMsgDir.resolve("." + fileStem + ".lang.yml");
                    if (!Files.exists(locValueFile)) continue;

                    Map<String, Object> locValues = loadYaml(locValueFile);
                    Map<String, String> locLang = Files.exists(locLangFile)
                            ? loadLangMap(locLangFile)
                            : Collections.emptyMap();

                    for (Map.Entry<String, Object> e : baseValues.entrySet()) {
                        String baseKey = e.getKey();
                        if ("version".equals(baseKey) || "placeholders".equals(baseKey)) continue;

                        String translatedKey = MessageKeyParity.translatedKey(baseKey, baseLang, locLang);
                        Object locVal = locValues.get(translatedKey);
                        if (locVal == null) continue;

                        Set<String> baseKeyTokens = new TreeSet<>();
                        scan(e.getValue(), baseKeyTokens);
                        baseKeyTokens.retainAll(filePlaceholderVocab);

                        Set<String> locKeyTokens = new TreeSet<>();
                        scan(locVal, locKeyTokens);
                        locKeyTokens.retainAll(filePlaceholderVocab);

                        if (!baseKeyTokens.equals(locKeyTokens)) {
                            failures.add(String.format(
                                    "%s/advanced/messages/%s.yml key '%s' (baseline '%s') has placeholders %s; expected %s",
                                    localeName, fileStem, translatedKey, baseKey, locKeyTokens, baseKeyTokens));
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
         * non-English locale's {@code advanced/messages} value that, after stripping
         * color codes ({@code &a}, {@code #RRGGBB}, {@code section x}) and
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
        @DisplayName("No locale message value is left as the untranslated English baseline string")
        void noUntranslatedBaselineValues() throws IOException {
            if (!Files.isDirectory(LANG_ROOT)) return;
            if (!Files.isDirectory(MESSAGES_DIR)) return;

            Map<String, Object> baseline = mergedMessagesBaseline();
            Map<String, String> baselineNorm = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : baseline.entrySet()) {
                if (e.getValue() instanceof String s) {
                    baselineNorm.put(e.getKey(), normalizeForTranslation(s));
                }
            }

            // ADR-076: baseline rename map is the union of the per-file
            // co-located dotfile maps under advanced/messages/.
            Map<String, String> baselineLangMap = mergedMessagesLangMap(MESSAGES_DIR);

            List<String> failures = new ArrayList<>();
            for (Path localeDir : discoverLocaleDirs()) {
                String localeName = localeDir.getFileName().toString();
                Path messagesDir = localeDir.resolve("advanced").resolve("messages");
                if (!Files.isDirectory(messagesDir)) continue;

                // A locale that does not even parse is a separate defect owned
                // by noCsvEscapingCorruption / the parse-level coverage tests;
                // skip it here so this value-level signal stays meaningful.
                Map<String, Object> localeValues;
                Map<String, String> localeLangMap;
                try {
                    localeValues = mergedMessages(messagesDir);
                    // ADR-076: per-locale rename map is the union of the per-file
                    // co-located dotfile maps under lang/<locale>/advanced/messages/.
                    localeLangMap = mergedMessagesLangMap(
                            localeDir.resolve("advanced").resolve("messages"));
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
                        failures.add(String.format("%s/advanced/messages '%s' still English: \"%s\"",
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
            "configuration", "diagnostics", "type", "documentation",
            // brand + universal "menu" loanword
            "rtpmenu");

    /**
     * Multi-word bracketed UI button labels in the English baseline that are literal
     * display text (not single-word {@code [placeholder]} tokens) and therefore
     * SHOULD be translated by locales rather than stripped as placeholders.
     */
    private static final Set<String> LITERAL_BRACKET_LABELS = Set.of(
            "[confirm delete]", "[Switch to chat]");

    /**
     * All bracketed UI button labels in the English baseline whose comments declare
     * {@code No placeholders.} and therefore must be excluded from the runtime
     * placeholder vocabulary in {@link ResourceParity#messagePlaceholdersArePreserved}.
     */
    private static final Set<String> NON_PLACEHOLDER_BRACKET_LABELS = Set.of(
            "[confirm]", "[cancel]", "[Refresh]", "[apply]", "[discard]",
            "[confirm delete]", "[Switch to chat]");

    private static final Pattern COLOR_AMP = Pattern.compile("[&\u00a7][0-9A-Za-z]");
    private static final Pattern COLOR_HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern BRACKET_TOKEN = Pattern.compile("\\[[^\\]]*]");
    private static final Pattern NON_LETTER = Pattern.compile("[^A-Za-z]");

    /**
     * Strips legacy/hex color codes and runtime {@code [placeholder]} tokens
     * (while preserving literal bracketed UI labels in {@link #LITERAL_BRACKET_LABELS}),
     * then removes every non-letter character and lowercases. Two values whose only
     * difference is formatting/placeholders collapse to the same residual; a
     * value with no translatable words collapses to the empty string.
     */
    private static String normalizeForTranslation(String value) {
        String s = COLOR_HEX.matcher(value).replaceAll("");
        s = COLOR_AMP.matcher(s).replaceAll("");
        Matcher m = BRACKET_TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            if (LITERAL_BRACKET_LABELS.contains(token)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(token));
            } else {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        s = NON_LETTER.matcher(sb.toString()).replaceAll("");
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
        Iterable<DynamicTest> baselineLangMapCoversEveryBaselineKey() {
            List<DynamicTest> tests = new ArrayList<>();
            for (String category : discoverBaselineCategories()) {
                tests.add(DynamicTest.dynamicTest(category, () -> {
                    Set<String> values = loadBaselineKeys(category);
                    Map<String, String> langMap = loadLangMap(MessageKeyParity.baselineLangPath(category));

                    List<String> missing = MessageKeyParity.unmappedBaselineKeys(values, langMap);
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
        Iterable<DynamicTest> everyLocaleShipsEveryBaselineKey() {
            List<DynamicTest> tests = new ArrayList<>();
            List<String> categories = discoverBaselineCategories();
            List<Path> locales = discoverLocaleDirs();

            for (Path localeDir : locales) {
                String localeName = localeDir.getFileName().toString();
                for (String category : categories) {
                    tests.add(DynamicTest.dynamicTest(localeName + " / " + category, () -> {
                        String localeValue = MessageKeyParity.localeValuePath(localeName, category);
                        String localeLang = MessageKeyParity.localeLangPath(localeName, category);

                        Set<String> baselineValues = loadBaselineKeys(category);
                        Map<String, String> baselineLangMap =
                                loadLangMap(MessageKeyParity.baselineLangPath(category));

                        if (!READER.exists(localeValue)) {
                            if (READER.exists(localeLang)) {
                                fail("Locale '" + localeName + "' ships "
                                        + category + ".lang.yml but no "
                                        + category + ".yml to back its "
                                        + "translated key names - runtime "
                                        + "will fall through to English.");
                            }
                            return;
                        }

                        Set<String> localeValues = MessageKeyParity.keys(READER, localeValue);
                        Map<String, String> localeLangMap = READER.exists(localeLang)
                                ? loadLangMap(localeLang)
                                : Collections.emptyMap();

                        List<String> missing = new ArrayList<>();
                        MessageKeyParity.missingLocaleKeys(baselineValues, baselineLangMap, localeLangMap, localeValues)
                                .forEach((baselineKey, translated) ->
                                        missing.add(baselineKey + " (expected as '" + translated + "')"));
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
        Iterable<DynamicTest> localeLangMapsHaveNoStaleRows() {
            List<DynamicTest> tests = new ArrayList<>();
            List<String> categories = discoverBaselineCategories();
            List<Path> locales = discoverLocaleDirs();

            for (Path localeDir : locales) {
                String localeName = localeDir.getFileName().toString();
                for (String category : categories) {
                    String localeLang = MessageKeyParity.localeLangPath(localeName, category);
                    if (!READER.exists(localeLang)) continue;
                    tests.add(DynamicTest.dynamicTest(localeName + " / " + category + ".lang.yml", () -> {
                        Set<String> baselineKeys = loadBaselineKeys(category);
                        Map<String, String> map = loadLangMap(localeLang);
                        List<String> stale = MessageKeyParity.staleLangRows(baselineKeys, map);
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
        @DisplayName("ADR-121 messages PBIT over src/main/resources reports no RTP_MESSAGES_LOCALE_KEY_MISSING or other gap")
        void messagesPbitAgreesWithParitySuite() {
            if (!Files.isDirectory(LANG_ROOT)) return;
            List<BitFinding> findings = MessagesConfigCheck.bundledFindings(READER);
            assertTrue(findings.isEmpty(), "messages config PBIT findings: " + findings);
        }

        @Test
        @DisplayName("ADR-121 classpath reader discovers the same locales as src/main/resources")
        void classpathReaderDiscoversShippedLocales() {
            if (!Files.isDirectory(LANG_ROOT)) return;
            MessageKeyParity.ResourceReader classpath =
                    MessageKeyParity.ResourceReader.classpath(LocaleParityTest.class.getClassLoader());
            assertEquals(MessageKeyParity.locales(READER), MessageKeyParity.locales(classpath));
        }

        @Test
        @DisplayName("at least one locale subdirectory is discovered under lang/")
        void atLeastOneLocaleDiscovered() {
            if (!Files.isDirectory(LANG_ROOT)) return;
            assertTrue(!discoverLocaleDirs().isEmpty(),
                    "No locale subdirectories found under " + LANG_ROOT.toAbsolutePath()
                            + " (expected at least one). NON_LOCALE_DIRS=" + NON_LOCALE_DIRS);
        }
    }

    // =========================================================================
    // Shared discovery + YAML helpers
    // =========================================================================

    private static String rel(Path path) {
        return RESOURCES.relativize(path).toString().replace('\\', '/');
    }

    /** Baseline categories: every {@code <file>.yml} with a co-located {@code .<file>.lang.yml}. */
    private static List<String> discoverBaselineCategories() {
        return MessageKeyParity.categories(READER);
    }

    /** Baseline top-level keys for a category: the single {@code <category>.yml}. */
    private static Set<String> loadBaselineKeys(String category) {
        String path = category + ".yml";
        assertTrue(READER.exists(path), "Expected file: " + RESOURCES.resolve(path).toAbsolutePath());
        return MessageKeyParity.keys(READER, path);
    }

    /**
     * Union of every {@code advanced/messages/*.yml} member file - the effective
     * baseline key set for the split messages tree, compared against the same
     * union taken over each locale's {@code advanced/messages} mirror.
     */
    private static Map<String, Object> mergedMessagesBaseline() throws IOException {
        return mergedMessages(MESSAGES_DIR);
    }

    /**
     * Union of every {@code *.yml} member of an {@code advanced/messages}
     * directory (baseline or a locale mirror). The co-located {@code .<file>.lang.yml}
     * rename maps are dotfiles and are excluded.
     */
    private static Map<String, Object> mergedMessages(Path messagesDir) throws IOException {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (Files.isDirectory(messagesDir)) {
            try (DirectoryStream<Path> ymls = Files.newDirectoryStream(messagesDir, "*.yml")) {
                for (Path p : ymls) {
                    if (p.getFileName().toString().endsWith(".lang.yml")) continue;
                    merged.putAll(loadYaml(p));
                }
            }
        }
        return merged;
    }

    /**
     * Union of every co-located dotfile rename map ({@code .<file>.lang.yml}) in
     * a {@code advanced/messages} directory (baseline or a locale mirror).
     */
    private static Map<String, String> mergedMessagesLangMap(Path messagesDir) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        if (Files.isDirectory(messagesDir)) {
            try (DirectoryStream<Path> maps = Files.newDirectoryStream(messagesDir, "*.lang.yml")) {
                for (Path p : maps) merged.putAll(loadLangMap(p));
            }
        }
        return merged;
    }

    /** Locale dirs: every directory under {@code lang/} not in {@link #NON_LOCALE_DIRS}. */
    private static List<Path> discoverLocaleDirs() {
        List<Path> dirs = new ArrayList<>();
        for (String locale : MessageKeyParity.locales(READER)) dirs.add(LANG_ROOT.resolve(locale));
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

    /** Rename map at a resource-relative path, read by {@link MessageKeyParity}. */
    private static Map<String, String> loadLangMap(String path) {
        assertTrue(READER.exists(path), "Expected file: " + RESOURCES.resolve(path).toAbsolutePath());
        return MessageKeyParity.langMap(READER, path);
    }

    private static Map<String, String> loadLangMap(Path path) throws IOException {
        Map<String, Object> raw = loadYaml(path);
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            map.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return map;
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
