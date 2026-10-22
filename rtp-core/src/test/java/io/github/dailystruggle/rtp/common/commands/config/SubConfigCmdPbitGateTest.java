package io.github.dailystruggle.rtp.common.commands.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubConfigCmdPbitGateTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    @Test
    void rejectedSetLeavesConfigFileUnchanged() throws Exception {
        Path configDirectory = Files.createDirectories(tempDir.resolve("advanced"));
        Path target = configDirectory.resolve("performance.yml");
        Files.writeString(target, "version: 1.1\nviewDistanceSelect: 5\n");
        ConfigParser<PerformanceKeys> parser = new ConfigParser<>(
                PerformanceKeys.class,
                "advanced/performance.yml",
                "1.1",
                tempDir.toFile(),
                new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(tempDir.toFile()));
        ActiveConfigs.get().configParserMap.put(PerformanceKeys.class, parser);
        String original = Files.readString(target);
        Files.writeString(tempDir.resolve("config.yml"), "radius: [1, 2\n");

        SubConfigCmd command = new SubConfigCmd(null, "performance", parser);
        command.onCommand(UUID.randomUUID(),
                Map.of("viewdistanceselect", List.of("17")), null);

        assertEquals(original, Files.readString(target));
    }
}
