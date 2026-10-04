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
    ActionSessionImpl.resetScoreboardObjectivesInitializedForTesting();
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
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("warn Player1") || c.contains("warn " + p1)));

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
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode adventure PlayerOne") || c.contains("gamemode adventure " + p1Id)));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode adventure PlayerTwo") || c.contains("gamemode adventure " + p2Id)));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("give PlayerOne iron_sword") || c.contains("give " + p1Id + " iron_sword")));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("give PlayerTwo iron_sword") || c.contains("give " + p2Id + " iron_sword")));

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
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode survival PlayerOne") || c.contains("gamemode survival " + p1Id)),
        "Executed commands: " + serverAccessor.getExecutedCommands());
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("gamemode survival PlayerTwo") || c.contains("gamemode survival " + p2Id)));

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
  void testRadialLeashInterpolation() {
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

  @Test
  @DisplayName("Multi-session concurrent dispatch and state isolation across independent arenas")
  void testConcurrentMultiSessionStateIsolation() {
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");

    // Session 1: Alpha arena at (1000, 2000)
    UUID a1 = UUID.randomUUID();
    UUID a2 = UUID.randomUUID();
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        a1, "Alpha1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 1000, 64, 2000)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        a2, "Alpha2", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 1010, 64, 2000)));

    // Session 2: Beta arena at (-5000, 8000)
    UUID b1 = UUID.randomUUID();
    UUID b2 = UUID.randomUUID();
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        b1, "Beta1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, -5000, 64, 8000)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        b2, "Beta2", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, -4990, 64, 8000)));

    ActionDefinition.ConfinementSpec conf1 = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 120L, 30.0, 64.0, 16.0, 60L);
    ActionDefinition.ConfinementSpec conf2 = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 120L, 30.0, 128.0, 32.0, 60L);

    ActionDefinition def1 = new ActionDefinition(
        "alpha_duel", "alpha_duel", "rtp.action.alpha", "Alpha",
        ActionDefinition.PlacementSpec.DEFAULT, conf1, ActionDefinition.LifecycleSpec.EMPTY);
    ActionDefinition def2 = new ActionDefinition(
        "beta_duel", "beta_duel", "rtp.action.beta", "Beta",
        ActionDefinition.PlacementSpec.DEFAULT, conf2, ActionDefinition.LifecycleSpec.EMPTY);

    ActionSessionImpl sessionAlpha = new ActionSessionImpl(
        UUID.randomUUID(), def1, List.of(a1, a2), ActionContext.EMPTY,
        Map.of(a1, new int[]{1000, 64, 2000}, a2, new int[]{1010, 64, 2000}),
        "world", 1000, 2000, null, null, null);

    ActionSessionImpl sessionBeta = new ActionSessionImpl(
        UUID.randomUUID(), def2, List.of(b1, b2), ActionContext.EMPTY,
        Map.of(b1, new int[]{-5000, 64, 8000}, b2, new int[]{-4990, 64, 8000}),
        "world", -5000, 8000, null, null, null);

    sessionAlpha.arm();
    sessionBeta.arm();
    sessionAlpha.triggerStart();
    sessionBeta.triggerStart();

    // Verify independent boundary violations
    sessionAlpha.triggerBoundaryViolation(a1, 1050, 2000, 1);
    assertEquals(1, sessionAlpha.getViolations(a1));
    assertEquals(0, sessionBeta.getViolations(b1));

    sessionBeta.triggerBoundaryViolation(b1, -4900, 8000, 1);
    assertEquals(1, sessionBeta.getViolations(b1));
    assertEquals(1, sessionAlpha.getViolations(a1));

    // Disarm Alpha independently; Beta must remain active
    sessionAlpha.disarm();
    assertFalse(sessionAlpha.isActive());
    assertTrue(sessionBeta.isActive());

    sessionBeta.disarm();
    assertFalse(sessionBeta.isActive());
  }

  @Test
  @DisplayName("ActionSessionImpl onDeath and onExpire lifecycle handlers")
  void testDeathAndExpireLifecycles() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "Victim", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p2, "Winner", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 5, 64, 5)));

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.console("match ended expire"),
                    ActionDefinition.CommandAction.player("tellraw [player] match_over")
                )
            )
        ),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.console("player died [victim] won [winner]"),
                    ActionDefinition.CommandAction.player("tellraw [player] respawned")
                )
            )
        )
    );

    ActionDefinition def = new ActionDefinition(
        "duel_lifecycles", "duel_lifecycles", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.LEASH, 5L, 30.0),
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{5, 64, 5}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    session.triggerStart();

    // 1. Trigger Death
    session.triggerDeath(p1, p2);
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("player died " + p1) || c.contains("won " + p2) || c.contains("player died")));

    // Re-arm for expire and tick
    ActionSessionImpl session2 = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{5, 64, 5}),
        "world", 0, 0, null, null, null
    );
    session2.arm();
    session2.triggerStart();

    // 2. Trigger Expire directly
    session2.triggerExpire();
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("match ended expire")));

    // 3. Trigger Expire via tick timeout
    ActionDefinition defExpired = new ActionDefinition(
        "expired_duel", "expired_duel", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.LEASH, 0L, 30.0),
        lifecycle
    );
    ActionSessionImpl session3 = new ActionSessionImpl(
        UUID.randomUUID(), defExpired, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{5, 64, 5}),
        "world", 0, 0, null, null, null
    );
    session3.arm();
    session3.triggerStart();
    session3.tick();
    assertFalse(session3.isActive());
  }

  @Test
  @DisplayName("Cross-world movement triggers boundary breach even if coordinates match boundary")
  void testCrossWorldBoundaryBreach() {
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
                List.of(
                    ActionDefinition.CommandAction.action("PULL_BACK"),
                    ActionDefinition.CommandAction.console("world_violator [violator]")
                )
            )
        ),
        List.of(),
        List.of()
    );

    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 300L, 100.0);

    ActionDefinition def = new ActionDefinition(
        "world_test", "world_test", "rtp.action.world", "Cross-world test",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        lifecycle);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    session.arm();
    session.triggerStart();

    // 1. Move inside "world" within leash: should NOT breach
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 10, 64, 10));
    assertEquals(0, session.getViolations(p1));

    // 2. Move in "world_nether" at identical coordinates (10, 64, 10): MUST breach because world != "world"
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world_nether", 0, 64, 0, 10, 64, 10));
    assertEquals(1, session.getViolations(p1));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("world_violator Player1") || c.contains("world_violator " + p1)));

    // 3. Player must have been pulled back to the assigned slot in "world"
    assertEquals("world", mockPlayer.getLocation().world().name());
    assertEquals(0, mockPlayer.getLocation().getBlockX());
    assertEquals(64, mockPlayer.getLocation().getBlockY());
    assertEquals(0, mockPlayer.getLocation().getBlockZ());

    session.disarm();
  }

  @Test
  @DisplayName("Namespaced scoreboards initialized on start, updated on breach/tick, and cleaned on disarm (ADR-093 §4)")
  void testScoreboardsLifecycleAndCleanup() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 10, 64, 10);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "ScorePlayer", loc);
    serverAccessor.addPlayer(mockPlayer);

    ActionDefinition def = new ActionDefinition(
        "score_test", "score_test", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.LEASH, 100L, 20.0),
        ActionDefinition.LifecycleSpec.EMPTY);

    UUID sId = UUID.randomUUID();
    String sIdTag = "rtp_session_" + sId.toString().substring(0, 8);
    int sIdHash = Math.abs(sId.hashCode());

    ActionSessionImpl session = new ActionSessionImpl(
        sId, def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null);

    session.arm();
    session.triggerStart();

    // Verify dummy objectives added
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard objectives add rtp_session_id dummy"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard objectives add rtp_time_left dummy"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard objectives add rtp_violations dummy"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard objectives add rtp_in_bounds dummy"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard objectives add rtp_dist_sq dummy"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard objectives add rtp_alive dummy"));

    // Verify initial scores and tag
    assertTrue(serverAccessor.getExecutedCommands().contains("tag " + p1 + " add " + sIdTag));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players set " + p1 + " rtp_session_id " + sIdHash));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players set " + p1 + " rtp_violations 0"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players set " + p1 + " rtp_in_bounds 1"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players set " + p1 + " rtp_alive 1"));

    // Tick updates dynamic scoreboards (e.g. dist_sq, alive, time_left)
    session.tick();
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.startsWith("scoreboard players set " + p1 + " rtp_time_left ")));
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.startsWith("scoreboard players set " + p1 + " rtp_dist_sq ")));

    // Breach updates violation & in_bounds scoreboards
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 10, 64, 10, 50, 64, 50));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players set " + p1 + " rtp_violations 1"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players set " + p1 + " rtp_in_bounds 0"));

    // Disarm prunes tag and resets scores
    session.disarm();
    assertTrue(serverAccessor.getExecutedCommands().contains("tag " + p1 + " remove " + sIdTag));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players reset " + p1 + " rtp_session_id"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players reset " + p1 + " rtp_time_left"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players reset " + p1 + " rtp_violations"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players reset " + p1 + " rtp_in_bounds"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players reset " + p1 + " rtp_dist_sq"));
    assertTrue(serverAccessor.getExecutedCommands().contains("scoreboard players reset " + p1 + " rtp_alive"));
  }

  @Test
  @DisplayName("Non-fatal guarded execution continues subsequent commands and guarantees disarm (ADR-093 §2, S-004)")
  void testGuardedExecutionContinuesAndDisarms() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    // Lifecycle spec where the first step has a faulty/throwing action, followed by a valid action
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    // Invalid/unknown action type or command that might fail
                    ActionDefinition.CommandAction.action("THROW_ERROR_OR_UNKNOWN"),
                    ActionDefinition.CommandAction.console("guarded_step_recovered [session_id]")
                )
            )
        ),
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.action("INVALID_ON_EXPIRE"),
                    ActionDefinition.CommandAction.console("expire_completed")
                )
            )
        ),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "guarded_def", "guarded_def", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.LEASH, 60L, 20.0),
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{5, 64, 5}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    session.triggerStart();

    // Verify guarded step continued despite unknown action command
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.startsWith("guarded_step_recovered ")));

    // Trigger expire; verify session executes following commands and always disarms
    session.triggerExpire();
    assertTrue(serverAccessor.getExecutedCommands().contains("expire_completed"));
    assertFalse(session.isActive(), "Session must be disarmed even if commands encountered errors");
  }

  @Test
  @DisplayName("ActionManager routes player death to active session and triggers onDeath")
  void testActionManagerHandlePlayerDeathRouting() {
    ActionManager manager = new ActionManager();
    UUID victim = UUID.randomUUID();
    UUID killer = UUID.randomUUID();

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.console("duel_ended victim [victim] killer [winner]")
                )
            )
        )
    );

    ActionDefinition def = new ActionDefinition(
        "pvp_duel", "pvp_duel", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.LEASH, 60L, 20.0),
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(victim, killer), ActionContext.EMPTY,
        Map.of(victim, new int[]{0, 64, 0}, killer, new int[]{5, 64, 5}),
        "world", 0, 0, null, null, null
    );

    // Register active session in manager via reflection or trigger setup
    session.arm();
    try {
      java.lang.reflect.Field activeSessionsField = ActionManager.class.getDeclaredField("activeSessions");
      activeSessionsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<UUID, ActionSessionImpl> activeSessions = (Map<UUID, ActionSessionImpl>) activeSessionsField.get(manager);
      activeSessions.put(session.sessionId(), session);

      java.lang.reflect.Field p2sField = ActionManager.class.getDeclaredField("participantToSession");
      p2sField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<UUID, UUID> p2s = (Map<UUID, UUID>) p2sField.get(manager);
      p2s.put(victim, session.sessionId());
      p2s.put(killer, session.sessionId());
    } catch (Exception e) {
      fail("Failed to set up active session reflection in ActionManager", e);
    }

    // Call handlePlayerDeath for victim
    manager.handlePlayerDeath(victim, killer);

    // Check command executed
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("duel_ended victim " + victim + " killer " + killer)));
    assertFalse(session.isActive(), "Session must be disarmed on death");
  }

  @Test
  @DisplayName("Phase 6.3: SUBSPACE boundary enforcement and non-blocking safe pull-back")
  void testSubspaceBoundaryEnforcementAndPullBack() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100, 64, 100);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "SubspacePlayer", loc);
    serverAccessor.addPlayer(mockPlayer);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.action("PULL_BACK"),
                    ActionDefinition.CommandAction.console("subspace_breach [violator]")
                )
            )
        ),
        List.of(),
        List.of()
    );

    // Subspace radius = 20 blocks centered at anchor (100, 100).
    // Bounding box is [80..120, 80..120]
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 20, 10, 8, Map.of(), 3, 2);
    ActionDefinition.ConfinementSpec cSpec =
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.SUBSPACE, 120L, 0.0);

    ActionDefinition def = new ActionDefinition(
        "subspace_action", "subspace_action", "perm", "desc",
        pSpec, cSpec, lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{100, 64, 100}),
        "world", 100, 100, null, null, null
    );

    session.arm();
    session.triggerStart();

    // 1. In bounds: moving to (115, 64, 115) (abs delta = 15 <= 20) -> no violation
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 100, 64, 100, 115, 64, 115));
    assertEquals(0, session.getViolations(p1));

    // 2. Out of bounds: moving to (125, 64, 100) (abs delta = 25 > 20) -> violation + PULL_BACK
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 115, 64, 115, 125, 64, 100));
    assertEquals(1, session.getViolations(p1));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("subspace_breach " + p1) || c.contains("subspace_breach " + mockPlayer.name())));

    // Pulled back to interior assigned safe slot (100, 64, 100)
    assertEquals(100, mockPlayer.getLocation().getBlockX());
    assertEquals(64, mockPlayer.getLocation().getBlockY());
    assertEquals(100, mockPlayer.getLocation().getBlockZ());

    session.disarm();
  }

  @Test
  @DisplayName("SHAPE boundary enforcement with larger radius and shape geometry")
  void testShapeBoundaryEnforcementAndExpansion() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100, 64, 100);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "ShapePlayer", loc);
    serverAccessor.addPlayer(mockPlayer);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.action("PULL_BACK"),
                    ActionDefinition.CommandAction.console("shape_breach [violator]")
                )
            )
        ),
        List.of(),
        List.of()
    );

    // Placement radius is 64 (4c), but Confinement shape is CIRCLE with radius 80 (5c) and centerRadius 16 (1c)
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "SQUARE", 64, 10, 8, Map.of(), 3, 2);
    ActionDefinition.ConfinementSpec cSpec =
        new ActionDefinition.ConfinementSpec(
            ConfinementBoundary.SHAPE, 120L, 0.0, 0.0, 0.0, 0L, true, "CIRCLE", 80, 16);

    ActionDefinition def = new ActionDefinition(
        "shape_action", "shape_action", "perm", "desc",
        pSpec, cSpec, lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{100, 64, 100}),
        "world", 100, 100, null, null, null
    );

    session.arm();
    session.triggerStart();

    // Verify currentBoundaryRadius uses shape's radius 80, which is larger than placement's 64
    assertEquals(80.0, session.currentBoundaryRadius());

    // 1. Move to (170, 64, 100): dx = 70. 70 > placement radius (64), but <= shape boundary radius (80)!
    // Should be IN BOUNDS (demonstrating boundary radius larger than placement radius)
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 100, 64, 100, 170, 64, 100));
    assertEquals(0, session.getViolations(p1), "Radius 70 is within shape boundary 80 even though > placement 64");

    // 2. Move to (185, 64, 100): dx = 85. 85 > shape boundary radius 80 -> VIOLATION + PULL_BACK
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 170, 64, 100, 185, 64, 100));
    assertEquals(1, session.getViolations(p1));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("shape_breach " + p1) || c.contains("shape_breach " + mockPlayer.name())));

    // 3. Move to (105, 64, 100): dx = 5 < centerRadius (16) -> inner exclusion breach!
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 100, 64, 100, 105, 64, 100));
    assertEquals(2, session.getViolations(p1), "Moving within centerRadius exclusion zone must trigger breach");

    session.disarm();
  }

  @Test
  @DisplayName("Phase 6.3: REGION boundary enforcement using MemoryShape.contains(x, z)")
  void testRegionBoundaryEnforcementAndPullBack() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.api.world.RTPLocation loc =
        new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "RegionPlayer", loc);
    serverAccessor.addPlayer(mockPlayer);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.action("PULL_BACK"),
                    ActionDefinition.CommandAction.console("region_breach [violator]")
                )
            )
        ),
        List.of(),
        List.of()
    );

    ActionDefinition.ConfinementSpec cSpec =
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.REGION, 120L, 0.0);
    ActionDefinition def = new ActionDefinition(
        "region_action", "region_action", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT, cSpec, lifecycle
    );

    // Mock parent region with shape containing [-50..50, -50..50]
    io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape<?> mockShape =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape.class);
    org.mockito.Mockito.when(mockShape.contains(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(inv -> {
          int x = inv.getArgument(0);
          int z = inv.getArgument(1);
          return Math.abs(x) <= 50 && Math.abs(z) <= 50;
        });

    io.github.dailystruggle.rtp.common.selection.region.Region mockRegion =
        org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
    org.mockito.Mockito.when(mockRegion.getShape()).thenAnswer(inv -> mockShape);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, mockRegion, null, null
    );

    session.arm();
    session.triggerStart();

    // 1. Move to (40, 64, 40) inside region -> no violation
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 40, 64, 40));
    assertEquals(0, session.getViolations(p1));

    // 2. Move to (60, 64, 40) outside region -> breach + PULL_BACK
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 40, 64, 40, 60, 64, 40));
    assertEquals(1, session.getViolations(p1));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("region_breach " + p1) || c.contains("region_breach " + mockPlayer.name())));

    // Safe pull-back to interior (0, 64, 0)
    assertEquals(0, mockPlayer.getLocation().getBlockX());
    assertEquals(64, mockPlayer.getLocation().getBlockY());
    assertEquals(0, mockPlayer.getLocation().getBlockZ());

    session.disarm();
  }

  @Test
  @DisplayName("Phase 6.4: Lifecycle script dispatch covers CONSOLE, PLAYER, ACTION, FOR_EACH and fault isolation (S-004)")
  void testScriptDispatchMatrixAndFaultIsolation() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "PlayerOne", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p2, "PlayerTwo", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 10, 64, 10)));

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    // 1. CONSOLE command
                    ActionDefinition.CommandAction.console("say Session [session_id] started"),
                    // 2. FOR_EACH with PLAYER command
                    ActionDefinition.CommandAction.forEach(List.of(
                        ActionDefinition.CommandAction.player("msg [player] Welcome to the arena")
                    )),
                    // 3. FAILING command (fault isolation verification S-004)
                    ActionDefinition.CommandAction.action("THROW_FAULT"),
                    // 4. ACTION command (PULL_BACK or subsequent valid command)
                    ActionDefinition.CommandAction.console("post_fault_recovery [session_id]")
                )
            )
        ),
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.action("DISARM"),
                    ActionDefinition.CommandAction.console("disarm_dispatched")
                )
            )
        ),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "dispatch_matrix", "dispatch_matrix", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1, p2), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}, p2, new int[]{10, 64, 10}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    session.triggerStart();

    String shortSessionId = session.sessionId().toString().substring(0, 8);

    // Verify CONSOLE dispatched with placeholder
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.startsWith("say Session " + shortSessionId + " started")));

    // Verify FOR_EACH executed for both p1 and p2
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("msg " + p1 + " Welcome to the arena") || c.contains("msg PlayerOne Welcome to the arena")));
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("msg " + p2 + " Welcome to the arena") || c.contains("msg PlayerTwo Welcome to the arena")));

    // Verify recovery after throwing/unknown action
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.equals("post_fault_recovery " + shortSessionId)));

    // Expire/disarm execution
    session.triggerExpire();
    assertTrue(serverAccessor.getExecutedCommands().contains("disarm_dispatched"));
    assertFalse(session.isActive(), "Session must disarm cleanly");
  }

  @Test
  @DisplayName("Zero-duration action auto-disarms after triggerStart (one-shot placement cleanup)")
  void testZeroDurationAutoDisarms() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "ZeroPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));

    ActionDefinition.ConfinementSpec zeroDurationConfinement =
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.SUBSPACE, 0L, 0.0);
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.console("oneshot_executed [player]"))
            )
        ),
        List.of(),
        List.of(),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "zero_action", "zero_action", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        zeroDurationConfinement,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    assertTrue(session.isActive());

    // triggerStart should execute onStart and immediately auto-disarm because duration is 0s
    session.triggerStart();

    assertTrue(serverAccessor.getExecutedCommands().contains("oneshot_executed " + p1)
            || serverAccessor.getExecutedCommands().contains("oneshot_executed ZeroPlayer"),
        "Expected 'oneshot_executed " + p1 + "' or 'oneshot_executed ZeroPlayer' in " + serverAccessor.getExecutedCommands());
    assertFalse(session.isActive(), "Session with 0s duration must automatically disarm after onStart");
    // Verify cleanup commands ran (reset scoreboards / tags)
    String sessionTag = "rtp_session_" + session.sessionId().toString().substring(0, 8);
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tag " + p1 + " remove " + sessionTag)
            || c.contains("tag ZeroPlayer remove " + sessionTag)));
  }

  @Test
  @DisplayName("MemoryTracker active GC sweep force-disarms stalled ActionSession")
  void testMemoryTrackerSweepsStalledActionSession() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition def = new ActionDefinition(
        "stalled_action", "stalled_action", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        new ActionDefinition.ConfinementSpec(ConfinementBoundary.SUBSPACE, 120L, 0.0),
        ActionDefinition.LifecycleSpec.EMPTY
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null
    );

    // Track session directly with a 0ms budget so it is immediately considered stalled/leaking
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(session, "ActionSession-" + session.sessionId(), 0L);
    session.arm();
    assertTrue(session.isActive());

    // Run active GC sweep via runDiagnostics
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.runDiagnostics();

    // Verify session was force-disarmed
    assertFalse(session.isActive(), "Stalled ActionSession must be force-disarmed by MemoryTracker GC sweep");
  }

  @Test
  @DisplayName("Player message command without target falls back cleanly to sendMessage without throwing")
  void testPlayerMessageFallbackToSendMessage() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    serverAccessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "MsgPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.player("msg &aArrived near spawn."))
            )
        ),
        List.of(),
        List.of(),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "msg_action", "msg_action", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    // Should not throw or crash
    assertDoesNotThrow(session::triggerStart);
    session.disarm();
  }

  @Test
  @DisplayName("Stationary player breached by shrinking worldborder triggers violation and declarative damage")
  void testStationaryPlayerBreachedByShrinkingBorder() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    // Place player at (40, 64, 0)
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p1, "StationaryPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 40, 64, 0));
    serverAccessor.addPlayer(mockPlayer);

    // Initial size 100 (radius 50), shrinks to 20 (radius 10) over 10 seconds.
    // At t=0, radius=50, player at 40 is inside!
    // Declarative damage: 4.0 damage, buffer 0.0, interval 1s.
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH,
        300L,
        50.0,
        100.0,
        20.0,
        10L,
        true,
        "CIRCLE",
        50,
        0,
        4.0,
        0.0,
        1L
    );

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(),
        List.of(
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.console("breach_detected [violator]"))
            )
        ),
        List.of(),
        List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "shrink_duel", "shrink_duel", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    session.triggerStart();

    // Verify WorldBorder packet sent with damage parameters
    assertFalse(serverAccessor.getSentWorldBorders().isEmpty());
    MockRTPServerAccessor.SentWorldBorder sentBorder = serverAccessor.getSentWorldBorders().get(0);
    assertEquals(4.0, sentBorder.damageAmount());
    assertEquals(0.0, sentBorder.damageBuffer());

    // Initially at t=0, radius=50, player is at 40 -> in bounds!
    session.tick();
    assertEquals(0, session.getViolations(p1));
    assertTrue(serverAccessor.getAppliedDamages().isEmpty());

    // Advance time by 10 seconds so border shrinks to radius 10 (player at 40 is now 30 blocks outside)
    // We simulate elapsed time by moving startTimeMillis back by 10_000ms
    try {
      java.lang.reflect.Field startField = ActionSessionImpl.class.getDeclaredField("startTimeMillis");
      startField.setAccessible(true);
      startField.setLong(session, System.currentTimeMillis() - 10_000L);
    } catch (Exception e) {
      fail(e);
    }

    // Now player has stood completely still, but the border moved past them.
    // Continuous confinement tick should catch the breach without any PlayerMoveEvent fired!
    session.tick();

    assertEquals(1, session.getViolations(p1));
    assertTrue(serverAccessor.getExecutedCommands().contains("breach_detected " + p1)
        || serverAccessor.getExecutedCommands().contains("breach_detected StationaryPlayer"));
    // Verify declarative damage was applied
    assertEquals(1, serverAccessor.getAppliedDamages().size());
    assertEquals(4.0, serverAccessor.getAppliedDamages().get(0).amount());
    assertEquals(p1, serverAccessor.getAppliedDamages().get(0).playerId());

    session.disarm();
  }

  @Test
  @DisplayName("Exceeding maxDistanceOutside triggers immediate pull-back on move and continuous tick")
  void testMaxDistanceOutsideTriggersPullBack() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p1, "Escapee", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(mockPlayer);

    // Confinement: radius 20, maxDistanceOutside 10.0 (anything beyond 30 blocks distance triggers pull-back)
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH,
        300L,
        20.0,
        0.0,
        0.0,
        0L,
        true,
        "CIRCLE",
        20,
        0,
        0.0,
        0.0,
        1L,
        10.0
    );

    ActionDefinition def = new ActionDefinition(
        "escape_duel", "escape_duel", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        ActionDefinition.LifecycleSpec.EMPTY
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    session.triggerStart();

    // 1. Player moves slightly outside border (dist = 25 > 20, but distOutside = 5 <= 10)
    // No automatic hard limit pull-back, player remains at (25, 64, 0)
    mockPlayer.setLocation(new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 25, 64, 0));
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 25, 64, 0));
    assertEquals(25, mockPlayer.getLocation().x());

    // 2. Player attempts to walk away farther (dist = 35 > 20, distOutside = 15 > 10)
    // Move event triggers immediate pull-back to anchor slot (0, 64, 0)
    mockPlayer.setLocation(new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 35, 64, 0));
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 25, 64, 0, 35, 64, 0));
    assertEquals(0, mockPlayer.getLocation().x());

    // 3. Test continuous confinement check: if a player is teleported/pushed to x=40 (distOutside=20 > 10)
    mockPlayer.setLocation(new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 40, 64, 0));
    session.tick();
    // Continuous tick should detect distOutside > maxDistanceOutside and pull back
    assertEquals(0, mockPlayer.getLocation().x());

    session.disarm();
  }

  @Test
  @DisplayName("Static initial boundary ceiling and declarative outsideActions execution")
  void testStaticInitialCeilingAndOutsideActions() {
    UUID p1 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer mockPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
            p1, "EscapeArtist", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(mockPlayer);

    // Initial size 100 (radius 50), shrinks to 20 (radius 10) over 10s. maxDistanceOutside = 5.0.
    // Declarative outsideActions executes console command: "kill [violator]"
    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.SUBSPACE,
        300L,
        50.0,
        100.0,
        20.0,
        10L,
        true,
        "SQUARE",
        50,
        0,
        0.0,
        0.0,
        1L,
        5.0,
        List.of(ActionDefinition.CommandAction.console("kill [player_name]"))
    );

    ActionDefinition def = new ActionDefinition(
        "escape_test", "escape_test", "perm", "desc",
        ActionDefinition.PlacementSpec.DEFAULT,
        confinement,
        ActionDefinition.LifecycleSpec.EMPTY
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), def, List.of(p1), ActionContext.EMPTY,
        Map.of(p1, new int[]{0, 64, 0}),
        "world", 0, 0, null, null, null
    );

    session.arm();
    session.triggerStart();

    // Verify initialBoundaryRadius is 50.0
    assertEquals(50.0, session.initialBoundaryRadius());

    // 1. Player is inside initial boundary (x=45 <= 50) -> outsideActions does NOT fire
    mockPlayer.setLocation(new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 45, 64, 0));
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 45, 64, 0));
    assertFalse(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("kill EscapeArtist")));

    // 2. Player breaches past initialBoundaryRadius + maxDistanceOutside (x=60 > 50 + 5 = 55)
    // Moving outside the initial boundary immediately triggers declarative outsideActions!
    mockPlayer.setLocation(new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 60, 64, 0));
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 45, 64, 0, 60, 64, 0));
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("kill EscapeArtist") || c.contains("kill " + p1)));

    session.disarm();
  }
}
