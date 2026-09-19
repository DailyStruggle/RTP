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
 * Verifies the observational-mode contract on {@link RegionCacheTask}.
 * Checks early returns on capacity/headroom and ensures queue sizes respect cacheCap.
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
                1000L,
                 0L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("observational_region", settings);
    }

    /**
     * Default-mode task must return immediately when the cache is already
     * at capacity - this is the behaviour the observational mode inverts,
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
     * headroom - the default-mode task retains priority while it still
     * has work to do.
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
     * the default constructor's gate - i.e. the two constructions are
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

    /**
     * Regression guard for perpetual {@code [cached] = totalCap - 1} symptom,
     * ensuring cache deficit computation reaches full totalCap with observational mode enabled.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void execute_withObservationalEnabled_reachesFullCacheCapPlusActiveCap() {
        long totalCap = region.getSettings().cacheCap() + region.getSettings().activeChunkCap();
        long previous = -1L;
        long current = region.getPublicQueueLength();
        // Bounded retry: each pulse should add at least one location until
        // saturation. 64 pulses comfortably covers the 8-slot total even
        // with cache-pipeline yielding.
        for (int i = 0; i < 64 && current < totalCap; i++) {
            region.execute(Long.MAX_VALUE);
            previous = current;
            current = region.getPublicQueueLength();
            if (current == previous && current >= totalCap - 1) {
                // Saturation candidate: one more pulse to confirm we're not
                // mid-promotion (kept polled, awaiting async chunk-load).
                region.execute(Long.MAX_VALUE);
                current = region.getPublicQueueLength();
                break;
            }
        }
        assertEquals(totalCap, current,
                "with observational mode enabled, [cached] must reach "
                        + "cacheCap + activeChunkCap (regression guard for the "
                        + "off-by-one cache-deficit bug - \"cached: 59\" symptom).");
    }
}
