package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScanProgressBars} - the platform-neutral driver that
 * aggregates {@link RTP#scanTasks} metrics and pushes / clears on-screen
 * progress bars via the server accessor.
 */
class ScanProgressBarsTest {

    @TempDir
    File tempDir;

    private Region region;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        RTP.getInstance().scanTasks.clear();

        MockRTPWorld world = new MockRTPWorld("scanbar_world");
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "scanbar_region", world, square, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("scanbar_region", settings);
        RTP.selectionAPI.permRegionLookup.put("scanbar_region", region);
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().scanTasks.clear();
        RTP.selectionAPI.permRegionLookup.clear();
    }

    @SuppressWarnings("unchecked")
    private void setBossBarTemplate(String template) {
        ConfigParser<CommandMessages> lang =
                (ConfigParser<CommandMessages>) RTP.configs.getParser(CommandMessages.class);
        lang.setData(Map.of("scanBossBar", template));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void update_withEmptyTemplate_doesNotThrow() {
        setBossBarTemplate("");
        RTP.getInstance().scanTasks.put("scanbar_region", new ScanTask(region, 0L));
        assertDoesNotThrow(ScanProgressBars::update);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void update_withNoScanTasks_doesNotThrow() {
        setBossBarTemplate("[scan_regions] [scan_landPercentage]%");
        RTP.getInstance().scanTasks.clear();
        assertDoesNotThrow(ScanProgressBars::update);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void update_withActiveScan_substitutesPlaceholders_withoutThrow() {
        setBossBarTemplate("&aScan [scan_regions] [scan_chunks]/[scan_totalChunks] "
                + "cps=[scan_cps] pct=[scan_landPercentage]% eta=[scan_eta]");

        ScanTask task = new ScanTask(region, 0L);
        task.latestAbsolutePos = 50L;
        task.latestAbsoluteTotal = 200L;
        task.latestCps = 10L;
        task.latestEtaSeconds = 90L;
        RTP.getInstance().scanTasks.put("scanbar_region", task);

        assertDoesNotThrow(ScanProgressBars::update);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void update_withZeroTotalChunks_doesNotThrow() {
        setBossBarTemplate("[scan_landPercentage]%");
        ScanTask task = new ScanTask(region, 0L);
        task.latestAbsolutePos = 0L;
        task.latestAbsoluteTotal = 0L; // guards the divide-by-zero branch
        RTP.getInstance().scanTasks.put("scanbar_region", task);
        assertDoesNotThrow(ScanProgressBars::update);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void update_landPercentageUsesScanTally_progressUsesChunkFraction() {
        setBossBarTemplate("land=[scan_landPercentage] progress=[scan_progress]");

        ScanTask task = new ScanTask(region, 0L);
        task.latestAbsolutePos = 50L;
        task.latestAbsoluteTotal = 200L;
        task.latestLandPercentage = 87.5;
        RTP.getInstance().scanTasks.put("scanbar_region", task);

        java.util.concurrent.atomic.AtomicReference<Map<String, io.github.dailystruggle.rtp.api.server.ProgressBar>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.dailystruggle.rtp.api.server.RTPServerAccessor original = RTP.serverAccessor;
        RTP.serverAccessor = new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(tempDir) {
            @Override
            public void updateProgressBars(Map<String, io.github.dailystruggle.rtp.api.server.ProgressBar> bars) {
                captured.set(bars);
            }
        };
        try {
            ScanProgressBars.update();
        } finally {
            RTP.serverAccessor = original;
        }

        assertNotNull(captured.get(), "update() must push bars while a scan is active");
        io.github.dailystruggle.rtp.api.server.ProgressBar bar = captured.get().get("scanbar_region");
        assertNotNull(bar);
        assertEquals("land=87.5 progress=25.0", bar.title());
        assertEquals(0.25, bar.progress(), 1e-9);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void clear_doesNotThrow() {
        assertDoesNotThrow(ScanProgressBars::clear);
    }
}
