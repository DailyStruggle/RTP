package io.github.dailystruggle.rtp.common.trigger;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
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

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
}
