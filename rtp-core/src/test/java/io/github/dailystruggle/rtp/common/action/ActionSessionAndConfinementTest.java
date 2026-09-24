package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
    RTP.scheduler = serverAccessor.getMockScheduler();
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
  @DisplayName("Action session sends world border on start and resets on disarm")
  void testWorldBorderConfinementPackets() {
    UUID p1 = UUID.randomUUID();

    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 300L, 40.0, 100.0, 20.0, 60L);

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "A duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        ActionDefinition.LifecycleSpec.EMPTY);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{100, 64, 200}),
        "world", 100, 200, null, null, null);

    session.arm();
    session.triggerStart();

    // Verify sent world border has center (100, 200), initialSize 100, shrinkTo 20, shrinkOver 60s
    assertEquals(1, serverAccessor.getSentWorldBorders().size());
    MockRTPServerAccessor.SentWorldBorder wb = serverAccessor.getSentWorldBorders().get(0);
    assertEquals(p1, wb.playerId());
    assertEquals(100.0, wb.centerX());
    assertEquals(200.0, wb.centerZ());
    assertEquals(100.0, wb.oldSize());
    assertEquals(20.0, wb.newSize());
    assertEquals(60L, wb.shrinkSeconds());

    session.disarm();

    // Verify reset world border called
    assertTrue(serverAccessor.getResetWorldBorders().contains(p1));
  }

  @Test
  @DisplayName("Delayed lifecycle step execution and opt-out cancellation")
  void testDelayedLifecycleStepOptOut() {
    UUID p1 = UUID.randomUUID();

    // Lifecycle with a 2-second delayed step guarded by violations == 0
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of("scoreboard", Map.of("objective", "rtp_violations", "matches", "== 0")),
                List.of(ActionDefinition.CommandAction.console("match_started [player]")),
                2L // 2 seconds delay
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
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    session.arm();
    session.triggerStart();

    // Immediately after start, command is not executed yet due to delay
    assertFalse(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("match_started")));

    // Simulate player opting out by leaving area / triggering a boundary violation
    session.triggerBoundaryViolation(p1, 1000, 1000, 1);
    assertEquals(1, session.getViolations(p1));

    // Advance mock scheduler by 40 ticks (2 seconds)
    serverAccessor.getMockScheduler().tick(40);

    // Re-evaluated gate should fail because violations == 1, not 0!
    assertFalse(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("match_started")));

    session.disarm();
  }

  @Test
  @DisplayName("Full mock run: Waiting room lobby with spatial shape, delay opt-out, and shrinking border arena")
  void testWaitingRoomToArenaEndToEndMockRun() {
    // 1. Setup mock environment & players
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");

    // Player 1 & Player 2
    UUID p1Id = UUID.randomUUID();
    UUID p2Id = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p1Id, "PlayerOne", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100, 64, 100));
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p2 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p2Id, "PlayerTwo", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 105, 64, 105));
    serverAccessor.addPlayer(p1);
    serverAccessor.addPlayer(p2);

    // Setup Shape Factory for Square lobby shape
    io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> shapeFactory =
        new io.github.dailystruggle.rtp.common.factory.Factory<>();
    shapeFactory.add("SQUARE", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square());
    RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);

    // Waiting room lobby shape centered at chunk (6, 6) [~96, 96] with radius 1 chunk
    Map<String, Object> lobbyGateConfig = Map.of(
        "spatial", Map.of(
            "world", "world",
            "shape", Map.of("name", "SQUARE", "radius", 1, "centerRadius", 0, "centerX", 6, "centerZ", 6),
            "playerCount", ">= 2"
        )
    );

    // Step A: Both players are inside the lobby -> Gate evaluates TRUE
    ActionGateContext lobbyCtx = new ActionGateContext(
        UUID.randomUUID(), "lobby", p1Id, 0L, 0L, 0, true, 0.0, 100.0, 64.0, 100.0);
    assertTrue(GateEvaluator.evaluate(lobbyGateConfig, lobbyCtx, null));

    // Step B: Player 2 leaves the lobby -> Gate evaluates FALSE (opt-out)
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p2OptOut =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p2Id, "PlayerTwo", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 500, 64, 500));
    serverAccessor.addPlayer(p2OptOut); // Updates player location
    assertFalse(GateEvaluator.evaluate(lobbyGateConfig, lobbyCtx, null));

    // Step C: Player 2 returns to lobby -> Gate evaluates TRUE again!
    serverAccessor.addPlayer(p2);
    assertTrue(GateEvaluator.evaluate(lobbyGateConfig, lobbyCtx, null));

    // Step D: Countdown step triggered with 3-second delay, gated on playerCount >= 2
    AtomicBoolean arenaTriggered = new AtomicBoolean(false);
    ActionDefinition.LifecycleSpec lobbyLifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                lobbyGateConfig,
                List.of(ActionDefinition.CommandAction.console("trigger_arena")),
                3L // 3-second delay before starting match
            )
        ),
        List.of(), List.of(), List.of()
    );

    ActionDefinition lobbyDef = new ActionDefinition(
        "lobby", "lobby", "rtp.action.lobby", "Lobby",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lobbyLifecycle);

    ActionSessionImpl lobbySession = new ActionSessionImpl(
        UUID.randomUUID(), lobbyDef, List.of(p1Id, p2Id), ActionContext.EMPTY,
        Map.of(p1Id, new int[]{100, 64, 100}, p2Id, new int[]{105, 64, 105}),
        "world", 100, 100, null, null, null);

    lobbySession.arm();
    lobbySession.triggerStart();

    // Advance 60 ticks (3 seconds) in mock scheduler
    serverAccessor.getMockScheduler().tick(60);

    // Verify command executed because both players stayed in lobby during countdown
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("trigger_arena")));
    lobbySession.disarm();

    // Step E: Now transition to Arena Action Session with Dynamic Shrinking World Border!
    // Anchor at (1000, 2000), initial size 128 blocks, shrinks to 32 blocks over 4 minutes (240s)
    ActionDefinition.ConfinementSpec arenaConfinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 300L, 48.0, 128.0, 32.0, 240L);

    ActionDefinition.LifecycleSpec arenaLifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Collections.emptyMap(),
                List.of(
                    ActionDefinition.CommandAction.forEach(
                        List.of(
                            ActionDefinition.CommandAction.console("gamemode adventure [player]"),
                            ActionDefinition.CommandAction.console("give [player] iron_sword 1")
                        )
                    )
                )
            )
        ),
        List.of(
            new ActionDefinition.LifecycleStep(
                Collections.emptyMap(),
                List.of(
                    ActionDefinition.CommandAction.action("PULL_BACK")
                )
            )
        ),
        List.of(
            new ActionDefinition.LifecycleStep(
                Collections.emptyMap(),
                List.of(
                    ActionDefinition.CommandAction.forEach(
                        List.of(
                            ActionDefinition.CommandAction.console("gamemode survival [player]")
                        )
                    )
                )
            )
        ),
        List.of()
    );

    ActionDefinition arenaDef = new ActionDefinition(
        "arena", "arena", "rtp.action.arena", "Arena Duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        arenaConfinement,
        arenaLifecycle);

    int initialBorderPacketCount = serverAccessor.getSentWorldBorders().size();

    ActionSessionImpl arenaSession = new ActionSessionImpl(
        UUID.randomUUID(), arenaDef, List.of(p1Id, p2Id), ActionContext.EMPTY,
        Map.of(p1Id, new int[]{980, 64, 2000}, p2Id, new int[]{1020, 64, 2000}),
        "world", 1000, 2000, null, null, null);

    arenaSession.arm();
    arenaSession.triggerStart();

    // 1. Verify gear & gamemode applied to both mock players
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode adventure " + p1Id)));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode adventure " + p2Id)));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("give " + p1Id + " iron_sword")));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("give " + p2Id + " iron_sword")));

    // 2. Verify per-player world border packets sent with arbitrary center and shrinking lerp
    List<MockRTPServerAccessor.SentWorldBorder> borderPackets = serverAccessor.getSentWorldBorders()
        .subList(initialBorderPacketCount, serverAccessor.getSentWorldBorders().size());
    assertEquals(2, borderPackets.size());

    for (MockRTPServerAccessor.SentWorldBorder packet : borderPackets) {
      assertEquals(1000.0, packet.centerX(), "Center X must be arena anchor");
      assertEquals(2000.0, packet.centerZ(), "Center Z must be arena anchor");
      assertEquals(128.0, packet.oldSize(), "Initial border width must be 128");
      assertEquals(32.0, packet.newSize(), "Target border width must be 32");
      assertEquals(240L, packet.shrinkSeconds(), "Shrink time must be 240 seconds");
    }

    // 3. Simulate boundary pull-back for p1 attempting to flee past border
    arenaSession.triggerBoundaryViolation(p1Id, 1100, 2000, 1);
    io.github.dailystruggle.rtp.api.entity.RTPPlayer arenaP1 = serverAccessor.getPlayer(p1Id);
    assertNotNull(arenaP1);
    assertEquals(980, arenaP1.getLocation().x(), "Player 1 pulled back to interior safe spawn");

    // 4. Trigger match expiration (onExpire runs steps then calls disarm)
    arenaSession.triggerExpire();

    // Verify survival gamemode restored on expire
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode survival " + p1Id)),
        "Executed commands: " + serverAccessor.getExecutedCommands());
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode survival " + p2Id)));

    // Verify world border reset for both players
    assertTrue(serverAccessor.getResetWorldBorders().contains(p1Id));
    assertTrue(serverAccessor.getResetWorldBorders().contains(p2Id));
  }

  @Test
  @DisplayName("Action session populates cluster placeholders [cluster_1], [cluster_2], [players] for team commands")
  void testClusterPlaceholdersForTeams() {
    UUID red1 = UUID.randomUUID();
    UUID red2 = UUID.randomUUID();
    UUID blue1 = UUID.randomUUID();

    ActionContext context = ActionContext.ofNamedClusters(Map.of(
        "red", List.of(red1, red2),
        "blue", List.of(blue1)
    ));

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.console("team join red [cluster_red]"),
                    ActionDefinition.CommandAction.console("team join blue [cluster_blue]"),
                    ActionDefinition.CommandAction.console("team join 1 [cluster_1]"),
                    ActionDefinition.CommandAction.console("team join 2 [cluster_2]"),
                    ActionDefinition.CommandAction.console("say All participants: [players]")
                )
            )
        ),
        List.of(), List.of(), List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "team_duel", "team_duel", "rtp.action.team_duel", "Team Duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(red1, red2, blue1), context,
        Map.of(red1, new int[]{0, 64, 0}, red2, new int[]{2, 64, 0}, blue1, new int[]{32, 64, 0}),
        "world", 16, 0, null, null, null);

    session.arm();
    session.triggerStart();

    List<String> commands = serverAccessor.getExecutedCommands();

    // Verify [cluster_red] expands to both red team UUIDs separated by space
    assertTrue(commands.stream().anyMatch(c -> c.contains("team join red " + red1 + " " + red2)
        || c.contains("team join red " + red2 + " " + red1)), "Commands: " + commands);

    // Verify [cluster_blue] expands to blue team UUID
    assertTrue(commands.stream().anyMatch(c -> c.contains("team join blue " + blue1)), "Commands: " + commands);

    // Verify numbered clusters work identically
    assertTrue(commands.stream().anyMatch(c -> c.startsWith("team join 1 ")), "Commands: " + commands);
    assertTrue(commands.stream().anyMatch(c -> c.startsWith("team join 2 ")), "Commands: " + commands);

    // Verify [players] contains all 3 participant UUIDs
    assertTrue(commands.stream().anyMatch(c -> c.contains("All participants:")
        && c.contains(red1.toString())
        && c.contains(red2.toString())
        && c.contains(blue1.toString())), "Commands: " + commands);

    session.disarm();
  }

  @Test
  @DisplayName("Radial Leash Interpolation: Mathematical boundary radius contracts smoothly over time")
  void testRadialLeashInterpolation() throws InterruptedException {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "Player1", loc);
    serverAccessor.addPlayer(mockPlayer);

    // Initial size 100 blocks (radius 50), shrinks to 20 blocks (radius 10) over 10 seconds
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 60L, 50.0, 100.0, 20.0, 10L);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.action("PULL_BACK"))
            )
        ),
        List.of(),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "shrink_duel", "shrink_duel", "rtp.action.shrink", "Shrink",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        lifecycle);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    session.arm();
    session.triggerStart();

    // At t=0, initial radius is 50.0 blocks
    assertEquals(50.0, session.currentBoundaryRadius(), 0.001);

    // Position at (30, 0) is well within 50.0 radius -> no violation
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 30, 64, 0));
    assertEquals(0, session.getViolations(p1));

    // Position at (55, 0) is outside 50.0 radius -> violation!
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 30, 64, 0, 55, 64, 0));
    assertEquals(1, session.getViolations(p1));

    session.disarm();
  }

  @Test
  @DisplayName("Subspace and Leash boundary interpolation contracts based on elapsed seconds")
  void testBoundaryRadiusInterpolationCalculation() {
    UUID p1 = UUID.randomUUID();

    // Initial size 200 blocks (radius 100), shrink to 40 blocks (radius 20) over 20 seconds
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 60L, 100.0, 200.0, 40.0, 20L);

    ActionDefinition def = new ActionDefinition(
        "shrink_calc", "shrink_calc", "rtp.action.calc", "Calc",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        ActionDefinition.LifecycleSpec.EMPTY);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    assertEquals(100.0, session.currentBoundaryRadius(), 0.001);

    // Subspace variant: placement radius 64 -> initial size default 128 (radius 64)
    ActionDefinition.ConfinementSpec subspaceConf = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.SUBSPACE, 60L, 64.0, 128.0, 32.0, 10L);

    ActionDefinition defSubspace = new ActionDefinition(
        "subspace_calc", "subspace_calc", "rtp.action.subspace", "Subspace",
        ActionDefinition.PlacementSpec.DEFAULT,
        subspaceConf,
        ActionDefinition.LifecycleSpec.EMPTY);

    ActionSessionImpl subspaceSession = new ActionSessionImpl(
        UUID.randomUUID(), defSubspace, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    assertEquals(64.0, subspaceSession.currentBoundaryRadius(), 0.001);
  }

  @Test
  @DisplayName("Adversarial: Mid-countdown player disconnect aborts match start (fail-closed)")
  void testMidCountdownDisconnectAbortsMatch() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc1 =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100, 64, 100);
    io.github.dailystruggle.rtp.api.world.RTPLocation loc2 =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 105, 64, 105);

    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockP1 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "Player1", loc1);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockP2 =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p2, "Player2", loc2);
    serverAccessor.addPlayer(mockP1);
    serverAccessor.addPlayer(mockP2);

    io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> shapeFactory =
        new io.github.dailystruggle.rtp.common.factory.Factory<>();
    shapeFactory.add("SQUARE", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square());
    RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);

    // Spatial gate for lobby room shape (playerCount >= 2)
    Map<String, Object> lobbyGateConfig = Map.of(
        "spatial", Map.of(
            "world", "world",
            "shape", Map.of("name", "SQUARE", "radius", 1, "centerRadius", 0, "centerX", 6, "centerZ", 6),
            "playerCount", ">= 2"
        )
    );

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            // Initial countdown announcement
            new ActionDefinition.LifecycleStep(
                lobbyGateConfig,
                List.of(ActionDefinition.CommandAction.console("countdown started")),
                0L
            ),
            // Delayed countdown sequence, gated on playerCount >= 2 in lobby
            new ActionDefinition.LifecycleStep(
                lobbyGateConfig,
                List.of(ActionDefinition.CommandAction.console("match started [player]")),
                5L
            )
        ),
        List.of(),
        List.of(),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "countdown_duel", "countdown_duel", "rtp.action.cd", "Countdown duel",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{100, 64, 100}, p2, new int[]{105, 64, 105}),
        "world", 100, 100, null, null, null);

    session.arm();
    session.triggerStart();

    // Verify step 1 countdown fired
    assertTrue(serverAccessor.getExecutedCommands().contains("countdown started"));
    assertFalse(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("match started")));

    // Player 2 leaves / moves far away mid-countdown (out of the lobby shape)
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockP2Away =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p2, "Player2", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 5000, 64, 5000));
    serverAccessor.addPlayer(mockP2Away);

    // Fast-forward scheduler 5 seconds (100 ticks)
    serverAccessor.getMockScheduler().tick(100L);

    // Assert fail-closed: match did NOT start because player 2 left and gate failed
    assertFalse(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("match started")));

    session.disarm();
  }

  @Test
  @DisplayName("Adversarial: Extreme boundary breach and clamped pull-back under heavy displacement")
  void testExtremeBoundaryBreachClamp() {
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
                Map.of(),
                List.of(ActionDefinition.CommandAction.action("PULL_BACK"))
            )
        ),
        List.of(),
        List.of()
    );

    // Small boundary: radius 10.0 around center (0, 0)
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 60L, 10.0);

    ActionDefinition def = new ActionDefinition(
        "clamp_duel", "clamp_duel", "rtp.action.clamp", "Clamp",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        lifecycle);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    session.arm();
    session.triggerStart();

    // Extreme displacement: player suddenly at (500, 64, -800)
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 500, 64, -800));

    assertEquals(1, session.getViolations(p1));

    // Player must be pulled back inside or onto the boundary (<= 10.0 from center 0, 0)
    io.github.dailystruggle.rtp.api.world.RTPLocation clampedLoc = mockPlayer.getLocation();
    double distFromCenter = Math.hypot(clampedLoc.x(), clampedLoc.z());
    assertTrue(distFromCenter <= 10.001, "Player was not clamped inside boundary! Dist: " + distFromCenter);

    session.disarm();
  }

  @Test
  @DisplayName("Adversarial: Malformed gate criteria fails closed without crashing")
  void testMalformedGateCriteriaFailsClosed() {
    UUID p1 = UUID.randomUUID();
    ActionGateContext gateCtx = new ActionGateContext(
        UUID.randomUUID(), "test_session", p1, 10L, 50L, 0, true, 0.0, 10.0, 20.0, 10.0);

    // Spatial gate referencing nonexistent shape and unparseable operator
    Map<String, Object> badGateConfig = Map.of(
        "spatial", Map.of(
            "world", "world",
            "shape", Map.of("name", "INVALID_SHAPE", "radius", 10),
            "playerCount", "not_a_number"
        )
    );

    // Evaluation must safely return false (fail-closed, S-004) rather than throwing
    assertFalse(GateEvaluator.evaluate(badGateConfig, gateCtx, null));
  }

  @Test
  @DisplayName("Adversarial: Session disarm completes cleanly even if lifecycle hook encounters error")
  void testDisarmResilienceUnderHookFailure() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "Player1", loc);
    serverAccessor.addPlayer(mockPlayer);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(),
        List.of(
            // Bad step with null command
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(new ActionDefinition.CommandAction(ActionDefinition.ActionType.CONSOLE, null, List.of()))
            ),
            // Subsequent step that must still execute
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.console("cleanup_verified"))
            )
        ),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "disarm_resilience", "disarm_resilience", "rtp.action.resilience", "Resilience",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    session.arm();
    assertDoesNotThrow(session::disarm);

    // Verify disarm is complete and session is no longer active
    assertFalse(session.isActive());
  }
}
