package io.github.dailystruggle.bstats.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared bStats config: existing files (either format) are read and never
 * rewritten, so the server UUID and opt-out survive the library swap.
 */
class BStatsConfigTest {

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_B = "66666666-7777-8888-9999-000000000000";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("Existing Bukkit config.yml keeps its UUID and is not rewritten")
    void readsBukkitYaml() throws Exception {
        Path yml = tmp.resolve("config.yml");
        String original = "# bStats header\nenabled: true\nserverUuid: \"" + UUID_A + "\"\nlogFailedRequests: true\n";
        Files.writeString(yml, original, StandardCharsets.UTF_8);

        BStatsConfig cfg = BStatsConfig.load(tmp.toFile(), BStatsConfig.Format.YAML);
        assertTrue(cfg.enabled());
        assertEquals(UUID_A, cfg.serverUuid());
        assertTrue(cfg.logFailedRequests());
        assertEquals(original, Files.readString(yml, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Existing modded config.txt and base-library keys are read")
    void readsTextFormats() throws Exception {
        Files.writeString(tmp.resolve("config.txt"), "enabled=false\nserverUuid=" + UUID_A + "\n", StandardCharsets.UTF_8);
        BStatsConfig modded = BStatsConfig.load(tmp.toFile(), BStatsConfig.Format.TEXT);
        assertFalse(modded.enabled());
        assertEquals(UUID_A, modded.serverUuid());

        Path velocityDir = Files.createDirectories(tmp.resolve("velocity"));
        Files.writeString(velocityDir.resolve("config.txt"),
                "enabled=true\nserver-uuid=" + UUID_B + "\nlog-errors=true\n", StandardCharsets.UTF_8);
        BStatsConfig base = BStatsConfig.load(velocityDir.toFile(), BStatsConfig.Format.TEXT);
        assertEquals(UUID_B, base.serverUuid());
        assertTrue(base.logFailedRequests());
    }

    @Test
    @DisplayName("config.yml wins over config.txt")
    void yamlPreferred() throws Exception {
        Files.writeString(tmp.resolve("config.yml"), "serverUuid: '" + UUID_A + "'\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("config.txt"), "serverUuid=" + UUID_B + "\n", StandardCharsets.UTF_8);
        assertEquals(UUID_A, BStatsConfig.load(tmp.toFile(), BStatsConfig.Format.TEXT).serverUuid());
    }

    @Test
    @DisplayName("Missing config is created once in the requested format; the UUID is then stable")
    void createsAndReuses() throws Exception {
        File dir = tmp.resolve("plugins").resolve("bStats").toFile();
        BStatsConfig first = BStatsConfig.load(dir, BStatsConfig.Format.YAML);
        Path yml = dir.toPath().resolve("config.yml");
        assertTrue(Files.isRegularFile(yml));
        String body = Files.readString(yml, StandardCharsets.UTF_8);
        assertTrue(body.contains("enabled: true"));
        assertTrue(body.contains("serverUuid: \"" + first.serverUuid() + "\""));
        assertEquals(first.serverUuid(), BStatsConfig.load(dir, BStatsConfig.Format.YAML).serverUuid());

        File txtDir = tmp.resolve("config").resolve("bStats").toFile();
        BStatsConfig txt = BStatsConfig.load(txtDir, BStatsConfig.Format.TEXT);
        String txtBody = Files.readString(txtDir.toPath().resolve("config.txt"), StandardCharsets.UTF_8);
        assertTrue(txtBody.contains("server-uuid=" + txt.serverUuid()), "upstream base-library key");
        assertTrue(txtBody.contains("log-errors=false"));
        assertEquals(txt.serverUuid(), BStatsConfig.load(txtDir, BStatsConfig.Format.TEXT).serverUuid());
    }

    @Test
    @DisplayName("Unwritable location falls back to an enabled, ephemeral UUID")
    void ioFailureFallsBack() throws Exception {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        BStatsConfig cfg = BStatsConfig.load(blocker.resolve("bStats").toFile(), BStatsConfig.Format.YAML);
        assertTrue(cfg.enabled());
        assertEquals(36, cfg.serverUuid().length());
    }

    @Test
    @DisplayName("Opt-out is detected in the sibling bStats folder in either format")
    void optOutDetection() throws Exception {
        File pluginDir = tmp.resolve("plugins").resolve("RTP").toFile();
        assertTrue(pluginDir.mkdirs());
        assertFalse(BStatsConfig.isOptedOut(pluginDir));

        Path bStats = Files.createDirectories(tmp.resolve("plugins").resolve("bStats"));
        Files.writeString(bStats.resolve("config.yml"), "enabled: false\n", StandardCharsets.UTF_8);
        assertTrue(BStatsConfig.isOptedOut(pluginDir));

        Files.delete(bStats.resolve("config.yml"));
        Files.writeString(bStats.resolve("config.txt"), "enabled=FALSE\n", StandardCharsets.UTF_8);
        assertTrue(BStatsConfig.isOptedOut(pluginDir));

        Path local = Files.createDirectories(pluginDir.toPath().resolve("bStats"));
        Files.delete(bStats.resolve("config.txt"));
        Files.writeString(local.resolve("config.txt"), "# comment\nenabled = false\n", StandardCharsets.UTF_8);
        assertTrue(BStatsConfig.isOptedOut(pluginDir), "legacy plugin-local location");
    }

    @Test
    @DisplayName("bStats directory is the plugin directory's sibling")
    void directoryResolution() {
        File pluginDir = tmp.resolve("plugins").resolve("RTP").toFile();
        assertEquals(tmp.resolve("plugins").resolve("bStats").toFile().getAbsoluteFile(),
                BStatsConfig.bStatsDirectory(pluginDir));
        assertEquals(new File("config", "bStats"), BStatsConfig.bStatsDirectory(null));
    }
}
