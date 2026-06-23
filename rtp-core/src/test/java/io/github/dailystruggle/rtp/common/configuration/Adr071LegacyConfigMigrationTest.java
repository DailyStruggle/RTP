package io.github.dailystruggle.rtp.common.configuration;

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
}
