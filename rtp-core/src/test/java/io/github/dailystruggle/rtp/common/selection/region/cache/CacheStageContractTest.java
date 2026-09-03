package io.github.dailystruggle.rtp.common.selection.region.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared {@link CacheStage} contract suite (ADR-078 phase 1).
 *
 * <p>Exercised here against {@link SimpleCacheStage}; the ring-backed implementation
 * joins the same suite when it wraps the existing buffer storage.
 */
class CacheStageContractTest {
    private final List<String> added = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final List<String> disposed = new ArrayList<>();

    private CacheStage<String> stage(int capacity) {
        return new SimpleCacheStage<>("test", capacity, added::add, removed::add, disposed::add);
    }

    @Test
    @DisplayName("REQ-RTP-S-002: overflow disposes the rejected entry exactly once")
    void overflowDisposesOnce() {
        CacheStage<String> stage = stage(1);
        assertTrue(stage.offer("a"));
        assertFalse(stage.offer("b"));
        assertEquals(List.of("b"), disposed);
        assertEquals(List.of("a"), added);
        assertEquals(1, stage.size());
    }

    @Test
    @DisplayName("REQ-RTP-S-002: close disposes every entry and recycles nothing")
    void closeDisposesEveryEntry() {
        CacheStage<String> stage = stage(4);
        stage.offer("a");
        stage.offer("b");
        stage.close();
        assertEquals(List.of("a", "b"), disposed);
        assertEquals(0, stage.size());
        assertTrue(removed.isEmpty(), "teardown is disposal, not a persistence-visible removal");
    }

    @Test
    @DisplayName("poll transfers ownership and fires the persistence-visible callbacks")
    void pollFiresRemoveCallback() {
        CacheStage<String> stage = stage(4);
        stage.offer("a");
        assertEquals(Optional.of("a"), stage.poll());
        assertEquals(List.of("a"), removed);
        assertTrue(disposed.isEmpty(), "a polled entry is owned by the caller, not disposed");
    }

    @Test
    @DisplayName("internal movement is silent: pollSilently/offerSilently fire no callbacks")
    void silentMovementFiresNoCallbacks() {
        CacheStage<String> source = stage(4);
        source.offerSilently("a");
        Optional<String> moved = source.pollSilently();
        assertEquals(Optional.of("a"), moved);
        assertTrue(added.isEmpty());
        assertTrue(removed.isEmpty());
        assertTrue(disposed.isEmpty());
    }

    @Test
    @DisplayName("resizeCapacity returns the applied capacity and disposes the surplus")
    void resizeDisposesSurplus() {
        CacheStage<String> stage = stage(4);
        stage.offer("a");
        stage.offer("b");
        stage.offer("c");
        assertEquals(1, stage.resizeCapacity(1));
        assertEquals(1, stage.capacity());
        assertEquals(1, stage.size());
        assertEquals(List.of("a", "b"), disposed);
    }

    @Test
    @DisplayName("a null entry is rejected without disposal")
    void nullEntryRejected() {
        CacheStage<String> stage = stage(4);
        assertFalse(stage.offer(null));
        assertTrue(disposed.isEmpty());
        assertEquals(0, stage.size());
    }

    @Test
    @DisplayName("REQ-RTP-S-002: concurrent producers and consumers never double-dispose")
    void concurrentTrafficNeverDoubleDisposes() throws InterruptedException {
        AtomicInteger disposals = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        CacheStage<String> stage =
                new SimpleCacheStage<>("concurrent", 32, null, null, item -> disposals.incrementAndGet());

        int perProducer = 500;
        int producers = 4;
        CountDownLatch done = new CountDownLatch(producers + 1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int id = p;
            threads.add(new Thread(() -> {
                for (int i = 0; i < perProducer; i++) stage.offer(id + ":" + i);
                done.countDown();
            }));
        }
        threads.add(new Thread(() -> {
            for (int i = 0; i < producers * perProducer; i++) {
                if (stage.poll().isPresent()) consumed.incrementAndGet();
            }
            done.countDown();
        }));
        threads.forEach(Thread::start);
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        stage.close();

        // Every produced entry is accounted for exactly once: consumed, disposed on
        // overflow, or disposed at teardown. A double-dispose would overshoot the total.
        assertEquals(producers * perProducer, consumed.get() + disposals.get());
    }
}
