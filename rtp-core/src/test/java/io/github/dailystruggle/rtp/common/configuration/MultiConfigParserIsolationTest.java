package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MultiConfigParserIsolationTest {

    @TempDir
    Path tempDir;

    private MultiConfigParser<WorldKeys> worldParser;

    @BeforeEach
    void setUp() throws IOException {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());

        // "world" is registered by default; add "world_nether" for this test
        accessor.addWorld(new MockRTPWorld("world_nether"));

        // Create default.yml in worlds directory to act as template
        Path worldsDir = tempDir.resolve("worlds");
        Files.createDirectories(worldsDir);
        Path defaultYaml = worldsDir.resolve("default.yml");
        Files.writeString(defaultYaml, "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");

        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        // Initialize Configs and MultiConfigParser
        RTP.configs = new Configs(tempDir.toFile());
        worldParser = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile());
        RTP.configs.putParser(worldParser);
    }

    @Test
    void testStateIsolationBetweenWorlds() {
        // 1. Get world_nether and world
        ConfigParser<WorldKeys> nether = RTP.configs.getWorldParser("world_nether");
        ConfigParser<WorldKeys> overworld = RTP.configs.getWorldParser("world");

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
        ConfigParser<WorldKeys> invalid = RTP.configs.getWorldParser("invalid_world");
        assertNull(invalid, "Expected null for unregistered/invalid world");

        File invalidFile = tempDir.resolve("worlds/invalid_world.yml").toFile();
        assertFalse(invalidFile.exists(), "Orphaned config file was created for invalid world!");
    }

    // --- merge conflict / override precedence ---

    @Test
    void testOverridePrecedence() throws IOException {
        // Create a world_nether.yml that overrides requirePermission to true
        Path worldsDir = tempDir.resolve("worlds");
        Files.writeString(worldsDir.resolve("world_nether.yml"),
                "requirePermission: true\nregion: default\noverride: none\nversion: 1.0\n");

        // Rebuild the MultiConfigParser so it picks up the new file
        worldParser = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile());
        RTP.configs.putParser(worldParser);

        ConfigParser<WorldKeys> nether = RTP.configs.getWorldParser("world_nether");
        assertNotNull(nether);
        assertEquals(true, nether.getData(WorldKeys.requirePermission),
                "world_nether.yml override should take precedence over default");
    }

    @Test
    void testDefaultFallbackWhenWorldFileAbsent() {
        // "world" has no explicit yml file; should fall back to default.yml values
        ConfigParser<WorldKeys> overworld = RTP.configs.getWorldParser("world");
        assertNotNull(overworld);
        // default.yml sets requirePermission: false
        assertEquals(false, overworld.getData(WorldKeys.requirePermission),
                "World without explicit yml should inherit default.yml values");
    }

    // --- reload-after-mutation ---

    @Test
    void testReloadAfterMutation() throws IOException {
        ConfigParser<WorldKeys> nether = RTP.configs.getWorldParser("world_nether");
        assertNotNull(nether);

        // Mutate in memory
        nether.set(WorldKeys.requirePermission, true);
        assertEquals(true, nether.getData(WorldKeys.requirePermission));

        // Save and reload via a fresh MultiConfigParser
        nether.save();
        worldParser = new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile());
        RTP.configs.putParser(worldParser);

        ConfigParser<WorldKeys> reloaded = RTP.configs.getWorldParser("world_nether");
        assertNotNull(reloaded);
        assertEquals(true, reloaded.getData(WorldKeys.requirePermission),
                "Mutated value should persist after save and reload");
    }

    // --- addParser / removeParser ---

    @Test
    void testAddAndRemoveParser() throws IOException {
        Path worldsDir = tempDir.resolve("worlds");
        Files.writeString(worldsDir.resolve("world_end.yml"),
                "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");

        ConfigParser<WorldKeys> endParser = new ConfigParser<>(
                WorldKeys.class, "world_end", "1.0", worldsDir.toFile(),
                null, new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(worldsDir.toFile()));

        worldParser.addParser(endParser);
        assertTrue(worldParser.listParsers().stream().anyMatch(n -> n.equalsIgnoreCase("world_end")),
                "world_end should be listed after addParser");

        worldParser.removeParser("world_end.yml");
        assertFalse(worldParser.listParsers().stream().anyMatch(n -> n.equalsIgnoreCase("world_end")),
                "world_end should be absent after removeParser");
    }

    // --- listParsers reflects registered worlds ---
    @Test
    void testListParsersContainsDefault() {
        assertTrue(worldParser.listParsers().stream().anyMatch(n -> n.equalsIgnoreCase("default")),
                "listParsers() should include 'default' from default.yml");
    }

    // --- addAll() registers multiple parsers ---
    @Test
    void testAddAllRegistersMultipleParsers() throws IOException {
        Path worldsDir = tempDir.resolve("worlds");
        Files.writeString(worldsDir.resolve("world_a.yml"),
                "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");
        Files.writeString(worldsDir.resolve("world_b.yml"),
                "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");

        worldParser.addAll("world_a.yml", "world_b.yml");

        assertTrue(worldParser.listParsers().stream().anyMatch(n -> n.equalsIgnoreCase("world_a")),
                "addAll should register world_a");
        assertTrue(worldParser.listParsers().stream().anyMatch(n -> n.equalsIgnoreCase("world_b")),
                "addAll should register world_b");
    }

    // --- getParser() returns DEFAULT fallback for unregistered but valid world ---
    @Test
    void testGetParserFallsBackToDefaultForRegisteredWorld() {
        // "world" is registered in MockRTPServerAccessor but has no explicit yml
        // getParser should fall back to DEFAULT.YML parser
        ConfigParser<WorldKeys> parser = worldParser.getParser("world");
        assertNotNull(parser, "getParser should return a non-null parser for a registered world");
    }

    // --- getMainDirectory and getClassLoader ---
    @Test
    void testGetMainDirectoryReturnsPluginDirectory() {
        assertEquals(tempDir.toFile().getAbsolutePath(),
                worldParser.getMainDirectory().getAbsolutePath(),
                "getMainDirectory() should return the plugin directory");
    }

    @Test
    void testGetClassLoaderIsNotNull() {
        assertNotNull(worldParser.getClassLoader(),
                "getClassLoader() should return a non-null ClassLoader");
    }
}
