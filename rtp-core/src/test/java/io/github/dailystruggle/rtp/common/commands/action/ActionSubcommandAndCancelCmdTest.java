package io.github.dailystruggle.rtp.common.commands.action;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionSubcommandAndCancelCmdTest {

  @TempDir
  Path tempDir;

  private MockRTPServerAccessor accessor;
  private ActionManager actionManager;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir.toFile());
    actionManager = new ActionManager();
    RTP.actionManager = actionManager;
    RTPAPI.actionService = actionManager;
  }

  @Test
  void testActionSubcommandCmdExecution() {
    ActionDefinition.CommandAction consoleAct = ActionDefinition.CommandAction.console("say [action_id] [sender_name]");
    ActionDefinition.CommandAction playerAct = ActionDefinition.CommandAction.player("msg [player_name] hi");
    ActionDefinition.CommandAction msgAct = ActionDefinition.CommandAction.message("hello [player_name]");
    ActionDefinition.CommandAction disarmAct = ActionDefinition.CommandAction.action("DISARM");

    ActionDefinition.SubcommandSpec spec = new ActionDefinition.SubcommandSpec(
        "sub", "perm.sub", "desc", List.of("s"), List.of(consoleAct, playerAct, msgAct, disarmAct));

    ActionSubcommandCmd cmd = new ActionSubcommandCmd(null, "test_action", spec);

    assertEquals("sub", cmd.name());
    assertEquals("perm.sub", cmd.permission());
    assertEquals("desc", cmd.description());
    assertSame(spec, cmd.spec());

    UUID playerId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(playerId, "Tester", null);
    accessor.addPlayer(player);

    // Permission denied branch
    cmd.onCommand(playerId, Collections.emptyMap(), null);

    // Permission granted branch
    player.setPermission("perm.sub", true);
    cmd.onCommand(playerId, Collections.emptyMap(), null);

    // Console sender branch
    UUID consoleId = new UUID(0, 0);
    cmd.onCommand(consoleId, Collections.emptyMap(), null);

    // With nextCommand
    assertTrue(cmd.onCommand(playerId, Collections.emptyMap(), cmd));

    // Execute with target placeholder prefix
    ActionDefinition.CommandAction targetConsole = ActionDefinition.CommandAction.console("say [target_name]");
    ActionDefinition.CommandAction targetPlayer = ActionDefinition.CommandAction.player("say [target_name]");
    ActionDefinition.CommandAction targetMsg = ActionDefinition.CommandAction.message("say [target_name]");
    ActionDefinition.SubcommandSpec targetSpec = new ActionDefinition.SubcommandSpec(
        "sub_target", "perm.sub", "desc", List.of(), List.of(targetConsole, targetPlayer, targetMsg));
    ActionSubcommandCmd cmdTarget = new ActionSubcommandCmd(null, "test_action", targetSpec);
    cmdTarget.onCommand(playerId, Collections.emptyMap(), null);
  }

  private static ActionDefinition placementlessDef(String id) {
    return new ActionDefinition(
        id, id, "rtp.action." + id, "test",
        ActionDefinition.PlacementSpec.DISABLED,
        ActionDefinition.ConfinementSpec.DEFAULT,
        new ActionDefinition.LifecycleSpec(List.of(), List.of(), List.of(), List.of()));
  }

  private static ActionSubcommandCmd forfeitCmd(String actionId) {
    ActionDefinition.SubcommandSpec spec = new ActionDefinition.SubcommandSpec(
        "leave", "perm.leave", "forfeit", List.of(),
        List.of(ActionDefinition.CommandAction.console("kill [player_name]")));
    return new ActionSubcommandCmd(null, actionId, spec);
  }

  @Test
  @DisplayName("Forfeit targets the caller even when session metadata names the opponent")
  void forfeitTargetsCallerNotMetadataPlayer() {
    UUID aliceId = UUID.randomUUID();
    UUID bobId = UUID.randomUUID();
    MockRTPPlayer alice = new MockRTPPlayer(aliceId, "Alice", null);
    MockRTPPlayer bob = new MockRTPPlayer(bobId, "Bob", null);
    alice.setPermission("perm.leave", true);
    accessor.addPlayer(alice);
    accessor.addPlayer(bob);

    // Queue-path metadata: player = first matched participant, player_name = last merged sender.
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("player", bobId);
    metadata.put("player_name", "Bob");
    metadata.put("player_uuid", bobId);
    metadata.put("sender_name", "Bob");
    metadata.put("target_name", "Bob");
    assertTrue(actionManager.executeDirect(placementlessDef("challenge"), List.of(aliceId, bobId),
        ActionContext.of(metadata)).join().success());

    forfeitCmd("challenge").onCommand(aliceId, Collections.emptyMap(), null);

    assertTrue(accessor.getExecutedCommands().contains("kill Alice"), accessor.getExecutedCommands().toString());
    assertFalse(accessor.getExecutedCommands().contains("kill Bob"), accessor.getExecutedCommands().toString());
  }

  @Test
  @DisplayName("Forfeit is refused for a player who is not in a match")
  void forfeitRefusedOutsideSession() {
    UUID carolId = UUID.randomUUID();
    MockRTPPlayer carol = new MockRTPPlayer(carolId, "Carol", null);
    carol.setPermission("perm.leave", true);
    accessor.addPlayer(carol);

    forfeitCmd("challenge").onCommand(carolId, Collections.emptyMap(), null);

    assertTrue(accessor.getExecutedCommands().stream().noneMatch(c -> c.startsWith("kill")),
        accessor.getExecutedCommands().toString());
    assertFalse(carol.sentMessages.isEmpty(), "caller must be told why the subcommand was refused");
  }

  @Test
  @DisplayName("Forfeit is refused when the caller's match belongs to a different action")
  void forfeitRefusedForOtherActionSession() {
    UUID daveId = UUID.randomUUID();
    MockRTPPlayer dave = new MockRTPPlayer(daveId, "Dave", null);
    dave.setPermission("perm.leave", true);
    accessor.addPlayer(dave);
    assertTrue(actionManager.executeDirect(placementlessDef("koth"), List.of(daveId),
        ActionContext.EMPTY).join().success());

    forfeitCmd("challenge").onCommand(daveId, Collections.emptyMap(), null);

    assertTrue(accessor.getExecutedCommands().stream().noneMatch(c -> c.startsWith("kill")),
        accessor.getExecutedCommands().toString());
  }

  @Test
  void testActionCancelCmd() {
    ActionCancelCmd cmdAll = new ActionCancelCmd(null);
    ActionCancelCmd cmdFixed = new ActionCancelCmd(null, "duel");

    assertEquals("cancel", cmdAll.name());
    assertEquals(ActionCancelCmd.CMD_PERMISSION, cmdAll.permission());
    assertNull(cmdAll.fixedActionId());
    assertEquals("duel", cmdFixed.fixedActionId());

    UUID player1Id = UUID.randomUUID();
    MockRTPPlayer player1 = new MockRTPPlayer(player1Id, "Alice", null);
    accessor.addPlayer(player1);

    UUID player2Id = UUID.randomUUID();
    MockRTPPlayer player2 = new MockRTPPlayer(player2Id, "Bob", null);
    accessor.addPlayer(player2);

    // NextCommand early return
    assertTrue(cmdAll.onCommand(player1Id, Collections.emptyMap(), cmdAll));

    // Console missing player parameter
    UUID consoleId = new UUID(0, 0);
    assertTrue(cmdAll.onCommand(consoleId, Collections.emptyMap(), null));

    // Console with non-existent player
    assertTrue(cmdAll.onCommand(consoleId, Map.of("player", List.of("Unknown")), null));

    // Cancel self - not queued / not found
    assertTrue(cmdAll.onCommand(player1Id, Collections.emptyMap(), null));

    // Cancel other without permission
    assertTrue(cmdAll.onCommand(player1Id, Map.of("player", List.of("Bob")), null));

    // Cancel other with permission
    player1.setPermission(ActionCancelCmd.PERMISSION_OTHER, true);
    assertTrue(cmdAll.onCommand(player1Id, Map.of("player", List.of("Bob"), "action", List.of("duel")), null));

    // Fixed command cancel
    assertTrue(cmdFixed.onCommand(player1Id, Collections.emptyMap(), null));
  }
}
