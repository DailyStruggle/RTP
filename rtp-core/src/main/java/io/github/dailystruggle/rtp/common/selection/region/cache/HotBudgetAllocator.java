package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamic hot cache budget allocator operating on the region compute pulse (ADR-078).
 *
 * <p>Centralizes quota computation and zero-I/O rebalancing across registered {@link HotSink}s:
 * <ul>
 *   <li><b>EWMA demand smoothing:</b> tracks recent usage per sink to derive proportional shares.</li>
 *   <li><b>Floor guarantees:</b> configured floors are satisfied before proportional shares are divided,
 *       bounded by {@code sum(floors) <= maxCachedChunks}.</li>
 *   <li><b>Zero-I/O direct transfer:</b> sinks below their floor or quota are topped up by direct
 *       transfer of eligible entries from siblings having surplus, without closing/re-opening chunk tickets (REQ-RTP-S-005).</li>
 *   <li><b>Quota adjustment as promotion gating:</b> no active chunk reservation is closed to satisfy
 *       a quota; quota changes act as gating on cold-to-hot promotions.</li>
 * </ul>
 */
public class HotBudgetAllocator {
    public static final double DEFAULT_EWMA_ALPHA = 0.3;

    private final double alpha;
    private final Map<String, Double> smoothedDemand = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rawDemandCounters = new ConcurrentHashMap<>();

    public HotBudgetAllocator() {
        this(DEFAULT_EWMA_ALPHA);
    }

    public HotBudgetAllocator(double alpha) {
        if (alpha <= 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be in (0, 1], got " + alpha);
        }
        this.alpha = alpha;
    }

    /**
     * Records a demand event (e.g. cache poll/hit) for a sink.
     *
     * @param sinkName the sink identity
     * @param count    the number of requests observed
     */
    public void recordDemand(String sinkName, long count) {
        if (sinkName == null || count <= 0) return;
        rawDemandCounters.computeIfAbsent(sinkName, k -> new AtomicLong()).addAndGet(count);
    }

    /**
     * Updates the EWMA demand weights for all known sinks using the given sink collection.
     *
     * @param sinks the collection of hot sinks
     * @return map of sink name to smoothed demand weight
     */
    public <T> Map<String, Double> updateDemandWeights(Collection<HotSink<T>> sinks) {
        Map<String, Double> weights = new HashMap<>();
        if (sinks == null) return weights;

        for (HotSink<T> sink : sinks) {
            if (sink == null) continue;
            String name = sink.name();
            AtomicLong counter = rawDemandCounters.get(name);
            long observed = counter != null ? counter.getAndSet(0L) : 0L;
            if (observed == 0L && sink.demandWeight() > 0) {
                observed = sink.demandWeight();
            }

            Double current = smoothedDemand.get(name);
            double updated;
            if (current == null) {
                updated = (double) observed;
            } else {
                updated = (alpha * observed) + ((1.0 - alpha) * current);
            }
            smoothedDemand.put(name, updated);
            weights.put(name, updated);
        }
        return weights;
    }

    /**
     * Returns the current smoothed demand weight for a sink.
     *
     * @param sinkName the sink identity
     * @return the EWMA smoothed demand, or 0.0 if unknown
     */
    public double getSmoothedDemand(String sinkName) {
        return smoothedDemand.getOrDefault(sinkName, 0.0);
    }

    /**
     * Configuration options for a hot sink during budget allocation.
     *
     * @param floor      minimum guaranteed entries (or slots) for this sink
     * @param maxCap     maximum ceiling cap for this sink
     */
    public record SinkConfig(int floor, int maxCap) {
        public SinkConfig {
            floor = Math.max(0, floor);
            maxCap = Math.max(floor, maxCap);
        }
    }

    /**
     * Computes target quotas (in entries) for each sink given a global maximum cached chunks budget.
     *
     * <p>Floors are honored first. Any remaining chunk budget is divided among sinks proportional
     * to {@code demandWeight / chunkCostPerEntry}. Resulting targets are clamped between floor
     * and maxCap.
     *
     * @param sinks             the collection of hot sinks
     * @param configs           sink-specific configurations (floor and maxCap)
     * @param maxCachedChunks   total resident chunk ceiling for the region
     * @return map of sink name to target capacity (entry count)
     */
    public <T> Map<String, Integer> computeQuotas(Collection<HotSink<T>> sinks,
                                                  Map<String, SinkConfig> configs,
                                                  int maxCachedChunks) {
        Map<String, Integer> targets = new LinkedHashMap<>();
        if (sinks == null || sinks.isEmpty() || maxCachedChunks <= 0) {
            if (sinks != null) {
                for (HotSink<T> sink : sinks) {
                    if (sink != null) targets.put(sink.name(), 0);
                }
            }
            return targets;
        }

        // 1. Gather floor requirements and footprint costs
        int totalFloorChunks = 0;
        Map<String, Integer> floors = new LinkedHashMap<>();
        Map<String, Integer> caps = new LinkedHashMap<>();
        Map<String, Integer> costs = new LinkedHashMap<>();
        Map<String, Double> normalizedDemands = new LinkedHashMap<>();

        for (HotSink<T> sink : sinks) {
            if (sink == null) continue;
            String name = sink.name();
            SinkConfig config = configs != null ? configs.get(name) : null;
            int floor = config != null ? config.floor() : 0;
            int maxCap = config != null ? config.maxCap() : Integer.MAX_VALUE;
            int cost = Math.max(1, sink.chunkCostPerEntry());

            floors.put(name, floor);
            caps.put(name, maxCap);
            costs.put(name, cost);
            totalFloorChunks += floor * cost;

            double demand = smoothedDemand.getOrDefault(name, (double) sink.demandWeight());
            normalizedDemands.put(name, demand / cost);
        }

        // 2. Bound floors: sum(floors * cost) <= maxCachedChunks
        double floorScale = 1.0;
        if (totalFloorChunks > maxCachedChunks && totalFloorChunks > 0) {
            floorScale = (double) maxCachedChunks / (double) totalFloorChunks;
        }

        int remainingChunks = maxCachedChunks;
        for (HotSink<T> sink : sinks) {
            if (sink == null) continue;
            String name = sink.name();
            int floor = floors.get(name);
            int effectiveFloor = (floorScale < 1.0) ? (int) Math.floor(floor * floorScale) : floor;
            int cap = caps.get(name);
            int target = Math.min(effectiveFloor, cap);
            targets.put(name, target);
            remainingChunks -= target * costs.get(name);
        }
        remainingChunks = Math.max(0, remainingChunks);

        // 3. Distribute remaining chunks proportionally to normalized demand
        double totalDemand = 0.0;
        for (double d : normalizedDemands.values()) {
            totalDemand += Math.max(0.0, d);
        }

        if (remainingChunks > 0) {
            if (totalDemand > 0.0) {
                // Proportional distribution
                for (HotSink<T> sink : sinks) {
                    if (sink == null) continue;
                    String name = sink.name();
                    double weight = Math.max(0.0, normalizedDemands.get(name));
                    int cost = costs.get(name);
                    int currentTarget = targets.get(name);
                    int cap = caps.get(name);

                    int additionalEntries = (int) Math.floor((remainingChunks * (weight / totalDemand)) / cost);
                    int newTarget = Math.min(cap, currentTarget + additionalEntries);
                    targets.put(name, newTarget);
                }
            } else {
                // If total demand is zero, distribute evenly across sinks respecting caps
                int eligibleSinkCount = sinks.size();
                for (HotSink<T> sink : sinks) {
                    if (sink == null) continue;
                    String name = sink.name();
                    int cost = costs.get(name);
                    int currentTarget = targets.get(name);
                    int cap = caps.get(name);

                    int additionalEntries = (remainingChunks / eligibleSinkCount) / cost;
                    int newTarget = Math.min(cap, currentTarget + additionalEntries);
                    targets.put(name, newTarget);
                }
            }
        }

        return targets;
    }

    /**
     * Executes zero-I/O rebalancing across registered sinks.
     *
     * <p>For each sink that is currently below its target (or floor), searches for sibling sinks
     * that currently hold surplus entries (occupancy > target or floor) and are transfer-eligible.
     * When found, transfers entries directly using {@code pollSilently()} and {@code offerSilently()}
     * without chunk loading or closing reservations (REQ-RTP-S-005, ADR-078).
     *
     * @param sinks   the collection of hot sinks
     * @param targets target entry counts per sink
     * @return total count of entries successfully transferred
     */
    public <T> int rebalance(Collection<HotSink<T>> sinks, Map<String, Integer> targets) {
        if (sinks == null || sinks.isEmpty() || targets == null) return 0;

        int totalTransfers = 0;
        List<HotSink<T>> sinkList = new ArrayList<>(sinks);

        // Identify deficit sinks (size < target) and surplus sinks (size > target)
        for (HotSink<T> recipient : sinkList) {
            if (recipient == null) continue;
            String recName = recipient.name();
            int recTarget = targets.getOrDefault(recName, 0);
            int deficit = recTarget - recipient.stage().size();
            if (deficit <= 0) continue;

            for (HotSink<T> donor : sinkList) {
                if (donor == null || donor == recipient) continue;
                String donorName = donor.name();
                int donorTarget = targets.getOrDefault(donorName, 0);
                int surplus = donor.stage().size() - donorTarget;
                if (surplus <= 0) continue;

                // Attempt to transfer up to min(deficit, surplus)
                int canTake = Math.min(deficit, surplus);
                for (int i = 0; i < canTake; i++) {
                    Optional<T> candidate = donor.stage().pollSilently();
                    if (candidate.isEmpty()) break;

                    T entry = candidate.get();
                    if (HotSink.transferEligible(donor, recipient, entry)) {
                        boolean offered = recipient.stage().offerSilently(entry);
                        if (offered) {
                            totalTransfers++;
                            deficit--;
                            surplus--;
                        } else {
                            // Destination full or overflowed (offerSilently disposes on overflow)
                            break;
                        }
                    } else {
                        // Ineligible: return entry to donor stage silently
                        donor.stage().offerSilently(entry);
                        break; // Stop trying this donor for this recipient
                    }
                    if (deficit <= 0) break;
                }
                if (deficit <= 0) break;
            }
        }

        return totalTransfers;
    }
}
