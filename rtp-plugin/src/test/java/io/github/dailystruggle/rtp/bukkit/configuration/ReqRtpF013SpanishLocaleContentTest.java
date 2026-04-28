package io.github.dailystruggle.rtp.bukkit.configuration;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-F-013 / ADR-020 — content-level regression tests for the shipped
 * Spanish translation at {@code rtp-plugin/src/main/resources/lang/es/messages.yml}.
 *
 * <p>ADR-020 contract recap (the part this test validates):
 * <ul>
 *   <li>The English {@code messages.yml} is the authoritative key set; every
 *       {@link MessagesKeys} enum entry maps to exactly one entry there.</li>
 *   <li>Each locale ships a {@code messages.lang.yml} that maps the internal
 *       enum key (left) to the user-visible key name used inside that locale's
 *       {@code messages.yml} (right). The locale's {@code messages.yml}
 *       therefore uses <i>translated key names</i>, not the English ones.</li>
 *   <li>If the locale's {@code messages.lang.yml} omits an entry, the baseline
 *       {@code lang/messages.lang.yml} is consulted; if both omit it, the enum
 *       name itself is the effective key (identity fallback).</li>
 * </ul>
 *
 * <p>Equivalent regression bait this test catches:
 * <ol>
 *   <li>The locale ships fewer {@code messages.yml} entries than the baseline
 *       (i.e. a key is missing from the translation).</li>
 *   <li>A locale key cannot be resolved back to a {@link MessagesKeys} enum via
 *       the effective key-name map (typo or stale entry).</li>
 *   <li>A typed key (list / int / bool / placeholder-bearing string) is shipped
 *       with the wrong YAML type after translation.</li>
 *   <li>YAML 1.1's "Norway problem" — values like {@code no}/{@code off}
 *       silently parsing as booleans because they weren't quoted.</li>
 * </ol>
 *
 * <p>Spanish is used as the canonical example locale. Adding another locale in
 * the future means parameterising {@link #SPANISH_PATH} / {@link #SPANISH_LANG_MAP_PATH},
 * not duplicating the assertion logic.
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 — Spanish locale content parity with English baseline")
public class ReqRtpF013SpanishLocaleContentTest {

    /** Module-relative paths; Gradle runs tests with cwd = the module directory. */
    private static final Path BASELINE_PATH =
            Paths.get("src", "main", "resources", "messages.yml");
    private static final Path BASELINE_LANG_MAP_PATH =
            Paths.get("src", "main", "resources", "lang", "messages.lang.yml");
    private static final Path SPANISH_PATH =
            Paths.get("src", "main", "resources", "lang", "es", "messages.yml");
    private static final Path SPANISH_LANG_MAP_PATH =
            Paths.get("src", "main", "resources", "lang", "es", "messages.lang.yml");

    /** Bracket placeholders like {@code [P0]}, {@code [arg]}, {@code [scan_regions]}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Za-z0-9_]+]");

    /** Keys whose YAML value is intentionally not a scalar string in the baseline. */
    private static final Set<MessagesKeys> LIST_KEYS = EnumSet.of(
            MessagesKeys.placeholders,
            MessagesKeys.worldInfo,
            MessagesKeys.regionInfo);
    private static final Set<MessagesKeys> INT_KEYS = EnumSet.of(
            MessagesKeys.fadeIn,
            MessagesKeys.stay,
            MessagesKeys.fadeOut);
    private static final Set<MessagesKeys> BOOLEAN_KEYS = EnumSet.of(
            MessagesKeys.showDevTag);

    private static Map<String, Object> load(Path path) throws IOException {
        assertTrue(Files.exists(path),
                "Expected resource file to exist on disk: " + path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            assertTrue(loaded instanceof Map,
                    "Root of " + path + " must be a YAML mapping; got "
                            + (loaded == null ? "null" : loaded.getClass()));
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) loaded;
            return cast;
        }
    }

    /**
     * Loads a {@code messages.lang.yml} as a {@code String -> String} map.
     * Non-string values (defensive guard) are coerced via {@code toString()}.
     */
    private static Map<String, String> loadLangMap(Path path) throws IOException {
        Map<String, Object> raw = load(path);
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            map.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return map;
    }

    /**
     * ADR-020 effective key-name map resolution: locale {@code messages.lang.yml}
     * wins, baseline {@code messages.lang.yml} fills gaps, and any enum entry
     * missing from both falls back to the enum name itself (identity).
     */
    private static Map<MessagesKeys, String> effectiveKeyMap(
            Map<String, String> localeMap, Map<String, String> baselineMap) {
        Map<MessagesKeys, String> resolved = new HashMap<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            String name = key.name();
            String mapped = localeMap.get(name);
            if (mapped == null) mapped = baselineMap.get(name);
            if (mapped == null) mapped = name;
            resolved.put(key, mapped);
        }
        return resolved;
    }

    @Test
    @DisplayName("lang/es/messages.yml has no fewer entries than the English baseline (full key parity, ADR-020)")
    void spanishLocaleHasNoMissingEntries() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);
        Map<String, String> esLangMap = loadLangMap(SPANISH_LANG_MAP_PATH);
        Map<String, String> enLangMap = loadLangMap(BASELINE_LANG_MAP_PATH);
        Map<MessagesKeys, String> resolved = effectiveKeyMap(esLangMap, enLangMap);

        // For every key the English baseline ships, the Spanish locale must
        // ship it too — under whatever name messages.lang.yml declares for it.
        // (Per the issue update: locale files must NOT have fewer entries
        // than the default config; partial overlays are not acceptable here.)
        List<String> missing = new ArrayList<>();
        for (String enKey : en.keySet()) {
            // Locate the MessagesKeys enum for this baseline key, if any.
            MessagesKeys enumKey = null;
            for (MessagesKeys k : MessagesKeys.values()) {
                if (k.name().equals(enKey)) { enumKey = k; break; }
            }
            String esKey = (enumKey != null) ? resolved.get(enumKey) : enKey;
            if (!es.containsKey(esKey)) {
                missing.add(enKey + " (expected as '" + esKey + "' in lang/es/messages.yml)");
            }
        }
        assertTrue(missing.isEmpty(),
                "Spanish locale is missing " + missing.size() + " entries from "
                        + "the English baseline. Locale messages.yml must align with "
                        + "messages.lang.yml and ship at least every baseline entry: "
                        + missing);
    }

    @Test
    @DisplayName("every key in lang/es/messages.yml resolves back to a MessagesKeys via messages.lang.yml")
    void spanishLocaleHasNoUnknownKeys() throws IOException {
        Map<String, Object> es = load(SPANISH_PATH);
        Map<String, String> esLangMap = loadLangMap(SPANISH_LANG_MAP_PATH);
        Map<String, String> enLangMap = loadLangMap(BASELINE_LANG_MAP_PATH);
        Map<MessagesKeys, String> resolved = effectiveKeyMap(esLangMap, enLangMap);

        // Build the inverse of the effective map: translatedName -> enumKey.
        Map<String, MessagesKeys> inverse = new HashMap<>();
        for (Map.Entry<MessagesKeys, String> e : resolved.entrySet()) {
            inverse.put(e.getValue(), e.getKey());
        }

        List<String> unknown = new ArrayList<>();
        for (String key : es.keySet()) {
            if (!inverse.containsKey(key)) {
                unknown.add(key);
            }
        }
        assertTrue(unknown.isEmpty(),
                "Spanish locale defines keys that no MessagesKeys entry resolves to "
                        + "via lang/es/messages.lang.yml or the baseline fallback "
                        + "(likely typo or stale entry): " + unknown);
    }

    @Test
    @DisplayName("every MessagesKeys enum entry is resolvable through the shipped baseline")
    void everyEnumEntryIsResolvable() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);

        List<String> unresolved = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            // The English baseline is the authoritative key set: every enum
            // entry must exist there. Locales translate names via messages.lang.yml.
            if (!en.containsKey(key.name())) {
                unresolved.add(key.name());
            }
        }
        assertTrue(unresolved.isEmpty(),
                "MessagesKeys entries that the English baseline messages.yml does not "
                        + "define: " + unresolved
                        + ". Add the key to messages.yml or remove the unused enum entry.");
    }

    @Test
    @DisplayName("Spanish values parse to the same YAML type as the English baseline")
    void spanishValueTypesMatchBaseline() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);
        Map<MessagesKeys, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        List<String> mismatches = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            Object enVal = en.get(key.name());
            Object esVal = es.get(resolved.get(key));
            if (enVal == null || esVal == null) continue; // completeness test owns this
            Class<?> enType = baseYamlType(enVal);
            Class<?> esType = baseYamlType(esVal);
            if (!enType.equals(esType)) {
                mismatches.add(key.name() + ": en=" + enType.getSimpleName()
                        + " es=" + esType.getSimpleName());
            }
        }
        assertTrue(mismatches.isEmpty(),
                "YAML type mismatch between English baseline and Spanish locale: "
                        + mismatches);
    }

    @Test
    @DisplayName("every [placeholder] token in a baseline string survives translation")
    void spanishPlaceholdersArePreserved() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);
        Map<MessagesKeys, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        List<String> lost = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            Object enVal = en.get(key.name());
            Object esVal = es.get(resolved.get(key));
            if (enVal == null || esVal == null) continue;

            String enJoined = flattenForScan(enVal);
            String esJoined = flattenForScan(esVal);

            Matcher m = PLACEHOLDER.matcher(enJoined);
            while (m.find()) {
                String token = m.group();
                if (!esJoined.contains(token)) {
                    lost.add(key.name() + " -> " + token);
                }
            }
        }
        assertTrue(lost.isEmpty(),
                "Spanish translation dropped placeholder tokens that the message "
                        + "pipeline substitutes at runtime: " + lost);
    }

    @Test
    @DisplayName("no Spanish value silently parsed as Boolean where baseline was a String (YAML 1.1 Norway-problem guard)")
    void spanishValuesAvoidNorwayProblem() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);
        Map<MessagesKeys, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        List<String> offenders = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            Object enVal = en.get(key.name());
            Object esVal = es.get(resolved.get(key));
            if (enVal == null || esVal == null) continue;
            // Only strings are at risk — INT_KEYS / BOOLEAN_KEYS / LIST_KEYS are handled.
            if (LIST_KEYS.contains(key) || INT_KEYS.contains(key) || BOOLEAN_KEYS.contains(key)) {
                continue;
            }
            if (enVal instanceof String && !(esVal instanceof String)) {
                offenders.add(key.name() + " (" + esVal.getClass().getSimpleName()
                        + " = " + esVal + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
                "Spanish value was coerced to a non-String type by the YAML parser "
                        + "(quote the value, e.g. \"no\" instead of no): " + offenders);
    }

    @Test
    @DisplayName("known-typed keys keep their expected YAML types in both files")
    void declaredTypedKeysAreConsistent() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);
        Map<MessagesKeys, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        for (MessagesKeys key : LIST_KEYS) {
            assertTrue(en.get(key.name()) instanceof List,
                    "Baseline key " + key + " must be a YAML list");
            assertTrue(es.get(resolved.get(key)) instanceof List,
                    "Spanish key " + key + " (as '" + resolved.get(key)
                            + "') must be a YAML list");
        }
        for (MessagesKeys key : INT_KEYS) {
            assertTrue(en.get(key.name()) instanceof Number,
                    "Baseline key " + key + " must be numeric");
            assertTrue(es.get(resolved.get(key)) instanceof Number,
                    "Spanish key " + key + " (as '" + resolved.get(key)
                            + "') must be numeric");
        }
        for (MessagesKeys key : BOOLEAN_KEYS) {
            assertTrue(en.get(key.name()) instanceof Boolean,
                    "Baseline key " + key + " must be boolean");
            assertTrue(es.get(resolved.get(key)) instanceof Boolean,
                    "Spanish key " + key + " (as '" + resolved.get(key)
                            + "') must be boolean");
        }
    }

    // --- helpers ---------------------------------------------------------------

    /** Maps the subset of YAML scalar/aggregate shapes we care about to a canonical type. */
    private static Class<?> baseYamlType(Object v) {
        if (v instanceof Boolean) return Boolean.class;
        if (v instanceof Number) return Number.class;
        if (v instanceof List) return List.class;
        if (v instanceof Map) return Map.class;
        return String.class;
    }

    /** Flattens a scalar, list, or nested structure into a single scannable string. */
    private static String flattenForScan(Object v) {
        if (v instanceof List<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object e : (List<?>) v) {
                sb.append(flattenForScan(e)).append('\n');
            }
            return sb.toString();
        }
        if (v instanceof Map<?, ?>) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sb.append(flattenForScan(e.getValue())).append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }
}
