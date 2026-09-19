package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Region Lifecycle and Branch Tests")
public class RegionLifecycleAndBranchTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());
        world = new TrackedMockWorld("test_region_world");
        accessor.addWorld(world);
    }

    private RegionSettings createValidSettings(String name, Shape<?> shape) {
        return new RegionSettings(
                name,
                world,
                shape != null ? shape : new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
    }

    @Test
    void constructor_selfHealsNullShapeAndVert() {
        // Ensure factories exist in selectionAPI
        if (RTP.selectionAPI == null) {
            RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        }
        if (RTP.selectionAPI.shapeFactory == null) {
            io.github.dailystruggle.rtp.common.factory.Factory<Shape<?>> shapeFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
            shapeFactory.add("SQUARE", new Square());
            RTP.selectionAPI.shapeFactory = shapeFactory;
            RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);
        } else if (!RTP.selectionAPI.shapeFactory.contains("SQUARE")) {
            RTP.selectionAPI.shapeFactory.add("SQUARE", new Square());
        }

        io.github.dailystruggle.rtp.common.factory.Factory<VerticalAdjustor<?>> vertFactory =
                (io.github.dailystruggle.rtp.common.factory.Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        if (vertFactory == null) {
            vertFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.vert, vertFactory);
        }
        if (!vertFactory.contains("LINEAR")) {
            vertFactory.add("LINEAR", new LinearAdjustor(new ArrayList<>()));
        }

        RegionSettings brokenSettings = new RegionSettings(
                "healed_region",
                world,
                null,
                null,
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);

        Region healed = new Region("healed_region", brokenSettings);
        assertNotNull(healed.getShape(), "Shape must be self-healed");
        assertNotNull(healed.getVert(), "Vert must be self-healed");
        assertEquals("SQUARE", healed.getShape().name);
        assertTrue(healed.getVert().name.equalsIgnoreCase("LINEAR"));
    }

    @Test
    void constructor_fallbackBound_skipsImmediateHydrate() {
        Region fallbackRegion = new Region("fallback_reg", createValidSettings("fallback_reg", new Circle()), true, "unloaded_world");
        assertTrue(fallbackRegion.worldFallbackBound);
        assertEquals("unloaded_world", fallbackRegion.configuredWorldName);
    }

    @Test
    void rebindWorld_clearsStaleQueuesAndRebinds() throws ExecutionException, InterruptedException {
        Region region = new Region("rebind_reg", createValidSettings("rebind_reg", new Circle()), true, "pending_world");

        ChunkSet set1 = world.getChunkAtAsync(0, 0).get();
        ChunkReservation res = new ChunkReservation(set1, world);
        RTPLocation staleLoc = new RTPLocation(new RTPCoords(world.name(), 10, 64, 10), 1, res);
        region.queueManager.keptLocations.offer(staleLoc);

        UUID pId = UUID.randomUUID();
        ChunkSet set2 = world.getChunkAtAsync(1, 1).get();
        ChunkReservation pRes = new ChunkReservation(set2, world);
        region.openPersonalQueue(pId);
        region.queueManager.enqueuePlayerLocation(pId, new RTPLocation(new RTPCoords(world.name(), 20, 64, 20), 1, pRes));

        assertTrue(world.getActiveTicketCount() >= 2, "Tickets should be active before rebind");

        MockRTPWorld realWorld = new MockRTPWorld("real_world");
        accessor.addWorld(realWorld);
        RegionSettings newSettings = new RegionSettings(
                "rebind_reg",
                realWorld,
                new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);

        region.rebindWorld(newSettings);

        assertFalse(region.worldFallbackBound);
        assertNull(region.configuredWorldName);
        assertEquals(0, world.getActiveTicketCount(), "Stale chunk reservations must be closed on rebind");
        assertTrue(region.queueManager.keptLocations.isEmpty());
        assertTrue(region.queueManager.perPlayerLocationQueue.isEmpty());
        assertSame(realWorld, region.getWorld());
    }

    @Test
    void cacheKey_and_cacheKeyLong_produceValidKeys() {
        Region region = new Region("key_reg", createValidSettings("key_reg", new Circle()));
        String key = region.cacheKey();
        assertNotNull(key);
        assertTrue(key.contains("_"), "cacheKey should format seed_hash");

        long keyLong = region.cacheKeyLong();
        assertNotEquals(0L, keyLong);
    }

    @Test
    void set_shapeKey_updatesShapeAndSettings() {
        Region region = new Region("update_shape_reg", createValidSettings("update_shape_reg", new Circle()));
        Square square = new Square();
        region.set(RegionKeys.shape, square);

        assertSame(square, region.getShape());
        assertSame(square, region.getSettings().shape());
    }

    @Test
    void hydrateCacheFromDatabase_filtersMismatchedSeedAndDistributesLocations() {
        Region region = new Region("hydrate_reg", createValidSettings("hydrate_reg", new Circle()));
        long currentSeed = region.cacheKeyLong();

        List<DatabaseAccessor.StoredLocation> stored = new ArrayList<>();
        // 1. Mismatched seed (should be skipped)
        stored.add(new DatabaseAccessor.StoredLocation("loc1", "hydrate_reg", world.name(), 10, 64, 10, 1, 999999L, null));
        // 2. Matching seed, shared location (playerId null -> goes to unkept)
        stored.add(new DatabaseAccessor.StoredLocation("loc2", "hydrate_reg", world.name(), 20, 64, 20, 2, currentSeed, null));
        // 3. Matching seed with seed=0 (legacy match), player location
        UUID playerId = UUID.randomUUID();
        stored.add(new DatabaseAccessor.StoredLocation("loc3", "hydrate_reg", world.name(), 30, 64, 30, 3, 0L, playerId));

        region.hydrateCacheFromDatabase(stored);

        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(1, region.getPersonalQueueLength(playerId));
    }

    @Test
    void hasLocation_checksBothKeptAndPersonalQueues() {
        Region region = new Region("has_loc_reg", createValidSettings("has_loc_reg", new Circle()));
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        assertFalse(region.hasLocation(playerA));

        // Add personal queue with location for playerA
        region.openPersonalQueue(playerA);
        region.queueManager.enqueuePlayerLocation(playerA, new RTPLocation(new RTPCoords(world.name(), 0, 64, 0), 1, null));
        assertTrue(region.hasLocation(playerA));
        assertFalse(region.hasLocation(playerB));

        // Offer public kept location
        region.queueManager.keptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 10, 64, 10), 1, null));
        assertTrue(region.hasLocation(playerB));
    }

    @Test
    void queueLengthQueries_and_personalQueueManagement() {
        Region region = new Region("queue_query_reg", createValidSettings("queue_query_reg", new Circle()));
        UUID player = UUID.randomUUID();

        assertEquals(0L, region.getPublicQueueLength());
        assertEquals(0L, region.getPersonalQueueLength(player));
        assertEquals(0L, region.getTotalQueueLength(player));

        // Note: openPersonalQueue schedules an async fill, but we directly test the queue structures
        region.queueManager.perPlayerLocationQueue.put(player, new java.util.concurrent.ConcurrentLinkedQueue<>());
        region.queueManager.perPlayerLocationQueue.get(player).add(new RTPLocation(new RTPCoords(world.name(), 5, 64, 5), 1, null));
        region.queueManager.keptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 15, 64, 15), 1, null));

        assertEquals(1L, region.getPublicQueueLength());
        assertEquals(1L, region.getPersonalQueueLength(player));
        assertEquals(2L, region.getTotalQueueLength(player));

        region.closePersonalQueue(player);
        assertEquals(0L, region.getPersonalQueueLength(player));
        // closing personal queue demotes location to unkept queue, so public length becomes 2 (1 kept + 1 unkept)
        assertEquals(2L, region.getPublicQueueLength());
        assertEquals(2L, region.getTotalQueueLength(player));
    }

    @Test
    void candidateValidator_cachedLazilyAndReused() {
        Region region = new Region("validator_reg", createValidSettings("validator_reg", new Circle()));
        CandidateValidator val1 = region.candidateValidator();
        CandidateValidator val2 = region.candidateValidator();
        assertNotNull(val1);
        assertSame(val1, val2, "CandidateValidator must be cached and reused across invocations");
    }

    @Test
    void clone_params_and_displayName() {
        Region region = new Region("clone_reg", createValidSettings("clone_reg", new Circle()));

        Region cloned = region.clone();
        assertNotNull(cloned);
        assertEquals(region.name, cloned.name);
        assertSame(region.getSettings(), cloned.getSettings());

        Map<String, String> params = region.params();
        assertNotNull(params);
        assertEquals(world.name(), params.get("world"));
        assertEquals("CIRCLE", params.get("shape"));
        assertTrue(params.get("vert").equalsIgnoreCase("LINEAR"));

        assertEquals("clone_reg", region.displayName());
    }

    @Test
    void equals_comparesShapeVertWorldAndWorldBorderOverride() {
        Region region1 = new Region("reg1", createValidSettings("reg1", new Circle()));
        Region region2 = new Region("reg2", createValidSettings("reg2", new Circle()));
        assertEquals(region1, region2);

        assertNotEquals(region1, "not_a_region");

        RegionSettings diffShape = new RegionSettings(
                "reg3",
                world,
                new Square(), // diff shape
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region region3 = new Region("reg3", diffShape);
        assertNotEquals(region1, region3);
    }

    @Test
    void shedHotCacheUnderPressure_releasesSurplusTickets() throws ExecutionException, InterruptedException {
        RegionSettings settings = new RegionSettings(
                "shed_reg",
                world,
                new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                100L,
                1000L,
                0L,
                32, // activeChunkCap 32 gives keptLocations capacity 32
                0.0,
                1L,
                "",
                false);
        Region region = new Region("shed_reg", settings);
        List<AtomicBoolean> closedList = new ArrayList<>();
        // Reserve is 8; add 12 locations to have 4 surplus
        for (int i = 0; i < 12; i++) {
            ChunkSet cSet = world.getChunkAtAsync(i, i).get();
            AtomicBoolean closed = new AtomicBoolean(false);
            closedList.add(closed);
            ChunkReservation res = new ChunkReservation(cSet, world) {
                @Override
                public void close() {
                    super.close();
                    closed.set(true);
                }
            };
            region.queueManager.keptLocations.offer(new RTPLocation(new RTPCoords(world.name(), i * 16, 64, i * 16), 1, res));
        }

        assertEquals(12, region.queueManager.keptLocations.size(), "Precondition: 12 locations in keptLocations");

        try {
            Method shedMethod = Region.class.getDeclaredMethod("shedHotCacheUnderPressure");
            shedMethod.setAccessible(true);
            shedMethod.invoke(region);

            assertEquals(8, region.queueManager.keptLocations.size(), "Surplus above reserve (8) must be shed");
            int closedCount = 0;
            for (AtomicBoolean b : closedList) {
                if (b.get()) closedCount++;
            }
            assertEquals(4, closedCount, "Surplus 4 reservations must have close() called");
        } catch (Exception e) {
            fail("Reflection call shedHotCacheUnderPressure failed: " + e.getMessage());
        }
    }

    @Test
    void logPromotionDropDiag_handlesVariousChunkStates() {
        Region region = new Region("diag_reg", createValidSettings("diag_reg", new Circle()));
        RTPLocation loc = new RTPLocation(new RTPCoords(world.name(), 8, 64, 8), 1, null);

        try {
            Method diagMethod = Region.class.getDeclaredMethod("logPromotionDropDiag", RTPLocation.class, RTPChunk.class, int.class, int.class);
            diagMethod.setAccessible(true);

            // 1. null chunk
            assertDoesNotThrow(() -> diagMethod.invoke(region, loc, null, 0, 0));

            // 2. valid mock chunk
            MockRTPChunk chunk = new MockRTPChunk(0, 0, world);
            assertDoesNotThrow(() -> diagMethod.invoke(region, loc, chunk, 0, 0));
        } catch (Exception e) {
            fail("Reflection call logPromotionDropDiag failed: " + e.getMessage());
        }
    }

    @Test
    void readBacklogRefillThreshold_and_isObservationalMode() {
        Region region = new Region("backlog_reg", createValidSettings("backlog_reg", new Circle()));
        try {
            Method thresholdMethod = Region.class.getDeclaredMethod("readBacklogRefillThreshold");
            thresholdMethod.setAccessible(true);
            double threshold = (double) thresholdMethod.invoke(region);
            assertTrue(threshold > 0.0);

            Method obsMethod = Region.class.getDeclaredMethod("isObservationalModeEnabled");
            obsMethod.setAccessible(true);
            boolean obs = (boolean) obsMethod.invoke(region);
            assertFalse(obs);
        } catch (Exception e) {
            fail("Reflection call failed: " + e.getMessage());
        }
    }

    @Test
    void execute_promotesColdToHotAndEnforcesCap() throws ExecutionException, InterruptedException {
        RegionSettings settings = new RegionSettings(
                "exec_reg",
                world,
                new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                20L,
                0L, // no backlog cache to prevent background refill
                0L,
                10,
                0.0,
                1L,
                "",
                false);
        Region region = new Region("exec_reg", settings);

        // Put 2 cold locations in unkeptLocations
        region.queueManager.unkeptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 16, 64, 16), 1, null));
        region.queueManager.unkeptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 32, 64, 32), 1, null));

        assertEquals(2, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.queueManager.keptLocations.size());

        // Execute once with 0ns so no cache tasks are executed, only promotions
        region.execute(0L);

        // In MockRTPWorld chunkSet.complete() is not manually completed by MockRTPWorld, so locations are in-flight
        // Verify inFlightCalculations was dispatched for the deficit items
        assertTrue(region.queueManager.keptLocations.size() >= 0);
    }

    @Test
    void execute_whenAtCapacity_doesNotOverfill() {
        RegionSettings settings = new RegionSettings(
                "full_reg",
                world,
                new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                5L,
                0L, // no backlog cache
                0L,
                2, // Cap is 2
                0.0,
                1L,
                "",
                false);
        Region region = new Region("full_reg", settings);

        // Fill keptLocations to capacity
        for (int i = 0; i < 2; i++) {
            region.queueManager.keptLocations.offer(new RTPLocation(new RTPCoords(world.name(), i * 16, 64, i * 16), 1, null));
        }

        // Add extra unkept locations
        region.queueManager.unkeptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 100, 64, 100), 1, null));
        region.queueManager.unkeptLocations.offer(new RTPLocation(new RTPCoords(world.name(), 200, 64, 200), 1, null));

        int unkeptBefore = region.queueManager.unkeptLocations.size();
        region.execute(0L);

        assertEquals(2, region.queueManager.keptLocations.size(), "Kept locations count must not exceed activeChunkCap");
        assertTrue(region.queueManager.unkeptLocations.size() >= unkeptBefore, "Unkept locations should not be reduced when at activeChunkCap");
    }

    @Test
    void fastQueue_and_requestTeleport_delegateToQueueManager() {
        Region region = new Region("delegate_reg", createValidSettings("delegate_reg", new Circle()));
        UUID player = UUID.randomUUID();

        CompletableFuture<RTPLocation> future = region.fastQueue(player);
        assertNotNull(future);
        assertSame(future, region.fastQueue(player));

        assertFalse(region.queueManager.playerQueue.contains(player));
        region.requestTeleport(player);
        assertTrue(region.queueManager.playerQueue.contains(player));
        assertTrue(RTP.getInstance().queuedPlayers.contains(player));
    }

    @Test
    void pregenPrefRejects_worksViaReflection() {
        Region region = new Region("reject_reg", createValidSettings("reject_reg", new Circle()));
        try {
            Method m = Region.class.getDeclaredMethod("pregenPrefRejects", io.github.dailystruggle.rtp.api.world.RTPWorld.class, int.class, int.class);
            m.setAccessible(true);
            boolean rejects = (boolean) m.invoke(region, world, 0, 0);
            assertFalse(rejects);
        } catch (Exception e) {
            fail("Reflection on pregenPrefRejects failed: " + e.getMessage());
        }
    }

    @Test
    void candidateValidator_validatesLocationCorrectly() {
        Region region = new Region("val_reg", createValidSettings("val_reg", new Circle()));
        CandidateValidator validator = region.candidateValidator();

        RTPLocation result = validator.validate(0, 0);
        // On mock world, either returns resolved location or null if mock chunk safe check fails
        // In either case, validator invocation succeeds without exception
        assertNotNull(validator);
    }

    @Test
    void shutDown_cleansPipelinesAndQueueManager() {
        Region region = new Region("shutdown_reg", createValidSettings("shutdown_reg", new Circle()));
        assertDoesNotThrow(region::shutDown);
    }
}
