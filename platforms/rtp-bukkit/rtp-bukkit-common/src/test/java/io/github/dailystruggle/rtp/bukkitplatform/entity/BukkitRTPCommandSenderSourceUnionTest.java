package io.github.dailystruggle.rtp.bukkitplatform.entity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BukkitRTPCommandSenderSourceUnionTest {

  @Test
  @DisplayName("BukkitRTPCommandSender pure-data constructor is self-contained")
  void testPureDataConstructor() {
    UUID uuid = UUID.randomUUID();
    Set<String> perms = Set.of("rtp.use", "rtp.wild");
    BukkitRTPCommandSender sender = new BukkitRTPCommandSender(uuid, "PlayerX", 5000L, 1000L, perms);

    assertTrue(sender.isSelfContained());
    assertEquals(uuid, sender.uuid());
    assertEquals("PlayerX", sender.name());
    assertEquals(5000L, sender.cooldown());
    assertEquals(1000L, sender.delay());
    assertTrue(sender.hasPermission("rtp.use"));
    assertFalse(sender.hasPermission("rtp.admin"));

    RTPCommandSender pure = sender.toPureData();
    assertTrue(pure.isSelfContained());
    assertEquals(uuid, pure.uuid());
  }

  @Test
  @DisplayName("BukkitRTPPlayer pure-data constructor is self-contained and releases player handle")
  void testPureDataPlayer() {
    UUID uuid = UUID.randomUUID();
    Set<String> perms = Set.of("rtp.see");
    BukkitRTPPlayer player = new BukkitRTPPlayer(uuid, "PlayerY", 3000L, 500L, perms);

    assertTrue(player.isSelfContained());
    assertNull(player.player());
    assertEquals(uuid, player.uuid());
    assertEquals("PlayerY", player.name());
    assertTrue(player.hasPermission("rtp.see"));
    assertFalse(player.isOnline());
  }
}
