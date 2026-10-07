package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.action.ParameterSpec;
import io.github.dailystruggle.rtp.api.action.ParameterType;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.group.GroupProfileSpec;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.action.ActionCommand;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-RTP-S-004 / S-005: Regression verification for RTP-5 and RTP-6.
 * Covers group placement failure rollback, single teleport guarantee, disconnect pruning,
 * mutual duel reciprocity, player parameter permissions, and explicit target messaging.
 */
class Rtp5And6ActionSubsystemTest {

  @org.junit.jupiter.api.io.TempDir
  File tempDir;

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;
  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  @BeforeEach
  void setUp() {
    serverAccessor = io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir);
    originalGroupService = RTP.groupPlacementService;
    actionManager = new ActionManager();
    RTP.actionManager = actionManager;
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = actionManager;
  }

  @AfterEach
  void tearDown() {
    RTP.groupPlacementService = originalGroupService;
    RTP.actionManager = null;
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = null;
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.cleanUp();
  }

  // =========================================================================
  // RTP-5: Group placement failure rollback and single-teleport guarantee
  // =========================================================================

  @Test
  @DisplayName("RTP-5: GroupPlacementDispatcher rolls back and releases reservations if any teleport fails (S-004)")
  void testGroupPlacementDispatcherRollsBackOnTeleportFailure() throws Exception {
    GroupPlacementDispatcher dispatcher = new GroupPlacementDispatcher();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");

    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    // p1 teleport succeeds, p2 teleport fails
    MockRTPPlayer player1 = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer player2 = new MockRTPPlayer(p2, "Player2", new RTPLocation(world, 0, 64, 0)) {
      @Override
      public CompletableFuture<Boolean> setLocation(RTPLocation location) {
        return CompletableFuture.completedFuture(false); // teleport fails!
      }
    };
    serverAccessor.addPlayer(player1);
    serverAccessor.addPlayer(player2);

    Region mockRegion = mock(Region.class);
    ChunkReservation ticket = mock(ChunkReservation.class);
    GenerationResult genResult = new GenerationResult(
        new io.github.dailystruggle.rtp.api.world.RTPCoords("world", 100, 64, 100),
        1,
        null,
        ticket
    );
    org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
    when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
    when(mockRegion.candidateValidator()).thenReturn((x, z) -> new io.github.dailystruggle.rtp.common.selection.region.RTPLocation(new RTPCoords("world", x, 64, z), 1L));

    RTP.selectionAPI.permRegionLookup.put("fail_teleport_region", mockRegion);

    try {
      GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
      GroupPlacementRequest request = GroupPlacementRequest.of(
          "fail_teleport_region", spec, List.of(p1, p2));

      CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
      GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);

      assertNotNull(result);
      assertFalse(result.isSuccess(), "Placement must report failure when a participant teleport fails");
      assertEquals(GroupPlacementResult.Reason.ERROR, result.reason());
      verify(ticket).close();
    } finally {
      RTP.selectionAPI.permRegionLookup.remove("fail_teleport_region");
    }
  }

  @Test
  @DisplayName("RTP-5: ActionManager.executeLivePlacement executes teleport exactly once per player (no duplicate teleports)")
  void testLivePlacementSingleTeleportNoDuplicate() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();

    AtomicInteger teleportCalls = new AtomicInteger(0);
    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0)) {
      @Override
      public CompletableFuture<Boolean> setLocation(RTPLocation location) {
        teleportCalls.incrementAndGet();
        return super.setLocation(location);
      }
    };
    serverAccessor.addPlayer(alice);

    AtomicBoolean ticketClosed = new AtomicBoolean(false);
    ChunkSet chunkSet = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(true));
    ChunkReservation reservation = new ChunkReservation(chunkSet, world) {
      @Override
      public void close() {
        ticketClosed.set(true);
        super.close();
      }
    };
    RTPLocation targetLoc = new RTPLocation(world, 500, 72, -300);
    targetLoc.setReservation(reservation);

    // Mock GroupPlacementService to simulate real GroupPlacementDispatcher behavior (dispatching teleport once)
    RTP.groupPlacementService = request -> {
      return alice.setLocation(targetLoc).thenApply(ok -> {
        reservation.close();
        return GroupPlacementResult.success(Map.of(aliceId, targetLoc));
      });
    };

    ActionDefinition.PlacementSpec pSpec = new ActionDefinition.PlacementSpec(
        true, "default", "SQUARE", 64, 24, 256, Map.of(), 1, 0);

    ActionDefinition def = new ActionDefinition(
        "single_teleport_action", "single_teleport_action", "rtp.live", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of());
    actionManager.registerAction(def);

    ActionSessionResult res = actionManager.trigger("single_teleport_action", List.of(aliceId), ActionContext.EMPTY).join();
    assertTrue(res.success());

    // Alice must be teleported exactly once
    assertEquals(1, teleportCalls.get(), "Participant must be teleported exactly once, not twice");
    assertTrue(ticketClosed.get(), "Chunk reservation must be closed");

    actionManager.disarm(res.sessionId());
  }

  @Test
  @DisplayName("RTP-5: Player quit hook removes queued entries and disarms active sessions")
  void testPlayerQuitRemovesQueuedEntriesAndActiveSession() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "quit_action", "quit_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    // 1. Enqueue p1 into wait queue
    CompletableFuture<ActionSessionResult> f1 = actionManager.trigger("quit_action", List.of(p1), ActionContext.EMPTY);
    assertFalse(f1.isDone(), "Must be waiting in queue");
    assertTrue(actionManager.isQueued("quit_action", p1));

    // Player quits while waiting
    actionManager.handlePlayerQuit(p1);
    assertTrue(f1.isDone(), "Future must be completed on disconnect");
    assertFalse(f1.join().success());
    assertFalse(actionManager.isQueued("quit_action", p1), "Player must no longer be in queue");

    // 2. Start active session with p1 and p2
    ActionDefinition directDef = new ActionDefinition(
        "direct_action", "direct_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of());
    actionManager.registerAction(directDef);

    ActionSessionResult activeRes = actionManager.trigger("direct_action", List.of(p1, p2), ActionContext.EMPTY).join();
    assertTrue(activeRes.success());
    assertTrue(actionManager.getSessionForParticipant(p1).isPresent());
    assertTrue(actionManager.getSessionForParticipant(p2).isPresent());

    // p1 disconnects during active session
    actionManager.handlePlayerQuit(p1);

    // Session must be disarmed and neither participant mapped
    assertFalse(actionManager.getSessionForParticipant(p1).isPresent(), "Disconnected player must not be in session");
    assertFalse(actionManager.getSession(activeRes.sessionId()).isPresent(), "Session must be terminated");
  }

  // =========================================================================
  // RTP-6: Mutual challenge reciprocity, permissions, and message routing
  // =========================================================================

  @Test
  @DisplayName("RTP-6: Duel challenge reciprocity strictly pairs only reciprocal invitations")
  void testDuelReciprocityStrictMatching() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID carol = UUID.randomUUID();
    UUID dave = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "chal_action", "chal_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    // 1. Alice challenges Bob
    ActionContext aliceCtx = new ActionContext(Map.of("sender_name", "Alice", "target_name", "Bob", "target_uuid", bob));
    CompletableFuture<ActionSessionResult> fAlice = actionManager.trigger("chal_action", List.of(alice), aliceCtx);
    assertFalse(fAlice.isDone());

    // 2. Carol challenges Dave
    ActionContext carolCtx = new ActionContext(Map.of("sender_name", "Carol", "target_name", "Dave", "target_uuid", dave));
    CompletableFuture<ActionSessionResult> fCarol = actionManager.trigger("chal_action", List.of(carol), carolCtx);

    // Alice and Carol must NOT be paired with each other!
    assertFalse(fAlice.isDone(), "Alice must not be paired with Carol");
    assertFalse(fCarol.isDone(), "Carol must not be paired with Alice");

    // 3. Bob challenges Alice (reciprocal response!)
    ActionContext bobCtx = new ActionContext(Map.of("sender_name", "Bob", "target_name", "Alice", "target_uuid", alice));
    CompletableFuture<ActionSessionResult> fBob = actionManager.trigger("chal_action", List.of(bob), bobCtx);

    // Alice and Bob must now match and start
    assertTrue(fAlice.isDone(), "Alice must be matched with Bob");
    assertTrue(fBob.isDone(), "Bob must be matched with Alice");
    assertEquals(fAlice.join().sessionId(), fBob.join().sessionId(), "Alice and Bob must share the session");

    // Carol must remain in the queue waiting for Dave!
    assertFalse(fCarol.isDone(), "Carol must still be waiting for Dave");

    actionManager.disarm(fAlice.join().sessionId());
  }

  @Test
  @DisplayName("RTP-6: Targeted challenge is never paired with an open challenge")
  void testTargetedChallengeNeverPairsWithOpenChallenge() {
    UUID alice = UUID.randomUUID();
    UUID openPlayer = UUID.randomUUID();
    UUID openPlayer2 = UUID.randomUUID();

    ActionDefinition def = new ActionDefinition(
        "open_chal_action", "open_chal_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    // Alice challenges Bob (targeted)
    ActionContext aliceCtx = new ActionContext(Map.of("sender_name", "Alice", "target_name", "Bob"));
    CompletableFuture<ActionSessionResult> fAlice = actionManager.trigger("open_chal_action", List.of(alice), aliceCtx);
    assertFalse(fAlice.isDone());

    // OpenPlayer1 enters with open matchmaking (target: "any")
    ActionContext openCtx1 = new ActionContext(Map.of("sender_name", "Open1", "target", "any"));
    CompletableFuture<ActionSessionResult> fOpen1 = actionManager.trigger("open_chal_action", List.of(openPlayer), openCtx1);

    // Alice must NOT be paired with OpenPlayer1
    assertFalse(fAlice.isDone(), "Targeted challenger must not pair with open challenger");
    assertFalse(fOpen1.isDone(), "Open challenger must not pair with targeted challenger");

    // OpenPlayer2 enters with open matchmaking
    ActionContext openCtx2 = new ActionContext(Map.of("sender_name", "Open2"));
    CompletableFuture<ActionSessionResult> fOpen2 = actionManager.trigger("open_chal_action", List.of(openPlayer2), openCtx2);

    // OpenPlayer1 and OpenPlayer2 must pair with each other!
    assertTrue(fOpen1.isDone(), "Open challengers must pair together");
    assertTrue(fOpen2.isDone(), "Open challengers must pair together");
    assertEquals(fOpen1.join().sessionId(), fOpen2.join().sessionId());

    // Alice still waiting for Bob!
    assertFalse(fAlice.isDone(), "Alice must still be waiting for Bob");

    actionManager.disarm(fOpen1.join().sessionId());
  }

  @Test
  @DisplayName("RTP-6: ActionCommand allows non-op with target permission to supply target player without rtp.other")
  void testActionCommandPlayerParameterPermissions() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID challengerId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();

    MockRTPPlayer challenger = new MockRTPPlayer(challengerId, "Challenger", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer opponent = new MockRTPPlayer(targetId, "Opponent", new RTPLocation(world, 10, 64, 10));

    // Challenger has action permission and specific target permission, but NO rtp.other and NO op!
    challenger.setPermission("rtp.command.challenge", true);
    challenger.setPermission("rtp.command.challenge.target", true);
    challenger.setPermission("rtp.other", false);
    challenger.setPermission("rtp.*", false);

    serverAccessor.addPlayer(challenger);
    serverAccessor.addPlayer(opponent);

    ParameterSpec playerParam = new ParameterSpec(
        "player", ParameterType.PLAYER, false, "rtp.command.challenge.target", "any");
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "challenge", "rtp.command.challenge", "Challenge opponent", List.of(), List.of(playerParam));

    ActionDefinition def = new ActionDefinition(
        "challenge", "challenge", "rtp.action.challenge", "Duel challenge",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    ActionCommand cmd = new ActionCommand(def);

    // Challenger issues /challenge Opponent
    boolean handled = cmd.onCommand(challengerId, Map.of("player", List.of("Opponent")), null);
    assertTrue(handled);

    // Must NOT have received "noPerms"
    assertFalse(challenger.sentMessages.stream().anyMatch(m -> m.contains(PlayerMessages.noPerms.name())),
        "Non-op challenger with target permission must be allowed to name target player without rtp.other");

    // Opponent now queued as target in wait queue
    assertTrue(actionManager.isQueued("challenge", challengerId));
  }

  @Test
  @DisplayName("RTP-6: MESSAGE_TARGET routes to target while MESSAGE routes to sender on enqueue")
  void testMessageTargetRoutesExclusivelyToOpponent() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();

    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", new RTPLocation(world, 10, 64, 10));
    serverAccessor.addPlayer(alice);
    serverAccessor.addPlayer(bob);

    ActionDefinition.LifecycleSpec life = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(
            Collections.emptyMap(),
            List.of(
                ActionDefinition.CommandAction.message("Challenge sent to [target_name]!"),
                ActionDefinition.CommandAction.messageTarget("[sender_name] has challenged you!"))))
    );

    ActionDefinition def = new ActionDefinition(
        "msg_target_action", "msg_target_action", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        life,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    ActionContext ctx = new ActionContext(Map.of(
        "player_name", "Alice",
        "sender_name", "Alice",
        "target_name", "Bob",
        "target_uuid", bobId));

    CompletableFuture<ActionSessionResult> f = actionManager.trigger("msg_target_action", List.of(aliceId), ctx);
    assertFalse(f.isDone());

    // Verify Alice received the sender message
    assertTrue(alice.sentMessages.stream().anyMatch(m -> m.contains("Challenge sent to Bob!")),
        "Alice must receive the sender confirmation");
    assertFalse(alice.sentMessages.stream().anyMatch(m -> m.contains("Alice has challenged you!")),
        "Alice must NOT receive the target challenge prompt");

    // Verify Bob received the target message
    assertTrue(bob.sentMessages.stream().anyMatch(m -> m.contains("Alice has challenged you!")),
        "Bob must receive the target invitation prompt");
    assertFalse(bob.sentMessages.stream().anyMatch(m -> m.contains("Challenge sent to Bob!")),
        "Bob must NOT receive the sender confirmation");
  }
}
