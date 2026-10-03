package io.github.dailystruggle.rtp.common.commands.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionCommandTest {

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;

  @BeforeEach
  void setUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    serverAccessor = io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    actionManager = new ActionManager();
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = actionManager;
  }

  @Test
  @DisplayName("ActionCommand executes action and triggers service")
  void testActionCommandExecution() {
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "duel", "rtp.command.duel", "Challenge a duel", List.of("fight"));

    ActionDefinition def = new ActionDefinition(
        "duel", "duel", "rtp.action.duel", "Duel Action",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec);

    actionManager.registerAction(def);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID p1Id = UUID.randomUUID();
    UUID p2Id = UUID.randomUUID();

    MockRTPPlayer p1 = new MockRTPPlayer(p1Id, "PlayerOne", new RTPLocation(world, 100, 64, 100));
    MockRTPPlayer p2 = new MockRTPPlayer(p2Id, "PlayerTwo", new RTPLocation(world, 105, 64, 105));
    p1.setPermission("rtp.command.duel", true);
    p1.setPermission("rtp.other", true);
    serverAccessor.addPlayer(p1);
    serverAccessor.addPlayer(p2);

    ActionCommand cmd = new ActionCommand(def);
    assertEquals("duel", cmd.name());
    assertEquals("rtp.command.duel", cmd.permission());
    assertEquals("Challenge a duel", cmd.description());

    // Execute with p1 as caller and p2 as parameter
    Map<String, List<String>> params = Map.of("player", List.of("PlayerTwo"));
    boolean handled = cmd.onCommand(p1Id, params, null);
    assertTrue(handled);

    // Verify command parameter lookup has "player"
    assertTrue(cmd.getParameterLookup().containsKey("player"));
  }

  @Test
  @DisplayName("Mutual Challenge Reciprocity: Symmetrical challenge resolves sender and target tokens")
  void testMutualChallengeReciprocityTokens() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();

    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 10, 64, 10));
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", new RTPLocation(world, 20, 64, 20));
    alice.setPermission("rtp.command.challenge", true);
    bob.setPermission("rtp.command.challenge", true);
    serverAccessor.addPlayer(alice);
    serverAccessor.addPlayer(bob);

    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "challenge", "rtp.command.challenge", "Challenge duel", List.of("duelreq"));

    ActionDefinition def = new ActionDefinition(
        "challenge", "challenge", "rtp.action.challenge", "Challenge Action",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        new ActionDefinition.LifecycleSpec(
            List.of(
                new ActionDefinition.LifecycleStep(
                    Map.of(),
                    List.of(
                        ActionDefinition.CommandAction.console("tag [sender] add rtp_chal_[target]"),
                        ActionDefinition.CommandAction.console("tellraw [target] [sender]")
                    )
                )
            ),
            List.of(), List.of(), List.of()
        ),
        cmdSpec);

    actionManager.registerAction(def);

    ActionCommand cmd = new ActionCommand(def);
    Map<String, List<String>> params = Map.of("player", List.of("Bob"));
    boolean handled = cmd.onCommand(aliceId, params, null);
    assertTrue(handled);

    // Trigger lifecycle session directly to verify [sender]=Alice and [target]=Bob substitution
    io.github.dailystruggle.rtp.common.action.ActionSessionImpl session =
        new io.github.dailystruggle.rtp.common.action.ActionSessionImpl(
            UUID.randomUUID(), def, List.of(aliceId, bobId), ActionContext.EMPTY,
            Map.of(aliceId, new int[]{10, 64, 10}, bobId, new int[]{20, 64, 20}),
            "world", 15, 15, null, null, null);

    session.triggerStart();

    // Verify console command received [sender]=Alice and [target]=Bob
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tag Alice add rtp_chal_Bob") || c.contains("tag " + aliceId + " add rtp_chal_" + bobId)));
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tellraw Bob Alice") || c.contains("tellraw " + bobId + " " + aliceId)));

    session.disarm();
  }

  @Test
  @DisplayName("Mutual Challenge Reciprocity: 30-second delay cleans up expired challenge tags")
  void testMutualChallengeAutoExpiry() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();

    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 10, 64, 10));
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", new RTPLocation(world, 20, 64, 20));
    serverAccessor.addPlayer(alice);
    serverAccessor.addPlayer(bob);

    // Lifecycle with immediate challenge tag and a 30s delayed expiration step
    ActionDefinition.LifecycleSpec lifecycle = new ActionDefinition.LifecycleSpec(
        List.of(
            // Initial challenge dispatch
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(ActionDefinition.CommandAction.console("tag [sender] add rtp_chal_[target]")),
                0L
            ),
            // Delayed 30-second cleanup
            new ActionDefinition.LifecycleStep(
                Map.of(),
                List.of(
                    ActionDefinition.CommandAction.console("tag [sender] remove rtp_chal_[target]"),
                    ActionDefinition.CommandAction.console("tellraw [sender] expired")
                ),
                30L
            )
        ),
        List.of(), List.of(), List.of()
    );

    ActionDefinition def = new ActionDefinition(
        "challenge_expiry", "challenge_expiry", "rtp.action.chal", "Challenge Expiry",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        lifecycle);

    io.github.dailystruggle.rtp.common.action.ActionSessionImpl session =
        new io.github.dailystruggle.rtp.common.action.ActionSessionImpl(
            UUID.randomUUID(), def, List.of(aliceId, bobId), ActionContext.EMPTY,
            Map.of(aliceId, new int[]{10, 64, 10}, bobId, new int[]{20, 64, 20}),
            "world", 15, 15, null, null, null);

    session.arm();
    session.triggerStart();

    // Verify initial tag applied
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tag Alice add rtp_chal_Bob") || c.contains("tag " + aliceId + " add rtp_chal_" + bobId)));
    assertFalse(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("expired")));

    // Advance mock scheduler by 600 ticks (30 seconds)
    serverAccessor.getMockScheduler().tick(600L);

    // Verify cleanup executed upon timeout
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tag Alice remove rtp_chal_Bob") || c.contains("tag " + aliceId + " remove rtp_chal_" + bobId)));
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tellraw Alice expired") || c.contains("tellraw " + aliceId + " expired")));

    session.disarm();
  }

  @Test
  @DisplayName("ActionCommand checks permissions and rejects unauthorized players")
  void testActionCommandPermissionDenied() {
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "secret", "rtp.command.secret", "Secret action", List.of());

    ActionDefinition def = new ActionDefinition(
        "secret", "secret", "rtp.action.secret", "Secret",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID pId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(pId, "User", new RTPLocation(world, 0, 64, 0));
    player.setPermission("secret", false);
    player.setPermission("rtp.command.secret", false);
    player.setPermission("rtp.*", false);
    serverAccessor.addPlayer(player);

    ActionCommand cmd = new ActionCommand(def);
    boolean handled = cmd.onCommand(pId, Collections.emptyMap(), null);
    assertTrue(handled);

    assertTrue(player.sentMessages.stream()
        .anyMatch(m -> m.contains(PlayerMessages.noPerms.name())));
  }

  @Test
  @DisplayName("ActionSubCmd syncs subcommands from ActionManager")
  void testActionSubCmdSync() {
    ActionDefinition def = new ActionDefinition(
        "arena", "arena", "rtp.action.arena", "Arena Action",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);

    actionManager.registerAction(def);

    ActionSubCmd subCmd = new ActionSubCmd(null);
    assertEquals("action", subCmd.name());
    assertEquals("rtp.action", subCmd.permission());

    subCmd.syncActions();
    assertTrue(subCmd.getCommandLookup().containsKey("ARENA"));

    // Verify tab complete on /rtp action does not cause StackOverflow or recursion
    List<String> suggestions = subCmd.onTabComplete(UUID.randomUUID(), perm -> true, new String[]{""});
    assertNotNull(suggestions);
    assertTrue(suggestions.contains("arena") || suggestions.contains("help"));

    // Test operator targeting another operator who has rtp.notme permission
    RTPWorld<?> testWorld = serverAccessor.getRTPWorld("world");
    UUID opCallerId = UUID.randomUUID();
    MockRTPPlayer opCaller = new MockRTPPlayer(opCallerId, "OpCaller", new RTPLocation(testWorld, 0, 64, 0));
    opCaller.setPermission("rtp.*", true);
    opCaller.setPermission("rtp.other", true);
    serverAccessor.addPlayer(opCaller);

    UUID opTargetId = UUID.randomUUID();
    MockRTPPlayer opTarget = new MockRTPPlayer(opTargetId, "OpTarget", new RTPLocation(testWorld, 20, 64, 20));
    opTarget.setPermission("rtp.notme", true); // Target has wildcard / notme
    serverAccessor.addPlayer(opTarget);

    io.github.dailystruggle.commandsapi.common.CommandParameter playerParam =
        new io.github.dailystruggle.rtp.common.commands.ServerAccessorCommandParameters().playerParameter();
    assertTrue(playerParam.isRelevant.apply(opCallerId, "OpTarget"),
        "Operator caller with rtp.* must be allowed to target player with rtp.notme");

    // Test non-operator caller targeting player with rtp.notme is rejected
    UUID regularCallerId = UUID.randomUUID();
    MockRTPPlayer regularCaller = new MockRTPPlayer(regularCallerId, "RegularCaller", new RTPLocation(testWorld, 0, 64, 0));
    regularCaller.setPermission("rtp.other", true);
    regularCaller.setPermission("rtp.*", false);
    regularCaller.setPermission("rtp.notme.bypass", false);
    serverAccessor.addPlayer(regularCaller);

    assertFalse(playerParam.isRelevant.apply(regularCallerId, "OpTarget"),
        "Regular caller without rtp.* must be rejected by rtp.notme");

    // Test onCommand usage and permissions
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID senderId = UUID.randomUUID();
    MockRTPPlayer sender = new MockRTPPlayer(senderId, "Sender", new RTPLocation(world, 0, 64, 0));
    sender.setPermission("rtp.action", true);
    serverAccessor.addPlayer(sender);
    assertTrue(subCmd.onCommand(senderId, Collections.emptyMap(), null));

    sender.setPermission("rtp.action", false);
    sender.setPermission("rtp.*", false);
    assertTrue(subCmd.onCommand(senderId, Collections.emptyMap(), null));
  }

  @Test
  @DisplayName("Example action: create and execute faction-anchored command via ActionCommand")
  void testFactionAnchoredActionCommand() {
    // 1. Define command spec for /rtp faction
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "faction", "rtp.command.faction", "Teleport to faction claim anchor", List.of("f"));

    // 2. Define placement spec with anchor: faction
    ActionDefinition.PlacementSpec placementSpec = new ActionDefinition.PlacementSpec(
        "default", "CIRCLE", 64, 16, 8, Map.of("anchor", "faction", "radius", 50), 32, 4
    );

    // 3. Register action definition in ActionManager
    ActionDefinition def = new ActionDefinition(
        "faction", "faction", "rtp.command.faction", "Faction RTP",
        placementSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec
    );
    actionManager.registerAction(def);

    // 4. Create and synchronize command tree via ActionSubCmd
    ActionSubCmd actionSubCmd = new ActionSubCmd(null);
    actionSubCmd.syncActions();
    assertTrue(actionSubCmd.getCommandLookup().containsKey("FACTION"));

    // 5. Build standalone / direct ActionCommand
    ActionCommand cmd = new ActionCommand(def);
    assertEquals("faction", cmd.name());
    assertEquals("rtp.command.faction", cmd.permission());
    assertEquals("Teleport to faction claim anchor", cmd.description());

    // 6. Setup player with permissions
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID playerId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(playerId, "FactionLeader", new RTPLocation(world, 0, 64, 0));
    player.setPermission("rtp.command.faction", true);
    serverAccessor.addPlayer(player);

    // 7. Execute command
    boolean handled = cmd.onCommand(playerId, Collections.emptyMap(), null);
    assertTrue(handled);
  }

  @Test
  @DisplayName("Action Command Feedback: uncached action with rtp.unqueued sends chunkLoading or executes on-demand")
  void testUncachedActionWithUnqueuedPermission() {
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "arena", "rtp.command.arena", "Arena teleport", List.of());
    ActionDefinition def = new ActionDefinition(
        "arena", "arena", "rtp.action.arena", "Arena",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec);
    actionManager.registerAction(def);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID playerId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(playerId, "FastPlayer", new RTPLocation(world, 0, 64, 0));
    player.setPermission("rtp.command.arena", true);
    player.setPermission("rtp.unqueued", true);
    serverAccessor.addPlayer(player);

    ActionCommand cmd = new ActionCommand(def);
    boolean handled = cmd.onCommand(playerId, Collections.emptyMap(), null);
    assertTrue(handled);

    // Player with rtp.unqueued should receive chunk loading / setup message, never raw INSUFFICIENT_SAFE_SLOTS
    assertFalse(player.sentMessages.stream().anyMatch(m -> m.contains("INSUFFICIENT_SAFE_SLOTS")),
        "Player with rtp.unqueued must not receive raw INSUFFICIENT_SAFE_SLOTS");
  }

  @Test
  @DisplayName("Action Command Feedback: uncached action without rtp.unqueued notifies queueUpdate")
  void testUncachedActionWithoutUnqueuedNotifiesQueueUpdate() {
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "arena", "rtp.command.arena", "Arena teleport", List.of());
    ActionDefinition def = new ActionDefinition(
        "arena", "arena", "rtp.action.arena", "Arena",
        ActionDefinition.PlacementSpec.DEFAULT,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec);
    actionManager.registerAction(def);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID playerId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(playerId, "QueuedPlayer", new RTPLocation(world, 0, 64, 0));
    player.setPermission("rtp.command.arena", true);
    player.setPermission("rtp.unqueued", false);
    player.setPermission("rtp.*", false);
    serverAccessor.addPlayer(player);

    ActionCommand cmd = new ActionCommand(def);
    boolean handled = cmd.onCommand(playerId, Collections.emptyMap(), null);
    assertTrue(handled);

    // Player without rtp.unqueued should receive queueUpdate notification
    assertTrue(player.sentMessages.stream().anyMatch(m -> m.contains(PlayerMessages.queueUpdate.name())),
        "Player without rtp.unqueued should receive queueUpdate message");
  }

  @Test
  @DisplayName("ActionCancelCmd: /rtp action cancel and /<action> cancel cancel pending queue or session")
  void testActionCancelCommand() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();

    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", new RTPLocation(world, 10, 64, 10));
    MockRTPPlayer admin = new MockRTPPlayer(adminId, "Admin", new RTPLocation(world, 20, 64, 20));

    alice.setPermission("rtp.action", true);
    alice.setPermission("rtp.action.cancel", true);
    alice.setPermission("rtp.action.cancel.other", false);
    alice.setPermission("rtp.*", false);

    bob.setPermission("rtp.action", true);
    bob.setPermission("rtp.action.cancel", true);
    bob.setPermission("rtp.action.cancel.other", false);
    bob.setPermission("rtp.*", false);

    admin.setPermission("rtp.action", true);
    admin.setPermission("rtp.action.cancel", true);
    admin.setPermission("rtp.action.cancel.other", true);

    serverAccessor.addPlayer(alice);
    serverAccessor.addPlayer(bob);
    serverAccessor.addPlayer(admin);

    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "challenge", "rtp.command.challenge", "Challenge duel", List.of());
    ActionDefinition def = new ActionDefinition(
        "challenge", "challenge", "rtp.action.challenge", "Challenge",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec,
        List.of(Map.of("players", ">= 2")));
    actionManager.registerAction(def);

    // 1. Put Alice in wait-queue for challenge
    java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.action.ActionSessionResult> fAlice =
        actionManager.trigger("challenge", List.of(aliceId), ActionContext.EMPTY);
    assertFalse(fAlice.isDone());

    // 2. Test /challenge cancel child subcommand
    ActionCommand challengeCmd = new ActionCommand(def);
    assertTrue(challengeCmd.getCommandLookup().containsKey("CANCEL"));
    io.github.dailystruggle.commandsapi.common.CommandsAPICommand childCancel = challengeCmd.getCommandLookup().get("CANCEL");
    assertNotNull(childCancel);

    // Alice runs /challenge cancel
    boolean handledChild = childCancel.onCommand(aliceId, Collections.emptyMap(), null);
    assertTrue(handledChild);
    assertTrue(fAlice.isDone());
    assertEquals("CANCELLED", fAlice.join().failureReason());
    assertTrue(alice.sentMessages.stream().anyMatch(m -> m.contains("Cancelled challenge")));

    // 3. Put Bob in wait-queue
    java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.action.ActionSessionResult> fBob =
        actionManager.trigger("challenge", List.of(bobId), ActionContext.EMPTY);
    assertFalse(fBob.isDone());

    // Alice tries to cancel Bob's action without rtp.action.cancel.other -> denied
    ActionSubCmd subCmd = new ActionSubCmd(null);
    subCmd.syncActions();
    io.github.dailystruggle.commandsapi.common.CommandsAPICommand actionCancel = subCmd.getCommandLookup().get("CANCEL");
    assertNotNull(actionCancel);

    actionCancel.onCommand(aliceId, Map.of("player", List.of("Bob")), null);
    assertFalse(fBob.isDone(), "Alice without rtp.action.cancel.other must not cancel Bob");

    // Admin cancels Bob's action with player=Bob
    actionCancel.onCommand(adminId, Map.of("player", List.of("Bob")), null);
    assertTrue(fBob.isDone(), "Admin with rtp.action.cancel.other should cancel Bob");
    assertEquals("CANCELLED", fBob.join().failureReason());
    assertTrue(admin.sentMessages.stream().anyMatch(m -> m.contains("Cancelled action for Bob")));
  }

  // Walks up from user.dir so the lookup is independent of the test JVM's working directory
  // (repo root, rtp-core, or an IDE-chosen module dir).
  private static java.io.File locateBundledActionsDir() {
    String[] candidates = {
        "addons/LeafRTPActionAddon/src/main/resources/definitions/actions",
        "rtp-plugin/src/main/resources/definitions/actions"
    };
    java.io.File start = new java.io.File(System.getProperty("user.dir")).getAbsoluteFile();
    for (java.io.File dir = start; dir != null; dir = dir.getParentFile()) {
      for (String candidate : candidates) {
        java.io.File f = new java.io.File(dir, candidate);
        if (f.isDirectory()) return f;
      }
    }
    return new java.io.File(start, candidates[0]);
  }

  @Test
  @DisplayName("Challenge command correctly pauses in wait-queue without premature chunk loading")
  void testChallengeCommandPausesInWaitQueue() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();

    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", new RTPLocation(world, 10, 64, 10));
    alice.setPermission("rtp.*", true);
    bob.setPermission("rtp.*", true);

    serverAccessor.addPlayer(alice);
    serverAccessor.addPlayer(bob);

    java.io.File actionsDir = locateBundledActionsDir();
    assertTrue(actionsDir.exists(), "Actions dir must exist: " + actionsDir.getAbsolutePath());

    io.github.dailystruggle.rtp.common.action.ActionConfigLoader.loadActions(actionsDir.getParentFile().getParentFile(), actionManager);

    ActionDefinition chalDef = actionManager.getAction("challenge").orElse(null);
    assertNotNull(chalDef);

    ActionCommand chalCmd = new ActionCommand(chalDef);

    // Alice runs /challenge Bob
    chalCmd.onCommand(aliceId, Map.of("player", List.of("Bob")), null);

    // Verify Alice is in the wait queue
    assertTrue(actionManager.isQueued("challenge", aliceId), "Alice must be queued in challenge wait queue");

    // Verify Alice does NOT receive "Loading chunks! please wait..."
    boolean receivedChunkLoading = alice.sentMessages.stream().anyMatch(m -> m.contains("Loading chunks"));
    assertFalse(receivedChunkLoading, "Alice must not receive premature chunk loading message while waiting in queue");

    // Verify onEnqueue lifecycle step executed console commands / messages (duel prompt to Bob and Alice)
    boolean bobReceivedInvite = bob.sentMessages.stream().anyMatch(m -> m.contains("challenge") || m.contains("duel"))
        || serverAccessor.getExecutedCommands().stream().anyMatch(m -> m.contains("Bob") && m.contains("challenge"));
    assertTrue(bobReceivedInvite, "Bob should have received duel prompt; bob messages: " + bob.sentMessages + ", executed: " + serverAccessor.getExecutedCommands());
  }

  @Test
  @DisplayName("Challenge command mutual reciprocity matches entries and starts duel")
  void testChallengeCommandMutualReciprocity() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();

    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", new RTPLocation(world, 10, 64, 10));
    alice.setPermission("rtp.*", true);
    bob.setPermission("rtp.*", true);

    serverAccessor.addPlayer(alice);
    serverAccessor.addPlayer(bob);

    io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService = io.github.dailystruggle.rtp.common.RTP.groupPlacementService;
    io.github.dailystruggle.rtp.common.RTP.groupPlacementService = request -> {
      Map<UUID, io.github.dailystruggle.rtp.api.world.RTPLocation> placements = new java.util.HashMap<>();
      int i = 0;
      for (UUID pid : request.participants()) {
        placements.put(pid, new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 100 + i * 20, 64, 100));
        i++;
      }
      return java.util.concurrent.CompletableFuture.completedFuture(
          io.github.dailystruggle.rtp.api.group.GroupPlacementResult.success(placements));
    };

    try {
      // Register a mock "execute" command handler so the vanilla execute gate in challenge.yml succeeds in mock tests
      serverAccessor.registerCommands(new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
        @Override public String name() { return "execute"; }
        @Override public String permission() { return "rtp.execute"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand subCommand) { return true; }
        @Override public java.util.concurrent.CompletableFuture<Boolean> onCommand(
            UUID callerId, java.util.function.Predicate<String> perm, java.util.function.Consumer<String> msg, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params) {
          return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
      });

      java.io.File actionsDir = locateBundledActionsDir();
      assertTrue(actionsDir.exists(), "Actions dir must exist: " + actionsDir.getAbsolutePath());
      io.github.dailystruggle.rtp.common.action.ActionConfigLoader.loadActions(actionsDir.getParentFile().getParentFile(), actionManager);

      ActionDefinition chalDef = actionManager.getAction("challenge").orElse(null);
      assertNotNull(chalDef);
      ActionCommand chalCmd = new ActionCommand(chalDef);

      // 1. Alice challenges Bob
      chalCmd.onCommand(aliceId, Map.of("player", List.of("Bob")), null);
      assertTrue(actionManager.isQueued("challenge", aliceId));
      assertFalse(actionManager.isQueued("challenge", bobId));

      // 2. Bob accepts by challenging Alice back
      chalCmd.onCommand(bobId, Map.of("player", List.of("Alice")), null);

      // 3. Reciprocity matched: both drained from wait queue
      assertFalse(actionManager.isQueued("challenge", aliceId), "Alice should be drained after match");
      assertFalse(actionManager.isQueued("challenge", bobId), "Bob should be drained after match");

      // 4. Session created for both participants
      assertTrue(actionManager.getSessionForParticipant(aliceId).isPresent());
      assertTrue(actionManager.getSessionForParticipant(bobId).isPresent());
    } finally {
      io.github.dailystruggle.rtp.common.RTP.groupPlacementService = originalGroupService;
    }
  }

  @Test
  @DisplayName("Nearplayer anchor returns null when no other players online rather than falling back to random region")
  void testNearPlayerFailsClosedWhenAlone() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0));
    alice.setPermission("rtp.*", true);
    serverAccessor.addPlayer(alice);

    ActionDefinition.PlacementSpec pSpec = new ActionDefinition.PlacementSpec(
        true, "default", "CIRCLE", 64, 24, 256, Map.of("anchor", "entity"), 1, 0);

    io.github.dailystruggle.rtp.api.group.AnchorSource source =
        ActionManager.resolveAnchorSource(pSpec, ActionContext.EMPTY, List.of(aliceId), null);

    assertNotNull(source);
    assertNull(source.resolveAnchor(null).join(), "Anchor coords must be null when no eligible online player exists");
  }

  @Test
  @DisplayName("Live placement teleports player to target location and closes reservation")
  void testLivePlacementTeleportsPlayer() {
    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID aliceId = UUID.randomUUID();
    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", new RTPLocation(world, 0, 64, 0));
    alice.setPermission("rtp.*", true);
    serverAccessor.addPlayer(alice);

    java.util.concurrent.atomic.AtomicBoolean ticketClosed = new java.util.concurrent.atomic.AtomicBoolean(false);
    io.github.dailystruggle.rtp.api.world.ChunkSet chunkSet =
        new io.github.dailystruggle.rtp.api.world.ChunkSet(world, 0, 0, List.of(), java.util.concurrent.CompletableFuture.completedFuture(true));
    io.github.dailystruggle.rtp.api.world.ChunkReservation reservation = new io.github.dailystruggle.rtp.api.world.ChunkReservation(chunkSet, world) {
      @Override
      public void close() {
        ticketClosed.set(true);
        super.close();
      }
    };
    RTPLocation targetLoc = new RTPLocation(world, 500, 72, -300);
    targetLoc.setReservation(reservation);

    io.github.dailystruggle.rtp.api.group.GroupPlacementService orig = io.github.dailystruggle.rtp.common.RTP.groupPlacementService;
    io.github.dailystruggle.rtp.common.RTP.groupPlacementService = request ->
        java.util.concurrent.CompletableFuture.completedFuture(
            io.github.dailystruggle.rtp.api.group.GroupPlacementResult.success(Map.of(aliceId, targetLoc)));

    try {
      ActionDefinition.PlacementSpec pSpec = new ActionDefinition.PlacementSpec(
          true, "default", "SQUARE", 64, 24, 256, Map.of(), 1, 0);

      ActionDefinition def = new ActionDefinition(
          "live_teleport_test", "live_teleport_test", "rtp.live", "",
          pSpec,
          ActionDefinition.ConfinementSpec.DEFAULT,
          ActionDefinition.LifecycleSpec.EMPTY,
          ActionDefinition.CommandSpec.EMPTY,
          List.of());
      actionManager.registerAction(def);

      io.github.dailystruggle.rtp.api.action.ActionSessionResult res = actionManager.trigger("live_teleport_test", List.of(aliceId), ActionContext.EMPTY).join();
      assertTrue(res.success());

      // Verify Alice was actually teleported!
      io.github.dailystruggle.rtp.api.world.RTPLocation currentLoc = alice.getLocation();
      assertNotNull(currentLoc);
      assertEquals(500, currentLoc.x(), "Player x must update to allocated placement");
      assertEquals(72, currentLoc.y(), "Player y must update to allocated placement");
      assertEquals(-300, currentLoc.z(), "Player z must update to allocated placement");

      // Verify chunk ticket reservation was properly closed (S-002)
      assertTrue(ticketClosed.get(), "ChunkReservation must be closed after teleport dispatch");
    } finally {
      io.github.dailystruggle.rtp.common.RTP.groupPlacementService = orig;
    }
  }

  private ActionDefinition summonDefWithTargetPermission() {
    io.github.dailystruggle.rtp.api.action.ParameterSpec playerParam =
        new io.github.dailystruggle.rtp.api.action.ParameterSpec(
            "player",
            io.github.dailystruggle.rtp.api.action.ParameterType.PLAYER,
            false,
            "rtp.command.summon.target",
            "self");
    ActionDefinition.CommandSpec cmdSpec = new ActionDefinition.CommandSpec(
        "summon", "rtp.command.summon", "Summon a player", List.of(), List.of(playerParam));
    return new ActionDefinition(
        "summon", "summon", "rtp.action.summon", "Summon",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY,
        cmdSpec);
  }

  @Test
  @DisplayName("Declarative PLAYER parameter: naming a target without the parameter permission is denied (ADR-098)")
  void testDeclarativeTargetPermissionDenied() {
    ActionDefinition def = summonDefWithTargetPermission();
    actionManager.registerAction(def);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID callerId = UUID.randomUUID();
    UUID victimId = UUID.randomUUID();
    MockRTPPlayer caller = new MockRTPPlayer(callerId, "Caller", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer victim = new MockRTPPlayer(victimId, "Victim", new RTPLocation(world, 5, 64, 5));
    caller.setPermission("rtp.command.summon", true);          // may run the action
    caller.setPermission("rtp.command.summon.target", false);  // may NOT name a specific player
    caller.setPermission("rtp.*", false);
    serverAccessor.addPlayer(caller);
    serverAccessor.addPlayer(victim);

    ActionCommand cmd = new ActionCommand(def);
    boolean handled = cmd.onCommand(callerId, Map.of("player", List.of("Victim")), null);
    assertTrue(handled);

    assertTrue(caller.sentMessages.stream().anyMatch(m -> m.contains(PlayerMessages.noPerms.name())),
        "Caller lacking the parameter permission must be denied when naming a specific player");
  }

  @Test
  @DisplayName("Declarative PLAYER parameter: caller holding the parameter permission may name a target (ADR-098)")
  void testDeclarativeTargetPermissionAllowed() {
    ActionDefinition def = summonDefWithTargetPermission();
    actionManager.registerAction(def);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID callerId = UUID.randomUUID();
    UUID victimId = UUID.randomUUID();
    MockRTPPlayer caller = new MockRTPPlayer(callerId, "Caller", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer victim = new MockRTPPlayer(victimId, "Victim", new RTPLocation(world, 5, 64, 5));
    caller.setPermission("rtp.command.summon", true);
    caller.setPermission("rtp.command.summon.target", true);   // may name a specific player
    caller.setPermission("rtp.*", false);
    serverAccessor.addPlayer(caller);
    serverAccessor.addPlayer(victim);

    ActionCommand cmd = new ActionCommand(def);
    boolean handled = cmd.onCommand(callerId, Map.of("player", List.of("Victim")), null);
    assertTrue(handled);

    assertFalse(caller.sentMessages.stream().anyMatch(m -> m.contains(PlayerMessages.noPerms.name())),
        "Caller holding the parameter permission must not be denied for naming a player");
  }

  @Test
  @DisplayName("Declarative PLAYER default 'self': a no-target caller is never denied by the parameter permission (ADR-098)")
  void testDeclarativeDefaultSelfUnrestricted() {
    ActionDefinition def = summonDefWithTargetPermission();
    actionManager.registerAction(def);

    RTPWorld<?> world = serverAccessor.getRTPWorld("world");
    UUID callerId = UUID.randomUUID();
    MockRTPPlayer caller = new MockRTPPlayer(callerId, "Caller", new RTPLocation(world, 0, 64, 0));
    caller.setPermission("rtp.command.summon", true);
    caller.setPermission("rtp.command.summon.target", false);  // no naming permission...
    caller.setPermission("rtp.*", false);
    serverAccessor.addPlayer(caller);

    ActionCommand cmd = new ActionCommand(def);
    // ...but no target supplied, so the 'self' default applies and the invocation is unrestricted.
    boolean handled = cmd.onCommand(callerId, Collections.emptyMap(), null);
    assertTrue(handled);

    assertFalse(caller.sentMessages.stream().anyMatch(m -> m.contains(PlayerMessages.noPerms.name())),
        "Default (self) path must not require the target permission");
  }

  @Test
  void testActionCommandNextCommandHandling() {
    ActionDefinition def = new ActionDefinition(
        "step_act", "step_act", "perm", "",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY
    );
    ActionCommand cmd = new ActionCommand(def);
    assertTrue(cmd.onCommand(UUID.randomUUID(), Map.of(), cmd));
  }
}
