package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiConfigParserCoverageTest {

    @TempDir
    Path tempDir;

    private File pluginDir;

    @SuppressWarnings("java:S115")
    enum TestRegionKeys {
        shape,
        vert,
        radius,
        price,
        version
    }

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
    @DisplayName("MultiConfigParser directory discovery and parser registration")
    void testDirectoryDiscovery() throws IOException {
        File regionsDir = new File(pluginDir, "regions");
        regionsDir.mkdirs();

        Files.writeString(new File(regionsDir, "default.yml").toPath(), "version: \"1.0\"\nradius: 1000\n");
        Files.writeString(new File(regionsDir, "nether.yml").toPath(), "version: \"1.0\"\nradius: 500\n");
        Files.writeString(new File(regionsDir, "old_backup.old1").toPath(), "ignored\n");
        Files.writeString(new File(regionsDir, ".regions.lang.yml").toPath(), "radius: radius\n");

        MultiConfigParser<TestRegionKeys> multiParser = new MultiConfigParser<>(
                TestRegionKeys.class,
                "regions",
                "1.0",
                pluginDir
        );

        Set<String> parsers = multiParser.listParsers();
        assertTrue(parsers.contains("default") || parsers.contains("DEFAULT"));
        assertTrue(parsers.contains("nether") || parsers.contains("NETHER"));
        assertFalse(parsers.contains("old_backup"));
        assertFalse(parsers.contains(".regions.lang"));

        assertEquals(pluginDir, multiParser.getMainDirectory());
        assertNotNull(multiParser.getClassLoader());
    }

    @Test
    @DisplayName("getParser with existing, default fallback, and unknown world lookup")
    void testGetParser() throws IOException {
        File regionsDir = new File(pluginDir, "regions");
        regionsDir.mkdirs();

        Files.writeString(new File(regionsDir, "default.yml").toPath(), "version: \"1.0\"\nradius: 1000\n");
        Files.writeString(new File(regionsDir, "custom.yml").toPath(), "version: \"1.0\"\nradius: 2000\n");

        MultiConfigParser<TestRegionKeys> multiParser = new MultiConfigParser<>(
                TestRegionKeys.class,
                "regions",
                "1.0",
                pluginDir
        );

        // 1. Direct hit
        ConfigParser<TestRegionKeys> custom = multiParser.getParser("custom");
        assertNotNull(custom);
        assertEquals(2000, custom.getConfigValue(TestRegionKeys.radius, 0));

        // 2. Lookup non-existent when world is not found on serverAccessor -> returns default parser
        ConfigParser<TestRegionKeys> fallback = multiParser.getParser("unknown_world_123");
        assertNotNull(fallback);
        assertEquals(1000, fallback.getConfigValue(TestRegionKeys.radius, 0));

        // 3. Lookup when world is present on serverAccessor -> constructs from default
        RTPWorld mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("known_world");
        when(RTP.serverAccessor.getRTPWorld("KNOWN_WORLD")).thenReturn(mockWorld);

        ConfigParser<TestRegionKeys> worldParser = multiParser.getParser("known_world");
        assertNotNull(worldParser);
        assertEquals(1000, worldParser.getConfigValue(TestRegionKeys.radius, 0));
        assertTrue(multiParser.configParserFactory.contains("KNOWN_WORLD.YML"));
    }

    @Test
    @DisplayName("addParser, addAll, and removeParser manipulate underlying factory")
    void testAddAndRemoveParser() throws IOException {
        File regionsDir = new File(pluginDir, "regions");
        regionsDir.mkdirs();
        Files.writeString(new File(regionsDir, "default.yml").toPath(), "version: \"1.0\"\nradius: 1000\n");

        MultiConfigParser<TestRegionKeys> multiParser = new MultiConfigParser<>(
                TestRegionKeys.class,
                "regions",
                "1.0",
                pluginDir
        );

        // addParser by name
        multiParser.addParser("wild");
        assertTrue(multiParser.configParserFactory.contains("WILD.YML"));

        // addParser with fromName
        multiParser.addParser("wild2", "wild");
        assertTrue(multiParser.configParserFactory.contains("WILD2.YML"));

        // addParser with invalid fromName falls back to default
        multiParser.addParser("wild3", "nonexistent_source");
        assertTrue(multiParser.configParserFactory.contains("WILD3.YML"));

        // addAll
        multiParser.addAll("alpha", "beta");
        assertTrue(multiParser.configParserFactory.contains("ALPHA.YML"));
        assertTrue(multiParser.configParserFactory.contains("BETA.YML"));

        // removeParser
        multiParser.removeParser("alpha");
        assertFalse(multiParser.configParserFactory.contains("ALPHA.YML"));

        // addParser with existing ConfigParser instance
        ConfigParser<TestRegionKeys> direct = new ConfigParser<>(
                TestRegionKeys.class,
                "direct",
                "1.0",
                regionsDir,
                multiParser.fileDatabase
        ) {
            @Override
            public InputStream getResourceFromJar(String filename) {
                return new ByteArrayInputStream("version: \"1.0\"\nradius: 300\n".getBytes());
            }
        };
        multiParser.addParser(direct);
        assertTrue(multiParser.configParserFactory.contains("direct.yml") || multiParser.configParserFactory.contains("DIRECT.YML"));
    }

    @Test
    @DisplayName("MultiConfigParser constructors with custom sub-directory and locale")
    void testConstructorsWithSubDirAndLocale() throws IOException {
        File defDir = new File(pluginDir, "definitions" + File.separator + "regions");
        defDir.mkdirs();
        Files.writeString(new File(defDir, "default.yml").toPath(), "version: \"1.0\"\nradius: 500\n");

        MultiConfigParser<TestRegionKeys> subMulti = new MultiConfigParser<>(
                TestRegionKeys.class,
                "regions",
                "1.0",
                pluginDir,
                getClass().getClassLoader(),
                "definitions/regions",
                "de"
        );

        assertEquals("definitions/regions", subMulti.directory);
        assertEquals("de", subMulti.locale);
        assertEquals(defDir.getAbsolutePath(), subMulti.myDirectory.getAbsolutePath());

        // MultiConfigParser with 4-args and 5-args constructor variants
        MultiConfigParser<TestRegionKeys> var1 = new MultiConfigParser<>(
                TestRegionKeys.class,
                "regions",
                "1.0",
                pluginDir,
                "definitions/regions"
        );
        assertEquals("definitions/regions", var1.directory);

        MultiConfigParser<TestRegionKeys> var2 = new MultiConfigParser<>(
                TestRegionKeys.class,
                "regions",
                "1.0",
                pluginDir,
                getClass().getClassLoader()
        );
        assertEquals("regions", var2.directory);
    }
}
