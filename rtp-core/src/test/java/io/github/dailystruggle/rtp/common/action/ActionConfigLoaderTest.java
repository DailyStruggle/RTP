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
}
