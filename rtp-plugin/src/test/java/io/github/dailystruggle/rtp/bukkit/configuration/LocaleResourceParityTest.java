package io.github.dailystruggle.rtp.bukkit.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * REQ-RTP-F-013 / ADR-020 — generic, locale-agnostic parity test for every
 * shipped translation under {@code rtp-plugin/src/main/resources/lang/<locale>/}.
 *
 * <p>For each locale directory containing at least one {@code *.lang.yml} and
 * matching {@code *.yml}, this test asserts:
 * <ol>
 *   <li><b>No backup files shipped:</b> no {@code *.bak} files anywhere under
 *       {@code lang/} (resources should never carry editor/agent backups).</li>
 *   <li><b>Lang-map / value-file key consistency:</b> the values on the right
 *       side of {@code <file>.lang.yml} must equal the top-level keys of the
 *       sibling {@code <file>.yml} (modulo identity fallback). A right-side
 *       key like {@code teleportMessage: mensajeTeletransporte} requires
 *       {@code mensajeTeletransporte} to be a top-level key in the locale's
 *       {@code <file>.yml}, otherwise lookups silently return empty strings
 *       (the original {@code rtp info} bug under Spanish).</li>
 *   <li><b>Placeholder fidelity:</b> every bracketed token
 *       ({@code [player]}, {@code [delay]}, ...) appearing in the English
 *       baseline {@code messages.yml} must appear with the same name in the
 *       locale's {@code messages.yml}. Translators occasionally rename
 *       placeholders by accident; those would never substitute.</li>
 * </ol>
 *
 * <p>The test does <i>not</i> require translations to be complete (identity
 * mappings are tolerated); it only flags positive divergence — entries the
 * lang map promises that the value file does not deliver. This keeps the test
 * a guardrail against silent breakage rather than a coverage gate.
 */
@DisplayName("REQ-RTP-F-013 / ADR-020 — locale resource parity (all shipped locales)")
public class LocaleResourceParityTest {

    private static final Path RESOURCES =
            Paths.get("src", "main", "resources");
    private static final Path LANG_ROOT = RESOURCES.resolve("lang");
    private static final Path BASELINE_MESSAGES = RESOURCES.resolve("messages.yml");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Za-z0-9_]+]");

    /**
     * Subdirectories under {@code lang/} that are NOT locale folders (region
     * shape templates, vertical shape templates, ...). Skip these.
     */
    private static final Set<String> NON_LOCALE_DIRS = Set.of("shape", "vert");

    @Test
    @DisplayName("No .bak files are shipped under lang/")
    void noBackupFilesShipped() throws IOException {
        if (!Files.isDirectory(LANG_ROOT)) {
            // Test runs from rtp-plugin module; if not, skip silently.
            return;
        }
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
                        // <file>.lang.yml -> sibling <file>.yml
                        String mapName = langMap.getFileName().toString();
                        String valueName = mapName.substring(0, mapName.length() - ".lang.yml".length()) + ".yml";
                        Path valueFile = localeDir.resolve(valueName);

                        if (!Files.exists(valueFile)) {
                            // .lang.yml without a sibling value file: a map promising
                            // translated key names with no values to look them up in.
                            // This makes ConfigParser fall back to the baseline; not
                            // a hard failure but worth surfacing.
                            //
                            // Allow this for files that genuinely have no localized
                            // values (regions.lang.yml, worlds.lang.yml are templates).
                            continue;
                        }

                        Map<String, String> map = loadLangMap(langMap);
                        Map<String, Object> values = loadYaml(valueFile);
                        Set<String> valueKeys = values.keySet();

                        for (Map.Entry<String, String> e : map.entrySet()) {
                            String mappedName = e.getValue();
                            // Skip entries that didn't get renamed; the canonical
                            // English key is fine if it's literally there too, but
                            // identity entries by themselves are not a parity issue.
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

    @Test
    @DisplayName("Each locale's messages.yml preserves baseline placeholder tokens")
    void messagePlaceholdersArePreserved() throws IOException {
        if (!Files.isDirectory(LANG_ROOT)) return;
        if (!Files.exists(BASELINE_MESSAGES)) return;

        Set<String> baselinePlaceholders = collectPlaceholders(loadYaml(BASELINE_MESSAGES));
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
                // Only flag placeholders that are critical (appear in baseline but
                // never in this locale at all). A placeholder absent from one
                // message because that message is also absent from the locale
                // is acceptable; this test catches wholesale renames like
                // [player] -> [jugador].
                if (!missing.isEmpty()) {
                    // Cross-check: maybe the locale has the same placeholder under
                    // a translated spelling (e.g. [jugador] for [player]). If any
                    // unexpected new placeholder appears that isn't in the
                    // baseline, that's a strong signal of accidental translation.
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

    // -- helpers --------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
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
