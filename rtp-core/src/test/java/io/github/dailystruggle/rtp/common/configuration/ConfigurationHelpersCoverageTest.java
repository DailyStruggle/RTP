package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigurationHelpersCoverageTest {

    @TempDir
    Path tempDir;

    private File pluginDir;

    @BeforeEach
    void setUp() {
        pluginDir = tempDir.resolve("RTP").toFile();
        pluginDir.mkdirs();

        RTPServerAccessor mockAccessor = mock(RTPServerAccessor.class);
        when(mockAccessor.getPluginDirectory()).thenReturn(pluginDir);
        RTP.serverAccessor = mockAccessor;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
    }

    @Test
    @DisplayName("ConfigBackups handles backup, list, restoreLatest, and pruneBaks")
    void testConfigBackups() throws IOException, InterruptedException {
        File target = new File(pluginDir, "config.yml");
        // Target doesn't exist yet -> backup returns null
        assertNull(ConfigBackups.backup(target, 3));
        assertNull(ConfigBackups.restoreLatest(target));
        assertEquals(0, ConfigBackups.pruneBaks(target, 3));
        assertTrue(ConfigBackups.listBaks(target).isEmpty());

        // Create target
        Files.writeString(target.toPath(), "version: 1.0\nrevision: 1\n");

        // 1st backup
        Path bak1 = ConfigBackups.backup(target, 2);
        assertNotNull(bak1);
        assertTrue(Files.exists(bak1));

        Thread.sleep(10);
        Files.writeString(target.toPath(), "version: 1.0\nrevision: 2\n");
        Path bak2 = ConfigBackups.backup(target, 2);
        assertNotNull(bak2);

        Thread.sleep(10);
        Files.writeString(target.toPath(), "version: 1.0\nrevision: 3\n");
        Path bak3 = ConfigBackups.backup(target, 2);
        assertNotNull(bak3);

        // Retention is 2, so oldest (bak1) should have been pruned
        List<Path> baks = ConfigBackups.listBaks(target);
        assertEquals(2, baks.size());
        assertEquals(bak3, baks.get(0)); // newest first

        // Restore latest (bak3)
        Path restored = ConfigBackups.restoreLatest(target);
        assertEquals(bak3, restored);
        assertFalse(Files.exists(bak3), "Restored backup should be consumed");
        assertEquals("version: 1.0\nrevision: 3\n", Files.readString(target.toPath()));

        // Pruning with negative keep clamps to 0
        assertEquals(1, ConfigBackups.pruneBaks(target, 0));
        assertTrue(ConfigBackups.listBaks(target).isEmpty());
    }

    @Test
    @DisplayName("ConfigDefaultResolver checks reference tokens and handles fallbacks")
    void testConfigDefaultResolver() {
        assertFalse(ConfigDefaultResolver.isReference(null));
        assertFalse(ConfigDefaultResolver.isReference(123));
        assertFalse(ConfigDefaultResolver.isReference("literal"));
        assertFalse(ConfigDefaultResolver.isReference("@"));
        assertTrue(ConfigDefaultResolver.isReference("@config"));
        assertTrue(ConfigDefaultResolver.isReference("  @economy  "));

        assertNull(ConfigDefaultResolver.referencedFile(null));
        assertNull(ConfigDefaultResolver.referencedFile("literal"));
        assertEquals("config", ConfigDefaultResolver.referencedFile("@config"));
        assertEquals("economy", ConfigDefaultResolver.referencedFile("  @ECONOMY "));

        // Resolving literal returns literal
        assertEquals("literalVal", ConfigDefaultResolver.resolve("literalVal", "key", "fallback"));

        // Resolving unknown reference returns fallback
        assertEquals("fallback", ConfigDefaultResolver.resolve("@unknown_source", "key", "fallback"));

        // Resolving when RTP.configs is null returns fallback
        RTP.configs = null;
        assertEquals("fallback", ConfigDefaultResolver.resolve("@config", "key", "fallback"));
        assertEquals("fallback", ConfigDefaultResolver.resolve("@economy", "key", "fallback"));
        assertEquals("fallback", ConfigDefaultResolver.resolve("@safety", "key", "fallback"));
    }

    @Test
    @DisplayName("ConfigDirectives parsing from comments")
    void testConfigDirectives() {
        ConfigDirectives empty1 = ConfigDirectives.parse(null);
        assertNotNull(empty1);
        assertNull(empty1.type());
        assertTrue(empty1.options().isEmpty());
        assertNull(empty1.source());
        assertFalse(empty1.hasFiniteDomain());

        ConfigDirectives empty2 = ConfigDirectives.parse("# just a regular comment\n# another line");
        assertFalse(empty2.hasFiniteDomain());

        String comment = "# Options for selection shape\n" +
                "# @type: enum\n" +
                "# @options: [\"circle\", 'square', rectangle]\n" +
                "# @source: shape\n";

        ConfigDirectives parsed = ConfigDirectives.parse(comment);
        assertNotNull(parsed);
        assertEquals("enum", parsed.type());
        assertEquals("shape", parsed.source());
        assertEquals(List.of("circle", "square", "rectangle"), parsed.options());
        assertTrue(parsed.hasFiniteDomain());
    }

    @Test
    @DisplayName("Messages facade byName and valueByName")
    void testMessages() {
        assertNull(Messages.byName(null));
        assertNull(Messages.byName("NON_EXISTENT_MESSAGE_KEY_XYZ"));

        Enum<?> invalidCmd = Messages.byName("invalidCommand");
        assertNotNull(invalidCmd);
        assertEquals(PlayerMessages.invalidCommand, invalidCmd);

        // valueByName when RTP.configs is null returns def
        RTP.configs = null;
        assertEquals("default_msg", Messages.valueByName("invalidCommand", "default_msg"));
        assertEquals("default_msg", Messages.valueByName("invalid_key_xyz", "default_msg"));
    }

    @SuppressWarnings("java:S115")
    enum DummyShapeKeys {
        radius,
        center
    }

    static class DummyShape extends FactoryValue<DummyShapeKeys> {
        public DummyShape(String name) {
            super(DummyShapeKeys.class, name);
            set(DummyShapeKeys.radius, 500);
            set(DummyShapeKeys.center, "0,0");
            language_mapping.put("radius", "radius");
            language_mapping.put("center", "center");
        }
    }

    @Test
    @DisplayName("SelectorCatalogWriter renders and writes shape/vert documentation")
    void testSelectorCatalogWriter() throws IOException {
        Factory<DummyShape> shapeFactory = new Factory<>();
        DummyShape dummy = new DummyShape("circle.yml");
        shapeFactory.add("circle.yml", dummy);
        RTP.factoryMap.put(RTP.factoryNames.shape, (Factory) shapeFactory);
        RTP.factoryMap.put(RTP.factoryNames.vert, new Factory<>());

        // write with null
        SelectorCatalogWriter.write(null);

        // write with valid pluginDirectory
        SelectorCatalogWriter.write(pluginDir);

        File catalogFile = new File(pluginDir, "definitions" + File.separator + "regions" + File.separator + "SHAPES.md");
        assertTrue(catalogFile.exists());
        String content = Files.readString(catalogFile.toPath());
        assertTrue(content.contains("# Shape catalog"));
    }

    @Test
    @DisplayName("SafetyTokenExpander resolves tags, materials, state-predicates and schedules retries")
    void testSafetyTokenExpander() throws IOException {
        YamlFileDatabase db = new YamlFileDatabase(pluginDir);
        File safetyFile = new File(pluginDir, "safety.yml");
        Files.writeString(safetyFile.toPath(),
                "version: \"1.0\"\n" +
                "airBlocks:\n" +
                "  - AIR\n" +
                "  - \"#minecraft:leaves\"\n" +
                "  - \"minecraft:oak_leaves[persistent=true]\"\n" +
                "unsafeBlocks:\n" +
                "  - LAVA\n" +
                "  - \"#unresolved_tag\"\n");

        ConfigParser<BlocksKeys> safetyParser = new ConfigParser<>(
                BlocksKeys.class,
                "safety",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
            }
        };

        // When tag snapshot has minecraft:leaves -> expands to materials
        Map<String, Set<String>> snapshot = new HashMap<>();
        snapshot.put("minecraft:leaves", Set.of("minecraft:oak_leaves", "minecraft:birch_leaves"));
        when(RTP.serverAccessor.blockTagSnapshot()).thenReturn(snapshot);

        SafetyTokenExpander.expandAndApply(safetyParser);

        Object airBlocksObj = safetyParser.getConfigValue(BlocksKeys.airBlocks, null);
        assertTrue(airBlocksObj instanceof List);
        List<?> airBlocks = (List<?>) airBlocksObj;
        assertTrue(airBlocks.contains("AIR"));
        assertTrue(airBlocks.contains("OAK_LEAVES"));
        assertTrue(airBlocks.contains("BIRCH_LEAVES"));
        assertTrue(airBlocks.contains("minecraft:oak_leaves[persistent=true]"));

        Object unsafeObj = safetyParser.getConfigValue(BlocksKeys.unsafeBlocks, null);
        assertTrue(unsafeObj instanceof List);
        List<?> unsafeBlocks = (List<?>) unsafeObj;
        assertTrue(unsafeBlocks.contains("LAVA"));
        assertTrue(unsafeBlocks.contains("#unresolved_tag")); // preserved because unresolved

        // Test with null parser
        SafetyTokenExpander.expandAndApply(null);
    }
}
