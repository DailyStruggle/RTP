package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestChunkTicketCmd#runProbe()}, the
 * {@link MemoryTracker} positive-path probe.
 *
 * <p>Traces REQ-RTP-S-002 (positive-path coverage; the existing
 * watchdog tests cover the negative path). See
 * {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.8} and
 * {@code docs/dev/TRACEABILITY.md}.
 */
class TestChunkTicketCmdTest {

  @Test
  @DisplayName("runProbe passes on a clean MemoryTracker (REQ-RTP-S-002 positive path)")
  void runProbePassesOnCleanTracker() {
    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe();

    assertTrue(r.pass, "probe must pass on a clean tracker; result=" + describe(r));
    assertEquals(0, r.afterUntrackById, "explicit untrack(UUID) must drop the entry");
    assertEquals(0, r.afterUntrackByRef, "explicit untrack(Object) must drop the entry");
    assertEquals(
        1,
        r.afterDiagnosticsOnLive,
        "runDiagnostics must NOT drop a live, non-leaking entry");
    assertEquals(0, r.finalResidual, "no sentinels may remain after the probe completes");
  }

  @Test
  @DisplayName("trackedCountByLabel isolates a label from unrelated registry traffic")
  void trackedCountByLabelIsolatesLabel() {
    // Pollute the tracker with unrelated entries; they must not affect the
    // label-scoped count used by the probe.
    Object unrelatedA = new Object();
    Object unrelatedB = new Object();
    UUID a = MemoryTracker.track(unrelatedA, "unrelated-label-a", 60_000L);
    UUID b = MemoryTracker.track(unrelatedB, "unrelated-label-b", 60_000L);
    try {
      int beforeSentinels =
          MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL);
      assertEquals(0, beforeSentinels, "no sentinels should exist before the probe starts");

      TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe();
      assertTrue(r.pass, "probe must pass despite unrelated tracker traffic; " + describe(r));
      assertEquals(0, r.staleBaseline, "stale baseline must be 0 on a clean label");
    } finally {
      MemoryTracker.untrack(a);
      MemoryTracker.untrack(b);
    }
  }

  @Test
  @DisplayName("runProbe is idempotent: repeated runs leave no residual")
  void runProbeIsIdempotent() {
    TestChunkTicketCmd.Result r1 = TestChunkTicketCmd.runProbe();
    TestChunkTicketCmd.Result r2 = TestChunkTicketCmd.runProbe();
    TestChunkTicketCmd.Result r3 = TestChunkTicketCmd.runProbe();

    assertTrue(r1.pass && r2.pass && r3.pass, "every repeat must pass");
    assertEquals(
        0,
        MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL),
        "label-scoped count must be 0 after all repeats (no leak across invocations)");
  }

  private static String describe(TestChunkTicketCmd.Result r) {
    return "stale=" + r.staleBaseline
        + " untrackById=" + r.afterUntrackById
        + " untrackByRef=" + r.afterUntrackByRef
        + " diagOnLive=" + r.afterDiagnosticsOnLive
        + " residual=" + r.finalResidual;
  }
}
