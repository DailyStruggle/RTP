package io.github.dailystruggle.rtp.bukkit.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-F-013 / ADR-020 - regression gate that fails when a shipped locale
 * value file carries a string that is still the English baseline text (or an
 * English fragment left behind by a partial translation), for values that
 * <em>genuinely should</em> be translated.
 *
 * <p>This is the runtime-build counterpart of {@code scripts/scan-locale-untranslated.py}.
 * {@link LocaleParityTest} only checks that every key <em>exists</em> in every
 * locale (parity); it cannot tell that the value under that key is still English.
 * This suite closes that gap so a newly-added baseline string that nobody
 * mirrored into the locales (or a half-finished translation) cannot land unnoticed.
 *
 * <p>Two categories, mirroring the Python scanner:
 * <ul>
 *   <li><b>SAME_VALUE</b> - the locale leaf is byte-identical to the English
 *       baseline leaf AND the value is genuine translatable prose (not a number,
 *       enum token, color code, placeholder-only string, command literal, etc.).</li>
 *   <li><b>ENGLISH_LEFTOVER</b> - the locale leaf was edited (differs from
 *       English) but still carries {@code >= 2} distinct English marker words.</li>
 * </ul>
 *
 * <p>Two narrowing rules keep the gate honest:
 * <ul>
 *   <li>{@link #EXEMPT_BASE_KEYS} - keys whose value is intentionally identical
 *       across every locale (command literals like {@code /rtp info}, acronym-only
 *       lines like the TPS readout). These are not "untranslated", they are
 *       language-neutral by design.</li>
 *   <li>{@link #ENGLISH_DIALECT_LOCALES} - the Internet-Cat dialect ({@code cat})
 *       is an English-based register by design, so identical-to-English connector
 *       words there are intentional, not defects.</li>
 * </ul>
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 - locale untranslated-value gate")
public class LocaleUntranslatedTest {

    private static final Path RESOURCES = Paths.get("src", "main", "resources");
    private static final Path LANG_ROOT = RESOURCES.resolve("lang");

    /** Subdirectories under {@code lang/} that are NOT locale folders. */
    private static final Set<String> NON_LOCALE_DIRS = Set.of("shape", "vert");

    /**
     * Locales whose register is an English-based dialect by design (the
     * Internet-Cat dialect, ISO-misnamed {@code cat}); identical-to-English
     * connector words there are intentional, so the locale is exempt.
     */
    private static final Set<String> ENGLISH_DIALECT_LOCALES = Set.of("cat");

    /**
     * Baseline keys whose value is intentionally language-neutral and identical
     * across every locale. Each entry is a genuine, reviewed decision:
     * <ul>
     *   <li>{@code infoTitle} - the literal {@code /rtp info} command string.</li>
     *   <li>{@code infoTps} - an acronym-only readout (TPS 1m/5m/15m).</li>
     *   <li>{@code scanStatus} - a symbol/placeholder progress line whose only
     *       residual words are units/acronyms (cps, land, ETA).</li>
     *   <li>{@code menuHoverFallbackType} - the developer-facing {@code type: [type]} hint.</li>
     * </ul>
     * Add to this set only after confirming the value truly should stay English
     * everywhere; the default expectation is that a value gets translated.
     */
    private static final Set<String> EXEMPT_BASE_KEYS = Set.of(
            "infoTitle", "infoTps", "scanStatus", "menuHoverFallbackType");

    /**
     * English connector / function words unlikely to survive verbatim in a
     * properly translated value. Used for ENGLISH_LEFTOVER detection. Mirrors
     * {@code scan-locale-untranslated.py}: deliberately excludes words that
     * collide with target languages or are kept as loanwords ("no", "server",
     * "chunks", "world", "player").
     */
    private static final String[] EN_MARKERS = {
            " the ", " and ", " for ", " with ", " when ", " this ", " that ", " your ",
            " you ", " will ", " must ", " should ", " does ", " from ", " into ",
            " before ", " after ", " between ", " available ", " enabled ", " disabled ",
            " empty ", " list ", " command ", " commands ", " loaded ", " already ",
            " reject ", " cause ", " teleporting ", " success ",
    };

    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern BOOLISH = Pattern.compile("^(true|false|null|yes|no)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern SENTINEL = Pattern.compile("^__.+__$");
    private static final Pattern BARE_COLOR = Pattern.compile("^&?#?[0-9A-Fa-f]{3,8}$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_.\\-]*$");
    private static final Pattern NAMESPACED = Pattern.compile("^#?[A-Za-z][A-Za-z0-9_./\\-]*:[A-Za-z0-9_./\\-:]+$");
    private static final Pattern KEBAB = Pattern.compile("[^A-Za-z]*[a-z]+(?:-[a-z]+)+[^A-Za-z]*");

    @TestFactory
    @DisplayName("no shipped locale value is still untranslated English (per category)")
    Iterable<DynamicTest> noUntranslatedEnglishValues() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        List<String> categories = discoverBaselineCategories();
        List<Path> locales = discoverLocaleDirs();

        for (Path localeDir : locales) {
            String localeName = localeDir.getFileName().toString();
            for (String category : categories) {
                tests.add(DynamicTest.dynamicTest(localeName + " / " + category, () -> {
                    Path baselineValue = RESOURCES.resolve(category + ".yml");
                    Path baselineLang = LANG_ROOT.resolve(category + ".lang.yml");
                    Path localeValue = localeDir.resolve(category + ".yml");
                    Path localeLang = localeDir.resolve(category + ".lang.yml");

                    if (!Files.exists(localeValue)) return;

                    Map<String, Object> baselineValues = loadYaml(baselineValue);
                    Map<String, Object> localeValues = loadYaml(localeValue);
                    Map<String, String> baselineLangMap =
                            Files.exists(baselineLang) ? loadLangMap(baselineLang) : Collections.emptyMap();
                    Map<String, String> localeLangMap =
                            Files.exists(localeLang) ? loadLangMap(localeLang) : Collections.emptyMap();

                    List<String> sameValue = new ArrayList<>();
                    List<String> leftover = new ArrayList<>();

                    for (Map.Entry<String, Object> e : baselineValues.entrySet()) {
                        String baseKey = e.getKey();
                        if (EXEMPT_BASE_KEYS.contains(baseKey)) continue;

                        String translated = localeLangMap.get(baseKey);
                        if (translated == null) translated = baselineLangMap.get(baseKey);
                        if (translated == null) translated = baseKey;
                        if (!localeValues.containsKey(translated)) continue; // parity test owns this

                        List<String> baseLeaves = new ArrayList<>();
                        List<String> locLeaves = new ArrayList<>();
                        flatten(e.getValue(), baseLeaves);
                        flatten(localeValues.get(translated), locLeaves);
                        if (baseLeaves.size() != locLeaves.size()) continue; // structure differs

                        for (int i = 0; i < baseLeaves.size(); i++) {
                            String baseLeaf = baseLeaves.get(i);
                            String locLeaf = locLeaves.get(i);
                            if (locLeaf.equals(baseLeaf)) {
                                if (!isLanguageAgnosticValue(locLeaf)) {
                                    sameValue.add(baseKey + "[" + i + "] = \"" + baseLeaf + "\"");
                                }
                            } else if (!ENGLISH_DIALECT_LOCALES.contains(localeName)) {
                                List<String> markers = englishLeftoverMarkers(locLeaf);
                                if (markers.size() >= 2) {
                                    leftover.add(baseKey + "[" + i + "] = \"" + locLeaf + "\" <"
                                            + String.join(",", markers) + ">");
                                }
                            }
                        }
                    }

                    List<String> failures = new ArrayList<>();
                    if (!ENGLISH_DIALECT_LOCALES.contains(localeName)) {
                        failures.addAll(sameValue);
                    }
                    failures.addAll(leftover);

                    assertTrue(failures.isEmpty(),
                            "Locale '" + localeName + "/" + category + ".yml' has "
                                    + failures.size() + " value(s) that are still untranslated "
                                    + "English (identical to baseline) or carry an English "
                                    + "leftover fragment. Translate them via the locale changeset "
                                    + "pipeline (see AGENTS.md \"Locale Config TSV Pipeline\"), or - "
                                    + "if the value is intentionally language-neutral - add the key "
                                    + "to EXEMPT_BASE_KEYS:\n  " + String.join("\n  ", failures));
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
                "No locale subdirectories found under " + LANG_ROOT.toAbsolutePath());
    }

    // =========================================================================
    // Translatability filter (ported from scan-locale-untranslated.py)
    // =========================================================================

    /** Strip color codes, hex, section signs and {@code [placeholder]} tokens. */
    private static String maskValue(String value) {
        String s = value.replaceAll("&[0-9A-Za-z]", " ");
        s = s.replaceAll("#[0-9A-Fa-f]{6}\\b", " ");
        s = s.replaceAll("#[0-9A-Fa-f]{3}\\b", " ");
        s = s.replaceAll("\u00a7.", " ");        // legacy section-sign color code
        s = s.replaceAll("\\[[^\\]]*\\]", " ");  // [placeholder] substitution tokens
        return s;
    }

    /**
     * True when the value is not genuine translatable prose: empty, numeric,
     * boolean, an IP/host, a sentinel, a single config enum/identifier token, a
     * namespaced resource id, an all-kebab-case selector, or a string with no
     * residual prose word after masking codes/placeholders.
     */
    static boolean isLanguageAgnosticValue(String value) {
        if (value == null) return true;
        String v = value.strip();
        if (v.isEmpty()) return true;
        if (NUMERIC.matcher(v).matches()) return true;
        if (BOOLISH.matcher(v).matches()) return true;
        if (IPV4.matcher(v).matches()) return true;
        if (SENTINEL.matcher(v).matches()) return true;
        if (BARE_COLOR.matcher(v).matches()) return true;
        if (!v.contains(" ") && IDENT.matcher(v).matches()) return true;
        if (!v.contains(" ") && NAMESPACED.matcher(v).matches()) return true;

        String masked = maskValue(v);
        List<String> tokens = new ArrayList<>();
        for (String t : masked.trim().split("\\s+")) {
            if (Pattern.compile("[A-Za-z]").matcher(t).find()) tokens.add(t);
        }
        if (!tokens.isEmpty()) {
            boolean allKebab = true;
            for (String t : tokens) {
                if (!KEBAB.matcher(t).matches()) { allKebab = false; break; }
            }
            if (allKebab) return true;
        }
        // Residual prose check: words of >= 3 letters left after masking.
        // Require >= 2 residual words to count as prose: a single residual word
        // is almost always a short field label (e.g. "region:", "override:")
        // that is frequently a cross-language cognate, so a single-word match is
        // too ambiguous to flag as untranslated without false positives.
        String residual = masked.replaceAll("[^A-Za-z ]", " ");
        int words = 0;
        for (String w : residual.trim().split("\\s+")) {
            if (w.length() >= 3) words++;
        }
        return words < 2;
    }

    /** Distinct English marker words present in a value's residual prose. */
    private static List<String> englishLeftoverMarkers(String value) {
        if (isLanguageAgnosticValue(value)) return Collections.emptyList();
        String hay = " " + maskValue(value).toLowerCase() + " ";
        TreeSet<String> found = new TreeSet<>();
        for (String m : EN_MARKERS) {
            if (hay.contains(m)) found.add(m.strip());
        }
        return new ArrayList<>(found);
    }

    // =========================================================================
    // Shared discovery + YAML helpers
    // =========================================================================

    private static void flatten(Object value, List<String> out) {
        if (value == null) {
            out.add("");
        } else if (value instanceof CharSequence cs) {
            out.add(cs.toString());
        } else if (value instanceof List<?> list) {
            for (Object o : list) flatten(o, out);
        } else if (value instanceof Map<?, ?> map) {
            for (Object o : map.values()) flatten(o, out);
        } else {
            out.add(String.valueOf(value));
        }
    }

    private static List<String> discoverBaselineCategories() throws IOException {
        List<String> categories = new ArrayList<>();
        if (!Files.isDirectory(RESOURCES) || !Files.isDirectory(LANG_ROOT)) return categories;
        try (DirectoryStream<Path> ymls = Files.newDirectoryStream(RESOURCES, "*.yml")) {
            for (Path yml : ymls) {
                String name = yml.getFileName().toString();
                String stem = name.substring(0, name.length() - ".yml".length());
                Path langSibling = LANG_ROOT.resolve(stem + ".lang.yml");
                if (Files.exists(langSibling)) categories.add(stem);
            }
        }
        Collections.sort(categories);
        return categories;
    }

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
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new IllegalStateException("Root of " + path + " must be a YAML mapping");
            }
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
}
