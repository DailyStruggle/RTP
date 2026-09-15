package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression guard for {@code inFlightCalculations} accounting in {@link RegionCacheTask}.
 */
public class RegionCacheTaskInFlightLeakTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());

        MockRTPWorld world = new MockRTPWorld("inflight_leak_world");
        accessor.addWorld(world);

        Circle circle = new Circle();
        circle.setRng(new Random(11L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "inflight_leak_region",
                world,
                circle,
                vert,
                false,
                false,
                4L,          // cacheCap: small but non-zero so the default-mode gate opens
                1000L,
                 0L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("inflight_leak_region", settings);
    }

    /**
     * A {@link RegionCacheTask} whose backing location future completes
     * normally with {@code null} must not leak {@code inFlightCalculations}.
     * Prior to the fix, {@code inFlightCalculations} stayed at {@code 1}
     * after {@code run()}, freezing the cache top-up gate one slot short of
     * {@code cacheCap + activeChunkCap} and producing the
     * {@code "cached: 59"} symptom against a 60-slot total.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_completesNullResult_releasesInFlightCounter() {
        accessor.setLocationGenerator(new NullReturningLocationGenerator());

        int beforeInFlight = region.inFlightCalculations.get();
        long beforeSize = region.queueManager.unkeptLocations.size();

        RegionCacheTask task = new RegionCacheTask(region, 50_000_000L);
        task.run();

        assertEquals(beforeInFlight, region.inFlightCalculations.get(),
                "inFlightCalculations must net to its pre-run value when the "
                        + "location future completes normally with null "
                        + "(regression guard for the [cached]=cacheCap-1 leak).");
        assertEquals(beforeSize, region.queueManager.unkeptLocations.size(),
                "a null result must not enqueue anything onto unkeptLocations");
    }

    /**
     * Completing exceptionally releases in-flight counter exactly once.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_exceptionalCompletion_releasesInFlightCounterExactlyOnce() {
        accessor.setLocationGenerator(new ThrowingLocationGenerator());

        int beforeInFlight = region.inFlightCalculations.get();

        RegionCacheTask task = new RegionCacheTask(region, 50_000_000L);
        task.run();

        assertEquals(beforeInFlight, region.inFlightCalculations.get(),
                "inFlightCalculations must net to its pre-run value when the "
                        + "location future completes exceptionally (no double-decrement, no leak).");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_playerSpecificQueue_enqueuesPlayerLocation() {
        UUID playerId = UUID.randomUUID();
        region.openPersonalQueue(playerId);
        RTPCoords coords = new RTPCoords("inflight_leak_world", 100, 64, 100);
        GenerationResult successfulResult = new GenerationResult(coords, 1, null);

        accessor.setLocationGenerator(new ILocationGenerator() {
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
                return CompletableFuture.completedFuture(successfulResult);
            }
            @Override
            public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
                return CompletableFuture.completedFuture(successfulResult);
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
                return CompletableFuture.completedFuture(successfulResult);
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
                return CompletableFuture.completedFuture(successfulResult);
            }
        });

        RegionCacheTask task = new RegionCacheTask(region, playerId, 50_000_000L);
        task.run();

        // Check that player personal queue received the location
        java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> pQueue = region.queueManager.getPerPlayerQueue(playerId);
        org.junit.jupiter.api.Assertions.assertNotNull(pQueue);
        org.junit.jupiter.api.Assertions.assertFalse(pQueue.isEmpty());
        assertEquals(coords, pQueue.peek().coords());
        assertEquals(0, region.inFlightCalculations.get());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void run_publicQueueSuccessfulGeneration_enqueuesToUnkeptLocations() {
        RTPCoords coords = new RTPCoords("inflight_leak_world", 200, 64, 200);
        GenerationResult successfulResult = new GenerationResult(coords, 2, null);

        accessor.setLocationGenerator(new ILocationGenerator() {
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
                return CompletableFuture.completedFuture(successfulResult);
            }
            @Override
            public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
                return CompletableFuture.completedFuture(successfulResult);
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
                return CompletableFuture.completedFuture(successfulResult);
            }
            @Override
            public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
                return CompletableFuture.completedFuture(successfulResult);
            }
        });

        int initialUnkept = region.queueManager.unkeptLocations.size();
        RegionCacheTask task = new RegionCacheTask(region, 50_000_000L);
        task.run();

        assertEquals(initialUnkept + 1, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.inFlightCalculations.get());
    }

    /**
     * Stub generator that completes every request synchronously with
     * {@code null}. Reproduces the {@code LocationGenerator.getLocationFuture}
     * normal-null exits (lines 105 / 181 / 188) without dragging in the full
     * pregen pipeline.
     */
    private static final class NullReturningLocationGenerator implements ILocationGenerator {
        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(
                Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Stub generator whose futures always complete exceptionally. Drives the
     * {@code exceptionally → thenAccept(null) → processResult(null)} chain
     * that the {@code releaseInFlight()} CAS must idempotently absorb.
     */
    private static final class ThrowingLocationGenerator implements ILocationGenerator {
        private static CompletableFuture<GenerationResult> failed() {
            CompletableFuture<GenerationResult> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("synthetic generator failure"));
            return f;
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
            return failed();
        }

        @Override
        public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
            return failed();
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(
                Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
            return failed();
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
            return failed();
        }
    }
}
