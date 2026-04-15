package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConfigParserLanguageTest {

    @TempDir
    Path tempDir;

    public enum TestKeys {
        alpha,
        beta,
        version
    }

    @BeforeEach
    void setUp() {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTPScheduler scheduler = mock(RTPScheduler.class);
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception ignored) {}
    }

    @Test
    void testAlternateLanguageMapping() throws IOException {
        File langDir = tempDir.resolve("lang").toFile();
        langDir.mkdirs();
        File langFile = new File(langDir, "test.lang.yml");

        // Write alternate language mapping
        Files.writeString(langFile.toPath(), "alpha: alternate_alpha\nbeta: alternate_beta\nversion: version\n");

        // Write config file using alternate keys
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(), "alternate_alpha: 10\nalternate_beta: 20\nversion: 1.0\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());

        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase
        );

        // The parser should have translated alternate_alpha to alpha and parsed the value 10
        Object alphaVal = parser.getData(TestKeys.alpha);
        Object betaVal = parser.getData(TestKeys.beta);

        assertEquals(10, alphaVal, "Parser failed to map alternate_alpha back to alpha");
        assertEquals(20, betaVal, "Parser failed to map alternate_beta back to beta");
    }
}
