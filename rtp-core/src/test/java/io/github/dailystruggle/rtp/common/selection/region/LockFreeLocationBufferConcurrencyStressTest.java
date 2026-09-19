package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress harness for {@link LockFreeLocationBuffer}.
 * <p>
 * Verifies single-producer / single-consumer and concurrent multi-thread interaction invariants,
 * buffer index wrap-around, and concurrent drain/clear operations (ENTERPRISE_READINESS.md item 25,
 * REQ-FOLIA-F-003).
 */
@DisplayName("LockFreeLocationBuffer Concurrency Stress Harness")
class LockFreeLocationBufferConcurrencyStressTest {

    private static RTPLocation loc(int id) {
        return new RTPLocation(new RTPCoords("world", id, 64, id), 1);
    }

    private static final class MockReservation extends ChunkReservation {
        final AtomicBoolean closed = new AtomicBoolean(false);

        MockReservation(MockRTPWorld w) {
            super(new ChunkSet(w, 0, 0,
                    java.util.List.of(java.util.concurrent.CompletableFuture.completedFuture(0L)),
                    new java.util.concurrent.CompletableFuture<>()), w);
        }

        @Override
        public void close() {
            closed.set(true);
            super.close();
        }

        public boolean isClosed() {
            return closed.get();
        }
    }

    private static RTPLocation locWithReservation(int id, MockReservation reservation) {
        return new RTPLocation(new RTPCoords("world", id, 64, id), 1L, reservation);
    }

    /**
     * SPSC high-throughput concurrency stress:
     * Producer continuously offers elements while Consumer concurrently polls.
     * Invariants verified:
     * 1. No items are lost: accepted offers == total polled.
     * 2. Strict FIFO ordering preserved from producer to consumer.
     * 3. Consistent buffer size accounting.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void spsc_concurrencySoak_conservationAndFifoOrder() throws InterruptedException {
        int capacity = 64;
        LockFreeLocationBuffer buffer = new LockFreeLocationBuffer(capacity);
        int totalItems = 500;

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<Integer> polledIds = new ArrayList<>(totalItems);
        AtomicInteger offered = new AtomicInteger(0);

        // Single Producer
        exec.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < totalItems; i++) {
                    RTPLocation location = loc(i);
                    boolean silent = (i % 2 == 0);
                    while (!(silent ? buffer.offerSilently(location) : buffer.offer(location))) {
                        Thread.sleep(1);
                    }
                    offered.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Single Consumer
        exec.submit(() -> {
            try {
                startLatch.await();
                while (polledIds.size() < totalItems) {
                    RTPLocation location = (polledIds.size() % 2 == 0)
                            ? buffer.poll()
                            : buffer.pollSilently();
                    if (location != null) {
                        polledIds.add(location.coords().x());
                    } else {
                        Thread.sleep(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(8, TimeUnit.SECONDS), "SPSC stress soak timed out");
        exec.shutdown();

        assertEquals(totalItems, offered.get(), "All items must be offered");
        assertEquals(totalItems, polledIds.size(), "All items must be polled");
        for (int i = 0; i < totalItems; i++) {
            assertEquals(i, polledIds.get(i), "FIFO ordering must be strictly preserved");
        }
        assertTrue(buffer.isEmpty(), "Buffer must be empty");
        assertEquals(0, buffer.size());
    }

    /**
     * Wrap-around stress test:
     * Using a minimal power-of-two buffer (capacity 4), cycle elements through the ring buffer
     * to guarantee head and tail advance past the power-of-two mask multiple times under concurrency.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void ringBufferIndexWrapAround_stress() throws InterruptedException {
        int capacity = 4; // Buffer length will be 4, mask = 3
        LockFreeLocationBuffer buffer = new LockFreeLocationBuffer(capacity);
        assertEquals(4, buffer.capacity());

        int totalCycles = 200;
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger offered = new AtomicInteger(0);
        AtomicInteger polled = new AtomicInteger(0);

        // Producer
        exec.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < totalCycles; i++) {
                    RTPLocation loc = loc(i);
                    while (!buffer.offer(loc)) {
                        Thread.sleep(1);
                    }
                    offered.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Consumer
        exec.submit(() -> {
            try {
                startLatch.await();
                while (polled.get() < totalCycles) {
                    RTPLocation loc = buffer.poll();
                    if (loc != null) {
                        polled.incrementAndGet();
                    } else {
                        Thread.sleep(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(6, TimeUnit.SECONDS), "Wrap around stress test timed out");
        exec.shutdown();

        assertEquals(totalCycles, offered.get());
        assertEquals(totalCycles, polled.get());
        assertTrue(buffer.isEmpty());
    }

    /**
     * Clear stress test:
     * Offers items with chunk reservations concurrently, halts producer, calls clear(),
     * and verifies that clear() closes all chunk reservations of remaining locations,
     * leaves the buffer empty, and terminates cleanly.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void clear_closesReservationsAndTerminates() throws InterruptedException {
        int capacity = 32;
        LockFreeLocationBuffer buffer = new LockFreeLocationBuffer(capacity);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);

        List<MockReservation> reservations = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalCreated = new AtomicInteger(0);

        MockRTPWorld world = new MockRTPWorld("stress_world");

        // Producer
        exec.submit(() -> {
            try {
                startLatch.await();
                while (running.get()) {
                    int id = totalCreated.incrementAndGet();
                    MockReservation res = new MockReservation(world);
                    if (buffer.offer(locWithReservation(id, res))) {
                        reservations.add(res);
                    } else {
                        res.close();
                    }
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        startLatch.countDown();
        Thread.sleep(50); // Allow buffer to partially fill

        // Stop producer before clear as required by contract
        running.set(false);
        exec.shutdown();
        assertTrue(exec.awaitTermination(3, TimeUnit.SECONDS));

        // Clear buffer
        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());

        // Invariant: every accepted reservation must now be closed
        for (MockReservation res : reservations) {
            assertTrue(res.isClosed(), "Reservation must be closed upon clear()");
        }
        assertNull(buffer.poll());
    }

    /**
     * Concurrent offer and poll with callbacks:
     * Verifies that onAdd and onRemove callbacks fire accurately under concurrency without missed invocations.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentOfferAndPoll_callbacksExactAccounting() throws InterruptedException {
        int capacity = 64;
        LockFreeLocationBuffer buffer = new LockFreeLocationBuffer(capacity);

        AtomicLong callbackAdds = new AtomicLong(0);
        AtomicLong callbackRemoves = new AtomicLong(0);
        buffer.setCallbacks(loc -> callbackAdds.incrementAndGet(), loc -> callbackRemoves.incrementAndGet());

        int totalItems = 500;
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // Producer
        exec.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < totalItems; i++) {
                    RTPLocation loc = loc(i);
                    while (!buffer.offer(loc)) {
                        Thread.sleep(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Consumer
        exec.submit(() -> {
            try {
                startLatch.await();
                int polled = 0;
                while (polled < totalItems) {
                    if (buffer.poll() != null) {
                        polled++;
                    } else {
                        Thread.sleep(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(6, TimeUnit.SECONDS), "Callbacks stress test timed out");
        exec.shutdown();

        assertEquals(totalItems, callbackAdds.get(), "Every offer must fire onAdd callback");
        assertEquals(totalItems, callbackRemoves.get(), "Every poll must fire onRemove callback");
        assertTrue(buffer.isEmpty());
    }
}
