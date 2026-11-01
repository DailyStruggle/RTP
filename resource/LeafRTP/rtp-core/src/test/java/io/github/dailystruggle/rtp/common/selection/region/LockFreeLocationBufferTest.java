package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LockFreeLocationBufferTest {

    private LockFreeLocationBuffer buffer;

    private static RTPLocation loc(int x) {
        return new RTPLocation(new RTPCoords("world", x, 64, x), 1);
    }

    @BeforeEach
    void setUp() {
        buffer = new LockFreeLocationBuffer(8);
    }

    @Test
    void newBuffer_isEmpty() {
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
    }

    @Test
    void offer_returnsTrueAndIncreasesSize() {
        assertTrue(buffer.offer(loc(1)));
        assertEquals(1, buffer.size());
        assertFalse(buffer.isEmpty());
    }

    @Test
    void offer_null_returnsFalse() {
        assertFalse(buffer.offer(null));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void poll_emptyBuffer_returnsNull() {
        assertNull(buffer.poll());
    }

    @Test
    void poll_returnsOfferedLocation() {
        RTPLocation l = loc(5);
        buffer.offer(l);
        assertSame(l, buffer.poll());
    }

    @Test
    void poll_fifoOrder() {
        RTPLocation a = loc(1);
        RTPLocation b = loc(2);
        RTPLocation c = loc(3);
        buffer.offer(a);
        buffer.offer(b);
        buffer.offer(c);
        assertSame(a, buffer.poll());
        assertSame(b, buffer.poll());
        assertSame(c, buffer.poll());
        assertNull(buffer.poll());
    }

    @Test
    void offer_atCapacity_returnsFalse() {
        // capacity rounds up to 8
        for (int i = 0; i < 8; i++) {
            assertTrue(buffer.offer(loc(i)));
        }
        assertFalse(buffer.offer(loc(99)));
        assertEquals(8, buffer.size());
    }

    @Test
    void peek_doesNotRemove() {
        RTPLocation l = loc(7);
        buffer.offer(l);
        assertSame(l, buffer.peek());
        assertEquals(1, buffer.size());
        assertSame(l, buffer.poll());
    }

    @Test
    void peek_emptyBuffer_returnsNull() {
        assertNull(buffer.peek());
    }

    @Test
    void get_validIndex_returnsLocation() {
        RTPLocation a = loc(1);
        RTPLocation b = loc(2);
        buffer.offer(a);
        buffer.offer(b);
        assertSame(a, buffer.get(0));
        assertSame(b, buffer.get(1));
    }

    @Test
    void get_outOfBounds_returnsNull() {
        buffer.offer(loc(1));
        assertNull(buffer.get(1));
        assertNull(buffer.get(-1));
    }

    @Test
    void set_replacesLocation() {
        RTPLocation a = loc(1);
        RTPLocation b = loc(2);
        buffer.offer(a);
        buffer.set(0, b);
        assertSame(b, buffer.get(0));
    }

    @Test
    void set_outOfBounds_noEffect() {
        buffer.offer(loc(1));
        buffer.set(5, loc(99)); // out of bounds, no exception
        assertEquals(1, buffer.size());
    }

    @Test
    void onAdd_callbackFired() {
        List<RTPLocation> added = new ArrayList<>();
        buffer.setCallbacks(added::add, null);
        RTPLocation l = loc(3);
        buffer.offer(l);
        assertEquals(1, added.size());
        assertSame(l, added.get(0));
    }

    @Test
    void onRemove_callbackFired() {
        List<RTPLocation> removed = new ArrayList<>();
        buffer.setCallbacks(null, removed::add);
        RTPLocation l = loc(4);
        buffer.offer(l);
        buffer.poll();
        assertEquals(1, removed.size());
        assertSame(l, removed.get(0));
    }

    /**
     * Regression: {@code Region.execute}'s unkept→kept promotion race caused the
     * {@code rtp_cached_locations} DB row to disappear across restarts because the
     * source poll fired a delete callback and the destination offer fired a save
     * callback against the same composite key. {@link LockFreeLocationBuffer#pollSilently}
     * suppresses the {@code onRemove} callback so the destination offer's save callback
     * acts as an idempotent upsert rather than racing with a competing delete.
     */
    @Test
    void pollSilently_doesNotFireOnRemoveButReturnsHead() {
        List<RTPLocation> removed = new ArrayList<>();
        buffer.setCallbacks(null, removed::add);
        RTPLocation a = loc(1);
        RTPLocation b = loc(2);
        buffer.offer(a);
        buffer.offer(b);
        assertSame(a, buffer.pollSilently());
        assertTrue(removed.isEmpty(), "pollSilently must not fire onRemove");
        assertEquals(1, buffer.size());
        // Subsequent regular poll still fires the callback.
        assertSame(b, buffer.poll());
        assertEquals(1, removed.size());
        assertSame(b, removed.get(0));
    }

    /**
     * Mirror of {@link #pollSilently_doesNotFireOnRemoveButReturnsHead()} for the
     * destination side of an internal buffer-to-buffer move (heap-pressure shed of
     * {@code keptLocations} back into {@code unkeptLocations}). The candidate
     * persists under the same DB composite key in both buffers, so the silent offer
     * must suppress the {@code onAdd} save to avoid redundant write churn.
     */
    @Test
    void offerSilently_doesNotFireOnAddButStores() {
        List<RTPLocation> added = new ArrayList<>();
        buffer.setCallbacks(added::add, null);
        RTPLocation a = loc(1);
        assertTrue(buffer.offerSilently(a));
        assertTrue(added.isEmpty(), "offerSilently must not fire onAdd");
        assertEquals(1, buffer.size());
        assertSame(a, buffer.get(0));
        // Subsequent regular offer still fires the callback.
        RTPLocation b = loc(2);
        assertTrue(buffer.offer(b));
        assertEquals(1, added.size());
        assertSame(b, added.get(0));
    }

    @Test
    void pollSilently_emptyBuffer_returnsNull() {
        List<RTPLocation> removed = new ArrayList<>();
        buffer.setCallbacks(null, removed::add);
        assertNull(buffer.pollSilently());
        assertTrue(removed.isEmpty());
    }

    @Test
    void concurrentOfferAndPoll_noDataLoss() throws InterruptedException {
        int count = 100;
        LockFreeLocationBuffer concurrentBuffer = new LockFreeLocationBuffer(256);
        AtomicInteger polled = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService exec = Executors.newFixedThreadPool(2);

        exec.submit(() -> {
            for (int i = 0; i < count; i++) {
                while (!concurrentBuffer.offer(loc(i))) Thread.yield();
            }
            latch.countDown();
        });

        exec.submit(() -> {
            int got = 0;
            long deadline = System.currentTimeMillis() + 5000;
            while (got < count && System.currentTimeMillis() < deadline) {
                if (concurrentBuffer.poll() != null) got++;
                else Thread.yield();
            }
            polled.set(got);
            latch.countDown();
        });

        latch.await(10, TimeUnit.SECONDS);
        exec.shutdown();
        assertEquals(count, polled.get());
    }
}
