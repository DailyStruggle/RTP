package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigParserCoverageTest {

    @TempDir
    Path tempDir;

    private File pluginDir;
    private YamlFileDatabase db;

    @SuppressWarnings("java:S115")
    enum SampleKeys {
        name,
        delay,
        cooldown,
        enabled,
        subSection,
        shape,
        vert,
        version
    }

    @SuppressWarnings("java:S115")
    enum SubKeys {
        minY,
        maxY
    }

    static class SubFactoryValue extends FactoryValue<SubKeys> {
        public SubFactoryValue(String name) {
            super(SubKeys.class, name);
            set(SubKeys.minY, 10);
            set(SubKeys.maxY, 100);
        }
    }

    @BeforeEach
    void setUp() {
        pluginDir = tempDir.resolve("RTP").toFile();
        pluginDir.mkdirs();

        RTPServerAccessor mockAccessor = mock(RTPServerAccessor.class);
        when(mockAccessor.getPluginDirectory()).thenReturn(pluginDir);
        RTP.serverAccessor = mockAccessor;

        RTP.factoryMap.put(RTP.factoryNames.shape, new Factory<>());
        RTP.factoryMap.put(RTP.factoryNames.vert, new Factory<>());

        db = new YamlFileDatabase(pluginDir);
        db.connect();
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
    }

    @Test
    @DisplayName("sanitizeName and leafName handle edge cases and Windows paths")
    void testNameSanitizationAndLeaf() {
        assertNull(ConfigParser.sanitizeName(null));
        assertEquals("minecraft_overworld", ConfigParser.sanitizeName("minecraft:overworld"));
        assertEquals("test_name_file", ConfigParser.sanitizeName("test/name\\file"));
        assertEquals("a_b_c_d_e_f_", ConfigParser.sanitizeName("a*b?c\"d<e>f|"));

        assertNull(ConfigParser.leafName(null));
        assertEquals("test.yml", ConfigParser.leafName("test.yml"));
        assertEquals("test.yml", ConfigParser.leafName("sub/dir/test.yml"));
        assertEquals("test.yml", ConfigParser.leafName("sub\\dir\\test.yml"));
    }

    @Test
    @DisplayName("parseDuration variants test various units, numbers, infinities, and invalid inputs")
    void testDurationParsing() {
        assertEquals(-1L, ConfigParser.parseDurationSeconds(null, -1L));
        assertEquals(42L, ConfigParser.parseDurationSeconds(null, 42L));
        assertEquals(10L, ConfigParser.parseDurationSeconds(10, 5L));
        assertEquals(-1L, ConfigParser.parseDurationSeconds(0, 5L));
        assertEquals(-1L, ConfigParser.parseDurationSeconds(-5, 5L));
        assertEquals(-1L, ConfigParser.parseDurationSeconds("-1", 5L));
        assertEquals(-1L, ConfigParser.parseDurationSeconds("infinite", 5L));
        assertEquals(-1L, ConfigParser.parseDurationSeconds("permanent", 5L));
        assertEquals(5L, ConfigParser.parseDurationSeconds("", 5L));
        assertEquals(5L, ConfigParser.parseDurationSeconds("invalid_duration_xyz", 5L));

        assertEquals(60L, ConfigParser.parseDurationSeconds("1m", 0L));
        assertEquals(3600L, ConfigParser.parseDurationSeconds("1h", 0L));
        assertEquals(86400L, ConfigParser.parseDurationSeconds("1d", 0L));
        assertEquals(120L, ConfigParser.parseDurationSeconds("2m", 0L));

        // parseDurationSeconds with single argument
        assertEquals(-1L, ConfigParser.parseDurationSeconds("infinite"));
        assertEquals(30L, ConfigParser.parseDurationSeconds("30s"));

        // parseDurationTicks
        assertEquals(-1L, ConfigParser.parseDurationTicks(null, -1L));
        assertEquals(100L, ConfigParser.parseDurationTicks(100, 20L));
        assertEquals(-1L, ConfigParser.parseDurationTicks(0, 20L));
        assertEquals(-1L, ConfigParser.parseDurationTicks("-1", 20L));
        assertEquals(-1L, ConfigParser.parseDurationTicks("infinite", 20L));
        assertEquals(20L, ConfigParser.parseDurationTicks("", 20L));
        assertEquals(20L, ConfigParser.parseDurationTicks("invalid", 20L));
        assertEquals(20L, ConfigParser.parseDurationTicks("1s", 0L)); // 1s = 20 ticks

        // parseDurationMillis
        assertEquals(-1L, ConfigParser.parseDurationMillis(null, -1L));
        assertEquals(5000L, ConfigParser.parseDurationMillis(5000, 1000L));
        assertEquals(-1L, ConfigParser.parseDurationMillis(0, 1000L));
        assertEquals(-1L, ConfigParser.parseDurationMillis("infinite", 1000L));
        assertEquals(1000L, ConfigParser.parseDurationMillis("", 1000L));
        assertEquals(1000L, ConfigParser.parseDurationMillis("invalid", 1000L));
        assertEquals(1000L, ConfigParser.parseDurationMillis("1s", 0L)); // 1s = 1000ms
    }

    @Test
    @DisplayName("parseDataSize variants test units, numbers, and unlimited sentinels")
    void testDataSizeParsing() {
        assertEquals(-1L, ConfigParser.parseDataSizeBytes(null));
        assertEquals(1000L, ConfigParser.parseDataSizeBytes("1KB", 0L));
        assertEquals(1024L, ConfigParser.parseDataSizeBytes("1KiB", 0L));
        assertEquals(1000000L, ConfigParser.parseDataSizeBytes("1MB", 0L));
        assertEquals(1048576L, ConfigParser.parseDataSizeBytes("1MiB", 0L));
        assertEquals(500L, ConfigParser.parseDataSizeBytes(500, 0L));
        assertEquals(-1L, ConfigParser.parseDataSizeBytes("infinite", 0L));
        assertEquals(-1L, ConfigParser.parseDataSizeBytes("unlimited", 0L));
        assertEquals(50L, ConfigParser.parseDataSizeBytes("invalid", 50L));
    }

    @Test
    @DisplayName("ConfigParser getTime and getDataSize helper methods")
    void testGetTimeAndGetDataSize() throws IOException {
        File configFile = new File(pluginDir, "sample.yml");
        Files.writeString(configFile.toPath(), "version: \"1.0\"\ndelay: \"10s\"\ncooldown: \"500MB\"\n");

        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "sample",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\ndelay: \"10s\"\ncooldown: \"500MB\"\n".getBytes());
            }
        };

        assertEquals(10L, parser.getTime(SampleKeys.delay, 1L));
        assertEquals(10L, parser.getTime(SampleKeys.delay, "5s"));
        assertEquals(20L, parser.getTime(SampleKeys.enabled, 20L)); // not a duration or missing
        assertEquals(60L, parser.getTime(SampleKeys.enabled, "1m"));

        assertEquals(500L * 1000L * 1000L, parser.getDataSize(SampleKeys.cooldown, 0L));
        assertEquals(500L * 1000L * 1000L, parser.getDataSize(SampleKeys.cooldown, "100MB"));
        assertEquals(1024L, parser.getDataSize(SampleKeys.enabled, 1024L));
        assertEquals(1000000L, parser.getDataSize(SampleKeys.enabled, "1MB"));
    }

    @Test
    @DisplayName("getMap returns Map or RtpYamlSection map values, and empty map for scalar/missing")
    void testGetMap() throws IOException {
        File configFile = new File(pluginDir, "section_test.yml");
        Files.writeString(configFile.toPath(),
                "version: \"1.0\"\n" +
                "subSection:\n" +
                "  foo: \"bar\"\n" +
                "  num: 123\n" +
                "name: \"plain_scalar\"\n");

        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "section_test",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
            }
        };

        Map<String, Object> map = parser.getMap(SampleKeys.subSection);
        assertNotNull(map);
        assertEquals("bar", map.get("foo"));

        Map<String, Object> scalarMap = parser.getMap(SampleKeys.name);
        assertNotNull(scalarMap);
        assertTrue(scalarMap.isEmpty());

        Map<String, Object> missingMap = parser.getMap(SampleKeys.delay);
        assertNotNull(missingMap);
        assertTrue(missingMap.isEmpty());
    }

    @Test
    @DisplayName("getYamlRoot returns root section or null if uncached")
    void testGetYamlRoot() {
        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "root_test",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nname: test\n".getBytes());
            }
        };

        RtpYamlSection root = parser.getYamlRoot();
        assertNotNull(root);
        assertEquals("root_test.yml", parser.name);
    }

    @Test
    @DisplayName("set with String keys and invalid key handling")
    void testSetWithStringKey() {
        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "set_str_test",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nname: initial\n".getBytes());
            }
        };

        parser.set("name", "updated");
        assertEquals("updated", parser.getConfigValue(SampleKeys.name, null));

        parser.setConfigValue("delay", 15);
        assertEquals(15, parser.getConfigValue(SampleKeys.delay, null));

        assertThrows(IllegalArgumentException.class, () -> parser.set("nonExistentKey", "value"));
    }

    @Test
    @DisplayName("set with FactoryValue, Map, and Section modifications")
    void testSetComplexStructures() throws IOException {
        File configFile = new File(pluginDir, "complex_set.yml");
        Files.writeString(configFile.toPath(),
                "version: \"1.0\"\n" +
                "subSection:\n" +
                "  minY: 0\n" +
                "  maxY: 50\n");

        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "complex_set",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
            }
        };

        // Set existing section using Map
        Map<String, Object> newMap = new HashMap<>();
        newMap.put("minY", 20);
        newMap.put("maxY", 80);
        parser.set(SampleKeys.subSection, newMap);

        // Set existing section using FactoryValue
        SubFactoryValue sfv = new SubFactoryValue("customSub");
        parser.set(SampleKeys.subSection, sfv);

        // Setting a scalar into a section should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> parser.set(SampleKeys.subSection, "scalar_value"));

        // Setting FactoryValue into a non-section key
        parser.set(SampleKeys.delay, sfv);

        // Setting Map into a non-section key
        Map<String, Object> plainMap = new LinkedHashMap<>();
        plainMap.put("a", 1);
        parser.set(SampleKeys.cooldown, plainMap);

        parser.save();

        RtpYamlConfig reloaded = new RtpYamlConfig(configFile);
        reloaded.load();
        assertNotNull(reloaded.getConfigurationSection("subSection"));
        assertNotNull(reloaded.getConfigurationSection("delay"));
        assertNotNull(reloaded.getConfigurationSection("cooldown"));
    }

    @Test
    @DisplayName("set with registered Factory shape and vert lookup")
    void testSetShapeAndVertLookups() throws IOException {
        Factory<SubFactoryValue> shapeFactory = new Factory<>();
        SubFactoryValue circle = new SubFactoryValue("circle");
        shapeFactory.add("circle", circle);
        RTP.factoryMap.put(RTP.factoryNames.shape, (Factory) shapeFactory);

        File configFile = new File(pluginDir, "shape_set.yml");
        Files.writeString(configFile.toPath(),
                "version: \"1.0\"\n" +
                "shape: circle\n");

        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "shape_set",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
            }
        };

        parser.set(SampleKeys.shape, circle);
        parser.save();

        RtpYamlConfig reloaded = new RtpYamlConfig(configFile);
        reloaded.load();
        assertNotNull(reloaded.getConfigurationSection("shape"));
    }

    @Test
    @DisplayName("clone duplicates parser with same state and checks")
    void testClone() {
        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "clone_test",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nname: cloned\n".getBytes());
            }
        };

        ConfigParser<SampleKeys> clone = parser.clone();
        assertNotNull(clone);
        assertEquals(parser.name, clone.name);
        assertEquals(parser.version, clone.version);
        assertEquals(parser.pluginDirectory, clone.pluginDirectory);
        assertEquals("cloned", clone.getConfigValue(SampleKeys.name, null));
    }

    @Test
    @DisplayName("corrupt yaml file is quarantined and clean default re-extracted")
    void testCorruptYamlQuarantine() throws IOException {
        File corruptFile = new File(pluginDir, "corrupt.yml");
        Files.writeString(corruptFile.toPath(), ":\n  - invalid: [unclosed yaml\n");

        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "corrupt",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nname: healed\n".getBytes());
            }
        };

        assertEquals("healed", parser.getConfigValue(SampleKeys.name, null));

        File[] files = pluginDir.listFiles((dir, name) -> name.startsWith("corrupt.yml.corrupt-"));
        assertNotNull(files);
        assertTrue(files.length > 0, "Corrupt file should have been renamed with .corrupt- prefix");
    }

    @Test
    @DisplayName("getMainDirectory and getClassLoader return correct references")
    void testGetMainDirectoryAndClassLoader() {
        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "accessors_test",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
            }
        };

        assertEquals(pluginDir, parser.getMainDirectory());
        assertNotNull(parser.getClassLoader());
    }

    @Test
    @DisplayName("save triggers backup creation with bakRetention")
    void testSaveBackupCreation() throws IOException {
        ConfigParser.bakRetention = 2;
        File configFile = new File(pluginDir, "backup_test.yml");
        Files.writeString(configFile.toPath(), "version: \"1.0\"\nname: backup_init\n");

        ConfigParser<SampleKeys> parser = new ConfigParser<>(
                SampleKeys.class,
                "backup_test",
                "1.0",
                pluginDir,
                db
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nname: backup_init\n".getBytes());
            }
        };

        parser.set(SampleKeys.name, "backup_rev1");
        parser.save();

        parser.set(SampleKeys.name, "backup_rev2");
        parser.save();

        assertEquals(2, ConfigBackups.listBaks(configFile).size());
    }
}
