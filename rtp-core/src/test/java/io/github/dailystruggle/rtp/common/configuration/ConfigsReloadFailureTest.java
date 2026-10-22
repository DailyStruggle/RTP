package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigsReloadFailureTest {
    @TempDir
    Path tempDir;

    private ConfigReloadLifecycle installed;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.clearWorlds();
        ActiveSelection.set(new io.github.dailystruggle.rtp.common.selection.SelectionAPI());
        new RTP();
        installed = Configs.getReloadLifecycle();
    }

    @AfterEach
    void tearDown() {
        Configs.setReloadLifecycle(installed);
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
        if (RTPRuntime.serverAccessor() instanceof MockRTPServerAccessor) {
            ((MockRTPServerAccessor) RTPRuntime.serverAccessor()).clearWorlds();
        }
        ActiveSelection.set(null);
        ActiveConfigs.set(null);
    }

    @Test
    @DisplayName("REQ-CORE-ARCH-003: config reload failure is logged and returned to callers")
    void reloadReturnsFalseAndLogsWhenLifecycleFails() {
        Configs configs = ActiveConfigs.get();
        Configs.setReloadLifecycle(new ConfigReloadLifecycle() {
            @Override
            public void beforeReload() {
                throw new IllegalStateException("injected reload failure");
            }
        });

        assertFalse(configs.reload());

        assertTrue(accessor.logMessages.stream().anyMatch(log ->
                log.contains("SEVERE") && log.contains("injected reload failure")));
    }
}
