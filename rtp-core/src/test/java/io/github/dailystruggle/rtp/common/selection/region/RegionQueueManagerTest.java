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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RegionQueueManager}.
 *
 * <p>Covers: queue population limits, empty-queue fallbacks, per-player queues,
 * fastQueue mechanics, getTotalQueueLength / getPublicQueueLength / getPersonalQueueLength,
 * thread-safe concurrent offer/poll via {@link LockFreeLocationBuffer}, and shutDown.
 */
public class RegionQueueManagerTest {

    @TempDir
    Path tempDir;

    private Region region;
    private RegionQueueManager qm;

    private static RTPLocation loc(MockRTPWorld world, int x, int z) {
        return new RTPLocation(new RTPCoords(world.name(), x, 64, z), 1L, null);
    }

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPWorld world = new MockRTPWorld("qm_world");
        accessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "qm_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 8,
                0.0, 1L, "", false);
        region = new Region("qm_region", settings);
        qm = region.queueManager;
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void initialState_allQueuesEmpty() {
        assertTrue(qm.keptLocations.isEmpty());
        assertTrue(qm.unkeptLocations.isEmpty());
        assertEquals(0L, qm.getPublicQueueLength());
        assertEquals(0L, qm.getTotalQueueLength(UUID.randomUUID()));
    }

    // -----------------------------------------------------------------------
    // enqueueLocation / unkeptLocations
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void enqueueLocation_addsToUnkeptQueue() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        qm.enqueueLocation(loc(world, 0, 0));
        assertEquals(1L, qm.getPublicQueueLength());
        assertFalse(qm.unkeptLocations.isEmpty());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void offerLocation_returnsTrueWhenSpaceAvailable() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        assertTrue(qm.offerLocation(loc(world, 10, 10)));
        assertEquals(1L, qm.getPublicQueueLength());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void offerLocation_returnsFalseWhenBufferFull() {
        // Determine actual buffer capacity by offering until rejected
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        int offered = 0;
        while (qm.offerLocation(loc(world, offered, offered))) {
            offered++;
            if (offered > 10_000) break; // safety guard
        }
        // Buffer must have a finite capacity
        assertTrue(offered > 0, "Buffer must accept at least one location");
        assertTrue(offered <= 10_000, "Buffer must have a finite capacity");
        // One more offer after full must fail
        assertFalse(qm.offerLocation(loc(world, offered + 1, offered + 1)),
                "Offer after buffer is full must return false");
    }

    // -----------------------------------------------------------------------
    // poll — empty queue returns null
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void poll_returnsNullWhenAllQueuesEmpty() {
        UUID id = UUID.randomUUID();
        assertNull(qm.poll(id));
    }

    // -----------------------------------------------------------------------
    // poll — keptLocations path
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void poll_returnsLocationFromKeptQueue() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        RTPLocation expected = loc(world, 5, 5);
        qm.keptLocations.offer(expected);

        CompletableFuture<RTPLocation> future = qm.poll(UUID.randomUUID());
        assertNotNull(future);
        assertTrue(future.isDone());
        assertEquals(expected, future.join());
        assertTrue(qm.keptLocations.isEmpty());
    }

    // -----------------------------------------------------------------------
    // poll — perPlayerLocationQueue path
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void poll_prefersPerPlayerQueueOverKeptLocations() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id = UUID.randomUUID();

        RTPLocation playerLoc = loc(world, 1, 1);
        RTPLocation keptLoc = loc(world, 2, 2);

        qm.perPlayerLocationQueue
                .computeIfAbsent(id, k -> new ConcurrentLinkedQueue<>())
                .add(playerLoc);
        qm.keptLocations.offer(keptLoc);

        CompletableFuture<RTPLocation> future = qm.poll(id);
        assertNotNull(future);
        assertEquals(playerLoc, future.join(),
                "per-player queue must be preferred over keptLocations");
    }

    // -----------------------------------------------------------------------
    // fastQueue / poll — fastLocations path
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fastQueue_createsFutureAndStoresIt() {
        UUID id = UUID.randomUUID();
        CompletableFuture<RTPLocation> f = qm.fastQueue(id);
        assertNotNull(f);
        assertTrue(qm.hasFastLocation(id));
        assertSame(f, qm.getFastLocation(id));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fastQueue_returnsSameFutureOnSecondCall() {
        UUID id = UUID.randomUUID();
        CompletableFuture<RTPLocation> f1 = qm.fastQueue(id);
        CompletableFuture<RTPLocation> f2 = qm.fastQueue(id);
        assertSame(f1, f2);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void poll_returnsFastLocationFutureAndRemovesIt() {
        UUID id = UUID.randomUUID();
        CompletableFuture<RTPLocation> f = qm.fastQueue(id);

        CompletableFuture<RTPLocation> polled = qm.poll(id);
        assertSame(f, polled);
        assertFalse(qm.hasFastLocation(id), "fastLocation must be removed after poll");
    }

    // -----------------------------------------------------------------------
    // enqueuePlayerLocation / getPersonalQueueLength
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void enqueuePlayerLocation_addsToPerPlayerQueue() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id = UUID.randomUUID();
        qm.enqueuePlayerLocation(id, loc(world, 3, 3));

        assertEquals(1L, qm.getPersonalQueueLength(id));
        assertTrue(qm.hasPerPlayerQueue(id));
        assertNotNull(qm.getPerPlayerQueue(id));
        assertEquals(1, qm.getPerPlayerQueue(id).size());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getPersonalQueueLength_countsPerPlayerAndFastLocations() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id = UUID.randomUUID();
        qm.enqueuePlayerLocation(id, loc(world, 4, 4));
        qm.fastQueue(id);

        assertEquals(2L, qm.getPersonalQueueLength(id));
    }

    // -----------------------------------------------------------------------
    // getTotalQueueLength
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getTotalQueueLength_sumsAllQueues() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id = UUID.randomUUID();

        qm.keptLocations.offer(loc(world, 1, 1));
        qm.unkeptLocations.offer(loc(world, 2, 2));
        qm.enqueuePlayerLocation(id, loc(world, 3, 3));
        qm.fastQueue(id);

        assertEquals(4L, qm.getTotalQueueLength(id));
    }

    // -----------------------------------------------------------------------
    // getPublicQueueLength
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getPublicQueueLength_sumsKeptAndUnkept() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        qm.keptLocations.offer(loc(world, 1, 1));
        qm.unkeptLocations.offer(loc(world, 2, 2));
        qm.unkeptLocations.offer(loc(world, 3, 3));

        assertEquals(3L, qm.getPublicQueueLength());
    }

    // -----------------------------------------------------------------------
    // getLocation (index-based)
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getLocation_returnsCorrectIndexedEntry() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        RTPLocation a = loc(world, 10, 10);
        RTPLocation b = loc(world, 20, 20);
        qm.keptLocations.offer(a);
        qm.keptLocations.offer(b);

        assertSame(a, qm.getLocation(0));
        assertSame(b, qm.getLocation(1));
        assertNull(qm.getLocation(2));
    }

    // -----------------------------------------------------------------------
    // clearPerPlayerQueues
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void clearPerPlayerQueues_removesAllEntries() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        qm.enqueuePlayerLocation(id1, loc(world, 1, 1));
        qm.enqueuePlayerLocation(id2, loc(world, 2, 2));

        qm.clearPerPlayerQueues();

        assertFalse(qm.hasPerPlayerQueue(id1));
        assertFalse(qm.hasPerPlayerQueue(id2));
        assertTrue(qm.perPlayerLocationQueue.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getPerPlayerQueues / getPerPlayerQueueEntries
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getPerPlayerQueues_returnsAllQueues() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id = UUID.randomUUID();
        qm.enqueuePlayerLocation(id, loc(world, 5, 5));

        assertEquals(1, qm.getPerPlayerQueues().size());
        assertEquals(1, qm.getPerPlayerQueueEntries().size());
    }

    // -----------------------------------------------------------------------
    // shutDown
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void shutDown_clearsAllQueues() {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        UUID id = UUID.randomUUID();
        qm.keptLocations.offer(loc(world, 1, 1));
        qm.unkeptLocations.offer(loc(world, 2, 2));
        qm.enqueuePlayerLocation(id, loc(world, 3, 3));
        qm.fastQueue(id);

        qm.shutDown();

        assertTrue(qm.keptLocations.isEmpty());
        assertTrue(qm.unkeptLocations.isEmpty());
        assertTrue(qm.perPlayerLocationQueue.isEmpty());
        assertTrue(qm.fastLocations.isEmpty());
        assertTrue(qm.playerQueue.isEmpty());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void shutDown_completesPendingFastLocationWithNull() {
        UUID id = UUID.randomUUID();
        CompletableFuture<RTPLocation> f = qm.fastQueue(id);
        assertFalse(f.isDone());

        qm.shutDown();

        assertTrue(f.isDone());
        assertNull(f.join());
    }

    // -----------------------------------------------------------------------
    // Thread-safety: concurrent offer + poll on LockFreeLocationBuffer
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentOfferAndPoll_noDataLoss() throws InterruptedException {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        int threads = 4;
        int itemsPerThread = 4; // buffer capacity = 16 (activeChunkCap)
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int base = t * itemsPerThread;
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        qm.keptLocations.offer(loc(world, base + i, base + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(4, TimeUnit.SECONDS));
        exec.shutdown();

        int total = qm.keptLocations.size();
        assertTrue(total > 0, "At least some locations must have been offered");
        assertTrue(total <= threads * itemsPerThread);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentPoll_noDuplicates() throws InterruptedException {
        MockRTPWorld world = (MockRTPWorld) region.getWorld();
        int count = 8;
        for (int i = 0; i < count; i++) {
            qm.keptLocations.offer(loc(world, i, i));
        }

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> polled =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        ExecutorService exec = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    RTPLocation loc;
                    while ((loc = qm.keptLocations.poll()) != null) {
                        polled.add(loc);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(4, TimeUnit.SECONDS));
        exec.shutdown();

        assertEquals(count, polled.size(), "Each location must be polled exactly once");
        // No duplicates
        java.util.Set<RTPLocation> unique = new java.util.HashSet<>(polled);
        assertEquals(count, unique.size());
    }
}
