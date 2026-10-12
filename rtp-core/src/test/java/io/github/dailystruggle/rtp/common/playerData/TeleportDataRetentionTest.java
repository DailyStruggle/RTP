package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeleportDataRetentionTest {

  @Test
  @DisplayName("TeleportData converts sender to pure data and detaches live platform handles")
  void testSenderDecoupling() {
    UUID uuid = UUID.randomUUID();
    PureDataRTPCommandSender liveSender = new PureDataRTPCommandSender(uuid, "Player1", 60_000L, 0L, Collections.emptySet());

    TeleportData data = new TeleportData();
    data.sender = liveSender;
    data.time = System.currentTimeMillis();
    data.completed = true;
    data.targetRegionName = "nether";

    // Convert sender to pure data
    data.sender = data.sender.toPureData();

    assertTrue(data.sender.isSelfContained());
    assertEquals(uuid, data.sender.uuid());
    assertEquals("nether", data.targetRegionName);

    TeleportData clone = data.clone();
    assertEquals("nether", clone.targetRegionName);
    assertEquals(uuid, clone.sender.uuid());
    assertTrue(clone.sender.isSelfContained());
  }
}
