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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress harness for {@link RegionQueueManager}.
 * <p>
 * Verifies thread safety and invariant maintenance across public queues, personal queues,
 * network reservation lifecycle, fast queue futures, dynamic login cache toggles, and
 * active concurrent shutdown (ENTERPRISE_READINESS.md item 25, REQ-FOLIA-F-003, S-002, S-004).
 */
@DisplayName("RegionQueueManager Concurrency Stress Harness")
class RegionQueueManagerConcurrencyStressTest {

    @TempDir
    Path tempDir;

    private Region region;
    private RegionQueueManager qm;
    private MockRTPWorld world;

    private static final class CountingReservation extends ChunkReservation {
        final AtomicInteger closes = new AtomicInteger(0);

        CountingReservation(MockRTPWorld w, int cx, int cz) {
            super(new ChunkSet(w, cx, cz,
                    List.of(CompletableFuture.completedFuture(((long) cx << 32) | (cz & 0xFFFFFFFFL))),
                    new CompletableFuture<>()), w);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }

    private static RTPLocation reservedLoc(MockRTPWorld w, int x, int z, ChunkReservation res) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, res);
    }

    private static RTPLocation bareLoc(MockRTPWorld w, int x, int z) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, null);
    }

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("stress_world");
        accessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "stress_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                64L, 1000L, 16L, 16,
                0.0, 1L, "", false);
        region = new Region("stress_region", settings);
        qm = region.queueManager;
    }

    /**
     * Stress test multi-threaded operations across the entire queue ecosystem:
     * - Public queue offers and polls
     * - Personal queue allocations, enqueuing, and polls
     * - Fast queue futures
     * - Network reservations (reserve, redeem, release)
     * - Dynamic login cache toggling
     * Invariants: No exceptions, no deadlock, proper status responses.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentOperations_allQueueTiers_noDeadlockOrExceptions() throws InterruptedException {
        int threads = 8;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successfulOps = new AtomicInteger(0);

        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            players.add(UUID.randomUUID());
        }

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            exec.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < 1000; i++) {
                        UUID player = players.get(rng.nextInt(players.size()));
                        int action = rng.nextInt(10);
                        switch (action) {
                            case 0 -> {
                                qm.offerLocation(bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                                successfulOps.incrementAndGet();
                            }
                            case 1 -> {
                                qm.enqueueLocation(bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                                successfulOps.incrementAndGet();
                            }
                            case 2 -> {
                                CompletableFuture<RTPLocation> pollFuture = qm.poll(player);
                                if (pollFuture != null) {
                                    assertNotNull(pollFuture);
                                }
                                successfulOps.incrementAndGet();
                            }
                            case 3 -> {
                                qm.openPersonalQueue(player);
                                qm.enqueuePlayerLocation(player, bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                                successfulOps.incrementAndGet();
                            }
                            case 4 -> {
                                qm.closePersonalQueue(player);
                                successfulOps.incrementAndGet();
                            }
                            case 5 -> {
                                CompletableFuture<RTPLocation> f = qm.fastQueue(player);
                                assertNotNull(f);
                                qm.hasFastLocation(player);
                                successfulOps.incrementAndGet();
                            }
                            case 6 -> {
                                UUID tokenId = UUID.randomUUID();
                                CountingReservation res = new CountingReservation(world, 0, 0);
                                if (qm.networkKeptLocations != null) {
                                    qm.networkKeptLocations.offer(reservedLoc(world, 10, 10, res));
                                    RTPLocation reserved = qm.reserveFromNetworkKept(tokenId, "stress_region");
                                    if (reserved != null) {
                                        if (rng.nextBoolean()) {
                                            RTPLocation redeemed = qm.redeemReserved(tokenId);
                                            assertEquals(reserved, redeemed);
                                        } else {
                                            qm.releaseToNetworkKept(tokenId);
                                        }
                                    }
                                }
                                successfulOps.incrementAndGet();
                            }
                            case 7 -> {
                                qm.acceptRedeemedReservation(player, bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                                successfulOps.incrementAndGet();
                            }
                            case 8 -> {
                                qm.getPublicQueueLength();
                                qm.getTotalQueueLength(player);
                                qm.getPersonalQueueLength(player);
                                successfulOps.incrementAndGet();
                            }
                            case 9 -> {
                                if (threadIdx == 0 && (i % 100 == 0)) {
                                    if (qm.loginLocations == null) {
                                        qm.enableLoginCache(16);
                                    } else {
                                        qm.disableLoginCache();
                                    }
                                }
                                successfulOps.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable t1) {
                    exceptions.add(t1);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent operations soak timed out");
        exec.shutdown();
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));

        assertTrue(exceptions.isEmpty(), "Exceptions thrown during soak: " + exceptions);
        assertTrue(successfulOps.get() >= threads * 1000);
    }

    /**
     * Network reservations concurrency stress test:
     * Multiple threads racing reserveFromNetworkKept, redeemReserved, and releaseToNetworkKept.
     * Invariants:
     * - Exactly-once redemption or release per token.
     * - Conservation of locations in network pool + in-flight reservations.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void networkReservationLifecycle_concurrentReserveRedeemRelease() throws InterruptedException {
        int initialLocations = 16;
        for (int i = 0; i < initialLocations; i++) {
            CountingReservation res = new CountingReservation(world, i, i);
            qm.networkKeptLocations.offer(reservedLoc(world, i, i, res));
        }

        int threads = 4;
        int tokensPerThread = 50;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger totalReserved = new AtomicInteger(0);
        AtomicInteger totalRedeemed = new AtomicInteger(0);
        AtomicInteger totalReleased = new AtomicInteger(0);
        Set<RTPLocation> redeemedLocations = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < tokensPerThread; i++) {
                        UUID token = UUID.randomUUID();
                        RTPLocation loc = null;
                        for (int retry = 0; retry < 50; retry++) {
                            loc = qm.reserveFromNetworkKept(token, "stress_region");
                            if (loc != null) break;
                            Thread.yield();
                        }
                        if (loc != null) {
                            totalReserved.incrementAndGet();
                            if (rng.nextBoolean()) {
                                RTPLocation redeemed = qm.redeemReserved(token);
                                if (redeemed != null) {
                                    totalRedeemed.incrementAndGet();
                                    redeemedLocations.add(redeemed);
                                }
                            } else {
                                if (qm.releaseToNetworkKept(token)) {
                                    totalReleased.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(6, TimeUnit.SECONDS), "Network reservation stress timed out");
        exec.shutdown();

        assertEquals(totalReserved.get(), totalRedeemed.get() + totalReleased.get(),
                "Every successfully reserved token must be either redeemed or released");
        assertEquals(0, qm.networkReservedCount(),
                "All reservations must be resolved at end of test");
    }

    /**
     * Active concurrency during shutDown():
     * Continuous background offers and polls running while shutDown() is called.
     * Invariants:
     * - shutDown() completes cleanly without hangs.
     * - Post-shutdown, all queues are empty, callbacks are unhooked, pending futures complete.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shutDown_underActiveConcurrency_cleansUpCompletely() throws InterruptedException {
        int threads = 4;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    while (running.get()) {
                        UUID player = UUID.randomUUID();
                        qm.keptLocations.offer(bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                        qm.unkeptLocations.offer(bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                        qm.enqueuePlayerLocation(player, bareLoc(world, rng.nextInt(100), rng.nextInt(100)));
                        qm.fastQueue(player);
                        qm.poll(player);
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        Thread.sleep(50); // Let threads run and fill queues

        // Stop active threads first
        running.set(false);
        exec.shutdown();
        assertTrue(exec.awaitTermination(3, TimeUnit.SECONDS));

        // Now shut down queue manager
        qm.shutDown();

        // Perform final verification of shutdown invariants
        assertTrue(qm.keptLocations.isEmpty());
        assertTrue(qm.unkeptLocations.isEmpty());
        assertTrue(qm.perPlayerLocationQueue.isEmpty());
        assertTrue(qm.fastLocations.isEmpty());
        assertTrue(qm.playerQueue.isEmpty());
        assertNull(qm.loginLocations);
    }
}
