package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AbstractForeignConfigImporter Deep Branch Tests")
class AbstractForeignConfigImporterTest {

    static class DummyForeignConfigImporter extends AbstractForeignConfigImporter {
        @Override
        public String sourceName() {
            return "dummy";
        }

        @Override
        public List<String> directoryAliases() {
            return List.of("DummyPlugin");
        }

        @Override
        public List<String> indicatorFiles() {
            return List.of("config.yml");
        }

        @Override
        public boolean canImport(Path directory) {
            return directory != null && Files.exists(directory.resolve("config.yml"));
        }

        @Override
        public ImportResult importConfiguration(Path sourceDirectory, Path destinationDirectory, boolean overwrite) {
            List<String> mapped = new ArrayList<>();
            List<Path> written = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            RtpYamlConfig config = loadYamlSafe(sourceDirectory.resolve("config.yml"), warnings);
            if (config != null) {
                mirrorDatabaseConfig(config, destinationDirectory, overwrite, mapped, written, warnings);
                mirrorEffectsConfig(config, destinationDirectory, overwrite, mapped, written, warnings);
            }
            return ImportResult.success(sourceName(), written, warnings, mapped);
        }
    }

    @Test
    @DisplayName("Test normalizeKey and findValueFuzzy variants")
    void testFuzzyMatching() {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();

        assertEquals("minradius", AbstractForeignConfigImporter.normalizeKey("min-radius"));
        assertEquals("minradius", AbstractForeignConfigImporter.normalizeKey("min_radius"));
        assertEquals("minradius", AbstractForeignConfigImporter.normalizeKey("Min.Radius"));
        assertEquals("", AbstractForeignConfigImporter.normalizeKey(null));

        RtpYamlConfig yaml = RtpYamlConfig.parse("""
                cooldown_time: 42
                Max-Distance: 1000
                Use_SSL: true
                nested:
                  sub_key: "found"
                """);

        assertEquals(42, importer.findValueFuzzy(yaml, "cooldown-time"));
        assertEquals(1000, importer.findValueFuzzy(yaml, "maxdistance"));
        assertEquals(true, importer.findValueFuzzy(yaml, "usessl"));

        RtpYamlSection nested = importer.getSectionCaseInsensitive(yaml, "Nested");
        assertNotNull(nested);
        assertEquals("found", importer.getStringCaseInsensitive(nested, "default", "sub-key"));
    }

    @Test
    @DisplayName("Test type safe getters and fallback behavior")
    void testTypeSafeGetters() {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        RtpYamlConfig yaml = RtpYamlConfig.parse("""
                str_val: "hello"
                int_str: "123"
                int_num: 456
                long_str: "9999999999"
                long_num: 8888888888
                double_str: "3.1415"
                double_num: 2.718
                bool_str_yes: "yes"
                bool_str_0: "0"
                bool_val: true
                list_val:
                  - "item1"
                  - "item2"
                """);

        assertEquals("hello", importer.getStringCaseInsensitive(yaml, "def", "str_val"));
        assertEquals("def", importer.getStringCaseInsensitive(yaml, "def", "missing_key"));

        assertEquals(123, importer.getIntCaseInsensitive(yaml, 0, "int_str"));
        assertEquals(456, importer.getIntCaseInsensitive(yaml, 0, "int_num"));
        assertEquals(99, importer.getIntCaseInsensitive(yaml, 99, "missing"));

        assertEquals(9999999999L, importer.getLongCaseInsensitive(yaml, 0L, "long_str"));
        assertEquals(8888888888L, importer.getLongCaseInsensitive(yaml, 0L, "long_num"));

        assertEquals(3.1415, importer.getDoubleCaseInsensitive(yaml, 0.0, "double_str"), 0.0001);
        assertEquals(2.718, importer.getDoubleCaseInsensitive(yaml, 0.0, "double_num"), 0.0001);

        assertTrue(importer.getBooleanCaseInsensitive(yaml, false, "bool_str_yes"));
        assertFalse(importer.getBooleanCaseInsensitive(yaml, true, "bool_str_0"));
        assertTrue(importer.getBooleanCaseInsensitive(yaml, false, "bool_val"));

        List<String> list = importer.getStringListCaseInsensitive(yaml, "list_val");
        assertEquals(List.of("item1", "item2"), list);
        assertTrue(importer.getStringListCaseInsensitive(yaml, "missing").isEmpty());
    }

    @Test
    @DisplayName("Test region and world YAML creation")
    void testRegionAndWorldYamlCreation() {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        RtpYamlConfig regYaml = importer.createRegionYaml("test_reg", "world", "SQUARE_NORMAL", 10, 500, 0, 0, 25.0, List.of("ocean", "desert"));
        assertNotNull(regYaml);
        assertEquals("world", regYaml.getString("world"));
        assertEquals(25.0, regYaml.getDouble("price"));
        assertEquals("SQUARE_NORMAL", regYaml.getString("shape.name"));
        assertEquals(500, regYaml.getInt("shape.radius"));
        assertEquals(10, regYaml.getInt("shape.centerRadius"));
        assertEquals(List.of("ocean", "desert"), regYaml.getStringList("biomes.list"));

        RtpYamlConfig worldYaml = importer.createWorldYaml("test_reg");
        assertNotNull(worldYaml);
        assertEquals("test_reg", worldYaml.getString("region"));
        assertEquals("test_reg", worldYaml.getString("name"));
    }

    @Test
    @DisplayName("Test database mirroring with MySQL, Postgres, and SQLite")
    void testMirrorDatabaseVariants(@TempDir Path tempDir) throws IOException {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();

        String yamlText = """
                Database:
                  Type: "MySQL"
                  Host: "db.internal"
                  Port: 3307
                  Database: "minecraft_rtp"
                  Username: "admin"
                  Password: "secret"
                  UseSSL: true
                Effects:
                  Sounds:
                    Enabled: true
                    Sound: "ENTITY_PLAYER_LEVELUP"
                    Volume: 0.8
                    Pitch: 1.2
                  Title:
                    Enabled: true
                    Title: "Welcome!"
                    Subtitle: "Teleported"
                    FadeIn: 5
                    Stay: 40
                    FadeOut: 10
                  ActionBar:
                    Enabled: true
                    Message: "Enjoy your spot"
                """;
        Path src = tempDir.resolve("source");
        Files.createDirectories(src);
        Files.writeString(src.resolve("config.yml"), yamlText);

        Path dest = tempDir.resolve("dest");
        ImportResult res = importer.importConfiguration(src, dest, true);
        assertTrue(res.isSuccess());
        assertFalse(res.getWrittenFiles().isEmpty());
        assertTrue(res.getMappedEntities().stream().anyMatch(m -> m.contains("Database")));

        Path dbFile = dest.resolve("advanced/database.yml");
        assertTrue(Files.exists(dbFile));
        String dbContent = Files.readString(dbFile).toLowerCase();
        assertTrue(dbContent.contains("mysql"));
        assertTrue(dbContent.contains("db.internal"));
    }
}
