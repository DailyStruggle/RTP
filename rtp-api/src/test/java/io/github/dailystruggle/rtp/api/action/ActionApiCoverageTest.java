package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.RTPAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ActionApiCoverageTest {

  @Test
  @DisplayName("ConfinementBoundary enum contains all values")
  void testConfinementBoundaryEnum() {
    for (ConfinementBoundary b : ConfinementBoundary.values()) {
      assertSame(b, ConfinementBoundary.valueOf(b.name()));
    }
  }

  @Test
  @DisplayName("ActionGateContext record accessors and null check")
  void testActionGateContext() {
    UUID sId = UUID.randomUUID();
    UUID pId = UUID.randomUUID();
    ActionGateContext ctx = new ActionGateContext(sId, "duel", pId, 10L, 50L, 1, false, 100.0);

    assertEquals(sId, ctx.sessionId());
    assertEquals("duel", ctx.actionId());
    assertEquals(pId, ctx.participantId());
    assertEquals(10L, ctx.elapsedSeconds());
    assertEquals(50L, ctx.remainingSeconds());
    assertEquals(1, ctx.violations());
    assertFalse(ctx.inBounds());
    assertEquals(100.0, ctx.distanceSqFromAnchor());

    assertThrows(NullPointerException.class, () ->
        new ActionGateContext(null, "duel", pId, 0, 0, 0, true, 0));
    assertThrows(NullPointerException.class, () ->
        new ActionGateContext(sId, null, pId, 0, 0, 0, true, 0));
  }

  @Test
  @DisplayName("ActionContext record factories and accessors")
  void testActionContext() {
    ActionContext empty1 = ActionContext.empty();
    assertNotNull(empty1);
    assertTrue(empty1.metadata().isEmpty());

    ActionContext single = ActionContext.of("tier", "gold");
    assertEquals("gold", single.metadata().get("tier"));

    ActionContext fromMap = ActionContext.of(Map.of("k", "v"));
    assertEquals("v", fromMap.metadata().get("k"));

    ActionContext nullMeta = new ActionContext(null);
    assertNotNull(nullMeta.metadata());
    assertTrue(nullMeta.metadata().isEmpty());

    assertThrows(NullPointerException.class, () -> ActionContext.of(null, "val"));
  }

  @Test
  @DisplayName("ActionSessionResult record factories")
  void testActionSessionResult() {
    UUID sId = UUID.randomUUID();
    ActionSessionResult success = ActionSessionResult.success(sId);
    assertTrue(success.success());
    assertEquals(sId, success.sessionId());
    assertNull(success.failureReason());

    ActionSessionResult failure = ActionSessionResult.failure("failed");
    assertFalse(failure.success());
    assertNull(failure.sessionId());
    assertEquals("failed", failure.failureReason());

    assertThrows(NullPointerException.class, () -> ActionSessionResult.success(null));
    assertThrows(NullPointerException.class, () -> ActionSessionResult.failure(null));
  }

  @Test
  @DisplayName("ActionDefinition and sub-records accessors")
  void testActionDefinition() {
    ActionDefinition.PlacementSpec placement = new ActionDefinition.PlacementSpec(
        "world", "duel", 4, 16, 8, Map.of("param", "val"));
    assertEquals("world", placement.region());
    assertEquals("duel", placement.profile());
    assertEquals(4, placement.subspaceChunkRadius());
    assertEquals(16, placement.minSeparation());
    assertEquals(8, placement.elevationTolerance());
    assertEquals("val", placement.parameters().get("param"));

    ActionDefinition.PlacementSpec nullParams = new ActionDefinition.PlacementSpec(
        "w", "p", 1, 1, 1, null);
    assertTrue(nullParams.parameters().isEmpty());

    ActionDefinition.ConfinementSpec conf = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.REGION, 120L, 0.0);
    assertEquals(ConfinementBoundary.REGION, conf.boundary());
    assertEquals(120L, conf.durationSeconds());
    assertEquals(64.0, conf.leashRadius(), "leashRadius <= 0 defaults to 64.0");

    ActionDefinition.CommandAction cmdConsole = ActionDefinition.CommandAction.console("say hi");
    assertEquals(ActionDefinition.ActionType.CONSOLE, cmdConsole.type());
    assertEquals("say hi", cmdConsole.payload());

    ActionDefinition.CommandAction cmdPlayer = ActionDefinition.CommandAction.player("help");
    assertEquals(ActionDefinition.ActionType.PLAYER, cmdPlayer.type());
    assertEquals("help", cmdPlayer.payload());

    ActionDefinition.CommandAction cmdAction = ActionDefinition.CommandAction.action("PULL_BACK");
    assertEquals(ActionDefinition.ActionType.ACTION, cmdAction.type());
    assertEquals("PULL_BACK", cmdAction.payload());

    ActionDefinition.CommandAction cmdForEach = ActionDefinition.CommandAction.forEach(List.of(cmdConsole));
    assertEquals(ActionDefinition.ActionType.FOR_EACH, cmdForEach.type());
    assertEquals(1, cmdForEach.subActions().size());

    for (ActionDefinition.ActionType t : ActionDefinition.ActionType.values()) {
      assertSame(t, ActionDefinition.ActionType.valueOf(t.name()));
    }

    ActionDefinition.LifecycleStep step = new ActionDefinition.LifecycleStep(
        Map.of("gate", "val"), List.of(cmdConsole));
    assertEquals("val", step.gateConfig().get("gate"));
    assertEquals(1, step.actions().size());

    ActionDefinition.LifecycleStep nullStep = new ActionDefinition.LifecycleStep(null, null);
    assertTrue(nullStep.gateConfig().isEmpty());
    assertTrue(nullStep.actions().isEmpty());

    ActionDefinition.LifecycleSpec life = new ActionDefinition.LifecycleSpec(
        List.of(step), List.of(step), List.of(step), List.of(step));
    assertEquals(1, life.onStart().size());
    assertEquals(1, life.onBoundaryViolation().size());
    assertEquals(1, life.onExpire().size());
    assertEquals(1, life.onDeath().size());

    ActionDefinition.LifecycleSpec nullLife = new ActionDefinition.LifecycleSpec(null, null, null, null);
    assertTrue(nullLife.onStart().isEmpty());

    ActionDefinition def = new ActionDefinition(
        "test", "test_alias", "rtp.action.test", "desc", placement, conf, life);
    assertEquals("test", def.id());
    assertEquals("test_alias", def.alias());
    assertEquals("rtp.action.test", def.permission());
    assertEquals("desc", def.description());
    assertSame(placement, def.placement());
    assertSame(conf, def.confinement());
    assertSame(life, def.lifecycle());

    ActionDefinition defNulls = new ActionDefinition(
        "test2", null, null, null, null, null, null);
    assertNotNull(defNulls.placement());
    assertNotNull(defNulls.confinement());
    assertNotNull(defNulls.lifecycle());

    assertThrows(NullPointerException.class, () ->
        new ActionDefinition(null, null, null, null, null, null, null));
  }

  @Test
  @DisplayName("RTPAPI.actions() throws IllegalStateException before core load")
  void testRtpApiActionsUnloaded() {
    ActionService prev = RTPAPI.actionService;
    try {
      RTPAPI.actionService = null;
      assertThrows(IllegalStateException.class, RTPAPI::actions);

      ActionService dummy = new ActionService() {
        @Override
        public CompletableFuture<ActionSessionResult> trigger(String actionId, List<UUID> participants, ActionContext context) {
          return null;
        }
        @Override
        public Optional<ActionSession> getSession(UUID sessionId) { return Optional.empty(); }
        @Override
        public Optional<ActionSession> getSessionForParticipant(UUID participantId) { return Optional.empty(); }
        @Override
        public void disarm(UUID sessionId) {}
        @Override
        public void registerPredicate(String name, Predicate<ActionGateContext> predicate) {}
        @Override
        public Set<String> getActionIds() { return Collections.emptySet(); }
      };

      RTPAPI.actionService = dummy;
      assertSame(dummy, RTPAPI.actions());
    } finally {
      RTPAPI.actionService = prev;
    }
  }
}
