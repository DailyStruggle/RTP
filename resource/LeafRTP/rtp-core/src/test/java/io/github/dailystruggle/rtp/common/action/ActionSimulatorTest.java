package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionSimulatorTest {
  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  @Test
  void reportsPassAndFailReasonsForSyntheticViolationCounts() {
    ActionDefinition definition = parse("""
        placement: false
        confinement:
          boundary: NONE
          duration: 30s
        lifecycle:
          onBoundaryViolation:
            - gate:
                scoreboard:
                  objective: rtp_violations
                  matches: "< 2"
              run:
                - CONSOLE: "say first"
            - gate:
                scoreboard:
                  objective: rtp_violations
                  matches: ">= 2"
              run:
                - CONSOLE: "say second"
        """);

    ActionSimulator.Result result = ActionSimulator.simulate(definition, 2, List.of("violate", "violate"));

    assertEquals(List.of("say first", "say second"), result.commands(), result.lines().toString());
    assertEquals(4, result.gateOutcomes().size());
    assertTrue(result.gateOutcomes().get(0).passed());
    assertFalse(result.gateOutcomes().get(1).passed());
    assertFalse(result.gateOutcomes().get(2).passed());
    assertTrue(result.gateOutcomes().get(3).passed());
    assertTrue(result.gateOutcomes().stream().allMatch(outcome -> !outcome.reason().isBlank()));
    assertFalse(result.notes().stream().anyMatch(note -> note.contains("onBoundaryViolation")
        && note.contains("gate not evaluated")));
    assertTrue(((MockRTPCommandSender) accessor.getSender(new UUID(0, 0))).performedCommands.isEmpty());
    assertTrue(result.lines().get(0).toUpperCase().contains("SIMULATION"));
  }

  @Test
  void delayedStepsUseTheSimulatedClock() {
    ActionDefinition definition = parse("""
        placement: false
        confinement:
          boundary: NONE
          duration: 30s
        lifecycle:
          onStart:
            - gate:
                time:
                  elapsed: "< 1"
              delay: 2s
              run:
                - CONSOLE: "say delayed"
        """);

    ActionSimulator.Result result = ActionSimulator.simulate(definition, 2, List.of());

    assertTrue(result.commands().isEmpty());
    assertEquals(2, result.gateOutcomes().size());
    assertTrue(result.gateOutcomes().get(0).passed());
    assertFalse(result.gateOutcomes().get(result.gateOutcomes().size() - 1).passed());
    assertTrue(result.gateOutcomes().get(result.gateOutcomes().size() - 1).reason().contains("elapsed"));
  }

  @Test
  void deathAndExpireScriptsUseRealLifecycleCommandsAndExplainUngatedContext() {
    ActionDefinition death = parse("""
        placement: false
        confinement:
          boundary: NONE
          duration: 30s
        lifecycle:
          onDeath:
            - CONSOLE: "say defeated [victim]"
        """);
    ActionDefinition expire = parse("""
        placement: false
        confinement:
          boundary: NONE
          duration: 30s
        lifecycle:
          onExpire:
            - gate:
                time:
                  elapsed: "< 1"
              run:
                - CONSOLE: "say expired"
        """);

    ActionSimulator.Result deathResult = ActionSimulator.simulate(death, 2, List.of("die"));
    ActionSimulator.Result expireResult = ActionSimulator.simulate(expire, 2, List.of("expire"));

    assertTrue(deathResult.commands().get(0).startsWith("say defeated "));
    assertTrue(expireResult.commands().contains("say expired"));
    assertTrue(expireResult.notes().stream().anyMatch(note -> note.contains("gate not evaluated")));
  }

  private static ActionDefinition parse(String yaml) {
    return ActionConfigLoader.parseDefinition("simulated", RtpYamlConfig.parse(yaml));
  }
}
