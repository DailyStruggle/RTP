package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 ADR-064 directive config PBIT")
class DirectiveConfigCheckTest {
    private static final String PATH = "advanced/sample.yml";
    private static final String BUNDLED = String.join("\n",
            "# Delay before teleport.",
            "# @type: integer",
            "# @range: [0, null]",
            "# @unit: seconds",
            "teleportDelay: 2",
            "",
            "# Storage backend.",
            "# @type: enum",
            "# @options: [\"yaml\", \"sqlite\"]",
            "backend: \"sqlite\"",
            "",
            "# Feature switch.",
            "# @type: boolean",
            "enabled: true",
            "",
            "# Fraction of something.",
            "# @type: number",
            "# @range: [0.0, 1.0]",
            "ratio: 0.5",
            "",
            "# Whole count.",
            "# @type: integer",
            "count: 3",
            "",
            "# Accepted names.",
            "# @type: list<enum>",
            "# @options: [A, B]",
            "names:",
            "  - A",
            "",
            "# Shape block.",
            "shape:",
            "  # Shape name.",
            "  # @type: enum",
            "  # @options: [CIRCLE, SQUARE]",
            "  name: CIRCLE",
            "",
            "version: 1",
            "");

    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    private static ConfigEnvironment bundled(Map<String, String> resources) {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String text = resources.get(name);
                return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            }
        };
        return new ConfigEnvironment(null, null, loader);
    }

    private static List<BitFinding> run(String disk) {
        return run(Map.of(PATH, disk), BUNDLED);
    }

    private static List<BitFinding> run(Map<String, String> disk, String bundled) {
        return new DirectiveConfigCheck("sample", PATH).check(ConfigSnapshot.of(disk), bundled(Map.of(PATH, bundled)));
    }

    private static List<String> codes(List<BitFinding> findings) {
        return findings.stream().map(BitFinding::code).toList();
    }

    private static BitFinding only(List<BitFinding> findings, String code) {
        List<BitFinding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), () -> code + " expected once in " + findings);
        return matching.get(0);
    }

    @Test
    @DisplayName("ADR-121 the bundled default itself produces no findings")
    void bundledCopyIsClean() {
        assertEquals(List.of(), run(BUNDLED));
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_TYPE_MISMATCH fires for boolean, number, integer and list mismatches")
    void typeMismatch() {
        List<BitFinding> findings = run("enabled: maybe\nratio: abc\ncount: 2.5\nbackend: [yaml]\n");
        List<String> paths = findings.stream().filter(f -> f.code().equals("RTP_DIRECTIVE_TYPE_MISMATCH"))
                .map(BitFinding::path).toList();
        assertEquals(List.of("backend", "enabled", "ratio", "count"), paths, findings::toString);
        assertEquals(PATH, findings.get(0).source());
    }

    @Test
    @DisplayName("ADR-121 lenient: numeric strings, unit-bearing strings and case-insensitive booleans pass")
    void lenientValues() {
        assertEquals(List.of(), run("enabled: TRUE\nratio: \"0.25\"\nteleportDelay: \"5s\"\ncount: 4.0\n"));
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_OPTION_UNKNOWN fires with a suggestion; case is ignored")
    void optionUnknown() {
        BitFinding finding = only(run("backend: sqlte\n"), "RTP_DIRECTIVE_OPTION_UNKNOWN");
        assertEquals("backend", finding.path());
        assertTrue(finding.suggestion().contains("sqlite"), finding.suggestion());
        assertEquals(List.of(), run("backend: SQLITE\n"));
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_OPTION_UNKNOWN checks list<enum> elements and nested keys")
    void optionUnknownInListsAndNestedKeys() {
        List<BitFinding> findings = run("names: [A, C]\nshape:\n  name: TRIANGLE\n");
        List<String> paths = findings.stream().filter(f -> f.code().equals("RTP_DIRECTIVE_OPTION_UNKNOWN"))
                .map(BitFinding::path).toList();
        assertEquals(List.of("names[1]", "shape.name"), paths, findings::toString);
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_OUT_OF_RANGE fires below min and above max; bounds are inclusive")
    void outOfRange() {
        List<BitFinding> findings = run("teleportDelay: -1\nratio: 1.5\n");
        List<String> paths = findings.stream().filter(f -> f.code().equals("RTP_DIRECTIVE_OUT_OF_RANGE"))
                .map(BitFinding::path).toList();
        assertEquals(List.of("teleportDelay", "ratio"), paths, findings::toString);
        assertEquals(List.of(), run("teleportDelay: 0\nratio: 1.0\n"));
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_UNKNOWN_KEY fires for a top-level typo with a suggestion")
    void unknownKey() {
        BitFinding finding = only(run("teleportDelai: 3\n"), "RTP_DIRECTIVE_UNKNOWN_KEY");
        assertEquals("teleportDelai", finding.path());
        assertTrue(finding.suggestion().contains("teleportDelay"), finding.suggestion());
    }

    @Test
    @DisplayName("ADR-121 localized on-disk keys map to canonical through the ADR-076 rename map")
    void localizedKeys() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(PATH, "retraso: -1\nforma:\n  name: TRIANGLE\n");
        disk.put("advanced/.sample.lang.yml", "teleportDelay: \"retraso\"\nshape: \"forma\"\n");
        List<BitFinding> findings = run(disk, BUNDLED);
        assertEquals(List.of("RTP_DIRECTIVE_OUT_OF_RANGE", "RTP_DIRECTIVE_OPTION_UNKNOWN"), codes(findings),
                findings::toString);
        assertEquals("retraso", findings.get(0).path());
        assertEquals("forma.name", findings.get(1).path());
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_DEFAULT_INVALID fires when the bundled value or @default breaks its directives")
    void defaultInvalid() {
        String bundled = "# Limit.\n# @type: integer\n# @range: [0, 10]\n# @default: 20\nlimit: 11\n";
        List<BitFinding> findings = run(Map.of(PATH, "limit: 5\n"), bundled);
        assertEquals(List.of("RTP_DIRECTIVE_DEFAULT_INVALID", "RTP_DIRECTIVE_DEFAULT_INVALID"), codes(findings),
                findings::toString);
        assertTrue(findings.stream().allMatch(f -> f.source().equals("bundled:" + PATH)));
        assertTrue(findings.stream().allMatch(f -> f.severity() == Level.ERROR));
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_MALFORMED fires for an unreadable @range in the bundled default")
    void malformedRange() {
        String bundled = "# Limit.\n# @type: integer\n# @range: [low, high]\nlimit: 1\n";
        BitFinding finding = only(run(Map.of(PATH, "limit: 1\n"), bundled), "RTP_DIRECTIVE_MALFORMED");
        assertEquals("limit", finding.path());
    }

    @Test
    @DisplayName("ADR-121 RTP_DIRECTIVE_MALFORMED fires when the bundled default does not parse")
    void malformedBundledYaml() {
        only(run(Map.of(PATH, "limit: 1\n"), "limit: [unclosed\n  - : :\n\t"), "RTP_DIRECTIVE_MALFORMED");
    }

    @Test
    @DisplayName("ADR-121 a missing on-disk file or bundled resource yields no findings")
    void missingFiles() {
        assertEquals(List.of(), new DirectiveConfigCheck("sample", PATH)
                .check(ConfigSnapshot.of(Map.of()), bundled(Map.of(PATH, BUNDLED))));
        assertEquals(List.of(), new DirectiveConfigCheck("sample", PATH)
                .check(ConfigSnapshot.of(Map.of(PATH, "ratio: 9\n")), bundled(Map.of())));
    }

    @Test
    @DisplayName("ADR-121 a bound check reads directives from its own loader, not the environment's")
    void boundUsesOwnLoader() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of(PATH, "ratio: 9\n"));
        ConfigEnvironment empty = bundled(Map.of());
        assertEquals(List.of(), new DirectiveConfigCheck("sample", PATH).check(snapshot, empty));
        ClassLoader own = bundled(Map.of(PATH, BUNDLED)).resources();
        DirectiveConfigCheck bound = DirectiveConfigCheck.bound("sample", PATH, own);
        assertEquals("sample", bound.id());
        assertEquals(PATH, bound.path());
        assertFalse(bound.check(snapshot, empty).isEmpty());
        assertFalse(bound.check(snapshot, null).isEmpty());
        assertThrows(NullPointerException.class, () -> DirectiveConfigCheck.bound("sample", PATH, null));
    }

    @Test
    @DisplayName("ADR-121 operator-value codes are staged to WARNING; shipping-bug codes stay ERROR")
    void stagedSeverity() {
        BitSeverityPolicy.resetDefaults();
        String bundled = "# Limit.\n# @type: integer\n# @range: [0, 10]\n# @default: 20\nlimit: 1\n";
        BitReport report = ConfigPbit.run(new DirectiveConfigCheck("sample", PATH),
                ConfigSnapshot.of(Map.of(PATH, "limit: 99\n")), bundled(Map.of(PATH, bundled)));
        Map<String, Level> severities = report.findings().stream()
                .collect(Collectors.toMap(BitFinding::code, BitFinding::severity));
        assertEquals(Level.WARNING, severities.get("RTP_DIRECTIVE_OUT_OF_RANGE"));
        assertEquals(Level.ERROR, severities.get("RTP_DIRECTIVE_DEFAULT_INVALID"));
    }

    @Test
    @DisplayName("ADR-121 directive owner check ids are unique, registered and avoid other owners' ids")
    void ownerIds() {
        Set<String> ids = new HashSet<>();
        for (ConfigOwnerCheck owner : DirectiveConfigCheck.owners()) assertTrue(ids.add(owner.id()), owner.id());
        for (String taken : List.of("yaml", "references", "regions", "worlds", "messages", "network", "effects")) {
            assertFalse(ids.contains(taken), taken);
        }
        Set<String> registered = ConfigPbit.checks().stream().map(ConfigOwnerCheck::id).collect(Collectors.toSet());
        assertTrue(registered.containsAll(ids), () -> "registered: " + registered);
    }

    @Test
    @DisplayName("ADR-121 shipped defaults produce no directive findings against their own bundled resources")
    void shippedDefaultsAreClean() throws IOException {
        Path root = repositoryRoot();
        Path core = root.resolve("rtp-plugin/src/main/resources");
        // Addon-owned files, bound by each addon at enable with its own class loader.
        Map<String, String[]> addons = new LinkedHashMap<>();
        addons.put("integrations", new String[] {"addons/LeafRTPClaimAddon/src/main/resources",
                "addons/integrations.yml"});
        addons.put("countdown", new String[] {"addons/LeafRTPCountdownAddon/src/main/resources",
                "addons/countdown.yml"});
        addons.put("guimenu", new String[] {"addons/LeafRTPGuiAddon/rtp-gui-common/src/main/resources",
                "addons/guimenu.yml"});
        Map<String, String> texts = new LinkedHashMap<>();
        for (String file : DirectiveConfigCheck.OWNER_FILES.values()) readOwner(core, file, texts);
        for (String[] addon : addons.values()) readOwner(root.resolve(addon[0]), addon[1], texts);
        assertEquals(DirectiveConfigCheck.OWNER_FILES.size() + addons.size(),
                texts.keySet().stream().filter(k -> !k.endsWith(".lang.yml")).count(),
                () -> "every owner file must be found: " + texts.keySet());
        assertTrue(addons.keySet().stream().noneMatch(DirectiveConfigCheck.OWNER_FILES::containsKey),
                "addon-owned files must not be core owners");
        List<URLClassLoader> addonLoaders = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {core.toUri().toURL()}, null)) {
            ConfigEnvironment environment = new ConfigEnvironment(null, null, loader);
            ConfigSnapshot snapshot = ConfigSnapshot.of(texts);
            List<ConfigOwnerCheck> owners = new ArrayList<>(DirectiveConfigCheck.owners());
            for (Map.Entry<String, String[]> addon : addons.entrySet()) {
                URLClassLoader own = new URLClassLoader(
                        new URL[] {root.resolve(addon.getValue()[0]).toUri().toURL()}, null);
                addonLoaders.add(own);
                owners.add(DirectiveConfigCheck.bound(addon.getKey(), addon.getValue()[1], own));
                // Without its own loader the addon file has no bundled directives on the core loader.
                assertEquals(List.of(), new DirectiveConfigCheck(addon.getKey(), addon.getValue()[1]).check(
                        ConfigSnapshot.of(Map.of(addon.getValue()[1], "notAKey: 1\n")), environment));
                assertEquals(List.of("RTP_DIRECTIVE_UNKNOWN_KEY"), codes(owners.get(owners.size() - 1).check(
                        ConfigSnapshot.of(Map.of(addon.getValue()[1], "notAKey: 1\n")), environment)));
            }
            List<BitFinding> findings = new ArrayList<>();
            for (ConfigOwnerCheck owner : owners) {
                findings.addAll(owner.check(snapshot, environment));
            }
            assertEquals(List.of(), findings.stream().filter(f -> f.severity() == Level.ERROR).toList());
            assertEquals(List.of(), findings);

            // Non-vacuous: the real bundled performance.yml directives do reject a bad value.
            List<BitFinding> bad = new DirectiveConfigCheck("performance", "advanced/performance.yml").check(
                    ConfigSnapshot.of(Map.of("advanced/performance.yml", "pregeneratedPreference: 2.0\n")),
                    environment);
            assertEquals(List.of("RTP_DIRECTIVE_OUT_OF_RANGE"), codes(bad), bad::toString);
        } finally {
            for (URLClassLoader own : addonLoaders) own.close();
        }
    }

    /** Adds {@code file} and its ADR-076 {@code .x.lang.yml} rename map from {@code resources}. */
    private static void readOwner(Path resources, String file, Map<String, String> texts) throws IOException {
        assertTrue(Files.isDirectory(resources), resources::toString);
        Path candidate = resources.resolve(file);
        if (Files.isRegularFile(candidate)) texts.put(file, Files.readString(candidate));
        int slash = file.lastIndexOf('/');
        String langMap = (slash < 0 ? "" : file.substring(0, slash + 1)) + "."
                + file.substring(slash + 1, file.length() - 4) + ".lang.yml";
        Path lang = resources.resolve(langMap);
        if (Files.isRegularFile(lang)) texts.put(langMap, Files.readString(lang));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
