package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionConfigLoaderBranchesTest {

  @Test
  void testDisabledPlacements() {
    for (String val : List.of("none", "false", "disabled", "FALSE")) {
      String yaml = "alias: test\nplacement: " + val;
      ActionDefinition def = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yaml));
      assertSame(ActionDefinition.PlacementSpec.DISABLED, def.placement());
    }

    String yamlBool = "alias: test\nplacement: false";
    ActionDefinition defBool = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yamlBool));
    assertSame(ActionDefinition.PlacementSpec.DISABLED, defBool.placement());
  }

  @Test
  void testPlacementComplexParameters() {
    String yaml = """
        alias: test
        placement:
          region: "custom_region"
          enabled: false
          minSeparation: 25
          elevationTolerance: 12
          clusterSeparation: 30
          teammateSeparation: 15
          groupSeparation: 40
          anchor: "PLAYER"
          shape:
            name: "CIRCLE"
            radius: "128"
            centerRadius: "32"
            weight: 2.5
          parameters:
            customParam: 100
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yaml));
    assertFalse(def.placement().enabled());
    assertEquals("custom_region", def.placement().region());
    assertEquals("CIRCLE", def.placement().shapeName());
    assertEquals(128, def.placement().radius());
    assertEquals(32, def.placement().centerRadius());
    assertEquals(25, def.placement().minSeparation());
    assertEquals(12, def.placement().elevationTolerance());
    assertEquals(30, def.placement().parameters().get("clusterSeparation"));
    assertEquals(15, def.placement().parameters().get("teammateSeparation"));
    assertEquals(40, def.placement().parameters().get("groupSeparation"));
    assertEquals("PLAYER", def.placement().parameters().get("anchor"));
    assertEquals(2.5, def.placement().parameters().get("weight"));
    assertEquals(100, def.placement().parameters().get("customParam"));
  }

  @Test
  void testConfinementBoundariesAndFallbacks() {
    for (String b : List.of("REGION", "LEASH", "SHAPE", "SUBSPACE", "UNKNOWN")) {
      String yaml = "confinement:\n  boundary: " + b + "\n  duration: 10s";
      ActionDefinition def = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yaml));
      assertNotNull(def.confinement().boundary());
    }

    String yamlStringShape = """
        confinement:
          shape: CIRCLE
        """;
    ActionDefinition defShape = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yamlStringShape));
    assertEquals(ConfinementBoundary.SHAPE, defShape.confinement().boundary());
    assertEquals("CIRCLE", defShape.confinement().shapeName());
  }

  @Test
  void testCommandAndSubcommandsAndParameters() {
    String yaml = """
        command:
          alias: "main_alias"
          permission: "perm.main"
          description: "main desc"
          aliases: "single_alias"
          parameters:
            - name: "target"
              type: "PLAYER"
              required: true
              permission: "perm.param"
              default: ""
            - name: "count"
              type: "NUMBER"
              default: "5"
          subcommands:
            join:
              permission: "perm.join"
              description: "join sub"
              aliases:
                - "j"
                - "enter"
              run:
                - ACTION: "START"
            quit:
              actions:
                - ACTION: "CANCEL"
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yaml));
    assertTrue(def.command().isConfigured());
    assertEquals("main_alias", def.command().name());
    assertEquals(List.of("single_alias"), def.command().aliases());
    assertEquals(2, def.command().parameters().size());
    assertTrue(def.command().parameters().get(0).required());

    assertEquals(2, def.command().subcommands().size());
    ActionDefinition.SubcommandSpec joinSub = def.command().subcommands().get("join");
    assertNotNull(joinSub);
    assertEquals("perm.join", joinSub.permission());
    assertEquals(List.of("j", "enter"), joinSub.aliases());
    assertEquals(1, joinSub.actions().size());
  }

  @Test
  void testTriggersAllVariants() {
    String yaml = """
        triggers:
          - id: "t_radius"
            type: "RADIUS"
            world: "world"
            x: 100
            y: 64
            z: 200
            radius: 15
            cooldown: "1m"
            batchInterval: "5s"
          - id: "t_box"
            type: "STEP_IN"
            worldName: "world_nether"
            pos1: "1,2,3"
            pos2: "world_nether,4,5,6"
            cooldownSeconds: "20s"
            batch_interval: "2s"
          - type: "PORTAL"
            min:
              x: "10"
              y: 20
              z: 30
            max:
              x: 40
              y: "50"
              z: 60
            interval: 10
          - minX: 1
            minY: 2
            minZ: 3
            maxX: 4
            maxY: 5
            maxZ: 6
            cooldown: -5
            interval: -1
          - pos1: "invalid"
            pos2: "bad"
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yaml));
    assertTrue(def.triggers().size() >= 3);
  }

  @Test
  void testLifecycleEveryEventAndActionTypes() {
    String yaml = """
        lifecycle:
          onStart:
            - CONSOLE: "say start"
            - PLAYER: "say player"
            - MESSAGE: "private message"
            - TELL: "tell message"
            - MSG: "msg message"
            - ACTION: "PULL_BACK"
            - FOR_EACH:
                CONSOLE: "tag [player] add test"
            - FOR_EACH:
                - CONSOLE: "list item"
            - gates:
                time:
                  elapsed: ">= 5s"
              run:
                - CONSOLE: "gated run"
          onTick:
            - CONSOLE: "tick"
          onBoundaryViolation:
            - ACTION: PULL_BACK
          onBoundaryEnter:
            - MESSAGE: "entered"
          onExpire:
            - CONSOLE: "expired"
          onInterrupt:
            - CONSOLE: "interrupted"
          onComplete:
            - CONSOLE: "completed"
          onDeath:
            - CONSOLE: "death"
          onCancel:
            - CONSOLE: "cancelled"
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("test", RtpYamlConfig.parse(yaml));
    assertNotNull(def.lifecycle());
    assertFalse(def.lifecycle().onStart().isEmpty());
    assertFalse(def.lifecycle().onBoundaryViolation().isEmpty());
    assertFalse(def.lifecycle().onExpire().isEmpty());
    assertFalse(def.lifecycle().onDeath().isEmpty());
  }

  @Test
  void testLoadActionsFromDirectory(@TempDir Path tempDir) throws Exception {
    Path actionsDir = tempDir.resolve("definitions/actions");
    Files.createDirectories(actionsDir);
    Path actionFile = actionsDir.resolve("test_action.yml");
    Files.writeString(actionFile, """
        alias: "test_action"
        permission: "rtp.action.test"
        """);

    ActionManager manager = new ActionManager();
    ActionConfigLoader.loadActions(tempDir.toFile(), manager);
    assertTrue(manager.getAction("test_action").isPresent());

    // Null and empty checks
    ActionConfigLoader.loadActions((File) null, manager);
    ActionConfigLoader.loadActions(new File(tempDir.toFile(), "nonexistent"), manager);
  }

  @Test
  void testParseTriggersFromMapOfMaps() {
    String yaml = """
        triggers:
          first_trigger:
            type: "STEP_IN"
            world: "world"
            minX: 0
            maxX: 10
          second_trigger:
            type: "PORTAL"
            world: "world_nether"
            x1: 20
            x2: 30
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("map_triggers", RtpYamlConfig.parse(yaml));
    assertEquals(2, def.triggers().size());
  }

  @Test
  void testLifecycleGatedAndUngatedSteps() {
    String yaml = """
        lifecycle:
          onStart:
            - delay: "2s"
              gate:
                permission: "rtp.vip"
              run:
                - CONSOLE: "say vip"
            - delaySeconds: 5
              gate:
                score: 10
              run:
                - PLAYER: "say hello"
            - delay: 1
              CONSOLE: "say simple"
            - FOR_EACH:
                CONSOLE: "tag [player] add p"
            - FOR_EACH:
                - CONSOLE: "list 1"
                - MESSAGE: "msg 2"
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("steps_test", RtpYamlConfig.parse(yaml));
    assertNotNull(def);
    assertEquals(5, def.lifecycle().onStart().size());
  }
  @Test
  void testParseDefinitionNulls() {
    ActionDefinition def = ActionConfigLoader.parseDefinition("null_test", (RtpYamlSection) null);
    assertEquals("null_test", def.id());
    assertEquals(ActionDefinition.PlacementSpec.DEFAULT, def.placement());

    ActionDefinition defNullParser = ActionConfigLoader.parseDefinition("null_parser", (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys>) null);
    assertEquals("null_parser", defNullParser.id());
  }

  @Test
  void testTriggersWithMapCoordsAndStringNumbers() {
    String yaml = """
        triggers:
          - id: "map_coords"
            type: "STEP_IN"
            world: "world"
            pos1:
              x: "15"
              y: "65"
              z: "15"
            pos2:
              x: 25
              y: 75
              z: 25
            cooldown: "5"
            batchInterval: "1"
          - id: "invalid_trigger"
            type: "INVALID_UNKNOWN_TYPE"
            world: "world"
            pos1: "invalid_coords"
            pos2: "also_invalid"
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("coords_test", RtpYamlConfig.parse(yaml));
    assertNotNull(def);
    assertEquals(2, def.triggers().size());
  }

  @Test
  void testConfinementDamageAndOutsideActionsVariants() {
    String yaml = """
        confinement:
          boundary: "LEASH"
          duration: 20
          damage: 5.0
          damageBuffer: 2.5
          damageIntervalSeconds: 3
          outsideLimit: 15.0
          outsideAction:
            - ACTION: "PULL_BACK"
          cancellable: false
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("damage_test", RtpYamlConfig.parse(yaml));
    assertNotNull(def);
    assertFalse(def.confinement().cancellable());
    assertEquals(5.0, def.confinement().damageAmount());
    assertEquals(2.5, def.confinement().damageBuffer());
    assertEquals(3L, def.confinement().damageIntervalSeconds());
    assertEquals(15.0, def.confinement().maxDistanceOutside());

    String yamlSingleOutsideAction = """
        confinement:
          boundary: "REGION"
          damageAmount: "3.5"
          maxOutsideDistance: "20.5"
          outsideAction: "PULL_BACK"
        """;
    ActionDefinition defSingle = ActionConfigLoader.parseDefinition("single_test", RtpYamlConfig.parse(yamlSingleOutsideAction));
    assertNotNull(defSingle);
    assertEquals(3.5, defSingle.confinement().damageAmount());
    assertEquals(20.5, defSingle.confinement().maxDistanceOutside());
    assertEquals(1, defSingle.confinement().outsideActions().size());

    String yamlMapOutsideAction = """
        confinement:
          boundary: "SHAPE"
          onOutsideLimit:
            ACTION: "PULL_BACK"
        """;
    ActionDefinition defMap = ActionConfigLoader.parseDefinition("map_test", RtpYamlConfig.parse(yamlMapOutsideAction));
    assertNotNull(defMap);
    assertEquals(1, defMap.confinement().outsideActions().size());
  }

  @Test
  void testLoadActionsFromMultiConfigParser(@TempDir Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    ActionConfigLoader.loadActions((io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys>) null, new ActionManager());

    io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys> actionsParser =
        new io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<>(
            io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys.class,
            "actions", "1.0", tempDir.toFile(), "definitions/actions", "io/github/dailystruggle/rtp/common/configuration/defaults/actions");
    ActionManager mgr = new ActionManager();
    ActionConfigLoader.loadActions(actionsParser, mgr);
    assertNotNull(mgr.getActionIds());
  }

  @Test
  void testParseDefinitionWithFullLifecycleAndPlacement() {
    String yaml = """
        alias: "full_action"
        permission: "rtp.action.full"
        description: "Full action description"
        icon: "DIAMOND"
        title: "Action Title"
        placement:
          enabled: true
          region: "default"
          shapeName: "SQUARE"
          radius: 500
          centerRadius: 50
          minSeparation: 10
          elevationTolerance: 5
          retries: 3
          cacheSize: 20
          clusterSeparation: 15
          teammateSeparation: 12
          groupSeparation: 25
          anchor: "PLAYER"
          shape:
            name: "SQUARE"
            radius: 500
            centerRadius: 50
            weight: 1.5
        confinement:
          boundary: "LEASH"
          duration: "30s"
          maxRadius: 100.0
          initialRadius: 50.0
          finalRadius: 20.0
          shrinkIntervalSeconds: 5
          pullBack: true
          shape:
            name: "CIRCLE"
            radius: 100
            centerRadius: 10
            expandRate: 1.5
            shrinkRate: 0.5
            interval: 2
            damage: 2.5
          onViolation:
            - ACTION: "PULL_BACK"
            - MESSAGE: "Do not leave the arena!"
        command:
          alias: "full"
          permission: "rtp.command.full"
          description: "Full command"
          aliases:
            - "f"
            - "fcmd"
          parameters:
            - name: "target"
              type: "PLAYER"
              required: true
              permission: "rtp.param.target"
              default: ""
            - name: "count"
              type: "NUMBER"
              required: false
              permission: ""
              default: "1"
            - name: "flag"
              type: "COORDINATE"
              default: "self"
            - name: "mode"
              type: "STRING"
              default: "fast"
          subcommands:
            start:
              permission: "rtp.sub.start"
              description: "Start action"
              aliases:
                - "begin"
              run:
                - ACTION: "START"
        triggers:
          - id: "step_trigger"
            type: "STEP_IN"
            world: "world"
            x1: 10
            y1: 60
            z1: 10
            x2: 20
            y2: 70
            z2: 20
            cooldown: "10s"
            batchInterval: "2s"
          - id: "portal_trigger"
            type: "PORTAL"
            worldName: "world_nether"
            pos1: "world_nether,0,64,0"
            pos2: "world_nether,5,68,5"
            cooldownSeconds: 15
            batch_interval: 1
        gates:
          - permission: "rtp.gate"
        lifecycle:
          onStart:
            - CONSOLE: "say Starting full action"
            - PLAYER: "me is ready"
            - MESSAGE: "Welcome to the action"
            - TELL: "Action began"
            - MSG: "Good luck"
            - BROADCAST: "Action started across server"
            - TITLE: "Fight! 10 20 10"
            - SOUND: "entity.experience_orb.pickup 1.0 1.0"
            - EFFECT: "speed 30 1"
            - FOR_EACH:
                CONSOLE: "tag [player] add full_participant"
            - IF_ELSE:
                if:
                  permission: "rtp.vip"
                then:
                  - CONSOLE: "say VIP player present"
                else:
                  - CONSOLE: "say Normal player present"
            - WAIT: "3s"
            - SET_SCORE:
                objective: "action_score"
                value: 100
            - ADD_SCORE:
                objective: "action_score"
                value: 10
            - RESET_SCORE: "action_score"
            - EXPAND: 10.0
            - SHRINK: 5.0
            - EXECUTE: "execute at [player] run particle flame ~ ~1 ~"
            - CANCEL: "Ending action"
            - DISARM: true
          onBoundaryViolation:
            - ACTION: "PULL_BACK"
            - MESSAGE: "Stay inside"
          onBoundaryEnter:
            - MESSAGE: "Entered zone"
          onExpire:
            - BROADCAST: "Action expired"
          onInterrupt:
            - BROADCAST: "Action interrupted"
          onComplete:
            - BROADCAST: "Action completed"
          onDeath:
            - BROADCAST: "A participant died"
          onCancel:
            - BROADCAST: "Action was cancelled"
        """;
    ActionDefinition def = ActionConfigLoader.parseDefinition("full_action", RtpYamlConfig.parse(yaml));
    assertNotNull(def);
    assertEquals("full_action", def.id());
    assertEquals("DIAMOND", def.icon());
    assertEquals("Action Title", def.title());
    assertTrue(def.placement().enabled());
    assertEquals("default", def.placement().region());
    assertEquals(ConfinementBoundary.LEASH, def.confinement().boundary());
    assertTrue(def.command().isConfigured());
    assertEquals(4, def.command().parameters().size());
    assertEquals(1, def.command().subcommands().size());
    assertEquals(2, def.triggers().size());
    assertEquals(1, def.gates().size());
    assertNotNull(def.lifecycle());
    assertFalse(def.lifecycle().onStart().isEmpty());
    assertFalse(def.lifecycle().onBoundaryViolation().isEmpty());
    assertFalse(def.lifecycle().onExpire().isEmpty());
    assertFalse(def.lifecycle().onDeath().isEmpty());
  }
}
