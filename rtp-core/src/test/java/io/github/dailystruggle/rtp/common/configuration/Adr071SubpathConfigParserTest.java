package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for subpath support in {@link ConfigParser} (ADR-071).
 * Verifies relative subdirectories for extraction, on-disk paths, and lang mirrors.
 */
public class Adr071SubpathConfigParserTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
    }

    private ConfigParser<LoggingKeys> subpathedParser() {
        return new ConfigParser<>(
                LoggingKeys.class,
                "advanced/logging.yml",
                "1.0",
                tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()),
                "en");
    }

    @Test
    @DisplayName("a subpathed name splits into a relative subDir and a bare leaf name")
    void subpathSplits() {
        ConfigParser<LoggingKeys> parser = subpathedParser();
        assertEquals("advanced", parser.subDir, "subDir should carry the relative directory");
        assertEquals("logging.yml", parser.name, "name should be the bare leaf file name");
    }

    @Test
    @DisplayName("a plain root-level name leaves subDir empty")
    void rootNameHasNoSubDir() {
        ConfigParser<LoggingKeys> parser = new ConfigParser<>(
                LoggingKeys.class, "logging.yml", "1.0", tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()), "en");
        assertEquals("", parser.subDir, "a root-level parser must have an empty subDir");
        assertEquals("logging.yml", parser.name);
    }

    @Test
    @DisplayName("the JAR resource is extracted under <pluginDir>/<subDir>/")
    void extractsUnderSubDir() {
        subpathedParser();
        File onDisk = new File(tempDir.toFile(), "advanced" + File.separator + "logging.yml");
        assertTrue(onDisk.exists(),
                "subpathed config should be extracted to " + onDisk.getAbsolutePath());
        // It must NOT be flattened to the plugin root.
        assertFalse(new File(tempDir.toFile(), "logging.yml").exists(),
                "subpathed config must not be extracted to the plugin root");
        assertFalse(new File(tempDir.toFile(), "advanced_logging.yml").exists(),
                "the subdirectory separator must not be sanitized into the file name");
    }

    @Test
    @DisplayName("values from a subpathed file are loaded and readable")
    void valuesLoad() {
        ConfigParser<LoggingKeys> parser = subpathedParser();
        Object minLevel = parser.getConfigValue(LoggingKeys.min_level, null);
        assertNotNull(minLevel, "min_level should be loaded from the subpathed file");
        assertEquals("ALL", String.valueOf(minLevel));
    }

    @Test
    @DisplayName("the lang map is created as a co-located dotfile sibling for the English locale (ADR-076)")
    void langMirrorUnderSubDir() {
        subpathedParser();
        // ADR-076: the English baseline rename map is a co-located dotfile sibling of
        // the value file - advanced/.logging.lang.yml beside advanced/logging.yml.
        File langMirror = new File(tempDir.toFile(),
                "advanced" + File.separator + ".logging.lang.yml");
        assertTrue(langMirror.exists(),
                "lang map should be created at " + langMirror.getAbsolutePath());
    }

    @Test
    @DisplayName("leafName drops any subdirectory prefix")
    void leafNameStripsSubDir() {
        assertEquals("player.yml", ConfigParser.leafName("messages/player.yml"));
        assertEquals("blocks.yml", ConfigParser.leafName("advanced\\blocks.yml"));
        assertEquals("config.yml", ConfigParser.leafName("config.yml"));
    }

    // ADR-071 item 2.4: the same subpath handling must work against the already-shipped
    // messages/ subdirectory, not only the synthetic advanced/ fixture. Driven by the
    // bundled test resource at messages/player.yml.

    enum PlayerMsgKeys {
        alreadyTeleporting,
        busy,
        days,
        version
    }

    private ConfigParser<PlayerMsgKeys> messagesParser() {
        return new ConfigParser<>(
                PlayerMsgKeys.class,
                "messages/player.yml",
                "1.0",
                tempDir.toFile(),
                new YamlFileDatabase(tempDir.toFile()),
                "en");
    }

    @Test
    @DisplayName("a messages/ subpathed name splits into subDir 'messages' and a bare leaf")
    void messagesSubpathSplits() {
        ConfigParser<PlayerMsgKeys> parser = messagesParser();
        assertEquals("messages", parser.subDir, "subDir should carry the messages/ directory");
        assertEquals("player.yml", parser.name, "name should be the bare leaf file name");
    }

    @Test
    @DisplayName("the messages/ resource is extracted under <pluginDir>/messages/ (not flattened)")
    void messagesExtractsUnderSubDir() {
        messagesParser();
        File onDisk = new File(tempDir.toFile(), "messages" + File.separator + "player.yml");
        assertTrue(onDisk.exists(),
                "subpathed messages config should be extracted to " + onDisk.getAbsolutePath());
        assertFalse(new File(tempDir.toFile(), "player.yml").exists(),
                "messages config must not be flattened to the plugin root");
        assertFalse(new File(tempDir.toFile(), "messages_player.yml").exists(),
                "the subdirectory separator must not be sanitized into the file name");
    }

    @Test
    @DisplayName("values from a messages/ subpathed file are loaded and readable")
    void messagesValuesLoad() {
        ConfigParser<PlayerMsgKeys> parser = messagesParser();
        assertEquals("&c[P0] busy", String.valueOf(parser.getConfigValue(PlayerMsgKeys.busy, null)),
                "busy should be loaded from the subpathed messages file");
        assertEquals("d", String.valueOf(parser.getConfigValue(PlayerMsgKeys.days, null)));
    }

    @Test
    @DisplayName("the messages/ lang map is created as a co-located dotfile sibling for the English locale (ADR-076)")
    void messagesLangMirrorUnderSubDir() {
        messagesParser();
        // ADR-076: co-located dotfile sibling - messages/.player.lang.yml beside messages/player.yml.
        File langMirror = new File(tempDir.toFile(),
                "messages" + File.separator + ".player.lang.yml");
        assertTrue(langMirror.exists(),
                "lang map should be created at " + langMirror.getAbsolutePath());
    }
}
