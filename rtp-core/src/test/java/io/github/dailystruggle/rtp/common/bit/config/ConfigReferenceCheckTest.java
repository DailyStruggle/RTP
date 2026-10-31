package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 config PBIT references owner (ADR-073 @<file> tokens, cross-file names)")
class ConfigReferenceCheckTest {
    private static final ConfigEnvironment WORLDS = new ConfigEnvironment(Set.of("world", "world_nether"), null, null);
    private static final String CONFIG = """
            teleportCooldown: 5
            defaults:
              cacheCap: 50
              requirePermission: false
            """;

    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    private static Map<String, String> files(String... pathsAndTexts) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.yml", CONFIG);
        files.put("economy.yml", "price: 50.0\n");
        files.put("definitions/regions/default.yml", "world: \"[0]\"\noverride: \"default\"\n");
        files.put("definitions/worlds/default.yml", "region: \"default\"\noverride: \"[0]\"\n");
        for (int i = 0; i + 1 < pathsAndTexts.length; i += 2) files.put(pathsAndTexts[i], pathsAndTexts[i + 1]);
        return files;
    }

    private static List<BitFinding> raw(ConfigEnvironment environment, String... pathsAndTexts) {
        return ConfigReferenceCheck.INSTANCE.check(ConfigSnapshot.of(files(pathsAndTexts)), environment);
    }

    private static List<BitFinding> withCode(List<BitFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    @DisplayName("ADR-121 RTP_REFERENCE_UNKNOWN_FILE fires for @confg with an @config suggestion; staged as WARNING")
    void unknownReferenceFile() {
        List<BitFinding> findings = withCode(raw(WORLDS,
                "definitions/regions/arena.yml", "world: world\ncacheCap: \"@confg\"\n"), "RTP_REFERENCE_UNKNOWN_FILE");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(Level.ERROR, findings.get(0).severity());
        assertEquals("definitions/regions/arena.yml", findings.get(0).source());
        assertEquals("cacheCap", findings.get(0).path());
        assertEquals("@config", findings.get(0).suggestion());

        var report = ConfigPbit.run(ConfigReferenceCheck.INSTANCE, ConfigSnapshot.of(files(
                "definitions/regions/arena.yml", "cacheCap: \"@confg\"\n")), WORLDS);
        assertEquals(Level.WARNING, withCode(report.findings(), "RTP_REFERENCE_UNKNOWN_FILE").get(0).severity());

        BitSeverityPolicy.load(false, true, Map.of());
        report = ConfigPbit.run(ConfigReferenceCheck.INSTANCE, ConfigSnapshot.of(files(
                "definitions/regions/arena.yml", "cacheCap: \"@confg\"\n")), WORLDS);
        assertEquals(Level.ERROR, withCode(report.findings(), "RTP_REFERENCE_UNKNOWN_FILE").get(0).severity());
    }

    @Test
    @DisplayName("ADR-121 RTP_REFERENCE_SETTING_MISSING fires when the referenced file lacks the setting")
    void missingReferencedSetting() {
        List<BitFinding> findings = withCode(raw(WORLDS,
                "definitions/regions/arena.yml", "activeChunkCap: \"@config\"\ncacheCap: \"@safety\"\n"),
                "RTP_REFERENCE_SETTING_MISSING");
        assertEquals(2, findings.size(), findings.toString());
        assertTrue(findings.stream().anyMatch(finding -> finding.path().equals("activeChunkCap")
                && finding.expected().equals("config.yml defaults.activeChunkCap")));
        assertTrue(findings.stream().anyMatch(finding -> finding.path().equals("cacheCap")));
    }

    @Test
    @DisplayName("ADR-121 @config, @economy and cooldown fallback that resolve raise no RTP_REFERENCE_SETTING_MISSING")
    void resolvedReferencesAreClean() {
        List<BitFinding> findings = raw(WORLDS, "definitions/regions/arena.yml",
                "cacheCap: \"@config\"\nrequirePermission: \"@config\"\nprice: \"@economy\"\ncooldown: \"@config\"\n",
                "definitions/worlds/world.yml", "requirePermission: \"@config\"\n");
        assertTrue(withCode(findings, "RTP_REFERENCE_SETTING_MISSING").isEmpty(), findings.toString());
        assertTrue(withCode(findings, "RTP_REFERENCE_UNKNOWN_FILE").isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("ADR-121 RTP_REFERENCE_SETTING_MISSING is not raised when the referenced file is unknown here")
    void absentReferencedFileIsUnknown() {
        Map<String, String> files = files("definitions/regions/arena.yml", "price: \"@economy\"\ncacheCap: \"@config\"\n");
        files.remove("economy.yml");
        files.remove("config.yml");
        List<BitFinding> findings = ConfigReferenceCheck.INSTANCE.check(ConfigSnapshot.of(files), WORLDS);
        assertTrue(withCode(findings, "RTP_REFERENCE_SETTING_MISSING").isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("ADR-121 RTP_REGION_UNKNOWN_WORLD fires only against a loaded-world list")
    void regionUnknownWorld() {
        List<BitFinding> findings = withCode(raw(WORLDS, "definitions/regions/arena.yml", "world: wrold\n"),
                "RTP_REGION_UNKNOWN_WORLD");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("world", findings.get(0).path());
        assertEquals("world", findings.get(0).suggestion());

        assertTrue(withCode(raw(WORLDS, "definitions/regions/arena.yml", "world: World_Nether\n"),
                "RTP_REGION_UNKNOWN_WORLD").isEmpty());
        assertFalse(withCode(raw(WORLDS, "definitions/regions/arena.yml", "world: \"[2]\"\n"),
                "RTP_REGION_UNKNOWN_WORLD").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 headless: no RTP_REGION_UNKNOWN_WORLD, one RTP_WORLD_REFERENCES_UNVERIFIED INFO")
    void headlessWorldReferencesAreInfoOnly() {
        List<BitFinding> findings = raw(ConfigEnvironment.HEADLESS, "definitions/regions/arena.yml", "world: wrold\n");
        assertTrue(withCode(findings, "RTP_REGION_UNKNOWN_WORLD").isEmpty(), findings.toString());
        assertTrue(withCode(findings, "RTP_WORLD_UNKNOWN_OVERRIDE").isEmpty(), findings.toString());
        List<BitFinding> info = withCode(findings, "RTP_WORLD_REFERENCES_UNVERIFIED");
        assertEquals(1, info.size(), findings.toString());
        assertEquals(Level.INFO, info.get(0).severity());
        assertTrue(withCode(raw(WORLDS), "RTP_WORLD_REFERENCES_UNVERIFIED").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_WORLD_UNKNOWN_REGION fires for a world file naming no region file")
    void worldUnknownRegion() {
        List<BitFinding> findings = withCode(raw(WORLDS, "definitions/worlds/world.yml", "region: defualt\n"),
                "RTP_WORLD_UNKNOWN_REGION");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("definitions/worlds/world.yml", findings.get(0).source());
        assertEquals("region", findings.get(0).path());
        assertEquals("default", findings.get(0).suggestion());
        assertTrue(withCode(raw(WORLDS, "definitions/worlds/world.yml", "region: DEFAULT\n"),
                "RTP_WORLD_UNKNOWN_REGION").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_REGION_UNKNOWN_OVERRIDE fires for a region override naming no region file")
    void regionUnknownOverride() {
        List<BitFinding> findings = withCode(raw(WORLDS, "definitions/regions/arena.yml", "override: lobby\n"),
                "RTP_REGION_UNKNOWN_OVERRIDE");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("override", findings.get(0).path());
        assertTrue(withCode(raw(WORLDS, "definitions/regions/arena.yml", "override: default\n"),
                "RTP_REGION_UNKNOWN_OVERRIDE").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_WORLD_UNKNOWN_OVERRIDE fires for an override naming no loaded world or world file")
    void worldUnknownOverride() {
        List<BitFinding> findings = withCode(raw(WORLDS, "definitions/worlds/world.yml", "override: hub\n"),
                "RTP_WORLD_UNKNOWN_OVERRIDE");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("override", findings.get(0).path());
        assertTrue(withCode(raw(WORLDS, "definitions/worlds/world.yml", "override: world_nether\n"),
                "RTP_WORLD_UNKNOWN_OVERRIDE").isEmpty());
        assertTrue(withCode(raw(WORLDS, "definitions/worlds/world.yml", "override: \"[1]\"\n"),
                "RTP_WORLD_UNKNOWN_OVERRIDE").isEmpty());
        assertTrue(withCode(raw(WORLDS, "definitions/worlds/world.yml", "override: default\n"),
                "RTP_WORLD_UNKNOWN_OVERRIDE").isEmpty());
    }

    @Test
    @DisplayName("ADR-121 RTP_SCHEMATIC_ORPHAN warns for a schematic matching no region or action placement")
    void schematicOrphan() {
        List<BitFinding> findings = withCode(raw(WORLDS,
                "advanced/schematics/default.schem", "",
                "advanced/schematics/pit.schematic", "",
                "advanced/schematics/shrine.litematic", "",
                "advanced/schematics/arenas/ring.schem", "",
                "advanced/schematics/old.schem", "",
                "definitions/actions/koth.yml", "placement:\n  schematic: \"pit\"\n",
                "definitions/actions/shrine_duel.yml", "placement:\n  schematic: \"shrine.litematic\"\n",
                "definitions/actions/arena.yml", "placement:\n  schematic: \"arenas/ring.schem\"\n"),
                "RTP_SCHEMATIC_ORPHAN");
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("advanced/schematics/old.schem", findings.get(0).source());
        assertEquals(Level.WARNING, findings.get(0).severity());
    }
}
