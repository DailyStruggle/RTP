package io.github.dailystruggle.rtp.common.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link ChunkLoadProfile}: the per-class (generated vs
 * ungenerated) running-minimum "floor" (smallest single-chunk load time), the
 * cumulative total/count, the aggregate-across-classes views, the
 * non-positive-input guard, and wait-free concurrent updates.
 */
class ChunkLoadProfileTest {

    @BeforeEach
    void reset() {
        ChunkLoadProfile.GLOBAL.reset();
    }

    @Test
    @DisplayName("empty profile reports -1 floor and zero totals for both classes and aggregate")
    void emptyProfile() {
        assertEquals(-1L, ChunkLoadProfile.GLOBAL.minNanos(true), "no generated load -> floor -1");
        assertEquals(-1L, ChunkLoadProfile.GLOBAL.minNanos(false), "no ungenerated load -> floor -1");
        assertEquals(-1L, ChunkLoadProfile.GLOBAL.minNanos(), "no load -> aggregate floor -1");
        assertEquals(0L, ChunkLoadProfile.GLOBAL.loadCount());
        assertEquals(0L, ChunkLoadProfile.GLOBAL.totalNanos());
    }

    @Test
    @DisplayName("generated and ungenerated floors / totals are tracked independently")
    void perClassTracking() {
        ChunkLoadProfile.GLOBAL.record(true, 5_000_000L);    // generated, 5 ms
        ChunkLoadProfile.GLOBAL.record(true, 2_000_000L);    // generated, 2 ms <- gen floor
        ChunkLoadProfile.GLOBAL.record(false, 40_000_000L);  // ungenerated, 40 ms <- ungen floor
        ChunkLoadProfile.GLOBAL.record(false, 90_000_000L);  // ungenerated, 90 ms

        assertEquals(2_000_000L, ChunkLoadProfile.GLOBAL.minNanos(true), "generated floor");
        assertEquals(40_000_000L, ChunkLoadProfile.GLOBAL.minNanos(false), "ungenerated floor");
        assertEquals(2L, ChunkLoadProfile.GLOBAL.loadCount(true));
        assertEquals(2L, ChunkLoadProfile.GLOBAL.loadCount(false));
        assertEquals(7_000_000L, ChunkLoadProfile.GLOBAL.totalNanos(true), "generated total");
        assertEquals(130_000_000L, ChunkLoadProfile.GLOBAL.totalNanos(false), "ungenerated total");

        // Aggregate views combine both classes.
        assertEquals(2_000_000L, ChunkLoadProfile.GLOBAL.minNanos(), "aggregate floor is the smaller");
        assertEquals(4L, ChunkLoadProfile.GLOBAL.loadCount());
        assertEquals(137_000_000L, ChunkLoadProfile.GLOBAL.totalNanos());
    }

    @Test
    @DisplayName("aggregate floor ignores a class with no recorded load")
    void aggregateWithOneClassOnly() {
        ChunkLoadProfile.GLOBAL.record(false, 8_000_000L);   // ungenerated only
        assertEquals(-1L, ChunkLoadProfile.GLOBAL.minNanos(true), "generated still empty");
        assertEquals(8_000_000L, ChunkLoadProfile.GLOBAL.minNanos(false));
        assertEquals(8_000_000L, ChunkLoadProfile.GLOBAL.minNanos(),
                "aggregate floor must skip the empty generated class");
    }

    @Test
    @DisplayName("non-positive durations are ignored (clock artefacts don't peg the floor)")
    void nonPositiveIgnored() {
        ChunkLoadProfile.GLOBAL.record(true, 0L);
        ChunkLoadProfile.GLOBAL.record(false, -100L);
        assertEquals(-1L, ChunkLoadProfile.GLOBAL.minNanos(), "zero/negative must not become the floor");
        assertEquals(0L, ChunkLoadProfile.GLOBAL.loadCount());
    }

    @Test
    @DisplayName("concurrent records are wait-free and converge on the true minimum")
    void concurrentRecords() throws InterruptedException {
        final int threads = 8;
        final int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicInteger seed = new AtomicInteger(0);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    int base = seed.incrementAndGet();
                    for (int i = 0; i < perThread; i++) {
                        // Alternate classes; smallest value any thread submits is 1
                        // (base==1, i==0), guaranteeing a deterministic global floor.
                        ChunkLoadProfile.GLOBAL.record((i & 1) == 0, (long) base + i);
                    }
                });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "pool must finish");
        }
        assertEquals((long) threads * perThread, ChunkLoadProfile.GLOBAL.loadCount());
        // The minimum submitted value across all threads is base==1, i==0 -> 1.
        assertEquals(1L, ChunkLoadProfile.GLOBAL.minNanos(),
                "concurrent updates must converge on the true global minimum");
    }
}
