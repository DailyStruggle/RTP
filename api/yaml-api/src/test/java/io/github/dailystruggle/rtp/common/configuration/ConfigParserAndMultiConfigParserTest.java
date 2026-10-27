package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.YamlFileCache;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConfigParser & MultiConfigParser Comprehensive Standalone Tests")
class ConfigParserAndMultiConfigParserTest {

    @TempDir
    Path tempDir;

    private File pluginDir;
    private YamlFileCache cache;
    private ConfigParser.FileCacheProvider provider;

    enum SampleKeys {
        alpha,
        beta,
        delay,
        cacheCap,
        enabled,
        shape,
        vert,
        subSection,
        version
    }

    enum ShapeKeys {
        mode,
        radius
    }

    static final class TestShape extends FactoryValue<ShapeKeys> {
        TestShape(String name, int radius) {
            super(ShapeKeys.class, name);
            set(ShapeKeys.mode, "ACCUMULATED");
            set(ShapeKeys.radius, radius);
        }
    }

    @BeforeEach
    void setUp() {
        pluginDir = tempDir.resolve("RTP").toFile();
        assertTrue(pluginDir.mkdirs());
        cache = new YamlFileCache(pluginDir);
        provider = () -> cache;
        FactoryValue.setPluginDirectorySupplier(() -> pluginDir);
    }

    @AfterEach
    void tearDown() {
        ConfigParser.setDefaultReferenceResolver(null);
        ConfigParser.setSectionFactoryResolver(null);
        MultiConfigParser.setDefaultCanCreateDynamicParser(null);
        FactoryValue.setPluginDirectorySupplier(null);
    }

    @Test
    @DisplayName("Static helpers: isReference, referencedFile, sanitizeName, leafName, duration and data-size parsers")
    void staticHelpersAndUnitParsers() {
        assertTrue(ConfigParser.isReference("@config"));
        assertTrue(ConfigParser.isReference("  @economy "));
        assertFalse(ConfigParser.isReference("@"));
        assertFalse(ConfigParser.isReference("literal"));
        assertFalse(ConfigParser.isReference(null));
        assertFalse(ConfigParser.isReference(123));

        assertEquals("config", ConfigParser.referencedFile("@CONFIG"));
        assertNull(ConfigParser.referencedFile("not_a_ref"));

        assertNull(ConfigParser.sanitizeName(null));
        assertEquals("minecraft_overworld", ConfigParser.sanitizeName("minecraft:overworld"));
        assertEquals("a_b_c", ConfigParser.sanitizeName("a/b\\c"));

        assertNull(ConfigParser.leafName(null));
        assertEquals("safety.yml", ConfigParser.leafName("advanced/safety.yml"));
        assertEquals("safety.yml", ConfigParser.leafName("advanced\\safety.yml"));

        // Duration seconds
        assertEquals(-1L, ConfigParser.parseDurationSeconds(null));
        assertEquals(-1L, ConfigParser.parseDurationSeconds(""));
        assertEquals(-1L, ConfigParser.parseDurationSeconds("-1"));
        assertEquals(-1L, ConfigParser.parseDurationSeconds("infinite"));
        assertEquals(-1L, ConfigParser.parseDurationSeconds("permanent"));
        assertEquals(-1L, ConfigParser.parseDurationSeconds(0));
        assertEquals(-1L, ConfigParser.parseDurationSeconds(-5));
        assertEquals(30L, ConfigParser.parseDurationSeconds(30));
        assertEquals(90L, ConfigParser.parseDurationSeconds("1m30s"));
        assertEquals(15L, ConfigParser.parseDurationSeconds("invalid", 15L));

        // Duration ticks
        assertEquals(20L, ConfigParser.parseDurationTicks(null, 20L));
        assertEquals(-1L, ConfigParser.parseDurationTicks(0, 20L));
        assertEquals(40L, ConfigParser.parseDurationTicks(40, 20L));
        assertEquals(-1L, ConfigParser.parseDurationTicks("infinite", 20L));
        assertEquals(100L, ConfigParser.parseDurationTicks("5s", 0L));
        assertEquals(7L, ConfigParser.parseDurationTicks("garbage", 7L));

        // Duration millis
        assertEquals(500L, ConfigParser.parseDurationMillis(null, 500L));
        assertEquals(-1L, ConfigParser.parseDurationMillis(0, 500L));
        assertEquals(250L, ConfigParser.parseDurationMillis(250, 0L));
        assertEquals(-1L, ConfigParser.parseDurationMillis("permanent", 500L));
        assertEquals(2000L, ConfigParser.parseDurationMillis("2s", 0L));
        assertEquals(99L, ConfigParser.parseDurationMillis("garbage", 99L));

        // Seconds duration millis
        assertEquals(3000L, ConfigParser.parseSecondsDurationMillis(3, 0L));
        assertEquals(0L, ConfigParser.parseSecondsDurationMillis(0, 100L));
        assertEquals(0L, ConfigParser.parseSecondsDurationMillis("-5", 100L));
        assertEquals(3000L, ConfigParser.parseSecondsDurationMillis("3", 0L));
        assertEquals(1500L, ConfigParser.parseSecondsDurationMillis("1.5s", 0L));
        assertEquals(42L, ConfigParser.parseSecondsDurationMillis("garbage", 42L));

        // Generic parseDuration
        assertEquals(5000L, ConfigParser.parseDuration(5, TemporalUnit.SECOND, TemporalUnit.MILLISECOND, 0L));
        assertEquals(-1L, ConfigParser.parseDuration(-1, TemporalUnit.SECOND, TemporalUnit.SECOND, 0L));
        assertEquals(123L, ConfigParser.parseDuration("garbage", TemporalUnit.SECOND, TemporalUnit.SECOND, 123L));

        // Data size bytes
        assertEquals(-1L, ConfigParser.parseDataSizeBytes(null));
        assertEquals(-1L, ConfigParser.parseDataSizeBytes("infinite"));
        assertEquals(1024L, ConfigParser.parseDataSizeBytes("1KiB"));
        assertEquals(1000L, ConfigParser.parseDataSizeBytes("1KB"));
    }

    @Test
    @DisplayName("ConfigParser constructors, FileCacheProvider overloads, getters, set, save, and clone")
    void constructorsGettersSetSaveAndClone() throws IOException {
        File cfgFile = new File(pluginDir, "sample.yml");
        Files.writeString(cfgFile.toPath(),
                """
                        version: "1.0"
                        alpha: "10"
                        beta: true
                        delay: "2m"
                        cacheCap: "128MB"
                        subSection:
                          k1: v1
                        """,
                StandardCharsets.UTF_8);

        // Exercise FileCacheProvider constructors
        ConfigParser<SampleKeys> p1 = new ConfigParser<>(SampleKeys.class, "sample", "1.0", pluginDir, provider);
        ConfigParser<SampleKeys> p2 = new ConfigParser<>(SampleKeys.class, "sample", "1.0", pluginDir, provider, "en");
        ConfigParser<SampleKeys> p3 = new ConfigParser<>(SampleKeys.class, "sample", "1.0", pluginDir, null, provider);
        ConfigParser<SampleKeys> p4 = new ConfigParser<>(SampleKeys.class, "sample", "1.0", pluginDir, null, provider, getClass().getClassLoader());
        ConfigParser<SampleKeys> p5 = new ConfigParser<>(SampleKeys.class, "sample", "1.0", pluginDir, null, provider, getClass().getClassLoader(), "en");
        ConfigParser<SampleKeys> p6 = new ConfigParser<>(SampleKeys.class, "sample", "1.0", pluginDir, null, cache, "en");

        assertNotNull(p1.getYamlRoot());
        assertNotNull(p2.getYamlRoot());
        assertNotNull(p3.getYamlRoot());
        assertNotNull(p4.getYamlRoot());
        assertNotNull(p5.getYamlRoot());
        assertNotNull(p6.getYamlRoot());

        assertEquals(120L, p1.getTime(SampleKeys.delay, 0L));
        assertEquals(120L, p1.getTime(SampleKeys.delay, "10s"));
        assertEquals(128_000_000L, p1.getDataSize(SampleKeys.cacheCap, 0L));
        assertEquals(128_000_000L, p1.getDataSize(SampleKeys.cacheCap, "64MB"));
        assertEquals(Map.of("k1", "v1"), p1.getMap(SampleKeys.subSection));
        assertTrue(p1.getMap(SampleKeys.alpha).isEmpty());

        // set with Map, FactoryValue, and scalar on section
        p1.set(SampleKeys.subSection, Map.of("k2", "v2"));
        TestShape shape = new TestShape("CIRCLE", 300);
        p1.set(SampleKeys.subSection, shape);
        assertThrows(IllegalArgumentException.class, () -> p1.set(SampleKeys.subSection, 123));

        // set FactoryValue and Map on non-section keys
        p1.set(SampleKeys.shape, shape);
        p1.set(SampleKeys.vert, Map.of("name", "LINEAR", "minY", 0));
        p1.set("alpha", 42);
        p1.setConfigValue("enabled", false);
        assertThrows(IllegalArgumentException.class, () -> p1.set("unknown_key_xyz", 1));

        p1.save();
        ConfigParser<SampleKeys> cloned = p1.clone();
        assertEquals(42, cloned.getConfigValue(SampleKeys.alpha, 0));
    }

    @Test
    @DisplayName("ConfigParser version upgrade, comment preservation, .oldN rotation, and corrupt quarantine")
    void versionUpgradeRotationAndQuarantine() throws IOException {
        File cfgFile = new File(pluginDir, "upgrade.yml");
        Files.writeString(cfgFile.toPath(),
                """
                        version: "1.0"
                        # Custom user comment
                        alpha: 777
                        vert: "@config"
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(new File(pluginDir, "upgrade.yml.old1").toPath(), "old1\n", StandardCharsets.UTF_8);

        ConfigParser<SampleKeys> upgraded = new ConfigParser<SampleKeys>(
                SampleKeys.class, "upgrade", "2.0", pluginDir, cache) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                if (filename.endsWith("upgrade.yml")) {
                    return new ByteArrayInputStream(
                            """
                                    version: "2.0"
                                    # Default alpha comment
                                    alpha: 1
                                    # Default beta comment
                                    beta: 2
                                    vert: "@config"
                                    """.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }
        };

        assertEquals(777, upgraded.getConfigValue(SampleKeys.alpha, 0));
        assertEquals(2, upgraded.getConfigValue(SampleKeys.beta, 0));
        assertTrue(new File(pluginDir, "upgrade.yml.old1").isFile());
        assertTrue(new File(pluginDir, "upgrade.yml.old2").isFile());

        // Preserves @config reference when setting a FactoryValue over a reference slot
        upgraded.set(SampleKeys.vert, new TestShape("LINEAR", 64));
        upgraded.save();
        RtpYamlConfig onDisk = new RtpYamlConfig(cfgFile);
        onDisk.loadWithComments();
        assertEquals("@config", onDisk.getString("vert"));
        assertEquals("Custom user comment", onDisk.getComment("alpha").trim());
        assertEquals("Default beta comment", onDisk.getComment("beta").trim());

        // Corrupt file quarantine
        File corrupt = new File(pluginDir, "corrupt.yml");
        Files.writeString(corrupt.toPath(), "alpha: [\n  - unclosed\n", StandardCharsets.UTF_8);
        ConfigParser<SampleKeys> healed = new ConfigParser<SampleKeys>(
                SampleKeys.class, "corrupt", "1.0", pluginDir, cache) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nalpha: 55\n".getBytes(StandardCharsets.UTF_8));
            }
        };
        assertEquals(55, healed.getConfigValue(SampleKeys.alpha, 0));
        File[] quarantined = pluginDir.listFiles((d, n) -> n.startsWith("corrupt.yml.corrupt-"));
        assertNotNull(quarantined);
        assertTrue(quarantined.length >= 1);
    }

    @Test
    @DisplayName("ConfigParser locale switch migration, subpathed parser, and legacy lang mirror purge")
    void localeSwitchAndSubpathedParser() throws IOException {
        // Legacy lang mirror directory should be purged on auto-resolved loadLangFile
        File legacyLangDir = new File(pluginDir, "lang/es");
        assertTrue(legacyLangDir.mkdirs());
        Files.writeString(new File(legacyLangDir, "test.lang.yml").toPath(), "alpha: alfa\n", StandardCharsets.UTF_8);

        // Set up classpath with English baseline and Spanish translation
        Path jarRoot = tempDir.resolve("cp");
        Files.createDirectories(jarRoot.resolve("lang/es/advanced"));
        Files.createDirectories(jarRoot.resolve("advanced"));
        Files.writeString(jarRoot.resolve("advanced/sub.yml"),
                "version: \"1.0\"\nalpha: \"hello\"\nbeta: \"world\"\n", StandardCharsets.UTF_8);
        Files.writeString(jarRoot.resolve("advanced/.sub.lang.yml"),
                "alpha: alpha\nbeta: beta\nversion: version\n", StandardCharsets.UTF_8);
        Files.writeString(jarRoot.resolve("lang/es/advanced/sub.yml"),
                "version: \"1.0\"\nalfa: \"hola\"\nbeta_es: \"mundo\"\n", StandardCharsets.UTF_8);
        Files.writeString(jarRoot.resolve("lang/es/advanced/.sub.lang.yml"),
                "alpha: alfa\nbeta: beta_es\nversion: version\n", StandardCharsets.UTF_8);

        // Write English file on disk with one customized value (beta = custom) and one default (alpha = hello)
        File subDir = new File(pluginDir, "advanced");
        assertTrue(subDir.mkdirs());
        Files.writeString(new File(subDir, "sub.yml").toPath(),
                "version: \"1.0\"\nalpha: \"hello\"\nbeta: \"custom\"\n", StandardCharsets.UTF_8);

        try (URLClassLoader cl = new URLClassLoader(new URL[]{jarRoot.toUri().toURL()}, getClass().getClassLoader())) {
            ConfigParser<SampleKeys> esParser = new ConfigParser<>(
                    SampleKeys.class, "advanced/sub.yml", "1.0", pluginDir, null, cache, cl, "es");

            assertFalse(new File(pluginDir, "lang").exists(), "Legacy lang/ mirror should be purged");
            assertEquals("hola", esParser.getConfigValue(SampleKeys.alpha, ""));
            assertEquals("custom", esParser.getConfigValue(SampleKeys.beta, ""));
            assertTrue(new File(subDir, "sub.yml.old1").isFile());
        }
    }

    @Test
    @DisplayName("MultiConfigParser directory extraction, addParser, removeParser, addAll, and getParser")
    void multiConfigParserFullLifecycle() throws IOException {
        Path jarRoot = tempDir.resolve("mcp-cp");
        Files.createDirectories(jarRoot.resolve("definitions/regions"));
        Files.writeString(jarRoot.resolve("definitions/regions/default.yml"),
                "version: \"1.0\"\nalpha: 100\n", StandardCharsets.UTF_8);
        Files.writeString(jarRoot.resolve("definitions/regions/nether.yml"),
                "version: \"1.0\"\nalpha: 200\n", StandardCharsets.UTF_8);

        try (URLClassLoader cl = new URLClassLoader(new URL[]{jarRoot.toUri().toURL()}, getClass().getClassLoader())) {
            MultiConfigParser<SampleKeys> mcp = new MultiConfigParser<>(
                    SampleKeys.class, "regions", "1.0", pluginDir, cl, "definitions/regions", "en");

            assertEquals(pluginDir, mcp.getMainDirectory());
            assertEquals(cl, mcp.getClassLoader());
            Set<String> names = mcp.listParsers();
            assertTrue(names.contains("default"));
            assertTrue(names.contains("nether"));

            ConfigParser<SampleKeys> def = mcp.getParser(null);
            assertNotNull(def);
            assertEquals(100, def.getConfigValue(SampleKeys.alpha, 0));

            // Seeded addParser(name, fromName)
            mcp.addParser("custom_nether", "nether");
            ConfigParser<SampleKeys> custom = mcp.getParser("custom_nether");
            assertNotNull(custom);

            // addAll and removeParser
            mcp.addAll("extra1", "extra2");
            assertTrue(mcp.listParsers().contains("extra1"));
            mcp.removeParser("extra1.yml");
            assertFalse(mcp.configParserFactory.contains("EXTRA1.YML"));

            // Global dynamic predicate
            MultiConfigParser.setDefaultCanCreateDynamicParser("DYNAMIC_WORLD"::equalsIgnoreCase);
            assertNotNull(MultiConfigParser.getDefaultCanCreateDynamicParser());
            mcp.setCanCreateDynamicParser(MultiConfigParser.getDefaultCanCreateDynamicParser());
            ConfigParser<SampleKeys> dyn = mcp.getParser("dynamic_world");
            assertNotNull(dyn);
            assertTrue(mcp.configParserFactory.contains("DYNAMIC_WORLD.YML"));
        }
    }
}
