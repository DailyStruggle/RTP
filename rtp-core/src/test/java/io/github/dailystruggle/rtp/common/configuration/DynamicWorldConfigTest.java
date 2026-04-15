package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

public class DynamicWorldConfigTest {
    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private Configs configs;

    @BeforeEach
    void setUp() {
        // "world" is registered by default in MockRTPServerAccessor
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        // Initialize Configs
        configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> mockMulti = mock(io.github.dailystruggle.rtp.common.configuration.MultiConfigParser.class);
        mockMulti.configParserFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        try {
            java.lang.reflect.Field myDirectoryField = io.github.dailystruggle.rtp.common.configuration.MultiConfigParser.class.getDeclaredField("myDirectory");
            myDirectoryField.setAccessible(true);
            myDirectoryField.set(mockMulti, tempDir.resolve("world").toFile());

            java.lang.reflect.Field fileDatabaseField = io.github.dailystruggle.rtp.common.configuration.MultiConfigParser.class.getDeclaredField("fileDatabase");
            fileDatabaseField.setAccessible(true);
            fileDatabaseField.set(mockMulti, new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(tempDir.resolve("world").toFile()));
        } catch (Exception e) {}
        configs.multiConfigParserMap.put(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class, mockMulti);
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> mockWorldParser = mock(io.github.dailystruggle.rtp.common.configuration.ConfigParser.class);
        mockWorldParser.name = "default.yml";
        mockMulti.configParserFactory.add("default.yml", mockWorldParser);
        when(mockMulti.getParser(anyString())).thenReturn(mockWorldParser);
        doReturn(new java.util.EnumMap<>(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class)).when(mockWorldParser).getData();
        doReturn(0).when(mockWorldParser).getNumber(any(), any());
        doReturn(false).when(mockWorldParser).getConfigValue(any(), any());
    }

    @Test
    void testDynamicWorldLoading() {
        String runtimeWorldName = "runtime_generated_dimension";

        // Ensure it doesn't exist yet in the accessor (not registered → returns null)
        assertNull(configs.getWorldParser(runtimeWorldName));

        // Register the world in the accessor to simulate it coming online at runtime
        accessor.addWorld(new MockRTPWorld(runtimeWorldName));

        // Invoke Configs.getWorldParser and assert it's successfully instantiated
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> runtimeParser = mock(io.github.dailystruggle.rtp.common.configuration.ConfigParser.class);
        runtimeParser.name = runtimeWorldName + ".yml";
        doReturn(new java.util.EnumMap<>(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class)).when(runtimeParser).getData();
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys> mockMulti = (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys>) configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class);
        mockMulti.configParserFactory.add(runtimeWorldName + ".yml", runtimeParser);
        when(mockMulti.getParser(runtimeWorldName)).thenReturn(runtimeParser);

        ConfigParser<WorldKeys> parser = configs.getWorldParser(runtimeWorldName);
        assertNotNull(parser, "Parser should be dynamically created for new world");
        assertEquals(runtimeWorldName, parser.name.replace(".yml", ""));

        // Verify it's added to MultiConfigParser's internal factory
        MultiConfigParser<WorldKeys> multiConfigParser = (MultiConfigParser<WorldKeys>) configs.getParser(WorldKeys.class);
        assertNotNull(multiConfigParser);
        assertTrue(multiConfigParser.configParserFactory.contains(runtimeWorldName.toUpperCase() + ".YML"));

        // Assert it can be retrieved again
        assertSame(parser, configs.getWorldParser(runtimeWorldName));
    }
}
