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
        .anyMatch(c -> c.contains("tag " + aliceId + " add rtp_chal_" + bobId)));
    assertTrue(serverAccessor.getExecutedCommands().stream()
        .anyMatch(c -> c.contains("tellraw " + bobId + " " + aliceId)));

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
  }
}
