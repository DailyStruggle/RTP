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
 * <p>These tests complement
 * {@code ReqRtpF013LocaleOverlayTest} in {@code rtp-core}, which covers the
 * overlay <i>mechanism</i>. Here we validate the <i>shipped content</i> so that
 * a translation PR cannot silently:
 *
 * <ul>
 *   <li>drop a {@link MessagesKeys} entry (completeness),</li>
 *   <li>change the YAML type of a value (e.g. turn a string into a boolean or int),</li>
 *   <li>lose a {@code [placeholder]} token that the message pipeline substitutes,</li>
 *   <li>fall into YAML 1.1's "Norway problem" (values like {@code no}/{@code off}
 *       silently parsing as booleans).</li>
 * </ul>
 *
 * <p>Spanish is used as the canonical example locale. Adding a second locale in
 * the future will mean duplicating the data-driven assertions, not the logic.
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 — Spanish locale content parity with English baseline")
public class ReqRtpF013SpanishLocaleContentTest {

    /** Module-relative paths; Gradle runs tests with cwd = the module directory. */
    private static final Path BASELINE_PATH =
            Paths.get("src", "main", "resources", "messages.yml");
    private static final Path SPANISH_PATH =
            Paths.get("src", "main", "resources", "lang", "es", "messages.yml");

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

    @Test
    @DisplayName("every key in the English baseline is present in lang/es/messages.yml")
    void spanishLocaleMatchesBaselineKeyset() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);

        List<String> missing = new ArrayList<>();
        for (String key : en.keySet()) {
            if (!es.containsKey(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(),
                "Spanish locale is missing " + missing.size()
                        + " keys from the English baseline (partial translations "
                        + "are permitted at runtime via ADR-020 fallback, but the "
                        + "shipped default pack must be complete): " + missing);
    }

    @Test
    @DisplayName("every MessagesKeys enum entry is resolvable through the shipped baseline + Spanish pack")
    void everyEnumEntryIsResolvable() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);

        List<String> unresolved = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            // ADR-020 fallback chain: locale -> baseline -> (missing).
            if (!en.containsKey(key.name()) && !es.containsKey(key.name())) {
                unresolved.add(key.name());
            }
        }
        assertTrue(unresolved.isEmpty(),
                "MessagesKeys entries that no shipped YAML file defines (neither "
                        + "messages.yml nor lang/es/messages.yml): " + unresolved
                        + ". Add the key to messages.yml (and translate to es) or "
                        + "remove the unused enum entry.");
    }

    @Test
    @DisplayName("Spanish values parse to the same YAML type as the English baseline")
    void spanishValueTypesMatchBaseline() throws IOException {
        Map<String, Object> en = load(BASELINE_PATH);
        Map<String, Object> es = load(SPANISH_PATH);

        List<String> mismatches = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            Object enVal = en.get(key.name());
            Object esVal = es.get(key.name());
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

        List<String> lost = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            Object enVal = en.get(key.name());
            Object esVal = es.get(key.name());
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

        List<String> offenders = new ArrayList<>();
        for (MessagesKeys key : MessagesKeys.values()) {
            Object enVal = en.get(key.name());
            Object esVal = es.get(key.name());
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

        for (MessagesKeys key : LIST_KEYS) {
            assertTrue(en.get(key.name()) instanceof List,
                    "Baseline key " + key + " must be a YAML list");
            assertTrue(es.get(key.name()) instanceof List,
                    "Spanish key " + key + " must be a YAML list");
        }
        for (MessagesKeys key : INT_KEYS) {
            assertTrue(en.get(key.name()) instanceof Number,
                    "Baseline key " + key + " must be numeric");
            assertTrue(es.get(key.name()) instanceof Number,
                    "Spanish key " + key + " must be numeric");
        }
        for (MessagesKeys key : BOOLEAN_KEYS) {
            assertTrue(en.get(key.name()) instanceof Boolean,
                    "Baseline key " + key + " must be boolean");
            assertTrue(es.get(key.name()) instanceof Boolean,
                    "Spanish key " + key + " must be boolean");
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
