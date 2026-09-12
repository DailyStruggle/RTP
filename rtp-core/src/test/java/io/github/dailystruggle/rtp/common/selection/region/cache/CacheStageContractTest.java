package io.github.dailystruggle.rtp.common.selection.region.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared {@link CacheStage} contract suite (ADR-078 phase 1 and phase 3).
 *
 * <p>Exercised against both {@link SimpleCacheStage} and {@link RingCacheStage}.
 */
class CacheStageContractTest {
    @FunctionalInterface
    interface StageFactory {
        CacheStage<String> create(String name,
                                  int capacity,
                                  Consumer<String> onAdd,
                                  Consumer<String> onRemove,
                                  Consumer<String> onDispose);
    }

    record StageTestCase(String name, StageFactory factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<StageTestCase> stageProviders() {
        return Stream.of(
                new StageTestCase("SimpleCacheStage", SimpleCacheStage::new),
                new StageTestCase("RingCacheStage", RingCacheStage::new)
        );
    }

    private static class TrackingCallbacks {
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        final List<String> disposed = new ArrayList<>();

        CacheStage<String> create(StageTestCase testCase, int capacity) {
            return testCase.factory().create("test", capacity, added::add, removed::add, disposed::add);
        }
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("REQ-RTP-S-002: overflow disposes the rejected entry exactly once")
    void overflowDisposesOnce(StageTestCase testCase) {
        TrackingCallbacks tracking = new TrackingCallbacks();
        CacheStage<String> stage = tracking.create(testCase, 1);
        assertTrue(stage.offer("a"));
        assertFalse(stage.offer("b"));
        assertEquals(List.of("b"), tracking.disposed);
        assertEquals(List.of("a"), tracking.added);
        assertEquals(1, stage.size());
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("REQ-RTP-S-002: close disposes every entry and recycles nothing")
    void closeDisposesEveryEntry(StageTestCase testCase) {
        TrackingCallbacks tracking = new TrackingCallbacks();
        CacheStage<String> stage = tracking.create(testCase, 4);
        stage.offer("a");
        stage.offer("b");
        stage.close();
        assertEquals(List.of("a", "b"), tracking.disposed);
        assertEquals(0, stage.size());
        assertTrue(tracking.removed.isEmpty(), "teardown is disposal, not a persistence-visible removal");
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("poll transfers ownership and fires the persistence-visible callbacks")
    void pollFiresRemoveCallback(StageTestCase testCase) {
        TrackingCallbacks tracking = new TrackingCallbacks();
        CacheStage<String> stage = tracking.create(testCase, 4);
        stage.offer("a");
        assertEquals(Optional.of("a"), stage.poll());
        assertEquals(List.of("a"), tracking.removed);
        assertTrue(tracking.disposed.isEmpty(), "a polled entry is owned by the caller, not disposed");
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("internal movement is silent: pollSilently/offerSilently fire no callbacks")
    void silentMovementFiresNoCallbacks(StageTestCase testCase) {
        TrackingCallbacks tracking = new TrackingCallbacks();
        CacheStage<String> source = tracking.create(testCase, 4);
        source.offerSilently("a");
        Optional<String> moved = source.pollSilently();
        assertEquals(Optional.of("a"), moved);
        assertTrue(tracking.added.isEmpty());
        assertTrue(tracking.removed.isEmpty());
        assertTrue(tracking.disposed.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("resizeCapacity returns the applied capacity and disposes the surplus")
    void resizeDisposesSurplus(StageTestCase testCase) {
        TrackingCallbacks tracking = new TrackingCallbacks();
        CacheStage<String> stage = tracking.create(testCase, 4);
        stage.offer("a");
        stage.offer("b");
        stage.offer("c");
        int applied = stage.resizeCapacity(1);
        assertEquals(applied, stage.capacity());
        assertEquals(1, stage.size());
        assertEquals(List.of("a", "b"), tracking.disposed);
        assertEquals(Optional.of("c"), stage.poll());
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("a null entry is rejected without disposal")
    void nullEntryRejected(StageTestCase testCase) {
        TrackingCallbacks tracking = new TrackingCallbacks();
        CacheStage<String> stage = tracking.create(testCase, 4);
        assertFalse(stage.offer(null));
        assertTrue(tracking.disposed.isEmpty());
        assertEquals(0, stage.size());
    }

    @ParameterizedTest
    @MethodSource("stageProviders")
    @DisplayName("REQ-RTP-S-002: concurrent producers and consumers never double-dispose")
    void concurrentTrafficNeverDoubleDisposes(StageTestCase testCase) throws InterruptedException {
        AtomicInteger disposals = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        CacheStage<String> stage =
                testCase.factory().create("concurrent", 32, null, null, item -> disposals.incrementAndGet());

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
