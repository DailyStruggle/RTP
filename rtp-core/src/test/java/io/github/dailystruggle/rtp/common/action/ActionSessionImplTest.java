package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionSessionImplTest {

  private MockRTPServerAccessor accessor;
  private MockRTPWorld world;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    RTPTestSetup.install(tempDir.toFile());
    accessor = (MockRTPServerAccessor) RTP.serverAccessor;
    world = (MockRTPWorld) accessor.getRTPWorld("world");
    ActionSessionImpl.resetScoreboardObjectivesInitializedForTesting();
  }

  private ActionDefinition createTestDefinition(
      ConfinementBoundary boundary,
      String shapeName,
      int radius,
      int centerRadius,
      double initialSize,
      double shrinkTo,
      long shrinkOver,
      long durationSeconds,
      double damageAmount,
      double damageBuffer,
      long damageInterval,
      double maxDistanceOutside,
      List<ActionDefinition.CommandAction> outsideActions,
      List<ActionDefinition.LifecycleStep> onStart,
      List<ActionDefinition.LifecycleStep> onBoundaryViolation,
      List<ActionDefinition.LifecycleStep> onExpire,
      List<ActionDefinition.LifecycleStep> onCancel,
      List<ActionDefinition.LifecycleStep> onDeath) {

    ActionDefinition.PlacementSpec placement = new ActionDefinition.PlacementSpec(
        true, "default", "CIRCLE", 100, 0, 16, 8, Map.of(), 3, 0
    );

    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        boundary, durationSeconds, 50.0, initialSize, shrinkTo, shrinkOver, true,
        shapeName, radius, centerRadius, damageAmount, damageBuffer, damageInterval,
        maxDistanceOutside, outsideActions
    );

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        onStart != null ? onStart : List.of(),
        onBoundaryViolation != null ? onBoundaryViolation : List.of(),
        onExpire != null ? onExpire : List.of(),
        onDeath != null ? onDeath : List.of(),
        List.of(),
        onCancel != null ? onCancel : List.of()
    );

    return new ActionDefinition(
        "test_session_action", "Test Session Action", "test.perm", "desc",
        placement, confinement, lifecycle
    );
  }

  @Test
  void testSessionLifecycleAndMethods() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    List<UUID> participants = List.of(p1, p2);
    MockRTPPlayer player1 = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer player2 = new MockRTPPlayer(p2, "Player2", new RTPLocation(world, 10, 64, 10));
    accessor.addPlayer(player1);
    accessor.addPlayer(player2);

    ActionDefinition.CommandAction startCmd = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.CONSOLE, "say session [session_id] started for [players]", List.of()
    );
    ActionDefinition.CommandAction startMsg = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.MESSAGE, "Welcome to the action [player_name]!", List.of()
    );
    ActionDefinition.LifecycleStep stepStart = new ActionDefinition.LifecycleStep(
        Map.of(), List.of(startCmd, startMsg), 0L
    );

    ActionDefinition def = createTestDefinition(
        ConfinementBoundary.SUBSPACE, "SQUARE", 50, 0, 100.0, 50.0, 60L, 30L,
        2.0, 5.0, 1L, 20.0, List.of(),
        List.of(stepStart), List.of(), List.of(), List.of(), List.of()
    );

    Map<UUID, int[]> slots = Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{10, 64, 10});
    ActionContext context = new ActionContext(Map.of("custom_key", "custom_val"));

    AtomicBoolean disarmed = new AtomicBoolean(false);
    UUID sessionId = UUID.randomUUID();

    ActionSessionImpl session = new ActionSessionImpl(
        sessionId, def, participants, context, slots, "world", 0, 0, null,
        id -> disarmed.set(true), Map.of()
    );

    assertEquals(sessionId, session.sessionId());
    assertEquals("test_session_action", session.actionId());
    assertEquals(context, session.context());
    assertEquals(participants, session.participants());
    assertTrue(session.isActive());
    assertEquals(0, session.getViolations(p1));
    assertEquals(0, session.getViolations(UUID.randomUUID()));
    assertEquals(50.0, session.initialBoundaryRadius());
    assertEquals(50.0, session.currentBoundaryRadius());
    assertTrue(session.remainingSeconds() > 0);

    // Arm and trigger start
    session.arm();
    session.triggerStart();

    // Verify tick execution
    session.tick();

    // Trigger boundary violation
    session.triggerBoundaryViolation(p1, 100, 100, 1);
    assertEquals(1, session.getViolations(p1));

    // Pullback
    session.pullBack(p1);

    // Trigger death
    session.triggerDeath(p1, p2);
    assertFalse(session.isActive());
    assertTrue(disarmed.get());
  }

  @Test
  void testConfinementBoundariesAndMovements() {
    MockRTPPlayer player = new MockRTPPlayer();
    UUID pid = player.uuid();
    accessor.addPlayer(player);
    player.setLocation(new RTPLocation(world, 0, 64, 0));

    // Test SHAPE CIRCLE
    ActionDefinition defCircle = createTestDefinition(
        ConfinementBoundary.SHAPE, "CIRCLE", 50, 10, 0.0, 0.0, 0L, 100L,
        1.0, 2.0, 1L, 50.0, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of()
    );
    ActionContext ctx = ActionContext.empty();
    ActionSessionImpl sessionCircle = new ActionSessionImpl(
        UUID.randomUUID(), defCircle, List.of(pid), ctx, Map.of(pid, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, Map.of()
    );
    sessionCircle.arm();

    // Fire PlayerMoveEvent in-bounds
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(pid, "world", 0, 64, 0, 20, 64, 0)
    );
    assertEquals(0, sessionCircle.getViolations(pid));

    // Fire PlayerMoveEvent centerRadius breach (inner donut)
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(pid, "world", 20, 64, 0, 5, 64, 0)
    );
    assertEquals(1, sessionCircle.getViolations(pid));

    // Fire PlayerMoveEvent outside outer radius
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(pid, "world", 5, 64, 0, 60, 64, 0)
    );
    assertEquals(2, sessionCircle.getViolations(pid));

    // Fire PlayerMoveEvent different world
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(pid, "other_world", 60, 64, 0, 10, 64, 10)
    );
    sessionCircle.disarm();

    // Test LEASH
    ActionDefinition defLeash = createTestDefinition(
        ConfinementBoundary.LEASH, null, 30, 0, 0.0, 0.0, 0L, 100L,
        0.0, 0.0, 1L, 0.0, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of()
    );
    ActionSessionImpl sessionLeash = new ActionSessionImpl(
        UUID.randomUUID(), defLeash, List.of(pid), ctx, Map.of(pid, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, Map.of()
    );
    sessionLeash.arm();
    sessionLeash.triggerStart();
    sessionLeash.disarm();

    // Test REGION boundary with ParentRegion
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("regionShape", 32);
    shape.set(GenericMemoryShapeParams.radius, 100L);
    RegionSettings settings = new RegionSettings(
        "test_reg", world, shape, new LinearAdjustor(new ArrayList<>()),
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region parentReg = new Region("test_reg", settings);

    ActionDefinition defRegion = createTestDefinition(
        ConfinementBoundary.REGION, null, 100, 0, 0.0, 0.0, 0L, 100L,
        0.0, 0.0, 1L, 0.0, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of()
    );
    ActionSessionImpl sessionRegion = new ActionSessionImpl(
        UUID.randomUUID(), defRegion, List.of(pid), ctx, Map.of(pid, new int[]{0, 64, 0}),
        "world", 0, 0, parentReg, null, Map.of()
    );
    sessionRegion.arm();
    sessionRegion.triggerStart();
    sessionRegion.disarm();
  }

  @Test
  void testActionCommandsExecutionAndTokens() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "Alice", new RTPLocation(world, 0, 64, 0));
    UUID pid = player.uuid();
    accessor.addPlayer(player);

    ActionDefinition.CommandAction cmdPlayer = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.PLAYER, "tell [player] hello from player!", List.of()
    );
    ActionDefinition.CommandAction cmdMsgDirect = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.PLAYER, "msg &aDirect formatted message", List.of()
    );
    ActionDefinition.CommandAction cmdPullback = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.ACTION, "PULL_BACK", List.of()
    );
    ActionDefinition.CommandAction cmdDisarm = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.ACTION, "DISARM", List.of()
    );
    ActionDefinition.CommandAction cmdUnknown = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.ACTION, "UNKNOWN_ACTION", List.of()
    );
    ActionDefinition.CommandAction cmdForEach = new ActionDefinition.CommandAction(
        ActionDefinition.ActionType.FOR_EACH, "", List.of(
            new ActionDefinition.CommandAction(ActionDefinition.ActionType.MESSAGE, "forEach msg", List.of())
        )
    );

    ActionDefinition.LifecycleStep stepViolation = new ActionDefinition.LifecycleStep(
        Map.of(), List.of(cmdPlayer, cmdMsgDirect, cmdPullback, cmdUnknown, cmdForEach), 0L
    );
    ActionDefinition.LifecycleStep stepExpire = new ActionDefinition.LifecycleStep(
        Map.of(), List.of(cmdDisarm), 0L
    );

    ActionDefinition def = createTestDefinition(
        ConfinementBoundary.SUBSPACE, "SQUARE", 50, 0, 0.0, 0.0, 0L, 100L,
        0.0, 0.0, 1L, 0.0, List.of(cmdPullback),
        List.of(), List.of(stepViolation), List.of(stepExpire), List.of(), List.of()
    );

    ActionContext ctx = ActionContext.empty();
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(pid), ctx, Map.of(pid, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, Map.of()
    );

    session.arm();
    session.triggerBoundaryViolation(pid, 200, 200, 1);
    session.triggerCancel(pid);
    assertFalse(session.isActive());
  }

  @Test
  void testExpirationAndContinuousConfinementDamage() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "Bob", new RTPLocation(world, 100, 64, 100));
    UUID pid = player.uuid();
    accessor.addPlayer(player);

    ActionDefinition def = createTestDefinition(
        ConfinementBoundary.SUBSPACE, "SQUARE", 20, 0, 0.0, 0.0, 0L, 0L, // 0 duration
        5.0, 2.0, 0L, 50.0, List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of()
    );

    ActionContext ctx = ActionContext.empty();
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(pid), ctx, Map.of(pid, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, Map.of()
    );

    session.arm();
    session.triggerStart();
    session.tick();
    assertFalse(session.isActive());
  }
}
