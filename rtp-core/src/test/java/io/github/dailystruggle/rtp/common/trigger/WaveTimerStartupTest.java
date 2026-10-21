package io.github.dailystruggle.rtp.common.trigger;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RTP's static initializer runs before the platform assigns {@link RTPRuntime#scheduler()}, so the wave
 * accumulator must be scheduled on core enable or every {@code batchInterval} trigger stays silent.
 */
class WaveTimerStartupTest {

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    if (ActiveConfigs.get() != null && ActiveConfigs.get().fileDatabase != null) {
      try {
        ActiveConfigs.get().fileDatabase.close();
      } catch (Throwable ignored) {
      }
    }
    if (RTP.getInstance() != null) {
      MiscTasks.async().clear();
      MiscTasks.startup().clear();
      RTP.stop();
    }
    if (RTPRuntime.serverAccessor() instanceof MockRTPServerAccessor mock) {
      mock.clearWorlds();
    }
    ActiveSelection.set(null);
    ActiveConfigs.set(null);
  }

  @Test
  @DisplayName("ADR-093: core enable schedules the physical-trigger wave timer")
  void coreEnableSchedulesWaveTimer() {
    MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
    accessor.clearWorlds();
    ActiveSelection.set(new io.github.dailystruggle.rtp.common.selection.SelectionAPI());

    new RTP();

    assertTrue(PhysicalTriggerManager.instance().isWaveTimerScheduled(),
        "wave accumulator must be scheduled once a scheduler exists");
  }
}
