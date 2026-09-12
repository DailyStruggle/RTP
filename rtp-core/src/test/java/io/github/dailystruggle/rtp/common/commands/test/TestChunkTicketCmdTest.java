package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code rtp test chunk-ticket} - the {@link MemoryTracker} release
 * paths it probes (untrack-by-id, untrack-by-ref, diagnostics-on-live) and the
 * PASS report it emits. Traces item 14 of ENTERPRISE_READINESS.md (REQ-RTP-S-002).
 */
class TestChunkTicketCmdTest {

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
  void runProbe_passesWithNoResidual() {
    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe();

    assertEquals(0, r.afterUntrackById, "untrack(UUID) must fully release");
    assertEquals(0, r.afterUntrackByRef, "untrack(Object) must fully release");
    assertEquals(1, r.afterDiagnosticsOnLive, "diagnostics must not drop a live, non-leaking entry");
    assertEquals(0, r.finalResidual, "no sentinels may leak after the probe");
    assertTrue(r.pass);
    // Baseline should be clean; the probe self-scopes its sentinel label.
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL));
  }

  @Test
  void onCommand_emitsOkSummaryToCaller() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    boolean ret = new TestChunkTicketCmd(null).onCommand(player.uuid(), noArgs(), null);

    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("chunk-ticket") && m.contains("ok")),
        player.sentMessages.toString());
  }

  @Test
  void onCommand_console_logsSummary() {
    boolean ret = new TestChunkTicketCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("chunk-ticket")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestChunkTicketCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestChunkTicketCmd(null)));
  }

  @Test
  void metadata_isStable() {
    TestChunkTicketCmd cmd = new TestChunkTicketCmd(null);
    assertEquals("chunk-ticket", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertTrue(cmd.description().length() > 0);
  }
}
