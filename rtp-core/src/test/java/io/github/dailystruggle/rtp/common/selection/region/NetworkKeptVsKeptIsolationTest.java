package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice A row A6 - CHECKLIST-cross-server-rtp-L6.md. Draining
 * {@code keptLocations} (local hot queue) must not deplete
 * {@code networkKeptLocations} (cross-server sibling pool), and vice versa.
 * Also verifies that disabling the network split ({@code networkReserveSize=0})
 * leaves {@code networkKeptLocations} null without disturbing the local hot
 * queue.
 */
class NetworkKeptVsKeptIsolationTest {

    @TempDir
    Path tempDir;

    private static RTPLocation loc(MockRTPWorld w, int x, int z) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, null);
    }

    private static RegionQueueManager buildQm(MockRTPWorld world, long networkReserveSize) {
        RegionSettings settings = new RegionSettings(
                "iso_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, networkReserveSize, 8,
                0.0, 1L, "", false);
        return new Region("iso_region", settings).queueManager;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void networkReserveSizeZero_networkKeptStaysNull_localUnaffected() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPWorld world = new MockRTPWorld("iso_world_a");
        accessor.addWorld(world);

        RegionQueueManager qm = buildQm(world, 0L);
        assertNull(qm.networkKeptLocations,
                "networkReserveSize=0 must leave networkKeptLocations null");
        assertEquals(0L, qm.networkKeptCount("iso_region"));

        // Local hot queue still works.
        qm.keptLocations.offer(loc(world, 1, 1));
        assertEquals(1L, qm.keptCount());

        // reserve on a disabled region returns null safely.
        assertNull(qm.reserveFromNetworkKept(UUID.randomUUID(), "iso_region"));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void drainingKept_doesNotAffectNetworkKept() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPWorld world = new MockRTPWorld("iso_world_b");
        accessor.addWorld(world);

        RegionQueueManager qm = buildQm(world, 8L);
        assertNotNull(qm.networkKeptLocations);

        // Seed both pools.
        for (int i = 0; i < 4; i++) qm.keptLocations.offer(loc(world, i, i));
        for (int i = 0; i < 4; i++) qm.networkKeptLocations.offer(loc(world, 100 + i, 100 + i));

        long networkBefore = qm.networkKeptCount("iso_region");
        long keptBefore = qm.keptCount();
        assertEquals(4L, networkBefore);
        assertEquals(4L, keptBefore);

        // Drain local hot queue via the public poll path.
        for (int i = 0; i < 4; i++) {
            CompletableFuture<RTPLocation> f = qm.poll(UUID.randomUUID());
            assertNotNull(f);
            assertTrue(f.isDone());
            assertNotNull(f.join());
        }
        assertEquals(0L, qm.keptCount(),
                "local hot queue must be drained");
        assertEquals(networkBefore, qm.networkKeptCount("iso_region"),
                "networkKeptLocations must be untouched by local poll");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void drainingNetworkKept_doesNotAffectKept() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPWorld world = new MockRTPWorld("iso_world_c");
        accessor.addWorld(world);

        RegionQueueManager qm = buildQm(world, 8L);
        assertNotNull(qm.networkKeptLocations);

        for (int i = 0; i < 4; i++) qm.keptLocations.offer(loc(world, i, i));
        for (int i = 0; i < 4; i++) qm.networkKeptLocations.offer(loc(world, 100 + i, 100 + i));

        long keptBefore = qm.keptCount();
        assertEquals(4L, keptBefore);
        assertEquals(4L, qm.networkKeptCount("iso_region"));

        // Drain network sibling pool via the reservation API.
        for (int i = 0; i < 4; i++) {
            UUID tok = UUID.randomUUID();
            assertNotNull(qm.reserveFromNetworkKept(tok, "iso_region"));
        }
        assertEquals(0L, qm.networkKeptCount("iso_region"));
        assertEquals(4L, qm.networkReservedCount());
        assertEquals(keptBefore, qm.keptCount(),
                "local hot queue must be untouched by network reservation drain");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void networkReserveSize_isClampedToCacheCap() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPWorld world = new MockRTPWorld("iso_world_d");
        accessor.addWorld(world);

        // networkReserveSize (= 999) exceeds cacheCap (= 16); buffer capacity
        // must clamp to cacheCap so operators cannot accidentally provision a
        // sibling pool larger than the region's total cache budget.
        RegionQueueManager qm = buildQm(world, 999L);
        assertNotNull(qm.networkKeptLocations);

        int offered = 0;
        while (qm.networkKeptLocations.offer(loc(world, offered, offered))) {
            offered++;
            if (offered > 10_000) fail("network buffer must be finite");
        }
        assertTrue(offered <= 16,
                "networkKeptLocations capacity must be clamped to cacheCap (got " + offered + ")");
        assertTrue(offered > 0,
                "networkKeptLocations must accept at least one coord");
    }
}
