package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GateEvaluatorTest {

  @Test
  @DisplayName("Empty or null gate configs evaluate to true (ungated)")
  void testUngated() {
    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    assertTrue(GateEvaluator.evaluate(null, ctx, null));
    assertTrue(GateEvaluator.evaluate(Map.of(), ctx, null));
  }

  @Test
  @DisplayName("Scoreboard gate evaluates violations count correctly")
  void testScoreboardGate() {
    ActionGateContext ctxLow = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 1, false, 25.0);
    ActionGateContext ctxHigh = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 3, false, 25.0);

    Map<String, Object> gateConfig = Map.of(
        "scoreboard", Map.of("objective", "rtp_violations", "matches", "< 2"));

    assertTrue(GateEvaluator.evaluate(gateConfig, ctxLow, null));
    assertFalse(GateEvaluator.evaluate(gateConfig, ctxHigh, null));
  }

  @Test
  @DisplayName("Scoreboard gate evaluates all managed objectives (ADR-093 §4)")
  void testScoreboardGateManagedObjectives() {
    UUID sId = UUID.randomUUID();
    int hash = Math.abs(sId.hashCode());
    ActionGateContext ctx = new ActionGateContext(
        sId, "test", UUID.randomUUID(), 10L, 50L, 2, true, 144.0);

    // rtp_in_bounds
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_in_bounds", "matches", "1")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_in_bounds", "matches", "0")), ctx, null));

    // rtp_time_left
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_time_left", "matches", ">= 30")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_time_left", "matches", "< 30")), ctx, null));

    // rtp_dist_sq
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_dist_sq", "matches", "<= 200")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_dist_sq", "matches", "> 200")), ctx, null));

    // rtp_session_id
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_session_id", "matches", String.valueOf(hash))), ctx, null));

    // rtp_alive
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_alive", "matches", ">= 1")), ctx, null));
  }

  @Test
  @DisplayName("Spatial gate validates withinBoundary and distance")
  void testSpatialGate() {
    ActionGateContext inBounds = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 16.0);
    ActionGateContext outOfBoundsFar = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 1, false, 10000.0);

    Map<String, Object> gateConfig = Map.of(
        "spatial", Map.of("withinBoundary", true, "distance", "<= 32"));

    assertTrue(GateEvaluator.evaluate(gateConfig, inBounds, null));
    assertFalse(GateEvaluator.evaluate(gateConfig, outOfBoundsFar, null));
  }

  @Test
  @DisplayName("Time gate checks elapsed and remaining thresholds")
  void testTimeGate() {
    ActionGateContext early = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 15L, 285L, 0, true, 0.0);
    ActionGateContext late = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 45L, 5L, 0, true, 0.0);

    Map<String, Object> elapsedGate = Map.of(
        "time", Map.of("elapsed", ">= 30s"));
    assertFalse(GateEvaluator.evaluate(elapsedGate, early, null));
    assertTrue(GateEvaluator.evaluate(elapsedGate, late, null));

    Map<String, Object> remainingGate = Map.of(
        "time", Map.of("remaining", "<= 10s"));
    assertFalse(GateEvaluator.evaluate(remainingGate, early, null));
    assertTrue(GateEvaluator.evaluate(remainingGate, late, null));
  }

  @Test
  @DisplayName("External predicate gate invokes registered callback")
  void testExternalPredicateGate() {
    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    Map<String, Object> gateConfig = Map.of("predicate", "my_custom_check");
    Map<String, java.util.function.Predicate<ActionGateContext>> registry = new HashMap<>();

    // Not registered yet -> fails closed
    assertFalse(GateEvaluator.evaluate(gateConfig, ctx, registry));

    // Register returning true
    registry.put("my_custom_check", c -> true);
    assertTrue(GateEvaluator.evaluate(gateConfig, ctx, registry));

    // Register returning false
    registry.put("my_custom_check", c -> false);
    assertFalse(GateEvaluator.evaluate(gateConfig, ctx, registry));
  }

  @Test
  @DisplayName("Spatial gate validates target coordinate and distance")
  void testSpatialTargetCoordinateGate() {
    ActionGateContext near = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0, 102.0, 64.0, 201.0);
    ActionGateContext far = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0, 500.0, 64.0, 500.0);

    Map<String, Object> gateConfig = Map.of(
        "spatial", Map.of("target", "100,64,200", "distance", "<= 5"));

    assertTrue(GateEvaluator.evaluate(gateConfig, near, null));
    assertFalse(GateEvaluator.evaluate(gateConfig, far, null));
  }

  @Test
  @DisplayName("Command gate executes command and checks exit status")
  void testCommandGate() {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("target/test"));
    io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;

    // Register a tree command
    accessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "test_pass"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, java.util.List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    Map<String, Object> passGate = Map.of("command", "test_pass");
    Map<String, Object> failGate = Map.of("command", "unregistered_command");

    assertTrue(GateEvaluator.evaluate(passGate, ctx, null));
    assertFalse(GateEvaluator.evaluate(failGate, ctx, null));
  }

  @Test
  @DisplayName("Command gate with map execute/run syntax")
  void testCommandGateMapSyntax() {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("target/test"));
    io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;

    accessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "test_pass_map"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, java.util.List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    Map<String, Object> runMapGate = Map.of("command", Map.of("run", "test_pass_map"));
    Map<String, Object> execMapGate = Map.of("command", Map.of("execute", "test_pass_map"));
    Map<String, Object> emptyMapGate = Map.of("command", Map.of());

    assertTrue(GateEvaluator.evaluate(runMapGate, ctx, null));
    assertTrue(GateEvaluator.evaluate(execMapGate, ctx, null));
    assertFalse(GateEvaluator.evaluate(emptyMapGate, ctx, null));
  }

  @Test
  @DisplayName("Spatial gate with region reference")
  void testSpatialGateRegionReference() {
    io.github.dailystruggle.rtp.common.selection.region.Region mockRegion =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
    @SuppressWarnings("unchecked")
    io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> mockShape =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape.class);

    org.mockito.Mockito.when(mockShape.contains(0, 0)).thenReturn(true);
    org.mockito.Mockito.when(mockShape.contains(31, 31)).thenReturn(false);
    org.mockito.Mockito.when(mockShape.contains(org.mockito.ArgumentMatchers.intThat(i -> i >= 30), org.mockito.ArgumentMatchers.intThat(i -> i >= 30))).thenReturn(false);
    org.mockito.Mockito.doReturn(mockShape).when(mockRegion).getShape();

    io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put("HUB_REGION", mockRegion);
    io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.put("hub_region", mockRegion);

    try {
      ActionGateContext inside = new ActionGateContext(
          UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0, 0.0, 64.0, 0.0);
      ActionGateContext outside = new ActionGateContext(
          UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0, 500.0, 64.0, 500.0);

      Map<String, Object> regGate = Map.of("spatial", Map.of("region", "hub_region"));
      assertTrue(GateEvaluator.evaluate(regGate, inside, null));
      assertFalse(GateEvaluator.evaluate(regGate, outside, null));
    } finally {
      io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.remove("HUB_REGION");
      io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.remove("hub_region");
    }
  }

  @Test
  @DisplayName("Spatial shape and playerCount gate validates online players count")
  void testSpatialShapeAndPlayerCountGate() {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("target/test"));
    io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");

    // Add 2 mock players inside chunk (0, 0)
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            UUID.randomUUID(), "player1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 5, 64, 5));
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p2 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            UUID.randomUUID(), "player2", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 10, 64, 10));
    accessor.addPlayer(p1);
    accessor.addPlayer(p2);

    // Setup Shape Factory
    io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> shapeFactory =
        new io.github.dailystruggle.rtp.common.factory.Factory<>();
    shapeFactory.add("SQUARE", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square());
    io.github.dailystruggle.rtp.common.RTP.factoryMap.put(io.github.dailystruggle.rtp.common.RTP.factoryNames.shape, shapeFactory);

    ActionGateContext ctxInside = new ActionGateContext(
        UUID.randomUUID(), "test", p1.uuid(), 10L, 290L, 0, true, 0.0, 5.0, 64.0, 5.0);

    Map<String, Object> gate2Players = Map.of(
        "spatial", Map.of(
            "world", "world",
            "shape", Map.of("name", "SQUARE", "radius", "10", "centerRadius", "0", "centerX", 0, "centerZ", 0, "mode", "NONE"),
            "playerCount", ">= 2"
        ));

    Map<String, Object> gate3Players = Map.of(
        "spatial", Map.of(
            "world", "world",
            "shape", Map.of("name", "SQUARE", "radius", "10", "centerRadius", "0", "centerX", 0, "centerZ", 0, "mode", "NONE"),
            "playerCount", ">= 3"
        ));

    assertTrue(GateEvaluator.evaluate(gate2Players, ctxInside, null));
    assertFalse(GateEvaluator.evaluate(gate3Players, ctxInside, null));
  }

  @Test
  @DisplayName("Unrecognized gate type fails closed (ADR-093)")
  void testUnrecognizedGateFailsClosed() {
    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    Map<String, Object> unknownGate = Map.of("unknown_future_gate", Map.of("key", "val"));
    assertFalse(GateEvaluator.evaluate(unknownGate, ctx, null));
  }

  @Test
  @DisplayName("Ephemeral scoreboard objectives evaluate correctly (ADR-093 §4)")
  void testEphemeralScoreboardObjectives() {
    UUID sId = UUID.randomUUID();
    UUID pId = UUID.randomUUID();

    ActionGateContext ctx = new ActionGateContext(
        sId, "test", pId, 15L, 45L, 2, true, 64.0, 10.0, 70.0, 20.0, 10.0, 64.0, 20.0);

    // rtp_violations: 2
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_violations", "matches", "<= 2")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_violations", "matches", "< 2")), ctx, null));

    // rtp_in_bounds: 1 (true)
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_in_bounds", "matches", "== 1")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_in_bounds", "matches", "== 0")), ctx, null));

    // rtp_time_left: 45
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_time_left", "matches", "30..60s")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_time_left", "matches", "< 30s")), ctx, null));

    // rtp_dist_sq: 64.0
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_dist_sq", "matches", "<= 100")), ctx, null));
    assertFalse(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_dist_sq", "matches", "> 100")), ctx, null));

    // rtp_session_id: hashCode
    int hash = Math.abs(sId.hashCode());
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_session_id", "matches", "== " + hash)), ctx, null));

    // rtp_alive
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_alive", "matches", ">= 1")), ctx, null));
  }

  @Test
  @DisplayName("Spatial elevationDelta gate evaluates vertical displacement")
  void testSpatialElevationDeltaGate() {
    UUID sId = UUID.randomUUID();
    UUID pId = UUID.randomUUID();

    // Player at Y=70, anchor Y=64 => delta = 6
    ActionGateContext ctxDelta6 = new ActionGateContext(
        sId, "test", pId, 10L, 50L, 0, true, 0.0, 0.0, 70.0, 0.0, 0.0, 64.0, 0.0);

    Map<String, Object> gateWithin8 = Map.of("spatial", Map.of("elevationDelta", "<= 8"));
    Map<String, Object> gateWithin4 = Map.of("spatial", Map.of("elevationDelta", "<= 4"));

    assertTrue(GateEvaluator.evaluate(gateWithin8, ctxDelta6, null));
    assertFalse(GateEvaluator.evaluate(gateWithin4, ctxDelta6, null));
  }

  @Test
  @DisplayName("GateExpressionParser matches operators, ranges, units and multipliers")
  void testGateExpressionParserAllBranches() {
    // Range syntax
    assertTrue(GateExpressionParser.matches("1..5", 3.0));
    assertTrue(GateExpressionParser.matches("10..30s", 20.0));
    assertTrue(GateExpressionParser.matches("1..2m", 90.0));
    assertFalse(GateExpressionParser.matches("1..5", 6.0));

    // Comparison operators
    assertTrue(GateExpressionParser.matches("< 10", 5.0));
    assertFalse(GateExpressionParser.matches("< 10", 15.0));
    assertTrue(GateExpressionParser.matches("<= 10", 10.0));
    assertTrue(GateExpressionParser.matches("> 5", 6.0));
    assertFalse(GateExpressionParser.matches("> 5", 4.0));
    assertTrue(GateExpressionParser.matches(">= 5", 5.0));
    assertTrue(GateExpressionParser.matches("== 42", 42.0));
    assertTrue(GateExpressionParser.matches("= 42", 42.0));
    assertFalse(GateExpressionParser.matches("== 42", 41.0));

    // Units
    assertTrue(GateExpressionParser.matches(">= 1h", 3600.0));
    assertTrue(GateExpressionParser.matches("<= 1d", 86400.0));
    assertTrue(GateExpressionParser.matches("== 60sec", 60.0));
    assertTrue(GateExpressionParser.matches("== 1min", 60.0));
    assertTrue(GateExpressionParser.matches("== 2hours", 7200.0));
    assertTrue(GateExpressionParser.matches("== 2days", 172800.0));

    // Exact numeric match fallback
    assertTrue(GateExpressionParser.matches("123.45", 123.45));
    assertFalse(GateExpressionParser.matches("123.45", 100.0));
    assertFalse(GateExpressionParser.matches("invalid_token", 5.0));
    assertFalse(GateExpressionParser.matches("", 5.0));
    assertFalse(GateExpressionParser.matches(null, 5.0));

    // parseDurationSeconds
    assertEquals(30L, GateExpressionParser.parseDurationSeconds("30s", 10L));
    assertEquals(120L, GateExpressionParser.parseDurationSeconds("2m", 10L));
    assertEquals(7200L, GateExpressionParser.parseDurationSeconds("2h", 10L));
    assertEquals(86400L, GateExpressionParser.parseDurationSeconds("1d", 10L));
    assertEquals(10L, GateExpressionParser.parseDurationSeconds("invalid", 10L));
    assertEquals(10L, GateExpressionParser.parseDurationSeconds(null, 10L));
  }

  @Test
  @DisplayName("Phase 6.4: Compound multi-gate evaluation matrix (all 4 gate types combined)")
  void testCompoundMultiGateMatrix() {
    UUID sId = UUID.randomUUID();
    UUID pId = UUID.randomUUID();

    // Context: session at 15s elapsed, 105s remaining (total 120s), 1 violation, in bounds, distanceSq 144 (dist = 12 blocks)
    ActionGateContext ctx = new ActionGateContext(
        sId, "matrix_action", pId, 15L, 105L, 1, true, 144.0, 10.0, 64.0, 10.0, 0.0, 64.0, 0.0);

    Map<String, java.util.function.Predicate<ActionGateContext>> registry = new HashMap<>();
    registry.put("custom_api_predicate", c -> c.participantId().equals(pId));

    // Case 1: All 4 gates match -> PASS
    Map<String, Object> allMatchGate = Map.of(
        "scoreboard", Map.of("objective", "rtp_violations", "matches", "<= 2"),
        "spatial", Map.of("withinBoundary", true, "distance", "<= 20"),
        "time", Map.of("elapsed", ">= 10s", "remaining", "> 60s"),
        "predicate", "custom_api_predicate"
    );
    assertTrue(GateEvaluator.evaluate(allMatchGate, ctx, registry));

    // Case 2: One gate fails (spatial distance > 20 fails because actual is 12) -> FAIL
    Map<String, Object> spatialFailsGate = Map.of(
        "scoreboard", Map.of("objective", "rtp_violations", "matches", "<= 2"),
        "spatial", Map.of("withinBoundary", true, "distance", "< 10"), // Fails! 12 is not < 10
        "time", Map.of("elapsed", ">= 10s"),
        "predicate", "custom_api_predicate"
    );
    assertFalse(GateEvaluator.evaluate(spatialFailsGate, ctx, registry));

    // Case 3: Scoreboard fails (violations == 0 fails because actual is 1) -> FAIL
    Map<String, Object> scoreboardFailsGate = Map.of(
        "scoreboard", Map.of("objective", "rtp_violations", "matches", "== 0"), // Fails!
        "predicate", "custom_api_predicate"
    );
    assertFalse(GateEvaluator.evaluate(scoreboardFailsGate, ctx, registry));

    // Case 4: Predicate fails -> FAIL
    registry.put("failing_predicate", c -> false);
    Map<String, Object> predicateFailsGate = Map.of(
        "time", Map.of("elapsed", ">= 10s"),
        "predicate", "failing_predicate"
    );
    assertFalse(GateEvaluator.evaluate(predicateFailsGate, ctx, registry));
  }

  @Test
  @DisplayName("Multi-gate list (AND) and multi-command gate evaluation")
  void testMultiGateAndMultiCommand() {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("target/test"));
    io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;

    accessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "cmd_one"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, java.util.List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    accessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "cmd_two"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, java.util.List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionGateContext ctx = new ActionGateContext(
        UUID.randomUUID(), "test", UUID.randomUUID(), 10L, 290L, 0, true, 0.0);

    List<Map<String, Object>> gateList = List.of(
        Map.of("players", ">= 1"),
        Map.of("command", List.of("cmd_one", "cmd_two"))
    );

    assertTrue(GateEvaluator.evaluateAll(gateList, ctx, null, Collections.emptyMap()));

    List<Map<String, Object>> failingGateList = List.of(
        Map.of("players", ">= 2"), // Fails! (count is 1)
        Map.of("command", List.of("cmd_one", "cmd_two"))
    );

    assertFalse(GateEvaluator.evaluateAll(failingGateList, ctx, null, Collections.emptyMap()));
  }

  @Test
  @DisplayName("Cover remaining GateEvaluator edge branches and selector tags")
  void testGateEvaluatorEdgeBranches() {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("target/test"));
    io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;

    UUID sId = UUID.randomUUID();
    UUID pId = UUID.randomUUID();
    ActionGateContext ctx = new ActionGateContext(
        sId, "test", pId, 10L, 290L, 0, true, 0.0, 10.0, 64.0, 20.0, 0.0, 64.0, 0.0);

    // Context gateValidators fail check
    io.github.dailystruggle.rtp.api.action.ActionContext actionCtx =
        io.github.dailystruggle.rtp.api.action.ActionContext.ofValidators(List.of(c -> false));
    ActionGateContext failingValidatorCtx = new ActionGateContext(
        sId, "test", pId, 10L, 290L, 0, true, 0.0, 10.0, 64.0, 20.0, 0.0, 64.0, 0.0, 1, actionCtx);
    assertFalse(GateEvaluator.evaluate(Map.of(), failingValidatorCtx, null));

    // Spatial coordinate parsing invalid part
    Map<String, Object> badSpatialTarget = Map.of(
        "spatial", Map.of("target", "not_a_number,abc", "distance", "< 10"));
    assertFalse(GateEvaluator.evaluate(badSpatialTarget, ctx, null));

    // Spatial target with single coordinate part (length < 2)
    Map<String, Object> shortSpatialTarget = Map.of(
        "spatial", Map.of("target", "100", "distance", "< 10"));
    assertFalse(GateEvaluator.evaluate(shortSpatialTarget, ctx, null));

    // Spatial elevationDelta when delta is null but currentY/anchorY are present
    ActionGateContext elevationCtx = new ActionGateContext(
        sId, "test", pId, 10L, 290L, 0, true, 0.0, 10.0, 70.0, 20.0, 0.0, 60.0, 0.0);
    assertTrue(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("elevationDelta", ">= 10")), elevationCtx, null));

    // Participants count gate via map
    assertTrue(GateEvaluator.evaluate(
        Map.of("participants", Map.of("matches", ">= 1")), ctx, null));
    assertTrue(GateEvaluator.evaluate(
        Map.of("queue", Map.of("range", "1..5")), ctx, null));
    assertTrue(GateEvaluator.evaluate(
        Map.of("participantcount", Map.of("count", "1")), ctx, null));

    // Command predicate execute if entity @a[tag=active_tag]
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        pId, "TestPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("default"), 0, 64, 0)));
    accessor.addScoreboardTag(pId, "active_tag");
    Map<String, Object> tagGate = Map.of(
        "command", "execute if entity @a[tag=active_tag]");
    assertTrue(GateEvaluator.evaluate(tagGate, ctx, null));

    Map<String, Object> missingTagGate = Map.of(
        "command", "execute if entity @a[tag=non_existent_tag]");
    assertFalse(GateEvaluator.evaluate(missingTagGate, ctx, null));

    Map<String, Object> unlessTagGate = Map.of(
        "command", "execute unless entity @a[tag=non_existent_tag]");
    assertTrue(GateEvaluator.evaluate(unlessTagGate, ctx, null));

    // Spatial withinBoundary false
    ActionGateContext outOfBounds = new ActionGateContext(
        sId, "test", pId, 10L, 290L, 0, false, 0.0);
    assertTrue(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("withinBoundary", false)), outOfBounds, null));

    // Spatial shape and region checks
    Map<String, Object> shapeMap = Map.of(
        "spatial", Map.of("shape", Map.of("name", "CIRCLE", "radius", 100))
    );
    assertTrue(GateEvaluator.evaluate(shapeMap, ctx, null));

    // Spatial playerCount with no shape configured -> false
    assertFalse(GateEvaluator.evaluate(
        Map.of("spatial", Map.of("playerCount", ">= 1")), ctx, null));

    // Scoreboard unknown managed objective -> defaults to 0.0
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "custom_objective", "matches", "0")), ctx, null));

    // Managed scoreboard objective rtp_alive
    assertTrue(GateEvaluator.evaluate(
        Map.of("scoreboard", Map.of("objective", "rtp_alive", "matches", ">= 1")), ctx, null));

    // Unrecognized gate fails closed
    assertFalse(GateEvaluator.evaluate(Map.of("unknown_gate_type", 123), ctx, null));
  }
}
