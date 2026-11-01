package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
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
 * Verifies {@code rtp test anvil-prefilter} - the {@code Snapshot} arithmetic
 * (reject/hit rate, total, toString) and the caller-facing summary plus the
 * "no probes observed" advisory emitted when the prefilter metrics class is
 * absent from the rtp-core classpath. Traces item 14 of ENTERPRISE_READINESS.md.
 */
class TestAnvilPrefilterCmdTest {

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
  void snapshot_arithmetic_onEmpty() {
    TestAnvilPrefilterCmd.Snapshot s = new TestAnvilPrefilterCmd.Snapshot(0, 0, 0);
    assertEquals(0L, s.total());
    assertEquals(0.0, s.rejectRate(), 1e-9);
    assertEquals(0.0, s.hitRate(), 1e-9);
    assertTrue(s.toString().contains("total=0"));
  }

  @Test
  void snapshot_arithmetic_onCounts() {
    TestAnvilPrefilterCmd.Snapshot s = new TestAnvilPrefilterCmd.Snapshot(3, 1, 0);
    assertEquals(4L, s.total());
    assertEquals(0.25, s.rejectRate(), 1e-9);
    assertEquals(1.0, s.hitRate(), 1e-9);
    assertTrue(s.toString().contains("accepts=3"));
    assertTrue(s.toString().contains("rejects=1"));
  }

  @Test
  void staticSnapshot_returnsZerosWhenMetricsClassAbsent() {
    // AnvilPrefilterMetrics lives in rtp-anvil, off the rtp-core classpath, so
    // the reflective lookup falls back to zero counters.
    TestAnvilPrefilterCmd.Snapshot s = TestAnvilPrefilterCmd.snapshot();
    assertEquals(0L, s.total());
  }

  @Test
  void onCommand_emitsSummaryAndNoProbesAdvisory() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    boolean ret = new TestAnvilPrefilterCmd(null).onCommand(player.uuid(), noArgs(), null);

    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("anvil-prefilter")),
        player.sentMessages.toString());
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("no probes observed")),
        player.sentMessages.toString());
  }

  @Test
  void onCommand_console_logsSummary() {
    boolean ret = new TestAnvilPrefilterCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("anvil-prefilter")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestAnvilPrefilterCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestAnvilPrefilterCmd(null)));
  }

  @Test
  void metadata_isStable() {
    TestAnvilPrefilterCmd cmd = new TestAnvilPrefilterCmd(null);
    assertEquals("anvil-prefilter", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertTrue(cmd.description().length() > 0);
  }
}
