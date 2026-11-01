package io.github.dailystruggle.rtp.common.importer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Test
  @DisplayName("Foreign YAML: a sequence at its key's indent inside a sequence item parses (BetterRTP PermissionGroup)")
  void nestedIndentlessSequenceParses() {
    String yaml = """
        PermissionGroup:
          Groups:
          - vip:
            - Build_World:
                MaxRadius: 10000
            - Survival_World:
                MaxRadius: 5000
          - vip2:
            - Build_World:
                MaxRadius: 25000
        Blocks:
        - water
        - lava
        After: 1
        """;
    RtpYamlConfig cfg = RtpYamlConfig.parse(ForeignConfigImporter.sanitizeForeignYaml(yaml));
    List<?> groups = cfg.getList("PermissionGroup.Groups");
    assertEquals(2, groups.size());
    List<?> vip = ((RtpYamlSection) groups.get(0)).getList("vip");
    assertEquals(2, vip.size());
    assertEquals(10000, ((RtpYamlSection) vip.get(0)).getInt("Build_World.MaxRadius"));
    assertEquals(5000, ((RtpYamlSection) vip.get(1)).getInt("Survival_World.MaxRadius"));
    List<?> vip2 = ((RtpYamlSection) groups.get(1)).getList("vip2");
    assertEquals(1, vip2.size());
    assertEquals(25000, ((RtpYamlSection) vip2.get(0)).getInt("Build_World.MaxRadius"));
    assertEquals(List.of("water", "lava"), cfg.getStringList("Blocks"));
    assertEquals(1, cfg.getInt("After"));
  }

  @Test
  @DisplayName("Foreign YAML: multi-line plain scalars fold into one value and keep later line numbers")
  void multiLinePlainScalarFolds() {
    String yaml = """
        a: Get the reason or yes output if player can rtp in set
          world
        b: one
          two
          three
        list:
        - first item
          continued
        c: 'quoted'
        """;
    String sanitized = ForeignConfigImporter.sanitizeForeignYaml(yaml);
    RtpYamlConfig cfg = RtpYamlConfig.parse(sanitized);
    assertEquals("Get the reason or yes output if player can rtp in set world", cfg.getString("a"));
    assertEquals("one two three", cfg.getString("b"));
    assertEquals(List.of("first item continued"), cfg.getStringList("list"));
    assertEquals("quoted", cfg.getString("c"));
    assertEquals(yaml.split("\n", -1).length, sanitized.split("\n", -1).length,
        "folding must not shift line numbers in parse errors");
  }

  @Test
  @DisplayName("Foreign YAML: a real parse error names the file and the line in the user's file")
  void invalidForeignYamlWarningPointsToSourceLine() throws IOException {
    Path bad = sourceDir.resolve("broken.yml");
    // The flow sequence unfolds to four lines; the warning must still name source line 4.
    Files.writeString(bad, "a: [1, 2, 3]\nb:\n  c: 2\n    d: 3\n");
    List<String> warnings = new java.util.ArrayList<>();
    assertNull(new UniversalConfigImporter().loadForeignYaml(bad, warnings));
    assertEquals(1, warnings.size());
    String w = warnings.get(0);
    assertTrue(w.contains("broken.yml") && w.contains("line 4"), w);
  }

  /** Imports the live-server BetterRTP fixture into {@code destDir}; fails unless the import succeeds. */
  private ImportResult importBetterRtpFixture() throws IOException {
    for (String f : List.of("config.yml", "placeholders.yml")) {
      try (InputStream in = getClass().getResourceAsStream("/importer/betterrtp/" + f)) {
        assertNotNull(in, f);
        Files.copy(in, sourceDir.resolve(f));
      }
    }
    ImportResult result = new UniversalConfigImporter().importConfiguration(sourceDir, destDir, true);
    assertTrue(result.isSuccess(), String.valueOf(result.getErrors()));
    return result;
  }

  @Test
  @DisplayName("BetterRTP fixture from a live server imports without parse warnings and keeps source region values")
  void betterRtpFixtureImportsCleanly() throws IOException {
    ImportResult result = importBetterRtpFixture();
    assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("parse")),
        String.valueOf(result.getWarnings()));

    // 1. Default world region emitted from Default section
    Path defWorldPath = destDir.resolve("definitions/regions/world_region.yml");
    assertTrue(Files.exists(defWorldPath), "world_region.yml must be emitted from Default section");
    RtpYamlConfig defWorld = RtpYamlConfig.load(defWorldPath.toFile());
    assertEquals("world", defWorld.getString("world"));
    assertEquals(17408, defWorld.getInt("shape.radius"));
    assertEquals(1024, defWorld.getInt("shape.centerRadius"));
    assertEquals(0, defWorld.getInt("vert.minY"));
    assertEquals(320, defWorld.getInt("vert.maxY"));

    // 2. Custom worlds inherit missing keys (e.g. minY: 0) from Default
    RtpYamlConfig cw1 = RtpYamlConfig.load(destDir.resolve("definitions/regions/custom_world_1_region.yml").toFile());
    assertEquals("custom_world_1", cw1.getString("world"));
    assertEquals("SQUARE", cw1.getString("shape.name"));
    assertEquals(1000, cw1.getInt("shape.radius"));
    assertEquals(100, cw1.getInt("shape.centerRadius"));
    assertEquals(0, cw1.getInt("vert.minY"));
    assertEquals(50.0, cw1.getDouble("price"));

    RtpYamlConfig cw2 = RtpYamlConfig.load(destDir.resolve("definitions/regions/other_custom_world_region.yml").toFile());
    assertEquals("CIRCLE", cw2.getString("shape.name"));
    assertEquals(10000, cw2.getInt("shape.radius"));
    assertEquals(150, cw2.getInt("shape.centerRadius"));
    assertEquals(123, cw2.getInt("shape.centerX"));
    assertEquals(-123, cw2.getInt("shape.centerZ"));
    assertEquals(0, cw2.getInt("vert.minY"), "custom world must inherit minY=0 from Default");
  }

  @Test
  @DisplayName("BetterRTP fixture maps biomes, disabled cooldown/delay and reports unmapped keys")
  void betterRtpFixtureMapsSettingsAndReportsUnmappedKeys() throws IOException {
    ImportResult result = importBetterRtpFixture();
    RtpYamlConfig cw2 = RtpYamlConfig.load(destDir.resolve("definitions/regions/other_custom_world_region.yml").toFile());

    // 3. Biome allow-list is written with blacklist: false
    assertFalse(cw2.getBoolean("biomes.blacklist"), "Biomes allow-list must be mapped with blacklist: false");
    assertEquals(List.of("desert", "forest"), cw2.getStringList("biomes.list"));

    // 4. Disabled cooldown & delay are honored
    Path destConfig = destDir.resolve("config.yml");
    assertTrue(Files.exists(destConfig));
    RtpYamlConfig cfg = RtpYamlConfig.load(destConfig.toFile());
    assertEquals(0, cfg.getInt("teleportCooldown"), "disabled cooldown must import as 0, not 600");
    assertEquals(0, cfg.getInt("teleportDelay"), "disabled delay must import as 0, not 5");

    // 5. Unmapped foreign keys trigger informative warning
    assertTrue(result.getWarnings().stream().anyMatch(w ->
        w.contains("Source keys in config.yml were not mapped")
            && w.contains("MaxAttempts")
            && w.contains("BlacklistedBlocks")
            && w.contains("Overrides")
            && w.contains("WorldType")
            && w.contains("PermissionGroup")),
        "Expected unmapped keys warning, got: " + result.getWarnings());
  }

  @Test
  @DisplayName("Biome polarity: blacklisted-biomes is written with blacklist: true, while biomes is written with blacklist: false")
  void biomePolarityRespectsAllowListAndBlacklistKeys() throws IOException {
    String yaml = """
        CustomWorlds:
        - allowed_world:
            Biomes:
            - plains
            - forest
        - blacklisted_world:
            blacklisted-biomes:
            - deep_dark
            - nether_wastes
        """;
    Files.writeString(sourceDir.resolve("config.yml"), yaml);

    ImportResult result = new UniversalConfigImporter().importConfiguration(sourceDir, destDir, true);
    assertTrue(result.isSuccess(), String.valueOf(result.getErrors()));

    RtpYamlConfig allowed = RtpYamlConfig.load(destDir.resolve("definitions/regions/allowed_world_region.yml").toFile());
    assertFalse(allowed.getBoolean("biomes.blacklist"));
    assertEquals(List.of("plains", "forest"), allowed.getStringList("biomes.list"));

    RtpYamlConfig blacklisted = RtpYamlConfig.load(destDir.resolve("definitions/regions/blacklisted_world_region.yml").toFile());
    assertTrue(blacklisted.getBoolean("biomes.blacklist"));
    assertEquals(List.of("deep_dark", "nether_wastes"), blacklisted.getStringList("biomes.list"));
  }

  @Test
  @DisplayName("Foreign YAML: unexpected text including MiniMessage hex gradients, emojis, and quotes with :# parses cleanly")
  void foreignYamlWithUnexpectedTextAndGradientsParsesCleanly() {
    String yaml = """
        chat_message:
          messages:
          - ""
          - "<dark_gray><gradient:#20B2AA:#7FFFD4>Wilderness Teleport</gradient>"
          - "<dark_gray>  <gray>Coordinates:</gray> <color:#7FFFD4><x><gray>, </gray><color:#7FFFD4><y>"
        hologram:
          header: "<gradient:#20B2AA:#7FFFD4><bold>⚡ RTP Zone</bold></gradient>"
          note: "Port: #1234"
          brackets: "syntax: [] and {}"
        real_comment_key: # this comment should be stripped
          nested: 42
        """;
    String sanitized = ForeignConfigImporter.sanitizeForeignYaml(yaml);
    RtpYamlConfig cfg = RtpYamlConfig.parse(sanitized);

    List<?> messages = cfg.getList("chat_message.messages");
    assertEquals(3, messages.size());
    assertEquals("", messages.get(0));
    assertEquals("<dark_gray><gradient:#20B2AA:#7FFFD4>Wilderness Teleport</gradient>", messages.get(1));
    assertEquals("<dark_gray>  <gray>Coordinates:</gray> <color:#7FFFD4><x><gray>, </gray><color:#7FFFD4><y>", messages.get(2));

    assertEquals("<gradient:#20B2AA:#7FFFD4><bold>⚡ RTP Zone</bold></gradient>", cfg.getString("hologram.header"));
    assertEquals("Port: #1234", cfg.getString("hologram.note"));
    assertEquals("syntax: [] and {}", cfg.getString("hologram.brackets"));
    assertEquals(42, cfg.getInt("real_comment_key.nested"));
  }

  @Test
  @DisplayName("Foreign YAML: standalone empty flow collections ({} and []) parse as empty config without warnings")
  void standaloneEmptyFlowCollectionsParseAsEmpty() throws IOException {
    Path mapFile = sourceDir.resolve("display_entities.yml");
    Files.writeString(mapFile, "{}\n# comment\n");
    Path seqFile = sourceDir.resolve("empty_list.yml");
    Files.writeString(seqFile, "[]\n");

    List<String> warnings = new java.util.ArrayList<>();
    UniversalConfigImporter importer = new UniversalConfigImporter();

    RtpYamlConfig mapCfg = importer.loadForeignYaml(mapFile, warnings);
    assertNotNull(mapCfg, "display_entities.yml with '{}' must parse as an empty config");
    assertTrue(warnings.isEmpty(), "expected no warnings for '{}', got: " + warnings);

    RtpYamlConfig seqCfg = importer.loadForeignYaml(seqFile, warnings);
    assertNotNull(seqCfg, "file with '[]' must parse as an empty config");
    assertTrue(warnings.isEmpty(), "expected no warnings for '[]', got: " + warnings);
  }

  @Test
  @DisplayName("JustRTP fixture imports with expected warning on animations.yml and no false warnings on config/holograms")
  void justRtpFixtureImportsWithExpectedWarnings() throws IOException {
    for (String f : List.of("config.yml", "custom_locations.yml", "holograms.yml", "display_entities.yml", "animations.yml")) {
      try (InputStream in = getClass().getResourceAsStream("/importer/justrtp/" + f)) {
        assertNotNull(in, "missing fixture resource: " + f);
        Files.copy(in, sourceDir.resolve(f));
      }
    }

    ImportResult result = new UniversalConfigImporter().importConfiguration(sourceDir, destDir, true);
    assertTrue(result.isSuccess(), String.valueOf(result.getErrors()));

    List<String> warnings = result.getWarnings();
    // animations.yml line 33 contains flow mappings which are unsupported and must be reported accurately
    assertTrue(warnings.stream().anyMatch(w -> w.contains("animations.yml") && w.contains("line 33") && w.contains("flow mappings are not supported")),
        "expected warning for animations.yml line 33, but got: " + warnings);

    // config.yml, holograms.yml, and display_entities.yml must NOT trigger parse warnings
    assertTrue(warnings.stream().noneMatch(w -> w.contains("config.yml")),
        "config.yml should not have warnings, got: " + warnings);
    assertTrue(warnings.stream().noneMatch(w -> w.contains("holograms.yml")),
        "holograms.yml should not have warnings, got: " + warnings);
    assertTrue(warnings.stream().noneMatch(w -> w.contains("display_entities.yml")),
        "display_entities.yml should not have warnings, got: " + warnings);

    // Verify settings from config.yml were imported (not dropped)
    Path worldRegion = destDir.resolve("definitions/regions/world_region.yml");
    assertTrue(Files.exists(worldRegion), "world_region.yml must be generated from custom_worlds");
    RtpYamlConfig wCfg = RtpYamlConfig.load(worldRegion.toFile());
    assertEquals("world", wCfg.getString("world"));
    assertEquals(16384, wCfg.getInt("shape.radius"));
    assertEquals(1024, wCfg.getInt("shape.centerRadius"));

    Path netherRegion = destDir.resolve("definitions/regions/world_nether_region.yml");
    assertTrue(Files.exists(netherRegion));
    RtpYamlConfig nCfg = RtpYamlConfig.load(netherRegion.toFile());
    assertEquals(625, nCfg.getInt("shape.radius"));
    assertEquals(50, nCfg.getInt("shape.centerRadius"));

    Path customLocRegion = destDir.resolve("definitions/regions/justrtp_loc_europe.yml");
    assertTrue(Files.exists(customLocRegion), "justrtp_loc_europe.yml must be generated from custom_locations.yml");
  }
}
