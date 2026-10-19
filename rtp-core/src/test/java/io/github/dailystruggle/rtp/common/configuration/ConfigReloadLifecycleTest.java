package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.lifecycle.ConfigReloadCoordinator;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-115 (RTP-68): reload steps on tasks and regions run through {@link ConfigReloadLifecycle}. */
class ConfigReloadLifecycleTest {

    @TempDir
    Path tempDir;

    private ConfigReloadLifecycle installed;

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.clearWorlds();
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        new RTP();
        installed = Configs.getReloadLifecycle();
    }

    @AfterEach
    void tearDown() {
        Configs.setReloadLifecycle(installed);
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
        if (RTP.serverAccessor instanceof MockRTPServerAccessor) {
            ((MockRTPServerAccessor) RTP.serverAccessor).clearWorlds();
        }
        RTP.selectionAPI = null;
        RTP.configs = null;
    }

    @Test
    @DisplayName("ADR-115 RTP-68: composition root installs the reload coordinator")
    void compositionRootInstallsCoordinator() {
        assertInstanceOf(ConfigReloadCoordinator.class, installed);
    }

    @Test
    @DisplayName("ADR-115 RTP-68: reload runs beforeReload, then parser refresh after the swap, then regions")
    void reloadRunsLifecycleStepsInOrder() {
        List<String> steps = new ArrayList<>();
        Configs configs = RTP.configs;
        Object previousMap = configs.configParserMap;
        Configs.setReloadLifecycle(new ConfigReloadLifecycle() {
            @Override
            public void beforeReload() {
                steps.add("before:old=" + (configs.configParserMap == previousMap));
            }

            @Override
            public void onParsersChanged() {
                steps.add("parsers:old=" + (configs.configParserMap == previousMap));
            }

            @Override
            public void reloadRegions(Configs c) {
                assertSame(configs, c);
                steps.add("regions");
            }
        });
        configs.reloadAction();

        assertEquals(List.of("before:old=true", "parsers:old=false", "regions"), steps);
    }

    @Test
    @DisplayName("ADR-115 RTP-68: null lifecycle restores the no-op lifecycle")
    void nullRestoresNoOp() {
        Configs.setReloadLifecycle(null);
        assertSame(ConfigReloadLifecycle.NONE, Configs.getReloadLifecycle());
    }
}
