package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PureDataRTPCommandSenderTest {

  @Test
  @DisplayName("PureDataRTPCommandSender is self-contained and holds pure data with zero platform handles")
  void testPureDataSender() {
    UUID uuid = UUID.randomUUID();
    Set<String> perms = Set.of("rtp.use", "rtp.see");
    PureDataRTPCommandSender sender = new PureDataRTPCommandSender(uuid, "Steve", 10_000L, 2000L, perms);

    assertTrue(sender.isSelfContained());
    assertSame(sender, sender.toPureData());
    assertEquals(uuid, sender.uuid());
    assertEquals("Steve", sender.name());
    assertEquals(10_000L, sender.cooldown());
    assertEquals(2000L, sender.delay());
    assertTrue(sender.hasPermission("rtp.use"));
    assertFalse(sender.hasPermission("rtp.admin"));

    // Clone retains pure data state
    RTPCommandSender clone = sender.clone();
    assertTrue(clone.isSelfContained());
    assertEquals(uuid, clone.uuid());
    assertEquals("Steve", clone.name());
  }

  @Test
  @DisplayName("PureDataRTPPlayer preserves player contracts without live entities")
  void testPureDataPlayer() {
    UUID uuid = UUID.randomUUID();
    PureDataRTPPlayer player = new PureDataRTPPlayer(uuid, "Alex", 5000L, 1000L, Set.of("rtp.use"));

    assertTrue(player.isSelfContained());
    assertSame(player, player.toPureData());
    assertEquals(uuid, player.uuid());
    assertEquals("Alex", player.name());
    assertNull(player.getLocation());
    assertFalse(player.isOnline());
  }
}
