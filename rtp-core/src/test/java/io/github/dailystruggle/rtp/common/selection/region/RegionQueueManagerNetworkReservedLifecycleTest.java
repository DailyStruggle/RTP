package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the
 * reserve/redeem/release lifecycle of {@code networkReservedLocations},
 * concurrent reserver safety, double-release idempotence, the
 * coord-back-to-pool invariant on release, and regionKey scoping.
 */
class RegionQueueManagerNetworkReservedLifecycleTest {

    @TempDir
    Path tempDir;

    private Region region;
    private RegionQueueManager qm;
    private MockRTPWorld world;

    private static RTPLocation loc(MockRTPWorld w, int x, int z) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, null);
    }

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("net_world");
        accessor.addWorld(world);

        // networkReserveSize = 8 (must be > 0 to allocate networkKeptLocations)
        RegionSettings settings = new RegionSettings(
                "net_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, 8L, 8,
                0.0, 1L, "", false);
        region = new Region("net_region", settings);
        qm = region.queueManager;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void networkKept_isAllocatedWhenNetworkReserveSizePositive() {
        assertNotNull(qm.networkKeptLocations,
                "networkReserveSize=8 must allocate the sibling buffer");
        assertEquals(0L, qm.networkKeptCount("net_region"));
        assertEquals(0L, qm.networkReservedCount());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void reserve_emptyPool_returnsNull() {
        UUID tok = UUID.randomUUID();
        assertNull(qm.reserveFromNetworkKept(tok, "net_region"));
        assertEquals(0L, qm.networkReservedCount());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void reserve_thenRedeem_returnsSameLocationAndClearsReservation() {
        RTPLocation seeded = loc(world, 10, 10);
        qm.networkKeptLocations.offer(seeded);

        UUID tok = UUID.randomUUID();
        RTPLocation reserved = qm.reserveFromNetworkKept(tok, "net_region");

        assertNotNull(reserved);
        assertEquals(seeded.coords(), reserved.coords());
        assertEquals(0L, qm.networkKeptCount("net_region"),
                "reserve must remove the coord from networkKeptLocations");
        assertEquals(1L, qm.networkReservedCount(),
                "reserved coord must be pinned in networkReservedLocations");

        RTPLocation redeemed = qm.redeemReserved(tok);
        assertNotNull(redeemed);
        assertEquals(seeded.coords(), redeemed.coords());
        assertEquals(0L, qm.networkReservedCount(),
                "redeem must clear the reservation");
        assertNull(qm.redeemReserved(tok),
                "redeem on a cleared token must return null");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void release_returnsCoordToNetworkKept_invariant() {
        RTPLocation seeded = loc(world, 20, 20);
        qm.networkKeptLocations.offer(seeded);

        UUID tok = UUID.randomUUID();
        qm.reserveFromNetworkKept(tok, "net_region");
        assertEquals(0L, qm.networkKeptCount("net_region"));
        assertEquals(1L, qm.networkReservedCount());

        assertTrue(qm.releaseToNetworkKept(tok));
        assertEquals(1L, qm.networkKeptCount("net_region"),
                "release must return the coord to networkKeptLocations");
        assertEquals(0L, qm.networkReservedCount());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void release_isIdempotent() {
        RTPLocation seeded = loc(world, 30, 30);
        qm.networkKeptLocations.offer(seeded);

        UUID tok = UUID.randomUUID();
        qm.reserveFromNetworkKept(tok, "net_region");
        assertTrue(qm.releaseToNetworkKept(tok));
        assertFalse(qm.releaseToNetworkKept(tok),
                "second release must be a no-op (idempotent)");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void reserve_isIdempotentForSameToken() {
        RTPLocation a = loc(world, 40, 40);
        RTPLocation b = loc(world, 41, 41);
        qm.networkKeptLocations.offer(a);
        qm.networkKeptLocations.offer(b);

        UUID tok = UUID.randomUUID();
        RTPLocation first = qm.reserveFromNetworkKept(tok, "net_region");
        RTPLocation second = qm.reserveFromNetworkKept(tok, "net_region");

        assertNotNull(first);
        assertSame(first, second,
                "re-reserving with the same token must return the pinned coord without consuming another");
        assertEquals(1L, qm.networkKeptCount("net_region"),
                "second reserve must not poll the pool again");
        assertEquals(1L, qm.networkReservedCount());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentReservers_eachClaimsDistinctCoord() throws Exception {
        int seedCount = 8;
        for (int i = 0; i < seedCount; i++) {
            qm.networkKeptLocations.offer(loc(world, 100 + i, 100 + i));
        }
        assertEquals((long) seedCount, qm.networkKeptCount("net_region"));

        int reservers = 16; // more reservers than coords; surplus must get null
        ExecutorService pool = Executors.newFixedThreadPool(reservers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        Set<RTPCoords> uniqueCoords = java.util.Collections.synchronizedSet(new HashSet<>());

        List<UUID> tokens = new ArrayList<>();
        for (int i = 0; i < reservers; i++) tokens.add(UUID.randomUUID());

        CountDownLatch done = new CountDownLatch(reservers);
        for (UUID tok : tokens) {
            pool.submit(() -> {
                try {
                    start.await();
                    RTPLocation r = qm.reserveFromNetworkKept(tok, "net_region");
                    if (r != null) {
                        successCount.incrementAndGet();
                        uniqueCoords.add(r.coords());
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(4, TimeUnit.SECONDS), "all reservers must finish");
        pool.shutdownNow();

        assertEquals(seedCount, successCount.get(),
                "exactly seedCount reservers must succeed");
        assertEquals(seedCount, uniqueCoords.size(),
                "each successful reserver must claim a distinct coord");
        assertEquals(0L, qm.networkKeptCount("net_region"),
                "pool must be drained");
        assertEquals((long) seedCount, qm.networkReservedCount());
    }

    /**
     * Counting spy: records {@code close()} calls without touching the
     * underlying ticket, so ticket hygiene is observable in-process.
     */
    private static final class CountingReservation extends ChunkReservation {
        final AtomicInteger closes = new AtomicInteger();

        CountingReservation(MockRTPWorld w, int cx, int cz) {
            super(new ChunkSet(w, cx, cz,
                    List.of(CompletableFuture.completedFuture(((long) cx << 32) | (cz & 0xFFFFFFFFL))),
                    new CompletableFuture<>()), w);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private RTPLocation reservedLoc(int x, int z, CountingReservation res) {
        return new RTPLocation(new RTPCoords(world.name(), x, 64, z), 1L, res);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-RTP-S-002: kept-to-kept release keeps the chunk reservation open")
    void release_toNetworkKept_doesNotCloseReservation() {
        CountingReservation res = new CountingReservation(world, 4, 4);
        qm.networkKeptLocations.offer(reservedLoc(60, 60, res));

        UUID tok = UUID.randomUUID();
        RTPLocation reserved = qm.reserveFromNetworkKept(tok, "net_region");
        assertNotNull(reserved);
        assertTrue(qm.releaseToNetworkKept(tok));

        assertEquals(1L, qm.networkKeptCount("net_region"),
                "coord must return to the kept pool");
        assertEquals(0, res.closes.get(),
                "the coord stays hot, so the ticket must not be dropped");
        RTPLocation back = qm.networkKeptLocations.poll();
        assertNotNull(back);
        assertSame(res, back.reservation(),
                "the reservation must remain attached to the kept entry");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-RTP-S-002: demotion to unkept on a full kept pool closes the reservation")
    void release_whenNetworkKeptFull_demotesAndClosesReservation() {
        CountingReservation res = new CountingReservation(world, 5, 5);
        qm.networkKeptLocations.offer(reservedLoc(70, 70, res));

        UUID tok = UUID.randomUUID();
        assertNotNull(qm.reserveFromNetworkKept(tok, "net_region"));

        // Saturate the sibling pool (capacity 8) so the release offer is rejected.
        for (int i = 0; i < 8; i++) {
            assertTrue(qm.networkKeptLocations.offer(loc(world, 200 + i, 200 + i)));
        }
        long unkeptBefore = qm.unkeptLocations.size();

        assertTrue(qm.releaseToNetworkKept(tok));

        assertEquals(1, res.closes.get(),
                "an entry demoted to unkept must have its ticket closed exactly once (S-002)");
        assertEquals(unkeptBefore + 1L, qm.unkeptLocations.size(),
                "the bare coordinate must not be silently lost (S-004)");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-RTP-S-002: release without a network pool closes the reservation")
    void release_withoutNetworkPool_demotesAndClosesReservation() {
        RegionSettings noNetwork = new RegionSettings(
                "no_net_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, 0L, 8,
                0.0, 1L, "", false);
        Region plain = new Region("no_net_region", noNetwork);
        RegionQueueManager plainQm = plain.queueManager;
        assertNull(plainQm.networkKeptLocations,
                "networkReserveSize=0 must leave the sibling pool unallocated");

        CountingReservation res = new CountingReservation(world, 6, 6);
        UUID tok = UUID.randomUUID();
        plainQm.networkReservedLocations.put(tok, reservedLoc(80, 80, res));
        long unkeptBefore = plainQm.unkeptLocations.size();

        assertTrue(plainQm.releaseToNetworkKept(tok));

        assertEquals(1, res.closes.get(),
                "with no kept pool the entry demotes to unkept, so the ticket must be closed");
        assertEquals(unkeptBefore + 1L, plainQm.unkeptLocations.size());
        assertEquals(0L, plainQm.networkReservedCount());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-RTP-S-002: disabling the login reserve closes every ticket it held")
    void disableLoginCache_closesReservations() {
        qm.enableLoginCache(4);
        CountingReservation a = new CountingReservation(world, 7, 7);
        CountingReservation b = new CountingReservation(world, 8, 8);
        assertTrue(qm.loginLocations.offer(reservedLoc(90, 90, a)));
        assertTrue(qm.loginLocations.offer(reservedLoc(91, 91, b)));
        long unkeptBefore = qm.unkeptLocations.size();

        qm.disableLoginCache();

        assertEquals(1, a.closes.get());
        assertEquals(1, b.closes.get());
        assertEquals(unkeptBefore + 2L, qm.unkeptLocations.size());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-RTP-S-002: closing a personal bucket closes every ticket it held")
    void closePersonalQueue_closesReservations() {
        UUID player = UUID.randomUUID();
        qm.perPlayerLocationQueue.put(player, new java.util.concurrent.ConcurrentLinkedQueue<>());
        CountingReservation res = new CountingReservation(world, 9, 9);
        qm.perPlayerLocationQueue.get(player).add(reservedLoc(95, 95, res));
        long unkeptBefore = qm.unkeptLocations.size();

        qm.closePersonalQueue(player);

        assertEquals(1, res.closes.get());
        assertEquals(unkeptBefore + 1L, qm.unkeptLocations.size());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void nullToken_isRejectedSafely() {
        qm.networkKeptLocations.offer(loc(world, 50, 50));
        assertNull(qm.reserveFromNetworkKept(null, "net_region"));
        assertNull(qm.redeemReserved(null));
        assertFalse(qm.releaseToNetworkKept(null));
        assertEquals(1L, qm.networkKeptCount("net_region"));
        assertEquals(0L, qm.networkReservedCount());
    }
}
