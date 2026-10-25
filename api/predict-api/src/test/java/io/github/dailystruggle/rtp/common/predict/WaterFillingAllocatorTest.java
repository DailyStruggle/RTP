package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit verification for {@link WaterFillingAllocator}.
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 * @governedBy ADR-124
 */
@DisplayName("ADR-087 / ADR-124: WaterFillingAllocator Constrained Max-Min Fairness and Share Normalization")
class WaterFillingAllocatorTest {

    @Test
    @DisplayName("Null, empty, or non-positive budgets return zeroed or empty allocations")
    void emptyAndNonPositiveInputs() {
        assertTrue(WaterFillingAllocator.allocate(null, Map.of("a", 1.0), 10.0).isEmpty());
        assertTrue(WaterFillingAllocator.allocate(Map.of(), Map.of("a", 1.0), 10.0).isEmpty());

        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("a", 5.0);
        demands.put("b", 10.0);

        Map<String, Double> zeroBudget = WaterFillingAllocator.allocate(demands, Map.of("a", 1.0, "b", 1.0), 0.0);
        assertEquals(0.0, zeroBudget.get("a"), 1e-12);
        assertEquals(0.0, zeroBudget.get("b"), 1e-12);

        Map<String, Double> negBudget = WaterFillingAllocator.allocate(demands, Map.of("a", 1.0, "b", 1.0), -4.0);
        assertEquals(0.0, negBudget.get("a"), 1e-12);
        assertEquals(0.0, negBudget.get("b"), 1e-12);
    }

    @Test
    @DisplayName("Budget exceeding total demand satisfies every key at its exact deliverable demand")
    void budgetExceedingTotalDemandSatisfiesAll() {
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("r1", 4.0);
        demands.put("r2", 6.0);
        demands.put("r3", -2.0);

        Map<String, Double> allocated = WaterFillingAllocator.allocate(demands, Map.of("r1", 1.0, "r2", 3.0), 25.0);
        assertEquals(List.of("r1", "r2", "r3"), new ArrayList<>(allocated.keySet()));
        assertEquals(4.0, allocated.get("r1"), 1e-12);
        assertEquals(6.0, allocated.get("r2"), 1e-12);
        assertEquals(0.0, allocated.get("r3"), 1e-12);
    }

    @Test
    @DisplayName("Multi-round weighted water-filling caps low-demand keys and redistributes surplus proportionally")
    void multiRoundWeightedWaterFilling() {
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("cappedEarly", 2.0);
        demands.put("medium", 10.0);
        demands.put("heavy", 20.0);

        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("cappedEarly", 1.0);
        weights.put("medium", 1.0);
        weights.put("heavy", 3.0);

        // Total budget = 14.0 (< sumDemands = 32.0)
        // First round: theta = 14 / 5 = 2.8 -> cappedEarly (2.8 * 1 >= 2.0) caps at 2.0, leaving 12.0
        // Second round over medium (w=1) and heavy (w=3): theta = 12 / 4 = 3.0 -> medium = 3.0, heavy = 9.0
        Map<String, Double> result = WaterFillingAllocator.allocate(demands, weights, 14.0);
        assertEquals(2.0, result.get("cappedEarly"), 1e-9);
        assertEquals(3.0, result.get("medium"), 1e-9);
        assertEquals(9.0, result.get("heavy"), 1e-9);

        double sum = result.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(14.0, sum, 1e-9);
    }

    @Test
    @DisplayName("Equal-share fallback distributes remaining budget evenly when active weights sum to zero")
    void equalShareFallbackWhenWeightsAreZeroOrMissing() {
        Map<Integer, Double> demands = new LinkedHashMap<>();
        demands.put(1, 1.0);
        demands.put(2, 10.0);
        demands.put(3, 10.0);

        // Null weights -> equal water-filling: key 1 caps at 1.0, keys 2 and 3 split remaining 8.0 -> 4.0 each
        Map<Integer, Double> result = WaterFillingAllocator.allocate(demands, null, 9.0);
        assertEquals(1.0, result.get(1), 1e-9);
        assertEquals(4.0, result.get(2), 1e-9);
        assertEquals(4.0, result.get(3), 1e-9);
    }

    @Test
    @DisplayName("normalizeShares returns unmodifiable normalized shares in [0, 1] and handles zero/invalid inputs")
    void normalizeSharesBehavior() {
        assertTrue(WaterFillingAllocator.normalizeShares(null).isEmpty());
        assertTrue(WaterFillingAllocator.normalizeShares(Map.of()).isEmpty());

        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("main", 3.0);
        rates.put("nether", 1.0);
        rates.put("end", -5.0);

        Map<String, Double> shares = WaterFillingAllocator.normalizeShares(rates);
        assertEquals(0.75, shares.get("main"), 1e-9);
        assertEquals(0.25, shares.get("nether"), 1e-9);
        assertEquals(0.0, shares.get("end"), 1e-9);
        assertThrows(UnsupportedOperationException.class, () -> shares.put("extra", 0.1));

        Map<String, Double> allZero = WaterFillingAllocator.normalizeShares(Map.of("a", 0.0, "b", Double.NaN));
        assertEquals(0.0, allZero.get("a"), 1e-9);
        assertEquals(0.0, allZero.get("b"), 1e-9);
    }
}
