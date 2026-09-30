package io.github.dailystruggle.rtp.actionaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RTPActionAddonConfigTest {

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    RTPTestSetup.install(tempDir.toFile());
  }

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  @Test
  void testAddonRegistersActionMultiConfigParserAndDefaultFallback() throws IOException {
    Configs configs = new Configs(tempDir.toFile());
    RTP.configs = configs;
    configs.reloadConfigs();

    // Verify core reloadConfigs does NOT register ActionKeys
    assertNull(configs.getParser(ActionKeys.class), "Core reloadConfigs should not register ActionKeys");

    // Load addon
    RTPActionAddon addon = new RTPActionAddon();
    addon.onLoad();

    // Verify addon registers ActionKeys parser
    FactoryValue<ActionKeys> parser = configs.getParser(ActionKeys.class);
    assertNotNull(parser, "ActionKeys MultiConfigParser should be registered after addon onLoad()");
    assertTrue(parser instanceof MultiConfigParser);
    MultiConfigParser<ActionKeys> actionsMulti = (MultiConfigParser<ActionKeys>) parser;

    File definitions = new File(tempDir.toFile(), "definitions");
    File actionsDir = new File(definitions, "actions");
    assertTrue(actionsDir.isDirectory(), "definitions/actions directory should be created");

    // Verify bundled action templates are extracted to definitions/actions when LeafRTPActionAddon is active
    File defaultAction = new File(actionsDir, "default.yml");
    File arenaAction = new File(actionsDir, "arena.yml");
    File challengeAction = new File(actionsDir, "challenge.yml");
    File kothAction = new File(actionsDir, "koth.yml");
    File scatterAction = new File(actionsDir, "scatter.yml");
    assertTrue(defaultAction.exists(), "default.yml should be extracted from addon resources");
    assertTrue(arenaAction.exists(), "arena.yml should be extracted from addon resources");
    assertTrue(challengeAction.exists(), "challenge.yml should be extracted from addon resources");
    assertTrue(kothAction.exists(), "koth.yml should be extracted from addon resources");
    assertTrue(scatterAction.exists(), "scatter.yml should be extracted from addon resources");

    // Add a new dynamic action without custom template, asserting it inherits default.yml
    actionsMulti.addParser("test_action");
    ConfigParser<ActionKeys> customParser = actionsMulti.getParser("test_action");
    assertNotNull(customParser, "getParser should resolve test_action");
    File customFile = new File(actionsDir, "test_action.yml");
    assertTrue(customFile.exists(), "test_action.yml should be created on disk from default.yml template");

    addon.onUnload();
  }

  @Test
  void testAddonIngestsImportedZoneActionsWithMultipleTriggers() throws IOException {
    Configs configs = new Configs(tempDir.toFile());
    RTP.configs = configs;
    configs.reloadConfigs();

    // Prepare an imported zone action file with multiple physical triggers
    File actionsDir = new File(tempDir.toFile(), "definitions/actions");
    actionsDir.mkdirs();

    String zoneActionYaml = """
        alias: "zone_double_portal"
        permission: "rtp.action.zone.double_portal"
        triggers:
          portal_alpha:
            type: PORTAL
            world: world
            pos1: { x: 10, y: 64, z: 10 }
            pos2: { x: 15, y: 68, z: 15 }
            cooldown: 5s
            batchInterval: 10s
          portal_beta:
            type: STEP_IN
            world: world_nether
            pos1: { x: -50, y: 30, z: -50 }
            pos2: { x: -45, y: 35, z: -45 }
            cooldown: 12s
            batchInterval: 0s
        placement:
          region: "default"
          anchor: "regionQueue"
          shape:
            name: "CIRCLE"
            radius: 2000
            centerRadius: 100
        lifecycle:
          onStart:
            - CONSOLE: "say Portal triggered"
        version: "1.0"
        """;
    File zoneFile = new File(actionsDir, "zone_double_portal.yml");
    java.nio.file.Files.writeString(zoneFile.toPath(), zoneActionYaml);

    // Clean any prior triggers in RTP.triggerManager
    RTP.triggerManager.clear();

    // Load addon
    RTPActionAddon addon = new RTPActionAddon();
    addon.onLoad();

    // Verify action definition is ingested into ActionManager
    assertNotNull(RTP.actionManager, "ActionManager must be initialized");
    var optDef = RTP.actionManager.getAction("zone_double_portal");
    assertTrue(optDef.isPresent(), "zone_double_portal action should be loaded by RTPActionAddon");

    var def = optDef.get();
    assertEquals("zone_double_portal", def.alias());
    assertEquals("rtp.action.zone.double_portal", def.permission());
    assertEquals("default", def.placement().region());
    assertEquals("CIRCLE", def.placement().shapeName());
    assertEquals(2000, def.placement().radius());
    assertEquals(100, def.placement().centerRadius());

    // Verify both physical triggers are parsed in the action definition
    assertEquals(2, def.triggers().size(), "Both triggers should be parsed in ActionDefinition");

    // Verify both triggers are ingested and registered in RTP.triggerManager
    var triggerA = RTP.triggerManager.getTrigger("portal_alpha");
    assertNotNull(triggerA, "portal_alpha must be registered in PhysicalTriggerManager");
    assertEquals(io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.PORTAL, triggerA.type());
    assertEquals("world", triggerA.worldName());
    assertEquals(10, triggerA.minX());
    assertEquals(15, triggerA.maxX());
    assertEquals(5L, triggerA.cooldownSeconds());
    assertEquals(10L, triggerA.batchIntervalSeconds());
    assertEquals("zone_double_portal", triggerA.actionId());

    var triggerB = RTP.triggerManager.getTrigger("portal_beta");
    assertNotNull(triggerB, "portal_beta must be registered in PhysicalTriggerManager");
    assertEquals(io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.STEP_IN, triggerB.type());
    assertEquals("world_nether", triggerB.worldName());
    assertEquals(-50, triggerB.minX());
    assertEquals(-45, triggerB.maxX());
    assertEquals(12L, triggerB.cooldownSeconds());
    assertEquals(0L, triggerB.batchIntervalSeconds());
    assertEquals("zone_double_portal", triggerB.actionId());

    addon.onUnload();
    RTP.triggerManager.clear();
  }
}
