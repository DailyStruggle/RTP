package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that the SHIPPED sample action definitions
 * ({@code rtp-plugin/src/main/resources/definitions/actions/*.yml}) actually parse and
 * drive the real {@link ActionConfigLoader} -> {@link ActionManager} -> {@link ActionSessionImpl}
 * lifecycle as they will in production (ADR-093 / ADR-095).
 *
 * <p>Unlike {@code ActionManagerTest} (which builds definitions in-code), this test loads the
 * exact YAML files bundled in the plugin jar, so a broken/mis-parsed shipped config fails here.
 */
class SampleActionE2ETest {

  /** Recording accessor: captures every dispatched command line so lifecycle side effects are observable. */
  private static final class RecordingServerAccessor extends MockRTPServerAccessor {
    final List<String> dispatched = new CopyOnWriteArrayList<>();
    final Map<String, java.util.function.BooleanSupplier> hooks = new java.util.concurrent.ConcurrentHashMap<>();

    RecordingServerAccessor(File dir) {
      super(dir);
    }

    void addCommandHook(String cmdPrefix, java.util.function.BooleanSupplier hook) {
      hooks.put(cmdPrefix, hook);
    }

    @Override
    public void sendMessage(UUID target, String message, String tag) {
      dispatched.add(target + " :: MSG :: " + message);
      super.sendMessage(target, message, tag);
    }

    @Override
    public boolean executeCommand(UUID senderId, String commandLine) {
      dispatched.add(senderId + " :: " + commandLine);
      // Maintain real scoreboard-tag state (e.g. 'tag <player> add/remove <tag>') so reciprocity
      // gates evaluate against genuine state instead of an always-true dispatch stub.
      super.executeCommand(senderId, commandLine);
      for (Map.Entry<String, java.util.function.BooleanSupplier> entry : hooks.entrySet()) {
        if (commandLine.contains(entry.getKey())) {
          return entry.getValue().getAsBoolean();
        }
      }
      return true;
    }
  }

  private RecordingServerAccessor accessor;
  private ActionManager manager;
  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  /** Resolves the shipped resources dir regardless of whether tests run from the module or repo root. */
  private static File shippedResourcesDir() {
    File[] candidates = {
      new File("../addons/LeafRTPActionAddon/src/main/resources"),
      new File("addons/LeafRTPActionAddon/src/main/resources"),
      new File("../rtp-plugin/src/main/resources"),
      new File("rtp-plugin/src/main/resources"),
    };
    for (File c : candidates) {
      if (new File(c, "definitions/actions").isDirectory()) {
        return c;
      }
    }
    fail("Could not locate shipped definitions/actions dir; cwd=" + new File(".").getAbsolutePath());
    return null;
  }

  @BeforeEach
  void setUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor installed =
        io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    accessor = new RecordingServerAccessor(tempDir.toFile());
    // Copy default worlds from installed to accessor
    for (io.github.dailystruggle.rtp.api.world.RTPWorld<?> w : installed.getRTPWorlds()) {
      if (w instanceof io.github.dailystruggle.rtp.common.mock.MockRTPWorld mw) {
        accessor.addWorld(mw);
      }
    }
    RTP.serverAccessor = accessor;
    RTP.scheduler = accessor.getMockScheduler();
    originalGroupService = RTP.groupPlacementService;
    manager = new ActionManager();
    // Production has one manager: subcommands resolve the caller's session through RTP.actionManager.
    RTP.actionManager = manager;
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = manager;

    // Deterministic placement stub: every participant lands at a fixed safe slot.
    RTP.groupPlacementService = request -> {
      java.util.Map<UUID, RTPLocation> placements = new java.util.HashMap<>();
      int i = 0;
      for (UUID pid : request.participants()) {
        placements.put(pid, new RTPLocation(accessor.getRTPWorld("world"), 100 + i * 40, 64, 100));
        i++;
      }
      return CompletableFuture.completedFuture(GroupPlacementResult.success(placements));
    };

    // Load the ACTUAL shipped YAML action definitions.
    ActionConfigLoader.loadActions(shippedResourcesDir(), manager);
  }

  @Test
  @DisplayName("MultiConfigParser unpacks bundled definitions to plugin directory if empty")
  void testUnpackBundledDefinitionsToPluginDir(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    ActionManager m = new ActionManager();
    io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys> actions =
        new io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<>(
            io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys.class, "actions", "1.0", tempDir.toFile(), "definitions/actions", "en");
    ActionConfigLoader.loadActions(actions, m);

    File actionsDir = new File(tempDir.toFile(), "definitions/actions");
    assertTrue(actionsDir.exists());
    File[] ymls = actionsDir.listFiles((dir, name) -> name.endsWith(".yml"));
    assertNotNull(ymls);
    assertTrue(actionsDir.isDirectory());
  }

  @AfterEach
  void tearDown() {
    RTP.groupPlacementService = originalGroupService;
    RTP.actionManager = null;
  }

  @Test
  @DisplayName("All shipped sample actions parse and register (all 10 shipped actions)")
  void testShippedActionsRegister() {
    List<String> expectedActions = List.of(
        "default", "scatter", "nearplayer", "nearclaim", "location",
        "arena", "challenge", "quickchallenge", "koth", "teams");
    for (String id : expectedActions) {
      assertTrue(manager.getActionIds().contains(id),
          "shipped action '" + id + "' must be registered from its YAML file");
    }
  }

  @Test
  @DisplayName("Shipped one-shot actions run the full trigger->onStart->placement lifecycle end to end")
  void testShippedLifecycleEndToEnd() {
    for (String id : List.of("default", "scatter", "nearplayer", "nearclaim", "location")) {
      accessor.dispatched.clear();
      UUID player = UUID.randomUUID();
      // Register online mock player so username can be resolved cleanly
      accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
          player, "TestPlayer", new RTPLocation(accessor.getRTPWorld("world"), 0, 64, 0)));

      // nearplayer/location may consult context for a live anchor; supply one so the flow is realistic.
      ActionContext ctx = ActionContext.of(
          Map.of("anchorWorld", "world", "anchorX", 250, "anchorY", 70, "anchorZ", -120));

      ActionSessionResult res = manager.trigger(id, List.of(player), ctx).join();
      assertTrue(res.success(), "shipped action '" + id + "' should trigger successfully: " + res.failureReason());
      assertNotNull(res.sessionId());

      // One-shot actions auto-disarm immediately after onStart placement cleanup
      assertFalse(manager.getSession(res.sessionId()).isPresent(),
          "one-shot action '" + id + "' should auto-disarm after trigger->onStart placement");

      // The shipped onStart step is `CONSOLE: "tellraw ..."` or `PLAYER: "msg ..."`; it MUST dispatch to the participant.
      boolean onStartFired = false;
      for (String line : accessor.dispatched) {
        if ((line.contains("tellraw") || line.contains("msg")) && (line.contains("TestPlayer") || line.contains(player.toString()))) {
          onStartFired = true;
        }
      }
      assertTrue(onStartFired,
          "shipped action '" + id + "' onStart message did not dispatch. Captured=" + accessor.dispatched);
    }
  }

  @Test
  @DisplayName("Shipped arena action end-to-end: multi-player duel start, equipment, and disarm")
  void testShippedArenaActionLifecycle() {
    accessor.dispatched.clear();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "PlayerOne", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p2, "PlayerTwo", new RTPLocation(world, 0, 64, 0)));

    ActionSessionResult res = manager.trigger("arena", List.of(p1, p2), ActionContext.EMPTY).join();
    assertTrue(res.success(), "arena action must trigger successfully");
    assertNotNull(res.sessionId());

    Optional<ActionSession> sessionOpt = manager.getSession(res.sessionId());
    assertTrue(sessionOpt.isPresent());
    ActionSession session = sessionOpt.get();
    assertEquals(2, session.participants().size());

    // Verify onStart dispatched equipment for each player
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("give") && c.contains("iron_sword")));
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("gamemode adventure")));

    manager.disarm(res.sessionId());
    assertFalse(manager.getSession(res.sessionId()).isPresent());
  }

  @Test
  @DisplayName("Shipped challenge action end-to-end: first challenge tags sender and sends prompt")
  void testShippedChallengeFirstTime() {
    accessor.dispatched.clear();
    UUID sender = UUID.randomUUID();
    UUID target = UUID.randomUUID();

    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        target, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionContext ctx = ActionContext.of(Map.of(
        "sender_name", "Alice",
        "target_name", "Bob"));

    CompletableFuture<ActionSessionResult> future = manager.trigger("challenge", List.of(sender), ctx);
    assertFalse(future.isDone(), "Must be waiting in queue for reciprocal participant");

    // onEnqueue should have sent prompt to target with [CLICK TO ACCEPT]
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("[CLICK TO ACCEPT]")));
  }

  @Test
  @DisplayName("Shipped challenge action end-to-end: reciprocal acceptance fires arena with valid parameter syntax")
  void testShippedChallengeReciprocalAcceptance() {
    accessor.dispatched.clear();
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    // Register named players so the reciprocity gate can resolve name -> uuid -> scoreboard tags.
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionContext aliceCtx = ActionContext.of(Map.of(
        "sender_name", "Alice",
        "target_name", "Bob"));

    ActionContext bobCtx = ActionContext.of(Map.of(
        "sender_name", "Bob",
        "target_name", "Alice"));

    CompletableFuture<ActionSessionResult> fAlice = manager.trigger("challenge", List.of(alice), aliceCtx);
    assertFalse(fAlice.isDone());

    CompletableFuture<ActionSessionResult> fBob = manager.trigger("challenge", List.of(bob), bobCtx);

    ActionSessionResult resAlice = fAlice.join();
    ActionSessionResult resBob = fBob.join();

    assertTrue(resAlice.success());
    assertTrue(resBob.success());
    assertEquals(resAlice.sessionId(), resBob.sessionId());

    // Verify onStart announced match start
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("Duel match started")));

    manager.disarm(resAlice.sessionId());
  }

  @Test
  @DisplayName("Failure test: non-existent action ID fails closed")
  void testTriggerNonExistentActionFails() {
    ActionSessionResult res = manager.trigger("non_existent_action_xyz", List.of(UUID.randomUUID()), ActionContext.EMPTY).join();
    assertFalse(res.success());
    assertNotNull(res.failureReason());
    assertTrue(res.failureReason().toLowerCase().contains("not found") || res.failureReason().toLowerCase().contains("non_existent"));
  }

  @Test
  @DisplayName("Failure test: empty participants list fails closed")
  void testTriggerEmptyParticipantsFails() {
    ActionSessionResult res = manager.trigger("scatter", List.of(), ActionContext.EMPTY).join();
    assertFalse(res.success());
    assertNotNull(res.failureReason());
  }

  @Test
  @DisplayName("Failure test: placement service failure rejects session cleanly")
  void testPlacementServiceFailureFailsSession() {
    // Override placement service to simulate placement failure (e.g. no safe location found)
    RTP.groupPlacementService = request -> CompletableFuture.completedFuture(
        GroupPlacementResult.failure(GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, "No standable safe column within tolerance"));

    ActionSessionResult res = manager.trigger("scatter", List.of(UUID.randomUUID()), ActionContext.EMPTY).join();
    assertFalse(res.success());
    assertNotNull(res.failureReason());
    assertTrue(res.failureReason().contains("INSUFFICIENT_SAFE_SLOTS") || res.failureReason().contains("tolerance"));
  }

  @Test
  @DisplayName("Shipped command testing: success and failure for all registered action commands")
  void testShippedActionCommandsSuccessAndFailure() {
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    UUID opId = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer opPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(opId, "OpUser", new RTPLocation(world, 0, 64, 0));
    opPlayer.setPermission("rtp.*", true);
    opPlayer.setPermission("rtp.other", true);
    accessor.addPlayer(opPlayer);

    UUID targetId = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer targetPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(targetId, "TargetUser", new RTPLocation(world, 10, 64, 10));
    targetPlayer.setPermission("rtp.notme", false);
    accessor.addPlayer(targetPlayer);

    UUID nonPermId = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer nonPermPlayer =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(nonPermId, "GuestUser", new RTPLocation(world, 20, 64, 20));
    nonPermPlayer.setPermission("rtp.*", false);
    nonPermPlayer.setPermission("rtp.command.duel", false);
    nonPermPlayer.setPermission("rtp.command.challenge", false);
    nonPermPlayer.setPermission("rtp.action.scatter", false);
    accessor.addPlayer(nonPermPlayer);

    // 1. Success test: executing arena command (/duel player=TargetUser)
    io.github.dailystruggle.rtp.api.action.ActionDefinition arenaDef = manager.getAction("arena").orElseThrow();
    io.github.dailystruggle.rtp.common.commands.action.ActionCommand arenaCmd =
        new io.github.dailystruggle.rtp.common.commands.action.ActionCommand(arenaDef);

    boolean handled = arenaCmd.onCommand(opId, Map.of("player", List.of("TargetUser")), null);
    assertTrue(handled);

    // 2. Failure test: permission denied for unauthorized caller
    boolean permHandled = arenaCmd.onCommand(nonPermId, Map.of("player", List.of("TargetUser")), null);
    assertTrue(permHandled);
    assertTrue(nonPermPlayer.sentMessages.stream().anyMatch(m -> m.contains("noPerms")));

    // 3. Failure test: invalid/unknown player name passed to command
    opPlayer.sentMessages.clear();
    boolean unknownPlayerHandled = arenaCmd.onCommand(opId, Map.of("player", List.of("CompletelyUnknownPlayer99999")), null);
    assertTrue(unknownPlayerHandled);
    // opId is still a player caller, so opId is included, but unknown player is ignored.

    // 4. Failure test: command sender is console with no player parameter -> fails closed (no participants)
    UUID consoleId = new UUID(0L, 0L);
    boolean consoleNoParamsHandled = arenaCmd.onCommand(consoleId, Map.of(), null);
    assertTrue(consoleNoParamsHandled);

    // 5. Success test: command sender is console with valid player parameter
    boolean consoleWithPlayerHandled = arenaCmd.onCommand(consoleId, Map.of("player", List.of("TargetUser")), null);
    assertTrue(consoleWithPlayerHandled);

    // 6. TreeCommand parameter delimiter verification:
    // Testing ActionSubCmd execution with valid player= parameter vs unknown parameter
    io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd subCmd =
        new io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd(null);
    subCmd.syncActions();

    // Verify unknown parameter is rejected / not present in parameter lookup
    assertFalse(arenaCmd.getParameterLookup().containsKey("unknownparam"));
    assertTrue(arenaCmd.getParameterLookup().containsKey("player"));

    // Execute via TreeCommand onCommand pipeline with unknown parameter vs valid player= parameter
    // TreeCommand onCommand(callerId, permissionCheckMethod, messageMethod, args, index, tempParams)
    java.util.concurrent.atomic.AtomicBoolean badParamTriggered = new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.function.Consumer<String> mockMsgMethod = msg -> badParamTriggered.set(true);

    // Call with bad parameter syntax (e.g. invalidparam=xyz)
    String[] badArgs = new String[]{"invalidparam=xyz"};
    Boolean badResult = arenaCmd.onCommand(opId, perm -> true, mockMsgMethod, badArgs, 0, new java.util.HashMap<>()).join();
    assertFalse(badResult, "TreeCommand must reject unknown parameters not registered in parameterLookup");
    assertTrue(badParamTriggered.get(), "TreeCommand must invoke msgBadParameter on unrecognized parameter");

    // Call with valid parameter syntax (player=TargetUser)
    badParamTriggered.set(false);
    String[] validArgs = new String[]{"player=TargetUser"};
    opPlayer.setPermission("rtp.other", true);
    Boolean validResult = arenaCmd.onCommand(opId, perm -> opPlayer.hasPermission(perm), mockMsgMethod, validArgs, 0, new java.util.HashMap<>()).join();
    assertFalse(badParamTriggered.get(), "msgBadParameter must not be called for valid parameter");
    assertTrue(validResult, "TreeCommand must accept registered parameter 'player'");
  }

  @Test
  @DisplayName("End-to-End Command Registration: Shipped actions register both top-level commands and /rtp action subcommands")
  void testShippedActionsRegisteredAsCommandsEndToEnd() {
    // 1. Verify top-level commands registered with RTPServerAccessor via registerAllCommands
    manager.registerAllCommands();
    Map<String, Object> registeredCommands = accessor.getRegisteredCommands();
    assertTrue(registeredCommands.containsKey("duel"), "duel command must be registered top-level on server accessor");
    assertTrue(registeredCommands.containsKey("challenge"), "challenge command must be registered top-level on server accessor");

    // 2. Verify aliases are opt-in on first boot (not registered by default to avoid command bloat)
    assertFalse(registeredCommands.containsKey("fight"), "fight alias must not be registered by default on first boot (opt-in)");
    assertFalse(registeredCommands.containsKey("duelreq"), "duelreq alias must not be registered by default on first boot (opt-in)");

    // 2b. Verify opt-in aliases register when an action explicitly defines them
    io.github.dailystruggle.rtp.api.action.ActionDefinition optInAction = new io.github.dailystruggle.rtp.api.action.ActionDefinition(
        "customduel", "customduel", "rtp.action.customduel", "Custom duel",
        io.github.dailystruggle.rtp.api.action.ActionDefinition.PlacementSpec.DEFAULT,
        io.github.dailystruggle.rtp.api.action.ActionDefinition.ConfinementSpec.DEFAULT,
        io.github.dailystruggle.rtp.api.action.ActionDefinition.LifecycleSpec.EMPTY,
        new io.github.dailystruggle.rtp.api.action.ActionDefinition.CommandSpec(
            "customduel", "rtp.command.customduel", "Custom duel", List.of("customfight"),
            List.of(), Map.of()
        )
    );
    manager.registerAction(optInAction);
    manager.registerAllCommands();
    assertTrue(accessor.getRegisteredCommands().containsKey("customfight"), "opt-in aliases must be registered when specified");

    // 3. Verify /rtp command tree integration
    io.github.dailystruggle.rtp.common.commands.CoreRtpRoot root =
        new io.github.dailystruggle.rtp.common.commands.CoreRtpRoot();
    RTP.baseCommand = root;
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = manager;
    manager.registerAllCommands();

    io.github.dailystruggle.commandsapi.common.CommandsAPICommand actionSub =
        root.getCommandLookup().get("ACTION");
    assertNotNull(actionSub, "/rtp action must be present in root command tree");

    // 4. Verify all shipped actions are accessible as subcommands under /rtp action
    assertTrue(actionSub instanceof io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd);
    io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd actionSubCmd =
        (io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd) actionSub;
    Map<String, io.github.dailystruggle.commandsapi.common.CommandsAPICommand> subLookup =
        actionSubCmd.getCommandLookup();
    assertTrue(subLookup.containsKey("ARENA"), "/rtp action ARENA must be resolvable");
    assertTrue(subLookup.containsKey("CHALLENGE"), "/rtp action CHALLENGE must be resolvable");
    assertTrue(subLookup.containsKey("SCATTER"), "/rtp action SCATTER must be resolvable");
    assertTrue(subLookup.containsKey("DEFAULT"), "/rtp action DEFAULT must be resolvable");

    // 5. Test executing /rtp action arena player=... through root tree command
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    UUID callerId = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer caller =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(callerId, "Caller", new RTPLocation(world, 0, 64, 0));
    caller.setPermission("rtp.*", true);
    accessor.addPlayer(caller);

    // Verify tab complete on root for /rtp action
    List<String> tabSuggestions = root.onTabComplete(callerId, perm -> true, new String[]{"action", ""});
    assertNotNull(tabSuggestions);
    assertTrue(tabSuggestions.contains("arena") || tabSuggestions.contains("ARENA") || tabSuggestions.contains("help"),
        "Tab complete on /rtp action must provide action names without StackOverflow");

    UUID friendId = UUID.randomUUID();
    io.github.dailystruggle.rtp.common.mock.MockRTPPlayer friend =
        new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(friendId, "Friend", new RTPLocation(world, 10, 64, 10));
    accessor.addPlayer(friend);

    String[] args = new String[]{"arena", "player=Friend"};
    CompletableFuture<Boolean> cmdFuture = actionSub.onCommand(callerId, perm -> true, msg -> {}, args, 0, new java.util.HashMap<>());
    io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
    Boolean res = cmdFuture.join();
    assertTrue(res, "Executing /rtp action arena player=Friend must succeed");
  }

  @Test
  @DisplayName("Full Plugin Bootstrap Lifecycle: Commands register at boot and remain registered after reload")
  void testFullPluginBootstrapCommandRegistrationLifecycle(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    // 1. Setup server accessor and mock directories
    RecordingServerAccessor bootAccessor = new RecordingServerAccessor(tempDir.toFile());
    RTP.serverAccessor = bootAccessor;
    RTP.scheduler = bootAccessor.getMockScheduler();
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    RTP.serverAccessor = bootAccessor;

    // 2. Load actions into RTP.actionManager
    RTP.actionManager = new io.github.dailystruggle.rtp.common.action.ActionManager();
    File actionsDir = shippedResourcesDir();
    io.github.dailystruggle.rtp.common.action.ActionConfigLoader.loadActions(actionsDir, RTP.actionManager);

    // 3. Simulate BootstrapSupport.registerRtpAndWildCommands:
    // CoreRtpRoot is created, baseCommand is set, and registerAllCommands is invoked
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = RTP.actionManager;
    io.github.dailystruggle.rtp.common.commands.CoreRtpRoot root =
        new io.github.dailystruggle.rtp.common.commands.CoreRtpRoot();
    RTP.baseCommand = root;
    RTP.actionManager.registerAllCommands();

    // 4. Verify boot registration
    Map<String, Object> bootCommands = bootAccessor.getRegisteredCommands();
    assertTrue(bootCommands.containsKey("duel"), "duel command must be registered at boot");
    assertTrue(bootCommands.containsKey("challenge"), "challenge command must be registered at boot");

    io.github.dailystruggle.commandsapi.common.CommandsAPICommand actionSub =
        root.getCommandLookup().get("ACTION");
    assertNotNull(actionSub);
    assertTrue(actionSub instanceof io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd);
    io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd actionSubCmd =
        (io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd) actionSub;

    assertTrue(actionSubCmd.getCommandLookup().containsKey("ARENA"));
    assertTrue(actionSubCmd.getCommandLookup().containsKey("CHALLENGE"));

    // 5. Simulate Configs.reloadConfigs()
    RTP.actionManager.clearDefinitions();
    io.github.dailystruggle.rtp.common.action.ActionConfigLoader.loadActions(actionsDir, RTP.actionManager);
    RTP.actionManager.registerAllCommands();

    // 6. Verify commands remain fully registered and operational after reload
    Map<String, Object> reloadedCommands = bootAccessor.getRegisteredCommands();
    assertTrue(reloadedCommands.containsKey("duel"), "duel command must remain registered after reload");
    assertTrue(reloadedCommands.containsKey("challenge"), "challenge command must remain registered after reload");

    assertTrue(actionSubCmd.getCommandLookup().containsKey("ARENA"), "arena subcommand must remain after reload");
    assertTrue(actionSubCmd.getCommandLookup().containsKey("CHALLENGE"), "challenge subcommand must remain after reload");
    assertTrue(actionSubCmd.getCommandLookup().containsKey("QUICKCHALLENGE"), "quickchallenge subcommand must remain after reload");

    // 7. Verify tab completion works without recursion after reload
    List<String> suggestions = root.onTabComplete(UUID.randomUUID(), perm -> true, new String[]{"action", ""});
    assertNotNull(suggestions);
    assertTrue(suggestions.contains("arena"));
  }

  @Test
  @DisplayName("Shipped quickchallenge action is one-shot with 0s duration and disarms immediately")
  void testShippedQuickChallengeOneShot() {
    io.github.dailystruggle.rtp.api.action.ActionDefinition quickDef = manager.getAction("quickchallenge").orElseThrow();
    assertEquals(0L, quickDef.confinement().durationSeconds(), "quickchallenge must have duration 0s");

    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionContext aliceCtx = ActionContext.of(Map.of(
        "sender_name", "Alice",
        "target_name", "Bob"));

    ActionContext bobCtx = ActionContext.of(Map.of(
        "sender_name", "Bob",
        "target_name", "Alice"));

    CompletableFuture<ActionSessionResult> fAlice = manager.trigger("quickchallenge", List.of(alice), aliceCtx);
    assertFalse(fAlice.isDone());

    CompletableFuture<ActionSessionResult> fBob = manager.trigger("quickchallenge", List.of(bob), bobCtx);

    ActionSessionResult resAlice = fAlice.join();
    ActionSessionResult resBob = fBob.join();

    assertTrue(resAlice.success());
    assertTrue(resBob.success());

    // Once started, a 0s session disarms immediately upon ticking
    manager.tick();
    assertFalse(manager.getSession(resAlice.sessionId()).isPresent(), "0s session must disarm immediately");
  }

  @Test
  @DisplayName("Challenge action with cancellable=false prevents session cancellation but permits wait-queue cancel")
  void testChallengeCancellationPolicy() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    ActionContext ctx = ActionContext.of(Map.of(
        "sender_name", "Alice",
        "target_name", "Bob"));

    // 1. Wait queue cancel succeeds prior to teleport
    CompletableFuture<ActionSessionResult> future = manager.trigger("challenge", List.of(alice), ctx);
    assertFalse(future.isDone());
    assertTrue(manager.cancelParticipant(alice, "challenge"), "Must cancel while in wait queue");
    assertTrue(future.isDone());
    assertFalse(future.join().success());

    // 2. Active session cancellation fails when cancellable=false
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionContext bobCtx = ActionContext.of(Map.of(
        "sender_name", "Bob",
        "target_name", "Alice"));

    CompletableFuture<ActionSessionResult> fAlice = manager.trigger("challenge", List.of(alice), ctx);
    manager.trigger("challenge", List.of(bob), bobCtx);

    ActionSessionResult res = fAlice.join();
    assertTrue(res.success());
    assertTrue(manager.getSession(res.sessionId()).isPresent());

    // Ongoing match cancellation fails
    assertFalse(manager.cancelParticipant(alice, "challenge"), "Cannot cancel ongoing match when cancellable=false");
    assertTrue(manager.getSession(res.sessionId()).isPresent(), "Session must remain active");

    // Surrender triggers forfeit and ends session
    assertTrue(manager.surrenderParticipant(alice, "challenge"), "Surrendering ongoing match succeeds");
    assertFalse(manager.getSession(res.sessionId()).isPresent(), "Session must disarm after surrender");
  }

  @Test
  @DisplayName("Challenge action with declarative leave subcommand and death disarm")
  void testChallengeDeclarativeLeaveSubcommand() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        bob, "Bob", new RTPLocation(world, 0, 64, 0)));

    ActionContext aliceCtx = ActionContext.of(Map.of(
        "sender_name", "Alice",
        "target_name", "Bob"));

    ActionContext bobCtx = ActionContext.of(Map.of(
        "sender_name", "Bob",
        "target_name", "Alice"));

    CompletableFuture<ActionSessionResult> fAlice = manager.trigger("challenge", List.of(alice), aliceCtx);
    manager.trigger("challenge", List.of(bob), bobCtx);

    ActionSessionResult res = fAlice.join();
    assertTrue(res.success());
    UUID sessionId = res.sessionId();
    assertTrue(manager.getSession(sessionId).isPresent());

    // 1. Verify subcommand registration
    io.github.dailystruggle.rtp.api.action.ActionDefinition challengeDef = manager.getAction("challenge").orElseThrow();
    assertTrue(challengeDef.command().subcommands().containsKey("leave"), "leave subcommand must be parsed");
    io.github.dailystruggle.rtp.api.action.ActionDefinition.SubcommandSpec leaveSpec = challengeDef.command().subcommands().get("leave");
    assertEquals(1, leaveSpec.actions().size());
    assertEquals("kill [player_name]", leaveSpec.actions().get(0).payload());

    // 2. Invoke /challenge leave as Alice
    io.github.dailystruggle.rtp.common.commands.action.ActionCommand challengeCmd =
        new io.github.dailystruggle.rtp.common.commands.action.ActionCommand(challengeDef);
    assertTrue(challengeCmd.getCommandLookup().containsKey("LEAVE"));
    assertTrue(challengeCmd.getCommandLookup().containsKey("SURRENDER"));

    io.github.dailystruggle.commandsapi.common.CommandsAPICommand leaveCmd =
        challengeCmd.getCommandLookup().get("LEAVE");
    assertNotNull(leaveCmd);

    boolean executed = leaveCmd.onCommand(alice, Map.of(), null);
    assertTrue(executed);

    // Verify console command 'kill Alice' was dispatched
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("kill Alice")),
        "Declared kill command must be dispatched: " + accessor.dispatched);

    // 3. Simulating death of Alice routes to session and ends the challenge
    manager.handlePlayerDeath(alice, bob);

    // onDeath executes and disarms session
    assertFalse(manager.getSession(sessionId).isPresent(), "Session must disarm upon participant death");
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("Alice was defeated in the duel!")),
        "onDeath message must be broadcast: " + accessor.dispatched);
  }

  @Test
  @DisplayName("Shipped challenge action without target: open matchmaking does not send invitation prompt to self")
  void testShippedChallengeWithoutTargetDoesNotSendInviteToSelf() {
    accessor.dispatched.clear();
    UUID alice = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        alice, "Alice", new RTPLocation(world, 0, 64, 0)));

    // Trigger challenge without target (e.g. /challenge or target="any")
    ActionContext ctx = ActionContext.of(Map.of("sender_name", "Alice", "target_name", "any"));

    CompletableFuture<ActionSessionResult> future = manager.trigger("challenge", List.of(alice), ctx);
    assertFalse(future.isDone(), "Must be waiting in queue for another open challenger");

    // Must NOT have dispatched challenge prompt to Alice
    boolean aliceReceivedPrompt = accessor.dispatched.stream()
        .anyMatch(c -> c.contains("[CLICK TO ACCEPT]") || c.contains("has challenged you"));
    assertFalse(aliceReceivedPrompt,
        "Targetless challenge must not send invite prompt to self. Dispatched: " + accessor.dispatched);
  }

  @Test
  @DisplayName("Action trigger when participant already in session reports participant name")
  void testAlreadyInSessionReportsPlayerName() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "leaf26", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p2, "PlayerTwo", new RTPLocation(world, 0, 64, 0)));

    ActionSessionResult res = manager.trigger("arena", List.of(p1, p2), ActionContext.EMPTY).join();
    assertTrue(res.success());
    UUID sId = res.sessionId();

    // Trigger another action while in session
    ActionSessionResult failRes = manager.trigger("arena", List.of(p1, p2), ActionContext.EMPTY).join();
    assertFalse(failRes.success());
    assertTrue(failRes.failureReason().contains("leaf26"),
        "Failure reason should report player name: " + failRes.failureReason());

    manager.disarm(sId);
  }

  @Test
  @DisplayName("Shipped arena action wait-queue matching: single player enqueues, second player triggers match")
  void testShippedArenaMatchmakingQueue() {
    accessor.dispatched.clear();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "Duelist1", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p2, "Duelist2", new RTPLocation(world, 0, 64, 0)));

    // 1. Duelist 1 enters queue
    CompletableFuture<ActionSessionResult> f1 = manager.trigger("arena", List.of(p1), ActionContext.EMPTY);
    assertFalse(f1.isDone(), "Player 1 must be waiting in arena queue");
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("Waiting for an opponent")),
        "Must send enqueue notification: " + accessor.dispatched);

    // 2. Duelist 2 enters queue
    CompletableFuture<ActionSessionResult> f2 = manager.trigger("arena", List.of(p2), ActionContext.EMPTY);
    ActionSessionResult res1 = f1.join();
    ActionSessionResult res2 = f2.join();

    assertTrue(res1.success());
    assertTrue(res2.success());
    assertEquals(res1.sessionId(), res2.sessionId());

    // 3. Subcommand /duel leave
    io.github.dailystruggle.rtp.api.action.ActionDefinition arenaDef = manager.getAction("arena").orElseThrow();
    assertTrue(arenaDef.command().subcommands().containsKey("leave"));

    // 4. Duelist 1 defeated -> disarms
    manager.handlePlayerDeath(p1, p2);
    assertFalse(manager.getSession(res1.sessionId()).isPresent(), "Session must disarm upon duel death");
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("has defeated")),
        "Victory message must be broadcast: " + accessor.dispatched);
  }

  @Test
  @DisplayName("Shipped koth action: matchmaking, equipment, and extraction reward")
  void testShippedKothExtractionAction() {
    accessor.dispatched.clear();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p1, "Hunter1", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
        p2, "Hunter2", new RTPLocation(world, 0, 64, 0)));

    // 1. Enter queue
    CompletableFuture<ActionSessionResult> f1 = manager.trigger("koth", List.of(p1), ActionContext.EMPTY);
    assertFalse(f1.isDone(), "Waiting in queue");
    manager.trigger("koth", List.of(p2), ActionContext.EMPTY);

    ActionSessionResult res = f1.join();
    assertTrue(res.success());
    UUID sessionId = res.sessionId();
    assertTrue(manager.getSession(sessionId).isPresent());

    // Verify equipment
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("give") && c.contains("bow 1")));

    // Subcommand /koth leave
    io.github.dailystruggle.rtp.api.action.ActionDefinition kothDef = manager.getAction("koth").orElseThrow();
    assertTrue(kothDef.command().subcommands().containsKey("leave"));

    manager.disarm(sessionId);
    assertFalse(manager.getSession(sessionId).isPresent());
  }

  @Test
  @DisplayName("Shipped teams action: 4-player 2v2 matchmaking queue and team start")
  void testShippedTeams2v2Action() {
    accessor.dispatched.clear();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID p3 = UUID.randomUUID();
    UUID p4 = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1, "T1P1", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p2, "T1P2", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p3, "T2P1", new RTPLocation(world, 0, 64, 0)));
    accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p4, "T2P2", new RTPLocation(world, 0, 64, 0)));

    CompletableFuture<ActionSessionResult> f1 = manager.trigger("teams", List.of(p1), ActionContext.EMPTY);
    manager.trigger("teams", List.of(p2), ActionContext.EMPTY);
    manager.trigger("teams", List.of(p3), ActionContext.EMPTY);
    assertFalse(f1.isDone(), "Waiting for 4 players");

    manager.trigger("teams", List.of(p4), ActionContext.EMPTY);
    ActionSessionResult res = f1.join();
    assertTrue(res.success(), "2v2 team match must trigger once 4 players queue");
    assertEquals(4, manager.getSession(res.sessionId()).orElseThrow().participants().size());

    // Verify equipment and title
    assertTrue(accessor.dispatched.stream().anyMatch(c -> c.contains("2v2 TEAM DUEL!")));

    manager.disarm(res.sessionId());
    assertFalse(manager.getSession(res.sessionId()).isPresent());
  }
}
