package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.YamlFileCache;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone unit tests for {@link YamlFileCache}, {@link ConfigParser}, {@link MultiConfigParser},
 * {@link Factory}, and {@link FactoryValue} inside {@code api/yaml-api} without {@code rtp-core}.
 */
class StandaloneConfigFrameworkTest {

    @TempDir
    Path tempDir;

    enum MyConfigKeys {
        teleportDelay,
        maxCacheSize,
        inheritedRadius,
        shape,
        version
    }

    enum ShapeKeys {
        mode,
        radius
    }

    static final class CustomShape extends FactoryValue<ShapeKeys> {
        CustomShape(String name, int radius) {
            super(ShapeKeys.class, name);
            set(ShapeKeys.mode, "ACCUMULATED");
            set(ShapeKeys.radius, radius);
        }
    }

    enum MyRegionKeys {
        world,
        radius,
        version
    }

    @AfterEach
    void resetHooks() {
        ConfigParser.setDefaultReferenceResolver(null);
        ConfigParser.setSectionFactoryResolver(null);
        MultiConfigParser.setDefaultCanCreateDynamicParser(null);
        FactoryValue.setPluginDirectorySupplier(null);
    }

    @Test
    void yamlFileCache_cachesByMtimeAndRecordsParseErrors() throws IOException {
        File dir = tempDir.toFile();
        File valid = new File(dir, "sample.yml");
        Files.writeString(valid.toPath(), "teleportDelay: 5s\nversion: 1.0\n");

        File broken = new File(dir, "broken.yml");
        Files.writeString(broken.toPath(), "broken: [unclosed\n  - : :\n\t");

        YamlFileCache cache = new YamlFileCache(dir);
        assertEquals(dir, cache.directory());

        Map<String, RtpYamlConfig> first = cache.connect();
        assertTrue(first.containsKey("sample.yml"));
        assertFalse(first.containsKey("broken.yml"));
        assertTrue(cache.lastParseErrors.containsKey("broken.yml"));

        RtpYamlConfig cachedDoc = first.get("sample.yml");
        Map<String, RtpYamlConfig> second = cache.connect();
        assertSame(cachedDoc, second.get("sample.yml"), "Unmodified file should be served from mtime cache");

        cachedDoc.set("teleportDelay", "10s");
        cache.disconnect(second);
        assertTrue(Files.readString(valid.toPath()).contains("10s"));
    }

    @Test
    void configParser_standaloneSingleFileAndPluggableHooks() throws IOException {
        File dir = tempDir.toFile();
        FactoryValue.setPluginDirectorySupplier(() -> dir);

        File configFile = new File(dir, "myconfig.yml");
        Files.writeString(
                configFile.toPath(),
                "teleportDelay: 2m30s\n"
                        + "maxCacheSize: 64MB\n"
                        + "inheritedRadius: \"@defaults\"\n"
                        + "shape:\n"
                        + "  name: CIRCLE\n"
                        + "  mode: ACCUMULATED\n"
                        + "  radius: 100\n"
                        + "version: 1.0\n");

        ConfigParser.setDefaultReferenceResolver(
                (raw, settingName, fallback) ->
                        "@defaults".equals(raw) && "inheritedRadius".equals(settingName) ? 512 : fallback);

        Factory<CustomShape> shapeFactory = new Factory<>();
        shapeFactory.add("CIRCLE", new CustomShape("CIRCLE", 100));
        shapeFactory.add("SQUARE", new CustomShape("SQUARE", 250));
        ConfigParser.setSectionFactoryResolver(
                (sectionName, valueName) ->
                        "shape".equals(sectionName) ? shapeFactory.getOrDefault(valueName) : null);

        ConfigParser<MyConfigKeys> parser =
                new ConfigParser<>(MyConfigKeys.class, "myconfig.yml", "1.0", dir);

        assertEquals(150L, parser.getDuration(MyConfigKeys.teleportDelay, TemporalUnit.SECOND, TemporalUnit.SECOND, 0L));
        assertEquals(64L * 1000L * 1000L, parser.getDataSize(MyConfigKeys.maxCacheSize, 0L));
        assertEquals(512, parser.getConfigValue(MyConfigKeys.inheritedRadius, 64));

        parser.set(MyConfigKeys.shape, "SQUARE");
        parser.save();

        Object shapeVal = parser.getData(MyConfigKeys.shape);
        assertInstanceOf(CustomShape.class, shapeVal);
        assertEquals(250, ((CustomShape) shapeVal).getNumber(ShapeKeys.radius, 0).intValue());
        assertEquals(250, parser.getYamlRoot().getInt("shape.radius"));
        assertFalse(ConfigBackups.listBaks(configFile).isEmpty());
    }

    @Test
    void multiConfigParser_standaloneDirectoryAndDynamicPredicate() throws IOException {
        File dir = tempDir.toFile();
        File regionsDir = new File(dir, "regions");
        assertTrue(regionsDir.mkdirs());
        Files.writeString(
                new File(regionsDir, "default.yml").toPath(),
                "world: overworld\nradius: 1000\nversion: 1.0\n");

        MultiConfigParser<MyRegionKeys> regions =
                new MultiConfigParser<>(MyRegionKeys.class, "regions", "1.0", dir, "regions");

        // Dynamic creation disabled by default: unknown entry falls back to DEFAULT.YML without registering
        ConfigParser<MyRegionKeys> fallback = regions.getParser("nether");
        assertNotNull(fallback);
        assertFalse(regions.configParserFactory.contains("NETHER.YML"));

        // Enable dynamic parser creation for "nether"
        regions.setCanCreateDynamicParser("NETHER"::equalsIgnoreCase);
        ConfigParser<MyRegionKeys> created = regions.getParser("nether");
        assertNotNull(created);
        assertTrue(regions.configParserFactory.contains("NETHER.YML"));
        assertEquals(1000, created.getNumber(MyRegionKeys.radius, 0).intValue());
    }
}
