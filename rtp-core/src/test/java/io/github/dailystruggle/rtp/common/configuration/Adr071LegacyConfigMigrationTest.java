package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;

import java.util.List;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-071 (Config Organization and Discoverability) rule 4/5: a key/block that
 * physically moves out of {@code config.yml} must keep being read from its legacy
 * location, applied at the new location, and surfaced as a one-time deprecation
 * warning, so existing installs upgrade cleanly. Covers the {@code database} block
 * (moved to database.yml) and the {@code network.redis} block (moved to network.yml).
 */
public class Adr071LegacyConfigMigrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        Path worldsDir = tempDir.resolve("worlds");
        Files.createDirectories(worldsDir);
        Files.writeString(worldsDir.resolve("default.yml"),
                "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");
    }

    private void writeConfig(String body) throws IOException {
        Files.writeString(tempDir.resolve("config.yml"), body);
    }

    // --- pure merge logic (ADR-071 rule 4/5 precedence) ---

    @Test
    @DisplayName("mergeLegacyConfig: legacy value fills a key still at the bundled default")
    void legacyFillsDefault() {
        Map<String, Object> newMap = new LinkedHashMap<>();
        newMap.put("type", "sqlite");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("type", "sqlite");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("type", "mysql");

        Map<String, Object> merged = Configs.mergeLegacyConfig(newMap, legacy, defaults);
        assertEquals("mysql", merged.get("type"),
                "legacy value must be honored when the new file still holds the default");
    }

    @Test
    @DisplayName("mergeLegacyConfig: a customized new-file value wins over legacy")
    void customizedNewWins() {
        Map<String, Object> newMap = new LinkedHashMap<>();
        newMap.put("type", "postgresql");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("type", "sqlite");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("type", "mysql");

        Map<String, Object> merged = Configs.mergeLegacyConfig(newMap, legacy, defaults);
        assertEquals("postgresql", merged.get("type"),
                "an operator-customized new-file value must take precedence over the legacy value");
    }

    @Test
    @DisplayName("mergeLegacyConfig: keys present only in the new map are preserved")
    void newOnlyKeyPreserved() {
        Map<String, Object> newMap = new LinkedHashMap<>();
        newMap.put("extra", 42);
        Map<String, Object> merged =
                Configs.mergeLegacyConfig(newMap, new HashMap<>(), new HashMap<>());
        assertEquals(42, merged.get("extra"));
    }

    // --- file-backed legacy readers ---

    @Test
    @DisplayName("readLegacyConfigSection reads a legacy database block from config.yml")
    void readsLegacyDatabaseBlock() throws IOException {
        writeConfig("teleportDelay: 2\n"
                + "database:\n"
                + "  type: \"mysql\"\n"
                + "  host: \"db.example.com\"\n"
                + "  port: 3307\n"
                + "version: 3.0\n");
        Configs configs = new Configs(tempDir.toFile());
        Map<String, Object> legacy = configs.readLegacyConfigSection("database");
        assertEquals("mysql", String.valueOf(legacy.get("type")));
        assertEquals("db.example.com", String.valueOf(legacy.get("host")));
    }

    @Test
    @DisplayName("readLegacyConfigSection returns empty when no legacy block present")
    void noLegacyBlockEmpty() throws IOException {
        writeConfig("teleportDelay: 2\nversion: 3.1\n");
        Configs configs = new Configs(tempDir.toFile());
        assertTrue(configs.readLegacyConfigSection("database").isEmpty());
    }

    @Test
    @DisplayName("readLegacyNetworkRedis reads the nested network.redis block")
    void readsLegacyNetworkRedis() throws IOException {
        writeConfig("teleportDelay: 2\n"
                + "network:\n"
                + "  redis:\n"
                + "    enabled: true\n"
                + "    host: \"127.0.0.1\"\n"
                + "    port: 6379\n"
                + "version: 3.0\n");
        Configs configs = new Configs(tempDir.toFile());
        Map<String, Object> redis = configs.readLegacyNetworkRedis();
        assertFalse(redis.isEmpty(), "nested network.redis block should be read");
        assertEquals("true", String.valueOf(redis.get("enabled")));
    }

    @Test
    @DisplayName("getDatabaseConfig honors a legacy config.yml database block on upgrade")
    void getDatabaseConfigHonorsLegacy() throws IOException {
        // rtp-core's test classpath ships no database.yml resource, so the new map is
        // empty (the lite-like path); the legacy block must still be honored fully.
        writeConfig("teleportDelay: 2\n"
                + "database:\n"
                + "  type: \"mysql\"\n"
                + "  host: \"db.example.com\"\n"
                + "version: 3.0\n");
        Configs configs = new Configs(tempDir.toFile());
        Map<String, Object> db = configs.getDatabaseConfig();
        assertEquals("mysql", String.valueOf(db.get("type")),
                "legacy database.type must be honored when database.yml provides no value");

        // Idempotent: a second read returns the same honored values (warn-once does not
        // alter the merge result).
        Map<String, Object> again = configs.getDatabaseConfig();
        assertEquals("mysql", String.valueOf(again.get("type")));
    }

    // --- ADR-071 rule 4: whole-file relocation into advanced/ ---

    @Test
    @DisplayName("migrateLegacyRootConfig folds a legacy root logging.yml into the relocated parser and archives it")
    void migratesLegacyRootLoggingFile() throws IOException {
        // A pre-ADR-071 install left logging.yml at the plugin root with a customized value.
        Files.writeString(tempDir.resolve("logging.yml"),
                "command: true\nteleport: false\nversion: 1.0\n");

        Configs configs = new Configs(tempDir.toFile());
        ConfigParser<LoggingKeys> parser = new ConfigParser<>(
                LoggingKeys.class, "advanced/logging.yml", "1.1", tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()), "en");

        configs.migrateLegacyRootConfig(parser, "logging.yml");

        assertEquals("true", String.valueOf(parser.getConfigValue(LoggingKeys.command, null)),
                "customized legacy logging.command must be carried forward to advanced/logging.yml");
        assertEquals("false", String.valueOf(parser.getConfigValue(LoggingKeys.teleport, null)),
                "customized legacy logging.teleport must be carried forward");
        assertFalse(tempDir.resolve("logging.yml").toFile().exists(),
                "the legacy root logging.yml must be archived (renamed), not left in place");
        assertTrue(tempDir.resolve("logging.yml.migrated").toFile().exists(),
                "the legacy file must be archived as logging.yml.migrated");
    }

    // --- ADR-071 rule 4: split-key relocation out of a file that stays in place ---

    @Test
    @DisplayName("migrateLegacyKeysFromFile folds moved safety.yml keys into the relocated parser and leaves safety.yml in place")
    void migratesSplitKeysFromSafetyFile() throws IOException {
        // A pre-ADR-071 install carries airBlocks/unsafeBlocks inside safety.yml.
        Files.writeString(tempDir.resolve("safety.yml"),
                "invulnerabilityTime: 5\n"
                        + "airBlocks:\n"
                        + "  - \"AIR\"\n"
                        + "  - \"CAVE_AIR\"\n"
                        + "unsafeBlocks:\n"
                        + "  - \"LAVA\"\n"
                        + "version: 1.1\n");

        Configs configs = new Configs(tempDir.toFile());
        ConfigParser<BlocksKeys> blocks = new ConfigParser<>(
                BlocksKeys.class, "advanced/blocks.yml", "1.0", tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()), "en");

        configs.migrateLegacyKeysFromFile(blocks, "safety.yml", "blocks");

        Object air = blocks.getConfigValue(BlocksKeys.airBlocks, null);
        assertTrue(air instanceof List<?> && ((List<?>) air).contains("AIR"),
                "legacy safety.yml airBlocks must be folded into advanced/blocks.yml");
        Object unsafe = blocks.getConfigValue(BlocksKeys.unsafeBlocks, null);
        assertTrue(unsafe instanceof List<?> && ((List<?>) unsafe).contains("LAVA"),
                "legacy safety.yml unsafeBlocks must be folded into advanced/blocks.yml");
        assertTrue(tempDir.resolve("safety.yml").toFile().exists(),
                "safety.yml must stay in place (it still owns the remaining safety knobs)");
    }

    @Test
    @DisplayName("migrateLegacyKeysFromFile is a no-op when the legacy source file is absent")
    void migrateSplitKeysNoopWhenAbsent() {
        Configs configs = new Configs(tempDir.toFile());
        ConfigParser<BlocksKeys> blocks = new ConfigParser<>(
                BlocksKeys.class, "advanced/blocks.yml", "1.0", tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()), "en");
        // No safety.yml written: must not throw.
        configs.migrateLegacyKeysFromFile(blocks, "safety.yml", "blocks");
    }

    @Test
    @DisplayName("migrateLegacyRootConfig is a no-op when no legacy root file is present")
    void migrateNoopWhenAbsent() {
        Configs configs = new Configs(tempDir.toFile());
        ConfigParser<LoggingKeys> parser = new ConfigParser<>(
                LoggingKeys.class, "advanced/logging.yml", "1.1", tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()), "en");
        // No legacy logging.yml written: must not throw and must not create an archive.
        configs.migrateLegacyRootConfig(parser, "logging.yml");
        assertFalse(tempDir.resolve("logging.yml.migrated").toFile().exists());
    }
}
