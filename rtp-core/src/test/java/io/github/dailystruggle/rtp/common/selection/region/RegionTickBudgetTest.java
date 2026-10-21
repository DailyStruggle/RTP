package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import io.github.dailystruggle.rtp.common.tools.CfDiag;
import io.github.dailystruggle.rtp.common.tools.ServerHealthGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Region.execute tick budget: promotion fan-out cap, minTPS/maxMSPT gate, sync-pipe verify")
class RegionTickBudgetTest {

    @TempDir
    Path tempDir;

    private TrackedMockWorld world;

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());
        world = new TrackedMockWorld("tick_budget_world");
        accessor.addWorld(world);
        ServerHealthGate.resetForTesting();
        MiscTasks.sync().clear();
        MiscTasks.sync().start();
    }

    @AfterEach
    void tearDown() {
        ServerHealthGate.resetForTesting();
        MiscTasks.sync().clear();
    }

    /** Cold cache full of candidates, hot cap far above the per-pulse promotion cap. */
    private Region regionWithColdCandidates(String name, int coldCount) {
        RegionSettings settings = new RegionSettings(
                name, world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false, coldCount, 0L, 0L, 64, 0.0, 1L, "", false);
        Region region = new Region(name, settings);
        region.cachePipeline.stop();
        for (int i = 0; i < coldCount; i++) {
            region.queueManager.unkeptLocations.offer(
                    new RTPLocation(new RTPCoords(world.name(), (i << 4) + 8, 64, 8), 1L, null));
        }
        return region;
    }

    @Test
    @DisplayName("a large hot deficit starts at most MAX_PROMOTIONS_PER_PULSE chunk loads per pulse")
    void promotionFanOutIsCappedPerPulse() {
        Region region = regionWithColdCandidates("fanout_reg", 40);
        long before = CfDiag.regionDeficitDispatch.sum();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));

        long dispatched = CfDiag.regionDeficitDispatch.sum() - before;
        assertTrue(dispatched >= 1, "the pulse must make progress");
        assertTrue(dispatched <= Region.MAX_PROMOTIONS_PER_PULSE,
                "dispatched " + dispatched + " promotions in one pulse");
    }

    @Test
    @DisplayName("REQ-RTP-S-005: on the tick thread, promotion verify is queued on the budgeted sync pipe")
    void verifyIsDeferredToSyncPipeOnTickThread() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RTPRuntime.serverAccessor().isPrimaryThread(),
                "harness runs callbacks off the primary thread");
        Region region = regionWithColdCandidates("verify_reg", 4);
        long before = MiscTasks.sync().size();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));

        assertTrue(MiscTasks.sync().size() > before,
                "chunk-load callbacks must hand verify to miscSyncTasks, not run it inline");
        assertTrue(region.inFlightCalculations.get() > 0, "promotions stay in flight until the sync pipe runs");

        MiscTasks.sync().execute(Long.MAX_VALUE);
        assertEquals(0, region.inFlightCalculations.get(), "draining the sync pipe settles every promotion");
    }

    @Test
    @DisplayName("minTPS gate: an unhealthy tick pauses cold->hot promotion; healthy resumes it")
    void unhealthyTickPausesPromotion() {
        Region region = regionWithColdCandidates("gate_reg", 20);
        ServerHealthGate.setReadingsForTesting(() -> 10.0, () -> 20.0);
        long before = CfDiag.regionDeficitDispatch.sum();

        region.execute(TimeUnit.MILLISECONDS.toNanos(25));

        assertEquals(0, CfDiag.regionDeficitDispatch.sum() - before, "no chunk loads while TPS is below minTPS");
        assertEquals(20, region.queueManager.unkeptLocations.size(), "cold candidates are left untouched");

        ServerHealthGate.setReadingsForTesting(() -> 20.0, () -> 20.0);
        region.execute(TimeUnit.MILLISECONDS.toNanos(25));
        assertTrue(CfDiag.regionDeficitDispatch.sum() - before > 0, "promotion resumes once the tick recovers");
    }

    @Test
    @DisplayName("ADR-087 regional circuit breaker: candidates in a saturated region stay cold, others promote")
    void saturatedRegionCandidatesStayCold() {
        Region region = regionWithColdCandidates("breaker_reg", 8);
        io.github.dailystruggle.rtp.api.scheduling.RTPScheduler original = RTPRuntime.scheduler();
        // Chunks x < 4 belong to a backlogged region; x >= 4 are healthy.
        RTPRuntime.setScheduler(new SaturationScheduler(original, cx -> cx < 4));
        try {
            long before = CfDiag.regionDeficitDispatch.sum();
            region.execute(TimeUnit.SECONDS.toNanos(1));

            long dispatched = CfDiag.regionDeficitDispatch.sum() - before;
            assertEquals(4, dispatched, "only the healthy region's candidates start chunk loads");
            int stillCold = 0;
            for (int i = 0; i < region.queueManager.unkeptLocations.size(); i++) {
                RTPLocation l = region.queueManager.unkeptLocations.get(i);
                if (l != null && (l.coords().x() >> 4) < 4) stillCold++;
            }
            assertEquals(4, stillCold, "saturated-region candidates are kept, not dropped");
        } finally {
            RTPRuntime.setScheduler(original);
        }
    }

    /** Delegating scheduler that reports chosen chunks as saturated. */
    private static final class SaturationScheduler implements io.github.dailystruggle.rtp.api.scheduling.RTPScheduler {
        private final io.github.dailystruggle.rtp.api.scheduling.RTPScheduler d;
        private final java.util.function.IntPredicate saturatedX;

        SaturationScheduler(io.github.dailystruggle.rtp.api.scheduling.RTPScheduler d,
                            java.util.function.IntPredicate saturatedX) {
            this.d = d;
            this.saturatedX = saturatedX;
        }

        @Override public boolean isRegionSaturated(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz) {
            return saturatedX.test(cx);
        }
        @Override public io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask runTaskAsynchronously(Runnable t) {
            return d.runTaskAsynchronously(t);
        }
        @Override public void runTask(Runnable t) { d.runTask(t); }
        @Override public void runTaskLater(Runnable t, long delay) { d.runTaskLater(t, delay); }
        @Override public Object runTaskTimer(Runnable t, long delay, long period) { return d.runTaskTimer(t, delay, period); }
        @Override public Object runTaskTimerAsynchronously(Runnable t, long delay, long period) {
            return d.runTaskTimerAsynchronously(t, delay, period);
        }
        @Override public void cancelTask(Object t) { d.cancelTask(t); }
        @Override public void runTaskForPlayer(io.github.dailystruggle.rtp.api.entity.RTPPlayer p,
                                               io.github.dailystruggle.rtp.common.tasks.RTPRunnable t, long delay) {
            d.runTaskForPlayer(p, t, delay);
        }
        @Override public void runTask(io.github.dailystruggle.rtp.api.world.RTPLocation l, Runnable t) { d.runTask(l, t); }
        @Override public void runTask(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz, Runnable t) {
            d.runTask(w, cx, cz, t);
        }
        @Override public Object runTaskTimer(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz,
                                             Runnable t, long delay, long period) {
            return d.runTaskTimer(w, cx, cz, t, delay, period);
        }
        @Override public void runTaskLater(io.github.dailystruggle.rtp.api.world.RTPWorld<?> w, int cx, int cz,
                                           Runnable t, long delay) {
            d.runTaskLater(w, cx, cz, t, delay);
        }
    }
}
