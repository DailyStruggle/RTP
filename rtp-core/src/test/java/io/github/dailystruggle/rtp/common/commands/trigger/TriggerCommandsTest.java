package io.github.dailystruggle.rtp.common.commands.trigger;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Physical trigger commands: /rtp trigger create, list, remove")
class TriggerCommandsTest {

  private MockRTPServerAccessor accessor;
  private TriggerSubCmd subCmd;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    accessor = RTPTestSetup.install(tempDir.toFile());
    subCmd = new TriggerSubCmd(null);
  }

  @Test
  @DisplayName("TriggerSubCmd permissions, usage and metadata")
  void testTriggerSubCmd() {
    assertEquals("trigger", subCmd.name());
    assertEquals("rtp.trigger", subCmd.permission());
    assertNotNull(subCmd.description());

    UUID senderId = UUID.randomUUID();
    MockRTPCommandSender sender = new MockRTPCommandSender(senderId, "Admin");
    sender.setPermission("rtp.trigger", true);
    accessor.addSender(sender);

    assertTrue(subCmd.onCommand(senderId, Collections.emptyMap(), null));
  }

  @Test
  @DisplayName("TriggerCreateCmd creates trigger from player position")
  void testTriggerCreateCmd() {
    TriggerCreateCmd createCmd = new TriggerCreateCmd(subCmd);
    assertEquals("create", createCmd.name());
    assertEquals("rtp.trigger", createCmd.permission());

    // Console fails
    UUID consoleId = UUID.randomUUID();
    MockRTPCommandSender console = new MockRTPCommandSender(consoleId, "CONSOLE");
    accessor.addSender(console);
    assertTrue(createCmd.onCommand(consoleId, Collections.emptyMap(), null));

    // Player creates trigger
    RTPWorld<?> world = accessor.getRTPWorld("world");
    UUID playerId = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(playerId, "Creator", new RTPLocation(world, 100, 64, 200));
    player.setPermission("rtp.trigger", true);
    accessor.addPlayer(player);

    assertTrue(createCmd.onCommand(playerId, Collections.emptyMap(), null));
    assertFalse(RTP.triggerManager.getTriggers().isEmpty());
  }

  @Test
  @DisplayName("TriggerListCmd lists registered triggers")
  void testTriggerListCmd() {
    TriggerListCmd listCmd = new TriggerListCmd(subCmd);
    assertEquals("list", listCmd.name());

    UUID playerId = UUID.randomUUID();
    MockRTPCommandSender sender = new MockRTPCommandSender(playerId, "Viewer");
    sender.setPermission("rtp.trigger", true);
    accessor.addSender(sender);

    // Empty list
    RTP.triggerManager.clear();
    assertTrue(listCmd.onCommand(playerId, Collections.emptyMap(), null));

    // Populated list
    RTP.triggerManager.registerTrigger(new io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec(
        "t1", io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.PORTAL,
        "world", 0, 60, 0, 5, 70, 5, "arena", 5L));
    assertTrue(listCmd.onCommand(playerId, Collections.emptyMap(), null));
  }

  @Test
  @DisplayName("TriggerRemoveCmd usage description")
  void testTriggerRemoveCmd() {
    TriggerRemoveCmd removeCmd = new TriggerRemoveCmd(subCmd);
    assertEquals("remove", removeCmd.name());
    assertEquals("rtp.trigger", removeCmd.permission());

    UUID senderId = UUID.randomUUID();
    MockRTPCommandSender sender = new MockRTPCommandSender(senderId, "Remover");
    accessor.addSender(sender);

    assertTrue(removeCmd.onCommand(senderId, Collections.emptyMap(), null));
  }
}
