package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.CommandAction;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.LifecycleStep;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
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
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ActionSessionImpl branch coverage (ADR-093, ADR-075, REQ-RTP-S-004)")
class ActionSessionImplCoverageTest {

  private static final ActionDefinition.PlacementSpec PLACEMENT =
      new ActionDefinition.PlacementSpec(true, "default", "CIRCLE", 40, 6, 16, 8, Map.of(), 3, 0);
  private static final ActionDefinition.PlacementSpec PLACEMENT_NO_CENTER =
      new ActionDefinition.PlacementSpec(true, "default", "CIRCLE", 40, 0, 16, 8, Map.of(), 3, 0);

  private MockRTPServerAccessor accessor;
  private MockRTPWorld world;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    RTPTestSetup.install(tempDir.toFile());
    accessor = (MockRTPServerAccessor) RTP.serverAccessor;
    world = (MockRTPWorld) accessor.getRTPWorld("world");
    ActionSessionImpl.resetScoreboardObjectivesInitializedForTesting();
    MemoryTracker.reset();
  }

  @AfterEach
  void tearDown() {
    MemoryTracker.reset();
    ActionSessionImpl.resetScoreboardObjectivesInitializedForTesting();
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static ActionDefinition.ConfinementSpec conf(
      ConfinementBoundary boundary, long duration, double leash, double initialSize, double shrinkTo,
      long shrinkOver, String shape, int radius, int centerRadius, double damage, double buffer,
      long interval, double maxOutside, List<CommandAction> outside) {
    return new ActionDefinition.ConfinementSpec(
        boundary, duration, leash, initialSize, shrinkTo, shrinkOver, true, shape, radius, centerRadius,
        damage, buffer, interval, maxOutside, outside);
  }

  private static ActionDefinition.ConfinementSpec simple(ConfinementBoundary boundary, String shape, int radius,
      int centerRadius) {
    return conf(boundary, 100L, 30.0, 0.0, 0.0, 0L, shape, radius, centerRadius, 0.0, 0.0, 1L, 0.0, List.of());
  }

  private static ActionDefinition.LifecycleSpec lifecycle(
      List<LifecycleStep> onStart, List<LifecycleStep> onViolation, List<LifecycleStep> onExpire,
      List<LifecycleStep> onDeath, List<LifecycleStep> onCancel) {
    return new ActionDefinition.LifecycleSpec(onStart, onViolation, onExpire, onDeath, List.of(), onCancel);
  }

  private static ActionDefinition def(ActionDefinition.PlacementSpec placement,
      ActionDefinition.ConfinementSpec confinement, ActionDefinition.LifecycleSpec lifecycle) {
    return new ActionDefinition("cov_action", "Coverage Action", "cov.perm", "desc", placement, confinement,
        lifecycle);
  }

  private static ActionDefinition def(ActionDefinition.ConfinementSpec confinement) {
    return def(PLACEMENT, confinement, ActionDefinition.LifecycleSpec.EMPTY);
  }

  private static LifecycleStep step(CommandAction... actions) {
    return new LifecycleStep(Map.of(), List.of(actions), 0L);
  }

  private static LifecycleStep gated(Map<String, Object> gate, long delay, CommandAction... actions) {
    return new LifecycleStep(gate, List.of(actions), delay);
  }

  private static ActionSessionImpl session(ActionDefinition def, List<UUID> participants, Map<UUID, int[]> slots,
      String worldName) {
    return new ActionSessionImpl(UUID.randomUUID(), def, participants, ActionContext.empty(), slots, worldName,
        0, 0, null, null, Map.of());
  }

  private MockRTPPlayer player(String name, int x, int z) {
    MockRTPPlayer p = new MockRTPPlayer(UUID.randomUUID(), name, new RTPLocation(world, x, 64, z));
    accessor.addPlayer(p);
    return p;
  }

  private List<String> commandsStartingWith(String prefix) {
    List<String> out = new ArrayList<>();
    for (String c : accessor.getExecutedCommands()) {
      if (c.startsWith(prefix)) out.add(c);
    }
    return out;
  }

  private boolean logged(String fragment) {
    return accessor.logMessages.stream().anyMatch(m -> m.contains(fragment));
  }

  private static final class FailingTeleportPlayer extends MockRTPPlayer {
    FailingTeleportPlayer(UUID id, String name, RTPLocation loc) {
      super(id, name, loc);
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
      return CompletableFuture.failedFuture(new IllegalStateException("teleport exploded"));
    }
  }

  private static final class ThrowingOnlinePlayer extends MockRTPPlayer {
    ThrowingOnlinePlayer(UUID id, String name, RTPLocation loc) {
      super(id, name, loc);
    }

    @Override
    public boolean isOnline() {
      throw new IllegalStateException("online probe failed");
    }
  }

  // ---------------------------------------------------------------------------
  // Radius math
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("initial/current boundary radius resolve per boundary type and sizing overrides")
  void boundaryRadiusResolution() {
    UUID p = UUID.randomUUID();
    List<UUID> ps = List.of(p);
    Map<UUID, int[]> slots = Map.of();

    assertEquals(40.0, session(def(simple(ConfinementBoundary.SHAPE, "SQUARE", 0, 0)), ps, slots, "world")
        .initialBoundaryRadius());
    assertEquals(25.0, session(def(simple(ConfinementBoundary.SHAPE, "SQUARE", 25, 0)), ps, slots, "world")
        .initialBoundaryRadius());
    assertEquals(30.0, session(def(simple(ConfinementBoundary.LEASH, null, 0, 0)), ps, slots, "world")
        .initialBoundaryRadius());
    assertEquals(40.0, session(def(simple(ConfinementBoundary.SUBSPACE, null, 0, 0)), ps, slots, "world")
        .initialBoundaryRadius());
    assertEquals(12.0, session(def(simple(ConfinementBoundary.SUBSPACE, null, 12, 0)), ps, slots, "world")
        .initialBoundaryRadius());

    ActionSessionImpl sized = session(def(conf(ConfinementBoundary.SUBSPACE, 100L, 30.0, 80.0, 20.0, 600L,
        "SQUARE", 12, 0, 0.0, 0.0, 1L, 0.0, List.of())), ps, slots, "world");
    assertEquals(40.0, sized.initialBoundaryRadius());
    // elapsed == 0 -> no shrink progress yet
    assertEquals(40.0, sized.currentBoundaryRadius());

    ActionSessionImpl noShrinkTime = session(def(conf(ConfinementBoundary.SUBSPACE, 100L, 30.0, 80.0, 20.0, 0L,
        "SQUARE", 12, 0, 0.0, 0.0, 1L, 0.0, List.of())), ps, slots, "world");
    assertEquals(40.0, noShrinkTime.currentBoundaryRadius());

    ActionSessionImpl sameSize = session(def(conf(ConfinementBoundary.SUBSPACE, 100L, 30.0, 80.0, 80.0, 600L,
        "SQUARE", 12, 0, 0.0, 0.0, 1L, 0.0, List.of())), ps, slots, "world");
    assertEquals(40.0, sameSize.currentBoundaryRadius());

    ActionSessionImpl unbounded = session(def(conf(ConfinementBoundary.SUBSPACE, -1L, 30.0, 0.0, 0.0, 0L,
        "SQUARE", 12, 0, 0.0, 0.0, 1L, 0.0, List.of())), ps, slots, "world");
    assertEquals(-1L, unbounded.remainingSeconds());
    assertTrue(unbounded.elapsedSeconds() >= 0L);
  }

  // ---------------------------------------------------------------------------
  // Breach classification
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("checkInBounds classifies world, region, subspace, leash and shape breaches")
  void breachClassification() {
    List<UUID> ps = List.of(UUID.randomUUID());
    Map<UUID, int[]> slots = Map.of();

    ActionSessionImpl sub = session(def(simple(ConfinementBoundary.SUBSPACE, null, 10, 0)), ps, slots, "world");
    assertFalse(sub.checkInBounds("other", 0, 0));
    assertTrue(sub.checkInBounds("WORLD", 0, 0));
    assertTrue(sub.checkInBounds(null, 0, 0));
    assertTrue(sub.checkInBounds("world", 10, -10));
    assertFalse(sub.checkInBounds("world", 11, 0));
    assertFalse(sub.checkInBounds("world", 0, 11));

    ActionSessionImpl noWorld = session(def(simple(ConfinementBoundary.SUBSPACE, null, 10, 0)), ps, slots, null);
    assertTrue(noWorld.checkInBounds("anything", 0, 0));

    ActionSessionImpl none = session(def(simple(ConfinementBoundary.NONE, null, 10, 0)), ps, slots, "world");
    assertTrue(none.checkInBounds("world", 9999, 9999));

    ActionSessionImpl regionNoParent =
        session(def(simple(ConfinementBoundary.REGION, null, 10, 0)), ps, slots, "world");
    assertTrue(regionNoParent.checkInBounds("world", 9999, 9999));

    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("covShape", 32);
    shape.set(GenericMemoryShapeParams.radius, 100L);
    RegionSettings settings = new RegionSettings(
        "cov_reg", world, shape, new LinearAdjustor(new ArrayList<>()),
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    Region parent = new Region("cov_reg", settings);
    ActionSessionImpl region = new ActionSessionImpl(UUID.randomUUID(),
        def(simple(ConfinementBoundary.REGION, null, 10, 0)), ps, ActionContext.empty(), slots, "world", 0, 0,
        parent, null, Map.of());
    // Square shape excludes its centerRadius (default 64): the ring 64..100 is inside.
    assertTrue(region.checkInBounds("world", 80, 0));
    assertFalse(region.checkInBounds("world", 0, 0));
    assertFalse(region.checkInBounds("world", 50_000, 50_000));

    ActionSessionImpl leash = session(def(conf(ConfinementBoundary.LEASH, 100L, 10.0, 0.0, 0.0, 0L, null, 0, 0,
        0.0, 0.0, 1L, 0.0, List.of())), ps, slots, "world");
    assertTrue(leash.checkInBounds("world", 7, 7));
    assertFalse(leash.checkInBounds("world", 8, 8));

    ActionSessionImpl circle =
        session(def(simple(ConfinementBoundary.SHAPE, "CIRCLE", 20, 5)), ps, slots, "world");
    assertFalse(circle.checkInBounds("world", 2, 0));
    assertTrue(circle.checkInBounds("world", 10, 0));
    assertFalse(circle.checkInBounds("world", 21, 0));

    // centerRadius falls back to placement centerRadius (6)
    ActionSessionImpl circlePlacementCenter =
        session(def(simple(ConfinementBoundary.SHAPE, "CIRCLE", 20, 0)), ps, slots, "world");
    assertFalse(circlePlacementCenter.checkInBounds("world", 3, 0));
    assertTrue(circlePlacementCenter.checkInBounds("world", 10, 0));

    ActionSessionImpl circleNoCenter = session(def(PLACEMENT_NO_CENTER,
        simple(ConfinementBoundary.SHAPE, "CIRCLE", 20, 0), ActionDefinition.LifecycleSpec.EMPTY), ps, slots,
        "world");
    assertTrue(circleNoCenter.checkInBounds("world", 0, 0));

    ActionSessionImpl square =
        session(def(simple(ConfinementBoundary.SHAPE, "SQUARE", 20, 5)), ps, slots, "world");
    assertFalse(square.checkInBounds("world", 3, 3));
    assertTrue(square.checkInBounds("world", 10, 0));
    assertFalse(square.checkInBounds("world", 21, 0));
    assertFalse(square.checkInBounds("world", 0, 21));

    ActionSessionImpl squareNoCenter = session(def(PLACEMENT_NO_CENTER,
        simple(ConfinementBoundary.SHAPE, "SQUARE", 20, 0), ActionDefinition.LifecycleSpec.EMPTY), ps, slots,
        "world");
    assertTrue(squareNoCenter.checkInBounds("world", 0, 0));
  }

  // ---------------------------------------------------------------------------
  // World border + scoreboards on start
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("triggerStart sends a world border sized per boundary type and initialises objectives once")
  void triggerStartWorldBorders() {
    MockRTPPlayer alice = player("BorderAlice", 0, 0);
    List<UUID> ps = List.of(alice.uuid());
    Map<UUID, int[]> slots = Map.of(alice.uuid(), new int[] {0, 70, 0});

    Object[][] cases = {
        {simple(ConfinementBoundary.SHAPE, "SQUARE", 0, 0), 80.0},
        {simple(ConfinementBoundary.SHAPE, "SQUARE", 15, 0), 30.0},
        {simple(ConfinementBoundary.SUBSPACE, null, 0, 0), 80.0},
        {simple(ConfinementBoundary.SUBSPACE, null, 12, 0), 24.0},
        {simple(ConfinementBoundary.LEASH, null, 0, 0), 60.0},
        {simple(ConfinementBoundary.REGION, null, 0, 0), 512.0},
    };
    for (Object[] c : cases) {
      int before = accessor.getSentWorldBorders().size();
      ActionSessionImpl s = session(def((ActionDefinition.ConfinementSpec) c[0]), ps, slots, "world");
      s.triggerStart();
      assertEquals(before + 1, accessor.getSentWorldBorders().size());
      MockRTPServerAccessor.SentWorldBorder sent =
          accessor.getSentWorldBorders().get(accessor.getSentWorldBorders().size() - 1);
      assertEquals((double) c[1], sent.oldSize(), 1e-9);
      assertEquals((double) c[1], sent.newSize(), 1e-9);
      s.disarm();
      assertTrue(accessor.getResetWorldBorders().contains(alice.uuid()));
    }

    ActionSessionImpl shrinking = session(def(conf(ConfinementBoundary.SUBSPACE, 100L, 30.0, 100.0, 50.0, 60L,
        "SQUARE", 12, 0, 1.0, 2.0, 1L, 0.0, List.of())), ps, slots, "world");
    shrinking.triggerStart();
    MockRTPServerAccessor.SentWorldBorder sent =
        accessor.getSentWorldBorders().get(accessor.getSentWorldBorders().size() - 1);
    assertEquals(100.0, sent.oldSize(), 1e-9);
    assertEquals(50.0, sent.newSize(), 1e-9);
    assertEquals(60L, sent.shrinkSeconds());
    shrinking.disarm();

    int before = accessor.getSentWorldBorders().size();
    session(def(simple(ConfinementBoundary.NONE, null, 0, 0)), ps, slots, "world").triggerStart();
    session(def(simple(ConfinementBoundary.SUBSPACE, null, 10, 0)), ps, slots, null).triggerStart();
    session(def(ActionDefinition.PlacementSpec.DISABLED, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        ActionDefinition.LifecycleSpec.EMPTY), ps, slots, "world").triggerStart();
    assertEquals(before, accessor.getSentWorldBorders().size());

    assertEquals(1, commandsStartingWith("scoreboard objectives add rtp_session_id").size());
  }

  @Test
  @DisplayName("triggerStart handles empty participants, missing slots, unbounded duration and offline players")
  void triggerStartEdgeCases() {
    AtomicInteger disarms = new AtomicInteger();
    ActionSessionImpl empty = new ActionSessionImpl(UUID.randomUUID(),
        def(conf(ConfinementBoundary.SUBSPACE, 0L, 30.0, 0.0, 0.0, 0L, null, 10, 0, 0.0, 0.0, 1L, 0.0,
            List.of())),
        List.of(), ActionContext.empty(), Map.of(), "world", 0, 0, null, id -> disarms.incrementAndGet(), Map.of());
    empty.triggerStart();
    assertFalse(empty.isActive());
    assertEquals(1, disarms.get());
    empty.disarm();
    assertEquals(1, disarms.get());

    MockRTPPlayer off1 = player("OffOne", 0, 0);
    MockRTPPlayer off2 = player("OffTwo", 0, 0);
    off1.setOnline(false);
    off2.setOnline(false);
    ActionSessionImpl unbounded = session(def(conf(ConfinementBoundary.SUBSPACE, -1L, 30.0, 0.0, 0.0, 0L, null,
        10, 0, 0.0, 0.0, 1L, 0.0, List.of())), List.of(off1.uuid(), off2.uuid()), Map.of(), "world");
    unbounded.arm();
    unbounded.tick();
    assertTrue(unbounded.isActive());
    assertTrue(accessor.getExecutedCommands()
        .contains("scoreboard players set " + off1.uuid() + " rtp_time_left 999999"));
    assertTrue(accessor.getExecutedCommands().contains("scoreboard players set " + off1.uuid() + " rtp_alive 2"));
    unbounded.triggerStart();
    assertFalse(unbounded.isActive());
    assertNull(accessor.getScoreboardScore(off1.uuid(), "rtp_time_left"));
  }

  // ---------------------------------------------------------------------------
  // Continuous confinement (tick)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("tick flags out-of-bounds participants once, applies damage on interval and runs outside actions")
  void tickContinuousConfinement() {
    MockRTPPlayer runner = player("Runner", 50, 0);
    MockRTPPlayer offline = player("Sleeper", 50, 0);
    offline.setOnline(false);
    UUID unknown = UUID.randomUUID();
    MockRTPPlayer noLoc = new MockRTPPlayer(UUID.randomUUID(), "NoLoc", null);
    accessor.addPlayer(noLoc);

    ActionDefinition d = def(conf(ConfinementBoundary.SUBSPACE, 100L, 30.0, 0.0, 0.0, 0L, null, 10, 0,
        2.0, 1.0, 1000L, 5.0, List.of(CommandAction.console("say out [player_name] [world]"))));
    ActionSessionImpl s = session(d, List.of(runner.uuid(), offline.uuid(), unknown, noLoc.uuid()),
        Map.of(runner.uuid(), new int[] {0, 64, 0}), "world");
    s.arm();
    s.tick();

    assertEquals(1, s.getViolations(runner.uuid()));
    assertEquals(0, s.getViolations(offline.uuid()));
    assertEquals(1, accessor.getAppliedDamages().size());
    assertEquals(2.0, accessor.getAppliedDamages().get(0).amount());
    assertTrue(accessor.getExecutedCommands().contains("say out Runner world"));
    assertEquals(2500, accessor.getScoreboardScore(runner.uuid(), "rtp_dist_sq"));

    s.tick();
    assertEquals(1, s.getViolations(runner.uuid()));
    assertEquals(1, accessor.getAppliedDamages().size());
    s.disarm();
    s.tick();
  }

  @Test
  @DisplayName("tick distance math covers wrong world, leash, circle and square boundaries")
  void tickDistanceVariants() {
    MockRTPWorld nether = new MockRTPWorld("nether");
    accessor.addWorld(nether);
    MockRTPPlayer elsewhere = new MockRTPPlayer(UUID.randomUUID(), "Elsewhere", new RTPLocation(nether, 0, 64, 0));
    accessor.addPlayer(elsewhere);
    ActionSessionImpl none = session(def(conf(ConfinementBoundary.NONE, 100L, 30.0, 0.0, 0.0, 0L, null, 10, 0,
        2.0, 0.0, 1L, 5.0, List.of())), List.of(elsewhere.uuid()), Map.of(), "world");
    none.tick();
    assertEquals(1, none.getViolations(elsewhere.uuid()));
    assertTrue(accessor.getAppliedDamages().isEmpty());
    none.disarm();

    ActionDefinition.ConfinementSpec[] specs = {
        conf(ConfinementBoundary.LEASH, 100L, 10.0, 0.0, 0.0, 0L, null, 0, 0, 1.0, 0.0, 1L, 0.0, List.of()),
        conf(ConfinementBoundary.SHAPE, 100L, 10.0, 0.0, 0.0, 0L, "CIRCLE", 10, 0, 1.0, 0.0, 1L, 0.0, List.of()),
        conf(ConfinementBoundary.SHAPE, 100L, 10.0, 0.0, 0.0, 0L, "SQUARE", 10, 0, 1.0, 0.0, 1L, 0.0, List.of()),
    };
    int expectedDamages = 0;
    for (ActionDefinition.ConfinementSpec spec : specs) {
      MockRTPPlayer p = player("Far" + expectedDamages, 30, 0);
      ActionSessionImpl s = session(def(PLACEMENT_NO_CENTER, spec, ActionDefinition.LifecycleSpec.EMPTY),
          List.of(p.uuid()), Map.of(), "world");
      s.tick();
      expectedDamages++;
      assertEquals(1, s.getViolations(p.uuid()));
      assertEquals(expectedDamages, accessor.getAppliedDamages().size());
      s.disarm();
    }

    MockRTPPlayer expiring = player("Expiring", 0, 0);
    ActionSessionImpl zero = session(def(conf(ConfinementBoundary.SUBSPACE, 0L, 30.0, 0.0, 0.0, 0L, null, 10, 0,
        0.0, 0.0, 1L, 0.0, List.of())), List.of(expiring.uuid()), Map.of(), "world");
    zero.tick();
    assertFalse(zero.isActive());
  }

  // ---------------------------------------------------------------------------
  // Move events
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("move events count breach-type transitions, apply damage and dispatch outside actions")
  void moveEventTransitions() {
    MockRTPPlayer mover = player("Mover", 100, 100);
    UUID pid = mover.uuid();
    ActionSessionImpl s = session(def(conf(ConfinementBoundary.SHAPE, 100L, 30.0, 0.0, 0.0, 0L, "SQUARE", 20, 5,
        1.5, 0.0, 1000L, 0.0, List.of())), List.of(pid), Map.of(pid, new int[] {5, 70, 5}), "world");
    s.arm();
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 0, 64, 0, 10, 64, 0));
    assertEquals(0, s.getViolations(pid));
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 10, 64, 0, 2, 64, 0));
    assertEquals(1, s.getViolations(pid));
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 2, 64, 0, 3, 64, 0));
    assertEquals(1, s.getViolations(pid));
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 3, 64, 0, 30, 64, 0));
    assertEquals(2, s.getViolations(pid));
    assertEquals(1, accessor.getAppliedDamages().size());
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 30, 64, 0, 31, 64, 0));
    assertEquals(2, s.getViolations(pid));
    assertEquals(1, accessor.getAppliedDamages().size());
    // outer -> inner (center) breach is a new breach type
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 31, 64, 0, 0, 64, 0));
    assertEquals(3, s.getViolations(pid));
    // inner -> wrong-world breach is another
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "elsewhere", 0, 64, 0, 0, 64, 0));
    assertEquals(4, s.getViolations(pid));
    s.disarm();

    ActionSessionImpl leashed = session(def(conf(ConfinementBoundary.SUBSPACE, 100L, 30.0, 0.0, 0.0, 0L, null, 10,
        0, 0.0, 0.0, 1L, 5.0, List.of(CommandAction.message("[player_name] left [world]"),
            CommandAction.action("PULL_BACK")))), List.of(pid), Map.of(pid, new int[] {5, 70, 5}), "world");
    leashed.arm();
    RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(pid, "world", 0, 64, 0, 100, 64, 0));
    assertTrue(mover.sentMessages.contains("Mover left world"));
    assertEquals(5, (int) mover.getLocation().z());
    assertEquals(70, (int) mover.getLocation().y());
    leashed.disarm();
  }

  // ---------------------------------------------------------------------------
  // Pull back / eliminate
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("pullBack ignores invalid inputs and logs rejected or failed teleports (S-004)")
  void pullBackOutcomes() {
    MockRTPPlayer ok = player("PullOk", 100, 100);
    MockRTPPlayer offline = player("PullOff", 100, 100);
    offline.setOnline(false);
    MockRTPPlayer rejecting = player("PullReject", 100, 100);
    rejecting.setFailSetLocation(true);
    FailingTeleportPlayer failing =
        new FailingTeleportPlayer(UUID.randomUUID(), "PullFail", new RTPLocation(world, 100, 64, 100));
    accessor.addPlayer(failing);
    UUID noSlot = UUID.randomUUID();

    Map<UUID, int[]> slots = new HashMap<>();
    for (UUID id : List.of(ok.uuid(), offline.uuid(), rejecting.uuid(), failing.uuid())) {
      slots.put(id, new int[] {1, 65, 2});
    }
    ActionSessionImpl s = session(def(simple(ConfinementBoundary.SUBSPACE, null, 10, 0)),
        List.of(ok.uuid(), offline.uuid(), rejecting.uuid(), failing.uuid(), noSlot), slots, "world");

    s.pullBack(null);
    s.pullBack(noSlot);
    s.pullBack(offline.uuid());
    assertEquals(100, (int) offline.getLocation().x());

    s.pullBack(rejecting.uuid());
    assertTrue(logged("pull-back rejected for player " + rejecting.uuid()));
    s.pullBack(failing.uuid());
    assertTrue(logged("pull-back failed for player " + failing.uuid()));

    s.pullBack(ok.uuid());
    assertEquals(1, (int) ok.getLocation().x());
    assertEquals(2, (int) ok.getLocation().z());
    // in-flight guard released after completion
    ok.setLocation(new RTPLocation(world, 50, 64, 50));
    s.pullBack(ok.uuid());
    assertEquals(1, (int) ok.getLocation().x());

    ActionSessionImpl missingWorld = session(def(simple(ConfinementBoundary.SUBSPACE, null, 10, 0)),
        List.of(ok.uuid()), slots, "no_such_world");
    ok.setLocation(new RTPLocation(world, 50, 64, 50));
    missingWorld.pullBack(ok.uuid());
    assertEquals(50, (int) ok.getLocation().x());
  }

  @Test
  @DisplayName("eliminate ends the session only when at most one other participant remains online")
  void eliminateOutcomes() {
    MockRTPPlayer a = player("ElimA", 0, 0);
    MockRTPPlayer b = player("ElimB", 0, 0);
    MockRTPPlayer c = player("ElimC", 0, 0);
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(), List.of(), List.of(step(CommandAction.console("say [victim] eliminated"))),
            List.of()));

    ActionSessionImpl three = session(d, List.of(a.uuid(), b.uuid(), c.uuid()), Map.of(), "world");
    three.eliminate(null);
    three.eliminate(a.uuid());
    assertTrue(accessor.getExecutedCommands().contains("gamemode survival " + a.uuid()));
    assertTrue(three.isActive());
    three.disarm();

    b.setOnline(false);
    ActionSessionImpl two = session(d, List.of(a.uuid(), b.uuid()), Map.of(), "world");
    two.eliminate(a.uuid());
    assertFalse(two.isActive());
    assertTrue(accessor.getExecutedCommands().stream().anyMatch(cmd -> cmd.endsWith(" eliminated")));

    ActionSessionImpl solo = session(d, List.of(c.uuid()), Map.of(), "world");
    solo.eliminate(c.uuid());
    assertTrue(solo.isActive());
    solo.disarm();
  }

  // ---------------------------------------------------------------------------
  // Terminal triggers
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("terminal triggers run at most once and cancel falls back to onExpire steps")
  void terminalTriggersIdempotent() {
    MockRTPPlayer p = player("Terminal", 0, 0);
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(), List.of(step(CommandAction.console("say expired now"))), List.of(),
            List.of()));

    ActionSessionImpl expire = session(d, List.of(p.uuid()), Map.of(), "world");
    expire.triggerExpire();
    expire.triggerExpire();
    expire.triggerCancel(p.uuid());
    expire.triggerDeath(p.uuid(), null);
    assertEquals(1, commandsStartingWith("say expired now").size());

    ActionSessionImpl cancel = session(d, List.of(p.uuid()), Map.of(), "world");
    cancel.triggerCancel(null);
    assertFalse(cancel.isActive());
    assertEquals(2, commandsStartingWith("say expired now").size());

    ActionSessionImpl death = session(d, List.of(p.uuid()), Map.of(), "world");
    death.triggerDeath(null, null);
    assertFalse(death.isActive());
    death.triggerDeath(null, null);
    assertEquals(2, commandsStartingWith("say expired now").size());
  }

  // ---------------------------------------------------------------------------
  // Lifecycle tokens, gates and delays
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("lifecycle tokens expose numbered participant names and cluster groups")
  void lifecycleTokens() {
    MockRTPPlayer alice = player("TokAlice", 0, 0);
    MockRTPPlayer bob = player("TokBob", 0, 0);
    List<UUID> ps = List.of(alice.uuid(), bob.uuid());
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(step(CommandAction.console("say [player_name_1] vs [sender_name_2]"))), List.of(),
            List.of(), List.of(), List.of()));

    ActionSessionImpl named = new ActionSessionImpl(UUID.randomUUID(), d, ps,
        ActionContext.ofNamedClusters(Map.of("Red", List.of(alice.uuid()), "Blue", List.of(bob.uuid()))),
        Map.of(alice.uuid(), new int[] {0, 80, 0}), "world", 0, 0, null, null, Map.of());
    named.triggerStart();
    assertTrue(named.isActive());
    assertTrue(accessor.getExecutedCommands().contains("say TokAlice vs TokBob"));
    named.disarm();

    ActionSessionImpl listed = new ActionSessionImpl(UUID.randomUUID(), d, ps,
        ActionContext.ofClusters(List.of(List.of(alice.uuid()), List.of(bob.uuid()))), Map.of(), "world", 0, 0,
        null, null, Map.of());
    listed.triggerStart();
    assertEquals(2, commandsStartingWith("say TokAlice vs TokBob").size());
    listed.disarm();
  }

  @Test
  @DisplayName("gated and delayed boundary-violation steps re-evaluate gates and survive failures")
  void gatesAndDelayedSteps() {
    MockRTPPlayer p = player("Gated", 0, 0);
    AtomicInteger flakyCalls = new AtomicInteger();
    AtomicInteger secondDenyCalls = new AtomicInteger();
    Map<String, Predicate<ActionGateContext>> preds = new HashMap<>();
    preds.put("deny", ctx -> false);
    preds.put("boom", ctx -> {
      throw new IllegalStateException("predicate boom");
    });
    preds.put("flaky", ctx -> {
      if (flakyCalls.incrementAndGet() > 1) throw new IllegalStateException("flaky boom");
      return true;
    });
    preds.put("secondDeny", ctx -> secondDenyCalls.incrementAndGet() == 1);
    preds.put("allow", ctx -> true);

    List<LifecycleStep> violation = List.of(
        gated(Map.of("predicate", "deny"), 0L, CommandAction.console("say denied")),
        gated(Map.of("predicate", "boom"), 0L, CommandAction.console("say boom")),
        gated(Map.of("predicate", "flaky"), 1L, CommandAction.console("say flaky")),
        gated(Map.of("predicate", "secondDeny"), 1L, CommandAction.console("say second denied")),
        gated(Map.of("predicate", "allow"), 1L, CommandAction.console("say delayed ok"),
            CommandAction.player("msg &aHi there"), CommandAction.message("")));
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), violation, List.of(), List.of(), List.of()));
    ActionSessionImpl s = new ActionSessionImpl(UUID.randomUUID(), d, List.of(p.uuid()), ActionContext.empty(),
        Map.of(p.uuid(), new int[] {0, 64, 0}), "world", 0, 0, null, null, preds);

    s.triggerBoundaryViolation(p.uuid(), 50, 50, 3);
    assertEquals(3, s.getViolations(p.uuid()));
    assertTrue(logged("Error evaluating lifecycle step"));
    assertTrue(commandsStartingWith("say delayed ok").isEmpty());

    accessor.getMockScheduler().tick(20);
    assertEquals(1, commandsStartingWith("say delayed ok").size());
    assertTrue(p.sentMessages.contains("&aHi there"));
    assertTrue(logged("Error executing delayed lifecycle step"));
    assertTrue(commandsStartingWith("say denied").isEmpty());
    assertTrue(commandsStartingWith("say second denied").isEmpty());
    assertTrue(commandsStartingWith("say boom").isEmpty());

    ActionSessionImpl stopped = new ActionSessionImpl(UUID.randomUUID(), d, List.of(p.uuid()),
        ActionContext.empty(), Map.of(), "world", 0, 0, null, null, preds);
    stopped.triggerBoundaryViolation(null, 50, 50, 1);
    stopped.disarm();
    accessor.getMockScheduler().tick(20);
    assertEquals(1, commandsStartingWith("say delayed ok").size());

    ActionSessionImpl validated = new ActionSessionImpl(UUID.randomUUID(), d, List.of(p.uuid()),
        ActionContext.empty().withValidator(ctx -> false), Map.of(), "world", 0, 0, null, null, preds);
    // Context gate validators govern queue admission (ActionManager), not in-session lifecycle gates.
    validated.triggerBoundaryViolation(p.uuid(), 1, 1, 1);
    accessor.getMockScheduler().tick(20);
    assertEquals(2, commandsStartingWith("say delayed ok").size());
    validated.disarm();
  }

  @Test
  @DisplayName("delayed terminal steps only fire while the subject or any participant is online")
  void delayedTerminalStepsRequireOnlinePlayers() {
    MockRTPPlayer online = player("DelayOn", 0, 0);
    MockRTPPlayer offline = player("DelayOff", 0, 0);
    offline.setOnline(false);
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(),
            List.of(gated(Map.of(), 1L, CommandAction.console("say late expire"))), List.of(),
            List.of(gated(Map.of(), 1L, CommandAction.console("say late cancel")))));

    session(d, List.of(online.uuid()), Map.of(), "world").triggerCancel(online.uuid());
    session(d, List.of(offline.uuid()), Map.of(), "world").triggerCancel(offline.uuid());
    session(d, List.of(offline.uuid()), Map.of(), "world").triggerExpire();
    session(d, List.of(offline.uuid(), online.uuid()), Map.of(), "world").triggerExpire();
    session(d, List.of(), Map.of(), "world").triggerExpire();
    accessor.getMockScheduler().tick(20);

    assertEquals(1, commandsStartingWith("say late cancel").size());
    assertEquals(2, commandsStartingWith("say late expire").size());
  }

  // ---------------------------------------------------------------------------
  // Guarded action dispatch
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("broadcast actions fan out per participant and drop unresolved or blank payloads")
  void broadcastGuardedActions() {
    MockRTPPlayer alice = player("FanAlice", 0, 0);
    MockRTPPlayer carl = player("FanCarl", 0, 0);
    carl.setOnline(false);
    List<CommandAction> actions = List.of(
        CommandAction.console("say hello [player_name]"),
        CommandAction.console("say [mystery_token]"),
        CommandAction.console(""),
        CommandAction.player("tell [player] hi"),
        CommandAction.player("msg §bSection colored"),
        CommandAction.player(""),
        CommandAction.message("Broadcast!"),
        CommandAction.messageTarget("To target"),
        CommandAction.action("PULL_BACK"),
        CommandAction.action("ELIMINATE"),
        CommandAction.action(" mystery "),
        CommandAction.forEach(List.of(CommandAction.player("spawn"))));
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(), List.of(new LifecycleStep(Map.of(), actions, 0L)), List.of(), List.of()));
    ActionSessionImpl s = session(d, List.of(alice.uuid(), carl.uuid()), Map.of(), "world");
    s.triggerExpire();

    assertTrue(accessor.getExecutedCommands().contains("say hello FanAlice"));
    assertFalse(accessor.getExecutedCommands().contains("say hello FanCarl"));
    assertTrue(commandsStartingWith("say [mystery_token]").isEmpty());
    assertTrue(logged("unresolved placeholder"));
    assertEquals(2, commandsStartingWith("tell ").size());
    assertTrue(alice.sentMessages.contains("§bSection colored"));
    assertTrue(alice.sentMessages.contains("Broadcast!"));
    assertTrue(carl.sentMessages.contains("Broadcast!"));
    assertTrue(carl.sentMessages.contains("To target"));
    assertFalse(alice.sentMessages.contains("To target"));
    assertTrue(logged("Unknown action command: MYSTERY"));
    assertEquals(2, commandsStartingWith("spawn").size());
  }

  @Test
  @DisplayName("MESSAGE_TARGET resolves name targets and skips wildcard, unknown and missing targets")
  void messageTargetVariants() {
    MockRTPPlayer solo = player("TargetSolo", 0, 0);
    MockRTPPlayer dora = player("TargetDora", 0, 0);
    List<CommandAction> actions = List.of(
        CommandAction.messageTarget("Ping"),
        CommandAction.console("say [target] missing"));
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(), List.of(new LifecycleStep(Map.of(), actions, 0L)), List.of(), List.of()));

    for (Map<String, Object> meta : List.<Map<String, Object>>of(
        Map.of("target_name", "TargetDora"),
        Map.of("target_name", "Ghost"),
        Map.of("target", "any"),
        Map.of())) {
      new ActionSessionImpl(UUID.randomUUID(), d, List.of(solo.uuid()), new ActionContext(meta), Map.of(), "world",
          0, 0, null, null, Map.of()).triggerExpire();
    }
    assertEquals(1, dora.sentMessages.stream().filter("Ping"::equals).count());
    assertFalse(solo.sentMessages.contains("Ping"));
    assertTrue(accessor.getExecutedCommands().stream().noneMatch(c -> c.endsWith(" missing")));
  }

  @Test
  @DisplayName("violator-scoped actions target the violator and guarded failures are logged")
  void violatorScopedActionsAndGuardedFailure() {
    MockRTPPlayer v = player("Violator", 0, 0);
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(step(CommandAction.player("spawn here"), CommandAction.action("ELIMINATE"),
            CommandAction.message("You strayed"))), List.of(), List.of(), List.of()));
    ActionSessionImpl s = session(d, List.of(v.uuid()), Map.of(), "world");
    s.triggerBoundaryViolation(v.uuid(), 40, 40, 1);
    assertTrue(accessor.getExecutedCommands().contains("spawn here"));
    assertTrue(accessor.getExecutedCommands().contains("gamemode survival " + v.uuid()));
    assertTrue(v.sentMessages.contains("You strayed"));
    assertTrue(s.isActive());
    s.disarm();

    ThrowingOnlinePlayer broken =
        new ThrowingOnlinePlayer(UUID.randomUUID(), "Broken", new RTPLocation(world, 0, 64, 0));
    accessor.addPlayer(broken);
    ActionDefinition failing = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(), List.of(), List.of(step(CommandAction.console("say [player] broken"))), List.of(),
            List.of()));
    ActionSessionImpl bs = session(failing, List.of(broken.uuid()), Map.of(), "world");
    bs.triggerExpire();
    assertFalse(bs.isActive());
    assertTrue(logged("Command execution failed for step: say [player] broken"));
  }

  // ---------------------------------------------------------------------------
  // No server accessor
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("session operations degrade to no-ops when no server accessor is installed (S-006)")
  void nullAccessorPaths() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    ActionDefinition d = def(PLACEMENT, simple(ConfinementBoundary.SUBSPACE, null, 10, 0),
        lifecycle(List.of(step(CommandAction.console("say start"))),
            List.of(step(CommandAction.console("say violation"), CommandAction.player("spawn"),
                CommandAction.message("hi"))),
            List.of(), List.of(), List.of()));
    RTPServerAccessor saved = RTP.serverAccessor;
    RTP.serverAccessor = null;
    try {
      ActionSessionImpl s = session(d, List.of(a, b), Map.of(a, new int[] {0, 64, 0}), "world");
      s.arm();
      s.tick();
      s.triggerStart();
      s.triggerBoundaryViolation(a, 100, 100, 2);
      assertEquals(2, s.getViolations(a));
      s.pullBack(a);
      assertTrue(s.isActive());
      s.eliminate(a);
      assertFalse(s.isActive());
    } finally {
      RTP.serverAccessor = saved;
    }
    assertTrue(commandsStartingWith("say ").isEmpty());
    assertTrue(accessor.getSentWorldBorders().isEmpty());
  }
}
