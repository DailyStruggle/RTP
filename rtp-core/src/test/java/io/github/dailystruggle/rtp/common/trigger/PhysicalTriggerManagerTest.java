package io.github.dailystruggle.rtp.common.trigger;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalTriggerManagerTest {

  private MockRTPServerAccessor serverAccessor;
  private PhysicalTriggerManager triggerManager;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    serverAccessor = RTPTestSetup.install(tempDir.toFile());
    triggerManager = new PhysicalTriggerManager();
    triggerManager.start();
  }

  @AfterEach
  void tearDown() {
    triggerManager.close();
  }

  @Test
  @DisplayName("Physical trigger registration, lookup, and unregistration")
  void testTriggerRegistrationAndLookup() {
    PhysicalTriggerSpec spec = new PhysicalTriggerSpec(
        "portal_1", PhysicalTriggerSpec.TriggerType.PORTAL,
        "world", 10, 64, 20, 12, 66, 20, "arena", 5L);

    triggerManager.registerTrigger(spec);

    assertEquals(1, triggerManager.getTriggers().size());
    assertSame(spec, triggerManager.getTrigger("portal_1"));
    assertSame(spec, triggerManager.getTrigger("PORTAL_1")); // Case-insensitive lookup

    assertTrue(triggerManager.unregisterTrigger("portal_1"));
    assertNull(triggerManager.getTrigger("portal_1"));
    assertEquals(0, triggerManager.getTriggers().size());
  }

  @Test
  @DisplayName("Player entering physical trigger volume triggers target action with cooldown")
  void testPlayerEnteringTriggerVolume() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID playerId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(playerId, "Tester", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    AtomicInteger actionTriggers = new AtomicInteger(0);

    // Register a mock action service that records triggers
    RTPAPI.actionService = new io.github.dailystruggle.rtp.api.action.ActionService() {
      @Override
      public java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.action.ActionSessionResult> trigger(
          String actionId, List<UUID> participants, ActionContext context) {
        if ("arena".equalsIgnoreCase(actionId) && participants.contains(playerId)) {
          actionTriggers.incrementAndGet();
        }
        return java.util.concurrent.CompletableFuture.completedFuture(
            io.github.dailystruggle.rtp.api.action.ActionSessionResult.success(UUID.randomUUID()));
      }

      @Override
      public java.util.Optional<io.github.dailystruggle.rtp.api.action.ActionSession> getSession(UUID sessionId) {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<io.github.dailystruggle.rtp.api.action.ActionSession> getSessionForParticipant(UUID participantId) {
        return java.util.Optional.empty();
      }

      @Override
      public void disarm(UUID sessionId) {
      }

      @Override
      public void registerPredicate(String name, java.util.function.Predicate<io.github.dailystruggle.rtp.api.action.ActionGateContext> predicate) {
      }

      @Override
      public java.util.Set<String> getActionIds() {
        return java.util.Set.of("arena");
      }
    };

    PhysicalTriggerSpec portal = new PhysicalTriggerSpec(
        "portal_test", PhysicalTriggerSpec.TriggerType.PORTAL,
        "world", 10, 64, 10, 12, 66, 12, "arena", 10L); // 10s cooldown
    triggerManager.registerTrigger(portal);

    // Move outside portal volume -> No trigger
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(playerId, "world", 0, 64, 0, 5, 64, 5));
    assertEquals(0, actionTriggers.get());

    // Step into portal volume (11, 65, 11) -> Action triggers!
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(playerId, "world", 5, 64, 5, 11, 65, 11));
    assertEquals(1, actionTriggers.get());

    // Step within portal volume again immediately -> Blocked by 10s cooldown!
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(playerId, "world", 11, 65, 11, 12, 65, 11));
    assertEquals(1, actionTriggers.get());

    // Different world -> No trigger
    UUID otherPlayer = UUID.randomUUID();
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(otherPlayer, "nether", 0, 64, 0, 11, 65, 11));
    assertEquals(1, actionTriggers.get());
  }

  @Test
  @DisplayName("Batch trigger accumulates occupants and dispatches wave on interval expiry")
  void testBatchTriggerWaveAccumulation() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    MockRTPPlayer player1 = new MockRTPPlayer(p1, "PlayerOne", new RTPLocation(world, 10, 64, 10));
    MockRTPPlayer player2 = new MockRTPPlayer(p2, "PlayerTwo", new RTPLocation(world, 11, 65, 11));
    serverAccessor.addPlayer(player1);
    serverAccessor.addPlayer(player2);

    java.util.concurrent.atomic.AtomicReference<List<UUID>> dispatchedGroup = new java.util.concurrent.atomic.AtomicReference<>();

    RTPAPI.actionService = new io.github.dailystruggle.rtp.api.action.ActionService() {
      @Override
      public java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.action.ActionSessionResult> trigger(
          String actionId, List<UUID> participants, ActionContext context) {
        if ("wave_action".equalsIgnoreCase(actionId)) {
          dispatchedGroup.set(participants);
        }
        return java.util.concurrent.CompletableFuture.completedFuture(
            io.github.dailystruggle.rtp.api.action.ActionSessionResult.success(UUID.randomUUID()));
      }

      @Override
      public java.util.Optional<io.github.dailystruggle.rtp.api.action.ActionSession> getSession(UUID sessionId) {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<io.github.dailystruggle.rtp.api.action.ActionSession> getSessionForParticipant(UUID participantId) {
        return java.util.Optional.empty();
      }

      @Override
      public void disarm(UUID sessionId) {}

      @Override
      public void registerPredicate(String name, java.util.function.Predicate<io.github.dailystruggle.rtp.api.action.ActionGateContext> predicate) {}

      @Override
      public java.util.Set<String> getActionIds() {
        return java.util.Set.of("wave_action");
      }
    };

    // Trigger with 3-second batch interval
    PhysicalTriggerSpec batchTrigger = new PhysicalTriggerSpec(
        "lobby_zone", PhysicalTriggerSpec.TriggerType.STEP_IN,
        "world", 10, 64, 10, 12, 66, 12, "wave_action", 5L, 3L);
    triggerManager.registerTrigger(batchTrigger);

    assertEquals(3L, triggerManager.getWaveRemainingSeconds("lobby_zone"));
    assertEquals(0, triggerManager.getOccupants("lobby_zone").size());

    // Player 1 steps into trigger zone
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(p1, "world", 0, 64, 0, 10, 64, 10));
    assertEquals(1, triggerManager.getOccupants("lobby_zone").size());
    assertTrue(triggerManager.getOccupants("lobby_zone").contains(p1));
    assertNull(dispatchedGroup.get(), "Batch trigger should not dispatch immediately");

    // Player 2 steps into trigger zone
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(p2, "world", 0, 64, 0, 11, 65, 11));
    assertEquals(2, triggerManager.getOccupants("lobby_zone").size());
    assertTrue(triggerManager.getOccupants("lobby_zone").contains(p2));

    // Tick 1 (remaining: 2)
    triggerManager.tickWaveAccumulator();
    assertEquals(2L, triggerManager.getWaveRemainingSeconds("lobby_zone"));
    assertNull(dispatchedGroup.get());

    // Tick 2 (remaining: 1)
    triggerManager.tickWaveAccumulator();
    assertEquals(1L, triggerManager.getWaveRemainingSeconds("lobby_zone"));
    assertNull(dispatchedGroup.get());

    // Tick 3 (interval expired: dispatch wave!)
    triggerManager.tickWaveAccumulator();
    assertNotNull(dispatchedGroup.get(), "Wave should dispatch when countdown reaches zero");
    assertEquals(2, dispatchedGroup.get().size());
    assertTrue(dispatchedGroup.get().contains(p1));
    assertTrue(dispatchedGroup.get().contains(p2));
    assertEquals(0, triggerManager.getOccupants("lobby_zone").size());
    assertEquals(3L, triggerManager.getWaveRemainingSeconds("lobby_zone"));
  }

  @Test
  @DisplayName("Batch trigger wave dispatch integrates with ActionManager and handles result ingestion")
  void testWaveDispatchActionManagerResultIngestion() {
    ActionManager actionManager = new ActionManager();
    RTP.actionManager = actionManager;

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    MockRTPPlayer player1 = new MockRTPPlayer(p1, "PlayerOne", new RTPLocation(world, 10, 64, 10));
    MockRTPPlayer player2 = new MockRTPPlayer(p2, "PlayerTwo", new RTPLocation(world, 11, 65, 11));
    serverAccessor.addPlayer(player1);
    serverAccessor.addPlayer(player2);

    io.github.dailystruggle.rtp.api.action.ActionDefinition def =
        new io.github.dailystruggle.rtp.api.action.ActionDefinition(
            "wave_real_action", "wave_real_action", "rtp.action.wave_real_action", "Wave Action",
            io.github.dailystruggle.rtp.api.action.ActionDefinition.PlacementSpec.DEFAULT,
            io.github.dailystruggle.rtp.api.action.ActionDefinition.ConfinementSpec.DEFAULT,
            io.github.dailystruggle.rtp.api.action.ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    RTP.groupPlacementService = request -> CompletableFuture.completedFuture(
        io.github.dailystruggle.rtp.api.group.GroupPlacementResult.success(Map.of(
            p1, new RTPLocation(world, 200, 64, 200),
            p2, new RTPLocation(world, 205, 64, 200)
        )));

    PhysicalTriggerSpec trigger = new PhysicalTriggerSpec(
        "real_lobby", PhysicalTriggerSpec.TriggerType.STEP_IN,
        "world", 10, 64, 10, 12, 66, 12, "wave_real_action", 5L, 1L);
    triggerManager.registerTrigger(trigger);

    // Both players step in
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(p1, "world", 0, 64, 0, 10, 64, 10));
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(p2, "world", 0, 64, 0, 11, 65, 11));
    assertEquals(2, triggerManager.getOccupants("real_lobby").size());

    // Tick to expire countdown
    triggerManager.tickWaveAccumulator();

    // Verify session was created and both players are in session
    assertTrue(actionManager.getSessionForParticipant(p1).isPresent());
    assertTrue(actionManager.getSessionForParticipant(p2).isPresent());
    assertEquals(
        actionManager.getSessionForParticipant(p1).get().sessionId(),
        actionManager.getSessionForParticipant(p2).get().sessionId()
    );
  }

  @Test
  void testTriggerManagerEdgeBranches() {
    // null and empty lookups
    assertNull(triggerManager.getTrigger(null));
    assertFalse(triggerManager.unregisterTrigger(null));
    assertFalse(triggerManager.unregisterTrigger("nonexistent"));
    assertEquals(0L, triggerManager.getWaveRemainingSeconds(null));
    assertEquals(0L, triggerManager.getWaveRemainingSeconds("nonexistent"));
    assertTrue(triggerManager.getOccupants(null).isEmpty());
    assertTrue(triggerManager.getOccupants("nonexistent").isEmpty());

    // start called multiple times is idempotent
    triggerManager.start();
    triggerManager.start();

    // register with null spec
    assertThrows(NullPointerException.class, () -> triggerManager.registerTrigger(null));

    PhysicalTriggerSpec spec = new PhysicalTriggerSpec(
        "dummy", PhysicalTriggerSpec.TriggerType.STEP_IN,
        "world", 0, 0, 0, 1, 1, 1, "act", 0L);
    triggerManager.registerTrigger(spec);
    assertNotNull(triggerManager.getTrigger("dummy"));
    assertTrue(triggerManager.unregisterTrigger("dummy"));
    assertNull(triggerManager.getTrigger("dummy"));
  }
}
