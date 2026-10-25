package io.github.dailystruggle.rtp.common.governor;

import io.github.dailystruggle.rtp.common.predict.WaterFillingAllocator;
import io.github.dailystruggle.rtp.common.predict.WorkAttributionLedger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory consumer attribution ledger tracking distinct active players per RTP region ({@code n_r})
 * and per-player dual-EWMA attributed compute cost ({@code a_p}) (ADR-124 Section 1).
 *
 * <p>Holds zero chunk tickets, acquires zero locks, and creates zero threads (F-001, ADR-087 Invariant I8).
 * Bounded to {@value #MAX_PLAYER_ENTRIES} UUID entries with least-recently-active eviction and
 * 30-minute idle expiry.
 *
 * @governedBy ADR-124 (Consumer Attribution for Constrained Background Refill)
 * @trace REQ-RTP-OPS-001
 * @trace REQ-RTP-OPS-002
 */
public final class ConsumerLedger {

    public static final int MAX_PLAYER_ENTRIES = WorkAttributionLedger.DEFAULT_MAX_ACTOR_ENTRIES;
    public static final int MAX_REGION_CONSUMERS = WorkAttributionLedger.DEFAULT_MAX_SCOPE_CONSUMERS;
    public static final long REGION_WINDOW_NANOS = WorkAttributionLedger.DEFAULT_SCOPE_WINDOW_NANOS;
    public static final long TEN_MINUTES_NANOS = REGION_WINDOW_NANOS;
    public static final long IDLE_EXPIRY_NANOS = WorkAttributionLedger.DEFAULT_IDLE_EXPIRY_NANOS;
    public static final long THIRTY_MINUTES_NANOS = IDLE_EXPIRY_NANOS;
    public static final long SHORT_HALF_LIFE_NANOS = WorkAttributionLedger.DEFAULT_SHORT_HALF_LIFE_NANOS;
    public static final long LONG_HALF_LIFE_NANOS = WorkAttributionLedger.DEFAULT_LONG_HALF_LIFE_NANOS;

    /**
     * Immutable telemetry row for a single player's attributed RTP cost in milliseconds per minute
     * (ADR-124 Section 1 & 5).
     *
     * @param playerId        player UUID
     * @param costMsPerMin    mean of 30 s and 10 min EWMAs ({@code a_p = 0.5 * (a_p^30s + a_p^10m)})
     * @param ewma30sMsPerMin 30-second half-life EWMA in ms/min
     * @param ewma10mMsPerMin 10-minute half-life EWMA in ms/min
     */
    public record ConsumerCostEntry(
            UUID playerId,
            double costMsPerMin,
            double ewma30sMsPerMin,
            double ewma10mMsPerMin
    ) {}

    private final WorkAttributionLedger<UUID> delegate = new WorkAttributionLedger<>(
            MAX_PLAYER_ENTRIES,
            MAX_REGION_CONSUMERS,
            REGION_WINDOW_NANOS,
            IDLE_EXPIRY_NANOS,
            SHORT_HALF_LIFE_NANOS,
            LONG_HALF_LIFE_NANOS,
            Comparator.comparing(UUID::toString)
    );

    /** Returns the active governor's {@link ConsumerLedger}. */
    public static ConsumerLedger active() {
        return ComputeGovernor.active().consumerLedger();
    }

    /**
     * Records that {@code playerId} consumed a cached location in {@code region} at {@code nowNanos}
     * and charges one measured unit cost ({@code DeltaT_async + DeltaT_tick}) to {@code playerId}
     * (ADR-124 Section 1).
     *
     * <p>Pregeneration or non-player consumers ({@code playerId == null}) are ignored.
     *
     * @param region        RTP region name
     * @param playerId      consuming player's UUID (nullable; ignored when null)
     * @param unitCostNanos measured unit refill cost in nanoseconds
     * @param nowNanos      monotonic timestamp in nanoseconds
     */
    public void recordConsumption(String region, UUID playerId, double unitCostNanos, long nowNanos) {
        delegate.recordConsumption(region, playerId, unitCostNanos, nowNanos);
    }

    /**
     * Returns the number of distinct active players {@code n_r} who consumed locations in {@code region}
     * within the last 10 minutes, floored at {@code 1} (ADR-124 Section 1).
     *
     * @param region   RTP region name
     * @param nowNanos monotonic timestamp in nanoseconds
     * @return distinct active consumers in {@code [1, 4096]}
     */
    public int distinctConsumers(String region, long nowNanos) {
        return delegate.distinctConsumers(region, nowNanos);
    }

    /**
     * Charges {@code costNanos} of RTP work (async selection, sync stages, interactive chunk loads,
     * entity-scheduler continuations, or cached unit consumption) to {@code playerId} at {@code nowNanos}
     * (ADR-124 Section 1).
     *
     * @param playerId  player UUID (ignored when null)
     * @param costNanos work duration in nanoseconds
     * @param nowNanos  monotonic timestamp in nanoseconds
     */
    public void recordPlayerCharge(UUID playerId, long costNanos, long nowNanos) {
        delegate.recordCharge(playerId, costNanos, nowNanos);
    }

    /**
     * Returns the current decayed {@link ConsumerCostEntry} for {@code playerId} at {@code nowNanos},
     * or {@code null} if the player is not present or has expired after 30 minutes idle.
     */
    public ConsumerCostEntry playerEntry(UUID playerId, long nowNanos) {
        WorkAttributionLedger.AttributionEntry<UUID> entry = delegate.entry(playerId, nowNanos);
        return (entry != null) ? toConsumerCostEntry(entry) : null;
    }

    /**
     * Returns the attributed cost {@code a_p = 0.5 * (a_p^30s + a_p^10m)} in ms/min for {@code playerId}
     * at {@code nowNanos}, or {@code 0.0} if absent/expired.
     */
    public double attributedCostMsPerMin(UUID playerId, long nowNanos) {
        return delegate.attributedCostMsPerMin(playerId, nowNanos);
    }

    /**
     * Returns up to {@code limit} active consumers ordered by descending attributed cost {@code a_p}
     * at {@code nowNanos} (ADR-124 Section 1 & 5).
     */
    public List<ConsumerCostEntry> topConsumers(int limit, long nowNanos) {
        return delegate.topActors(limit, nowNanos, ConsumerLedger::toConsumerCostEntry);
    }

    /**
     * Returns the 99th-percentile attributed cost {@code a_p} (ms/min) across active players at {@code nowNanos}
     * for {@code rtp_governor_consumer_cost_p99_ms} (ADR-124 Section 5).
     */
    public double costP99MsPerMin(long nowNanos) {
        return delegate.costP99MsPerMin(nowNanos);
    }

    /**
     * Evicts expired player entries and returns the current number of active player entries (bounded to 4096).
     */
    public int activePlayerCount(long nowNanos) {
        return delegate.activeActorCount(nowNanos);
    }

    /** Returns the raw map size of tracked player entries. */
    public int size() {
        return delegate.size();
    }

    /**
     * Clears all region and player attribution state (called on governor reset/reload, ADR-124 Section 1 & 6).
     */
    public void reset() {
        delegate.reset();
    }

    /**
     * Computes normalized per-region refill shares in {@code [0.0, 1.0]} from planned rates
     * for {@code rtp_governor_region_refill_share} (ADR-124 Section 5).
     */
    public static Map<String, Double> computeRegionRefillShares(Map<String, Double> plannedRates) {
        return WaterFillingAllocator.normalizeShares(plannedRates);
    }

    private static ConsumerCostEntry toConsumerCostEntry(WorkAttributionLedger.AttributionEntry<UUID> entry) {
        return new ConsumerCostEntry(
                entry.actorId(),
                entry.costMsPerMin(),
                entry.shortEwmaMsPerMin(),
                entry.longEwmaMsPerMin()
        );
    }
}
