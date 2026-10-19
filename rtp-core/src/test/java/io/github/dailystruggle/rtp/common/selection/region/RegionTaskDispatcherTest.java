package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.lifecycle.RegionTaskCoordinator;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-115 (RTP-68): regions start scan and teleport tasks through {@link RegionTaskDispatcher}. */
class RegionTaskDispatcherTest {

    @TempDir
    File tempDir;

    private RegionTaskDispatcher installed;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        installed = Region.getTaskDispatcher();
    }

    @AfterEach
    void tearDown() {
        Region.setTaskDispatcher(installed);
        RTP.getInstance().scanTasks.clear();
    }

    private static RegionSettings settings(String name) {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        return new RegionSettings(
                name, new MockRTPWorld(name + "_world"), square, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    }

    @Test
    @DisplayName("ADR-115 RTP-68: composition root installs the region task coordinator")
    void compositionRootInstallsCoordinator() {
        assertInstanceOf(RegionTaskCoordinator.class, installed);
    }

    @Test
    @DisplayName("ADR-115 RTP-68: a bound region with a memory shape asks the dispatcher to resume its scan")
    void constructorResumesScanThroughDispatcher() {
        List<Region> resumed = new ArrayList<>();
        Region.setTaskDispatcher(new RecordingDispatcher(resumed));

        Region region = new Region("dispatch_region", settings("dispatch_region"));

        assertEquals(List.of(region), resumed);
    }

    @Test
    @DisplayName("ADR-115 RTP-68: a dormant region does not resume its scan until rebound")
    void dormantRegionSkipsScanResume() {
        List<Region> resumed = new ArrayList<>();
        Region.setTaskDispatcher(new RecordingDispatcher(resumed));

        Region region = new Region("dormant_region", settings("dormant_region"), true, "not_loaded");
        assertTrue(resumed.isEmpty());

        region.rebindWorld(settings("dormant_region"));
        assertEquals(List.of(region), resumed);
    }

    @Test
    @DisplayName("ADR-115 RTP-68: coordinator registers no scan task when no scan progress is stored")
    void coordinatorSkipsResumeWithoutProgress() {
        Region region = new Region("no_progress", settings("no_progress"));
        RTP.getInstance().scanTasks.clear();

        new RegionTaskCoordinator().resumeScan(region);

        assertFalse(RTP.getInstance().scanTasks.containsKey("no_progress"));
    }

    @Test
    @DisplayName("ADR-115 S-004: without an installed dispatcher, teleport dispatch and cancel fail closed")
    void noneFailsClosedOnTeleports() {
        Region.setTaskDispatcher(null);
        RegionTaskDispatcher none = Region.getTaskDispatcher();
        assertSame(RegionTaskDispatcher.NONE, none);

        TeleportData data = new TeleportData();
        assertThrows(IllegalStateException.class,
                () -> none.dispatchTeleport(data, new GenerationContext(null, null, null), null,
                        new RTPCoords("w", 0, 64, 0), null));
        assertThrows(IllegalStateException.class,
                () -> none.cancelTeleport(UUID.randomUUID(), data, true));
        assertNull(data.nextTask);
    }

    /** Records scan resumes; teleport methods are not reached by these tests. */
    private static final class RecordingDispatcher implements RegionTaskDispatcher {
        private final List<Region> resumed;

        RecordingDispatcher(List<Region> resumed) {
            this.resumed = resumed;
        }

        @Override
        public void resumeScan(Region region) {
            resumed.add(region);
        }

        @Override
        public void dispatchTeleport(TeleportData data, GenerationContext context, Region region,
                                     RTPCoords coords, ChunkReservation reservation) {
            fail("unexpected teleport dispatch");
        }

        @Override
        public void cancelTeleport(UUID playerId, TeleportData data, boolean cancelNow) {
            fail("unexpected teleport cancel");
        }

        @Override
        public int teleportViewDistance() {
            return 0;
        }
    }
}
