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

    @Test
    @DisplayName("Test writeConfigFile overwrite and backup mechanics")
    void testWriteConfigFileWithBackups(@TempDir Path tempDir) throws IOException {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        Path target = tempDir.resolve("test.yml");
        List<Path> created = new ArrayList<>();
        List<Path> backedUp = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Initial write
        boolean ok1 = importer.writeConfigFile(target, "initial: true", false, created, backedUp, warnings);
        assertTrue(ok1);
        assertEquals(1, created.size());
        assertTrue(backedUp.isEmpty());
        assertTrue(warnings.isEmpty());

        // 2. Write with overwrite=false (should fail with warning)
        boolean ok2 = importer.writeConfigFile(target, "second: true", false, created, backedUp, warnings);
        assertFalse(ok2);
        assertFalse(warnings.isEmpty());

        // 3. Write with overwrite=true (should backup and update)
        boolean ok3 = importer.writeConfigFile(target, "overwritten: true", true, created, backedUp, warnings);
        assertTrue(ok3);
        assertEquals(1, backedUp.size());
        assertTrue(Files.exists(backedUp.get(0)));
        assertEquals("overwritten: true", Files.readString(target));
    }

    @Test
    void testImportResultMethods() {
        ImportResult fail = ImportResult.failure("dummy", List.of("err1"), List.of("warn1"));
        assertFalse(fail.isSuccess());
        assertEquals("dummy", fail.getSourceName());
        assertEquals(List.of("err1"), fail.getErrors());
        assertEquals(List.of("warn1"), fail.getWarnings());
        assertTrue(fail.getWrittenFiles().isEmpty());
        assertTrue(fail.getMappedEntities().isEmpty());
        assertTrue(fail.toString().contains("dummy"));
    }

    @Test
    void testHelperMethodsAndFallbacks() {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        RtpYamlConfig cfg = RtpYamlConfig.parse("""
            num_str: "123.45"
            bool_yes: "yes"
            bool_no: "no"
            bool_one: "1"
            bool_zero: "0"
            list_key:
              - "item1"
              - null
              - "item2"
            bad_num: "not_a_number"
            """);

        assertEquals(123.45, importer.getDoubleCaseInsensitive(cfg, 0.0, "num_str"));
        assertEquals(99.0, importer.getDoubleCaseInsensitive(cfg, 99.0, "missing", "bad_num"));
        assertEquals(0.0, importer.getDoubleCaseInsensitive(null, 0.0, "key"));

        assertTrue(importer.getBooleanCaseInsensitive(cfg, false, "bool_yes"));
        assertTrue(importer.getBooleanCaseInsensitive(cfg, false, "bool_one"));
        assertFalse(importer.getBooleanCaseInsensitive(cfg, true, "bool_no"));
        assertFalse(importer.getBooleanCaseInsensitive(cfg, true, "bool_zero"));
        assertTrue(importer.getBooleanCaseInsensitive(null, true, "key"));

        List<String> list = importer.getStringListCaseInsensitive(cfg, "list_key");
        assertEquals(List.of("item1", "item2"), list);
        assertTrue(importer.getStringListCaseInsensitive(null, "key").isEmpty());
        assertTrue(importer.getStringListCaseInsensitive(cfg, "missing").isEmpty());

        // createRegionYaml with NORMAL shape and biomes
        RtpYamlConfig regYaml = importer.createRegionYaml("test_reg", "world", "CIRCLE_NORMAL", 100, 1000, 0, 0, 25.0, List.of("OCEAN"));
        assertNotNull(regYaml);
        assertEquals("test_reg", regYaml.getString("displayName").replace("&a", ""));
        assertEquals(0.5, regYaml.getDouble("shape.mean"));
    }

    @Test
    void testNormalizeKeyAndSectionMethods() {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        RtpYamlConfig cfg = RtpYamlConfig.parse("""
            root_section:
              sub_section:
                int_val: 42
                long_val: 10000000000
                str_val: "hello"
                bool_val: true
            """);

        RtpYamlSection root = importer.getSectionCaseInsensitive(cfg, "root-section");
        assertNotNull(root);
        RtpYamlSection sub = importer.getSectionCaseInsensitive(root, "sub_section");
        assertNotNull(sub);

        assertTrue(importer.containsCaseInsensitive(sub, "int_val"));
        assertFalse(importer.containsCaseInsensitive(sub, "missing"));
        assertFalse(importer.containsCaseInsensitive(null, "missing"));

        assertEquals(42, importer.getIntCaseInsensitive(sub, 0, "int_val"));
        assertEquals(99, importer.getIntCaseInsensitive(sub, 99, "missing"));
        assertEquals(99, importer.getIntCaseInsensitive(null, 99, "missing"));

        assertEquals(10000000000L, importer.getLongCaseInsensitive(sub, 0L, "long_val"));
        assertEquals(55L, importer.getLongCaseInsensitive(sub, 55L, "missing"));
        assertEquals(55L, importer.getLongCaseInsensitive(null, 55L, "missing"));

        assertEquals("hello", importer.getStringCaseInsensitive(sub, "def", "str_val"));
        assertEquals("def", importer.getStringCaseInsensitive(sub, "def", "missing"));
        assertEquals("def", importer.getStringCaseInsensitive(null, "def", "missing"));
    }

    @Test
    void testMirrorZonesConfig(@TempDir Path tempDir) throws IOException {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        Path src = tempDir.resolve("src");
        Path dst = tempDir.resolve("dst");
        Files.createDirectories(src);
        Files.createDirectories(dst);

        Files.writeString(src.resolve("rtp_zones.yml"), """
            zones:
              lobby_portal:
                world: "world"
                type: "PORTAL"
                pos1:
                  x: 10
                  y: 60
                  z: 10
                pos2:
                  x: 20
                  y: 70
                  z: 20
                cooldown: 15
                interval: 2
                region: "spawn"
              jump_pad:
                type: "PRESSURE_PLATE"
                x1: 50
                y1: 64
                z1: 50
                x2: 52
                y2: 65
                z2: 52
            """);

        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        importer.mirrorZonesConfig(src, dst, true, mapped, written, warnings, errors);
        assertEquals(2, mapped.size());
        assertEquals(2, written.size());
        assertTrue(errors.isEmpty());

        // Call again with overwrite=false to test warning branch
        importer.mirrorZonesConfig(src, dst, false, mapped, written, warnings, errors);
        assertFalse(warnings.isEmpty());
    }

    @Test
    void testMirrorGlobalSettingsAllOptions(@TempDir Path tempDir) {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        Path dst = tempDir.resolve("dst");

        AbstractForeignConfigImporter.GlobalSettings settings = new AbstractForeignConfigImporter.GlobalSettings(
            30, 5, 10, true, true, true, true, 20, 100, 50
        );

        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        importer.updateDestinationConfig(dst, settings, mapped, written, warnings);
        assertTrue(Files.exists(dst.resolve("config.yml")));
        assertTrue(Files.exists(dst.resolve("performance.yml")));
        assertTrue(mapped.size() >= 8);
    }

    @Test
    void testDiscoveredWorldRegionAndGlobalSettingsBuilder() {
        AbstractForeignConfigImporter.DiscoveredWorldRegion r1 =
            new AbstractForeignConfigImporter.DiscoveredWorldRegion("r1", "world", "CIRCLE", 10, 100, 0, 0, 15.0);
        assertEquals(64, r1.minY());
        assertEquals(320, r1.maxY());
        assertEquals(-1, r1.cacheCap());

        AbstractForeignConfigImporter.DiscoveredWorldRegion r2 =
            new AbstractForeignConfigImporter.DiscoveredWorldRegion("r2", "world", "CIRCLE", 10, 100, 0, 0, 15.0, List.of("PLAINS"));
        assertEquals(List.of("PLAINS"), r2.biomes());

        AbstractForeignConfigImporter.DiscoveredWorldRegion r3 =
            new AbstractForeignConfigImporter.DiscoveredWorldRegion("r3", "world", "CIRCLE", 10, 100, 0, 0, 50, 200, 15.0, List.of("PLAINS"));
        assertEquals(50, r3.minY());
        assertEquals(200, r3.maxY());

        AbstractForeignConfigImporter.DiscoveredWorldRegion r4 =
            new AbstractForeignConfigImporter.DiscoveredWorldRegion("r4", "world", "CIRCLE", 10, 100, 0, 0, 50, 200, 15.0, 500, List.of("PLAINS"));
        assertEquals(500, r4.cacheCap());

        AbstractForeignConfigImporter.GlobalSettings.Builder builder = AbstractForeignConfigImporter.GlobalSettings.builder()
            .cooldown(60)
            .delay(5)
            .lockAfter(10)
            .setAsRespawn(true)
            .rtpOnFirstJoin(true)
            .rtpOnDeath(true)
            .cancelOnMove(true)
            .maxAttempts(25)
            .cacheCap(200)
            .queueTargetSize(100);

        AbstractForeignConfigImporter.GlobalSettings built = builder.build();
        assertEquals(60, built.cooldown());
        assertEquals(5, built.delay());
        assertEquals(10, built.lockAfter());
        assertTrue(built.setAsRespawn());
        assertTrue(built.rtpOnFirstJoin());
        assertTrue(built.rtpOnDeath());
        assertTrue(built.cancelOnMove());
        assertEquals(25, built.maxAttempts());
        assertEquals(200, built.cacheCap());
        assertEquals(100, built.queueTargetSize());
    }

    @Test
    void testMirrorEffectsConfigExistingFile(@TempDir Path tempDir) throws IOException {
        DummyForeignConfigImporter importer = new DummyForeignConfigImporter();
        Path src = tempDir.resolve("src");
        Path dst = tempDir.resolve("dst");
        Files.createDirectories(src);
        Files.createDirectories(dst);

        // Create an existing effects.yml in dst
        Files.writeString(dst.resolve("effects.yml"), "version: 1.0\n");

        RtpYamlConfig cfg = RtpYamlConfig.parse("""
            teleport_sound: "ENTITY_ENDERMAN_TELEPORT"
            particles:
              enabled: true
              type: "PORTAL"
              amount: 50
            potions:
              list:
                - "SPEED:200:1"
                - "BLINDNESS:10:0"
            """);

        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        importer.mirrorEffectsConfig(cfg, dst, true, mapped, written, warnings);
        assertTrue(Files.exists(dst.resolve("effects.yml")));
        assertFalse(mapped.isEmpty());
    }
}
