package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
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

public class ConfigsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());

        // Ensure worlds directory exists with a default.yml for MultiConfigParser
        Path worldsDir = tempDir.resolve("worlds");
        Files.createDirectories(worldsDir);
        Files.writeString(worldsDir.resolve("default.yml"),
                "requirePermission: false\nregion: default\noverride: none\nversion: 1.0\n");
    }

    // --- singleton initialization ---

    @Test
    void testConfigsConstructorSetsRTPConfigs() {
        Configs configs = new Configs(tempDir.toFile());
        assertSame(configs, RTP.configs, "Configs constructor should set RTP.configs to itself");
    }

    @Test
    void testPluginDirectoryIsSet() {
        Configs configs = new Configs(tempDir.toFile());
        assertEquals(tempDir.toFile().getAbsolutePath(),
                configs.pluginDirectory.getAbsolutePath(),
                "pluginDirectory should match the constructor argument");
    }

    @Test
    void testFileDatabaseIsInitialized() {
        Configs configs = new Configs(tempDir.toFile());
        assertNotNull(configs.fileDatabase, "fileDatabase should be initialized by constructor");
    }

    // --- double-init: second Configs replaces RTP.configs ---

    @Test
    void testDoubleInitReplacesRTPConfigs() {
        Configs first = new Configs(tempDir.toFile());
        assertSame(first, RTP.configs);

        Configs second = new Configs(tempDir.toFile());
        assertSame(second, RTP.configs,
                "Second Configs() call should replace RTP.configs with the new instance");
        assertNotSame(first, RTP.configs);
    }

    // --- putParser null guard ---

    @Test
    void testPutParserNullThrows() {
        Configs configs = new Configs(tempDir.toFile());
        assertThrows(NullPointerException.class, () -> configs.putParser(null),
                "putParser(null) should throw NullPointerException");
    }

    @Test
    void testPutParserWrongTypeThrows() {
        Configs configs = new Configs(tempDir.toFile());
        assertThrows(IllegalArgumentException.class, () -> configs.putParser("not-a-parser"),
                "putParser with wrong type should throw IllegalArgumentException");
    }

    // --- getParser by key ---

    @Test
    void testGetParserAfterReloadConfigs() {
        // reloadConfigs() is called by RTPTestSetup.install(); parsers should be registered
        FactoryValue<SafetyKeys> safetyParser = RTP.configs.getParser(SafetyKeys.class);
        assertNotNull(safetyParser, "SafetyKeys parser should be registered after reloadConfigs()");
    }

    @Test
    void testGetParserLoggingKeys() {
        FactoryValue<LoggingKeys> loggingParser = RTP.configs.getParser(LoggingKeys.class);
        assertNotNull(loggingParser, "LoggingKeys parser should be registered after reloadConfigs()");
    }

    @Test
    void testGetParserPerformanceKeys() {
        FactoryValue<PerformanceKeys> perfParser = RTP.configs.getParser(PerformanceKeys.class);
        assertNotNull(perfParser, "PerformanceKeys parser should be registered after reloadConfigs()");
    }

    @Test
    void testGetParserUnregisteredClassReturnsNull() {
        Configs freshConfigs = new Configs(tempDir.toFile());
        // No parsers registered yet on this fresh instance
        FactoryValue<WorldKeys> result = freshConfigs.getParser(WorldKeys.class);
        assertNull(result, "getParser for unregistered class should return null");
    }

    // --- putParser and retrieve ---

    @Test
    void testPutAndGetConfigParser() throws IOException {
        Configs configs = new Configs(tempDir.toFile());

        // Need LoggingKeys parser first (putParser logs via it)
        RTP.configs = configs;
        configs.reloadConfigs();

        FactoryValue<SafetyKeys> retrieved = configs.getParser(SafetyKeys.class);
        assertNotNull(retrieved, "getParser should return the parser registered by reloadConfigs");
    }

    @Test
    void testPutAndGetMultiConfigParser() throws IOException {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reloadConfigs();

        MultiConfigParser<WorldKeys> worldParser =
                new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile(), "definitions/worlds");
        configs.putParser(worldParser);

        FactoryValue<WorldKeys> retrieved = configs.getParser(WorldKeys.class);
        assertNotNull(retrieved, "getParser should return the MultiConfigParser after putParser");
    }

    // --- getWorldParser ---

    @Test
    void testGetWorldParserForRegisteredWorld() throws IOException {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        // "world" is registered by default in MockRTPServerAccessor

        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reloadConfigs();

        MultiConfigParser<WorldKeys> worldParser =
                new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile(), "definitions/worlds");
        configs.putParser(worldParser);

        ConfigParser<WorldKeys> worldCfg = configs.getWorldParser("world");
        assertNotNull(worldCfg, "getWorldParser should return a parser for a registered world");
    }

    @Test
    void testGetWorldParserForUnregisteredWorldReturnsNull() throws IOException {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reloadConfigs();

        MultiConfigParser<WorldKeys> worldParser =
                new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile(), "definitions/worlds");
        configs.putParser(worldParser);

        ConfigParser<WorldKeys> result = configs.getWorldParser("no_such_world");
        assertNull(result, "getWorldParser for unregistered world should return null");
    }

    // --- onReload callback ---

    @Test
    void testOnReloadCallbackIsInvoked() {
        boolean[] called = {false};
        Configs.onReload(() -> called[0] = true);

        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reload();

        assertTrue(called[0], "onReload callback should be invoked during reload()");
    }

    // --- reload() returns true ---

    @Test
    void testReloadReturnsTrue() {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        assertTrue(configs.reload(), "reload() should return true on success");
    }

    // --- getWorldParserValue() for unregistered world returns null ---

    @Test
    void testGetWorldParserValueForUnregisteredWorldReturnsNull() throws IOException {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reloadConfigs();

        MultiConfigParser<WorldKeys> worldParser =
                new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile(), "definitions/worlds");
        configs.putParser(worldParser);

        Object result = configs.getWorldParserValue("no_such_world", WorldKeys.requirePermission);
        assertNull(result, "getWorldParserValue for unregistered world should return null");
    }

    // --- getWorldParserValue() for registered world returns non-null ---

    @Test
    void testGetWorldParserValueForRegisteredWorldReturnsNonNull() throws IOException {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reloadConfigs();

        MultiConfigParser<WorldKeys> worldParser =
                new MultiConfigParser<>(WorldKeys.class, "worlds", "1.0", tempDir.toFile(), "definitions/worlds");
        configs.putParser(worldParser);

        // "world" is registered in MockRTPServerAccessor
        Object result = configs.getWorldParserValue("world", WorldKeys.requirePermission);
        assertNotNull(result, "getWorldParserValue for a registered world should return non-null");
    }

    // --- reloadConfigs() registers all standard parsers ---

    @Test
    void testReloadConfigsRegistersAllStandardParsers() throws IOException {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reloadConfigs();

        assertNotNull(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class));
        assertNotNull(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class));
        assertNotNull(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class));
        assertNotNull(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys.class));
        assertNotNull(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class));
    }

    // --- ADR-076 regression: MultiConfigParser children share the folder-similar rename map ---

    @Test
    void multiConfigWorldsReuseSharedLangMap_noStrayPerFileMapsInFolder() throws IOException {
        Configs configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        // Registers the worlds MultiConfigParser and adds a per-world parser for the
        // MockRTPServerAccessor-registered "world" via Factory.construct (the clone path).
        configs.reloadConfigs();

        File definitions = new File(tempDir.toFile(), "definitions");
        File worldsDir = new File(definitions, "worlds");

        // The shared, folder-similar rename map lives beside the folder, not inside it.
        File shared = new File(definitions, ".worlds.lang.yml");
        assertTrue(shared.isFile(),
                "shared folder-similar rename map (definitions/.worlds.lang.yml) should exist beside the worlds folder");

        // No stray per-file `.<name>.lang.yml` rename map should be written INSIDE the folder.
        File[] strays = worldsDir.listFiles(
                (d, n) -> n.startsWith(".") && n.endsWith(".lang.yml"));
        assertTrue(strays == null || strays.length == 0,
                "MultiConfigParser child parsers must reuse the shared map; found stray in-folder maps: "
                        + java.util.Arrays.toString(strays));
    }
}
