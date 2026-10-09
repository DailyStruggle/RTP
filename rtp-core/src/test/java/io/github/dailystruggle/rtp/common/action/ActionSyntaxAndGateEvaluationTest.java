package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-001 / ADR-093:
 * Regression tests for action syntax, boundary parsing, composite durations,
 * inline/map step gates, and live scoreboard objective gate evaluation.
 */
class ActionSyntaxAndGateEvaluationTest {

  @TempDir
  File tempDir;

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;

  @BeforeEach
  void setUp() {
    serverAccessor = RTPTestSetup.install(tempDir);
    actionManager = new ActionManager();
  }

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  @Test
  @DisplayName("REQ-RTP-S-001 / ADR-093: boundary NONE disables confinement checks and world border")
  void testBoundaryNoneDisablesConfinement() throws IOException {
    File actionsDir = new File(tempDir, "definitions/actions");
    actionsDir.mkdirs();
    File actFile = new File(actionsDir, "none_boundary.yml");
    try (FileWriter writer = new FileWriter(actFile)) {
      writer.write("""
          id: test_none
          title: "None Boundary Action"
          placement:
            enabled: true
            shape: SQUARE
            radius: 32
          confinement:
            boundary: NONE
            duration: 120s
          """);
    }

    ActionConfigLoader.loadActions(tempDir, actionManager);
    ActionDefinition def = actionManager.getAction("none_boundary").orElse(null);
    assertNotNull(def, "Action definition none_boundary must load");
    assertEquals(ConfinementBoundary.NONE, def.confinement().boundary(), "Boundary must be NONE");

    UUID p1 = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer player = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(),
        def,
        List.of(p1),
        ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world",
        0,
        0,
        null,
        null,
        Collections.emptyMap()
    );

    session.arm();

    // Verify player is in bounds even far away
    assertTrue(session.checkInBounds("world", 50000, 50000));
  }

  @Test
  @DisplayName("REQ-RTP-S-001 / ADR-093: unknown boundary falls back to SUBSPACE")
  void testUnknownBoundaryFallsBackToSubspace() throws IOException {
    File actionsDir = new File(tempDir, "definitions/actions");
    actionsDir.mkdirs();
    File actFile = new File(actionsDir, "typo_boundary.yml");
    try (FileWriter writer = new FileWriter(actFile)) {
      writer.write("""
          id: test_typo
          title: "Typo Boundary Action"
          confinement:
            boundary: NON_EXISTENT_SHAPE
          """);
    }

    ActionConfigLoader.loadActions(tempDir, actionManager);
    ActionDefinition def = actionManager.getAction("typo_boundary").orElse(null);
    assertNotNull(def);
    assertEquals(ConfinementBoundary.SUBSPACE, def.confinement().boundary());
  }

  @Test
  @DisplayName("REQ-RTP-S-001 / ADR-093: composite and extended duration units parse accurately")
  void testDurationParsing() {
    // Single and composite units
    assertEquals(90L, GateExpressionParser.parseDurationSeconds("1m30s", 0L));
    assertEquals(9000L, GateExpressionParser.parseDurationSeconds("2h30m", 0L));
    assertEquals(604800L, GateExpressionParser.parseDurationSeconds("1w", 0L));
    assertEquals(1L, GateExpressionParser.parseDurationSeconds("500ms", 0L));
    assertEquals(3600L, GateExpressionParser.parseDurationSeconds("1h", 0L));
    assertEquals(86400L, GateExpressionParser.parseDurationSeconds("1d", 0L));

    // Comparison expressions
    assertTrue(GateExpressionParser.matches(">= 1m30s", 90.0));
    assertTrue(GateExpressionParser.matches(">= 1m30s", 120.0));
    assertFalse(GateExpressionParser.matches(">= 1m30s", 89.0));

    // Range expressions
    assertTrue(GateExpressionParser.matches("10s..1m30s", 45.0));
    assertTrue(GateExpressionParser.matches("10s..1m30s", 10.0));
    assertTrue(GateExpressionParser.matches("10s..1m30s", 90.0));
    assertFalse(GateExpressionParser.matches("10s..1m30s", 91.0));
    assertFalse(GateExpressionParser.matches("10s..1m30s", 9.0));
  }

  @Test
  @DisplayName("REQ-RTP-S-001 / ADR-093: inline step gates and map-valued run blocks parse properly")
  void testStepGatesAndMapRunParsing() throws IOException {
    File actionsDir = new File(tempDir, "definitions/actions");
    actionsDir.mkdirs();
    File actFile = new File(actionsDir, "step_gates.yml");
    try (FileWriter writer = new FileWriter(actFile)) {
      writer.write("""
          id: test_steps
          title: "Step Gates Action"
          lifecycle:
            onStart:
              - gate:
                  scoreboard:
                    objective: kills
                    matches: ">= 5"
                CONSOLE: "broadcast High kills player started"
              - gate:
                  scoreboard:
                    objective: score
                    matches: ">= 100"
                run:
                  CONSOLE: "broadcast High score player"
                  PLAYER: "title @s title &aHigh Score"
          """);
    }

    ActionConfigLoader.loadActions(tempDir, actionManager);
    ActionDefinition def = actionManager.getAction("step_gates").orElse(null);
    assertNotNull(def);

    List<ActionDefinition.LifecycleStep> onStart = def.lifecycle().onStart();
    assertEquals(2, onStart.size());

    // Inline gate payload verification
    ActionDefinition.LifecycleStep step1 = onStart.get(0);
    assertFalse(step1.gateConfig().isEmpty(), "Inline gate configuration must be parsed");
    assertTrue(step1.gateConfig().containsKey("scoreboard"));
    assertEquals(1, step1.actions().size());
    assertEquals("broadcast High kills player started", step1.actions().get(0).payload());

    // Map-valued run block payload verification
    ActionDefinition.LifecycleStep step2 = onStart.get(1);
    assertFalse(step2.gateConfig().isEmpty(), "Map run gate configuration must be parsed");
    assertTrue(step2.gateConfig().containsKey("scoreboard"));
    assertEquals(2, step2.actions().size());
    assertEquals("broadcast High score player", step2.actions().get(0).payload());
  }

  @Test
  @DisplayName("REQ-RTP-S-001 / ADR-093: scoreboard gate queries live server accessor and fails closed when missing")
  void testScoreboardGateLiveEvaluation() {
    UUID pid = UUID.randomUUID();
    serverAccessor.setScoreboardScore(pid, "kills", 12);

    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(),
        "test_action",
        pid,
        0L,
        30L,
        0,
        true,
        0.0
    );

    // Objective "kills" >= 10 -> true
    Map<String, Object> gateConfigPassing = Map.of(
        "scoreboard", Map.of("objective", "kills", "matches", ">= 10")
    );
    assertTrue(GateEvaluator.evaluate(gateConfigPassing, ctx, Collections.emptyMap()), "Gate must pass when score matches");

    // Objective "kills" >= 20 -> false
    Map<String, Object> gateConfigFailing = Map.of(
        "scoreboard", Map.of("objective", "kills", "matches", ">= 20")
    );
    assertFalse(GateEvaluator.evaluate(gateConfigFailing, ctx, Collections.emptyMap()), "Gate must fail when score is below threshold");

    // Missing objective "non_existent" -> fails closed (false)
    Map<String, Object> gateConfigMissing = Map.of(
        "scoreboard", Map.of("objective", "non_existent", "matches", ">= 0")
    );
    assertFalse(GateEvaluator.evaluate(gateConfigMissing, ctx, Collections.emptyMap()), "Gate must fail closed when objective is missing");
  }
}
