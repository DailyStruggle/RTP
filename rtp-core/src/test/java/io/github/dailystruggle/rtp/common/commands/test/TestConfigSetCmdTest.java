package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code rtp test config-set} round-trips a live config value through
 * {@link ConfigParser#set} / {@code getConfigValue} and restores the original.
 * Confirms the async dispatch, the caller-facing PASS report, and the
 * not-yet-loaded guard. Traces item 14 of ENTERPRISE_READINESS.md.
 */
class TestConfigSetCmdTest {

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
  @SuppressWarnings("unchecked")
  void roundTrip_passes_andRestoresOriginal() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    ConfigParser<PerformanceKeys> parser =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    long before = ((Number) parser.getConfigValue(PerformanceKeys.viewDistanceSelect, 5L)).longValue();

    // MockRTPScheduler runs the async probe inline, so results are ready on return.
    boolean ret = new TestConfigSetCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertTrue(ret);

    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("PASS")),
        "probe must report PASS: " + player.sentMessages);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("restored original")),
        player.sentMessages.toString());

    long after =
        ((Number) parser.getConfigValue(PerformanceKeys.viewDistanceSelect, 5L)).longValue();
    assertEquals(before, after, "original value must be restored after the probe");
  }

  @Test
  void nullScheduler_reportsCoreNotLoaded() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);
    RTP.scheduler = null;
    try {
      new TestConfigSetCmd(null).onCommand(player.uuid(), noArgs(), null);
    } finally {
      RTP.scheduler = accessor.getMockScheduler();
    }
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("core not yet loaded")),
        player.sentMessages.toString());
  }

  @Test
  void consoleCaller_logsButDoesNotMessage() {
    boolean ret = new TestConfigSetCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("config-set")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(new TestConfigSetCmd(null).onCommand(RTPAPI.serverId, noArgs(), new TestConfigSetCmd(null)));
  }

  @Test
  void metadata_isStable() {
    TestConfigSetCmd cmd = new TestConfigSetCmd(null);
    assertEquals("config-set", cmd.name());
    assertEquals("rtp.test.admin", cmd.permission());
    assertTrue(cmd.description().length() > 0);
  }
}
