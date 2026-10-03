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
import java.util.Collections;
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

    // 6. faction with claimBoundary in context -> resolves to ClaimBoundaryAnchorSource
    registerAnchorAction("faction_rtp", "faction", Map.of());
    io.github.dailystruggle.rtp.api.claim.ClaimBoundary b =
        new io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTrackerTest.RectangularClaimBoundary(
            "f1", "world", 10, 20, 30, 40);
    ActionContext fCtx = ActionContext.of(Map.of("claimBoundary", b));
    ActionSessionResult fRes = actionManager.trigger("faction_rtp", List.of(UUID.randomUUID()), fCtx).join();
    assertTrue(fRes.success());
    assertTrue(captured.get() instanceof io.github.dailystruggle.rtp.api.group.AnchorSource.ClaimBoundaryAnchorSource);
    assertEquals(b, ((io.github.dailystruggle.rtp.api.group.AnchorSource.ClaimBoundaryAnchorSource) captured.get()).boundary());
    actionManager.disarm(fRes.sessionId());

    // 7. faction with ClaimBoundaryRegistry provider -> resolves automatically
    UUID providerPlayerId = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.claim.ClaimBoundary providerBoundary =
        new io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTrackerTest.RectangularClaimBoundary(
            "f_registered", "world", 50, 60, 70, 80);
    io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider mockProvider =
        new io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider() {
          @Override
          public String namespace() {
            return "factions";
          }

          @Override
          public int priority() {
            return 10;
          }

          @Override
          public java.util.Optional<io.github.dailystruggle.rtp.api.claim.ClaimBoundary> getBoundary(
              UUID playerId, String worldName) {
            if (providerPlayerId.equals(playerId)) {
              return java.util.Optional.of(providerBoundary);
            }
            return java.util.Optional.empty();
          }
        };

    io.github.dailystruggle.rtp.api.RTPAPI.hooks.claimBoundaries().register(mockProvider);
    try {
      ActionSessionResult pRes =
          actionManager.trigger("faction_rtp", List.of(providerPlayerId), ActionContext.empty()).join();
      assertTrue(pRes.success());
      assertTrue(captured.get() instanceof io.github.dailystruggle.rtp.api.group.AnchorSource.ClaimBoundaryAnchorSource);
      assertEquals(
          providerBoundary,
          ((io.github.dailystruggle.rtp.api.group.AnchorSource.ClaimBoundaryAnchorSource) captured.get()).boundary());
      actionManager.disarm(pRes.sessionId());
    } finally {
      io.github.dailystruggle.rtp.api.RTPAPI.hooks.claimBoundaries().clear();
    }
  }

  private void registerAnchorAction(String id, String anchor, Map<String, Object> extraParams) {
    java.util.Map<String, Object> params = new java.util.HashMap<>(extraParams);
    if (anchor != null) params.put("anchor", anchor);
    ActionDefinition.PlacementSpec placement =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, params);
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

  @Test
  @DisplayName("Rejects concurrent trigger while placement is async in progress")
  void testConcurrentTriggerDuringAsyncPlacement() {
    UUID p1 = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "duel_async", "duel_async", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);

    actionManager.registerAction(def);

    CompletableFuture<GroupPlacementResult> placeFuture = new CompletableFuture<>();
    RTP.groupPlacementService = request -> placeFuture;

    // Trigger 1 begins async placement and does not complete yet
    CompletableFuture<ActionSessionResult> trigger1 =
        actionManager.trigger("duel_async", List.of(p1), ActionContext.EMPTY);
    assertFalse(trigger1.isDone());

    // Trigger 2 for the same participant must immediately be rejected due to atomic reservation
    CompletableFuture<ActionSessionResult> trigger2 =
        actionManager.trigger("duel_async", List.of(p1), ActionContext.EMPTY);
    assertTrue(trigger2.isDone(), "Second trigger must complete immediately with rejection");
    ActionSessionResult res2 = trigger2.join();
    assertFalse(res2.success());
    assertTrue(res2.failureReason().contains("already in an active session"));

    // Now complete the async placement for trigger 1
    placeFuture.complete(
        GroupPlacementResult.success(Map.of(p1, new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0))));

    ActionSessionResult res1 = trigger1.join();
    assertTrue(res1.success());

    // Verify session lookup works for p1
    Optional<ActionSession> sessionOpt = actionManager.getSessionForParticipant(p1);
    assertTrue(sessionOpt.isPresent());
    assertEquals(res1.sessionId(), sessionOpt.get().sessionId());

    actionManager.disarm(res1.sessionId());
    assertFalse(actionManager.getSessionForParticipant(p1).isPresent());
  }

  @Test
  @DisplayName("Rolls back atomic participant reservation on placement failure")
  void testRollbackReservationOnPlacementFailure() {
    UUID p1 = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "duel_fail", "duel_fail", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);

    actionManager.registerAction(def);

    RTP.groupPlacementService = request ->
        CompletableFuture.completedFuture(
            GroupPlacementResult.failure(GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, "Subspace failure"));

    ActionSessionResult res1 = actionManager.trigger("duel_fail", List.of(p1), ActionContext.EMPTY).join();
    assertFalse(res1.success());

    // Participant must be cleared from participantToSession so a subsequent trigger is allowed
    RTP.groupPlacementService = request ->
        CompletableFuture.completedFuture(
            GroupPlacementResult.success(Map.of(p1, new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0))));

    ActionSessionResult res2 = actionManager.trigger("duel_fail", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res2.success(), "Subsequent trigger must succeed after earlier failure rolled back reservation");

    actionManager.disarm(res2.sessionId());
  }

  @Test
  @DisplayName("Per-action prevalidated caching: offer, poll, revalidate, and dispatch (ADR-097)")
  void testPerActionCachingAndRevalidation() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    ActionDefinition def = new ActionDefinition(
        "cached_action", "cached_action", "rtp.action.cached", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    // Group service should not be called if cache hit succeeds
    java.util.concurrent.atomic.AtomicInteger liveCalls = new java.util.concurrent.atomic.AtomicInteger(0);
    RTP.groupPlacementService = req -> {
      liveCalls.incrementAndGet();
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(Map.of(p1, new RTPLocation(serverAccessor.getRTPWorld("world"), 100, 64, 100))));
    };

    // 1. Offer a pre-validated placement (Y=32 matches MockRTPChunk ground level for LinearAdjustor)
    RTPLocation cachedLoc = new RTPLocation(serverAccessor.getRTPWorld("world"), 200, 32, 200);
    ActionManager.PrevalidatedActionPlacement item =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), cachedLoc), 200, 200);
    boolean offered = actionManager.offerCachedPlacement("cached_action", item);
    assertTrue(offered);
    assertEquals(1, actionManager.getCachedPlacementCount("cached_action"));

    // 2. Trigger should consume the cached item without calling live group service
    ActionSessionResult res = actionManager.trigger("cached_action", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res.success());
    assertEquals(0, liveCalls.get(), "Must not call live placement when cache hit succeeds");
    assertEquals(0, actionManager.getCachedPlacementCount("cached_action"));

    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("Per-action cache revalidation fallback to live placement on block column failure (ADR-097)")
  void testCacheRevalidationFallbackOnInvalidBlock() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    ActionDefinition def = new ActionDefinition(
        "fallback_action", "fallback_action", "rtp.action.fallback", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    // Mock parent region with candidate validator that rejects worldX=999
    io.github.dailystruggle.rtp.common.selection.region.Region mockRegion =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
    org.mockito.Mockito.when(mockRegion.candidateValidator()).thenReturn((x, z) -> {
      if (x == 999) return null; // invalidated!
      return new io.github.dailystruggle.rtp.common.selection.region.RTPLocation(
          new io.github.dailystruggle.rtp.api.world.RTPCoords("world", x, 64, z), 1);
    });
    RTP.selectionAPI.permRegionLookup.put("default", mockRegion);

    java.util.concurrent.atomic.AtomicInteger liveCalls = new java.util.concurrent.atomic.AtomicInteger(0);
    RTP.groupPlacementService = req -> {
      liveCalls.incrementAndGet();
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(Map.of(p1, new RTPLocation(serverAccessor.getRTPWorld("world"), 100, 64, 100))));
    };

    // Offer cached location at x=999 (which will fail revalidation)
    RTPLocation invalidLoc = new RTPLocation(serverAccessor.getRTPWorld("world"), 999, 64, 200);
    ActionManager.PrevalidatedActionPlacement item =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), invalidLoc), 999, 200);
    actionManager.offerCachedPlacement("fallback_action", item);

    // Trigger: cache recheck fails -> falls back to live placement -> succeeds!
    ActionSessionResult res = actionManager.trigger("fallback_action", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res.success());
    assertEquals(1, liveCalls.get(), "Must fallback to live placement when cached item is invalidated");

    actionManager.disarm(res.sessionId());
    RTP.selectionAPI.permRegionLookup.remove("default");
  }

  @Test
  @DisplayName("Cache clearing, startup orphan sweeping, and session tick execution")
  void testCacheClearOrphanSweepAndTick() {
    // 1. sweepStartupOrphans
    actionManager.sweepStartupOrphans();
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("scoreboard objectives remove rtp_session_id")));

    // 2. clearCaches
    actionManager.offerCachedPlacement("cached_action",
        new ActionManager.PrevalidatedActionPlacement(Map.of(), 0, 0));
    actionManager.clearCaches();
    assertEquals(0, actionManager.getCachedPlacementCount("cached_action"));

    // 3. registerPredicate
    actionManager.registerPredicate("custom_pred", ctx -> true);

    // 4. tick
    actionManager.tick();

    // 5. invalid triggers
    assertFalse(actionManager.trigger(null, List.of(UUID.randomUUID()), null).join().success());
    assertFalse(actionManager.trigger("cached_action", null, null).join().success());
    assertFalse(actionManager.trigger("cached_action", List.of(), null).join().success());
  }

  @Test
  @DisplayName("ActionManager accessors, unknown action trigger, and session query")
  void testActionManagerAccessorsAndOverloads() {
    ActionDefinition def = new ActionDefinition(
        "test_acc", "test_acc", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);

    actionManager.registerAction(def);
    assertTrue(actionManager.getAction("test_acc").isPresent());
    assertEquals(def, actionManager.getAction("test_acc").get());
    assertTrue(actionManager.getActionIds().contains("test_acc"));
    assertFalse(actionManager.getAction("non_existent").isPresent());

    // Unknown action trigger fails
    UUID p = UUID.randomUUID();
    ActionSessionResult unkResult = actionManager.trigger("non_existent_action", List.of(p), ActionContext.EMPTY).join();
    assertFalse(unkResult.success());

    // Session query for non-participant
    assertFalse(actionManager.getSessionForParticipant(p).isPresent());
    assertFalse(actionManager.getSessionForParticipant(null).isPresent());
    assertFalse(actionManager.getSession(UUID.randomUUID()).isPresent());
    assertFalse(actionManager.getSession(null).isPresent());
    assertDoesNotThrow(() -> actionManager.disarm(null));
  }

  @Test
  @DisplayName("Nearplayer anchor resolves automatically to online player location when not provided in context")
  void testNearPlayerAutoResolvesOnlinePlayer() {
    UUID onlinePlayerId = UUID.randomUUID();
    RTPLocation targetLoc = new RTPLocation(serverAccessor.getRTPWorld("world"), 150, 72, -300);
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(onlinePlayerId, "TargetPlayer", targetLoc));

    UUID callerId = UUID.randomUUID();
    ActionDefinition.PlacementSpec nearPlayerSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("anchor", "nearplayer"), 3, 2);
    ActionDefinition def = new ActionDefinition(
        "nearplayer_test", "nearplayer_test", "perm", "desc",
        nearPlayerSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    java.util.concurrent.atomic.AtomicReference<io.github.dailystruggle.rtp.api.group.GroupPlacementRequest> capturedRequest =
        new java.util.concurrent.atomic.AtomicReference<>();
    RTP.groupPlacementService = req -> {
      capturedRequest.set(req);
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(Map.of(callerId, targetLoc)));
    };

    // Trigger nearplayer with EMPTY context (like an operator running /rtp action nearplayer)
    ActionSessionResult res = actionManager.trigger("nearplayer_test", List.of(callerId), ActionContext.EMPTY).join();
    assertTrue(res.success(), "Nearplayer action must succeed by auto-discovering online player");
    assertNotNull(capturedRequest.get());
    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("Phase 6.2: Cache eviction closes chunk tickets when capacity is exceeded (S-002)")
  void testCacheFullEvictionClosesReservations() {
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 1); // cacheSize = 1
    ActionDefinition def = new ActionDefinition(
        "evict_action", "evict_action", "rtp.action.evict", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    // Create 2 mock reservations to verify close() is called when cache overflows
    io.github.dailystruggle.rtp.api.world.ChunkReservation res1 =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.ChunkReservation.class);
    io.github.dailystruggle.rtp.api.world.ChunkReservation res2 =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.ChunkReservation.class);

    RTPLocation loc1 = new RTPLocation(serverAccessor.getRTPWorld("world"), 100, 64, 100, res1);
    RTPLocation loc2 = new RTPLocation(serverAccessor.getRTPWorld("world"), 200, 64, 200, res2);

    ActionManager.PrevalidatedActionPlacement item1 =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), loc1), 100, 100);
    ActionManager.PrevalidatedActionPlacement item2 =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), loc2), 200, 200);

    // Offer 1st item: should fit in cache
    boolean accepted1 = actionManager.offerCachedPlacement("evict_action", item1);
    assertTrue(accepted1);
    assertEquals(1, actionManager.getCachedPlacementCount("evict_action"));
    org.mockito.Mockito.verify(res1, org.mockito.Mockito.never()).close();

    // Offer 2nd item: capacity is 1, so item2 must be rejected and immediately closed (S-002)
    boolean accepted2 = actionManager.offerCachedPlacement("evict_action", item2);
    assertFalse(accepted2);
    assertEquals(1, actionManager.getCachedPlacementCount("evict_action"));
    org.mockito.Mockito.verify(res2, org.mockito.Mockito.times(1)).close();

    // Clear caches: item1 in cache should now also be closed
    actionManager.clearCaches();
    assertEquals(0, actionManager.getCachedPlacementCount("evict_action"));
    org.mockito.Mockito.verify(res1, org.mockito.Mockito.times(1)).close();
  }

  @Test
  @DisplayName("Phase 6.2: Pre-flight invalidation closes chunk tickets and records fail type in memory shape (S-001, S-002)")
  @SuppressWarnings("unchecked")
  void testPreFlightInvalidationReleasesTicketsAndLearnsHazard() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("hazard_region", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    ActionDefinition def = new ActionDefinition(
        "hazard_action", "hazard_action", "perm", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    // Setup mock region with MemoryShape
    io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape mockShape =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape.class);
    io.github.dailystruggle.rtp.common.selection.region.Region mockRegion =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
    org.mockito.Mockito.when(mockRegion.getShape()).thenReturn(mockShape);
    org.mockito.Mockito.when(mockRegion.candidateValidator()).thenReturn((x, z) -> null); // Rejects everything!
    org.mockito.Mockito.when(mockShape.xzToLocation(org.mockito.ArgumentMatchers.any(long.class), org.mockito.ArgumentMatchers.any(long.class)))
        .thenReturn(9999L);
    RTP.selectionAPI.permRegionLookup.put("hazard_region", mockRegion);

    // Mock live group service fallback
    RTP.groupPlacementService = req -> CompletableFuture.completedFuture(
        GroupPlacementResult.success(Map.of(p1, new RTPLocation(serverAccessor.getRTPWorld("world"), 100, 64, 100))));

    io.github.dailystruggle.rtp.api.world.ChunkReservation res =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.ChunkReservation.class);
    RTPLocation cachedLoc = new RTPLocation(serverAccessor.getRTPWorld("world"), 300, 64, 300, res);
    ActionManager.PrevalidatedActionPlacement cachedItem =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), cachedLoc), 300, 300);

    actionManager.offerCachedPlacement("hazard_action", cachedItem);
    assertEquals(1, actionManager.getCachedPlacementCount("hazard_action"));

    // Trigger: cached revalidation fails -> should release reservation and record hazard
    ActionSessionResult result = actionManager.trigger("hazard_action", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(result.success()); // Fell back to live placement and succeeded
    org.mockito.Mockito.verify(res, org.mockito.Mockito.times(1)).close();

    // Verify hazard recorded in memory shape
    org.mockito.Mockito.verify(mockShape).addBadChunk(org.mockito.ArgumentMatchers.eq(9999L), org.mockito.ArgumentMatchers.any());

    actionManager.disarm(result.sessionId());
    RTP.selectionAPI.permRegionLookup.remove("hazard_region");
  }

  @Test
  @DisplayName("Pre-execution gates: Action with players gate queues until participant condition is met")
  void testActionPreExecutionGateQueuesUntilConditionMet() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    // Action requiring at least 2 players via gate
    Map<String, Object> gateConfig = Map.of("players", ">= 2");
    ActionDefinition def = new ActionDefinition(
        "multi_participant_gate_action", "multi_participant_gate_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED, // Disabled placement for pure testing
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        gateConfig);
    actionManager.registerAction(def);

    // Trigger with 1 player: gate fails, returns uncompleted future waiting in queue
    CompletableFuture<ActionSessionResult> future1 = actionManager.trigger("multi_participant_gate_action", List.of(p1), ActionContext.EMPTY);
    assertFalse(future1.isDone(), "Action with unmet gate must be held in queue");

    // Trigger with 2nd player: queue satisfies gate (1 + 1 = 2 players), drains both and starts session!
    CompletableFuture<ActionSessionResult> future2 = actionManager.trigger("multi_participant_gate_action", List.of(p2), ActionContext.EMPTY);

    ActionSessionResult res1 = future1.join();
    ActionSessionResult res2 = future2.join();

    assertTrue(res1.success());
    assertTrue(res2.success());
    assertEquals(res1.sessionId(), res2.sessionId(), "Both queued participants must join the same merged session");

    Optional<ActionSession> session = actionManager.getSession(res1.sessionId());
    assertTrue(session.isPresent());
    assertEquals(2, session.get().participants().size());
    assertTrue(session.get().participants().contains(p1));
    assertTrue(session.get().participants().contains(p2));

    actionManager.disarm(res1.sessionId());
  }

  @Test
  @DisplayName("ActionContext gate validators: custom predicate blocks immediate execution")
  void testActionContextGateValidators() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition def = new ActionDefinition(
        "validator_action", "validator_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    // 1. Context validator returns false -> queues
    ActionContext failingCtx = ActionContext.empty().withValidator(ctx -> false);
    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("validator_action", List.of(p1), failingCtx);
    assertFalse(f1.isDone());

    // 2. Context validator returns true -> passes immediately
    UUID p2 = UUID.randomUUID();
    ActionContext passingCtx = ActionContext.empty().withValidator(ctx -> true);
    ActionSessionResult res2 = actionManager.trigger("validator_action", List.of(p2), passingCtx).join();
    assertTrue(res2.success());
    actionManager.disarm(res2.sessionId());
  }

  @Test
  @DisplayName("Disabled placement action runs session without invoking GroupPlacementService")
  void testDisabledPlacementBypassesGroupService() {
    UUID p1 = UUID.randomUUID();
    java.util.concurrent.atomic.AtomicBoolean groupServiceCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
    RTP.groupPlacementService = req -> {
      groupServiceCalled.set(true);
      return CompletableFuture.completedFuture(GroupPlacementResult.success(Map.of()));
    };

    ActionDefinition def = new ActionDefinition(
        "no_placement_action", "no_placement_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    ActionSessionResult res = actionManager.trigger("no_placement_action", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res.success());
    assertFalse(groupServiceCalled.get(), "GroupPlacementService must NOT be called when placement is disabled");

    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("Wait-queue matching: candidate entries matched by command gate and numbered tokens")
  void testWaitQueueMatchingWithCommandGate() {
    serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "check_match"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        // If args has "Alice Bob", returns true; otherwise false
        if (args.length >= 2 && "Alice".equalsIgnoreCase(args[0]) && "Bob".equalsIgnoreCase(args[1])) {
          return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
        return java.util.concurrent.CompletableFuture.completedFuture(false);
      }
    });

    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID p3 = UUID.randomUUID();

    // Action requiring 2 players AND command match between [player_name_1] and [player_name_2]
    List<Map<String, Object>> gates = List.of(
        Map.of("players", ">= 2"),
        Map.of("command", "check_match [player_name_1] [player_name_2]")
    );

    ActionDefinition def = new ActionDefinition(
        "duel_match_action", "duel_match_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        gates);
    actionManager.registerAction(def);

    // 1. Entry 1: Alice enters queue
    ActionContext aliceCtx = new ActionContext(Map.of("player_name", "Alice"));
    CompletableFuture<ActionSessionResult> fAlice = actionManager.trigger("duel_match_action", List.of(p1), aliceCtx);
    assertFalse(fAlice.isDone());

    // 2. Entry 2: Charlie enters queue (check_match Alice Charlie will fail!)
    ActionContext charlieCtx = new ActionContext(Map.of("player_name", "Charlie"));
    CompletableFuture<ActionSessionResult> fCharlie = actionManager.trigger("duel_match_action", List.of(p2), charlieCtx);
    assertFalse(fAlice.isDone(), "Alice and Charlie do not match command gate");
    assertFalse(fCharlie.isDone());

    // 3. Entry 3: Bob enters queue (check_match Alice Bob passes!)
    ActionContext bobCtx = new ActionContext(Map.of("player_name", "Bob"));
    CompletableFuture<ActionSessionResult> fBob = actionManager.trigger("duel_match_action", List.of(p3), bobCtx);

    // Alice and Bob should now be popped and completed together!
    ActionSessionResult resAlice = fAlice.join();
    ActionSessionResult resBob = fBob.join();

    assertTrue(resAlice.success());
    assertTrue(resBob.success());
    assertEquals(resAlice.sessionId(), resBob.sessionId(), "Alice and Bob must share the same session");

    // Charlie should still be waiting in the queue!
    assertFalse(fCharlie.isDone(), "Charlie must remain in queue");

    actionManager.disarm(resAlice.sessionId());
  }

  @Test
  @DisplayName("onEnqueue lifecycle hook triggers when action invocation enters wait queue")
  void testOnEnqueueLifecycleHook() {
    java.util.concurrent.atomic.AtomicBoolean enqueueCommandRan = new java.util.concurrent.atomic.AtomicBoolean(false);
    serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "notify_enqueue"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        enqueueCommandRan.set(true);
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionDefinition.LifecycleSpec life = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(
            Collections.emptyMap(),
            List.of(ActionDefinition.CommandAction.console("notify_enqueue [player_name]"))))
    );

    ActionDefinition def = new ActionDefinition(
        "enqueue_hook_action", "enqueue_hook_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        life,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    UUID p1 = UUID.randomUUID();
    ActionContext ctx = new ActionContext(Map.of("player_name", "Alice"));
    CompletableFuture<ActionSessionResult> f = actionManager.trigger("enqueue_hook_action", List.of(p1), ctx);

    assertFalse(f.isDone(), "Must be enqueued waiting for 2 players");
    assertTrue(enqueueCommandRan.get(), "onEnqueue command must have executed upon entering queue");
  }

  @Test
  @DisplayName("Wait Queue & Active Session Cancellation: cancelParticipant removes entry or disarms session and runs onCancel")
  void testCancelParticipantQueueAndSession() {
    java.util.concurrent.atomic.AtomicBoolean cancelHookRan = new java.util.concurrent.atomic.AtomicBoolean(false);
    serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "notify_cancel"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        cancelHookRan.set(true);
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionDefinition.LifecycleSpec life = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(
            Collections.emptyMap(),
            List.of(ActionDefinition.CommandAction.console("notify_cancel [player_name]"))))
    );

    ActionDefinition def = new ActionDefinition(
        "cancel_test_action", "cancel_test_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        life,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    UUID p1 = UUID.randomUUID();
    ActionContext ctx = new ActionContext(Map.of("player_name", "Alice"));

    // Case 1: Cancel from wait-queue
    CompletableFuture<ActionSessionResult> f = actionManager.trigger("cancel_test_action", List.of(p1), ctx);
    assertFalse(f.isDone(), "Should be waiting in queue");

    // Cancel participant with specific actionId
    boolean cancelled = actionManager.cancelParticipant(p1, "cancel_test_action");
    assertTrue(cancelled, "cancelParticipant must return true when player is in wait queue");
    assertTrue(f.isDone(), "Future should be completed on cancellation");
    ActionSessionResult res = f.join();
    assertFalse(res.success(), "Result must be failure");
    assertEquals("CANCELLED", res.failureReason());
    assertTrue(cancelHookRan.get(), "onCancel lifecycle hook should have executed");

    // Cancel again when not in queue or session returns false
    assertFalse(actionManager.cancelParticipant(p1, "cancel_test_action"));

    // Case 2: Cancel from active session
    ActionDefinition defSingle = new ActionDefinition(
        "single_action", "single_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        life);
    actionManager.registerAction(defSingle);

    cancelHookRan.set(false);
    ActionSessionResult activeRes = actionManager.trigger("single_action", List.of(p1), ctx).join();
    assertTrue(activeRes.success());
    assertTrue(actionManager.getSession(activeRes.sessionId()).isPresent());

    // Cancel active participant (actionId null -> match any action)
    boolean activeCancelled = actionManager.cancelParticipant(p1, null);
    assertTrue(activeCancelled, "cancelParticipant must return true when player is in active session");
    assertFalse(actionManager.getSession(activeRes.sessionId()).isPresent(), "Session should be disarmed");
    assertTrue(cancelHookRan.get(), "onCancel lifecycle hook should have executed for active session cancellation");
  }

  @Test
  @DisplayName("Commands with missing target placeholders are dropped in lifecycle steps")
  void testCommandsWithMissingTargetDropped() {
    java.util.concurrent.atomic.AtomicBoolean targetCommandRan = new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean normalCommandRan = new java.util.concurrent.atomic.AtomicBoolean(false);

    serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "target_cmd"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        targetCommandRan.set(true);
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "normal_cmd"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        normalCommandRan.set(true);
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionDefinition.LifecycleSpec life = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(
            Collections.emptyMap(),
            List.of(
                ActionDefinition.CommandAction.console("target_cmd [target_name]"),
                ActionDefinition.CommandAction.console("normal_cmd [sender_name]")
            )))
    );

    ActionDefinition def = new ActionDefinition(
        "drop_target_action", "drop_target_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        life,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    UUID p1 = UUID.randomUUID();
    ActionContext ctx = new ActionContext(Map.of("sender_name", "Alice"));
    actionManager.trigger("drop_target_action", List.of(p1), ctx);

    assertFalse(targetCommandRan.get(), "Command with [target_name] must be dropped when target is absent");
    assertTrue(normalCommandRan.get(), "Command without missing target placeholders must execute");
  }

  @Test
  @DisplayName("Two players without explicit target match symmetrically in wait queue")
  void testTwoPlayersWithoutTargetMatchSymmetrically() {
    serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
      @Override public String name() { return "execute"; }
      @Override public String permission() { return "test.use"; }
      @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
        return true;
      }
      @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
          UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
    });

    ActionDefinition def = new ActionDefinition(
        "symmetric_duel", "symmetric_duel", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(
            Map.of("players", ">= 2")
        )
    );
    actionManager.registerAction(def);

    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    ActionContext ctx1 = new ActionContext(Map.of("sender_name", "Alice", "player_name", "Alice"));
    ActionContext ctx2 = new ActionContext(Map.of("sender_name", "Bob", "player_name", "Bob"));

    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("symmetric_duel", List.of(p1), ctx1);
    assertFalse(f1.isDone(), "First player should be waiting in queue");

    CompletableFuture<ActionSessionResult> f2 = actionManager.trigger("symmetric_duel", List.of(p2), ctx2);
    assertTrue(f1.isDone(), "First player future should complete when second player arrives");
    assertTrue(f2.isDone(), "Second player future should complete when matched");

    ActionSessionResult r1 = f1.join();
    ActionSessionResult r2 = f2.join();
    assertTrue(r1.success());
    assertTrue(r2.success());
    assertEquals(r1.sessionId(), r2.sessionId(), "Both players should join the same matched session");
  }

  @Test
  @DisplayName("Two players with targeted challenges only match when declarative reciprocity gate matches")
  void testTargetedChallengesMatchOnlyWhenReciprocal() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID charlie = UUID.randomUUID();

    // Register named players so the native entity/tag predicate can resolve name -> uuid -> tags.
    io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
        new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        charlie, "Charlie", new RTPLocation(world, 0, 64, 0)));

    ActionDefinition def = new ActionDefinition(
        "targeted_duel", "targeted_duel", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(
            Map.of("players", ">= 2"),
            Map.of("command", List.of(
                "execute if entity @a[name=[sender_name_1],tag=rtp_chal_[sender_name_2]]",
                "execute if entity @a[name=[sender_name_2],tag=rtp_chal_[sender_name_1]]"
            ))
        )
    );
    actionManager.registerAction(def);

    ActionContext aliceCtx = new ActionContext(Map.of("sender_name", "Alice", "player_name", "Alice", "target", "Bob", "target_name", "Bob"));
    ActionContext charlieCtx = new ActionContext(Map.of("sender_name", "Charlie", "player_name", "Charlie", "target", "David", "target_name", "David"));
    ActionContext bobCtx = new ActionContext(Map.of("sender_name", "Bob", "player_name", "Bob", "target", "Alice", "target_name", "Alice"));

    // Reciprocity state expressed through real scoreboard tags (as lifecycle onEnqueue would set them).
    // Alice has challenged Bob (has tag rtp_chal_Bob).
    serverAccessor.executeCommand(new UUID(0, 0), "tag Alice add rtp_chal_Bob");
    // Charlie has challenged David (has tag rtp_chal_David) - David is not a participant.
    serverAccessor.executeCommand(new UUID(0, 0), "tag Charlie add rtp_chal_David");

    // 1. Alice queues targeting Bob
    CompletableFuture<ActionSessionResult> aliceFuture = actionManager.trigger("targeted_duel", List.of(alice), aliceCtx);
    assertFalse(aliceFuture.isDone());

    // 2. Charlie queues targeting David - declarative reciprocity gate fails with Alice!
    CompletableFuture<ActionSessionResult> charlieFuture = actionManager.trigger("targeted_duel", List.of(charlie), charlieCtx);
    assertFalse(aliceFuture.isDone());
    assertFalse(charlieFuture.isDone());

    // 3. Bob queues targeting Alice (Bob now has tag rtp_chal_Alice)
    serverAccessor.executeCommand(new UUID(0, 0), "tag Bob add rtp_chal_Alice");
    CompletableFuture<ActionSessionResult> bobFuture = actionManager.trigger("targeted_duel", List.of(bob), bobCtx);
    assertTrue(aliceFuture.isDone(), "Alice should now be matched with Bob");
    assertTrue(bobFuture.isDone(), "Bob should now be matched with Alice");
    assertFalse(charlieFuture.isDone(), "Charlie should still be in queue");

    ActionSessionResult rAlice = aliceFuture.join();
    ActionSessionResult rBob = bobFuture.join();
    assertTrue(rAlice.success());
    assertTrue(rBob.success());
    assertEquals(rAlice.sessionId(), rBob.sessionId(), "Alice and Bob must share the session");
  }

  @Test
  @DisplayName("Blank challenge target = open matchmaking: two no-target challengers pair via missing-target wildcard skip")
  void testBlankTargetChallengeOpenMatchmaking() {
    UUID leaf = UUID.randomUUID();
    UUID leaf2 = UUID.randomUUID();

    io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
        new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        leaf, "leaf26", new RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        leaf2, "leaf_26", new RTPLocation(world, 0, 64, 0)));

    // Target-based reciprocity gate (mirrors shipped challenge.yml): when a challenger has no target,
    // [target_name_N] is unresolved and the missing-target wildcard skips the reciprocity check, so
    // open challengers pair purely on players >= 2.
    ActionDefinition def = new ActionDefinition(
        "targeted_duel", "targeted_duel", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(
            Map.of("players", ">= 2"),
            Map.of("command", List.of(
                "execute if entity @a[name=[sender_name_1],tag=rtp_chal_[target_name_1]]",
                "execute if entity @a[name=[sender_name_2],tag=rtp_chal_[target_name_2]]"
            ))
        )
    );
    actionManager.registerAction(def);

    // No target metadata => open matchmaking.
    ActionContext ctx1 = new ActionContext(Map.of("sender_name", "leaf26", "player_name", "leaf26"));
    ActionContext ctx2 = new ActionContext(Map.of("sender_name", "leaf_26", "player_name", "leaf_26"));

    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("targeted_duel", List.of(leaf), ctx1);
    assertFalse(f1.isDone(), "leaf26 must wait until a second open challenger arrives");

    CompletableFuture<ActionSessionResult> f2 = actionManager.trigger("targeted_duel", List.of(leaf2), ctx2);

    // Both open challengers pair with each other on players >= 2 (reciprocity skipped for no-target).
    assertTrue(f1.isDone(), "leaf26 should be matched once a second open challenger queues");
    assertTrue(f2.isDone(), "leaf_26 should be matched with leaf26");

    ActionSessionResult r1 = f1.join();
    ActionSessionResult r2 = f2.join();
    assertTrue(r1.success());
    assertTrue(r2.success());
    assertEquals(r1.sessionId(), r2.sessionId(), "Open challengers must share the matched session");
  }

  @Test
  @DisplayName("Targeted challenge stays queued until the named opponent reciprocates (target-based gate)")
  void testTargetedChallengeRequiresReciprocity() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
        new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionDefinition def = new ActionDefinition(
        "targeted_duel", "targeted_duel", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(
            Map.of("players", ">= 2"),
            Map.of("command", List.of(
                "execute if entity @a[name=[sender_name_1],tag=rtp_chal_[target_name_1]]",
                "execute if entity @a[name=[sender_name_2],tag=rtp_chal_[target_name_2]]"
            ))
        )
    );
    actionManager.registerAction(def);

    ActionContext aliceCtx = new ActionContext(Map.of("sender_name", "Alice", "player_name", "Alice", "target", "Bob", "target_name", "Bob"));
    ActionContext bobCtx = new ActionContext(Map.of("sender_name", "Bob", "player_name", "Bob", "target", "Alice", "target_name", "Alice"));

    // Alice has challenged Bob (as lifecycle onEnqueue would tag), Bob has not reciprocated yet.
    serverAccessor.executeCommand(new UUID(0, 0), "tag Alice add rtp_chal_Bob");

    CompletableFuture<ActionSessionResult> aliceFuture = actionManager.trigger("targeted_duel", List.of(alice), aliceCtx);
    assertFalse(aliceFuture.isDone(), "Alice waits until Bob reciprocates");

    // Bob reciprocates.
    serverAccessor.executeCommand(new UUID(0, 0), "tag Bob add rtp_chal_Alice");
    CompletableFuture<ActionSessionResult> bobFuture = actionManager.trigger("targeted_duel", List.of(bob), bobCtx);

    assertTrue(aliceFuture.isDone(), "Alice matches once Bob reciprocates");
    assertTrue(bobFuture.isDone(), "Bob matches with Alice");
    assertEquals(aliceFuture.join().sessionId(), bobFuture.join().sessionId(), "Reciprocal pair shares a session");
  }

  @Test
  @DisplayName("Matched wait queue entries preserve numbered participant tokens for onStart lifecycle")
  void testMatchedWaitQueueNumberedTokensInLifecycle() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
        new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Collections.emptyMap(),
                List.of(
                    ActionDefinition.CommandAction.console("tag [sender_name_1] remove rtp_chal_[sender_name_2]"),
                    ActionDefinition.CommandAction.console("tag [sender_name_2] remove rtp_chal_[sender_name_1]")
                ),
                0L
            )
        ),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList()
    );

    ActionDefinition def = new ActionDefinition(
        "challenge_duel", "challenge_duel", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2"))
    );
    actionManager.registerAction(def);

    // Both players have challenge tags
    serverAccessor.executeCommand(new UUID(0, 0), "tag Alice add rtp_chal_Bob");
    serverAccessor.executeCommand(new UUID(0, 0), "tag Bob add rtp_chal_Alice");

    ActionContext aliceCtx = new ActionContext(Map.of("sender_name", "Alice", "player_name", "Alice"));
    ActionContext bobCtx = new ActionContext(Map.of("sender_name", "Bob", "player_name", "Bob"));

    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("challenge_duel", List.of(alice), aliceCtx);
    CompletableFuture<ActionSessionResult> f2 = actionManager.trigger("challenge_duel", List.of(bob), bobCtx);

    assertTrue(f1.isDone());
    assertTrue(f2.isDone());

    // Verify onStart removed the tags with properly substituted sender_name_1 and sender_name_2
    assertTrue(serverAccessor.getExecutedCommands().contains("tag Alice remove rtp_chal_Bob"));
    assertTrue(serverAccessor.getExecutedCommands().contains("tag Bob remove rtp_chal_Alice"));
  }
}
