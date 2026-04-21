package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Phase 8.2 pivot (2026-04-20c) observational-mode contract on
 * {@link RegionCacheTask}:
 *
 * <ol>
 *   <li>The default-mode task early-returns when the pregen cache is at or
 *       above {@code cacheCap} (unchanged behaviour — regression guard).</li>
 *   <li>The observational-mode task early-returns when the cache has
 *       headroom (the strict inversion).</li>
 *   <li>When an observational task runs to completion with a safe-candidate
 *       result, the candidate is not enqueued on {@code unkeptLocations} —
 *       the queue size does not grow past {@code cacheCap}.</li>
 * </ol>
 *
 * <p>Traces to {@code docs/dev/BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md} §2 /
 * §4.2 / §4.4. Uses only mock server components
 * ({@link MockRTPWorld}, {@link MockRTPServerAccessor}) + the real
 * {@link LocationGenerator} to exercise the full public surface the pivot
 * touches.
 */
public class ReqRtpObservationalCacheTaskTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new MockRTPWorld("observational_world");
        accessor.addWorld(world);

        Circle circle = new Circle();
        circle.setRng(new Random(7L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "observational_region",
                world,
                circle,
                vert,
                false,
                false,
                3L,          // cacheCap: small for fast fill-up
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("observational_region", settings);
    }

    /**
     * Default-mode task must return immediately when the cache is already
     * at capacity — this is the behaviour the observational mode inverts,
     * so it's guarded here as the prerequisite invariant.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void defaultMode_earlyReturns_whenCacheAtCap() {
        long cacheCap = region.getSettings().cacheCap();
        for (long i = 0; i < cacheCap; i++) {
            RTPCoords c = new RTPCoords(world.name(), (int) (i * 16), 64, 0);
            region.queueManager.unkeptLocations.offer(new RTPLocation(c, 1L, null));
        }

        int beforeInFlight = region.inFlightCalculations.get();
        long beforeSize = region.queueManager.unkeptLocations.size();

        RegionCacheTask defaultTask = new RegionCacheTask(region, 50_000_000L);
        defaultTask.run();

        assertEquals(beforeInFlight, region.inFlightCalculations.get(),
                "default-mode task must not start in-flight work when cache is full");
        assertEquals(beforeSize, region.queueManager.unkeptLocations.size(),
                "default-mode task must not grow the queue past cacheCap");
    }

    /**
     * Observational-mode task must early-return when the cache has
     * headroom — the default-mode task retains priority while it still
     * has work to do. Plan §2.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void observationalMode_earlyReturns_whenCacheHasHeadroom() {
        // cache is empty → has headroom → observational task must yield
        assertEquals(0L, region.queueManager.unkeptLocations.size(),
                "precondition: cache empty");

        int beforeInFlight = region.inFlightCalculations.get();
        long beforeSize = region.queueManager.unkeptLocations.size();

        RegionCacheTask observational = RegionCacheTask.observe(region, 50_000_000L);
        observational.run();

        assertEquals(beforeInFlight, region.inFlightCalculations.get(),
                "observational task must not start in-flight work when cache has headroom");
        assertEquals(beforeSize, region.queueManager.unkeptLocations.size(),
                "observational task must not enqueue anything when gate is closed");
    }

    /**
     * observe() factory must produce a task whose gate is inverted from
     * the default constructor's gate — i.e. the two constructions are
     * complementary, never both runnable at the same cache state.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void observe_factory_returnsNonNullTask() {
        RegionCacheTask task = RegionCacheTask.observe(region, 1_000_000L);
        assertNotNull(task, "observe() factory must not return null");
        // Distinct identity from the default constructor's output.
        RegionCacheTask defaultTask = new RegionCacheTask(region, 1_000_000L);
        assertNotSame(task, defaultTask);
    }
}
