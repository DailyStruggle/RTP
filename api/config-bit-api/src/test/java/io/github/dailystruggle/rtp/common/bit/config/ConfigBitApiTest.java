package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the standalone {@code config-bit-api} library (ADR-064, ADR-073, ADR-121).
 */
@DisplayName("config-bit-api: Directives, Constraints, References, and Message Key Parity")
class ConfigBitApiTest {

    @Test
    @DisplayName("DirectiveSchemaParser parses @type, @options, @range, @pattern, @unit, @default, and @source")
    void directiveSchemaParserExtractsAllTags() {
        String yamlText = """
                # @type: string
                # @options: ["yaml", "sqlite"]
                # @range: [0, 100]
                # @pattern: "^[a-z]+$"
                # @unit: blocks
                # @default: "yaml"
                # @source: registry
                storage: "yaml"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        DirectiveSchemaParser parsed = DirectiveSchemaParser.parse(yaml.getComment("storage"));
        assertEquals("string", parsed.type());
        assertEquals(List.of("yaml", "sqlite"), parsed.options());
        assertEquals(0.0, parsed.rangeMin());
        assertEquals(100.0, parsed.rangeMax());
        assertFalse(parsed.rangeMalformed());
        assertEquals("^[a-z]+$", parsed.pattern());
        assertNotNull(parsed.compiledPattern());
        assertFalse(parsed.patternMalformed());
        assertEquals("blocks", parsed.unit());
        assertEquals("yaml", parsed.defaultValue());
        assertEquals("registry", parsed.source());
        assertTrue(parsed.hasRange());
        assertTrue(parsed.hasPattern());
        assertTrue(parsed.hasChecks());

        Map<String, DirectiveSchemaParser> all = DirectiveSchemaParser.parseAll(yaml);
        assertEquals(1, all.size());
        assertTrue(all.containsKey("storage"));
    }

    @Test
    @DisplayName("DirectiveSchemaParser flags malformed @range and @pattern")
    void directiveSchemaParserFlagsMalformedDirectives() {
        DirectiveSchemaParser badRange = DirectiveSchemaParser.parse("# @range: [10, 5]");
        assertTrue(badRange.rangeMalformed());
        assertTrue(badRange.hasChecks());

        DirectiveSchemaParser badPattern = DirectiveSchemaParser.parse("# @pattern: \"[unclosed\"");
        assertTrue(badPattern.patternMalformed());
        assertNull(badPattern.compiledPattern());
        assertTrue(badPattern.hasChecks());
    }

    @Test
    @DisplayName("DirectiveValidator enforces @type, @options, @range, @pattern, and unknown keys")
    void directiveValidatorChecksAllDirectives() {
        String bundled = """
                # @type: integer
                # @range: [0, 60]
                # @default: 10
                delay: 10

                # @type: string
                # @options: ["yaml", "sqlite"]
                storage: "yaml"

                # @type: string
                # @pattern: "^[a-z0-9_-]+$"
                regionName: "default_region"
                """;
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of(
                "config.yml", """
                        delay: -5
                        storage: "mysql"
                        regionName: "Invalid Space!"
                        extraKey: true
                        """));
        ConfigSnapshot.Document doc = snapshot.document("config.yml").orElseThrow();
        DirectiveValidator validator = new DirectiveValidator();
        List<BitFinding> findings = validator.check(doc, bundled);

        assertEquals(4, findings.size());
        assertTrue(findings.stream().anyMatch(f ->
                f.code().equals(DirectiveCodes.CFG_OUT_OF_RANGE.key()) && "delay".equals(f.path())));
        assertTrue(findings.stream().anyMatch(f ->
                f.code().equals(DirectiveCodes.CFG_OPTION_UNKNOWN.key()) && "storage".equals(f.path())));
        assertTrue(findings.stream().anyMatch(f ->
                f.code().equals(DirectiveCodes.CFG_PATTERN_MISMATCH.key()) && "regionName".equals(f.path())));
        assertTrue(findings.stream().anyMatch(f ->
                f.code().equals(DirectiveCodes.CFG_UNKNOWN_KEY.key()) && "extraKey".equals(f.path())));
    }

    @Test
    @DisplayName("DirectiveValidator detects invalid bundled default and malformed directives")
    void directiveValidatorChecksBundledSelfConsistency() {
        String bundled = """
                # @type: integer
                # @range: [low, high]
                # @pattern: "[unclosed"
                # @default: "notAnInt"
                delay: "alsoNotAnInt"
                """;
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of("config.yml", "delay: 5\n"));
        ConfigSnapshot.Document doc = snapshot.document("config.yml").orElseThrow();
        List<BitFinding> findings = DirectiveValidator.validate(doc, bundled);

        assertEquals(4, findings.size());
        assertEquals(2L, findings.stream()
                .filter(f -> f.code().equals(DirectiveCodes.CFG_MALFORMED.key())).count());
        assertEquals(2L, findings.stream()
                .filter(f -> f.code().equals(DirectiveCodes.CFG_DEFAULT_INVALID.key())).count());
    }

    @Test
    @DisplayName("ConfigSnapshot reads directories, overlays candidates, and maps localized keys")
    void configSnapshotReadsAndOverlays(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("config.yml"), "delay: 10\n");
        Files.writeString(tempDir.resolve(".config.lang.yml"), "teleportDelay: delay\n");
        Files.writeString(tempDir.resolve("config.yml.bak"), "ignored: true\n");

        ConfigSnapshot disk = ConfigSnapshot.read(tempDir);
        ConfigSnapshot.Document doc = disk.document("config.yml").orElseThrow();
        assertEquals(10, doc.get("teleportDelay"));
        assertEquals("delay", doc.localizedKey("teleportDelay"));
        assertEquals("config", doc.stem());

        ConfigSnapshot candidate = disk.withOverlay(Map.of("config.yml", "delay: 25\n"));
        assertEquals(25, candidate.document("config.yml").orElseThrow().get("teleportDelay"));
        assertEquals(10, disk.document("config.yml").orElseThrow().get("teleportDelay"));
    }

    @Test
    @DisplayName("ConfigConstraint and GenericConstraintCheck enforce distance ordering across units")
    void configConstraintEvaluatesOrdering() {
        ConfigConstraint minLessThanMax = new ConfigConstraint(
                "definitions/regions/", "", "minRadius", "maxRadius", false,
                SpatialUnit.CHUNK, DirectiveCodes.CFG_OUT_OF_RANGE);
        GenericConstraintCheck check = GenericConstraintCheck.of(minLessThanMax);

        ConfigSnapshot invalid = ConfigSnapshot.of(Map.of(
                "definitions/regions/default.yml", "minRadius: 4096b\nmaxRadius: 64\n"));
        List<BitFinding> findings = check.evaluate(invalid);
        assertEquals(1, findings.size());
        assertEquals("maxRadius", findings.get(0).path());

        ConfigSnapshot valid = ConfigSnapshot.of(Map.of(
                "definitions/regions/default.yml", "minRadius: 16\nmaxRadius: 64\n"));
        assertTrue(check.evaluate(valid).isEmpty());
    }

    @Test
    @DisplayName("ReferenceSettingCheck validates @<file> tokens and suggests known reference files")
    void referenceSettingCheckValidatesCrossFileTokens() {
        Vocabulary files = new Vocabulary("reference file", List.of("config", "economy", "safety"));
        ReferenceSettingCheck check = new ReferenceSettingCheck(files);

        assertEquals("economy", ReferenceSettingCheck.referencedFile(" @Economy "));
        assertNull(ReferenceSettingCheck.referencedFile("literal"));
        assertTrue(ReferenceSettingCheck.isReference("@safety"));

        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of(
                "config.yml", "defaults:\n  price: \"@economi\"\n",
                "economy.yml", "refund: true\n",
                "definitions/regions/default.yml", "cost: \"@economy\"\n"));

        List<BitFinding> findings = check.evaluate(
                snapshot,
                List.of(
                        snapshot.document("config.yml").orElseThrow(),
                        snapshot.document("definitions/regions/default.yml").orElseThrow()),
                Set.of("defaults"));

        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f ->
                f.code().equals(ReferenceSettingCheck.CFG_UNKNOWN_FILE.key())
                        && "defaults.price".equals(f.path())
                        && "@economy".equals(f.suggestion())));
        assertTrue(findings.stream().anyMatch(f ->
                f.code().equals(ReferenceSettingCheck.CFG_SETTING_MISSING.key())
                        && "cost".equals(f.path())));
    }

    @Test
    @DisplayName("MessageKeyParity and MessageParityCodes report baseline, locale, and operator gaps")
    void messageKeyParityAndCodesReportGaps() {
        MessageKeyParity.ResourceReader reader = MessageKeyParity.ResourceReader.of(Map.of(
                "advanced/messages/player.yml", "teleport: \" Go \"\nunmapped: \"x\"\n",
                "advanced/messages/.player.lang.yml", "teleport: teleport\n",
                "lang/es/advanced/messages/player.yml", "wrongTarget: \" Ir \"\n",
                "lang/es/advanced/messages/.player.lang.yml", "teleport: teletransporte\nobsolete: viejo\n"));

        List<BitFinding> bundled = MessageParityCodes.DEFAULT.bundledFindings(
                reader, List.of("advanced/messages/player"));
        assertTrue(bundled.stream().anyMatch(f ->
                f.code().equals(MessageParityCodes.CFG_BASELINE_UNMAPPED.key()) && "unmapped".equals(f.path())));
        assertTrue(bundled.stream().anyMatch(f ->
                f.code().equals(MessageParityCodes.CFG_LOCALE_KEY_MISSING.key()) && "teletransporte".equals(f.path())));
        assertTrue(bundled.stream().anyMatch(f ->
                f.code().equals(MessageParityCodes.CFG_LOCALE_STALE_ROW.key()) && "obsolete".equals(f.path())));
        assertTrue(bundled.stream().anyMatch(f ->
                f.code().equals(MessageParityCodes.CFG_LOCALE_UNRESOLVED_ROW.key()) && "teleport".equals(f.path())));

        ConfigSnapshot operator = ConfigSnapshot.of(Map.of(
                "advanced/messages/player.yml", "teleprt: \"Typo\"\n"));
        List<BitFinding> operatorFindings = MessageParityCodes.DEFAULT.operatorFindings(
                operator.document("advanced/messages/player.yml").orElseThrow(),
                "player",
                Set.of("teleport", "version"));
        assertEquals(3, operatorFindings.size());
        assertEquals(2L, operatorFindings.stream()
                .filter(f -> f.code().equals(MessageParityCodes.CFG_KEY_MISSING.key())).count());
        assertEquals(1L, operatorFindings.stream()
                .filter(f -> f.code().equals(MessageParityCodes.CFG_KEY_UNKNOWN.key())).count());
    }
}
