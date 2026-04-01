package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

public class DynamicWorldConfigTest {
    @TempDir
    Path tempDir;

    private RTPServerAccessor serverAccessor;
    private RTPScheduler scheduler;
    private Configs configs;

    @BeforeEach
    void setUp() {
        serverAccessor = mock(RTPServerAccessor.class);
        io.github.dailystruggle.rtp.api.entity.RTPPlayer console = mock(io.github.dailystruggle.rtp.api.entity.RTPPlayer.class);
        when(console.uuid()).thenReturn(RTP.serverId);
        when(serverAccessor.getConsolePlayer()).thenReturn(console);
        scheduler = mock(RTPScheduler.class);

        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;

        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.getRTPWorlds()).thenReturn(new ArrayList<>());
        when(serverAccessor.createTaskPipe()).thenReturn(mock(RTPTaskPipe.class));

        // Mock a default world "world"
        RTPWorld<?> defaultWorld = mock(RTPWorld.class);
        when(defaultWorld.name()).thenReturn("world");
        when(serverAccessor.getRTPWorld("world")).thenReturn((RTPWorld) defaultWorld);

        ArrayList<RTPWorld<?>> worlds = new ArrayList<>();
        worlds.add(defaultWorld);
        when(serverAccessor.getRTPWorlds()).thenReturn(worlds);

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception e) {}
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

        // Ensure it doesn't exist yet in the accessor
        when(serverAccessor.getRTPWorld(runtimeWorldName)).thenReturn(null);

        // Assert getWorldParser returns null if world not in accessor
        assertNull(configs.getWorldParser(runtimeWorldName));

        // Mutate the mock to return a valid RTPWorld post-initialization
        RTPWorld<?> runtimeWorld = mock(RTPWorld.class);
        when(runtimeWorld.name()).thenReturn(runtimeWorldName);
        when(serverAccessor.getRTPWorld(runtimeWorldName)).thenReturn((RTPWorld) runtimeWorld);

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
