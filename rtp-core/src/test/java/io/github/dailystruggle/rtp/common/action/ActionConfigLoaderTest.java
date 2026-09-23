package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class ActionConfigLoaderTest {

  @Test
  @DisplayName("ActionConfigLoader parses declarative action definition from YAML")
  void testParseDefinition() throws Exception {
    String yamlText = """
        alias: "duel"
        permission: "rtp.action.duel"
        description: "Challenge another player to a confined arena duel"

        placement:
          region: "pvp_world"
          profile: "duel"
          parameters:
            subspaceChunkRadius: 4
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

    assertEquals("pvp_world", def.placement().region());
    assertEquals("duel", def.placement().profile());
    assertEquals(4, def.placement().subspaceChunkRadius());
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
}
