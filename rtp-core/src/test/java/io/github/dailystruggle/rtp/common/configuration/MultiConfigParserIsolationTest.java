package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

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
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        RTP.serverAccessor = serverAccessor;

        // Mock worlds
        when(serverAccessor.getRTPWorld("world")).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class));
        when(serverAccessor.getRTPWorld("world_nether")).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class));

        // Create default.yml in worlds directory to act as template
        Path worldsDir = tempDir.resolve("worlds");
        Files.createDirectories(worldsDir);
        Path defaultYaml = worldsDir.resolve("default.yml");
        Files.writeString(defaultYaml, "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");

        // Initialize Configs and MultiConfigParser
        RTP.configs = new Configs(tempDir.toFile());
        worldParser = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile());
        RTP.configs.putParser(worldParser);
        System.out.println("[DEBUG_LOG] MultiConfigParser initialized with myDirectory=" + worldParser.myDirectory.getAbsolutePath());
    }

    @Test
    void testStateIsolationBetweenWorlds() {
        System.out.println("[DEBUG_LOG] Starting testStateIsolationBetweenWorlds");
        // 1. Get world_nether and world
        ConfigParser<WorldKeys> nether = worldParser.getParser("world_nether");
        ConfigParser<WorldKeys> overworld = worldParser.getParser("world");
        System.out.println("[DEBUG_LOG] nether parser name: " + nether.name);
        System.out.println("[DEBUG_LOG] overworld parser name: " + overworld.name);

        assertNotNull(nether);
        assertNotNull(overworld);
        assertNotSame(nether, overworld);

        // 2. Mutate world_nether
        System.out.println("[DEBUG_LOG] Mutating nether requirePermission to true");
        nether.set(WorldKeys.requirePermission, true);
        assertEquals(true, nether.getData(WorldKeys.requirePermission));

        // 3. Assert overworld is unchanged
        Object overworldVal = overworld.getData(WorldKeys.requirePermission);
        System.out.println("[DEBUG_LOG] overworld requirePermission: " + overworldVal);
        assertEquals(false, overworldVal, "Overworld configuration was contaminated by world_nether mutation!");
    }

    @Test
    void testInvalidWorldReturnsNullOrFallbacksGracefully() {
        System.out.println("[DEBUG_LOG] Starting testInvalidWorldReturnsNullOrFallbacksGracefully");
        // The requirement says getWorldParser("invalid_world") strictly returns null 
        // or falls back gracefully without generating a blank, orphaned config file.
        
        // Let's check current behavior of Configs.getWorldParser
        ConfigParser<WorldKeys> invalid = RTP.configs.getWorldParser("invalid_world");
        System.out.println("[DEBUG_LOG] invalid parser name: " + (invalid != null ? invalid.name : "null"));
        
        // My fix makes it return the default parser instead of null if it's not found on the server.
        // This is a "graceful fallback".
        assertNotNull(invalid);
        assertTrue(invalid.name.equalsIgnoreCase("default.yml"), "Expected default.yml for invalid world, got: " + invalid.name);
        
        File invalidFile = tempDir.resolve("worlds/invalid_world.yml").toFile();
        System.out.println("[DEBUG_LOG] invalidFile exists: " + invalidFile.exists());
        assertFalse(invalidFile.exists(), "Orphaned config file was created for invalid world!");
    }
}
