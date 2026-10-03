package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.action.ParameterType;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionConfigLoader Deep Branch Coverage Tests")
class ActionConfigLoaderDeepCoverageTest {

    @Test
    @DisplayName("Test various trigger declarations, nested map triggers, and string pos formats")
    void testParseTriggerVariations() {
        String yamlText = """
                triggers:
                  trigger_one:
                    type: "STEP_IN"
                    world: "overworld"
                    pos1: "world,10,60,10"
                    pos2: "world,20,70,20"
                    cooldown: "5s"
                    batchInterval: "2s"
                  trigger_two:
                    type: "PRESSURE_PLATE"
                    worldName: "nether"
                    min: "10,20,30"
                    max: "40,50,60"
                    cooldownSeconds: 10
                    interval: 1
                  trigger_three:
                    type: "UNKNOWN_TYPE"
                    x1: 1
                    y1: 2
                    z1: 3
                    x2: 4
                    y2: 5
                    z2: 6
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("trigger_test", yaml);
        assertNotNull(def);
        assertEquals(3, def.triggers().size());

        PhysicalTriggerSpec t1 = def.triggers().get(0);
        assertEquals("trigger_one", t1.id());
        assertEquals("overworld", t1.worldName());
        assertEquals(10, t1.minX());
        assertEquals(60, t1.minY());
        assertEquals(10, t1.minZ());
        assertEquals(20, t1.maxX());
        assertEquals(70, t1.maxY());
        assertEquals(20, t1.maxZ());
        assertEquals(5L, t1.cooldownSeconds());
        assertEquals(2L, t1.batchIntervalSeconds());

        PhysicalTriggerSpec t2 = def.triggers().get(1);
        assertEquals("trigger_two", t2.id());
        assertEquals("nether", t2.worldName());
        assertEquals(10, t2.minX());
        assertEquals(20, t2.minY());
        assertEquals(30, t2.minZ());
        assertEquals(40, t2.maxX());
        assertEquals(50, t2.maxY());
        assertEquals(60, t2.maxZ());

        PhysicalTriggerSpec t3 = def.triggers().get(2);
        assertEquals(PhysicalTriggerSpec.TriggerType.STEP_IN, t3.type());
        assertEquals(1, t3.minX());
        assertEquals(4, t3.maxX());
    }

    @Test
    @DisplayName("Test list of trigger maps with map pos and single trigger map")
    void testTriggerListAndMapPos() {
        String yamlText = """
                trigger:
                  - id: "list_t1"
                    type: "SENSOR"
                    world: "custom"
                    pos1:
                      x: 100
                      y: 64
                      z: 100
                    pos2:
                      x: 120
                      y: 80
                      z: 120
                    cooldown: -1
                    batch_interval: "3s"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("list_trigger_test", yaml);
        assertNotNull(def);
        assertEquals(1, def.triggers().size());
        PhysicalTriggerSpec t1 = def.triggers().get(0);
        assertEquals("list_t1", t1.id());
        assertEquals(100, t1.minX());
        assertEquals(120, t1.maxX());
        assertEquals(0L, t1.cooldownSeconds());
        assertEquals(3L, t1.batchIntervalSeconds());
    }

    @Test
    @DisplayName("Test single trigger map form")
    void testSingleTriggerMap() {
        String yamlText = """
                triggers:
                  id: "single_t"
                  type: "STEP_IN"
                  world: "world"
                  minX: 5
                  minY: 10
                  minZ: 15
                  maxX: 25
                  maxY: 30
                  maxZ: 35
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("single_t_test", yaml);
        assertNotNull(def);
        assertEquals(1, def.triggers().size());
        assertEquals("single_t", def.triggers().get(0).id());
        assertEquals(5, def.triggers().get(0).minX());
        assertEquals(25, def.triggers().get(0).maxX());
    }

    @Test
    @DisplayName("Test confinement boundary options, leash, shrink, and string outsideActions")
    void testConfinementDetails() {
        String yamlText = """
                confinement:
                  boundary: "LEASH"
                  leashRadius: 48.5
                  duration: "5m"
                  initialSize: "100b"
                  shrinkTo: "20b"
                  shrinkOver: "3m"
                  cancellable: false
                  damage: "5.5"
                  damageBuffer: "1.5"
                  damageIntervalSeconds: 2
                  outsideLimit: 12.0
                  outsideAction: "PULL_BACK"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("conf_test", yaml);
        assertNotNull(def);
        ActionDefinition.ConfinementSpec conf = def.confinement();
        assertEquals(ConfinementBoundary.LEASH, conf.boundary());
        assertEquals(48.5, conf.leashRadius());
        assertEquals(300L, conf.durationSeconds());
        assertEquals(100.0, conf.initialSize());
        assertEquals(20.0, conf.shrinkTo());
        assertEquals(180L, conf.shrinkOverSeconds());
        assertFalse(conf.cancellable());
        assertEquals(5.5, conf.damageAmount());
        assertEquals(1.5, conf.damageBuffer());
        assertEquals(2L, conf.damageIntervalSeconds());
        assertEquals(12.0, conf.maxDistanceOutside());
        assertEquals(1, conf.outsideActions().size());
    }

    @Test
    @DisplayName("Test confinement shape string and alternate outside action properties")
    void testConfinementShapeScalar() {
        String yamlText = """
                confinement:
                  shape: "HEXAGON"
                  radius: "50b"
                  centerRadius: "10b"
                  onOutsideLimit:
                    - CONSOLE: "say player outside"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("shape_scalar", yaml);
        assertNotNull(def);
        ActionDefinition.ConfinementSpec conf = def.confinement();
        assertEquals(ConfinementBoundary.SHAPE, conf.boundary());
        assertEquals("HEXAGON", conf.shapeName());
        assertEquals(50, conf.radius());
        assertEquals(10, conf.centerRadius());
        assertEquals(1, conf.outsideActions().size());
    }

    @Test
    @DisplayName("Test placement parameters map and separations")
    void testPlacementParameters() {
        String yamlText = """
                placement:
                  region: "mining"
                  shape: "CIRCLE"
                  minSeparation: 25
                  elevationTolerance: 5
                  clusterSeparation: 15
                  teammateSeparation: 10
                  groupSeparation: 50
                  parameters:
                    customKey: "customVal"
                  anchor: "RESPAWN_ANCHOR"
                  retries: 7
                  cacheSize: 20
                  enabled: "true"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("placement_test", yaml);
        assertNotNull(def);
        ActionDefinition.PlacementSpec p = def.placement();
        assertTrue(p.enabled());
        assertEquals("mining", p.region());
        assertEquals("CIRCLE", p.shapeName());
        assertEquals(25, p.minSeparation());
        assertEquals(5, p.elevationTolerance());
        assertEquals(7, p.retries());
        assertEquals(20, p.cacheSize());
        assertEquals(15, p.parameters().get("clusterSeparation"));
        assertEquals(10, p.parameters().get("teammateSeparation"));
        assertEquals(50, p.parameters().get("groupSeparation"));
        assertEquals("RESPAWN_ANCHOR", p.parameters().get("anchor"));
        assertEquals("customVal", p.parameters().get("customKey"));
    }

    @Test
    @DisplayName("Test lifecycle gated steps with delays and for-each action")
    void testLifecycleGatedStepsAndForEach() {
        String yamlText = """
                lifecycle:
                  onStart:
                    - delay: "2s"
                      gate:
                        permission: "vip.access"
                      run:
                        - MESSAGE: "Welcome VIP!"
                        - TELL: "Enjoy"
                        - MSG: "Direct message"
                    - delaySeconds: 5
                      CONSOLE: "say Delayed start"
                    - FOR_EACH:
                        - PLAYER: "jump"
                        - MESSAGE: "Each player jumps"
                  onWait:
                    - MESSAGE: "Waiting in queue"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("life_test", yaml);
        assertNotNull(def);
        ActionDefinition.LifecycleSpec life = def.lifecycle();
        assertEquals(3, life.onStart().size());

        ActionDefinition.LifecycleStep step1 = life.onStart().get(0);
        assertEquals(2L, step1.delaySeconds());
        assertEquals("vip.access", step1.gateConfig().get("permission"));
        assertEquals(3, step1.actions().size());

        ActionDefinition.LifecycleStep step2 = life.onStart().get(1);
        assertEquals(5L, step2.delaySeconds());
        assertEquals(1, step2.actions().size());

        ActionDefinition.LifecycleStep step3 = life.onStart().get(2);
        assertEquals(1, step3.actions().size());
        assertEquals(ActionDefinition.ActionType.FOR_EACH, step3.actions().get(0).type());

        assertEquals(1, life.onEnqueue().size());
    }

    @Test
    @DisplayName("Test command aliases single string and parameter list parsing")
    void testCommandAliasesAndParameters() {
        String yamlText = """
                command:
                  name: "hunt"
                  aliases: "bounty"
                  parameters:
                    - name: "target"
                      type: "PLAYER"
                    - name: "bounty"
                      type: "NUMBER"
                      default: "100"
                """;
        RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
        ActionDefinition def = ActionConfigLoader.parseDefinition("cmd_test", yaml);
        assertNotNull(def);
        ActionDefinition.CommandSpec cmd = def.command();
        assertEquals("hunt", cmd.name());
        assertEquals(List.of("bounty"), cmd.aliases());
        assertEquals(2, cmd.parameters().size());
        assertEquals("target", cmd.parameters().get(0).name());
        assertEquals(ParameterType.PLAYER, cmd.parameters().get(0).type());
        assertEquals("bounty", cmd.parameters().get(1).name());
        assertEquals("100", cmd.parameters().get(1).defaultValue());
    }

    @Test
    @DisplayName("Test loadActions from File directory")
    void testLoadActionsFromFileDir(@org.junit.jupiter.api.io.TempDir File tempDir) throws Exception {
        File actionsDir = new File(tempDir, "definitions/actions");
        assertTrue(actionsDir.mkdirs());

        File file1 = new File(actionsDir, "action1.yml");
        Files.writeString(file1.toPath(), "alias: \"act1\"\nplacement: false\n");

        File file2 = new File(actionsDir, "action2.yml");
        Files.writeString(file2.toPath(), "alias: \"act2\"\npermission: \"perm.act2\"\n");

        ActionManager manager = new ActionManager();
        ActionConfigLoader.loadActions(tempDir, manager);

        assertNotNull(manager.getAction("action1"));
        assertNotNull(manager.getAction("action2"));
    }
}
