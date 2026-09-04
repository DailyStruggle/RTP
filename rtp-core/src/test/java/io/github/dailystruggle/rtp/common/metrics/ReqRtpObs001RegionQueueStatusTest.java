package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.metrics.api.RegionQueueRow;
import io.github.dailystruggle.metrics.api.RegionQueueStatus;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-RTP-OBS-001: RegionQueueStatus Telemetry (METRICS_PLAN.md Phase M2)")
class ReqRtpObs001RegionQueueStatusTest {

    @Test
    @DisplayName("CoreMetrics.snapshot collects regionQueueStatus rows with stage occupancy and status")
    void snapshotCollectsRegionQueueStatus(@TempDir File tempDir) {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);

        MockRTPWorld world = new MockRTPWorld("test_world");
        accessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "test_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                50L, 0L, 0L, 10, 0.0, 16L, null, false
        );
        Region region = new Region("test_region", settings);
        RTP.selectionAPI.permRegionLookup.put("test_region", region);

        try {
            CoreMetrics metrics = new CoreMetrics();
            MetricsSnapshot snapshot = metrics.snapshot();
            RTPMetricsExtension ext = snapshot.extension(RTPMetricsExtension.class);

            assertNotNull(ext);
            assertNotNull(ext.regionQueueStatus);
            assertTrue(ext.regionQueueStatus.containsKey("test_region"));

            RegionQueueRow row = ext.regionQueueStatus.get("test_region");
            assertNotNull(row);
            assertEquals(0, row.playerQueueDepth);
            assertEquals(0, row.keptFill);
            assertEquals(10, row.keptCap);
            assertEquals(0, row.unkeptFill);
            assertEquals(50, row.unkeptCap);
            assertEquals(RegionQueueStatus.EMPTY, row.status);

            Map<String, Integer> occupancy = row.stageOccupancy;
            assertNotNull(occupancy);
            assertEquals(0, occupancy.get("hot"));
            assertEquals(0, occupancy.get("cold"));

            // Verify with queued player -> SATURATED
            UUID playerUuid = UUID.randomUUID();
            region.queueManager.playerQueue.add(playerUuid);

            MetricsSnapshot saturatedSnap = metrics.snapshot();
            RTPMetricsExtension satExt = saturatedSnap.extension(RTPMetricsExtension.class);
            RegionQueueRow satRow = satExt.regionQueueStatus.get("test_region");
            assertEquals(1, satRow.playerQueueDepth);
            assertEquals(RegionQueueStatus.SATURATED, satRow.status);
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("test_region");
        }
    }
}
