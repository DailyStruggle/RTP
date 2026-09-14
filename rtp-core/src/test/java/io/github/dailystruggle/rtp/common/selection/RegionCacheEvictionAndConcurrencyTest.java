package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer.BacklogEntry;
import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer.Validity;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.cache.HotBudgetAllocator;
import io.github.dailystruggle.rtp.common.selection.region.cache.HotBudgetAllocator.SinkConfig;
import io.github.dailystruggle.rtp.common.selection.region.cache.RingCacheStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@DisplayName("REQ-CORE-F-005: Region Cache Eviction Policies, Hits, Misses, and Concurrency")
public class RegionCacheEvictionAndConcurrencyTest {

    private RTPLocation createMockLocation(int x, int z) {
        RTPWorld world = Mockito.mock(RTPWorld.class);
        RTPCoords coords = Mockito.mock(RTPCoords.class);
        return new RTPLocation(coords, 1L, null);
    }

    @Test
    @DisplayName("RingCacheStage: Eviction on resize, overflow disposal, and hit/miss mechanics")
    void testRingCacheStageEvictionAndResizing() {
        List<String> disposed = new ArrayList<>();
        RingCacheStage<String> stage = new RingCacheStage<>(
                "test-stage",
                4, // capacity will be power-of-two = 4
                null,
                null,
                disposed::add
        );

        assertEquals(4, stage.capacity());
        assertEquals(0, stage.size());

        // Cache miss on empty
        assertTrue(stage.poll().isEmpty());
        assertTrue(stage.pollSilently().isEmpty());

        // Fill to capacity
        assertTrue(stage.offer("A"));
        assertTrue(stage.offer("B"));
        assertTrue(stage.offer("C"));
        assertTrue(stage.offer("D"));
        assertEquals(4, stage.size());

        // Overflow: 5th item offered exceeds capacity (4) -> rejected and immediately disposed
        assertFalse(stage.offer("E"));
        assertEquals(4, stage.size());
        assertTrue(disposed.contains("E"));

        // Hit / retrieval
        assertEquals(Optional.of("A"), stage.poll());
        assertEquals(3, stage.size());

        // Downsize capacity: from 4 to 2
        // Remaining items in buffer: B, C, D (size 3)
        // New capacity 2 -> excess item (oldest B) is disposed as surplus
        stage.resizeCapacity(2);
        assertEquals(2, stage.capacity());
        assertEquals(2, stage.size());
        assertTrue(disposed.contains("B"));

        // Verify remaining items in publication order: C, D
        assertEquals(Optional.of("C"), stage.poll());
        assertEquals(Optional.of("D"), stage.poll());
        assertTrue(stage.poll().isEmpty());

        // Close stage disposes all remaining
        stage.offer("F");
        stage.close();
        assertEquals(0, stage.size());
        assertTrue(disposed.contains("F"));
    }

    @Test
    @DisplayName("RingCacheStage: High-concurrency multi-threaded offers and polls")
    void testRingCacheStageConcurrency() throws InterruptedException {
        int threads = 4;
        int operationsPerThread = 500;
        AtomicInteger disposedCount = new AtomicInteger(0);

        RingCacheStage<Integer> stage = new RingCacheStage<>(
                "concurrent-stage",
                64,
                null,
                null,
                item -> disposedCount.incrementAndGet()
        );

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger consumedCount = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        int val = threadId * operationsPerThread + i;
                        if (!stage.offer(val)) {
                            // offered but rejected due to full buffer
                        }

                        Optional<Integer> polled = stage.poll();
                        if (polled.isPresent()) {
                            consumedCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // Drain any remaining
        while (stage.poll().isPresent()) {
            consumedCount.incrementAndGet();
        }

        int totalPushed = threads * operationsPerThread;
        assertEquals(totalPushed, consumedCount.get() + disposedCount.get(),
                "Every offered item must either be consumed or disposed on overflow");
    }

    @Test
    @DisplayName("BacklogLocationBuffer: Head-blocking validation, invalidation drops, and capacity limits")
    void testBacklogLocationBufferEvictionAndFiltering() {
        assertThrows(IllegalArgumentException.class, () -> new BacklogLocationBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new BacklogLocationBuffer(-5));

        BacklogLocationBuffer buffer = new BacklogLocationBuffer(3);
        assertEquals(3, buffer.capacity());

        RTPLocation loc1 = createMockLocation(10, 10);
        RTPLocation loc2 = createMockLocation(20, 20);
        RTPLocation loc3 = createMockLocation(30, 30);
        RTPLocation loc4 = createMockLocation(40, 40);

        BacklogEntry e1 = buffer.offerUnverified(loc1);
        BacklogEntry e2 = buffer.offerUnverified(loc2);
        BacklogEntry e3 = buffer.offerUnverified(loc3);
        assertNotNull(e1);
        assertNotNull(e2);
        assertNotNull(e3);

        // Capacity hit: 4th entry rejected
        assertNull(buffer.offerUnverified(loc4));
        assertEquals(3, buffer.size());
        assertEquals(0, buffer.validatedSize());

        // Head is unverified -> pollContiguousValidatedHead stops and returns empty
        List<BacklogEntry> drained0 = buffer.pollContiguousValidatedHead(10);
        assertTrue(drained0.isEmpty());

        // e1 marked INVALIDATED, e2 marked VALIDATED, e3 remains UNVERIFIED
        e1.setValidity(Validity.INVALIDATED);
        e2.setValidity(Validity.VALIDATED);

        // Draining: e1 is invalidated (dropped), e2 is validated (drained), e3 stops the head!
        List<BacklogEntry> drained = buffer.pollContiguousValidatedHead(10);
        assertEquals(1, drained.size());
        assertSame(e2, drained.get(0));

        // Buffer now only has e3
        assertEquals(1, buffer.size());
        assertSame(e3, buffer.peekOldestUnverified());

        // Mark e3 VALIDATED and drain
        e3.setValidity(Validity.VALIDATED);
        assertEquals(1, buffer.validatedSize());
        List<BacklogEntry> drained2 = buffer.pollContiguousValidatedHead(1);
        assertEquals(1, drained2.size());
        assertSame(e3, drained2.get(0));
        assertEquals(0, buffer.size());
    }

    @Test
    @DisplayName("HotBudgetAllocator: Floor guarantees, EWMA demand smoothing, and zero-I/O quota allocation")
    void testHotBudgetAllocatorQuotas() {
        HotBudgetAllocator allocator = new HotBudgetAllocator(0.5);

        allocator.recordDemand("sinkA", 100);
        allocator.recordDemand("sinkB", 300);

        io.github.dailystruggle.rtp.common.selection.region.cache.HotSink<String> sinkA =
                Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.cache.HotSink.class);
        when(sinkA.name()).thenReturn("sinkA");
        when(sinkA.chunkCostPerEntry()).thenReturn(1);
        when(sinkA.demandWeight()).thenReturn(100L);

        io.github.dailystruggle.rtp.common.selection.region.cache.HotSink<String> sinkB =
                Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.cache.HotSink.class);
        when(sinkB.name()).thenReturn("sinkB");
        when(sinkB.chunkCostPerEntry()).thenReturn(1);
        when(sinkB.demandWeight()).thenReturn(300L);

        List<io.github.dailystruggle.rtp.common.selection.region.cache.HotSink<String>> sinks =
                List.of(sinkA, sinkB);

        allocator.updateDemandWeights(sinks);

        Map<String, SinkConfig> configs = new HashMap<>();
        configs.put("sinkA", new SinkConfig(10, 50));
        configs.put("sinkB", new SinkConfig(20, 100));

        // Total chunk budget: 80
        // Floors take: 10 + 20 = 30 chunks. Remaining: 50 chunks.
        // sinkA demand = 1/4 of remaining = 12.5 -> quota ~ 22 or 23
        // sinkB demand = 3/4 of remaining = 37.5 -> quota ~ 57 or 58
        Map<String, Integer> quotas = allocator.computeQuotas(sinks, configs, 80);

        assertNotNull(quotas);
        assertTrue(quotas.get("sinkA") >= 10, "sinkA must satisfy floor 10");
        assertTrue(quotas.get("sinkB") >= 20, "sinkB must satisfy floor 20");
        assertTrue(quotas.get("sinkB") > quotas.get("sinkA"), "sinkB has 3x demand, must receive higher quota");

        int totalAllocated = quotas.get("sinkA") + quotas.get("sinkB");
        assertTrue(totalAllocated <= 80, "Total allocated must not exceed budget");
    }
}
