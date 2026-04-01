package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

        // Initialize Configs
        configs = new Configs(tempDir.toFile());
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
