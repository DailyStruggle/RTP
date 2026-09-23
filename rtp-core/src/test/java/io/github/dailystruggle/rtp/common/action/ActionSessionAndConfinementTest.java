package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ActionSessionAndConfinementTest {

  private MockRTPServerAccessor serverAccessor;

  @BeforeEach
  void setUp() {
    serverAccessor = new MockRTPServerAccessor(new java.io.File("."));
    RTP.serverAccessor = serverAccessor;
  }

  @Test
  @DisplayName("Action session dispatches onStart commands upon start")
  void testOnStartExecution() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.forEach(
                        List.of(ActionDefinition.CommandAction.console("tag [player] add active"))
                    )
                )
            )
        ),
        List.of(),
        List.of(),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{16, 64, 0}),
        "world", 8, 0, null, null, null);

    session.arm();
    session.triggerStart();

    // Verify console commands were dispatched
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("tag " + p1 + " add active")));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("tag " + p2 + " add active")));

    session.disarm();
  }

  @Test
  @DisplayName("Boundary breach triggers onBoundaryViolation and safe pull-back")
  void testBoundaryBreachAndPullBack() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "Player1", loc);
    serverAccessor.addPlayer(mockPlayer);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of("scoreboard", Map.of("objective", "rtp_violations", "matches", "<= 2")),
                List.of(
                    ActionDefinition.CommandAction.action("PULL_BACK"),
                    ActionDefinition.CommandAction.console("warn [violator]")
                )
            )
        ),
        List.of(),
        List.of()
    );

    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 300L, 32.0); // Leash radius 32 blocks

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        lifecycle);

    AtomicBoolean disarmed = new AtomicBoolean(false);
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, sId -> disarmed.set(true), null);

    session.arm();

    // In bounds move (x=10, z=10, dist=14.1 <= 32) -> no violation
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 10, 64, 10));
    assertEquals(0, session.getViolations(p1));

    // Out of bounds move (x=50, z=50, dist=70.7 > 32) -> violation triggers
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 10, 64, 10, 50, 64, 50));
    assertEquals(1, session.getViolations(p1));

    // Check pull-back teleport dispatched
    io.github.dailystruggle.rtp.api.entity.RTPPlayer p = serverAccessor.getPlayer(p1);
    assertNotNull(p);
    assertEquals(0, p.getLocation().x());

    // Check warning command executed
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("warn " + p1)));

    session.disarm();
    assertTrue(disarmed.get());
  }

  @Test
  @DisplayName("Session expiration triggers onExpire and disarms")
  void testExpiration() {
    UUID p1 = UUID.randomUUID();

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.console("timeout match"))
            )
        ),
        List.of()
    );

    // 0 duration triggers immediate expire on tick
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.SUBSPACE, 0L, 64.0);

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        lifecycle);

    AtomicBoolean disarmed = new AtomicBoolean(false);
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, sId -> disarmed.set(true), null);

    session.arm();
    session.tick();

    assertFalse(session.isActive());
    assertTrue(disarmed.get());
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("timeout match")));
  }
}
