package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ActionConfigLoaderTest {

  @Test
  @DisplayName("ActionConfigLoader parses declarative action definition from YAML")
  void testParseDefinition() throws Exception {
    String yamlText = """
        alias: "duel"
        permission: "rtp.action.duel"
        description: "Challenge another player to a confined arena duel"

        command:
          name: "duel"
          permission: "rtp.command.duel"
          description: "Challenge duel"
          aliases:
            - "fight"
            - "1v1"

        placement:
          region: "pvp_world"
          shape:
            name: "SQUARE"
            radius: 4c
            centerRadius: 0
          minSeparation: 32
          elevationTolerance: 8

        confinement:
          boundary: SUBSPACE
          duration: 5m
          leashRadius: 48.0

        lifecycle:
          onStart:
            - FOR_EACH:
                CONSOLE: "tag [player] add rtp_session_[session_id]"
          onBoundaryViolation:
            - gate:
                scoreboard:
                  objective: "rtp_violations"
                  matches: "< 2"
              run:
                - ACTION: PULL_BACK
                - FOR_EACH:
                    PLAYER: "title [violator] warning"
          onExpire:
            - CONSOLE: "broadcast timeout"
            - ACTION: DISARM
        """;

    RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);

    ActionDefinition def = ActionConfigLoader.parseDefinition("duel", yaml);

    assertEquals("duel", def.id());
    assertEquals("duel", def.alias());
    assertEquals("rtp.action.duel", def.permission());

    assertTrue(def.command().isConfigured());
    assertEquals("duel", def.command().name());
    assertEquals("rtp.command.duel", def.command().permission());
    assertEquals("Challenge duel", def.command().description());
    assertEquals(java.util.List.of("fight", "1v1"), def.command().aliases());

    assertEquals("pvp_world", def.placement().region());
    assertEquals("SQUARE", def.placement().shapeName());
    assertEquals(64, def.placement().radius());
    assertEquals(32, def.placement().minSeparation());
    assertEquals(8, def.placement().elevationTolerance());

    assertEquals(ConfinementBoundary.SUBSPACE, def.confinement().boundary());
    assertEquals(300L, def.confinement().durationSeconds());
    assertEquals(48.0, def.confinement().leashRadius());

    assertEquals(1, def.lifecycle().onStart().size());
    assertEquals(1, def.lifecycle().onBoundaryViolation().size());
    assertEquals(2, def.lifecycle().onExpire().size());

    // Check gated step
    ActionDefinition.LifecycleStep violationStep = def.lifecycle().onBoundaryViolation().get(0);
    assertFalse(violationStep.gateConfig().isEmpty());
    assertEquals(2, violationStep.actions().size());
  }

  @Test
  @DisplayName("ActionConfigLoader parses custom confinement shape and radius")
  void testParseConfinementShape() throws Exception {
    String yamlText = """
        alias: "shaped_duel"
        placement:
          region: "pvp_world"
          shape:
            name: "SQUARE"
            radius: 4c
        confinement:
          duration: 3m
          shape:
            name: "CIRCLE"
            radius: 5c
            centerRadius: 1c
        """;

    RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
    ActionDefinition def = ActionConfigLoader.parseDefinition("shaped_duel", yaml);

    assertEquals(ConfinementBoundary.SHAPE, def.confinement().boundary());
    assertEquals(180L, def.confinement().durationSeconds());
    assertEquals("CIRCLE", def.confinement().shapeName());
    assertEquals(80, def.confinement().radius());
    assertEquals(16, def.confinement().centerRadius());
  }

  @Test
  @DisplayName("ActionConfigLoader loads actions from MultiConfigParser")
  void testExtractBundledActions(@TempDir Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    ActionManager manager = new ActionManager();
    MultiConfigParser<ActionKeys> actions =
        new MultiConfigParser<>(ActionKeys.class, "actions", "1.0", tempDir.toFile(), "definitions/actions", "en");
    ActionConfigLoader.loadActions(actions, manager);

    File actionsDir = new File(tempDir.toFile(), "definitions/actions");
    assertTrue(actionsDir.exists(), "definitions/actions directory should be created");

    File[] files = actionsDir.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("."));
    assertNotNull(files);
    for (File f : files) {
      String id = f.getName().substring(0, f.getName().length() - 4);
      assertTrue(manager.getAction(id).isPresent(), "Action " + id + " should be registered");
    }

    // Verify default.yml fallback and dynamic addParser creation seeded from default.yml
    actions.addParser("custom_action");
    assertTrue(actions.listParsers().contains("CUSTOM_ACTION") || actions.listParsers().contains("custom_action"));
    File customFile = new File(actionsDir, "custom_action.yml");
    assertTrue(customFile.exists(), "custom_action.yml should be created from default.yml template");
  }

  @Test
  @DisplayName("ActionConfigLoader handles nulls, empty files, and invalid formats safely")
  void testParseInvalidAndDefaults() {
    assertNotNull(ActionConfigLoader.parseDefinition("empty", (io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) null));

    RtpYamlConfig emptyYaml = RtpYamlConfig.parse("");
    ActionDefinition emptyDef = ActionConfigLoader.parseDefinition("empty", emptyYaml);
    assertEquals("empty", emptyDef.id());
    assertEquals(ActionDefinition.PlacementSpec.DEFAULT, emptyDef.placement());
    assertEquals(ActionDefinition.ConfinementSpec.DEFAULT, emptyDef.confinement());

    // Null and empty loader calls
    ActionConfigLoader.loadActions((File) null, null);
    ActionConfigLoader.loadActions((File) null, new ActionManager());
  }

  @Test
  @DisplayName("ActionConfigLoader parses gate conditions from ConfigParser")
  void testParseGatesFromConfigParser(@TempDir Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    MultiConfigParser<ActionKeys> actions =
        new MultiConfigParser<>(ActionKeys.class, "actions", "1.0", tempDir.toFile(), "definitions/actions", "en");

    io.github.dailystruggle.rtp.common.configuration.ConfigParser<ActionKeys> parser = actions.getParser("default");
    assertNotNull(parser);

    parser.set(ActionKeys.gate, java.util.List.of(java.util.Map.of("players", ">= 2")));

    ActionDefinition def = ActionConfigLoader.parseDefinition("challenge", parser);
    assertNotNull(def);
    assertEquals(1, def.gates().size());
    assertEquals(">= 2", def.gates().get(0).get("players"));
  }

  @Test
  @DisplayName("ActionConfigLoader loads bundled challenge gates from MultiConfigParser")
  void testBundledChallengeGates(@TempDir Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());

    // Copy shipped challenge.yml into tempDir/definitions/actions/
    File shippedActions = new File("../rtp-plugin/src/main/resources/definitions/actions");
    if (!shippedActions.exists()) {
      shippedActions = new File("rtp-plugin/src/main/resources/definitions/actions");
    }
    File targetDir = new File(tempDir.toFile(), "definitions/actions");
    targetDir.mkdirs();
    File challengeSrc = new File(shippedActions, "challenge.yml");
    if (challengeSrc.exists()) {
      try {
        java.nio.file.Files.copy(challengeSrc.toPath(), new File(targetDir, "challenge.yml").toPath());
      } catch (Exception ignored) {
      }
    }

    ActionManager manager = new ActionManager();
    MultiConfigParser<ActionKeys> actions =
        new MultiConfigParser<>(ActionKeys.class, "actions", "1.0", tempDir.toFile(), "definitions/actions", "en");
    ActionConfigLoader.loadActions(actions, manager);

    ActionDefinition challenge = manager.getAction("challenge").orElse(null);
    assertNotNull(challenge, "Challenge action should be loaded");
    assertFalse(challenge.gates().isEmpty(), "Challenge action should have gate conditions loaded");
    assertTrue(challenge.gates().size() >= 1, "Challenge action should have at least 1 gate");
    assertEquals(">= 2", challenge.gates().get(0).get("players"));
  }

  @Test
  @DisplayName("ActionConfigLoader parses declarative confinement damage fields")
  void testParseDeclarativeDamage() {
    String yamlText = """
        alias: "damage_arena"
        confinement:
          boundary: "LEASH"
          leashRadius: 48.0
          duration: "5m"
          initialSize: 128
          shrinkTo: 32
          shrinkOver: "4m"
          damage: 2.5
          damageBuffer: 1.0
          damageInterval: 2s
        """;

    RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
    ActionDefinition def = ActionConfigLoader.parseDefinition("damage_arena", yaml);

    assertEquals(2.5, def.confinement().damageAmount());
    assertEquals(1.0, def.confinement().damageBuffer());
    assertEquals(2L, def.confinement().damageIntervalSeconds());
  }

  @Test
  @DisplayName("ActionConfigLoader parses declarative maxDistanceOutside")
  void testParseMaxDistanceOutside() {
    String yamlText = """
        alias: "limit_arena"
        confinement:
          boundary: "LEASH"
          leashRadius: 48.0
          maxDistanceOutside: 8.5
        """;

    RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
    ActionDefinition def = ActionConfigLoader.parseDefinition("limit_arena", yaml);

    assertEquals(8.5, def.confinement().maxDistanceOutside());
  }

  @Test
  @DisplayName("ActionConfigLoader parses declarative outsideActions")
  void testParseOutsideActions() {
    String yamlText = """
        alias: "actions_arena"
        confinement:
          boundary: "SUBSPACE"
          maxDistanceOutside: 12.0
          outsideActions:
            - CONSOLE: "kill [player]"
            - ACTION: PULL_BACK
        """;

    RtpYamlConfig yaml = RtpYamlConfig.parse(yamlText);
    ActionDefinition def = ActionConfigLoader.parseDefinition("actions_arena", yaml);

    assertEquals(12.0, def.confinement().maxDistanceOutside());
    assertEquals(2, def.confinement().outsideActions().size());
    assertEquals(ActionDefinition.ActionType.CONSOLE, def.confinement().outsideActions().get(0).type());
    assertEquals("kill [player]", def.confinement().outsideActions().get(0).payload());
    assertEquals(ActionDefinition.ActionType.ACTION, def.confinement().outsideActions().get(1).type());
    assertEquals("PULL_BACK", def.confinement().outsideActions().get(1).payload());
  }
}
