package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ActionManagerTest {

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;

  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  @BeforeEach
  void setUp() {
    serverAccessor = new MockRTPServerAccessor(new File("."));
    RTP.serverAccessor = serverAccessor;
    originalGroupService = RTP.groupPlacementService;
    actionManager = new ActionManager();
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    RTP.groupPlacementService = originalGroupService;
  }

  @Test
  @DisplayName("Trigger fails if action definition is not registered")
  void testTriggerMissingAction() {
    CompletableFuture<ActionSessionResult> future =
        actionManager.trigger("missing_action", List.of(UUID.randomUUID()), ActionContext.EMPTY);
    ActionSessionResult result = future.join();
    assertFalse(result.success());
    assertTrue(result.failureReason().contains("Action definition not found"));
  }

  @Test
  @DisplayName("Trigger succeeds with mocked GroupPlacementService")
  void testTriggerSuccessWithSubspacePlacement() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);

    actionManager.registerAction(def);

    // Mock GroupPlacementService
    RTP.groupPlacementService = request -> {
      RTPLocation loc1 = new RTPLocation(serverAccessor.getRTPWorld("world"), 100, 64, 100);
      RTPLocation loc2 = new RTPLocation(serverAccessor.getRTPWorld("world"), 120, 64, 100);
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(Map.of(p1, loc1, p2, loc2)));
    };

    CompletableFuture<ActionSessionResult> future =
        actionManager.trigger("duel", List.of(p1, p2), ActionContext.EMPTY);

    ActionSessionResult result = future.join();
    assertTrue(result.success());
    assertNotNull(result.sessionId());

    // Check active session lookup
    Optional<ActionSession> sessionOpt = actionManager.getSession(result.sessionId());
    assertTrue(sessionOpt.isPresent());
    assertEquals(2, sessionOpt.get().participants().size());

    // Check participant to session lookup
    Optional<ActionSession> p1Session = actionManager.getSessionForParticipant(p1);
    assertTrue(p1Session.isPresent());
    assertEquals(result.sessionId(), p1Session.get().sessionId());

    // Disarm session and verify cleanup
    actionManager.disarm(result.sessionId());
    assertFalse(actionManager.getSession(result.sessionId()).isPresent());
    assertFalse(actionManager.getSessionForParticipant(p1).isPresent());
  }

  @Test
  @DisplayName("Anchor source selection: regionQueue, entity, claimHazard, fixed (ADR-095)")
  void testAnchorSourceSelection() {
    java.util.concurrent.atomic.AtomicReference<io.github.dailystruggle.rtp.api.group.AnchorSource> captured =
        new java.util.concurrent.atomic.AtomicReference<>();
    RTP.groupPlacementService = request -> {
      captured.set(request.anchorSource());
      UUID pid = request.participants().get(0);
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(
              Map.of(pid, new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0))));
    };

    // 1. Default / regionQueue
    registerAnchorAction("plainrtp", null, Map.of());
    triggerAndDisarm("plainrtp");
    assertEquals(
        io.github.dailystruggle.rtp.api.group.AnchorSource.regionQueue(),
        captured.get(),
        "default anchor must be regionQueue");

    // 2. claimHazard
    registerAnchorAction("claim", "claimHazard", Map.of());
    triggerAndDisarm("claim");
    assertEquals(
        io.github.dailystruggle.rtp.api.group.AnchorSource.claimHazard(),
        captured.get(),
        "claim anchor must be claimHazard");

    // 3. fixed with landmark coords -> resolves to those coords
    registerAnchorAction(
        "landmark", "fixed", Map.of("anchorWorld", "world", "anchorX", 200, "anchorY", 70, "anchorZ", -50));
    triggerAndDisarm("landmark");
    io.github.dailystruggle.rtp.api.world.RTPCoords fixed = captured.get().resolveAnchor("world").join();
    assertNotNull(fixed);
    assertEquals(200, fixed.x());
    assertEquals(-50, fixed.z());

    // 4. entity with context override coords -> resolves to context coords
    registerAnchorAction("nearplayer", "entity", Map.of());
    ActionContext ctx =
        ActionContext.of(Map.of("anchorWorld", "world", "anchorX", 5, "anchorY", 64, "anchorZ", 9));
    ActionSessionResult res = actionManager.trigger("nearplayer", List.of(UUID.randomUUID()), ctx).join();
    assertTrue(res.success());
    io.github.dailystruggle.rtp.api.world.RTPCoords ent = captured.get().resolveAnchor("world").join();
    assertNotNull(ent);
    assertEquals(5, ent.x());
    assertEquals(9, ent.z());
    actionManager.disarm(res.sessionId());

    // 5. fixed without any coords -> falls back to regionQueue
    registerAnchorAction("badfixed", "fixed", Map.of());
    triggerAndDisarm("badfixed");
    assertEquals(
        io.github.dailystruggle.rtp.api.group.AnchorSource.regionQueue(),
        captured.get(),
        "fixed with no coords must fall back to regionQueue");
  }

  private void registerAnchorAction(String id, String anchor, Map<String, Object> extraParams) {
    java.util.Map<String, Object> params = new java.util.HashMap<>(extraParams);
    if (anchor != null) params.put("anchor", anchor);
    ActionDefinition.PlacementSpec placement =
        new ActionDefinition.PlacementSpec("default", "circle", 4, 16, 8, params);
    ActionDefinition def = new ActionDefinition(
        id, id, "rtp.action." + id, "",
        placement,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);
  }

  private void triggerAndDisarm(String id) {
    ActionSessionResult res =
        actionManager.trigger(id, List.of(UUID.randomUUID()), ActionContext.EMPTY).join();
    assertTrue(res.success(), "trigger of " + id + " should succeed");
    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("Rejects trigger if participant already in active session")
  void testParticipantConflict() {
    UUID p1 = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);

    actionManager.registerAction(def);

    RTP.groupPlacementService = request -> CompletableFuture.completedFuture(
        GroupPlacementResult.success(Map.of(p1, new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0))));

    ActionSessionResult res1 = actionManager.trigger("duel", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res1.success());

    ActionSessionResult res2 = actionManager.trigger("duel", List.of(p1), ActionContext.EMPTY).join();
    assertFalse(res2.success());
    assertTrue(res2.failureReason().contains("already in an active session"));

    actionManager.disarm(res1.sessionId());
  }
}
