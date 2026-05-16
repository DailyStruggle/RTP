package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigParserUpdateTest {

    @TempDir
    Path tempDir;

    private File pluginDir;
    private YamlFileDatabase db;

    @BeforeEach
    void setUp() throws IOException {
        pluginDir = tempDir.resolve("RTP").toFile();
        pluginDir.mkdirs();

        // Mock RTP.serverAccessor for ConfigParser.saveResource
        RTPServerAccessor mockAccessor = mock(RTPServerAccessor.class);
        when(mockAccessor.getPluginDirectory()).thenReturn(pluginDir);
        RTP.serverAccessor = mockAccessor;

        db = new YamlFileDatabase(pluginDir);
        db.connect();
    }

    enum TestKeys {
        teleportDelay,
        version
    }

    @Test
    void update_preservesPreexistingValues() throws IOException {
        // 1. Create a config file with version 1.0 and a custom value
        File configFile = new File(pluginDir, "config.yml");
        Files.writeString(configFile.toPath(), "version: \"1.0\"\nteleportDelay: 99\n");

        // 2. Initialize ConfigParser with version 2.0 (triggers update)
        // Overriding getResourceFromJar to simulate a JAR resource for the "new" version
        ConfigParser<TestKeys> parser = new ConfigParser<TestKeys>(
                TestKeys.class,
                "config",
                "2.0",
                pluginDir,
                db
        ) {
            @Override
            public java.io.InputStream getResourceFromJar(String filename) {
                if (filename.endsWith("config.yml")) {
                    return new java.io.ByteArrayInputStream("version: \"2.0\"\nteleportDelay: 2\nnewKey: \"newValue\"".getBytes());
                }
                return null;
            }
        };

        // 3. Check if teleportDelay is still 99
        Object value = parser.getConfigValue(TestKeys.teleportDelay, null);
        assertEquals(99, ((Number)value).intValue(), "Preexisting value should be preserved after update");
    }

    @Test
    void update_preservesSections() throws IOException {
        // 1. Create a config file with version 1.0 and a custom section value
        File configFile = new File(pluginDir, "config.yml");
        Files.writeString(configFile.toPath(),
            "version: \"1.0\"\n" +
            "shape:\n" +
            "  name: \"circle\"\n" +
            "  radius: 500\n" +
            "vert:\n" +
            "  min-y: 64\n"
        );

        // 2. Initialize ConfigParser with version 2.0
        ConfigParser<TestKeys> parser = new ConfigParser<TestKeys>(
                TestKeys.class,
                "config",
                "2.0",
                pluginDir,
                db
        ) {
            @Override
            public java.io.InputStream getResourceFromJar(String filename) {
                if (filename.endsWith("config.yml")) {
                    return new java.io.ByteArrayInputStream(
                        ("version: \"2.0\"\n" +
                        "shape:\n" +
                        "  name: \"square\"\n" +
                        "  radius: 10\n" +
                        "vert:\n" +
                        "  min-y: 0\n" +
                        "  max-y: 255\n").getBytes()
                    );
                }
                return null;
            }
        };

        // 3. Check preserved values
        // Note: we need to access via RtpYamlConfig because ConfigParser uses an Enum-based Map for its own data
        // and we haven't defined shape/vert in TestKeys enum for this test.
        // But the update() method operates on the RtpYamlConfig.
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig RtpYamlConfig = new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(new File(pluginDir, "config.yml"));
        RtpYamlConfig.load();

        assertEquals("circle", RtpYamlConfig.getString("shape.name"), "Section value 'shape.name' should be preserved");
        assertEquals(500, RtpYamlConfig.getInt("shape.radius"), "Section value 'shape.radius' should be preserved");
        assertEquals(64, RtpYamlConfig.getInt("vert.min-y"), "Section value 'vert.min-y' should be preserved");
        assertEquals(255, RtpYamlConfig.getInt("vert.max-y"), "New default value 'vert.max-y' should be added");
    }

    @Test
    void update_preservesCustomKeysInSections() throws IOException {
        // 1. Create a config file with a custom key in a section that is NOT in the default
        File configFile = new File(pluginDir, "config.yml");
        Files.writeString(configFile.toPath(),
            "version: \"1.0\"\n" +
            "shape:\n" +
            "  custom-param: \"custom\"\n"
        );

        // 2. Initialize ConfigParser with version 2.0
        ConfigParser<TestKeys> parser = new ConfigParser<TestKeys>(
                TestKeys.class,
                "config",
                "2.0",
                pluginDir,
                db
        ) {
            @Override
            public java.io.InputStream getResourceFromJar(String filename) {
                if (filename.endsWith("config.yml")) {
                    return new java.io.ByteArrayInputStream(
                        ("version: \"2.0\"\n" +
                        "shape:\n" +
                        "  name: \"square\"\n").getBytes()
                    );
                }
                return null;
            }
        };

        // 3. Check if custom-param is preserved
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig RtpYamlConfig = new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(new File(pluginDir, "config.yml"));
        RtpYamlConfig.load();

        assertEquals("custom", RtpYamlConfig.getString("shape.custom-param"), "Custom key 'shape.custom-param' should be preserved");
        assertEquals("square", RtpYamlConfig.getString("shape.name"), "Default key 'shape.name' should be added");
    }

    @Test
    void update_preservesComments() throws IOException {
        // 1. Create a config file with comments
        File configFile = new File(pluginDir, "config.yml");
        Files.writeString(configFile.toPath(),
            "version: \"1.0\"\n" +
            "# This is a preserved comment\n" +
            "teleportDelay: 99\n"
        );

        // 2. Initialize ConfigParser with version 2.0
        ConfigParser<TestKeys> parser = new ConfigParser<TestKeys>(
                TestKeys.class,
                "config",
                "2.0",
                pluginDir,
                db
        ) {
            @Override
            public java.io.InputStream getResourceFromJar(String filename) {
                if (filename.endsWith("config.yml")) {
                    return new java.io.ByteArrayInputStream(
                        ("version: \"2.0\"\n" +
                        "# This is a new comment from default\n" +
                        "teleportDelay: 2\n" +
                        "# New key comment\n" +
                        "newKey: \"val\"").getBytes()
                    );
                }
                return null;
            }
        };

        // 3. Verify comments in the final file
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig RtpYamlConfig = new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(new File(pluginDir, "config.yml"));
        RtpYamlConfig.loadWithComments();

        String comment = RtpYamlConfig.getComment("teleportDelay");
        assertEquals("This is a preserved comment", comment.trim(), "User comment should be preserved");

        String newComment = RtpYamlConfig.getComment("newKey");
        assertEquals("New key comment", newComment.trim(), "New comment from defaults should be added");
    }

    @Test
    void testAllConfigFiles() throws IOException {
        // Test all core config files defined in Configs.java
        String[] configFiles = {"config.yml", "messages.yml", "economy.yml", "performance.yml", "logging.yml", "safety.yml"};

        for (String fileName : configFiles) {
            String baseName = fileName.replace(".yml", "");
            File file = new File(pluginDir, fileName);
            Files.writeString(file.toPath(), "version: \"1.0\"\nuserValue: \"custom\"\n");

            ConfigParser<TestKeys> parser = new ConfigParser<TestKeys>(
                    TestKeys.class,
                    baseName,
                    "2.0",
                    pluginDir,
                    db
            ) {
                @Override
                public java.io.InputStream getResourceFromJar(String resName) {
                    return new java.io.ByteArrayInputStream("version: \"2.0\"\ndefaultValue: \"default\"".getBytes());
                }
            };

            io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig RtpYamlConfig = new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(file);
            RtpYamlConfig.load();
            assertEquals("2.0", RtpYamlConfig.getString("version"), "Version should be updated for " + fileName);
            assertEquals("custom", RtpYamlConfig.getString("userValue"), "User value should be preserved for " + fileName);
            assertEquals("default", RtpYamlConfig.getString("defaultValue"), "Default value should be added for " + fileName);
        }
    }
}
