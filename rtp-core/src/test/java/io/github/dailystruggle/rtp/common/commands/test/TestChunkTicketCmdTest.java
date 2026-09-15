package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code rtp test chunk-ticket} - live chunk ticketing contracts (S-002),
 * fallback sentinel validation for mock/headless environments, and {@link MemoryTracker}
 * release paths (REQ-RTP-S-002).
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

  /** Test world implementing live chunk ticketing contracts. */
  private static class LiveTestWorld extends MockRTPWorld {
    final AtomicInteger forceLoadedCount;
    final java.util.Set<Long> tickets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile boolean failKeep = false;
    volatile boolean failForget = false;

    LiveTestWorld(String name, int initialCount) {
      super(name);
      this.forceLoadedCount = new AtomicInteger(initialCount);
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
      return CompletableFuture.completedFuture(forceLoadedCount.get() + tickets.size());
    }

    @Override
    public void keepChunkAt(int chunkX, int chunkZ) {
      if (!failKeep) {
        tickets.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
      }
    }

    @Override
    public void forgetChunkAt(int chunkX, int chunkZ) {
      if (!failForget) {
        tickets.remove(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
      }
    }
  }

  @Test
  void runProbe_passesWithNoResidual() {
    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe();

    assertEquals(0, r.afterUntrackById, "untrack(UUID) must fully release");
    assertEquals(0, r.afterUntrackByRef, "untrack(Object) must fully release");
    assertEquals(1, r.afterDiagnosticsOnLive, "diagnostics must not drop a live, non-leaking entry");
    assertEquals(0, r.finalResidual, "no sentinels may leak after the probe");
    assertTrue(r.pass);
    assertFalse(r.liveProbed);
    // Baseline should be clean; the probe self-scopes its sentinel label.
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL));
  }

  @Test
  void runProbe_nullWorld_fallsBackToSentinelValidation() {
    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(null);

    assertFalse(r.liveProbed);
    assertTrue(r.pass);
    assertEquals(0, r.finalResidual);
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL));
  }

  @Test
  void runProbe_negativeCountWorld_fallsBackToSentinelValidation() {
    MockRTPWorld negativeWorld = new MockRTPWorld("negative-world") {
      @Override
      public CompletableFuture<Integer> getServerForceLoadedCount() {
        return CompletableFuture.completedFuture(-1);
      }
    };

    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(negativeWorld);

    assertFalse(r.liveProbed);
    assertTrue(r.pass);
    assertEquals(0, r.finalResidual);
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL));
  }

  @Test
  void runProbe_unsupportedCountOperation_fallsBackToSentinelValidation() {
    MockRTPWorld throwingWorld = new MockRTPWorld("throwing-world") {
      @Override
      public CompletableFuture<Integer> getServerForceLoadedCount() {
        throw new UnsupportedOperationException("live counts unsupported in headless test");
      }
    };

    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(throwingWorld);

    assertFalse(r.liveProbed);
    assertTrue(r.pass);
    assertEquals(0, r.finalResidual);
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL));
  }

  @Test
  void runProbe_unsupportedKeepChunkAt_fallsBackToSentinelValidation() {
    MockRTPWorld unsupportedKeepWorld = new MockRTPWorld("unsupported-keep-world") {
      @Override
      public CompletableFuture<Integer> getServerForceLoadedCount() {
        return CompletableFuture.completedFuture(2);
      }

      @Override
      public void keepChunkAt(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("keepChunkAt not implemented");
      }
    };

    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(unsupportedKeepWorld);

    assertFalse(r.liveProbed);
    assertTrue(r.pass);
    assertEquals(0, r.finalResidual);
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL));
  }

  @Test
  void runProbe_liveWorld_positivelyAssertsLiveChunkTicketingContracts() {
    LiveTestWorld liveWorld = new LiveTestWorld("live-world", 3);

    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(liveWorld, 0, 0);

    assertTrue(r.liveProbed, "Should run live probe when world supports contracts");
    assertTrue(r.pass, "Live ticketing probe must pass");
    assertEquals(3, r.baselineCount, "Baseline count must match initial world count");
    assertEquals(4, r.openCount, "Count must positively increment by 1 when ticket is opened");
    assertEquals(3, r.closeCount, "Count must return to baseline after ticket is released");
    assertEquals(0, r.memoryTrackerTicketCount, "MemoryTracker ticket count must return to 0");
    assertEquals(3, liveWorld.forceLoadedCount.get(), "World forceLoadedCount must be restored to baseline");
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL),
        "MemoryTracker must have zero leaked sentinels (S-002)");
  }

  @Test
  void runProbe_liveWorld_countDoesNotIncrement_failsAssertionAndCleansUp() {
    LiveTestWorld liveWorld = new LiveTestWorld("broken-keep-world", 2);
    liveWorld.failKeep = true;

    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(liveWorld, 0, 0);

    assertTrue(r.liveProbed);
    assertFalse(r.pass, "Probe must fail if count did not increment by 1");
    assertEquals(2, r.baselineCount);
    assertEquals(2, r.openCount);
    assertEquals(2, liveWorld.forceLoadedCount.get(), "World count must remain at baseline (S-002)");
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL),
        "Sentinel must not leak even on failure");
  }

  @Test
  void runProbe_liveWorld_countDoesNotReturnToBaseline_failsAssertion() {
    LiveTestWorld liveWorld = new LiveTestWorld("broken-forget-world", 2);
    liveWorld.failForget = true;

    TestChunkTicketCmd.Result r = TestChunkTicketCmd.runProbe(liveWorld, 0, 0);

    assertTrue(r.liveProbed);
    assertFalse(r.pass, "Probe must fail if count did not return to baseline");
    assertEquals(2, r.baselineCount);
    assertEquals(3, r.openCount);
    assertEquals(3, r.closeCount);
    assertEquals(0, MemoryTracker.trackedCountByLabel(TestChunkTicketCmd.SENTINEL_LABEL),
        "Sentinel must not leak even on failure");
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
  void onCommand_withLiveWorld_emitsLiveOkSummaryToCaller() {
    LiveTestWorld liveWorld = new LiveTestWorld("live-cmd-world", 0);
    accessor.clearWorlds();
    accessor.addWorld(liveWorld);

    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p2",
        new io.github.dailystruggle.rtp.api.world.RTPLocation(liveWorld, 0, 0, 0));
    accessor.addPlayer(player);

    boolean ret = new TestChunkTicketCmd(null).onCommand(player.uuid(), noArgs(), null);

    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream()
            .anyMatch(m -> m.contains("chunk-ticket") && m.contains("ok") && m.contains("live=true")),
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
