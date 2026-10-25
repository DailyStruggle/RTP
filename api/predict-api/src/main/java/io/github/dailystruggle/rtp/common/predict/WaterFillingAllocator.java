package io.github.dailystruggle.rtp.common.predict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constrained weighted max-min fairness (water-filling) solver and share normalizer.
 *
 * <p>Solves {@code x_k = min(d_bar_k, theta * w_k)} such that {@code sum_k x_k = totalBudget}
 * whenever {@code totalBudget < sum_k d_bar_k}, falling back to equal water-filling across
 * remaining active keys when their weights sum to {@code 0.0}.
 *
 * <p>Zero external dependencies, zero locks, and zero thread creation.
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 * @governedBy ADR-124
 */
public final class WaterFillingAllocator {

    private static final double EPSILON = 1e-12;

    private WaterFillingAllocator() {
    }

    /**
     * Computes constrained water-filling allocations across keys in insertion order.
     *
     * @param deliverableDemands per-key upper demand caps {@code d_bar_k} (negative/null clamped to {@code 0.0})
     * @param weights            per-key positive weights {@code w_k} (negative/null clamped to {@code 0.0})
     * @param totalBudget        total budget {@code R*} to distribute
     * @param <K>                key type
     * @return mutable {@link LinkedHashMap} preserving {@code deliverableDemands} iteration order
     */
    public static <K> Map<K, Double> allocate(
            Map<K, Double> deliverableDemands,
            Map<K, Double> weights,
            double totalBudget
    ) {
        Map<K, Double> out = new LinkedHashMap<>();
        if (deliverableDemands == null || deliverableDemands.isEmpty()) {
            return out;
        }
        double sumDemands = 0.0;
        for (Map.Entry<K, Double> e : deliverableDemands.entrySet()) {
            double d = sanitize(e.getValue());
            out.put(e.getKey(), 0.0);
            sumDemands += d;
        }
        if (Double.isNaN(totalBudget) || totalBudget <= 0.0 || sumDemands <= 0.0) {
            return out;
        }
        if (totalBudget >= sumDemands - EPSILON) {
            for (Map.Entry<K, Double> e : deliverableDemands.entrySet()) {
                out.put(e.getKey(), sanitize(e.getValue()));
            }
            return out;
        }

        List<K> keys = new ArrayList<>(deliverableDemands.keySet());
        boolean[] capped = new boolean[keys.size()];
        double remainingRate = totalBudget;

        while (remainingRate > EPSILON) {
            double activeWeightSum = 0.0;
            int activeCount = 0;
            for (int i = 0; i < keys.size(); i++) {
                if (capped[i]) continue;
                K k = keys.get(i);
                double d = sanitize(deliverableDemands.get(k));
                if (d <= 0.0) {
                    capped[i] = true;
                    continue;
                }
                double w = weights != null ? sanitize(weights.get(k)) : 0.0;
                activeWeightSum += w;
                activeCount++;
            }
            if (activeCount == 0) {
                break;
            }

            if (activeWeightSum <= 0.0) {
                double share = remainingRate / activeCount;
                boolean anyCappedThisRound = false;
                for (int i = 0; i < keys.size(); i++) {
                    if (capped[i]) continue;
                    K k = keys.get(i);
                    double d = sanitize(deliverableDemands.get(k));
                    if (d <= share + EPSILON) {
                        out.put(k, d);
                        remainingRate -= d;
                        capped[i] = true;
                        anyCappedThisRound = true;
                    }
                }
                if (!anyCappedThisRound) {
                    for (int i = 0; i < keys.size(); i++) {
                        if (!capped[i]) {
                            out.put(keys.get(i), share);
                        }
                    }
                    break;
                }
                continue;
            }

            double theta = remainingRate / activeWeightSum;
            boolean anyCappedThisRound = false;
            for (int i = 0; i < keys.size(); i++) {
                if (capped[i]) continue;
                K k = keys.get(i);
                double d = sanitize(deliverableDemands.get(k));
                double w = sanitize(weights.get(k));
                if (theta * w >= d - EPSILON) {
                    out.put(k, d);
                    remainingRate = Math.max(0.0, remainingRate - d);
                    capped[i] = true;
                    anyCappedThisRound = true;
                }
            }
            if (!anyCappedThisRound) {
                for (int i = 0; i < keys.size(); i++) {
                    if (capped[i]) continue;
                    K k = keys.get(i);
                    double d = sanitize(deliverableDemands.get(k));
                    double w = sanitize(weights.get(k));
                    out.put(k, Math.min(d, theta * w));
                }
                break;
            }
        }
        return out;
    }

    /**
     * Normalizes positive planned rates into shares in {@code [0.0, 1.0]} summing to {@code 1.0}
     * (or {@code 0.0} when all inputs are non-positive).
     *
     * @param plannedRates per-key planned rates
     * @param <K>          key type
     * @return unmodifiable {@link LinkedHashMap} of normalized shares
     */
    public static <K> Map<K, Double> normalizeShares(Map<K, Double> plannedRates) {
        if (plannedRates == null || plannedRates.isEmpty()) {
            return Collections.emptyMap();
        }
        double sum = 0.0;
        for (Double v : plannedRates.values()) {
            double s = sanitize(v);
            sum += s;
        }
        Map<K, Double> out = new LinkedHashMap<>();
        for (Map.Entry<K, Double> e : plannedRates.entrySet()) {
            double v = sanitize(e.getValue());
            out.put(e.getKey(), sum > 0.0 ? (v / sum) : 0.0);
        }
        return Collections.unmodifiableMap(out);
    }

    private static double sanitize(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }
}
