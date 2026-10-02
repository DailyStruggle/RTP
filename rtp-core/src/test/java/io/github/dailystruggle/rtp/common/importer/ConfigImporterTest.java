package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigImportCmd;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigImporterTest {

    @TempDir
    Path tempDir;

    private Path pluginsDir;
    private Path rtpDir;

    @BeforeEach
    void setUp() throws IOException {
        pluginsDir = tempDir.resolve("plugins");
        rtpDir = pluginsDir.resolve("RTP");
        Files.createDirectories(rtpDir);

        RTPTestSetup.install(rtpDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        RTP.configs = new Configs(rtpDir.toFile());

        RTP.addShape(new Circle("CIRCLE"));
        RTP.addShape(new Square("SQUARE"));
        RTP.addVerticalAdjustor(new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor(new ArrayList<>()));

        MockRTPServerAccessor mockAccessor = new MockRTPServerAccessor(rtpDir.toFile());
        MockRTPWorld world = new MockRTPWorld("world");
        MockRTPWorld worldNether = new MockRTPWorld("world_nether");
        mockAccessor.addWorld(world);
        mockAccessor.addWorld(worldNether);
        RTP.serverAccessor = mockAccessor;
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    @Test
    @DisplayName("UniversalConfigImporter parses EzRTP rtp.yml, config.yml, and limits.yml into valid LeafRTP regions and worlds")
    void testEzRtpImporter() throws IOException {
        Path ezDir = pluginsDir.resolve("EzRTP");
        Files.createDirectories(ezDir);

        // Mock rtp.yml
        Files.writeString(ezDir.resolve("rtp.yml"),
                "search-pattern: circle\n" +
                "radius:\n" +
                "  min-distance: 150\n" +
                "  max-distance: 3500\n" +
                "center:\n" +
                "  center-x: 100\n" +
                "  center-z: -200\n" +
                "worlds:\n" +
                "  - world\n");

        // Mock config.yml
        Files.writeString(ezDir.resolve("config.yml"),
                "world: world\n" +
                "cost: 25.5\n");

        // Mock limits.yml
        Files.writeString(ezDir.resolve("limits.yml"),
                "rtp-limits:\n" +
                "  default:\n" +
                "    cooldown-seconds: 180\n" +
                "    cost: 25.5\n");

        EzRtpConfigImporter importer = new EzRtpConfigImporter();
        assertTrue(importer.canImport(ezDir));

        ImportResult result = importer.importConfiguration(ezDir, rtpDir, false);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());
        assertEquals("ezrtp", result.getSourceName());

        // Verify generated region file
        Path regionFile = rtpDir.resolve("regions").resolve("world_region.yml");
        assertTrue(Files.exists(regionFile), "Region file must exist: " + regionFile);

        RtpYamlConfig regionConfig = RtpYamlConfig.load(regionFile.toFile());
        assertEquals("world", regionConfig.getString("world"));
        assertEquals("CIRCLE", regionConfig.getString("shape.name"));
        assertEquals(3500, regionConfig.getInt("shape.radius"));
        assertEquals(150, regionConfig.getInt("shape.centerRadius"));
        assertEquals(100, regionConfig.getInt("shape.centerX"));
        assertEquals(-200, regionConfig.getInt("shape.centerZ"));
        assertEquals(25.5, regionConfig.getDouble("price"));

        // Verify generated world file
        Path worldFile = rtpDir.resolve("worlds").resolve("world.yml");
        assertTrue(Files.exists(worldFile), "World file must exist: " + worldFile);
        RtpYamlConfig worldConfig = RtpYamlConfig.load(worldFile.toFile());
        assertEquals("world_region", worldConfig.getString("region"));

        // Verify RegionConfigLoader parses it into a valid RegionSettings model
        var shapeSection = regionConfig.getConfigurationSection("shape");
        assertNotNull(shapeSection);
        var shape = RegionConfigLoader.deserializeShape(shapeSection.getValues(false));
        assertNotNull(shape);
        assertTrue(shape instanceof Circle);
        Circle circle = (Circle) shape;
        assertEquals(3500, circle.getNumber(GenericMemoryShapeParams.radius, 0).intValue());
        assertEquals(150, circle.getNumber(GenericMemoryShapeParams.centerRadius, 0).intValue());
        assertEquals(100, circle.getNumber(GenericMemoryShapeParams.centerX, 0).intValue());
        assertEquals(-200, circle.getNumber(GenericMemoryShapeParams.centerZ, 0).intValue());

        // Verify cooldown updated in config.yml
        Path destConfig = rtpDir.resolve("config.yml");
        assertTrue(Files.exists(destConfig));
        RtpYamlConfig globalConfig = RtpYamlConfig.load(destConfig.toFile());
        assertEquals(180L, globalConfig.getLong("teleportCooldown"));
    }

    @Test
    @DisplayName("UniversalConfigImporter maps JustRTP per-world radii, shapes ROUND->CIRCLE, SQUARE->SQUARE, and cooldowns")
    void testJustRtpImporter() throws IOException {
        Path justDir = pluginsDir.resolve("justRTP");
        Files.createDirectories(justDir);

        // Mock config.yml
        Files.writeString(justDir.resolve("config.yml"),
                "cooldown: 75\n" +
                "worlds:\n" +
                "  world:\n" +
                "    landing_shape: ROUND\n" +
                "    min_radius: 200\n" +
                "    max_radius: 4000\n" +
                "    center:\n" +
                "      x: 50\n" +
                "      z: -50\n" +
                "    cost: 10.0\n" +
                "  world_nether:\n" +
                "    shape: SQUARE\n" +
                "    min-radius: 50\n" +
                "    max-radius: 2000\n" +
                "    center-x: 0\n" +
                "    center-z: 0\n");

        // Mock cache.yml
        Files.writeString(justDir.resolve("cache.yml"),
                "cache_size: 40\n");

        JustRtpConfigImporter importer = new JustRtpConfigImporter();
        assertTrue(importer.canImport(justDir));

        ImportResult result = importer.importConfiguration(justDir, rtpDir, false);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());
        assertEquals("justrtp", result.getSourceName());

        // Check world region (ROUND -> CIRCLE)
        Path worldRegionFile = rtpDir.resolve("regions").resolve("world_region.yml");
        assertTrue(Files.exists(worldRegionFile));
        RtpYamlConfig worldCfg = RtpYamlConfig.load(worldRegionFile.toFile());
        assertEquals("CIRCLE", worldCfg.getString("shape.name"));
        assertEquals(4000, worldCfg.getInt("shape.radius"));
        assertEquals(200, worldCfg.getInt("shape.centerRadius"));
        assertEquals(50, worldCfg.getInt("shape.centerX"));
        assertEquals(-50, worldCfg.getInt("shape.centerZ"));
        assertEquals(10.0, worldCfg.getDouble("price"));
        assertEquals(40, worldCfg.getInt("cacheCap"));

        var worldShapeSection = worldCfg.getConfigurationSection("shape");
        assertNotNull(worldShapeSection);
        var worldShape = RegionConfigLoader.deserializeShape(worldShapeSection.getValues(false));
        assertNotNull(worldShape);
        assertTrue(worldShape instanceof Circle);

        // Check world_nether region (SQUARE -> SQUARE)
        Path netherRegionFile = rtpDir.resolve("regions").resolve("world_nether_region.yml");
        assertTrue(Files.exists(netherRegionFile));
        RtpYamlConfig netherCfg = RtpYamlConfig.load(netherRegionFile.toFile());
        assertEquals("SQUARE", netherCfg.getString("shape.name"));
        assertEquals(2000, netherCfg.getInt("shape.radius"));
        assertEquals(50, netherCfg.getInt("shape.centerRadius"));

        var netherShapeSection = netherCfg.getConfigurationSection("shape");
        assertNotNull(netherShapeSection);
        var netherShape = RegionConfigLoader.deserializeShape(netherShapeSection.getValues(false));
        assertNotNull(netherShape);
        assertTrue(netherShape instanceof Square);

        // Verify cooldown
        RtpYamlConfig globalConfig = RtpYamlConfig.load(rtpDir.resolve("config.yml").toFile());
        assertEquals(75L, globalConfig.getLong("teleportCooldown"));
    }

    @Test
    @DisplayName("Importer protects against overwriting existing files unless overwrite is true")
    void testOverwriteProtection() throws IOException {
        Path ezDir = pluginsDir.resolve("EzRTP");
        Files.createDirectories(ezDir);
        Files.writeString(ezDir.resolve("config.yml"), "world: world\nradius:\n  min: 100\n  max: 1000\n");

        EzRtpConfigImporter importer = new EzRtpConfigImporter();

        // 1st import - succeeds
        ImportResult first = importer.importConfiguration(ezDir, rtpDir, false);
        assertTrue(first.isSuccess());

        // 2nd import without overwrite - fails due to existing files
        ImportResult second = importer.importConfiguration(ezDir, rtpDir, false);
        assertFalse(second.isSuccess());
        assertFalse(second.getErrors().isEmpty());

        // 3rd import with overwrite=true - succeeds
        ImportResult third = importer.importConfiguration(ezDir, rtpDir, true);
        assertTrue(third.isSuccess());
    }

    @Test
    @DisplayName("ForeignConfigImporterRegistry accurately detects competitor configurations")
    void testAutoDetection() throws IOException {
        Path betterDir = pluginsDir.resolve("BetterRTP");
        Files.createDirectories(betterDir);
        Files.writeString(betterDir.resolve("config.yml"), "Default:\n  MinRadius: 50\n");

        Map<String, Path> detected0 = ForeignConfigImporterRegistry.detectAvailableSources(pluginsDir);
        assertEquals(1, detected0.size());
        assertTrue(detected0.containsKey("BetterRTP"));

        Path ezDir = pluginsDir.resolve("EzRTP");
        Files.createDirectories(ezDir);
        Files.writeString(ezDir.resolve("rtp.yml"), "search-pattern: circle\n");

        Map<String, Path> detected1 = ForeignConfigImporterRegistry.detectAvailableSources(pluginsDir);
        assertEquals(2, detected1.size());
        assertTrue(detected1.containsKey("BetterRTP"));
        assertTrue(detected1.containsKey("EzRTP"));

        Path justDir = pluginsDir.resolve("justRTP");
        Files.createDirectories(justDir);
        Files.writeString(justDir.resolve("config.yml"), "cooldown: 60\n");

        Map<String, Path> detected2 = ForeignConfigImporterRegistry.detectAvailableSources(pluginsDir);
        assertEquals(3, detected2.size());
        assertTrue(detected2.containsKey("BetterRTP"));
        assertTrue(detected2.containsKey("EzRTP"));
        assertTrue(detected2.containsKey("justRTP"));
    }

    @Test
    @DisplayName("UniversalConfigImporter parses BetterRTP Default, CustomWorlds, Cooldown, and Price into LeafRTP configs")
    void testBetterRtpImporter() throws IOException {
        Path betterDir = pluginsDir.resolve("BetterRTP");
        Files.createDirectories(betterDir);

        // Mock BetterRTP config.yml
        Files.writeString(betterDir.resolve("config.yml"),
                "Settings:\n" +
                "  Cooldown: 45\n" +
                "  Delay: 5\n" +
                "  Price: 15.0\n" +
                "  SetAsRespawn: true\n" +
                "  LockAfter: 3\n" +
                "Default:\n" +
                "  Shape: \"circle\"\n" +
                "  MinRadius: 100\n" +
                "  MaxRadius: 2500\n" +
                "  CenterX: 0\n" +
                "  CenterZ: 0\n" +
                "CustomWorlds:\n" +
                "  - Name: \"world_nether\"\n" +
                "    Shape: \"square\"\n" +
                "    MinRadius: 25\n" +
                "    MaxRadius: 1200\n" +
                "    CenterX: 150\n" +
                "    CenterZ: -150\n" +
                "    Price: 30.0\n");

        BetterRtpConfigImporter importer = new BetterRtpConfigImporter();
        assertTrue(importer.canImport(betterDir));

        ImportResult result = importer.importConfiguration(betterDir, rtpDir, false);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());
        assertEquals("betterrtp", result.getSourceName());

        // Verify mapped global config values for SetAsRespawn and LockAfter
        RtpYamlConfig globalConfig = RtpYamlConfig.load(rtpDir.resolve("config.yml").toFile());
        assertEquals(45L, globalConfig.getLong("teleportCooldown"));
        assertEquals(3L, globalConfig.getLong("lockAfterUses"));
        assertTrue(globalConfig.getBoolean("setRespawnOnTeleport"));

        // Verify world_nether region
        Path netherRegFile = rtpDir.resolve("regions").resolve("world_nether_region.yml");
        assertTrue(Files.exists(netherRegFile));
        RtpYamlConfig netherCfg = RtpYamlConfig.load(netherRegFile.toFile());
        assertEquals("SQUARE", netherCfg.getString("shape.name"));
        assertEquals(1200, netherCfg.getInt("shape.radius"));
        assertEquals(25, netherCfg.getInt("shape.centerRadius"));
        assertEquals(150, netherCfg.getInt("shape.centerX"));
        assertEquals(-150, netherCfg.getInt("shape.centerZ"));
        assertEquals(30.0, netherCfg.getDouble("price"));

        var shapeSection = netherCfg.getConfigurationSection("shape");
        assertNotNull(shapeSection);
        var shape = RegionConfigLoader.deserializeShape(shapeSection.getValues(false));
        assertNotNull(shape);
        assertTrue(shape instanceof Square);
    }

    @Test
    @DisplayName("Command integration /rtp config import executes auto-detection and handles arguments")
    void testCommandExecution() throws IOException {
        Path ezDir = pluginsDir.resolve("EzRTP");
        Files.createDirectories(ezDir);
        Files.writeString(ezDir.resolve("config.yml"), "world: world\nradius:\n  min: 50\n  max: 500\n");

        ConfigCmd configCmd = new ConfigCmd(null);
        configCmd.addCommands();

        assertTrue(configCmd.getCommandLookup().containsKey("import"), "ConfigCmd must register 'import' subcommand");
        ConfigImportCmd importCmd = (ConfigImportCmd) configCmd.getCommandLookup().get("import");
        assertNotNull(importCmd);

        // Execute auto-detect with no params
        UUID caller = UUID.randomUUID();
        Map<String, List<String>> params = new HashMap<>();
        params.put("overwrite", List.of("true"));
        boolean res = importCmd.onCommand(caller, params, null);
        assertTrue(res, "Command should succeed on single detected source");

        assertTrue(Files.exists(rtpDir.resolve("regions").resolve("world_region.yml"))
                || Files.exists(rtpDir.resolve("definitions").resolve("regions").resolve("world_region.yml")));

        // Test bad custom path
        Map<String, List<String>> badPathParams = new HashMap<>();
        badPathParams.put("path", List.of(tempDir.resolve("non_existent_folder").toString()));
        assertFalse(importCmd.onCommand(caller, badPathParams, null));

        // Test unknown requested source
        Map<String, List<String>> unknownSourceParams = new HashMap<>();
        unknownSourceParams.put("source", List.of("nonexistent_importer_xyz"));
        assertFalse(importCmd.onCommand(caller, unknownSourceParams, null));

        // Test direct custom path to ezDir
        Map<String, List<String>> directPathParams = new HashMap<>();
        directPathParams.put("path", List.of(ezDir.toString()));
        directPathParams.put("overwrite", List.of("true"));
        assertTrue(importCmd.onCommand(caller, directPathParams, null));
    }

    @Test
    @DisplayName("UniversalConfigImporter parses list of single-key maps and nested Settings.Cooldown.Time")
    void testBetterRtpSingleKeyMapList() throws IOException {
        Path betterDir = pluginsDir.resolve("BetterRTP");
        Files.createDirectories(betterDir);

        Files.writeString(betterDir.resolve("config.yml"),
                "Settings:\n" +
                "  Cooldown:\n" +
                "    Enabled: true\n" +
                "    Time: 600\n" +
                "    LockAfter: 5\n" +
                "  Delay:\n" +
                "    Enabled: true\n" +
                "    Time: 5\n" +
                "  SetAsRespawn: true\n" +
                "Default:\n" +
                "  Shape: \"square\"\n" +
                "  MinRadius: 10\n" +
                "  MaxRadius: 1000\n" +
                "  Biomes: []\n" +
                "CustomWorlds:\n" +
                "  - custom_world_1:\n" +
                "      UseWorldBorder: false\n" +
                "      MaxRadius: 1000\n" +
                "      MinRadius: 100\n" +
                "      Price: 50\n" +
                "      Shape: 'square'\n" +
                "  - other_custom_world:\n" +
                "      MaxRadius: 10000\n" +
                "      MinRadius: 150\n" +
                "      CenterX: 123\n" +
                "      CenterZ: -123\n" +
                "      Price: 0\n" +
                "      Shape: 'circle'\n");

        BetterRtpConfigImporter importer = new BetterRtpConfigImporter();
        ImportResult result = importer.importConfiguration(betterDir, rtpDir, true);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());

        Path cw1File = rtpDir.resolve("regions").resolve("custom_world_1_region.yml");
        Path ocwFile = rtpDir.resolve("regions").resolve("other_custom_world_region.yml");
        assertTrue(Files.exists(cw1File));
        assertTrue(Files.exists(ocwFile));

        RtpYamlConfig cw1Cfg = RtpYamlConfig.load(cw1File.toFile());
        assertEquals("SQUARE", cw1Cfg.getString("shape.name"));
        assertEquals(1000, cw1Cfg.getInt("shape.radius"));
        assertEquals(100, cw1Cfg.getInt("shape.centerRadius"));
        assertEquals(50.0, cw1Cfg.getDouble("price"));

        RtpYamlConfig ocwCfg = RtpYamlConfig.load(ocwFile.toFile());
        assertEquals("CIRCLE", ocwCfg.getString("shape.name"));
        assertEquals(10000, ocwCfg.getInt("shape.radius"));
        assertEquals(150, ocwCfg.getInt("shape.centerRadius"));
        assertEquals(123, ocwCfg.getInt("shape.centerX"));
        assertEquals(-123, ocwCfg.getInt("shape.centerZ"));

        RtpYamlConfig globalCfg = RtpYamlConfig.load(rtpDir.resolve("config.yml").toFile());
        assertEquals(600L, globalCfg.getLong("teleportCooldown"));
        assertEquals(5L, globalCfg.getLong("lockAfterUses"));
        assertTrue(globalCfg.getBoolean("setRespawnOnTeleport"));

        // Verify loaded RegionSettings using RegionConfigLoader
        RtpYamlConfig regionYml = RtpYamlConfig.load(cw1File.toFile());
        var shapeSection = regionYml.getConfigurationSection("shape");
        assertNotNull(shapeSection);
        var loadedShape = RegionConfigLoader.deserializeShape(shapeSection.getValues(false));
        assertNotNull(loadedShape);
        assertTrue(loadedShape instanceof Square);
        assertEquals(1000, ((Square) loadedShape).getNumber(GenericMemoryShapeParams.radius, 0).intValue());
        assertEquals(100, ((Square) loadedShape).getNumber(GenericMemoryShapeParams.centerRadius, 0).intValue());
        assertEquals("custom_world_1", regionYml.getString("world"));
        assertEquals(50.0, regionYml.getDouble("price"));
    }

    @Test
    @DisplayName("UniversalConfigImporter parses custom_locations.yml and nested settings")
    void testJustRtpCustomLocations() throws IOException {
        Path justDir = pluginsDir.resolve("justRTP");
        Files.createDirectories(justDir);

        Files.writeString(justDir.resolve("config.yml"),
                "settings:\n" +
                "  cooldown: 30\n" +
                "economy:\n" +
                "  cost: 100.0\n" +
                "location_cache:\n" +
                "  cache_size: 8\n" +
                "  worlds:\n" +
                "    world:\n" +
                "      generate_chunks: true\n" +
                "    world_nether:\n" +
                "      generate_chunks: true\n");

        Files.writeString(justDir.resolve("custom_locations.yml"),
                "locations:\n" +
                "  europe:\n" +
                "    enabled: true\n" +
                "    world: \"world\"\n" +
                "    center-x: 10000\n" +
                "    center-z: 5000\n" +
                "    min-radius: 0\n" +
                "    max-radius: 2500\n" +
                "    display-name: \"Europe\"\n" +
                "  nether_hub:\n" +
                "    enabled: true\n" +
                "    world: \"world_nether\"\n" +
                "    center-x: 0\n" +
                "    center-z: 0\n" +
                "    min-radius: 50\n" +
                "    max-radius: 500\n" +
                "    display-name: \"Nether Hub\"\n");

        JustRtpConfigImporter importer = new JustRtpConfigImporter();
        ImportResult result = importer.importConfiguration(justDir, rtpDir, true);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());

        Path europeFile = rtpDir.resolve("regions").resolve("justrtp_loc_europe.yml");
        Path netherHubFile = rtpDir.resolve("regions").resolve("justrtp_loc_nether_hub.yml");
        assertTrue(Files.exists(europeFile));
        assertTrue(Files.exists(netherHubFile));

        RtpYamlConfig europeCfg = RtpYamlConfig.load(europeFile.toFile());
        assertEquals("world", europeCfg.getString("world"));
        assertEquals(10000, europeCfg.getInt("shape.centerX"));
        assertEquals(5000, europeCfg.getInt("shape.centerZ"));
        assertEquals(2500, europeCfg.getInt("shape.radius"));
        assertEquals(0, europeCfg.getInt("shape.centerRadius"));
        assertEquals(8, europeCfg.getInt("cacheCap"));
        assertEquals(100.0, europeCfg.getDouble("price"));

        RtpYamlConfig globalCfg = RtpYamlConfig.load(rtpDir.resolve("config.yml").toFile());
        assertEquals(30L, globalCfg.getLong("teleportCooldown"));
    }

    @Test
    @DisplayName("Command integration /rtp config import supports custom path argument")
    void testCommandCustomPath() throws IOException {
        Path customDir = tempDir.resolve("external_plugins");
        Path extBetter = customDir.resolve("BetterRTP");
        Files.createDirectories(extBetter);
        Files.writeString(extBetter.resolve("config.yml"), "Default:\n  MinRadius: 75\n  MaxRadius: 3000\n");

        ConfigCmd configCmd = new ConfigCmd(null);
        configCmd.addCommands();
        ConfigImportCmd importCmd = (ConfigImportCmd) configCmd.getCommandLookup().get("import");

        Map<String, List<String>> params = new HashMap<>();
        params.put("source", List.of("betterrtp"));
        params.put("path", List.of(customDir.toString()));
        params.put("overwrite", List.of("true"));

        UUID caller = UUID.randomUUID();
        boolean res = importCmd.onCommand(caller, params, null);
        assertTrue(res, "Command should succeed with custom path");

        Path regFile = rtpDir.resolve("regions").resolve("world_region.yml");
        if (!Files.exists(regFile)) {
            regFile = rtpDir.resolve("definitions").resolve("regions").resolve("world_region.yml");
        }
        assertTrue(Files.exists(regFile));
        RtpYamlConfig reg = RtpYamlConfig.load(regFile.toFile());
        assertEquals(3000, reg.getInt("shape.radius"));
        assertEquals(75, reg.getInt("shape.centerRadius"));
    }

    @Test
    @DisplayName("Verifies importing directly against C:/GameServers/Minecraft/testServer/RTP-Paper/26.3 if directory is accessible")
    void testActualTestServerConfigsIfPresent() throws IOException {
        Path serverPlugins = Path.of("C:", "GameServers", "Minecraft", "testServer", "RTP-Paper", "26.3", "plugins");
        if (!Files.isDirectory(serverPlugins)) {
            return; // Skip when running on a different machine / CI
        }

        Path outDir = tempDir.resolve("test_server_output");
        Files.createDirectories(outDir);

        // Test BetterRTP
        Path betterDir = serverPlugins.resolve("BetterRTP");
        if (Files.isDirectory(betterDir) && Files.exists(betterDir.resolve("config.yml"))) {
            BetterRtpConfigImporter bImporter = new BetterRtpConfigImporter();
            if (bImporter.canImport(betterDir)) {
                ImportResult bResult = bImporter.importConfiguration(betterDir, outDir.resolve("better"), true);
                if (bResult.isSuccess()) {
                    assertTrue(Files.exists(outDir.resolve("better").resolve("regions").resolve("custom_world_1_region.yml")));
                    assertTrue(Files.exists(outDir.resolve("better").resolve("regions").resolve("other_custom_world_region.yml")));
                }
            }
        }

        // Test EzRTP
        Path ezDir = serverPlugins.resolve("EzRTP");
        if (Files.isDirectory(ezDir) && (Files.exists(ezDir.resolve("config.yml")) || Files.exists(ezDir.resolve("rtp.yml")))) {
            EzRtpConfigImporter ezImporter = new EzRtpConfigImporter();
            if (ezImporter.canImport(ezDir)) {
                ImportResult ezResult = ezImporter.importConfiguration(ezDir, outDir.resolve("ez"), true);
                assertTrue(ezResult.isSuccess(), "EzRTP import failed: " + ezResult.getErrors());
                assertTrue(Files.exists(outDir.resolve("ez").resolve("regions").resolve("world_region.yml")));
            }
        }

        // Test JustRTP
        Path justDir = serverPlugins.resolve("JustRTP");
        if (Files.isDirectory(justDir) && Files.exists(justDir.resolve("config.yml"))) {
            JustRtpConfigImporter jImporter = new JustRtpConfigImporter();
            if (jImporter.canImport(justDir)) {
                ImportResult jResult = jImporter.importConfiguration(justDir, outDir.resolve("just"), true);
                assertTrue(jResult.isSuccess(), "JustRTP import failed: " + jResult.getErrors());
                assertTrue(Files.exists(outDir.resolve("just").resolve("regions").resolve("justrtp_loc_europe.yml")));
            }
        }
    }

    @Test
    @DisplayName("Phase 1.2: BetterRTP and JustRTP SQL database setup mirroring into database.yml")
    void testSqlDatabaseMirroring() throws IOException {
        // 1. BetterRTP SQL database extraction
        Path betterDir = pluginsDir.resolve("BetterRTP_SQL");
        Files.createDirectories(betterDir);
        Files.writeString(betterDir.resolve("config.yml"),
                "Database:\n" +
                "  Type: MySQL\n" +
                "  Host: 192.168.1.100\n" +
                "  Port: 3307\n" +
                "  Database: my_rtp_db\n" +
                "  Username: admin\n" +
                "  Password: secret_password\n" +
                "  UseSSL: true\n" +
                "Default:\n" +
                "  MinRadius: 100\n" +
                "  MaxRadius: 2000\n");

        Path outBetter = tempDir.resolve("out_better_sql");
        Files.createDirectories(outBetter);

        BetterRtpConfigImporter betterImporter = new BetterRtpConfigImporter();
        ImportResult bRes = betterImporter.importConfiguration(betterDir, outBetter, false);
        assertTrue(bRes.isSuccess());

        Path bDbFile = outBetter.resolve("advanced").resolve("database.yml");
        assertTrue(Files.exists(bDbFile), "advanced/database.yml should be created");
        RtpYamlConfig bDbCfg = RtpYamlConfig.load(bDbFile.toFile());
        assertEquals("mysql", bDbCfg.getString("database.type"));
        assertEquals("192.168.1.100", bDbCfg.getString("database.host"));
        assertEquals(3307, bDbCfg.getInt("database.port"));
        assertEquals("my_rtp_db", bDbCfg.getString("database.name"));
        assertEquals("admin", bDbCfg.getString("database.username"));
        assertEquals("secret_password", bDbCfg.getString("database.password"));
        assertTrue(bDbCfg.getBoolean("database.useSSL"));

        // 2. JustRTP SQL database extraction
        Path justDir = pluginsDir.resolve("JustRTP_SQL");
        Files.createDirectories(justDir);
        Files.writeString(justDir.resolve("config.yml"),
                "database:\n" +
                "  type: postgresql\n" +
                "  host: postgres.internal\n" +
                "  port: 5432\n" +
                "  name: justrtp_pg\n" +
                "  user: pg_user\n" +
                "  password: pg_pass\n" +
                "  ssl: false\n");

        Path outJust = tempDir.resolve("out_just_sql");
        Files.createDirectories(outJust);

        JustRtpConfigImporter justImporter = new JustRtpConfigImporter();
        ImportResult jRes = justImporter.importConfiguration(justDir, outJust, false);
        assertTrue(jRes.isSuccess());

        Path jDbFile = outJust.resolve("advanced").resolve("database.yml");
        assertTrue(Files.exists(jDbFile), "advanced/database.yml should be created");
        RtpYamlConfig jDbCfg = RtpYamlConfig.load(jDbFile.toFile());
        assertEquals("postgresql", jDbCfg.getString("database.type"));
        assertEquals("postgres.internal", jDbCfg.getString("database.host"));
        assertEquals(5432, jDbCfg.getInt("database.port"));
        assertEquals("justrtp_pg", jDbCfg.getString("database.name"));
        assertEquals("pg_user", jDbCfg.getString("database.username"));
        assertEquals("pg_pass", jDbCfg.getString("database.password"));
        assertFalse(jDbCfg.getBoolean("database.useSSL"));
    }

    @Test
    @DisplayName("Phase 1.3: Effects mirroring for BetterRTP, JustRTP, and EzRTP configs")
    void testCompetitorEffectsMirroring() throws IOException {
        // BetterRTP effects
        Path bDir = pluginsDir.resolve("BetterRTP_Effects");
        Files.createDirectories(bDir);
        Files.writeString(bDir.resolve("config.yml"),
                "Default:\n" +
                "  MinRadius: 50\n" +
                "  MaxRadius: 1000\n" +
                "Effects:\n" +
                "  Title:\n" +
                "    Enabled: true\n" +
                "    Title: \"&aTeleported\"\n" +
                "    Subtitle: \"&eExplore safely\"\n" +
                "    FadeIn: 15\n" +
                "    Stay: 60\n" +
                "    FadeOut: 15\n" +
                "  ActionBar:\n" +
                "    Enabled: true\n" +
                "    Message: \"&6Welcome to the wild!\"\n" +
                "  Sounds:\n" +
                "    Enabled: true\n" +
                "    Sound: \"ENTITY_PLAYER_LEVELUP\"\n" +
                "    Volume: 0.8\n" +
                "    Pitch: 1.2\n" +
                "  Potions:\n" +
                "    Enabled: true\n" +
                "    List:\n" +
                "      - \"BLINDNESS:40:1\"\n" +
                "      - \"SLOW_FALLING:60:1\"\n" +
                "Invulnerable: 5\n");

        Path outBetter = tempDir.resolve("out_better_eff");
        Files.createDirectories(outBetter);
        BetterRtpConfigImporter bImporter = new BetterRtpConfigImporter();
        ImportResult bRes = bImporter.importConfiguration(bDir, outBetter, false);
        assertTrue(bRes.isSuccess());

        Path bEffFile = outBetter.resolve("definitions/effects/imported_betterrtp_teleport.yml");
        assertTrue(Files.exists(bEffFile), "BetterRTP effect profile should exist: " + bEffFile);
        RtpYamlConfig bEffCfg = RtpYamlConfig.load(bEffFile.toFile());
        assertEquals("postteleport", bEffCfg.getString("when"));
        List<String> bEffects = bEffCfg.getStringList("effects");
        assertNotNull(bEffects);
        assertTrue(bEffects.stream().anyMatch(e -> e.startsWith("SOUND.ENTITY_PLAYER_LEVELUP")));
        assertTrue(bEffects.stream().anyMatch(e -> e.startsWith("TITLE.&aTeleported.&eExplore safely")));
        assertTrue(bEffects.stream().anyMatch(e -> e.contains("actionbar") && e.contains("Welcome to the wild!")));
        assertTrue(bEffects.stream().anyMatch(e -> e.startsWith("POTION.BLINDNESS")));
        assertTrue(bEffects.stream().anyMatch(e -> e.startsWith("POTION.SLOW_FALLING")));
        assertTrue(bEffects.stream().anyMatch(e -> e.startsWith("POTION.RESISTANCE"))); // from Invulnerable: 5

        // JustRTP effects
        Path jDir = pluginsDir.resolve("JustRTP_Effects");
        Files.createDirectories(jDir);
        Files.writeString(jDir.resolve("config.yml"),
                "effects:\n" +
                "  sound:\n" +
                "    name: \"ENTITY_ENDERMAN_TELEPORT\"\n" +
                "    volume: 1.0\n" +
                "    pitch: 0.8\n" +
                "  title:\n" +
                "    title: \"&2JustRTP\"\n" +
                "    sub_title: \"&fArrived\"\n" +
                "  action_bar:\n" +
                "    text: \"&aJustRTP arrival\"\n" +
                "  potions:\n" +
                "    - \"RESISTANCE:100:2\"\n");

        Path outJust = tempDir.resolve("out_just_eff");
        Files.createDirectories(outJust);
        JustRtpConfigImporter jImporter = new JustRtpConfigImporter();
        ImportResult jRes = jImporter.importConfiguration(jDir, outJust, false);
        assertTrue(jRes.isSuccess());

        Path jEffFile = outJust.resolve("definitions/effects/imported_justrtp_teleport.yml");
        assertTrue(Files.exists(jEffFile), "JustRTP effect profile should exist: " + jEffFile);
        RtpYamlConfig jEffCfg = RtpYamlConfig.load(jEffFile.toFile());
        List<String> jEffects = jEffCfg.getStringList("effects");
        assertNotNull(jEffects);
        assertTrue(jEffects.stream().anyMatch(e -> e.startsWith("SOUND.ENTITY_ENDERMAN_TELEPORT")));
        assertTrue(jEffects.stream().anyMatch(e -> e.startsWith("TITLE.&2JustRTP.&fArrived")));
        assertTrue(jEffects.stream().anyMatch(e -> e.contains("actionbar") && e.contains("JustRTP arrival")));
        assertTrue(jEffects.stream().anyMatch(e -> e.startsWith("POTION.RESISTANCE.100.2")));

        // EzRTP effects
        Path ezDir = pluginsDir.resolve("EzRTP_Effects");
        Files.createDirectories(ezDir);
        Files.writeString(ezDir.resolve("config.yml"),
                "world: \"world\"\n" +
                "effects:\n" +
                "  sounds:\n" +
                "    sound: \"ENTITY_FIREWORK_ROCKET_BLAST\"\n" +
                "    volume: 1.0\n" +
                "    pitch: 1.0\n" +
                "  titles:\n" +
                "    title: \"&bEzRTP\"\n" +
                "    subtitle: \"&7Landed\"\n" +
                "  actionbar:\n" +
                "    message: \"&eEzRTP finished\"\n" +
                "  potion-effects:\n" +
                "    list:\n" +
                "      - \"SLOW_FALLING:80:1\"\n");

        Path outEz = tempDir.resolve("out_ez_eff");
        Files.createDirectories(outEz);
        EzRtpConfigImporter ezImporter = new EzRtpConfigImporter();
        ImportResult ezRes = ezImporter.importConfiguration(ezDir, outEz, false);
        assertTrue(ezRes.isSuccess());

        Path ezEffFile = outEz.resolve("definitions/effects/imported_ezrtp_teleport.yml");
        assertTrue(Files.exists(ezEffFile), "EzRTP effect profile should exist: " + ezEffFile);
        RtpYamlConfig ezEffCfg = RtpYamlConfig.load(ezEffFile.toFile());
        List<String> ezEffects = ezEffCfg.getStringList("effects");
        assertNotNull(ezEffects);
        assertTrue(ezEffects.stream().anyMatch(e -> e.startsWith("SOUND.ENTITY_FIREWORK_ROCKET_BLAST")));
        assertTrue(ezEffects.stream().anyMatch(e -> e.startsWith("TITLE.&bEzRTP.&7Landed")));
        assertTrue(ezEffects.stream().anyMatch(e -> e.contains("actionbar") && e.contains("EzRTP finished")));
        assertTrue(ezEffects.stream().anyMatch(e -> e.startsWith("POTION.SLOW_FALLING")));
    }

    @Test
    @DisplayName("Phase 1.4: JustRTP rtp_zones.yml conversion into declarative zone action YAML via UniversalConfigImporter")
    void testJustRtpZonesImporterSeam() throws IOException {
        Path justDir = pluginsDir.resolve("JustRTP_Zones");
        Files.createDirectories(justDir);
        Files.writeString(justDir.resolve("config.yml"), "shape: CIRCLE\n");

        Files.writeString(justDir.resolve("rtp_zones.yml"),
                "zones:\n" +
                "  lobby_portal:\n" +
                "    type: PORTAL\n" +
                "    world: world\n" +
                "    pos1:\n" +
                "      x: 10\n" +
                "      y: 64\n" +
                "      z: 20\n" +
                "    pos2:\n" +
                "      x: 14\n" +
                "      y: 70\n" +
                "      z: 24\n" +
                "    cooldown: 10\n" +
                "    interval: 30\n" +
                "    region: default\n" +
                "    shape: SQUARE\n" +
                "    radius: 3000\n" +
                "    min_radius: 200\n" +
                "    min_separation: 32\n");

        Path outJust = tempDir.resolve("out_just_zones");
        Files.createDirectories(outJust);

        JustRtpConfigImporter importer = new JustRtpConfigImporter();
        ImportResult result = importer.importConfiguration(justDir, outJust, false);
        assertTrue(result.isSuccess());

        Path actionFile = outJust.resolve("definitions/actions/zone_lobby_portal.yml");
        assertTrue(Files.exists(actionFile), "zone_lobby_portal.yml should be created: " + actionFile);

        RtpYamlConfig actionCfg = RtpYamlConfig.load(actionFile.toFile());
        assertEquals("zone_lobby_portal", actionCfg.getString("alias"));
        assertEquals("rtp.action.zone.lobby_portal", actionCfg.getString("permission"));

        // Verify triggers block
        assertEquals("PORTAL", actionCfg.getString("triggers.zone_lobby_portal_trigger.type"));
        assertEquals("world", actionCfg.getString("triggers.zone_lobby_portal_trigger.world"));
        assertEquals(10, actionCfg.getInt("triggers.zone_lobby_portal_trigger.pos1.x"));
        assertEquals(64, actionCfg.getInt("triggers.zone_lobby_portal_trigger.pos1.y"));
        assertEquals(20, actionCfg.getInt("triggers.zone_lobby_portal_trigger.pos1.z"));
        assertEquals(14, actionCfg.getInt("triggers.zone_lobby_portal_trigger.pos2.x"));
        assertEquals(70, actionCfg.getInt("triggers.zone_lobby_portal_trigger.pos2.y"));
        assertEquals(24, actionCfg.getInt("triggers.zone_lobby_portal_trigger.pos2.z"));
        assertEquals("10s", actionCfg.getString("triggers.zone_lobby_portal_trigger.cooldown"));
        assertEquals("30s", actionCfg.getString("triggers.zone_lobby_portal_trigger.batchInterval"));

        // Verify placement block
        assertEquals("default", actionCfg.getString("placement.region"));
        assertEquals("regionQueue", actionCfg.getString("placement.anchor"));
        assertEquals("SQUARE", actionCfg.getString("placement.shape.name"));
        assertEquals(3000, actionCfg.getInt("placement.shape.radius"));
        assertEquals(200, actionCfg.getInt("placement.shape.centerRadius"));
        assertEquals(32, actionCfg.getInt("placement.minSeparation"));

        // Verify lifecycle
        assertNotNull(actionCfg.get("lifecycle.onStart"));
    }

    @Test
    @DisplayName("UniversalConfigImporter parses JakesRTP config.yml, rtpSettings, and distributions into LeafRTP configs")
    void testJakesRtpImporter() throws IOException {
        Path jakesDir = pluginsDir.resolve("JakesRTP");
        Path rtpSettingsDir = jakesDir.resolve("rtpSettings");
        Path distributionsDir = jakesDir.resolve("distributions");
        Files.createDirectories(rtpSettingsDir);
        Files.createDirectories(distributionsDir);

        // Global config.yml
        Files.writeString(jakesDir.resolve("config.yml"),
                "config-version: 2.0\n" +
                "rtp-on-first-join:\n" +
                "  enabled: true\n" +
                "  settings: 'default-settings'\n" +
                "rtp-on-death:\n" +
                "  enabled: true\n" +
                "location-cache-filler:\n" +
                "  enabled: true\n" +
                "  recheck-time: 1800\n" +
                "  between-time: 1\n");

        // Distribution default-symmetric.yml (Gaussian enabled -> CIRCLE_NORMAL)
        Files.writeString(distributionsDir.resolve("default-symmetric.yml"),
                "shape: circle\n" +
                "center:\n" +
                "  option: c-custom\n" +
                "  c-custom:\n" +
                "    x: 100\n" +
                "    z: -150\n" +
                "radius:\n" +
                "  max: 3000\n" +
                "  min: 500\n" +
                "gaussian-distribution:\n" +
                "  enabled: true\n" +
                "  shrink: 4\n" +
                "  center: 0.25\n");

        // Distribution nether-square.yml (Standard square -> SQUARE)
        Files.writeString(distributionsDir.resolve("nether-square.yml"),
                "shape: square\n" +
                "center:\n" +
                "  c-custom:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "radius:\n" +
                "  max: 1500\n" +
                "  min: 100\n" +
                "gaussian-distribution:\n" +
                "  enabled: false\n");

        // rtpSettings/default-settings.yml
        Files.writeString(rtpSettingsDir.resolve("default-settings.yml"),
                "enabled: true\n" +
                "landing-world: 'world'\n" +
                "call-from-worlds:\n" +
                "  - 'world'\n" +
                "distribution: 'default-symmetric'\n" +
                "cooldown: 45\n" +
                "warmup:\n" +
                "  time: 5\n" +
                "cost: 15.0\n" +
                "bounds:\n" +
                "  low: 60\n" +
                "  high: 220\n" +
                "max-attempts:\n" +
                "  value: 20\n" +
                "preparations:\n" +
                "  cache-locations: 15\n");

        // rtpSettings/nether-settings.yml
        Files.writeString(rtpSettingsDir.resolve("nether-settings.yml"),
                "enabled: true\n" +
                "landing-world: 'world_nether'\n" +
                "distribution: 'nether-square'\n" +
                "cooldown: 60\n" +
                "cost: 25.0\n" +
                "bounds:\n" +
                "  low: 30\n" +
                "  high: 120\n");

        JakesRtpConfigImporter importer = new JakesRtpConfigImporter();
        assertTrue(importer.canImport(jakesDir));

        ImportResult result = importer.importConfiguration(jakesDir, rtpDir, false);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());
        assertEquals("jakesrtp", result.getSourceName());

        // Verify default region file (should be CIRCLE_NORMAL because gaussian enabled)
        Path defaultRegionFile = rtpDir.resolve("regions").resolve("default_settings_region.yml");
        assertTrue(Files.exists(defaultRegionFile), "Default region file must exist: " + defaultRegionFile);
        RtpYamlConfig defRegionCfg = RtpYamlConfig.load(defaultRegionFile.toFile());
        assertEquals("world", defRegionCfg.getString("world"));
        assertEquals("CIRCLE_NORMAL", defRegionCfg.getString("shape.name"));
        assertEquals(3000, defRegionCfg.getInt("shape.radius"));
        assertEquals(500, defRegionCfg.getInt("shape.centerRadius"));
        assertEquals(100, defRegionCfg.getInt("shape.centerX"));
        assertEquals(-150, defRegionCfg.getInt("shape.centerZ"));
        assertEquals(15.0, defRegionCfg.getDouble("price"));
        assertEquals(60, defRegionCfg.getInt("vert.minY"));
        assertEquals(220, defRegionCfg.getInt("vert.maxY"));

        // Verify nether region file (SQUARE)
        Path netherRegionFile = rtpDir.resolve("regions").resolve("nether_settings_region.yml");
        assertTrue(Files.exists(netherRegionFile), "Nether region file must exist: " + netherRegionFile);
        RtpYamlConfig netherRegionCfg = RtpYamlConfig.load(netherRegionFile.toFile());
        assertEquals("world_nether", netherRegionCfg.getString("world"));
        assertEquals("SQUARE", netherRegionCfg.getString("shape.name"));
        assertEquals(1500, netherRegionCfg.getInt("shape.radius"));
        assertEquals(100, netherRegionCfg.getInt("shape.centerRadius"));
        assertEquals(30, netherRegionCfg.getInt("vert.minY"));
        assertEquals(120, netherRegionCfg.getInt("vert.maxY"));

        // Verify world files
        Path worldFile = rtpDir.resolve("worlds").resolve("world.yml");
        assertTrue(Files.exists(worldFile), "World file must exist");
        Path netherWorldFile = rtpDir.resolve("worlds").resolve("world_nether.yml");
        assertTrue(Files.exists(netherWorldFile), "Nether world file must exist");

        // Verify global config.yml updates
        Path destConfig = rtpDir.resolve("config.yml");
        assertTrue(Files.exists(destConfig));
        RtpYamlConfig configOut = RtpYamlConfig.load(destConfig.toFile());
        assertEquals(60, configOut.getLong("teleportCooldown"));
        assertEquals(5, configOut.getLong("teleportDelay"));
        assertTrue(configOut.getBoolean("rtpOnFirstJoin", false));
        assertTrue(configOut.getBoolean("rtpOnDeath", false));

        // Verify performance.yml updates
        Path destPerf = rtpDir.resolve("performance.yml");
        assertTrue(Files.exists(destPerf));
        RtpYamlConfig perfOut = RtpYamlConfig.load(destPerf.toFile());
        assertEquals(20, perfOut.getInt("maxAttempts"));
        assertEquals(15, perfOut.getInt("queue.targetSize"));

        // Verify ForeignConfigImporterRegistry resolution
        assertEquals(jakesDir, ForeignConfigImporterRegistry.resolveSourceDir(pluginsDir, "jakesrtp"));
        assertNotNull(ForeignConfigImporterRegistry.getImporter("jakesrtp"));
    }

    @Test
    @DisplayName("GenericSchemaImporter correctly imports AsyncRTP configuration")
    void testAsyncRtpGenericImport() throws IOException {
        Path asyncDir = tempDir.resolve("plugins").resolve("AsyncRTP");
        Files.createDirectories(asyncDir);

        Files.writeString(asyncDir.resolve("config.yml"),
                "cooldown: 40\n" +
                "teleport-delay: 3\n" +
                "worlds:\n" +
                "  survival:\n" +
                "    radius: 7500\n" +
                "    min-radius: 250\n" +
                "    center:\n" +
                "      x: 50\n" +
                "      z: -50\n" +
                "    min-y: 60\n" +
                "    max-y: 250\n" +
                "    shape: circle\n" +
                "    price: 10.0\n" +
                "    blacklisted-biomes:\n" +
                "      - ocean\n" +
                "      - deep_ocean\n");

        ForeignConfigImporter importer = ForeignConfigImporterRegistry.getImporter("asyncrtp");
        assertNotNull(importer);
        assertTrue(importer.canImport(asyncDir));

        Path outDir = tempDir.resolve("async_output");
        ImportResult result = importer.importConfiguration(asyncDir, outDir, false);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());

        Path regFile = outDir.resolve("regions").resolve("survival_region.yml");
        assertTrue(Files.exists(regFile));
        RtpYamlConfig regCfg = RtpYamlConfig.load(regFile.toFile());
        assertEquals(7500, regCfg.getInt("shape.radius"));
        assertEquals(250, regCfg.getInt("shape.centerRadius"));
        assertEquals(50, regCfg.getInt("shape.centerX"));
        assertEquals(-50, regCfg.getInt("shape.centerZ"));
        assertEquals(10.0, regCfg.getDouble("price"));
        assertEquals("CIRCLE", regCfg.getString("shape.name"));

        Path configFile = outDir.resolve("config.yml");
        assertTrue(Files.exists(configFile));
        RtpYamlConfig cfg = RtpYamlConfig.load(configFile.toFile());
        assertEquals(40, cfg.getInt("teleportCooldown"));
        assertEquals(3, cfg.getInt("teleportDelay"));
    }

    @Test
    @DisplayName("GenericSchemaImporter correctly imports AdvancedRTP configuration")
    void testAdvancedRtpGenericImport() throws IOException {
        Path advDir = tempDir.resolve("plugins").resolve("AdvancedRTP");
        Files.createDirectories(advDir);

        Files.writeString(advDir.resolve("config.yml"),
                "cooldown: 55\n" +
                "warmup: 4\n" +
                "worlds:\n" +
                "  mining:\n" +
                "    radius: 4000\n" +
                "    min_radius: 150\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "    min_y: 10\n" +
                "    max_y: 180\n" +
                "    shape: square\n" +
                "    cost: 5.0\n");

        ForeignConfigImporter importer = ForeignConfigImporterRegistry.getImporter("advancedrtp");
        assertNotNull(importer);
        assertTrue(importer.canImport(advDir));

        Path outDir = tempDir.resolve("adv_output");
        ImportResult result = importer.importConfiguration(advDir, outDir, false);
        assertTrue(result.isSuccess(), "Import should succeed: " + result.getErrors());

        Path regFile = outDir.resolve("regions").resolve("mining_region.yml");
        assertTrue(Files.exists(regFile));
        RtpYamlConfig regCfg = RtpYamlConfig.load(regFile.toFile());
        assertEquals(4000, regCfg.getInt("shape.radius"));
        assertEquals(150, regCfg.getInt("shape.centerRadius"));
        assertEquals("SQUARE", regCfg.getString("shape.name"));
        assertEquals(5.0, regCfg.getDouble("price"));
    }

    @Test
    @DisplayName("UniversalConfigImporter imports across 6 major competitors successfully")
    void testUniversalImporterAcrossSixCompetitors() throws IOException {
        UniversalConfigImporter universal = new UniversalConfigImporter();
        assertEquals("universal", universal.sourceName());

        // 1. BetterRTP via Universal
        Path betterDir = tempDir.resolve("plugins").resolve("BetterRTP_Uni");
        Files.createDirectories(betterDir);
        Files.writeString(betterDir.resolve("config.yml"),
                "Default:\n" +
                "  MaxRadius: 6000\n" +
                "  MinRadius: 200\n" +
                "CustomWorlds:\n" +
                "  - world:\n" +
                "      MaxRadius: 8000\n" +
                "      MinRadius: 500\n" +
                "      CenterX: 15\n" +
                "      CenterZ: -15\n" +
                "      Shape: 'Circle'\n" +
                "      Price: 20\n");
        assertTrue(universal.canImport(betterDir));
        Path out1 = tempDir.resolve("uni_out_better");
        ImportResult res1 = universal.importConfiguration(betterDir, out1, false);
        assertTrue(res1.isSuccess(), "BetterRTP universal import failed: " + res1.getErrors());
        Path reg1 = out1.resolve("regions").resolve("world_region.yml");
        assertTrue(Files.exists(reg1));
        RtpYamlConfig cfg1 = RtpYamlConfig.load(reg1.toFile());
        assertEquals(8000, cfg1.getInt("shape.radius"));
        assertEquals(500, cfg1.getInt("shape.centerRadius"));
        assertEquals(20.0, cfg1.getDouble("price"));

        // 2. EzRTP via Universal
        Path ezDir = tempDir.resolve("plugins").resolve("EzRTP_Uni");
        Files.createDirectories(ezDir);
        Files.writeString(ezDir.resolve("rtp.yml"),
                "radius:\n" +
                "  min-distance: 120\n" +
                "  max-distance: 3600\n" +
                "center:\n" +
                "  center-x: 50\n" +
                "  center-z: -50\n" +
                "worlds:\n" +
                "  - world\n");
        Files.writeString(ezDir.resolve("config.yml"),
                "world: world\n" +
                "cost: 12.0\n" +
                "cooldown: 90\n");
        assertTrue(universal.canImport(ezDir));
        Path out2 = tempDir.resolve("uni_out_ez");
        ImportResult res2 = universal.importConfiguration(ezDir, out2, false);
        assertTrue(res2.isSuccess(), "EzRTP universal import failed: " + res2.getErrors());
        Path reg2 = out2.resolve("regions").resolve("world_region.yml");
        assertTrue(Files.exists(reg2));
        RtpYamlConfig cfg2 = RtpYamlConfig.load(reg2.toFile());
        assertEquals(3600, cfg2.getInt("shape.radius"));
        assertEquals(120, cfg2.getInt("shape.centerRadius"));
        assertEquals(12.0, cfg2.getDouble("price"));

        // 3. JustRTP via Universal
        Path justDir = tempDir.resolve("plugins").resolve("JustRTP_Uni");
        Files.createDirectories(justDir);
        Files.writeString(justDir.resolve("config.yml"),
                "teleportation:\n" +
                "  cooldown: 45\n" +
                "  delay: 2\n" +
                "radius: 4200\n" +
                "min-radius: 300\n" +
                "center-x: 0\n" +
                "center-z: 0\n" +
                "world: world\n");
        assertTrue(universal.canImport(justDir));
        Path out3 = tempDir.resolve("uni_out_just");
        ImportResult res3 = universal.importConfiguration(justDir, out3, false);
        assertTrue(res3.isSuccess(), "JustRTP universal import failed: " + res3.getErrors());
        Path reg3 = out3.resolve("regions").resolve("world_region.yml");
        assertTrue(Files.exists(reg3));
        RtpYamlConfig cfg3 = RtpYamlConfig.load(reg3.toFile());
        assertEquals(4200, cfg3.getInt("shape.radius"));
        assertEquals(300, cfg3.getInt("shape.centerRadius"));

        // 4. JakesRTP via Universal (Multi-file directory topology)
        Path jakesDir = tempDir.resolve("plugins").resolve("JakesRTP_Uni");
        Path jakesRtpSettings = jakesDir.resolve("rtpSettings");
        Files.createDirectories(jakesRtpSettings);
        Files.writeString(jakesDir.resolve("config.yml"),
                "rtp-on-first-join:\n" +
                "  enabled: true\n");
        Files.writeString(jakesRtpSettings.resolve("survival.yml"),
                "landing-world: world\n" +
                "radius: 5500\n" +
                "min-radius: 400\n" +
                "center:\n" +
                "  x: 100\n" +
                "  z: -100\n" +
                "cost: 15.0\n");
        assertTrue(universal.canImport(jakesDir));
        Path out4 = tempDir.resolve("uni_out_jakes");
        ImportResult res4 = universal.importConfiguration(jakesDir, out4, false);
        assertTrue(res4.isSuccess(), "JakesRTP universal import failed: " + res4.getErrors());
        Path reg4 = out4.resolve("regions").resolve("survival_region.yml");
        assertTrue(Files.exists(reg4));
        RtpYamlConfig cfg4 = RtpYamlConfig.load(reg4.toFile());
        assertEquals(5500, cfg4.getInt("shape.radius"));
        assertEquals(400, cfg4.getInt("shape.centerRadius"));

        // 5. AsyncRTP via Universal
        Path asyncDir = tempDir.resolve("plugins").resolve("AsyncRTP_Uni");
        Files.createDirectories(asyncDir);
        Files.writeString(asyncDir.resolve("config.yml"),
                "worlds:\n" +
                "  nether:\n" +
                "    radius: 3000\n" +
                "    min-radius: 100\n" +
                "    shape: square\n" +
                "    price: 5.0\n");
        assertTrue(universal.canImport(asyncDir));
        Path out5 = tempDir.resolve("uni_out_async");
        ImportResult res5 = universal.importConfiguration(asyncDir, out5, false);
        assertTrue(res5.isSuccess(), "AsyncRTP universal import failed: " + res5.getErrors());
        Path reg5 = out5.resolve("regions").resolve("nether_region.yml");
        assertTrue(Files.exists(reg5));
        RtpYamlConfig cfg5 = RtpYamlConfig.load(reg5.toFile());
        assertEquals(3000, cfg5.getInt("shape.radius"));
        assertEquals("SQUARE", cfg5.getString("shape.name"));

        // 6. AdvancedRTP via Universal
        Path advDir = tempDir.resolve("plugins").resolve("AdvancedRTP_Uni");
        Files.createDirectories(advDir);
        Files.writeString(advDir.resolve("config.yml"),
                "cooldown: 75\n" +
                "worlds:\n" +
                "  end_world:\n" +
                "    radius: 6500\n" +
                "    min_radius: 500\n" +
                "    cost: 30.0\n");
        assertTrue(universal.canImport(advDir));
        Path out6 = tempDir.resolve("uni_out_adv");
        ImportResult res6 = universal.importConfiguration(advDir, out6, false);
        assertTrue(res6.isSuccess(), "AdvancedRTP universal import failed: " + res6.getErrors());
        Path reg6 = out6.resolve("regions").resolve("end_world_region.yml");
        assertTrue(Files.exists(reg6));
        RtpYamlConfig cfg6 = RtpYamlConfig.load(reg6.toFile());
        assertEquals(6500, cfg6.getInt("shape.radius"));
        assertEquals(500, cfg6.getInt("shape.centerRadius"));
        assertEquals(30.0, cfg6.getDouble("price"));
    }

    @Test
    @DisplayName("UniversalConfigImporter handles full BetterRTP configuration without dedicated importer")
    void testUniversalImporterHandlesFullBetterRtpConfig() throws IOException {
        UniversalConfigImporter universal = new UniversalConfigImporter();

        // An unknown plugin directory containing a full real-world BetterRTP config
        Path unknownPluginDir = tempDir.resolve("plugins").resolve("UnknownRandomTeleport_BetterRTPStructure");
        Files.createDirectories(unknownPluginDir);

        Files.writeString(unknownPluginDir.resolve("config.yml"),
                "Language-File: en.yml\n" +
                "Settings:\n" +
                "  MaxAttempts: 32\n" +
                "  RtpOnFirstJoin:\n" +
                "    Enabled: true\n" +
                "    SetAsRespawn: true\n" +
                "  Cooldown:\n" +
                "    Enabled: true\n" +
                "    LockAfter: 5\n" +
                "    Time: 600\n" +
                "  Delay:\n" +
                "    Enabled: true\n" +
                "    Time: 5\n" +
                "    CancelOnMove: true\n" +
                "Default:\n" +
                "  UseWorldBorder: false\n" +
                "  MaxRadius: 1000\n" +
                "  MinRadius: 10\n" +
                "  CenterX: 0\n" +
                "  CenterZ: 0\n" +
                "  Shape: square\n" +
                "DisabledWorlds:\n" +
                "  - prison\n" +
                "  - creative\n" +
                "CustomWorlds:\n" +
                "  - custom_world_1:\n" +
                "      MaxRadius: 2500\n" +
                "      MinRadius: 150\n" +
                "      Price: 50.0\n" +
                "      Shape: square\n" +
                "  - other_custom_world:\n" +
                "      MaxRadius: 8500\n" +
                "      MinRadius: 200\n" +
                "      CenterX: 123\n" +
                "      CenterZ: -123\n" +
                "      Price: 0.0\n" +
                "      Shape: circle\n" +
                "  - prison:\n" +
                "      MaxRadius: 500\n" +
                "      MinRadius: 50\n");

        assertTrue(universal.canImport(unknownPluginDir), "Universal importer should detect unknown folder with RTP markers");

        Path outDir = tempDir.resolve("unknown_better_rtp_out");
        ImportResult result = universal.importConfiguration(unknownPluginDir, outDir, false);

        assertTrue(result.isSuccess(), "Import must succeed: " + result.getErrors());

        // Verify custom worlds were mapped
        Path cw1File = outDir.resolve("regions").resolve("custom_world_1_region.yml");
        Path ocwFile = outDir.resolve("regions").resolve("other_custom_world_region.yml");
        Path prisonFile = outDir.resolve("regions").resolve("prison_region.yml");

        assertTrue(Files.exists(cw1File), "custom_world_1_region.yml must be created");
        assertTrue(Files.exists(ocwFile), "other_custom_world_region.yml must be created");
        assertFalse(Files.exists(prisonFile), "prison_region.yml must NOT be created because prison is in DisabledWorlds");

        RtpYamlConfig cw1Cfg = RtpYamlConfig.load(cw1File.toFile());
        assertEquals("SQUARE", cw1Cfg.getString("shape.name"));
        assertEquals(2500, cw1Cfg.getInt("shape.radius"));
        assertEquals(150, cw1Cfg.getInt("shape.centerRadius"));
        assertEquals(50.0, cw1Cfg.getDouble("price"));

        RtpYamlConfig ocwCfg = RtpYamlConfig.load(ocwFile.toFile());
        assertEquals("CIRCLE", ocwCfg.getString("shape.name"));
        assertEquals(8500, ocwCfg.getInt("shape.radius"));
        assertEquals(200, ocwCfg.getInt("shape.centerRadius"));
        assertEquals(123, ocwCfg.getInt("shape.centerX"));
        assertEquals(-123, ocwCfg.getInt("shape.centerZ"));

        // Verify global destination config.yml extracted nested Settings correctly
        Path globalFile = outDir.resolve("config.yml");
        assertTrue(Files.exists(globalFile));
        RtpYamlConfig globalCfg = RtpYamlConfig.load(globalFile.toFile());
        assertEquals(600L, globalCfg.getLong("teleportCooldown"));
        assertEquals(5L, globalCfg.getLong("teleportDelay"));
        assertEquals(5L, globalCfg.getLong("lockAfterUses"));
        assertTrue(globalCfg.getBoolean("cancelOnMove"));
        assertTrue(globalCfg.getBoolean("setRespawnOnTeleport"));
        assertTrue(globalCfg.getBoolean("rtpOnFirstJoin"));
    }

    @Test
    @DisplayName("UniversalConfigImporter imports real-world AdvancedRTP from testServer plugins")
    void testUniversalImporterWithTestServerAdvancedRtp() throws IOException {
        Path advTestServerPath = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.3\\plugins\\AdvancedRTP");
        if (!Files.isDirectory(advTestServerPath)) return;

        UniversalConfigImporter importer = new UniversalConfigImporter();
        assertTrue(importer.canImport(advTestServerPath));

        Path outDir = tempDir.resolve("advancedrtp_testserver_out");
        ImportResult result = importer.importConfiguration(advTestServerPath, outDir, false);
        assertTrue(result.isSuccess(), "Import must succeed: " + result.getErrors());
        assertEquals("AdvancedRTP", result.getSourceName());

        // Verify default world region was created with AdvancedRTP params
        Path regFile = outDir.resolve("regions").resolve("world_region.yml");
        assertTrue(Files.exists(regFile), "world_region.yml must exist");
        RtpYamlConfig regCfg = RtpYamlConfig.load(regFile.toFile());
        assertEquals("world", regCfg.getString("world"));
        assertEquals(2000, regCfg.getInt("shape.radius"));
        assertEquals(100, regCfg.getInt("shape.centerRadius"));
        assertEquals(34, regCfg.getInt("vert.minY"));
        assertEquals(94, regCfg.getInt("vert.maxY"));

        // Verify blacklisted worlds were skipped
        assertFalse(Files.exists(outDir.resolve("regions").resolve("events_region.yml")));
        assertFalse(Files.exists(outDir.resolve("regions").resolve("admin_world_region.yml")));
        assertFalse(Files.exists(outDir.resolve("regions").resolve("dungeon_region.yml")));

        // Verify global config extracted cooldown and delay
        Path destConfig = outDir.resolve("config.yml");
        assertTrue(Files.exists(destConfig));
        RtpYamlConfig cfg = RtpYamlConfig.load(destConfig.toFile());
        assertEquals(10L, cfg.getLong("teleportCooldown"));
        assertEquals(3L, cfg.getLong("teleportDelay"));
        assertEquals(10, cfg.getInt("maxAttempts"));
    }

    @Test
    @DisplayName("UniversalConfigImporter imports real-world AsyRTP from testServer plugins")
    void testUniversalImporterWithTestServerAsyRtp() throws IOException {
        Path asyTestServerPath = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.3\\plugins\\AsyRTP");
        if (!Files.isDirectory(asyTestServerPath)) return;

        UniversalConfigImporter importer = new UniversalConfigImporter();
        assertTrue(importer.canImport(asyTestServerPath));

        Path outDir = tempDir.resolve("asyrtp_testserver_out");
        ImportResult result = importer.importConfiguration(asyTestServerPath, outDir, false);
        assertTrue(result.isSuccess(), "Import must succeed: " + result.getErrors());
        assertEquals("AsyRTP", result.getSourceName());

        // Verify gui.worlds sections were extracted
        Path worldReg = outDir.resolve("regions").resolve("world_region.yml");
        Path netherReg = outDir.resolve("regions").resolve("nether_region.yml");
        Path endReg = outDir.resolve("regions").resolve("end_region.yml");

        assertTrue(Files.exists(worldReg), "world_region.yml must exist");
        assertTrue(Files.exists(netherReg), "nether_region.yml must exist");
        assertTrue(Files.exists(endReg), "end_region.yml must exist");

        RtpYamlConfig worldCfg = RtpYamlConfig.load(worldReg.toFile());
        assertEquals(10000, worldCfg.getInt("shape.radius"));
        assertEquals(0, worldCfg.getInt("shape.centerRadius"));

        RtpYamlConfig netherCfg = RtpYamlConfig.load(netherReg.toFile());
        assertEquals(900, netherCfg.getInt("shape.radius"));
        assertEquals(150, netherCfg.getInt("shape.centerRadius"));
        assertEquals(3500.0, netherCfg.getDouble("price"));

        RtpYamlConfig endCfg = RtpYamlConfig.load(endReg.toFile());
        assertEquals(1200, endCfg.getInt("shape.radius"));
        assertEquals(200, endCfg.getInt("shape.centerRadius"));
        assertEquals(5000.0, endCfg.getDouble("price"));

        // Verify global config
        Path destConfig = outDir.resolve("config.yml");
        assertTrue(Files.exists(destConfig));
        RtpYamlConfig cfg = RtpYamlConfig.load(destConfig.toFile());
        assertEquals(60L, cfg.getLong("teleportCooldown"));
    }

    @Test
    @DisplayName("UniversalConfigImporter canImport and importConfiguration edge cases")
    void testUniversalImporterEdgeCases() throws IOException {
        UniversalConfigImporter importer = new UniversalConfigImporter();
        assertEquals("universal", importer.sourceName());
        assertTrue(importer.directoryAliases().contains("universal"));
        assertTrue(importer.indicatorFiles().contains("config.yml"));

        // Null and non-directory
        assertFalse(importer.canImport(null));
        Path notDir = tempDir.resolve("file.txt");
        Files.writeString(notDir, "hello");
        assertFalse(importer.canImport(notDir));

        // canImport false on empty directory
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        assertFalse(importer.canImport(emptyDir));

        // importConfiguration fails when canImport is false
        Path outDir = tempDir.resolve("out_empty");
        ImportResult failResult = importer.importConfiguration(emptyDir, outDir, false);
        assertFalse(failResult.isSuccess());

        // canImport returns true when indicator file exists
        Path indicatorDir = tempDir.resolve("indicator");
        Files.createDirectories(indicatorDir);
        Files.writeString(indicatorDir.resolve("rtp.yml"), "teleport: true\n");
        assertTrue(importer.canImport(indicatorDir));

        // canImport returns true when common subdir has yaml
        Path subDirPlugin = tempDir.resolve("subdir_plugin");
        Path worldsSub = subDirPlugin.resolve("worlds");
        Files.createDirectories(worldsSub);
        Files.writeString(worldsSub.resolve("w.yml"), "radius: 1000\n");
        assertTrue(importer.canImport(subDirPlugin));

        // canImport returns true when any yml has RTP markers
        Path markerDir = tempDir.resolve("marker_plugin");
        Files.createDirectories(markerDir);
        Files.writeString(markerDir.resolve("custom.yml"), "min_radius: 100\nmax_radius: 5000\n");
        assertTrue(importer.canImport(markerDir));

        // Test root config with blacklisted_worlds and default_worlds
        Path customRtpDir = tempDir.resolve("custom_rtp");
        Files.createDirectories(customRtpDir);
        Files.writeString(customRtpDir.resolve("config.yml"),
                "blacklisted_worlds: [dungeon]\n" +
                "default_worlds: [world]\n" +
                "min-radius: 50\n" +
                "max-radius: 1500\n" +
                "cooldown:\n" +
                "  duration: 25\n" +
                "  lockafter: 4\n" +
                "delay:\n" +
                "  duration: 2\n" +
                "  cancel-on-move: true\n" +
                "settings:\n" +
                "  cooldown:\n" +
                "    duration: 30\n" +
                "    lockafter: 5\n" +
                "  delay:\n" +
                "    duration: 4\n" +
                "    cancel-on-move: true\n" +
                "  set-respawn: true\n" +
                "  first-join:\n" +
                "    enabled: true\n" +
                "    set-respawn: true\n" +
                "on-death: true\n");

        Path customOut = tempDir.resolve("custom_out");
        ImportResult customRes = importer.importConfiguration(customRtpDir, customOut, false);
        assertTrue(customRes.isSuccess(), "Import should succeed: " + customRes.getErrors());
        assertTrue(Files.exists(customOut.resolve("regions").resolve("world_region.yml")));
        assertFalse(Files.exists(customOut.resolve("regions").resolve("dungeon_region.yml")));
        RtpYamlConfig customCfg = RtpYamlConfig.load(customOut.resolve("config.yml").toFile());
        assertEquals(30L, customCfg.getLong("teleportCooldown"));
        assertEquals(4L, customCfg.getLong("teleportDelay"));
        assertEquals(5L, customCfg.getLong("lockAfterUses"));
        assertTrue(customCfg.getBoolean("cancelOnMove"));
        assertTrue(customCfg.getBoolean("setRespawnOnTeleport"));
        assertTrue(customCfg.getBoolean("rtpOnFirstJoin"));
        assertTrue(customCfg.getBoolean("rtpOnDeath"));
    }
}
