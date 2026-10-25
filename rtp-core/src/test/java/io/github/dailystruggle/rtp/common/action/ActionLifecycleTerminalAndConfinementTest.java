package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004 / S-005 / ADR-093 / ADR-097:
 * Tests for action terminal lifecycle execution, atomic transitions, confinement boundaries,
 * and stationary placement cache pre-warming.
 */
class ActionLifecycleTerminalAndConfinementTest {

  @TempDir
  File tempDir;

  private MockRTPServerAccessor serverAccessor;
  private io.github.dailystruggle.rtp.common.mock.MockRTPScheduler mockScheduler;
  private ActionManager actionManager;

  @BeforeEach
  void setUp() {
    serverAccessor = RTPTestSetup.install(tempDir);
    mockScheduler = serverAccessor.getMockScheduler();
    RTPRuntime.setScheduler(mockScheduler);
    actionManager = new ActionManager();
    ActionServices.setActionManager(actionManager);
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = actionManager;
  }

  @AfterEach
  void tearDown() {
    ActionServices.setActionManager(null);
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = null;
    RTPTestSetup.cleanUp();
  }

  @Test
  @DisplayName("Delayed terminal lifecycle step executes after session disarms")
  void testDelayedTerminalStepExecutesAfterDisarm() {
    UUID p1 = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer player = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    List<String> executedCommands = new ArrayList<>();
    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        executedCommands.add(commandLine);
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(player);

    ActionDefinition.LifecycleStep delayedStep = new ActionDefinition.LifecycleStep(
        Collections.emptyMap(),
        List.of(ActionDefinition.CommandAction.console("give [player] diamond 5")),
        1L // 1 second delay
    );

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(delayedStep), // onExpire with delay
        Collections.emptyList()
    );

    ActionDefinition def = new ActionDefinition(
        "test_delayed",
        "test_delayed",
        "",
        "",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    Map<UUID, int[]> slots = Map.of(p1, new int[] {0, 64, 0});
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(),
        def,
        List.of(p1),
        ActionContext.EMPTY,
        slots,
        "world",
        0,
        0,
        null,
        null,
        Collections.emptyMap()
    );

    session.arm();
    session.triggerExpire();

    // Session is now disarmed
    assertFalse(session.isActive(), "Session must be disarmed after triggerExpire");

    // The delayed task was scheduled via MockRTPScheduler.runTaskLater. Run pending tasks:
    for (Object task : mockScheduler.getScheduledTasks()) {
      if (task != null) {
        try {
          java.lang.reflect.Field rf = task.getClass().getDeclaredField("runnable");
          rf.setAccessible(true);
          Runnable r = (Runnable) rf.get(task);
          r.run();
        } catch (Throwable ignored) {
        }
      }
    }

    assertTrue(
        executedCommands.stream().anyMatch(c -> c.contains("give") && c.contains("diamond")),
        "Delayed command in onExpire must execute even after session is disarmed"
    );
  }

  @Test
  @DisplayName("Delayed terminal lifecycle step aborts if player went offline")
  void testDelayedTerminalStepAbortsIfPlayerOffline() {
    UUID p1 = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer player = new MockRTPPlayer(p1, "OfflinePlayer", new RTPLocation(world, 0, 64, 0)) {
      @Override
      public boolean isOnline() {
        return false;
      }
    };
    serverAccessor.addPlayer(player);

    List<String> executedCommands = new ArrayList<>();
    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        executedCommands.add(commandLine);
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(player);

    ActionDefinition.LifecycleStep delayedStep = new ActionDefinition.LifecycleStep(
        Collections.emptyMap(),
        List.of(ActionDefinition.CommandAction.console("give [player] diamond 5")),
        1L
    );

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(delayedStep),
        Collections.emptyList()
    );

    ActionDefinition def = new ActionDefinition(
        "test_offline_delayed",
        "test_offline_delayed",
        "",
        "",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(),
        def,
        List.of(p1),
        ActionContext.EMPTY,
        Map.of(p1, new int[] {0, 64, 0}),
        "world",
        0,
        0,
        null,
        null,
        Collections.emptyMap()
    );

    session.arm();
    session.triggerExpire();

    // Run scheduled tasks
    for (Object task : mockScheduler.getScheduledTasks()) {
      if (task != null) {
        try {
          java.lang.reflect.Field rf = task.getClass().getDeclaredField("runnable");
          rf.setAccessible(true);
          Runnable r = (Runnable) rf.get(task);
          r.run();
        } catch (Throwable ignored) {
        }
      }
    }

    assertFalse(
        executedCommands.stream().anyMatch(c -> c.contains("diamond")),
        "Delayed command must not run if player went offline"
    );
  }

  @Test
  @DisplayName("Atomic ending CAS prevents concurrent terminal executions")
  void testAtomicEndingGuardPreventsConcurrentTerminalExecutions() {
    UUID p1 = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer player = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    AtomicInteger expireCount = new AtomicInteger(0);
    AtomicInteger deathCount = new AtomicInteger(0);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(Collections.emptyMap(), List.of(
            ActionDefinition.CommandAction.console("expire")
        ))),
        List.of(new ActionDefinition.LifecycleStep(Collections.emptyMap(), List.of(
            ActionDefinition.CommandAction.console("death")
        )))
    );

    ActionDefinition def = new ActionDefinition(
        "test_cas",
        "test_cas",
        "",
        "",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        if ("expire".equals(commandLine)) expireCount.incrementAndGet();
        if ("death".equals(commandLine)) deathCount.incrementAndGet();
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(player);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(),
        def,
        List.of(p1),
        ActionContext.EMPTY,
        Map.of(p1, new int[] {0, 64, 0}),
        "world",
        0,
        0,
        null,
        null,
        Collections.emptyMap()
    );

    session.arm();

    // Call triggerExpire and triggerDeath back to back
    session.triggerExpire();
    session.triggerDeath(p1, null);

    assertEquals(1, expireCount.get(), "Expire must have executed once");
    assertEquals(0, deathCount.get(), "Death must not execute after expire has claimed ending state");
  }

  @Test
  @DisplayName("Expire gates are evaluated once per participant")
  void testExpireGatesAreEvaluatedPerParticipant() {
    UUID nearId = UUID.randomUUID();
    UUID farId = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer nearPlayer = new MockRTPPlayer(nearId, "Near", new RTPLocation(world, 1, 64, 0));
    MockRTPPlayer farPlayer = new MockRTPPlayer(farId, "Far", new RTPLocation(world, 20, 64, 0));
    List<String> commands = new ArrayList<>();
    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        commands.add(commandLine);
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(nearPlayer);
    customAccessor.addPlayer(farPlayer);

    ActionDefinition.LifecycleStep nearStep = new ActionDefinition.LifecycleStep(
        Map.of("spatial", Map.of("distance", "< 5")),
        List.of(ActionDefinition.CommandAction.forEach(List.of(
            ActionDefinition.CommandAction.console("say near [player]")))));
    ActionDefinition.LifecycleStep farStep = new ActionDefinition.LifecycleStep(
        Map.of("spatial", Map.of("distance", ">= 5")),
        List.of(ActionDefinition.CommandAction.forEach(List.of(
            ActionDefinition.CommandAction.console("say far [player]")))));
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(), Collections.emptyList(), List.of(nearStep, farStep), Collections.emptyList());
    ActionDefinition definition = new ActionDefinition(
        "test_expire_gates", "test_expire_gates", "", "",
        ActionDefinition.PlacementSpec.DEFAULT, ActionDefinition.ConfinementSpec.DEFAULT, lifecycle);
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), definition, List.of(nearId, farId), ActionContext.EMPTY,
        Map.of(nearId, new int[] {1, 64, 0}, farId, new int[] {20, 64, 0}),
        "world", 0, 0, null, null, Collections.emptyMap());

    session.arm();
    session.triggerExpire();

    assertEquals(List.of("say near Near", "say far Far"), commands);
  }

  @Test
  @DisplayName("Death gate rejects a terminal step when its condition fails")
  void testDeathGateRejectsFailedCondition() {
    UUID victimId = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer victim = new MockRTPPlayer(victimId, "Victim", new RTPLocation(world, 20, 64, 0));
    List<String> commands = new ArrayList<>();
    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        commands.add(commandLine);
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(victim);

    ActionDefinition.LifecycleStep deathStep = new ActionDefinition.LifecycleStep(
        Map.of("spatial", Map.of("distance", "< 5")),
        List.of(ActionDefinition.CommandAction.console("say death [victim]")));
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), List.of(deathStep));
    ActionDefinition definition = new ActionDefinition(
        "test_death_gate", "test_death_gate", "", "",
        ActionDefinition.PlacementSpec.DEFAULT, ActionDefinition.ConfinementSpec.DEFAULT, lifecycle);
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), definition, List.of(victimId), ActionContext.EMPTY,
        Map.of(victimId, new int[] {20, 64, 0}), "world", 0, 0, null, null, Collections.emptyMap());

    session.arm();
    session.triggerDeath(victimId, null);

    assertTrue(commands.isEmpty(), "A failed death gate must suppress its command");
  }

  @Test
  @DisplayName("Cancel gate is evaluated against the canceller")
  void testCancelGateUsesCancellerContext() {
    UUID cancellerId = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer canceller = new MockRTPPlayer(cancellerId, "Canceller", new RTPLocation(world, 20, 64, 0));
    List<String> commands = new ArrayList<>();
    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        commands.add(commandLine);
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(canceller);

    ActionDefinition.LifecycleStep cancelStep = new ActionDefinition.LifecycleStep(
        Map.of("spatial", Map.of("distance", "< 5")),
        List.of(ActionDefinition.CommandAction.console("say cancel [player]")));
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
        Collections.emptyList(), List.of(cancelStep));
    ActionDefinition definition = new ActionDefinition(
        "test_cancel_gate", "test_cancel_gate", "", "",
        ActionDefinition.PlacementSpec.DEFAULT, ActionDefinition.ConfinementSpec.DEFAULT, lifecycle);
    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(), definition, List.of(cancellerId), ActionContext.EMPTY,
        Map.of(cancellerId, new int[] {20, 64, 0}), "world", 0, 0, null, null, Collections.emptyMap());

    session.arm();
    session.triggerCancel(cancellerId);

    assertTrue(commands.isEmpty(), "A cancel gate must use the canceller's context");
  }

  @Test
  @DisplayName("In-to-out violation transitions do not multi-count while outside")
  void testInToOutViolationTransitions() {
    UUID p1 = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer player = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    AtomicInteger violationCount = new AtomicInteger(0);
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(Collections.emptyMap(), List.of(
            ActionDefinition.CommandAction.console("violation")
        ))),
        Collections.emptyList(),
        Collections.emptyList()
    );

    ActionDefinition.PlacementSpec placement = new ActionDefinition.PlacementSpec(
        true, "default", "CIRCLE", 16, 0, 10, 10, Collections.emptyMap(), 5, 0);

    ActionDefinition.ConfinementSpec confinement = new ActionDefinition.ConfinementSpec(
        ConfinementBoundary.LEASH, 60L, 20.0);

    ActionDefinition def = new ActionDefinition(
        "test_violations",
        "test_violations",
        "",
        "",
        placement,
        confinement,
        lifecycle
    );

    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        if ("violation".equals(commandLine)) violationCount.incrementAndGet();
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(player);

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(),
        def,
        List.of(p1),
        ActionContext.EMPTY,
        Map.of(p1, new int[] {0, 64, 0}),
        "world",
        0,
        0,
        null,
        null,
        Collections.emptyMap()
    );

    session.arm();

    // 1. Move inside bounds (x=5, z=5, leash radius = 20)
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 0, 64, 0, 5, 64, 5)
    );
    assertEquals(0, session.getViolations(p1), "In-bounds move must not trigger violation");

    // 2. Move out of bounds (x=30, z=0) -> in-to-out transition!
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 5, 64, 5, 30, 64, 0)
    );
    assertEquals(1, session.getViolations(p1), "In-to-out transition must trigger 1 violation");

    // 3. Move further out of bounds (x=35, z=0) -> outside-to-outside, should not increment violation!
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1, "world", 30, 64, 0, 35, 64, 0)
    );
    assertEquals(1, session.getViolations(p1), "Continuous outside movement must not increment violation count again");
  }

  @Test
  @DisplayName("ACTION: ELIMINATE restores survival game mode and resolves 1v1 match")
  void testActionEliminateRestoresSurvivalAndResolvesMatch() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    MockRTPPlayer player1 = new MockRTPPlayer(p1, "Player1", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer player2 = new MockRTPPlayer(p2, "Player2", new RTPLocation(world, 10, 64, 10));

    List<String> commands = new ArrayList<>();
    MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public boolean executeCommand(UUID senderId, String commandLine) {
        commands.add(commandLine);
        return true;
      }
    };
    RTPRuntime.setServerAccessor(customAccessor);
    customAccessor.addPlayer(player1);
    customAccessor.addPlayer(player2);

    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        List.of(new ActionDefinition.LifecycleStep(Collections.emptyMap(), List.of(
            ActionDefinition.CommandAction.console("match_won_by_[winner]")
        )))
    );

    ActionDefinition def = new ActionDefinition(
        "test_eliminate",
        "test_eliminate",
        "",
        "",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle
    );

    ActionSessionImpl session = new ActionSessionImpl(
        UUID.randomUUID(),
        def,
        List.of(p1, p2),
        ActionContext.EMPTY,
        Map.of(p1, new int[] {0, 64, 0}, p2, new int[] {10, 64, 10}),
        "world",
        0,
        0,
        null,
        null,
        Collections.emptyMap()
    );

    session.arm();

    // Eliminate p1
    session.eliminate(p1);

    assertTrue(
        commands.stream().anyMatch(c -> c.contains("gamemode survival") && c.contains(p1.toString())),
        "Eliminating participant must restore survival game mode"
    );

    assertTrue(
        commands.stream().anyMatch(c -> c.startsWith("match_won_by_") && (c.contains("Player2") || c.contains(p2.toString()))),
        "Eliminating one of two participants must trigger death/win resolution for the remaining player"
    );
  }

  @Test
  @DisplayName("ActionCacheWarmTask preserves centerRadius and derives slot count from players gate")
  void testActionCacheWarmTaskDerivesSlotCountAndCenterRadius() {
    // 1. Definition with players: ">= 4" gate
    ActionDefinition def4 = new ActionDefinition(
        "test_4p",
        "test_4p",
        "",
        "",
        new ActionDefinition.PlacementSpec(true, "default", "CIRCLE", 64, 16, 24, 128, Map.of("centerRadius", 16), 1, 0),
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        ActionDefinition.CommandSpec.EMPTY,
        List.of(Map.of("players", ">= 4"))
    );

    int slots = ActionCacheWarmTask.resolveSlotCount(def4);
    assertEquals(4, slots, "resolveSlotCount must detect 'players: >= 4' and return 4");

    // 2. Definition with default 2
    ActionDefinition def2 = new ActionDefinition(
        "test_2p",
        "test_2p",
        "",
        "",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY
    );

    assertEquals(2, ActionCacheWarmTask.resolveSlotCount(def2), "resolveSlotCount must default to 2 without player gate");
  }
}
