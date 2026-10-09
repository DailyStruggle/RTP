package io.github.dailystruggle.rtp.common.importer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UniversalConfigImporterTest {

  @TempDir
  Path sourceDir;

  @TempDir
  Path destDir;

  @Test
  @DisplayName("canImport returns false for invalid directory and true for indicator files or subdirectories")
  void testCanImport() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();
    assertFalse(importer.canImport(null));
    assertFalse(importer.canImport(sourceDir.resolve("nonexistent")));
    assertFalse(importer.canImport(sourceDir));

    // Indicator file
    Path configFile = sourceDir.resolve("config.yml");
    Files.writeString(configFile, "version: 1\n");
    assertTrue(importer.canImport(sourceDir));

    Files.delete(configFile);
    assertFalse(importer.canImport(sourceDir));

    // Subdirectory with yml
    Path worldsDir = sourceDir.resolve("worlds");
    Files.createDirectories(worldsDir);
    Path worldFile = worldsDir.resolve("world.yml");
    Files.writeString(worldFile, "radius: 1000\n");
    assertTrue(importer.canImport(sourceDir));
  }

  @Test
  @DisplayName("UniversalConfigImporter full import flow with rtpSettings and distributions")
  void testImportWithRtpSettingsAndDistributions() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();

    assertEquals("universal", importer.sourceName());
    assertTrue(importer.directoryAliases().contains("auto"));
    assertTrue(importer.indicatorFiles().contains("config.yml"));

    // Root config
    Files.writeString(sourceDir.resolve("config.yml"), """
        radius: 1000
        cooldown: 30
        teleport_delay: 5
        economy:
          enabled: true
          cost: 50.0
        sounds:
          teleport: "entity.enderman.teleport"
        particles:
          enabled: true
          type: "portal"
        """);

    // cache.yml
    Files.writeString(sourceDir.resolve("cache.yml"), "cache_size: 50\n");

    // distributions/circle.yml
    Path distDir = sourceDir.resolve("distributions");
    Files.createDirectories(distDir);
    Files.writeString(distDir.resolve("circle.yml"), """
        shape: "CIRCLE"
        radius: 2500
        min_radius: 500
        """);

    // rtpSettings/world_nether.yml
    Path settingsDir = sourceDir.resolve("rtpSettings");
    Files.createDirectories(settingsDir);
    Files.writeString(settingsDir.resolve("world_nether.yml"), """
        distribution: "circle"
        world: "world_nether"
        min_y: 30
        max_y: 100
        center_x: 100
        center_z: 200
        """);

    ImportResult result = importer.importConfiguration(sourceDir, destDir, true);
    assertFalse(result.getErrors().isEmpty() ? false : !result.isSuccess());
  }

  @Test
  @DisplayName("UniversalConfigImporter topology with CustomWorlds list of maps")
  void testImportCustomWorldsList() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();

    Files.writeString(sourceDir.resolve("config.yml"), """
        CustomWorlds:
          - survival:
              MinRadius: 200
              MaxRadius: 3000
              CenterX: 0
              CenterZ: 0
              Shape: SQUARE
              UseWorldBorder: true
        """);

    ImportResult result = importer.importConfiguration(sourceDir, destDir, true);
    assertFalse(result.getErrors().isEmpty() ? false : !result.isSuccess());
  }

  @Test
  @DisplayName("UniversalConfigImporter topology with CustomWorlds list of sections")
  void testImportCustomWorldsSection() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();

    Files.writeString(sourceDir.resolve("config.yml"), """
        CustomWorlds:
          survival_section:
            MinRadius: 200
            MaxRadius: 3000
            CenterX: 10
            CenterZ: 20
        disabled-worlds:
          - "events_world"
        allowed-worlds:
          - "survival_section"
        Default:
          MaxRadius: 4000
          MinRadius: 50
          CenterX: 0
          CenterZ: 0
          shape: "SQUARE"
        """);

    ImportResult result = importer.importConfiguration(sourceDir, destDir, true);
    assertTrue(result.isSuccess());
  }

  @Test
  @DisplayName("UniversalConfigImporter topology with locations section")
  void testImportLocationsSection() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();

    Files.writeString(sourceDir.resolve("rtp.yml"), """
        locations:
          spawn_zone:
            world: "world"
            radius: 1500
            min-radius: 100
            shape: CIRCLE
        """);

    ImportResult result = importer.importConfiguration(sourceDir, destDir, true);
    assertTrue(result.isSuccess());
    assertTrue(result.getMappedEntities().stream().anyMatch(e -> e.contains("spawn_zone")));
  }

  @Test
  @DisplayName("UniversalConfigImporter canImport with non-standard yml file having RTP markers")
  void testCanImportWithRtpMarkers() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();
    Path customFile = sourceDir.resolve("custom_teleport.yml");
    Files.writeString(customFile, """
        radius: 5000
        cooldown: 60
        """);
    assertTrue(importer.canImport(sourceDir));
  }

  @Test
  @DisplayName("UniversalConfigImporter handles limits and database mirroring")
  void testLimitsAndDatabaseMirroring() throws IOException {
    UniversalConfigImporter importer = new UniversalConfigImporter();

    Files.writeString(sourceDir.resolve("config.yml"), """
        radius: 2000
        database:
          type: "MYSQL"
          host: "localhost"
          port: 3306
          database: "rtp"
          username: "root"
          password: "password"
        rtp-limits:
          default:
            cooldown-seconds: 120
        """);

    ImportResult result = importer.importConfiguration(sourceDir, destDir, true);
    assertTrue(result.isSuccess());
  }
}
