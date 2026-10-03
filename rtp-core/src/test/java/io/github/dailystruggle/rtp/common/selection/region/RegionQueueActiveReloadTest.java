package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests verifying dynamic configuration reloads (/rtp reload)
 * while region queues are active (REQ-RTP-S-002, REQ-PAPER-ARCH-001/005).
 */
@DisplayName("Dynamic configuration reloads (/rtp reload) while region queues are active")
public class RegionQueueActiveReloadTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;
    private File regionsDir;

    @BeforeEach
    void setUp() throws IOException {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new TrackedMockWorld("reload_world");
        accessor.addWorld(world);

        // Ensure definitions/worlds directory exists with a default.yml template per ADR-076
        Path worldsDir = tempDir.resolve("definitions").resolve("worlds");
        Files.createDirectories(worldsDir);
        Files.writeString(worldsDir.resolve("default.yml"),
                "requirePermission: false\nregion: default\noverride: none\nversion: \"1.0\"\n");

        // Ensure definitions/regions directory exists per ADR-076
        regionsDir = tempDir.resolve("definitions").resolve("regions").toFile();
        if (!regionsDir.exists()) {
            regionsDir.mkdirs();
        }

        MemoryTracker.reset();
    }

    @AfterEach
    void tearDown() {
        MemoryTracker.reset();
    }

    /**
     * Requirement 1:
     * Calling RTP.reload() while RegionQueueManager fill tasks are actively polling or generating locations.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("RTP.reload() while fill tasks actively poll and generate locations executes cleanly without deadlocks or errors")
    void testReloadWhileQueueManagerFillTasksActivelyPollingAndGenerating() throws Exception {
        // Create initial region config on disk
        String regionYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: circle\n" +
                "  radius: 1000\n" +
                "  center:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 50\n";
        Files.writeString(new File(regionsDir, "active_reg.yml").toPath(), regionYaml);

        // Initial reload to register region
        assertTrue(RTP.reload(), "Initial RTP.reload() must succeed");

        Region region = RTP.selectionAPI.permRegionLookup.get("active_reg");
        assertNotNull(region, "Region active_reg should be registered");

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        // Worker 1: actively submit RegionCacheTasks
        futures.add(executor.submit(() -> {
            while (running.get()) {
                try {
                    Region current = RTP.selectionAPI.permRegionLookup.get("active_reg");
                    if (current != null) {
                        current.cachePipeline.add(new RegionCacheTask(current, 50_000_000L));
                        current.execute(10_000_000L);
                    }
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable ignored) {
                }
            }
        }));

        // Worker 2: actively poll from RegionQueueManager
        futures.add(executor.submit(() -> {
            while (running.get()) {
                try {
                    Region current = RTP.selectionAPI.permRegionLookup.get("active_reg");
                    if (current != null && current.queueManager != null) {
                        current.queueManager.poll(UUID.randomUUID());
                    }
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable ignored) {
                }
            }
        }));

        // Worker 3: generate candidates via LocationGenerator
        futures.add(executor.submit(() -> {
            while (running.get()) {
                try {
                    Region current = RTP.selectionAPI.permRegionLookup.get("active_reg");
                    if (current != null && RTP.serverAccessor != null && RTP.serverAccessor.getLocationGenerator() != null) {
                        RTP.serverAccessor.getLocationGenerator().getLocation(current, (java.util.Set<String>) null);
                    }
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable ignored) {
                }
            }
        }));

        // Let background activity ramp up
        Thread.sleep(100);

        // Perform dynamic reload while workers are in full flight
        boolean reloadSuccess = false;
        try {
            reloadSuccess = RTP.reload();
        } finally {
            running.set(false);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor threads should terminate cleanly");
        }

        assertTrue(reloadSuccess, "RTP.reload() must return true while fill tasks were actively executing");

        // Verify that region was re-instantiated and is still accessible
        Region reloaded = RTP.selectionAPI.permRegionLookup.get("active_reg");
        assertNotNull(reloaded, "Region must be reloaded and present in permRegionLookup");
        assertNotNull(reloaded.queueManager, "Reloaded region must have an active RegionQueueManager");
    }

    /**
     * Requirement 2:
     * Validating that pre-cached locations outside the new boundaries are purged immediately.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Pre-cached locations outside new boundaries are purged immediately upon reload")
    void testPreCachedLocationsOutsideNewBoundariesPurgedImmediately() throws Exception {
        // Initial region: large radius = 1000, center = (0, 0)
        String initialYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: circle\n" +
                "  radius: 1000\n" +
                "  center:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 50\n";
        File regFile = new File(regionsDir, "boundary_reg.yml");
        Files.writeString(regFile.toPath(), initialYaml);

        assertTrue(RTP.reload(), "Initial RTP.reload() must succeed");

        Region initialRegion = RTP.selectionAPI.permRegionLookup.get("boundary_reg");
        assertNotNull(initialRegion);

        // Pre-cache locations:
        // locInsideNew: (50 * 16, 64, 50 * 16) -> chunk (50, 50) dist ~70.7 <= 200 chunks
        // locOutsideNew: (500 * 16, 64, 500 * 16) -> chunk (500, 500) dist ~707.1 > 200 chunks
        RTPCoords coordsInside = new RTPCoords(world.name(), 50 * 16, 64, 50 * 16);
        RTPCoords coordsOutside = new RTPCoords(world.name(), 500 * 16, 64, 500 * 16);

        ChunkSet chunkInside = world.getChunkAtAsync(50, 50).get();
        ChunkReservation resInside = new ChunkReservation(chunkInside, world);
        RTPLocation locInside = new RTPLocation(coordsInside, 1, resInside);

        ChunkSet chunkOutside = world.getChunkAtAsync(500, 500).get();
        ChunkReservation resOutside = new ChunkReservation(chunkOutside, world);
        RTPLocation locOutside = new RTPLocation(coordsOutside, 1, resOutside);

        // Place in kept queue
        initialRegion.queueManager.keptLocations.offerSilently(locInside);
        initialRegion.queueManager.keptLocations.offerSilently(locOutside);

        assertEquals(2, initialRegion.queueManager.keptLocations.size(), "Kept queue should contain both locations");

        // Now update config: shrink radius from 1000 down to 200!
        String newYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: circle\n" +
                "  radius: 200\n" +
                "  center:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 50\n";
        Files.writeString(regFile.toPath(), newYaml);

        // Trigger dynamic configuration reload
        assertTrue(RTP.reload(), "RTP.reload() must succeed");

        // The old region was shut down and all its candidates purged/closed
        Region reloadedRegion = RTP.selectionAPI.permRegionLookup.get("boundary_reg");
        assertNotNull(reloadedRegion);
        assertEquals(200, ((Number) reloadedRegion.getShape().getData().get(GenericMemoryShapeParams.radius)).intValue(),
                "Reloaded region must reflect the new radius of 200");

        // Confirm the old outside location is not in the reloaded region
        for (int i = 0; i < reloadedRegion.queueManager.keptLocations.size(); i++) {
            RTPLocation loc = reloadedRegion.queueManager.keptLocations.get(i);
            if (loc != null) {
                int cx = loc.coords().x() >> 4;
                int cz = loc.coords().z() >> 4;
                assertTrue(reloadedRegion.getShape().contains(cx, cz),
                        "All locations in reloaded region must be within the new 200-radius boundaries");
                assertNotEquals(500 * 16, loc.coords().x(), "Pre-cached location outside new boundaries must not remain");
            }
        }

        // Also test unit method purgeOutsideBounds on an existing region
        Circle smallerCircle = new Circle();
        smallerCircle.set(GenericMemoryShapeParams.radius, 200L);
        smallerCircle.set(GenericMemoryShapeParams.centerRadius, 0L);
        smallerCircle.set(GenericMemoryShapeParams.centerX, 0L);
        smallerCircle.set(GenericMemoryShapeParams.centerZ, 0L);

        // Offer locations to reloadedRegion to test immediate purgeOutsideBounds
        ChunkSet cOut = world.getChunkAtAsync(500, 500).get();
        ChunkReservation rOut = new ChunkReservation(cOut, world);
        RTPLocation outCandidate = new RTPLocation(coordsOutside, 1, rOut);

        ChunkSet cIn = world.getChunkAtAsync(50, 50).get();
        ChunkReservation rIn = new ChunkReservation(cIn, world);
        RTPLocation inCandidate = new RTPLocation(coordsInside, 1, rIn);

        reloadedRegion.queueManager.keptLocations.offerSilently(outCandidate);
        reloadedRegion.queueManager.keptLocations.offerSilently(inCandidate);

        int purgedCount = reloadedRegion.purgeOutsideBounds();
        assertEquals(1, purgedCount, "purgeOutsideBounds should purge exactly the 1 out-of-bounds candidate");
        assertEquals(1, reloadedRegion.queueManager.keptLocations.size(), "Only inside location should remain");
        assertEquals(50 * 16, reloadedRegion.queueManager.keptLocations.peek().coords().x());
    }

    /**
     * Requirement 3:
     * Confirming that chunk tickets registered with MemoryTracker for evicted candidate locations
     * are cleanly closed (Rule S-002, no permanently force-loaded chunks).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Chunk tickets registered with MemoryTracker for evicted candidate locations are cleanly closed (Rule S-002)")
    void testEvictedCandidateLocationChunkTicketsCleanlyClosedWithMemoryTracker() throws Exception {
        String regionYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: circle\n" +
                "  radius: 1000\n" +
                "  center:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 50\n";
        File regFile = new File(regionsDir, "ticket_reg.yml");
        Files.writeString(regFile.toPath(), regionYaml);

        assertTrue(RTP.reload());

        Region region = RTP.selectionAPI.permRegionLookup.get("ticket_reg");
        assertNotNull(region);

        // Pre-cache 3 locations holding chunk reservations
        List<ChunkReservation> reservations = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int x = i * 200;
            int z = i * 200;
            RTPCoords coords = new RTPCoords(world.name(), x, 64, z);
            ChunkSet chunkSet = world.getChunkAtAsync(x >> 4, z >> 4).get();
            ChunkReservation reservation = new ChunkReservation(chunkSet, world);
            reservations.add(reservation);
            RTPLocation loc = new RTPLocation(coords, 1, reservation);
            region.queueManager.keptLocations.offerSilently(loc);
        }

        // Verify precondition: active tickets are recorded on the world and tracked by MemoryTracker
        assertTrue(world.getActiveTicketCount() > 0, "Precondition: active chunk tickets must be > 0");
        assertTrue(MemoryTracker.activeTickets() > 0, "Precondition: MemoryTracker.activeTickets() must be > 0");
        assertEquals(world.getActiveTicketCount(), MemoryTracker.activeTickets(),
                "MemoryTracker activeTickets must match world activeTicketCount");

        // Now update config: shrink radius so all 3 locations (at 200, 400, 600) are outside (radius = 50)
        String newYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: circle\n" +
                "  radius: 50\n" +
                "  center:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 50\n";
        Files.writeString(regFile.toPath(), newYaml);

        // Reload plugin and regions
        assertTrue(RTP.reload(), "RTP.reload() must succeed");

        // Verify Rule S-002: chunk tickets for all evicted candidates are cleanly closed
        assertEquals(0, world.getActiveTicketCount(),
                "Rule S-002: Active chunk tickets on the world must be exactly 0 after reload evicted candidates");
        assertEquals(0, MemoryTracker.activeTickets(),
                "Rule S-002: MemoryTracker activeTickets must be exactly 0 after reload evicted candidates");
    }

    /**
     * Requirement 4:
     * Verifying that queue refills cleanly resume under the new shape/radius parameters.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("Queue refills cleanly resume under the new shape and radius parameters")
    void testQueueRefillsCleanlyResumeUnderNewShapeAndRadiusParameters() throws Exception {
        // Start with Circle radius 800
        String initialYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: circle\n" +
                "  radius: 800\n" +
                "  center:\n" +
                "    x: 0\n" +
                "    z: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 10\n";
        File regFile = new File(regionsDir, "refill_reg.yml");
        Files.writeString(regFile.toPath(), initialYaml);

        assertTrue(RTP.reload());

        Region region = RTP.selectionAPI.permRegionLookup.get("refill_reg");
        assertNotNull(region);
        assertTrue(region.getShape().name.equalsIgnoreCase("CIRCLE"), "Initial shape must be Circle");

        // Alter configuration to Square with radius = 150
        String squareYaml =
                "world: reload_world\n" +
                "shape:\n" +
                "  name: square\n" +
                "  radius: 150\n" +
                "  centerRadius: 0\n" +
                "  centerX: 0\n" +
                "  centerZ: 0\n" +
                "vert:\n" +
                "  name: linear\n" +
                "cacheCap: 10\n";
        Files.writeString(regFile.toPath(), squareYaml);

        // Execute reload
        assertTrue(RTP.reload());

        // Validate new region configuration
        Region reloaded = RTP.selectionAPI.permRegionLookup.get("refill_reg");
        assertNotNull(reloaded, "Reloaded region must be registered");
        assertTrue(reloaded.getShape().name.equalsIgnoreCase("SQUARE"),
                "Reloaded region shape must resume as Square (was Circle)");
        assertEquals(150, ((Number) reloaded.getShape().getData().get(GenericMemoryShapeParams.radius)).intValue(),
                "Reloaded region radius parameter must be 150");

        // Trigger fill execution to refill the queue under new parameters
        reloaded.execute(50_000_000L);

        // Fill task generation: generate candidates directly into queue or via RegionCacheTask
        for (int i = 0; i < 5; i++) {
            RegionCacheTask task = new RegionCacheTask(reloaded, 50_000_000L);
            task.run();
        }

        // Verify that candidate locations generated into the queue strictly conform to Square [cx=100, cz=100, radius=150]
        assertTrue(reloaded.queueManager.keptLocations.size() > 0 || reloaded.queueManager.unkeptLocations.size() > 0,
                "Queue refills should resume and populate candidate locations");

        for (int i = 0; i < reloaded.queueManager.keptLocations.size(); i++) {
            RTPLocation loc = reloaded.queueManager.keptLocations.get(i);
            if (loc != null) {
                int cx = loc.coords().x() >> 4;
                int cz = loc.coords().z() >> 4;
                assertTrue(reloaded.getShape().contains(cx, cz),
                        "Kept candidate (" + loc.coords().x() + "," + loc.coords().z() + ") must be inside new Square boundaries");
            }
        }

        for (int i = 0; i < reloaded.queueManager.unkeptLocations.size(); i++) {
            RTPLocation loc = reloaded.queueManager.unkeptLocations.get(i);
            if (loc != null) {
                int cx = loc.coords().x() >> 4;
                int cz = loc.coords().z() >> 4;
                assertTrue(reloaded.getShape().contains(cx, cz),
                        "Unkept candidate (" + loc.coords().x() + "," + loc.coords().z() + ") must be inside new Square boundaries");
            }
        }
    }

    /**
     * Test verifying database hydration rejects stale seed / out-of-bounds locations during reload.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Database hydration purges stale seed and out-of-bounds cached locations during reload")
    void testHydrateCacheFromDatabasePurgesMismatchedLocationsOnReload() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 200L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        circle.set(GenericMemoryShapeParams.centerX, 0L);
        circle.set(GenericMemoryShapeParams.centerZ, 0L);

        RegionSettings settings = new RegionSettings(
                "hydrate_reload_reg",
                world,
                circle,
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                200L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);

        Region region = new Region("hydrate_reload_reg", settings);
        long currentSeed = region.cacheKeyLong();

        List<DatabaseAccessor.StoredLocation> stored = new ArrayList<>();
        // Stale seed location (from old radius/bounds)
        stored.add(new DatabaseAccessor.StoredLocation("loc_stale", "hydrate_reload_reg", world.name(), 500 * 16, 64, 500 * 16, 1, 999999L, null));
        // Out of bounds location even if seed was 0
        stored.add(new DatabaseAccessor.StoredLocation("loc_oob", "hydrate_reload_reg", world.name(), 800 * 16, 64, 800 * 16, 1, 0L, null));
        // Valid location inside bounds with current seed
        stored.add(new DatabaseAccessor.StoredLocation("loc_valid", "hydrate_reload_reg", world.name(), 50 * 16, 64, 50 * 16, 1, currentSeed, null));

        region.hydrateCacheFromDatabase(stored);

        // Only loc_valid should be retained in unkeptLocations
        assertEquals(1, region.queueManager.unkeptLocations.size(),
                "Only valid in-bounds candidate matching current cache key should be hydrated");
        RTPLocation hydrated = region.queueManager.unkeptLocations.peek();
        assertNotNull(hydrated);
        assertEquals(50 * 16, hydrated.coords().x());
        assertEquals(50 * 16, hydrated.coords().z());
    }
}
