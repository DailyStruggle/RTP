package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the merged-directory mode of {@link ConfigParser}: a single enum schema
 * split across several physical files under a {@code messages/} directory. Covers
 * load+merge across member files, write-back routed to the owning member file, and
 * one-time migration of a legacy single-file {@code messages.yml} into the split tree.
 */
@DisplayName("ConfigParser merged-directory mode (split messages tree)")
class MergedConfigParserTest {

    @TempDir
    Path tempDir;

    private File pluginDir;
    private YamlFileDatabase db;

    enum MergedKeys {
        alpha,
        beta,
        version
    }

    @BeforeEach
    void setUp() {
        pluginDir = tempDir.resolve("RTP").toFile();
        pluginDir.mkdirs();
        RTPServerAccessor mockAccessor = mock(RTPServerAccessor.class);
        when(mockAccessor.getPluginDirectory()).thenReturn(pluginDir);
        RTP.serverAccessor = mockAccessor;
        db = new YamlFileDatabase(pluginDir);
        db.connect();
    }

    private void seedMembers(String alphaVal, String betaVal) throws IOException {
        File msgDir = new File(pluginDir, "messages");
        msgDir.mkdirs();
        Files.writeString(new File(msgDir, "player.yml").toPath(), "alpha: \"" + alphaVal + "\"\n");
        Files.writeString(new File(msgDir, "system.yml").toPath(),
                "beta: \"" + betaVal + "\"\nversion: \"1.1\"\n");
    }

    private ConfigParser<MergedKeys> buildParser() {
        return new ConfigParser<>(MergedKeys.class, "messages.yml", "1.1", pluginDir, db, "en",
                "messages", List.of("player.yml", "system.yml"));
    }

    @Test
    @DisplayName("merges values from every member file into one logical parser")
    void mergesAcrossMembers() throws IOException {
        seedMembers("a-default", "b-default");

        ConfigParser<MergedKeys> parser = buildParser();

        assertEquals("a-default", parser.getConfigValue(MergedKeys.alpha, null),
                "alpha should be read from player.yml");
        assertEquals("b-default", parser.getConfigValue(MergedKeys.beta, null),
                "beta should be read from system.yml");
    }

    @Test
    @DisplayName("version key (now in system.yml) is readable through the merged parser")
    void versionReadableFromMergedTree() throws IOException {
        seedMembers("a-default", "b-default");

        ConfigParser<MergedKeys> parser = buildParser();

        assertEquals("1.1", parser.getConfigValue(MergedKeys.version, null).toString(),
                "version should be read from its owning member file (system.yml) via the merge");
    }

    @Test
    @DisplayName("set+save routes the edit to the owning member file only")
    void writeBackRoutesToOwner() throws IOException {
        seedMembers("a-default", "b-default");

        ConfigParser<MergedKeys> parser = buildParser();
        parser.set(MergedKeys.alpha, "a-new");
        parser.save();

        RtpYamlConfig player = new RtpYamlConfig(new File(pluginDir, "messages/player.yml"));
        player.load();
        RtpYamlConfig system = new RtpYamlConfig(new File(pluginDir, "messages/system.yml"));
        system.load();

        assertEquals("a-new", player.getString("alpha"),
                "edited value should be written to the owning member (player.yml)");
        assertFalse(system.contains("alpha"),
                "the non-owning member (system.yml) must not gain the key");
        assertEquals("b-default", system.getString("beta"),
                "the non-owning member's own keys are untouched");
    }

    @Test
    @DisplayName("migrates a legacy single-file messages.yml into the split tree and archives it")
    void migratesLegacyFile() throws IOException {
        seedMembers("a-default", "b-default");
        // Operator-customized legacy single-file messages.yml left over from a prior version.
        File legacy = new File(pluginDir, "messages.yml");
        Files.writeString(legacy.toPath(),
                "alpha: \"custom-alpha\"\nbeta: \"b-default\"\nversion: \"1.1\"\n");

        ConfigParser<MergedKeys> parser = buildParser();

        assertEquals("custom-alpha", parser.getConfigValue(MergedKeys.alpha, null),
                "customized legacy value should be migrated into the merged tree");

        assertFalse(new File(pluginDir, "messages.yml").exists(),
                "legacy messages.yml should be moved aside after migration");
        assertTrue(new File(pluginDir, "messages.yml.migrated").exists(),
                "legacy messages.yml should be archived as .migrated");

        RtpYamlConfig player = new RtpYamlConfig(new File(pluginDir, "messages/player.yml"));
        player.load();
        assertEquals("custom-alpha", player.getString("alpha"),
                "migrated value should land in the owning member file on disk");
    }
}
