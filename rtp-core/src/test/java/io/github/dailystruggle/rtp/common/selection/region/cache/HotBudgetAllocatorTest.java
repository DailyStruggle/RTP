package io.github.dailystruggle.rtp.common.selection.region.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link HotBudgetAllocator} (ADR-078 phase 3).
 *
 * <p>Verifies EWMA demand smoothing, floor guarantees, proportional quotas,
 * and zero-I/O rebalancing transfers.
 */
class HotBudgetAllocatorTest {

    private static final class TestHotSink implements HotSink<String> {
        private final String name;
        private final CacheStage<String> stage;
        private final CacheStage<?> cold;
        private final Predicate<String> accepts;
        private long demandWeight;
        private int chunkCost = 1;
        private boolean leased = false;
        private boolean extrinsic = false;
        private boolean narrows = false;

        TestHotSink(String name, int stageCap, CacheStage<?> cold, Predicate<String> accepts, AtomicInteger disposals) {
            this.name = name;
            this.stage = new RingCacheStage<>(name, stageCap, null, null, item -> {
                if (disposals != null) disposals.incrementAndGet();
            });
            this.cold = cold;
            this.accepts = accepts;
        }

        @Override public String name() { return name; }
        @Override public CacheStage<String> stage() { return stage; }
        @Override public CacheStage<?> coldSource() { return cold; }
        @Override public boolean accepts(String entry) { return accepts != null && accepts.test(entry); }
        @Override public boolean hasExtrinsicVerifier() { return extrinsic; }
        @Override public boolean isExternallyLeased() { return leased; }
        @Override public boolean narrowsBeyondColdSource() { return narrows; }
        @Override public int chunkCostPerEntry() { return chunkCost; }
        @Override public long demandWeight() { return demandWeight; }

        void setDemandWeight(long weight) { this.demandWeight = weight; }
        void setChunkCost(int cost) { this.chunkCost = cost; }
        void setLeased(boolean leased) { this.leased = leased; }
    }

    private static CacheStage<String> cold() {
        return new SimpleCacheStage<>("cold", 16, null, null, null);
    }

    @Test
    @DisplayName("constructor validates alpha parameter")
    void constructorValidatesAlpha() {
        assertThrows(IllegalArgumentException.class, () -> new HotBudgetAllocator(0.0));
        assertThrows(IllegalArgumentException.class, () -> new HotBudgetAllocator(-0.5));
        assertThrows(IllegalArgumentException.class, () -> new HotBudgetAllocator(1.1));
    }

    @Test
    @DisplayName("EWMA demand weights smooth raw demand events across pulses")
    void ewmaDemandSmoothing() {
        HotBudgetAllocator allocator = new HotBudgetAllocator(0.5);
        CacheStage<String> cold = cold();
        TestHotSink sinkA = new TestHotSink("sinkA", 8, cold, e -> true, null);
        TestHotSink sinkB = new TestHotSink("sinkB", 8, cold, e -> true, null);

        allocator.recordDemand("sinkA", 100);
        allocator.recordDemand("sinkB", 20);

        Map<String, Double> weights1 = allocator.updateDemandWeights(List.of(sinkA, sinkB));
        assertEquals(100.0, weights1.get("sinkA"));
        assertEquals(20.0, weights1.get("sinkB"));
        assertEquals(100.0, allocator.getSmoothedDemand("sinkA"));
        assertEquals(20.0, allocator.getSmoothedDemand("sinkB"));

        // Pulse 2: sinkA has 0 demand, sinkB has 40 demand
        allocator.recordDemand("sinkB", 40);
        Map<String, Double> weights2 = allocator.updateDemandWeights(List.of(sinkA, sinkB));
        // sinkA: 0.5 * 0 + 0.5 * 100 = 50.0
        // sinkB: 0.5 * 40 + 0.5 * 20 = 30.0
        assertEquals(50.0, weights2.get("sinkA"));
        assertEquals(30.0, weights2.get("sinkB"));
    }

    @Test
    @DisplayName("floors are guaranteed first before dividing remaining budget")
    void floorGuaranteesSatisfiedFirst() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();
        CacheStage<String> cold = cold();
        TestHotSink login = new TestHotSink("login", 8, cold, e -> true, null);
        TestHotSink normal = new TestHotSink("normal", 8, cold, e -> true, null);

        allocator.recordDemand("login", 10);
        allocator.recordDemand("normal", 90);
        allocator.updateDemandWeights(List.of(login, normal));

        Map<String, HotBudgetAllocator.SinkConfig> configs = Map.of(
                "login", new HotBudgetAllocator.SinkConfig(4, 16),
                "normal", new HotBudgetAllocator.SinkConfig(2, 16)
        );

        // Total chunk budget: 10.
        // login floor: 4. normal floor: 2. Remaining budget: 4.
        // login normalized demand = 10 / 100 * 4 ~= 0.4 -> 0
        // normal normalized demand = 90 / 100 * 4 ~= 3.6 -> 3
        Map<String, Integer> quotas = allocator.computeQuotas(List.of(login, normal), configs, 10);
        assertEquals(4, quotas.get("login")); // floor satisfied
        assertEquals(5, quotas.get("normal")); // floor 2 + 3 additional
    }

    @Test
    @DisplayName("floors are scaled down proportionally when total floors exceed max budget")
    void floorsScaledWhenExceedingBudget() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();
        CacheStage<String> cold = cold();
        TestHotSink sinkA = new TestHotSink("sinkA", 8, cold, e -> true, null);
        TestHotSink sinkB = new TestHotSink("sinkB", 8, cold, e -> true, null);

        Map<String, HotBudgetAllocator.SinkConfig> configs = Map.of(
                "sinkA", new HotBudgetAllocator.SinkConfig(10, 20),
                "sinkB", new HotBudgetAllocator.SinkConfig(10, 20)
        );

        // Budget is 10, but total floor chunks = 20. Scale = 10/20 = 0.5.
        // Scaled floor for each = floor(10 * 0.5) = 5.
        Map<String, Integer> quotas = allocator.computeQuotas(List.of(sinkA, sinkB), configs, 10);
        assertEquals(5, quotas.get("sinkA"));
        assertEquals(5, quotas.get("sinkB"));
    }

    @Test
    @DisplayName("quotas respect maxCap ceilings")
    void quotasRespectMaxCap() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();
        CacheStage<String> cold = cold();
        TestHotSink sink = new TestHotSink("sink", 16, cold, e -> true, null);

        allocator.recordDemand("sink", 1000);
        allocator.updateDemandWeights(List.of(sink));

        Map<String, HotBudgetAllocator.SinkConfig> configs = Map.of(
                "sink", new HotBudgetAllocator.SinkConfig(1, 5)
        );

        // Huge budget, but maxCap is 5.
        Map<String, Integer> quotas = allocator.computeQuotas(List.of(sink), configs, 100);
        assertEquals(5, quotas.get("sink"));
    }

    @Test
    @DisplayName("zero-I/O rebalance transfers surplus to deficit sinks silently without disposal")
    void rebalanceTransfersSurplusSilently() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();
        AtomicInteger disposals = new AtomicInteger();
        CacheStage<String> cold = cold();

        TestHotSink donor = new TestHotSink("donor", 8, cold, e -> true, disposals);
        TestHotSink recipient = new TestHotSink("recipient", 8, cold, e -> true, disposals);

        // Pre-fill donor with 4 entries
        donor.stage().offerSilently("loc1");
        donor.stage().offerSilently("loc2");
        donor.stage().offerSilently("loc3");
        donor.stage().offerSilently("loc4");

        // Donor target is 2 (surplus of 2). Recipient target is 3 (deficit of 3).
        Map<String, Integer> targets = Map.of(
                "donor", 2,
                "recipient", 3
        );

        int transferred = allocator.rebalance(List.of(donor, recipient), targets);
        assertEquals(2, transferred);
        assertEquals(2, donor.stage().size());
        assertEquals(2, recipient.stage().size());
        assertEquals(0, disposals.get(), "no entries shall be disposed during rebalance");
    }

    @Test
    @DisplayName("zero-I/O rebalance leaves non-transferable entries in donor sink")
    void rebalanceLeavesIneligibleEntriesInDonor() {
        HotBudgetAllocator allocator = new HotBudgetAllocator();
        CacheStage<String> cold = cold();

        TestHotSink donor = new TestHotSink("donor", 8, cold, e -> true, null);
        TestHotSink leasedRecipient = new TestHotSink("leasedRecipient", 8, cold, e -> true, null);
        leasedRecipient.setLeased(true);

        donor.stage().offerSilently("loc1");
        donor.stage().offerSilently("loc2");

        Map<String, Integer> targets = Map.of(
                "donor", 0,
                "leasedRecipient", 2
        );

        int transferred = allocator.rebalance(List.of(donor, leasedRecipient), targets);
        assertEquals(0, transferred);
        assertEquals(2, donor.stage().size(), "donor entries must be restored if ineligible");
        assertEquals(0, leasedRecipient.stage().size());
    }
}
