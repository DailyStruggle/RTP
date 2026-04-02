package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MultiConfigParserIsolationTest {

    @TempDir
    Path tempDir;

    private MultiConfigParser<WorldKeys> worldParser;

    @BeforeEach
    void setUp() throws IOException {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTPScheduler scheduler = mock(RTPScheduler.class);
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;

        // Mock worlds
        when(serverAccessor.getRTPWorld("world")).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class));
        when(serverAccessor.getRTPWorld("world_nether")).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class));

        // Create default.yml in worlds directory to act as template
        Path worldsDir = tempDir.resolve("worlds");
        Files.createDirectories(worldsDir);
        Path defaultYaml = worldsDir.resolve("default.yml");
        Files.writeString(defaultYaml, "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception e) {}
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        // Initialize Configs and MultiConfigParser
        RTP.configs = new Configs(tempDir.toFile());
        worldParser = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile());
        RTP.configs.putParser(worldParser);

    }

    @Test
    void testStateIsolationBetweenWorlds() {

        // 1. Get world_nether and world
        ConfigParser<WorldKeys> nether = worldParser.getParser("world_nether");
        ConfigParser<WorldKeys> overworld = worldParser.getParser("world");



        assertNotNull(nether);
        assertNotNull(overworld);
        assertNotSame(nether, overworld);

        // 2. Mutate world_nether

        nether.set(WorldKeys.requirePermission, true);
        assertEquals(true, nether.getData(WorldKeys.requirePermission));

        // 3. Assert overworld is unchanged
        Object overworldVal = overworld.getData(WorldKeys.requirePermission);

        assertEquals(false, overworldVal, "Overworld configuration was contaminated by world_nether mutation!");
    }

    @Test
    void testInvalidWorldReturnsNullOrFallbacksGracefully() {

        // The requirement says getWorldParser("invalid_world") strictly returns null
        // or falls back gracefully without generating a blank, orphaned config file.

        // Let's check current behavior of Configs.getWorldParser
        ConfigParser<WorldKeys> invalid = RTP.configs.getWorldParser("invalid_world");


        assertNull(invalid, "Expected null for unregistered/invalid world");

        File invalidFile = tempDir.resolve("worlds/invalid_world.yml").toFile();

        assertFalse(invalidFile.exists(), "Orphaned config file was created for invalid world!");
    }
}
