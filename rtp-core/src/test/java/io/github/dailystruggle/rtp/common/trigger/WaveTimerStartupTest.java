package io.github.dailystruggle.rtp.common.trigger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RTP's static initializer runs before the platform assigns {@link RTP#scheduler}, so the wave
 * accumulator must be scheduled on core enable or every {@code batchInterval} trigger stays silent.
 */
class WaveTimerStartupTest {

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    if (RTP.configs != null && RTP.configs.fileDatabase != null) {
      try {
        RTP.configs.fileDatabase.close();
      } catch (Throwable ignored) {
      }
    }
    if (RTP.getInstance() != null) {
      RTP.getInstance().miscAsyncTasks.clear();
      RTP.getInstance().startupTasks.clear();
      RTP.stop();
    }
    if (RTP.serverAccessor instanceof MockRTPServerAccessor mock) {
      mock.clearWorlds();
    }
    RTP.selectionAPI = null;
    RTP.configs = null;
  }

  @Test
  @DisplayName("ADR-093: core enable schedules the physical-trigger wave timer")
  void coreEnableSchedulesWaveTimer() {
    MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
    accessor.clearWorlds();
    RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

    new RTP();

    assertTrue(RTP.triggerManager.isWaveTimerScheduled(),
        "wave accumulator must be scheduled once a scheduler exists");
  }
}
