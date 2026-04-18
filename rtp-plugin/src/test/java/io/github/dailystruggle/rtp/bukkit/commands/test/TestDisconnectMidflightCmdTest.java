package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestDisconnectMidflightCmd#runProbe} &mdash; verifies
 * the disconnect-mid-teleport cleanup contract without the RTP singleton.
 *
 * <p>Traces REQ-RTP-S-002 / REQ-RTP-S-004. See
 * {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.9}.
 */
class TestDisconnectMidflightCmdTest {

  private static TestDisconnectMidflightCmd.State freshState() {
    ConcurrentSkipListSet<UUID> processing = new ConcurrentSkipListSet<>();
    ConcurrentHashMap<UUID, Long> invulnerable = new ConcurrentHashMap<>();
    ConcurrentMap<UUID, TeleportData> latest = new ConcurrentHashMap<>();
    ConcurrentMap<UUID, TeleportData> prior = new ConcurrentHashMap<>();
    return new TestDisconnectMidflightCmd.State(
        processing, invulnerable.keySet(), invulnerable, latest, prior);
  }

  @Test
  @DisplayName("runProbe passes on a clean in-memory state (REQ-RTP-S-002 leak canary)")
  void runProbePassesOnCleanState() {
    TestDisconnectMidflightCmd.State s = freshState();

    TestDisconnectMidflightCmd.Result r =
        TestDisconnectMidflightCmd.runProbe(s, TestDisconnectMidflightCmd.fakeCancelMirror(s));

    assertTrue(r.pass, "probe must pass on clean state; result=" + describe(r));
    assertNull(r.threw, "probe must not throw on clean state; got " + r.threw);
    assertNotNull(r.probeId, "probe must allocate a synthetic UUID");
    assertTrue(r.processingCleared, "processingPlayers must be cleared after cleanup");
    assertTrue(r.invulnerableCleared, "invulnerablePlayers must be cleared after cleanup");
    assertTrue(r.nextTaskCancelled, "nextTask must be marked cancelled");
    assertTrue(r.teleportDataCleared, "latestTeleportData must be cleared when no prior data");
  }

  @Test
  @DisplayName("runProbe leaves the injected state untouched after a clean run")
  void runProbeIsSelfContained() {
    TestDisconnectMidflightCmd.State s = freshState();

    TestDisconnectMidflightCmd.Result r =
        TestDisconnectMidflightCmd.runProbe(s, TestDisconnectMidflightCmd.fakeCancelMirror(s));
    assertTrue(r.pass, "probe must pass; result=" + describe(r));

    // Every collection must return to empty so live traffic is unaffected
    // by the probe's staging + cleanup.
    assertEquals(0, s.processingPlayers.size(), "processingPlayers must be empty after probe");
    assertFalse(
        s.invulnerableContains(r.probeId),
        "invulnerablePlayers must not retain the probe id");
    assertFalse(
        s.latestTeleportData.containsKey(r.probeId),
        "latestTeleportData must not retain the probe id");
    assertFalse(
        s.priorTeleportData.containsKey(r.probeId),
        "priorTeleportData must not retain the probe id");
  }

  @Test
  @DisplayName("runProbe never collides with pre-existing UUIDs in the state")
  void runProbeAvoidsCollisions() {
    TestDisconnectMidflightCmd.State s = freshState();

    // Seed the collections with an unrelated UUID; the probe must pick a
    // different one and leave the pre-seeded one untouched.
    UUID existing = UUID.randomUUID();
    s.processingPlayers.add(existing);
    s.stageInvulnerable(existing);
    TeleportData existingData = new TeleportData();
    existingData.completed = false;
    s.latestTeleportData.put(existing, existingData);

    TestDisconnectMidflightCmd.Result r =
        TestDisconnectMidflightCmd.runProbe(s, TestDisconnectMidflightCmd.fakeCancelMirror(s));

    assertTrue(r.pass, "probe must pass despite pre-existing unrelated state; " + describe(r));
    assertFalse(
        r.probeId.equals(existing),
        "probe must pick a UUID distinct from pre-existing state");

    // Pre-seeded entries must be preserved — the probe only cleans up after
    // itself, never after unrelated traffic.
    assertTrue(
        s.processingPlayers.contains(existing),
        "pre-existing processingPlayers entry must be preserved");
    assertTrue(
        s.invulnerableContains(existing),
        "pre-existing invulnerablePlayers entry must be preserved");
    assertTrue(
        s.latestTeleportData.containsKey(existing),
        "pre-existing latestTeleportData entry must be preserved");
  }

  @Test
  @DisplayName("runProbe is idempotent across repeated invocations")
  void runProbeIsIdempotent() {
    TestDisconnectMidflightCmd.State s = freshState();

    java.util.function.Consumer<UUID> cancel = TestDisconnectMidflightCmd.fakeCancelMirror(s);
    TestDisconnectMidflightCmd.Result r1 = TestDisconnectMidflightCmd.runProbe(s, cancel);
    TestDisconnectMidflightCmd.Result r2 = TestDisconnectMidflightCmd.runProbe(s, cancel);
    TestDisconnectMidflightCmd.Result r3 = TestDisconnectMidflightCmd.runProbe(s, cancel);

    assertTrue(r1.pass && r2.pass && r3.pass, "every repeat must pass");
    // After three runs the state must still be empty — no cross-invocation leak.
    assertEquals(
        0, s.processingPlayers.size(), "processingPlayers must be empty after repeats");
    assertEquals(
        0,
        s.latestTeleportData.size(),
        "latestTeleportData must be empty after repeats (no cross-invocation leak)");
    // All three probe ids must be distinct (fresh UUID per run).
    Set<UUID> ids = Set.of(r1.probeId, r2.probeId, r3.probeId);
    assertEquals(3, ids.size(), "each invocation must allocate a fresh probe id");
  }

  private static String describe(TestDisconnectMidflightCmd.Result r) {
    return "probeId=" + r.probeId
        + " processingCleared=" + r.processingCleared
        + " invulnerableCleared=" + r.invulnerableCleared
        + " nextTaskCancelled=" + r.nextTaskCancelled
        + " teleportDataCleared=" + r.teleportDataCleared
        + " threw=" + r.threw;
  }
}
