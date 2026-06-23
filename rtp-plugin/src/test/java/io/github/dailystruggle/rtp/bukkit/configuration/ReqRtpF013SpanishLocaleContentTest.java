package io.github.dailystruggle.rtp.bukkit.configuration;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * REQ-RTP-F-013 / ADR-020 — content-parity regression tests for the shipped
 * Spanish {@code messages.yml}. Per-locale {@code messages.lang.yml} maps enum
 * key → translated key name; baseline + identity fallback. Catches: missing
 * keys, unresolvable keys, wrong YAML type for typed keys (list/int/bool),
 * dropped placeholders, and YAML 1.1 "Norway problem" (no/off → boolean).
 * Spanish is the canonical example; new locales parameterise {@link #SPANISH_PATH}.
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 — Spanish locale content parity with English baseline")
public class ReqRtpF013SpanishLocaleContentTest {

    /** Module-relative paths; Gradle runs tests with cwd = the module directory. */
    /**
     * The English baseline messages are split, per concern, across this
     * directory (placeholders / player / network / commands / system); the
     * baseline key set is the union of these member files. See
     * {@link #loadBaseline()}.
     */
    private static final Path BASELINE_DIR =
            Paths.get("src", "main", "resources", "messages");
    private static final Path BASELINE_LANG_MAP_PATH =
            Paths.get("src", "main", "resources", "lang", "messages.lang.yml");
    private static final Path SPANISH_PATH =
            Paths.get("src", "main", "resources", "lang", "es", "messages.yml");
    private static final Path SPANISH_LANG_MAP_PATH =
            Paths.get("src", "main", "resources", "lang", "es", "messages.lang.yml");

    /** Bracket placeholders like {@code [P0]}, {@code [arg]}, {@code [scan_regions]}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Za-z0-9_]+]");

    /** Keys whose YAML value is intentionally not a scalar string in the baseline. */
    private static final Set<Enum<?>> LIST_KEYS = setOf(
            PlaceholderMessages.placeholders,
            CommandMessages.worldInfo,
            CommandMessages.regionInfo);
    private static final Set<Enum<?>> INT_KEYS = setOf(
            PlayerMessages.fadeIn,
            PlayerMessages.stay,
            PlayerMessages.fadeOut);
    private static final Set<Enum<?>> BOOLEAN_KEYS = setOf(
            SystemMessages.showDevTag);

    private static Set<Enum<?>> setOf(Enum<?>... keys) {
        return new java.util.HashSet<>(java.util.Arrays.asList(keys));
    }

    private static List<Enum<?>> allMessageKeys() {
        List<Enum<?>> all = new ArrayList<>();
        java.util.Collections.addAll(all, PlaceholderMessages.values());
        java.util.Collections.addAll(all, PlayerMessages.values());
        java.util.Collections.addAll(all, io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages.values());
        java.util.Collections.addAll(all, CommandMessages.values());
        java.util.Collections.addAll(all, SystemMessages.values());
        return all;
    }
    /** English baseline as the union of every {@code messages/*.yml} member file. */
    private static Map<String, Object> loadBaseline() throws IOException {
        assertTrue(Files.isDirectory(BASELINE_DIR),
                "Expected baseline messages directory to exist: " + BASELINE_DIR.toAbsolutePath());
        Map<String, Object> merged = new LinkedHashMap<>();
        try (java.nio.file.DirectoryStream<Path> ymls =
                     Files.newDirectoryStream(BASELINE_DIR, "*.yml")) {
            for (Path p : ymls) merged.putAll(load(p));
        }
        return merged;
    }

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
    private static Map<Enum<?>, String> effectiveKeyMap(
            Map<String, String> localeMap, Map<String, String> baselineMap) {
        Map<Enum<?>, String> resolved = new HashMap<>();
        for (Enum<?> key : allMessageKeys()) {
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
        Map<String, Object> en = loadBaseline();
        Map<String, Object> es = load(SPANISH_PATH);
        Map<String, String> esLangMap = loadLangMap(SPANISH_LANG_MAP_PATH);
        Map<String, String> enLangMap = loadLangMap(BASELINE_LANG_MAP_PATH);
        Map<Enum<?>, String> resolved = effectiveKeyMap(esLangMap, enLangMap);

        // For every key the English baseline ships, the Spanish locale must
        // ship it too — under whatever name messages.lang.yml declares for it.
        // (Per the issue update: locale files must NOT have fewer entries
        // than the default config; partial overlays are not acceptable here.)
        List<String> missing = new ArrayList<>();
        for (String enKey : en.keySet()) {
            // Locate the Enum<?> enum for this baseline key, if any.
            Enum<?> enumKey = null;
            for (Enum<?> k : allMessageKeys()) {
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
    @DisplayName("every key in lang/es/messages.yml resolves back to a Enum<?> via messages.lang.yml")
    void spanishLocaleHasNoUnknownKeys() throws IOException {
        Map<String, Object> es = load(SPANISH_PATH);
        Map<String, String> esLangMap = loadLangMap(SPANISH_LANG_MAP_PATH);
        Map<String, String> enLangMap = loadLangMap(BASELINE_LANG_MAP_PATH);
        Map<Enum<?>, String> resolved = effectiveKeyMap(esLangMap, enLangMap);

        // Build the inverse of the effective map: translatedName -> enumKey.
        Map<String, Enum<?>> inverse = new HashMap<>();
        for (Map.Entry<Enum<?>, String> e : resolved.entrySet()) {
            inverse.put(e.getValue(), e.getKey());
        }

        List<String> unknown = new ArrayList<>();
        for (String key : es.keySet()) {
            if (!inverse.containsKey(key)) {
                unknown.add(key);
            }
        }
        assertTrue(unknown.isEmpty(),
                "Spanish locale defines keys that no Enum<?> entry resolves to "
                        + "via lang/es/messages.lang.yml or the baseline fallback "
                        + "(likely typo or stale entry): " + unknown);
    }

    @Test
    @DisplayName("every Enum<?> enum entry is resolvable through the shipped baseline")
    void everyEnumEntryIsResolvable() throws IOException {
        Map<String, Object> en = loadBaseline();

        List<String> unresolved = new ArrayList<>();
        for (Enum<?> key : allMessageKeys()) {
            // The English baseline is the authoritative key set: every enum
            // entry must exist there. Locales translate names via messages.lang.yml.
            if (!en.containsKey(key.name())) {
                unresolved.add(key.name());
            }
        }
        assertTrue(unresolved.isEmpty(),
                "Enum<?> entries that the English baseline messages.yml does not "
                        + "define: " + unresolved
                        + ". Add the key to messages.yml or remove the unused enum entry.");
    }

    @Test
    @DisplayName("Spanish values parse to the same YAML type as the English baseline")
    void spanishValueTypesMatchBaseline() throws IOException {
        Map<String, Object> en = loadBaseline();
        Map<String, Object> es = load(SPANISH_PATH);
        Map<Enum<?>, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        List<String> mismatches = new ArrayList<>();
        for (Enum<?> key : allMessageKeys()) {
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
        Map<String, Object> en = loadBaseline();
        Map<String, Object> es = load(SPANISH_PATH);
        Map<Enum<?>, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        List<String> lost = new ArrayList<>();
        for (Enum<?> key : allMessageKeys()) {
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
        Map<String, Object> en = loadBaseline();
        Map<String, Object> es = load(SPANISH_PATH);
        Map<Enum<?>, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        List<String> offenders = new ArrayList<>();
        for (Enum<?> key : allMessageKeys()) {
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
        Map<String, Object> en = loadBaseline();
        Map<String, Object> es = load(SPANISH_PATH);
        Map<Enum<?>, String> resolved = effectiveKeyMap(
                loadLangMap(SPANISH_LANG_MAP_PATH), loadLangMap(BASELINE_LANG_MAP_PATH));

        for (Enum<?> key : LIST_KEYS) {
            assertTrue(en.get(key.name()) instanceof List,
                    "Baseline key " + key + " must be a YAML list");
            assertTrue(es.get(resolved.get(key)) instanceof List,
                    "Spanish key " + key + " (as '" + resolved.get(key)
                            + "') must be a YAML list");
        }
        for (Enum<?> key : INT_KEYS) {
            assertTrue(en.get(key.name()) instanceof Number,
                    "Baseline key " + key + " must be numeric");
            assertTrue(es.get(resolved.get(key)) instanceof Number,
                    "Spanish key " + key + " (as '" + resolved.get(key)
                            + "') must be numeric");
        }
        for (Enum<?> key : BOOLEAN_KEYS) {
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
