package io.github.dailystruggle.rtp.common.importer.schema;

import io.github.dailystruggle.rtp.common.importer.ImportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GenericSchemaImporter and PluginImportSchema Tests")
public class GenericSchemaImporterTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("schema-importer-test");
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("PluginImportSchema builder constructs complete schema")
    void testSchemaBuilder() {
        PluginImportSchema schema = PluginImportSchema.builder("CustomRTP")
                .aliases("CustomRTP", "custom-rtp")
                .candidateFiles("config.yml", "settings.yml")
                .worldMapping("worlds", PluginImportSchema.Mode.MAP, new PluginImportSchema.FieldAliases(
                        List.of("max-radius"), List.of("min-radius"),
                        List.of("center-x"), List.of("center-z"),
                        List.of("min-y"), List.of("max-y"),
                        List.of("shape"), List.of("price"),
                        List.of("cooldown"), List.of("delay"),
                        List.of("blacklisted-blocks"), List.of("biomes")
                ))
                .effectMapping(List.of("effects.particles"), List.of("effects.sounds"))
                .globalMapping(List.of("global.cooldown"), List.of("global.delay"), List.of("global.max-attempts"))
                .permission("customrtp.use", "rtp.use")
                .build();

        assertNotNull(schema);
        assertEquals("CustomRTP", schema.pluginName());
        assertTrue(schema.directoryAliases().contains("custom-rtp"));
        assertTrue(schema.candidateFiles().contains("settings.yml"));
        assertEquals("worlds", schema.worldMapping().rootPath());
        assertEquals("rtp.use", schema.permissionEquivalences().get("customrtp.use"));
    }

    @Test
    @DisplayName("GenericSchemaImporter discovers candidate files and handles invalid paths")
    void testCanImport() throws IOException {
        PluginImportSchema schema = PluginImportSchema.builder("TestPlugin")
                .candidateFiles("config.yml")
                .build();
        GenericSchemaImporter importer = new GenericSchemaImporter(schema);

        assertFalse(importer.canImport(null));
        assertFalse(importer.canImport(tempDir.resolve("non_existent")));
        assertFalse(importer.canImport(tempDir));

        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "dummy: true\n");
        assertTrue(importer.canImport(tempDir));
        assertEquals("testplugin", importer.sourceName());
        assertEquals(schema, importer.getSchema());
        assertEquals(List.of("TestPlugin"), importer.directoryAliases());
        assertEquals(List.of("config.yml"), importer.indicatorFiles());
    }

    @Test
    @DisplayName("GenericSchemaImporter imports worlds and configurations successfully")
    void testImportConfiguration() throws IOException {
        PluginImportSchema schema = PluginImportSchema.builder("SampleRTP")
                .aliases("SampleRTP")
                .candidateFiles("config.yml")
                .globalMapping(List.of("cooldown"), List.of("delay"), List.of("attempts"))
                .build();

        GenericSchemaImporter importer = new GenericSchemaImporter(schema);

        String yaml = """
                cooldown: 30
                delay: 5
                worlds:
                  world:
                    max-radius: 4000
                    min-radius: 200
                    center:
                      x: 100
                      z: -100
                    min-y: 60
                    max-y: 256
                    shape: SQUARE
                    price: 25.0
                    biomes:
                      - PLAINS
                      - FOREST
                  world_nether:
                    max-radius: 2000
                    min-radius: 50
                    shape: CIRCLE
                """;

        Files.writeString(tempDir.resolve("config.yml"), yaml);

        Path destDir = tempDir.resolve("leaf_rtp_dest");
        Files.createDirectories(destDir);

        ImportResult result = importer.importConfiguration(tempDir, destDir, true);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("samplertp", result.getSourceName());
        assertFalse(result.getWrittenFiles().isEmpty());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("GenericSchemaImporter handles fallback world when no worlds section present")
    void testImportConfigurationFallbackWorld() throws IOException {
        PluginImportSchema schema = PluginImportSchema.builder("FlatRTP")
                .candidateFiles("config.yml")
                .build();

        GenericSchemaImporter importer = new GenericSchemaImporter(schema);

        String yaml = """
                max-radius: 3500
                min-radius: 150
                center-x: 0
                center-z: 0
                shape: RECTANGLE
                price: 10.0
                """;

        Files.writeString(tempDir.resolve("config.yml"), yaml);

        Path destDir = tempDir.resolve("leaf_rtp_dest_fallback");
        Files.createDirectories(destDir);

        ImportResult result = importer.importConfiguration(tempDir, destDir, true);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertFalse(result.getWrittenFiles().isEmpty());
    }

    @Test
    @DisplayName("GenericSchemaImporter fails gracefully on missing candidate files or unparseable yaml")
    void testImportErrors() throws IOException {
        PluginImportSchema schema = PluginImportSchema.builder("BrokenRTP")
                .candidateFiles("config.yml")
                .build();

        GenericSchemaImporter importer = new GenericSchemaImporter(schema);

        Path emptySource = tempDir.resolve("empty_source");
        Files.createDirectories(emptySource);

        ImportResult result = importer.importConfiguration(emptySource, tempDir.resolve("dest"), true);
        assertFalse(result.isSuccess());
        assertFalse(result.getErrors().isEmpty());

        Path brokenFile = emptySource.resolve("config.yml");
        Files.writeString(brokenFile, "unquoted_colons: [unclosed");
        ImportResult result2 = importer.importConfiguration(emptySource, tempDir.resolve("dest"), true);
        assertFalse(result2.isSuccess());
        assertFalse(result2.getErrors().isEmpty());
    }
}
