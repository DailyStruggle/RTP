package io.github.dailystruggle.rtp.common.tasks.scan;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ScanTask skips circumscribing-key positions outside the shape without marking them bad")
class ScanTaskShapeBoundsTest {

    @TempDir
    File tempDir;

    private final List<int[]> probed = new CopyOnWriteArrayList<>();
    private Region region;
    private CircleOptimizedDualLayer circle;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        MockRTPWorld world = new MockRTPWorld("scan_bounds_world") {
            @Override
            public boolean isChunkGenerated(int cx, int cz) {
                probed.add(new int[]{cx, cz});
                return super.isChunkGenerated(cx, cz);
            }

            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                probed.add(new int[]{cx, cz});
                return super.probeChunkColumn(cx, cz, minY, maxY);
            }
        };
        circle = new CircleOptimizedDualLayer("CIRCLE_SCAN_BOUNDS", 8);
        circle.set(GenericMemoryShapeParams.radius, 40L);
        circle.set(GenericMemoryShapeParams.centerRadius, 16L);

        RegionSettings settings = new RegionSettings(
                "scan_bounds_region", world, circle, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("scan_bounds_region", settings);
        ActiveSelection.get().permRegionLookup.put("scan_bounds_region", region);
        ScanTasks.current().clear();
    }

    @AfterEach
    void tearDown() {
        ScanTasks.current().clear();
    }

    private long counter(ScanTask task, String name) throws Exception {
        Field f = ScanTask.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((AtomicLong) f.get(task)).get();
    }

    @Test
    @Timeout(60)
    @DisplayName("Out-of-circle keys are neither probed, counted nor recorded as bad")
    void outOfShapeKeysAreSkipped() throws Exception {
        long range = circle.getRange();
        MutableRTPCoords c = new MutableRTPCoords(0, 0);
        long inShape = 0;
        long outside = 0;
        for (long pos = 0; pos < range; pos++) {
            circle.locationToXZ(pos, c);
            if (circle.contains(c.x, c.z)) inShape++;
            else outside++;
        }
        assertTrue(outside > 0, "fixture must contain circumscribing keys outside the circle");

        ScanTask task = new ScanTask(region, 0L);
        task.scanIncrement.set(range);
        task.run();

        for (int[] xz : probed) {
            assertTrue(circle.contains(xz[0], xz[1]),
                    "probed chunk outside the shape: (" + xz[0] + "," + xz[1] + ")");
        }
        assertEquals(inShape, counter(task, "scanGoodCount") + counter(task, "scanBadCount"));

        for (long pos = 0; pos < range; pos++) {
            circle.locationToXZ(pos, c);
            if (!circle.contains(c.x, c.z)) {
                assertFalse(circle.isKnownBad(pos), "out-of-shape key recorded as bad: " + pos);
            }
        }
    }
}
