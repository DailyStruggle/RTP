package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 config PBIT semantic constraints owner (region and action loaders)")
class ConfigConstraintCheckTest {
    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    private static List<BitFinding> check(String path, String text) {
        return ConfigConstraintCheck.INSTANCE.check(ConfigSnapshot.of(Map.of(path, text)), ConfigEnvironment.HEADLESS);
    }

    @Test
    @DisplayName("ADR-121 RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER fires for a region shape with radius <= centerRadius")
    void regionShapeRadius() {
        List<BitFinding> findings = check("definitions/regions/arena.yml",
                "shape:\n  name: CIRCLE\n  radius: 1000b\n  centerRadius: 64\n");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER", findings.get(0).code());
        assertEquals("shape.radius", findings.get(0).path());
        assertEquals(Level.ERROR, findings.get(0).severity());

        assertEquals(1, check("definitions/regions/arena.yml", "shape:\n  radius: 64\n  centerRadius: 64\n").size());
        assertTrue(check("definitions/regions/arena.yml",
                "shape:\n  radius: 4096b\n  centerRadius: 64\n").isEmpty());
        assertTrue(check("definitions/regions/arena.yml", "shape: \"@config\"\n").isEmpty());
        assertTrue(check("definitions/regions/arena.yml", "shape:\n  radius: 10\n").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER covers config.yml defaults.shape and is not staged")
    void configDefaultShapeRadius() {
        String text = "defaults:\n  shape:\n    radius: 32\n    centerRadius: 64\n";
        List<BitFinding> findings = check("config.yml", text);
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER", findings.get(0).code());
        assertEquals("defaults.shape.radius", findings.get(0).path());

        var report = ConfigPbit.run(ConfigConstraintCheck.INSTANCE, ConfigSnapshot.of(Map.of("config.yml", text)),
                ConfigEnvironment.HEADLESS);
        assertEquals(Level.ERROR, report.findings().get(0).severity());
        assertTrue(check("config.yml", "defaults:\n  shape:\n    radius: 4096b\n    centerRadius: 64\n").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_ACTION_RADIUS_NOT_ABOVE_CENTER fires for placement.shape and confinement radii")
    void actionRadius() {
        List<BitFinding> findings = check("definitions/actions/arena.yml", """
                placement:
                  shape:
                    radius: 2c
                    centerRadius: 32
                confinement:
                  radius: 10
                  centerRadius: 20
                  shape:
                    radius: 5
                    centerRadius: 5
                """);
        assertEquals(3, findings.size(), findings.toString());
        assertTrue(findings.stream().allMatch(finding -> finding.code().equals("RTP_ACTION_RADIUS_NOT_ABOVE_CENTER")));
        assertEquals(Set.of("placement.shape.radius", "confinement.radius", "confinement.shape.radius"),
                Set.copyOf(findings.stream().map(BitFinding::path).toList()));

        assertTrue(check("definitions/actions/arena.yml",
                "placement:\n  shape:\n    radius: 4c\n    centerRadius: 0\n").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_ACTION_SHRINK_ABOVE_INITIAL fires for confinement shrinkTo > initialSize")
    void actionShrink() {
        List<BitFinding> findings = check("definitions/actions/arena.yml",
                "confinement:\n  initialSize: 32\n  shrinkTo: 128\n");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("RTP_ACTION_SHRINK_ABOVE_INITIAL", findings.get(0).code());
        assertEquals("confinement.shrinkTo", findings.get(0).path());

        assertTrue(check("definitions/actions/arena.yml", "confinement:\n  initialSize: 128\n  shrinkTo: 128\n").isEmpty());
        assertTrue(check("definitions/actions/arena.yml", "confinement:\n  initialSize: 128\n  shrinkTo: 32\n").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 shipped defaults (rtp-plugin resources and action addon definitions) raise no ERROR")
    void shippedDefaultsRaiseNoError() throws IOException {
        Path root = repositoryRoot();
        Path resources = root.resolve("rtp-plugin/src/main/resources");
        assertTrue(Files.isDirectory(resources), resources.toString());
        Map<String, String> actions = new TreeMap<>();
        Path actionDir = root.resolve("addons/LeafRTPActionAddon/src/main/resources/definitions/actions");
        try (Stream<Path> files = Files.list(actionDir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                actions.put("definitions/actions/" + file.getFileName(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        assertTrue(actions.size() > 1, "action definitions present");
        ConfigSnapshot snapshot = ConfigSnapshot.read(resources).withOverlay(actions);
        assertTrue(snapshot.document("config.yml").isPresent());
        assertTrue(snapshot.document("definitions/regions/default.yml").isPresent());

        BitSeverityPolicy.load(false, true, Map.of());
        for (ConfigEnvironment environment : List.of(ConfigEnvironment.HEADLESS,
                new ConfigEnvironment(Set.of("world"), null, null))) {
            for (ConfigOwnerCheck owner : List.of(ConfigReferenceCheck.INSTANCE, ConfigConstraintCheck.INSTANCE)) {
                List<BitFinding> findings = ConfigPbit.run(owner, snapshot, environment).findings();
                List<BitFinding> severe = findings.stream()
                        .filter(finding -> finding.severity() == Level.ERROR || finding.severity() == Level.WARNING)
                        .toList();
                assertTrue(severe.isEmpty(), owner.id() + ": " + severe);
            }
        }
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
