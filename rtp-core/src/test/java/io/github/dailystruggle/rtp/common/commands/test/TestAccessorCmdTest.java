package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests verifying {@link TestAccessorCmd} ({@code rtp test accessor}).
 */
class TestAccessorCmdTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  private static Map<String, List<String>> noArgs() {
    return new HashMap<>();
  }

  @Test
  void metadata_isStable() {
    TestAccessorCmd cmd = new TestAccessorCmd(null);
    assertEquals("accessor", cmd.name());
    assertEquals("rtp.test.accessor", cmd.permission());
    assertNotNull(cmd.description());
    assertFalse(cmd.description().isEmpty());
  }

  @Test
  void runProbe_mockSetup_passes() {
    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertTrue(r.pass, () -> "failed: " + r.message);
    assertTrue(r.materialsValid);
    assertTrue(r.tagsValid);
    assertTrue(r.senderValid);
    assertTrue(r.formatValid);
    assertTrue(r.threadValid);
    assertTrue(r.biomeValid);
    assertTrue(r.menuValid);
    assertTrue(r.versionValid);
    assertTrue(r.worldValid);
    assertTrue(r.messagingValid);
    assertTrue(r.subsystemValid);
    assertEquals("ok", r.message);
  }

  @Test
  void runProbe_nullServerAccessor_failsGracefully() {
    RTP.serverAccessor = null;
    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertFalse(r.pass);
    assertTrue(r.message.contains("RTPServerAccessor is null"));
  }

  @Test
  void runProbe_emptyMaterials_fails() {
    MockRTPServerAccessor emptyMatsAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public Set<String> materials() {
        return new HashSet<>();
      }
    };
    RTP.serverAccessor = emptyMatsAccessor;
    RTPAPI.serverAccessor = emptyMatsAccessor;

    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertFalse(r.pass);
    assertFalse(r.materialsValid);
    assertTrue(r.message.contains("materials"));
  }

  @Test
  void onCommand_emitsSummaryToPlayer() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "player1", null);
    accessor.addPlayer(player);

    boolean ret = new TestAccessorCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("accessor")),
        player.sentMessages.toString());
  }

  @Test
  void onCommand_console_logsSummary() {
    boolean ret = new TestAccessorCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("accessor")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestAccessorCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestAccessorCmd(null)));
  }
}
