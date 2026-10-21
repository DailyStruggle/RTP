package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004: a {@code processingPlayers} lock nothing owns is released by the periodic
 * sweep, never left as "already teleporting" until quit; owned locks and legitimate waits are
 * never cut.
 */
class ProcessingLockSweeperTest {

  @TempDir
  File pluginDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(pluginDir);
    MemoryTracker.reset();
    ProcessingLockSweeper.reset();
    clearState();
  }

  @AfterEach
  void tearDown() {
    clearState();
    MemoryTracker.reset();
    ProcessingLockSweeper.reset();
  }

  private static void clearState() {
    TeleportSessions.current().processingPlayers.clear();
    TeleportSessions.current().queuedPlayers.clear();
    DeathTeleports.effectInFlight().clear();
    DeathTeleports.pending().clear();
  }

  private static boolean locked(UUID id) {
    return TeleportSessions.current().processingPlayers.contains(id);
  }

  @Test
  @DisplayName("REQ-RTP-S-004: an unowned lock survives one sweep and is released, with a WARNING, on the next")
  void orphanLockReleasedOnSecondSweep() {
    UUID id = UUID.randomUUID();
    TeleportSessions.current().processingPlayers.add(id);

    MemoryTracker.runDiagnostics();
    assertTrue(locked(id), "first unowned sighting is a grace period");

    MemoryTracker.runDiagnostics();
    assertFalse(locked(id), "lock must be released on the second unowned sweep");
    MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTPRuntime.serverAccessor();
    assertTrue(accessor.logMessages.stream()
            .anyMatch(m -> m.contains("released stale teleport lock") && m.contains(id.toString())),
        "release must be logged naming the player (S-004)");
  }

  @Test
  @DisplayName("REQ-RTP-S-004: a queued player's lock is never released by the sweep")
  void queuedLockKept() {
    UUID id = UUID.randomUUID();
    TeleportSessions.current().processingPlayers.add(id);
    TeleportSessions.current().queuedPlayers.add(id);

    for (int i = 0; i < 3; i++) {
      MemoryTracker.runDiagnostics();
    }
    assertTrue(locked(id));
  }

  @Test
  @DisplayName("REQ-RTP-S-004: death hold keeps the lock")
  void deathHoldKeepsLock() {
    UUID id = UUID.randomUUID();
    TeleportSessions.current().processingPlayers.add(id);
    DeathTeleports.effectInFlight().add(id);

    for (int i = 0; i < 3; i++) {
      MemoryTracker.runDiagnostics();
    }
    assertTrue(locked(id));
  }

  @Test
  @DisplayName("REQ-RTP-S-004: a live tracked pipeline keeps the lock; once it is cancelled the lock is released")
  void livePipelineKeepsLock() {
    UUID id = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(id, "SweepPlayer", null);
    ((MockRTPServerAccessor) RTPRuntime.serverAccessor()).addPlayer(player);
    TeleportSessions.current().processingPlayers.add(id);
    TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(player, player, null));

    MemoryTracker.runDiagnostics();
    MemoryTracker.runDiagnostics();
    assertTrue(locked(id), "a running pipeline owns the lock");

    task.setCancelled(true);
    MemoryTracker.runDiagnostics();
    MemoryTracker.runDiagnostics();
    assertFalse(locked(id), "a cancelled pipeline no longer owns the lock");
    java.lang.ref.Reference.reachabilityFence(task);
  }

  @Test
  @DisplayName("REQ-RTP-S-004: an owner seen on a sweep restarts the grace period")
  void ownerResetsGrace() {
    UUID id = UUID.randomUUID();
    TeleportSessions.current().processingPlayers.add(id);

    MemoryTracker.runDiagnostics(); // unowned: suspect
    TeleportSessions.current().queuedPlayers.add(id);
    MemoryTracker.runDiagnostics(); // owned: cleared
    TeleportSessions.current().queuedPlayers.remove(id);
    MemoryTracker.runDiagnostics(); // unowned again: first sighting
    assertTrue(locked(id));
    MemoryTracker.runDiagnostics();
    assertFalse(locked(id));
  }

  @Test
  @DisplayName("REQ-RTP-S-004: a tracked TeleportData within its warm-up delay is kept with its lock")
  void teleportDataWithinDelayKept() throws Exception {
    UUID id = UUID.randomUUID();
    TeleportData td = new TeleportData();
    td.completed = false;
    td.delay = 60_000L;
    TeleportSessions.current().processingPlayers.add(id);
    TeleportSessions.current().latestTeleportData.put(id, td);
    MemoryTracker.track(td, "TeleportData-" + id, 0L);
    Thread.sleep(5L);

    MemoryTracker.runDiagnostics();
    MemoryTracker.runDiagnostics();

    assertFalse(td.completed, "a record still inside its delay must not be purged");
    assertEquals(1, MemoryTracker.trackedCountByLabel("TeleportData-" + id));
    assertTrue(locked(id), "the live record owns the lock");
  }

  @Test
  @DisplayName("REQ-RTP-S-004: a queued player's TeleportData outlives the 120 s budget")
  void queuedTeleportDataKept() throws Exception {
    UUID id = UUID.randomUUID();
    TeleportData td = new TeleportData();
    td.completed = false;
    TeleportSessions.current().processingPlayers.add(id);
    TeleportSessions.current().queuedPlayers.add(id);
    TeleportSessions.current().latestTeleportData.put(id, td);
    MemoryTracker.track(td, "TeleportData-" + id, 0L);
    Thread.sleep(5L);

    MemoryTracker.runDiagnostics();

    assertFalse(td.completed);
    assertEquals(1, MemoryTracker.trackedCountByLabel("TeleportData-" + id));
    assertTrue(locked(id));

    // Once the wait ends without the record being cleaned up, the budget applies again.
    TeleportSessions.current().queuedPlayers.remove(id);
    MemoryTracker.runDiagnostics();
    assertTrue(td.completed, "stalled record is purged once nothing holds it");
    assertFalse(locked(id));
  }
}
