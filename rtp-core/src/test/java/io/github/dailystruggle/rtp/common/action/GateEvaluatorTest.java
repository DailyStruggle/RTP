package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
}
