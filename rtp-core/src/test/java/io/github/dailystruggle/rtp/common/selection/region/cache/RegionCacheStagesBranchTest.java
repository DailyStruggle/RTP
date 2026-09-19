package io.github.dailystruggle.rtp.common.selection.region.cache;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RegionCacheStagesBranchTest - Missed branches in RingCacheStage, HotBudgetAllocator, SimpleCacheStage, and LockFreeLocationBuffer")
class RegionCacheStagesBranchTest {

    private RTPLocation loc(int x, int z) {
        return new RTPLocation(new RTPCoords("world", x, 64, z), 1L, null);
    }

    // -------------------------------------------------------------------------
    // RingCacheStage Branches
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RingCacheStage handles zero and negative requested capacities clamping to min 1")
    void ringCacheStage_zeroAndNegativeCapacities() {
        RingCacheStage<String> s0 = new RingCacheStage<>("zero", 0, null, null, null);
        assertTrue(s0.capacity() >= 1);
        assertEquals("zero", s0.name());

        RingCacheStage<String> sNeg = new RingCacheStage<>(null, -10, null, null, null);
        assertTrue(sNeg.capacity() >= 1);
        assertEquals("unnamed", sNeg.name());
    }

    @Test
    @DisplayName("RingCacheStage wrapping LockFreeLocationBuffer exercises delegate paths, non-RTPLocation rejection and disposal")
    void ringCacheStage_delegateWrappingBranches() {
        List<Object> disposed = new ArrayList<>();
        List<RTPLocation> added = new ArrayList<>();
        List<RTPLocation> removed = new ArrayList<>();

        LockFreeLocationBuffer buffer = new LockFreeLocationBuffer(4);
        RingCacheStage<Object> stage = new RingCacheStage<>(
                "delegateStage",
                buffer,
                item -> added.add((RTPLocation) item),
                item -> removed.add((RTPLocation) item),
                disposed::add
        );

        assertSame(buffer, stage.delegate());
        assertEquals(4, stage.capacity());
        assertEquals(0, stage.size());

        // Null offer
        assertFalse(stage.offer(null));
        assertFalse(stage.offerSilently(null));

        // Non-RTPLocation item offered to delegate -> rejected and disposed
        String nonLoc = "not-a-location";
        assertFalse(stage.offer(nonLoc));
        assertTrue(disposed.contains(nonLoc));

        assertFalse(stage.offerSilently("another-non-loc"));
        assertTrue(disposed.contains("another-non-loc"));

        // Offer valid RTPLocation
        RTPLocation l1 = loc(1, 1);
        assertTrue(stage.offer(l1));
        assertEquals(1, stage.size());
        assertTrue(added.contains(l1));

        RTPLocation l2 = loc(2, 2);
        assertTrue(stage.offerSilently(l2));
        assertEquals(2, stage.size());

        // Poll silently
        Optional<Object> polledSilent = stage.pollSilently();
        assertTrue(polledSilent.isPresent());
        assertEquals(l1, polledSilent.get());

        // Poll with callback
        Optional<Object> polled = stage.poll();
        assertTrue(polled.isPresent());
        assertEquals(l2, polled.get());
        assertTrue(removed.contains(l2));

        // Poll when empty
        assertTrue(stage.poll().isEmpty());
        assertTrue(stage.pollSilently().isEmpty());

        // Fill buffer to capacity and test overflow
        RTPLocation l3 = loc(3, 3);
        RTPLocation l4 = loc(4, 4);
        RTPLocation l5 = loc(5, 5);
        RTPLocation l6 = loc(6, 6);
        assertTrue(stage.offer(l3));
        assertTrue(stage.offer(l4));
        assertTrue(stage.offer(l5));
        assertTrue(stage.offer(l6));
        assertEquals(4, stage.size());

        RTPLocation overflow = loc(7, 7);
        assertFalse(stage.offer(overflow));
        assertTrue(disposed.contains(overflow));

        // Delegate resizeCapacity returns buffer capacity (delegate does not resize in-place)
        assertEquals(4, stage.resizeCapacity(10));

        // Delegate close drains and disposes remaining
        stage.close();
        assertEquals(0, stage.size());
        assertTrue(disposed.contains(l3));
        assertTrue(disposed.contains(l4));
        assertTrue(disposed.contains(l5));
        assertTrue(disposed.contains(l6));
    }

    @Test
    @DisplayName("RingCacheStage constructor with null delegate throws IllegalArgumentException")
    void ringCacheStage_nullDelegateThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RingCacheStage<>("test", (LockFreeLocationBuffer) null, null));
    }

    @Test
    @DisplayName("RingCacheStage resizeCapacity upsize, downsize, and surplus disposal")
    void ringCacheStage_resizeCapacityBranches() {
        List<String> disposed = new ArrayList<>();
        RingCacheStage<String> stage = new RingCacheStage<>("resizeStage", 2, null, null, disposed::add);
        assertEquals(2, stage.capacity());

        stage.offer("one");
        stage.offer("two");
        assertEquals(2, stage.size());

        // Upsize to 8
        int newCap = stage.resizeCapacity(8);
        assertTrue(newCap >= 8);
        assertEquals(2, stage.size());
        assertTrue(disposed.isEmpty());

        // Offer more
        stage.offer("three");
        stage.offer("four");
        assertEquals(4, stage.size());

        // Downsize to 2 -> 2 items must be disposed as excess
        stage.resizeCapacity(2);
        assertEquals(2, stage.capacity());
        assertEquals(2, stage.size());
        assertEquals(2, disposed.size());
        assertTrue(disposed.contains("one"));
        assertTrue(disposed.contains("two"));

        // Remaining elements should be three and four
        assertEquals(Optional.of("three"), stage.poll());
        assertEquals(Optional.of("four"), stage.poll());
        assertTrue(stage.poll().isEmpty());
    }

    @Test
    @DisplayName("RingCacheStage callbacks fire on offer and poll")
    void ringCacheStage_callbacksFire() {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        RingCacheStage<String> stage = new RingCacheStage<>("cbStage", 4, added::add, removed::add, null);

        assertTrue(stage.offer("item1"));
        assertEquals(List.of("item1"), added);

        Optional<String> polled = stage.poll();
        assertTrue(polled.isPresent());
        assertEquals(List.of("item1"), removed);
    }

    // -------------------------------------------------------------------------
    // SimpleCacheStage Branches
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SimpleCacheStage handles null offer, clamping, resizing, and disposal")
    void simpleCacheStage_allBranches() {
        List<String> disposed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        SimpleCacheStage<String> stage = new SimpleCacheStage<>(
                null, -5, added::add, removed::add, disposed::add
        );
        assertEquals("unnamed", stage.name());
        assertEquals(1, stage.capacity());

        // Null offer returns false without adding
        assertFalse(stage.offer(null));
        assertFalse(stage.offerSilently(null));
        assertEquals(0, stage.size());

        // Offer to capacity 1
        assertTrue(stage.offer("A"));
        assertEquals(1, stage.size());
        assertEquals(List.of("A"), added);

        // Overflow: 2nd offer rejected and disposed
        assertFalse(stage.offer("B"));
        assertTrue(disposed.contains("B"));

        assertFalse(stage.offerSilently("C"));
        assertTrue(disposed.contains("C"));

        // Resize capacity up to 3
        assertEquals(3, stage.resizeCapacity(3));
        assertEquals(3, stage.capacity());

        assertTrue(stage.offerSilently("D"));
        assertTrue(stage.offer("E"));
        assertEquals(3, stage.size());

        // Poll silently
        Optional<String> p1 = stage.pollSilently();
        assertTrue(p1.isPresent());
        assertEquals("A", p1.get());
        assertTrue(removed.isEmpty()); // silent, so removed not called

        // Poll with callback
        Optional<String> p2 = stage.poll();
        assertTrue(p2.isPresent());
        assertEquals("D", p2.get());
        assertTrue(removed.contains("D"));

        // Add 2 more to fill to 3
        stage.offer("F");
        stage.offer("G");
        assertEquals(3, stage.size());

        // Resize capacity down to 1: surplus (2 items) disposed
        assertEquals(1, stage.resizeCapacity(1));
        assertEquals(1, stage.capacity());
        assertEquals(1, stage.size());
        assertTrue(disposed.contains("E"));
        assertTrue(disposed.contains("F"));

        // Close drains remaining G
        stage.close();
        assertEquals(0, stage.size());
        assertTrue(disposed.contains("G"));
    }

    // -------------------------------------------------------------------------
    // HotBudgetAllocator Branches
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HotBudgetAllocator invalid alpha throws, recordDemand null/zero ignored")
    void hotBudgetAllocator_constructorAndRecordDemand() {
        assertThrows(IllegalArgumentException.class, () -> new HotBudgetAllocator(0.0));
        assertThrows(IllegalArgumentException.class, () -> new HotBudgetAllocator(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new HotBudgetAllocator(1.1));

        HotBudgetAllocator allocator = new HotBudgetAllocator(0.5);
        allocator.recordDemand(null, 10);
        allocator.recordDemand("sink", 0);
        allocator.recordDemand("sink", -5);
        assertEquals(0.0, allocator.getSmoothedDemand("sink"));

        allocator.recordDemand("sink", 100);
        // smoothedDemand updated on updateDemandWeights
        assertEquals(0.0, allocator.getSmoothedDemand("sink"));
    }

    @Test
    @DisplayName("HotBudgetAllocator updateDemandWeights handles null and empty sinks")
    void hotBudgetAllocator_updateDemandWeights() {
        HotBudgetAllocator allocator = new HotBudgetAllocator(0.4);
        Map<String, Double> weights = allocator.updateDemandWeights(null);
        assertTrue(weights.isEmpty());

        weights = allocator.updateDemandWeights(Collections.emptyList());
        assertTrue(weights.isEmpty());
    }

    @Test
    @DisplayName("HotBudgetAllocator zero-I/O transfer between donor and needy sinks")
    void hotBudgetAllocator_transferEligibleEntries() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();

        SimpleCacheStage<RTPLocation> donorStage = new SimpleCacheStage<>("donor", 10, null, null, null);
        SimpleCacheStage<RTPLocation> needyStage = new SimpleCacheStage<>("needy", 10, null, null, null);
        SimpleCacheStage<RTPLocation> commonCold = new SimpleCacheStage<>("cold", 100, null, null, null);

        for (int i = 0; i < 5; i++) {
            donorStage.offer(loc(i, i));
        }

        HotSink<RTPLocation> donor = new HotSink<>() {
            @Override public String name() { return "donor"; }
            @Override public CacheStage<RTPLocation> stage() { return donorStage; }
            @Override public CacheStage<?> coldSource() { return commonCold; }
            @Override public boolean accepts(RTPLocation entry) { return true; }
            @Override public boolean hasExtrinsicVerifier() { return false; }
            @Override public boolean isExternallyLeased() { return false; }
            @Override public boolean narrowsBeyondColdSource() { return false; }
            @Override public int chunkCostPerEntry() { return 1; }
            @Override public long demandWeight() { return 1L; }
        };

        HotSink<RTPLocation> needy = new HotSink<>() {
            @Override public String name() { return "needy"; }
            @Override public CacheStage<RTPLocation> stage() { return needyStage; }
            @Override public CacheStage<?> coldSource() { return commonCold; }
            @Override public boolean accepts(RTPLocation entry) { return true; }
            @Override public boolean hasExtrinsicVerifier() { return false; }
            @Override public boolean isExternallyLeased() { return false; }
            @Override public boolean narrowsBeyondColdSource() { return false; }
            @Override public int chunkCostPerEntry() { return 1; }
            @Override public long demandWeight() { return 5L; }
        };

        // Before transfer: donor has 5 (target 2 => surplus 3), needy has 0 (target 3 => deficit 3)
        assertEquals(5, donorStage.size());
        assertEquals(0, needyStage.size());

        Map<String, Integer> targets = Map.of("donor", 2, "needy", 3);
        int transferred = allocator.rebalance(List.of(donor, needy), targets);
        assertEquals(3, transferred);
        assertEquals(3, needyStage.size());
        assertEquals(2, donorStage.size());
    }

    @Test
    @DisplayName("HotBudgetAllocator rebalance with null sinks or empty targets does nothing")
    void hotBudgetAllocator_rebalanceZeroIoEdgeCases() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();
        assertEquals(0, allocator.rebalance(null, Map.of()));
        assertEquals(0, allocator.rebalance(Collections.emptyList(), Map.of()));
        assertEquals(0, allocator.rebalance(List.of(), null));
    }

    // -------------------------------------------------------------------------
    // LockFreeLocationBuffer Concurrency & Full-Queue Rejection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LockFreeLocationBuffer single-producer single-consumer concurrent poll and fill race")
    void lockFreeLocationBuffer_concurrentPollAndFillRace() throws InterruptedException {
        int capacity = 32;
        LockFreeLocationBuffer buffer = new LockFreeLocationBuffer(capacity);
        int totalItems = 50;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<Integer> polledIds = Collections.synchronizedList(new ArrayList<>(totalItems));
        AtomicInteger offered = new AtomicInteger(0);

        // Single Producer thread
        Thread producer = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < totalItems; i++) {
                    RTPLocation location = loc(i, i);
                    while (!buffer.offer(location)) {
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

        // Single Consumer thread
        Thread consumer = new Thread(() -> {
            try {
                startLatch.await();
                while (polledIds.size() < totalItems) {
                    RTPLocation location = buffer.poll();
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

        producer.start();
        consumer.start();

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS));

        producer.join();
        consumer.join();

        assertEquals(totalItems, offered.get());
        assertEquals(totalItems, polledIds.size());
        for (int i = 0; i < totalItems; i++) {
            assertEquals(i, polledIds.get(i));
        }
        assertEquals(0, buffer.size());
    }
}
