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
 * ADR-071 (Config Organization and Discoverability), rule 3 sub-bullet: a {@code ConfigParser}
 * {@code name} may carry a relative subdirectory (e.g. {@code "advanced/blocks.yml"},
 * {@code "messages/player.yml"}). The on-disk file, the JAR-resource extraction path, and the
 * {@code lang/<locale>/<name>} mirror path must all tolerate the subdirectory while the
 * file-database key and the {@code /rtp config} sub-command stay unqualified (bare leaf name).
 *
 * <p>Driven by a bundled test resource at {@code advanced/logging.yml} (+ no
 * {@code lang/advanced/logging.lang.yml}, so an identity lang map is seeded).
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
    @DisplayName("the lang map mirror is created under lang/<subDir>/ for the English locale")
    void langMirrorUnderSubDir() {
        subpathedParser();
        File langMirror = new File(tempDir.toFile(),
                "lang" + File.separator + "advanced" + File.separator + "logging.lang.yml");
        assertTrue(langMirror.exists(),
                "lang mirror should be created at " + langMirror.getAbsolutePath());
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
    @DisplayName("the lang map mirror is created under lang/messages/ for the English locale")
    void messagesLangMirrorUnderSubDir() {
        messagesParser();
        File langMirror = new File(tempDir.toFile(),
                "lang" + File.separator + "messages" + File.separator + "player.lang.yml");
        assertTrue(langMirror.exists(),
                "lang mirror should be created at " + langMirror.getAbsolutePath());
    }
}
