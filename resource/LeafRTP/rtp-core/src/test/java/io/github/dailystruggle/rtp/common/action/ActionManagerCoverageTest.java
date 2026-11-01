package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.AnchorSource;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ActionManagerCoverageTest {

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;
  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  @BeforeEach
  void setUp() {
    serverAccessor = new MockRTPServerAccessor(new File("."));
    RTPRuntime.setServerAccessor(serverAccessor);
    originalGroupService = RTP.groupPlacementService;
    actionManager = new ActionManager();
  }

  @AfterEach
  void tearDown() {
    actionManager.clearCaches();
    RTP.groupPlacementService = originalGroupService;
  }

  private ActionDefinition disabledDef(String id, List<Map<String, Object>> gates) {
    return new ActionDefinition(
        id, id, "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        gates);
  }

  @Test
  @DisplayName("PrevalidatedActionPlacement.release tolerates null locations and throwing reservations")
  void testPlacementReleaseToleratesFailures() {
    ChunkReservation throwing = Mockito.mock(ChunkReservation.class);
    Mockito.doThrow(new RuntimeException("boom")).when(throwing).close();
    Map<UUID, RTPLocation> map = new HashMap<>();
    map.put(UUID.randomUUID(), null);
    map.put(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0));
    map.put(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 1, 64, 1, throwing));
    ActionManager.PrevalidatedActionPlacement p = new ActionManager.PrevalidatedActionPlacement(map, 0, 0);
    assertSame(map, p.placements());
    assertDoesNotThrow(p::release);
    Mockito.verify(throwing).close();
  }

  @Test
  @DisplayName("ActionWaitQueueEntry defaults null context to EMPTY and exposes accessors")
  void testWaitQueueEntryAccessors() {
    UUID p = UUID.randomUUID();
    CompletableFuture<ActionSessionResult> f = new CompletableFuture<>();
    ActionManager.ActionWaitQueueEntry e = new ActionManager.ActionWaitQueueEntry(List.of(p), null, f);
    assertEquals(List.of(p), e.participants());
    assertSame(ActionContext.EMPTY, e.context());
    assertSame(f, e.future());
  }

  @Test
  @DisplayName("offerCachedPlacement rejects null arguments and unknown actions; count handles null id")
  void testOfferCachedPlacementGuards() {
    ActionManager.PrevalidatedActionPlacement p =
        new ActionManager.PrevalidatedActionPlacement(Map.of(), 0, 0);
    assertFalse(actionManager.offerCachedPlacement(null, p));
    assertFalse(actionManager.offerCachedPlacement("x", null));
    assertFalse(actionManager.offerCachedPlacement("unknown_action", p));
    assertEquals(0, actionManager.getCachedPlacementCount(null));
    assertEquals(0, actionManager.getCachedPlacementCount("unknown_action"));
  }

  @Test
  @DisplayName("cancelParticipant skips wait queues of other actions and returns false")
  void testCancelParticipantOtherActionSkipped() {
    actionManager.registerAction(disabledDef("queued_a", List.of(Map.of("players", ">= 2"))));
    UUID p1 = UUID.randomUUID();
    CompletableFuture<ActionSessionResult> f =
        actionManager.trigger("queued_a", List.of(p1), ActionContext.EMPTY);
    assertFalse(f.isDone());
    assertFalse(actionManager.cancelParticipant(p1, "other_action"));
    assertFalse(f.isDone());
    // Another participant not in the queue: no removal.
    assertFalse(actionManager.cancelParticipant(UUID.randomUUID(), " "));
    assertTrue(actionManager.cancelParticipant(p1, ""));
    assertTrue(f.isDone());
    // Queue now empty; second cancel cleans up and returns false.
    assertFalse(actionManager.cancelParticipant(p1, null));
  }

  @Test
  @DisplayName("surrenderParticipant on active session returns true; mismatched action id returns false")
  void testSurrenderParticipant() {
    actionManager.registerAction(disabledDef("solo", List.of()));
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(p1, "S1", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    serverAccessor.addPlayer(new MockRTPPlayer(p2, "S2", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    ActionSessionResult res = actionManager.trigger("solo", List.of(p1, p2), ActionContext.EMPTY).join();
    assertTrue(res.success());
    assertFalse(actionManager.surrenderParticipant(p1, "not_solo"));
    assertFalse(actionManager.surrenderParticipant(UUID.randomUUID(), null));
    assertTrue(actionManager.surrenderParticipant(p1, "SOLO"));
    actionManager.disarm(res.sessionId());
  }

  private ActionDefinition enqueueDef(String id, List<ActionDefinition.LifecycleStep> onEnqueue) {
    ActionDefinition.LifecycleSpec life = new ActionDefinition.LifecycleSpec(
        List.of(), List.of(), List.of(), List.of(), onEnqueue, List.of());
    return new ActionDefinition(
        id, id, "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        life,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 3")));
  }

  @Test
  @DisplayName("onEnqueue executes console, player, for-each, message and message-target actions")
  void testEnqueueActionTypes() {
    UUID p1 = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(p1, "Enq1", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    serverAccessor.addPlayer(new MockRTPPlayer(target, "Tgt", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    List<ActionDefinition.LifecycleStep> steps = new java.util.ArrayList<>();
    steps.add(new ActionDefinition.LifecycleStep(Map.of(), List.of(
        ActionDefinition.CommandAction.console("say enqueue_console"),
        ActionDefinition.CommandAction.player("say enqueue_player"),
        ActionDefinition.CommandAction.forEach(List.of(ActionDefinition.CommandAction.console("say enqueue_each"))),
        ActionDefinition.CommandAction.message("hello"),
        ActionDefinition.CommandAction.messageTarget("invite"),
        ActionDefinition.CommandAction.action(""),
        ActionDefinition.CommandAction.action("missing_nested"))));
    actionManager.registerAction(enqueueDef("enq_types", steps));

    CompletableFuture<ActionSessionResult> f = actionManager.trigger(
        "enq_types", List.of(p1), new ActionContext(Map.of("target_name", "Tgt")));
    assertFalse(f.isDone());
    List<String> cmds = serverAccessor.getExecutedCommands();
    assertTrue(cmds.contains("say enqueue_console"));
    assertTrue(cmds.contains("say enqueue_each"));
    assertTrue(actionManager.cancelParticipant(p1, "enq_types"));
  }

  @Test
  @DisplayName("onEnqueue message-target with UUID target, 'any' target and no target")
  void testEnqueueMessageTargetVariants() {
    UUID target = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(target, "TgtU", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    actionManager.registerAction(enqueueDef("enq_mt", List.of(new ActionDefinition.LifecycleStep(
        Map.of(), List.of(ActionDefinition.CommandAction.messageTarget("invite"),
            ActionDefinition.CommandAction.message("plain"))))));
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    assertFalse(actionManager.trigger("enq_mt", List.of(a), new ActionContext(Map.of("target_uuid", target))).isDone());
    assertFalse(actionManager.trigger("enq_mt", List.of(b), new ActionContext(Map.of("target", "any"))).isDone());
    assertFalse(actionManager.trigger("enq_mt", List.of(c), ActionContext.EMPTY).isDone());
    assertTrue(actionManager.cancelParticipant(a, null));
    assertTrue(actionManager.cancelParticipant(b, null));
    assertTrue(actionManager.cancelParticipant(c, null));
  }

  @Test
  @DisplayName("onEnqueue step with failing gate config is skipped")
  void testEnqueueStepGateSkipped() {
    actionManager.registerPredicate("never", ctx -> false);
    actionManager.registerAction(enqueueDef("enq_gate", List.of(new ActionDefinition.LifecycleStep(
        Map.of("predicate", "never"), List.of(ActionDefinition.CommandAction.console("say gated_out"))))));
    UUID p = UUID.randomUUID();
    assertFalse(actionManager.trigger("enq_gate", List.of(p), ActionContext.EMPTY).isDone());
    assertFalse(serverAccessor.getExecutedCommands().contains("say gated_out"));
    assertTrue(actionManager.cancelParticipant(p, "enq_gate"));
  }

  @Test
  @DisplayName("handlePlayerDeath ignores null and unknown participants and routes active sessions")
  void testHandlePlayerDeath() {
    assertDoesNotThrow(() -> actionManager.handlePlayerDeath(null, null));
    assertDoesNotThrow(() -> actionManager.handlePlayerDeath(UUID.randomUUID(), null));
    actionManager.registerAction(disabledDef("death_act", List.of()));
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    ActionSessionResult res = actionManager.trigger("death_act", List.of(p1, p2), ActionContext.EMPTY).join();
    assertTrue(res.success());
    assertDoesNotThrow(() -> actionManager.handlePlayerDeath(p1, p2));
    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("sweepStartupOrphans removes session tags for each online player; no-op without accessor")
  void testSweepOrphansWithPlayers() {
    UUID p = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(p, "Orphan", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    actionManager.sweepStartupOrphans();
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.equals("tag @a remove rtp_session_*")));
    RTPRuntime.setServerAccessor(null);
    try {
      assertDoesNotThrow(actionManager::sweepStartupOrphans);
    } finally {
      RTPRuntime.setServerAccessor(serverAccessor);
    }
  }

  @Test
  @DisplayName("tick prunes wait-queue entries whose participants went offline or joined a session")
  void testTickPrunesWaitQueue() {
    UUID offline = UUID.randomUUID();
    MockRTPPlayer offlinePlayer =
        new MockRTPPlayer(offline, "Off", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0));
    serverAccessor.addPlayer(offlinePlayer);
    actionManager.registerAction(disabledDef("prune_q", List.of(Map.of("players", ">= 5"))));
    actionManager.registerAction(disabledDef("other_solo", List.of()));
    CompletableFuture<ActionSessionResult> fOff = actionManager.trigger("prune_q", List.of(offline), ActionContext.EMPTY);
    UUID busy = UUID.randomUUID();
    CompletableFuture<ActionSessionResult> fBusy = actionManager.trigger("prune_q", List.of(busy), ActionContext.EMPTY);
    assertFalse(fOff.isDone());
    assertFalse(fBusy.isDone());

    ActionSessionResult solo = actionManager.trigger("other_solo", List.of(busy), ActionContext.EMPTY).join();
    assertTrue(solo.success());
    offlinePlayer.setOnline(false);
    actionManager.tick();

    assertTrue(fOff.join().failureReason().contains("Participant offline"));
    assertTrue(fBusy.join().failureReason().contains("joined another session"));
    // Queue drained; second processing pass removes the empty queue.
    actionManager.processWaitQueues();
    actionManager.processWaitQueue(null);
    actionManager.processWaitQueue(disabledDef("prune_q", List.of()));
    actionManager.disarm(solo.sessionId());
  }

  private static ActionDefinition.PlacementSpec spec(Map<String, Object> params) {
    return new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, params);
  }

  @Test
  @DisplayName("resolveAnchorSource maps claim aliases, unknown types and fixed coordinates parsed from strings")
  void testResolveAnchorSourceVariants() {
    AnchorSource regionQueue = AnchorSource.regionQueue();
    assertEquals(regionQueue, ActionManager.resolveAnchorSource(spec(Map.of()), null, null, null));
    assertEquals(regionQueue, ActionManager.resolveAnchorSource(spec(Map.of("anchor", "bogus")), ActionContext.EMPTY, List.of(), null));
    for (String alias : List.of("claim", "nearclaim", "claimhazard")) {
      assertEquals(AnchorSource.claimHazard(),
          ActionManager.resolveAnchorSource(spec(Map.of("anchor", alias)), ActionContext.EMPTY, List.of(), null));
    }
    // String coordinates are parsed; missing Y defaults to 0; world defaults to region name.
    AnchorSource fixed = ActionManager.resolveAnchorSource(
        spec(Map.of("anchor", "location", "anchorX", " 12 ", "anchorZ", "-7")), null, List.of(), null);
    RTPCoords c = fixed.resolveAnchor("default").join();
    assertEquals(12, c.x());
    assertEquals(0, c.y());
    assertEquals(-7, c.z());
    // Unparseable coordinate falls back to regionQueue.
    assertEquals(regionQueue, ActionManager.resolveAnchorSource(
        spec(Map.of("anchor", "landmark", "anchorX", "abc", "anchorZ", 3)), null, List.of(), null));
  }

  @Test
  @DisplayName("resolveAnchorSource claimBoundary without context or provider falls back to claimHazard")
  void testResolveAnchorClaimBoundaryFallback() {
    io.github.dailystruggle.rtp.api.RTPAPI.hooks.claimBoundaries().clear();
    assertEquals(AnchorSource.claimHazard(), ActionManager.resolveAnchorSource(
        spec(Map.of("anchor", "claimboundary", "namespace", "towny")), ActionContext.EMPTY, List.of(UUID.randomUUID()), null));
    assertEquals(AnchorSource.claimHazard(), ActionManager.resolveAnchorSource(
        spec(Map.of("anchor", "faction")), null, List.of(), null));
  }

  @Test
  @DisplayName("resolveAnchorSource nearplayer with only participants online yields a null-resolving entity anchor")
  void testResolveAnchorNearPlayerNoCandidates() {
    UUID self = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(self, "Self", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    AnchorSource src = ActionManager.resolveAnchorSource(spec(Map.of("anchor", "player")), ActionContext.EMPTY, List.of(self), null);
    assertNull(src.resolveAnchor("world").join());
  }

  @Test
  @DisplayName("isQueued and handlePlayerQuit drain wait-queue entries and active sessions")
  void testIsQueuedAndHandlePlayerQuit() {
    assertFalse(actionManager.isQueued(null, UUID.randomUUID()));
    assertFalse(actionManager.isQueued("x", null));
    assertFalse(actionManager.isQueued("x", UUID.randomUUID()));
    assertDoesNotThrow(() -> actionManager.handlePlayerQuit(null));

    actionManager.registerAction(disabledDef("quit_q", List.of(Map.of("players", ">= 4"))));
    UUID q1 = UUID.randomUUID();
    UUID q2 = UUID.randomUUID();
    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("quit_q", List.of(q1), ActionContext.EMPTY);
    CompletableFuture<ActionSessionResult> f2 = actionManager.trigger("quit_q", List.of(q2), ActionContext.EMPTY);
    assertTrue(actionManager.isQueued("QUIT_Q", q1));
    assertFalse(actionManager.isQueued("quit_q", UUID.randomUUID()));
    actionManager.handlePlayerQuit(q1);
    assertTrue(f1.join().failureReason().contains("Participant disconnected"));
    assertFalse(f2.isDone());
    actionManager.handlePlayerQuit(q2);
    assertTrue(f2.isDone());
    assertFalse(actionManager.isQueued("quit_q", q2));

    actionManager.registerAction(disabledDef("quit_s", List.of()));
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    ActionSessionResult res = actionManager.trigger("quit_s", List.of(s1, s2), ActionContext.EMPTY).join();
    assertTrue(res.success());
    actionManager.handlePlayerQuit(s1);
    assertFalse(actionManager.getSessionForParticipant(s1).isPresent());
    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("hasCachedPlacement checks queue presence and participant capacity")
  void testHasCachedPlacement() {
    ActionDefinition def = new ActionDefinition(
        "cache_has", "cache_has", "perm", "",
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2),
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);
    assertFalse(actionManager.hasCachedPlacement(null, 1));
    assertFalse(actionManager.hasCachedPlacement("cache_has", 1));
    RTPLocation loc = new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0);
    assertTrue(actionManager.offerCachedPlacement("cache_has",
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), loc), 0, 0)));
    assertTrue(actionManager.hasCachedPlacement(" Cache_Has ", 1));
    assertFalse(actionManager.hasCachedPlacement("cache_has", 2));
  }

  @Test
  @DisplayName("Placement-enabled trigger fails cleanly without GroupPlacementService and on empty placement result")
  void testPlacementServiceMissingAndEmptyResult() {
    ActionDefinition def = new ActionDefinition(
        "mem_region", "mem_region", "perm", "",
        new ActionDefinition.PlacementSpec("", "CIRCLE", 64, 16, 8, Map.of("memoryRegion", "  ", "centerRadius", 5)),
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);
    UUID p = UUID.randomUUID();

    RTP.groupPlacementService = null;
    ActionSessionResult r1 = actionManager.trigger("mem_region", List.of(p), ActionContext.EMPTY).join();
    assertFalse(r1.success());
    assertFalse(actionManager.getSessionForParticipant(p).isPresent());

    RTP.groupPlacementService = req -> CompletableFuture.completedFuture(
        io.github.dailystruggle.rtp.api.group.GroupPlacementResult.success(Map.of()));
    ActionSessionResult r2 = actionManager.trigger("mem_region", List.of(p), ActionContext.EMPTY).join();
    assertFalse(r2.success());
    assertFalse(actionManager.getSessionForParticipant(p).isPresent());
  }

  private ActionDefinition cachedDef(String id) {
    return new ActionDefinition(
        id, id, "perm", "",
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 4),
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
  }

  @Test
  @DisplayName("Cached placement teleports online participants and releases excess slot reservations")
  void testCachedPlacementTeleportsAndReleasesExcess() {
    UUID p1 = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(p1, "CacheP", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0));
    serverAccessor.addPlayer(player);
    actionManager.registerAction(cachedDef("cache_tp"));
    RTP.groupPlacementService = req -> CompletableFuture.failedFuture(new IllegalStateException("live not expected"));

    ChunkReservation r1 = Mockito.mock(ChunkReservation.class);
    ChunkReservation r2 = Mockito.mock(ChunkReservation.class);
    Map<UUID, RTPLocation> slots = new java.util.LinkedHashMap<>();
    slots.put(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 50, 32, 50, r1));
    slots.put(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 60, 32, 60, r2));
    assertTrue(actionManager.offerCachedPlacement("cache_tp", new ActionManager.PrevalidatedActionPlacement(slots, 55, 55)));

    ActionSessionResult res = actionManager.trigger("cache_tp", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res.success(), String.valueOf(res.failureReason()));
    Mockito.verify(r1, Mockito.atLeastOnce()).close();
    Mockito.verify(r2, Mockito.atLeastOnce()).close();
    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("Undersized cached placement is discarded and released before live placement")
  void testUndersizedCachedPlacementReleased() {
    actionManager.registerAction(cachedDef("cache_small"));
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    RTP.groupPlacementService = req -> CompletableFuture.completedFuture(
        io.github.dailystruggle.rtp.api.group.GroupPlacementResult.success(Map.of(
            a, new RTPLocation(serverAccessor.getRTPWorld("world"), 10, 64, 10),
            b, new RTPLocation(serverAccessor.getRTPWorld("world"), 30, 64, 40))));
    ChunkReservation r = Mockito.mock(ChunkReservation.class);
    actionManager.offerCachedPlacement("cache_small", new ActionManager.PrevalidatedActionPlacement(
        Map.of(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0, r)), 0, 0));

    ActionSessionResult res = actionManager.trigger("cache_small", List.of(a, b), ActionContext.EMPTY).join();
    assertTrue(res.success());
    Mockito.verify(r).close();
    assertEquals(0, actionManager.getCachedPlacementCount("cache_small"));
    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("executeDirect rejects participants already reserved and conflict message uses the player name")
  void testExecuteDirectConflict() {
    ActionDefinition def = disabledDef("direct_act", List.of());
    actionManager.registerAction(def);
    UUID p = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(p, "Named", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    ActionSessionResult first = actionManager.executeDirect(def, List.of(p), ActionContext.EMPTY).join();
    assertTrue(first.success());

    ActionSessionResult second = actionManager.executeDirect(def, List.of(UUID.randomUUID(), p), ActionContext.EMPTY).join();
    assertFalse(second.success());
    assertTrue(second.failureReason().contains("Named"));

    ActionSessionResult third = actionManager.trigger("direct_act", List.of(p), ActionContext.EMPTY).join();
    assertFalse(third.success());
    assertTrue(third.failureReason().contains("Named"));
    actionManager.disarm(first.sessionId());
  }

  @Test
  @DisplayName("Targeted entry does not pair with an open entry; reciprocal UUID targets pair")
  void testReciprocityTargetedVersusOpen() {
    actionManager.registerAction(disabledDef("recip", List.of(Map.of("players", ">= 2"))));
    UUID open = UUID.randomUUID();
    UUID t1 = UUID.randomUUID();
    UUID t2 = UUID.randomUUID();
    CompletableFuture<ActionSessionResult> fOpen = actionManager.trigger("recip", List.of(open), ActionContext.EMPTY);
    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("recip", List.of(t1),
        new ActionContext(Map.of("target_uuid", t2, "sender_name", "T1")));
    assertFalse(fOpen.isDone(), "open and targeted entries must not pair");
    assertFalse(f1.isDone());
    CompletableFuture<ActionSessionResult> f2 = actionManager.trigger("recip", List.of(t2),
        new ActionContext(Map.of("target_name", "T1", "sender_name", "T2")));
    assertTrue(f1.isDone());
    assertTrue(f2.isDone());
    assertEquals(f1.join().sessionId(), f2.join().sessionId());
    assertFalse(fOpen.isDone());
    assertTrue(actionManager.cancelParticipant(open, "recip"));
    actionManager.disarm(f1.join().sessionId());
  }

  @Test
  @DisplayName("Gate with unparseable players threshold backtracks candidates instead of accumulating")
  void testPlayersGateUnparseableThreshold() {
    actionManager.registerPredicate("never2", ctx -> false);
    actionManager.registerAction(disabledDef("bad_thresh", List.of(
        Map.of("players", ">= many"), Map.of("predicate", "never2"))));
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    CompletableFuture<ActionSessionResult> fa = actionManager.trigger("bad_thresh", List.of(a), ActionContext.EMPTY);
    CompletableFuture<ActionSessionResult> fb = actionManager.trigger("bad_thresh", List.of(b), ActionContext.EMPTY);
    assertFalse(fa.isDone());
    assertFalse(fb.isDone());
    assertTrue(actionManager.isQueued("bad_thresh", a));
    assertTrue(actionManager.isQueued("bad_thresh", b));
    assertTrue(actionManager.cancelParticipant(a, null));
    assertTrue(actionManager.cancelParticipant(b, null));
  }

  @Test
  @DisplayName("resolveAnchorSource prefers context coordinates and world over placement parameters")
  void testResolveAnchorContextCoordinates() {
    ActionContext ctx = new ActionContext(Map.of("anchorX", 5, "anchorY", "70", "anchorZ", 9L, "anchorWorld", "nether"));
    AnchorSource src = ActionManager.resolveAnchorSource(
        spec(Map.of("anchor", "FIXED", "anchorX", 100, "anchorZ", 100, "anchorWorld", "end")), ctx, List.of(), null);
    RTPCoords c = src.resolveAnchor("default").join();
    assertEquals("nether", c.worldName());
    assertEquals(5, c.x());
    assertEquals(70, c.y());
    assertEquals(9, c.z());
  }

  @Test
  @DisplayName("resolveAnchorSource nearplayer discovers a non-participant online player")
  void testResolveAnchorNearPlayerDiscoversOther() {
    UUID self = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    serverAccessor.addPlayer(new MockRTPPlayer(self, "SelfN", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0)));
    serverAccessor.addPlayer(new MockRTPPlayer(other, "OtherN", new RTPLocation(serverAccessor.getRTPWorld("world"), 321, 70, -123)));
    AnchorSource src = ActionManager.resolveAnchorSource(spec(Map.of("anchor", "nearplayer")), null, List.of(self), null);
    RTPCoords c = src.resolveAnchor("world").join();
    assertNotNull(c);
    assertEquals(321, c.x());
    assertEquals(-123, c.z());
  }

  @Test
  @DisplayName("Cached placement fails when a participant is offline and releases the slot")
  void testCachedPlacementOfflineParticipant() {
    UUID p1 = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(p1, "OffC", new RTPLocation(serverAccessor.getRTPWorld("world"), 0, 64, 0));
    player.setOnline(false);
    serverAccessor.addPlayer(player);
    actionManager.registerAction(cachedDef("cache_off"));
    ChunkReservation r = Mockito.mock(ChunkReservation.class);
    actionManager.offerCachedPlacement("cache_off", new ActionManager.PrevalidatedActionPlacement(
        Map.of(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 5, 64, 5, r)), 5, 5));
    ActionSessionResult res = actionManager.trigger("cache_off", List.of(p1), ActionContext.EMPTY).join();
    assertFalse(res.success());
    Mockito.verify(r, Mockito.atLeastOnce()).close();
    assertFalse(actionManager.getSessionForParticipant(p1).isPresent());
  }

  @Test
  @DisplayName("Cached placement for an unknown participant releases reservation and starts the session")
  void testCachedPlacementUnknownPlayer() {
    UUID ghost = UUID.randomUUID();
    actionManager.registerAction(cachedDef("cache_ghost"));
    ChunkReservation r = Mockito.mock(ChunkReservation.class);
    actionManager.offerCachedPlacement("cache_ghost", new ActionManager.PrevalidatedActionPlacement(
        Map.of(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 7, 64, 7, r)), 7, 7));
    ActionSessionResult res = actionManager.trigger("cache_ghost", List.of(ghost), ActionContext.EMPTY).join();
    assertTrue(res.success(), String.valueOf(res.failureReason()));
    Mockito.verify(r, Mockito.atLeastOnce()).close();
    actionManager.disarm(res.sessionId());
  }
}
