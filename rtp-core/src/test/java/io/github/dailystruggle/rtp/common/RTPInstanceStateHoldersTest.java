package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ADR-115 invariant 3: state that used to be {@code RTP} instance fields lives in holders that a
 * new {@code RTP} instance installs as it becomes current, with the values a fresh instance had.
 */
@DisplayName("ADR-115 invariant 3: a new RTP instance installs fresh state holders")
class RTPInstanceStateHoldersTest {

  @TempDir Path pluginDir;

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  @Test
  @DisplayName("constructor installs fresh sessions, scan map, pipes and limit store; clears the accessor")
  void constructorInstallsFreshHolders() {
    RTPTestSetup.install(pluginDir.toFile());
    TeleportSessions before = TeleportSessions.current();
    Map<?, ?> scansBefore = ScanTasks.current();
    ActiveDatabase.setAccessor(mock(DatabaseAccessor.class));

    RTP rtp = new RTP() { };

    assertSame(rtp, RTP.getInstance());
    TeleportSessions sessions = TeleportSessions.current();
    assertNotNull(sessions);
    assertNotSame(before, sessions, "each instance owns its sessions");
    assertTrue(sessions.latestTeleportData.isEmpty());
    assertTrue(sessions.processingPlayers.isEmpty());
    assertNotNull(ScanTasks.current());
    assertNotSame(scansBefore, ScanTasks.current(), "each instance owns its scan map");
    assertTrue(ScanTasks.current().isEmpty());
    assertNull(ActiveDatabase.accessor(), "a new instance starts without a database accessor");
    assertNotNull(ActiveDatabase.teleportLimitStore());
    assertNotNull(MiscTasks.sync());
    assertNotNull(MiscTasks.backgroundSync());
    assertNotNull(MiscTasks.async());
    assertNotNull(MiscTasks.startup());
    assertNotNull(MiscTasks.cancel());
    assertNotNull(MiscTasks.chunksToUnload());
    if (MiscTasks.cancel() instanceof TimeBoundTaskPipe cancel) {
      assertEquals(Long.MAX_VALUE, cancel.getAvailableTime(), "cancel pipe is unbounded");
    }
  }
}
