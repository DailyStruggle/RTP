package io.github.dailystruggle.rtp.common.selection.region.cache;

/**
 * A hot consumer pool branching off a shared cold inventory (ADR-078).
 *
 * <p>A hot sink's entries hold live {@code keep(true)} chunk reservations, so its
 * occupancy is resource-bounded and its teardown must close every reservation it holds
 * (REQ-RTP-S-002). This contract centralizes that lifecycle without merging the sinks:
 * the login reserve (ADR-023), network reservations (ADR-036), and personal buckets
 * (ADR-043) each remain a distinct sink with its own capacity.
 *
 * @param <T> hot entry type
 */
public interface HotSink<T> {
    /**
     * Returns the sink identity used for budgeting, metrics, and logs.
     *
     * @return a stable, non-null sink name.
     */
    String name();

    /**
     * Returns the sink's own bounded storage.
     *
     * @return the hot stage; never null.
     */
    CacheStage<T> stage();

    /**
     * Returns the upstream verified inventory this sink is filled from. Reference
     * identity is what establishes common provenance between sibling sinks.
     *
     * @return the cold stage; never null.
     */
    CacheStage<?> coldSource();

    /**
     * Would this sink accept the given entry?
     *
     * <p>A pure, in-memory recheck over an entry whose reservation already holds its
     * chunk resident: world identity by reference, shape and distance containment,
     * vertical bounds, then block and biome rules read from the resident chunk. It
     * shall load no chunk (REQ-RTP-S-005) and invoke no extrinsic verifier.
     *
     * <p>Fails closed: an entry whose reservation is null (a bare cold coordinate) or
     * already closed is rejected rather than inspected, and shall not be resolved by
     * loading the chunk.
     *
     * @param entry the candidate entry, holding a live reservation.
     * @return {@code true} only if the sink's own criteria were evaluated and passed.
     */
    boolean accepts(T entry);

    /**
     * Does acceptance depend on a check outside the server process or pinned to a
     * specific thread (claim-plugin lookups, REQ-RTP-S-003)?
     *
     * @return {@code true} if so, which excludes the sink from per-entry recheck.
     */
    boolean hasExtrinsicVerifier();

    /**
     * Are entries pinned to a specific holder - a proxy token (ADR-036) or a partition
     * key (ADR-043)?
     *
     * @return {@code true} if leased, which excludes the sink as transfer source and
     *         destination alike.
     */
    boolean isExternallyLeased();

    /**
     * Does this sink apply criteria its cold source does not already guarantee (for
     * example a default-world-only reserve branching off a wider cold stage)?
     *
     * @return {@code true} if it narrows further, which denies provenance certification.
     */
    boolean narrowsBeyondColdSource();

    /**
     * Returns the resident chunk footprint of a single entry.
     *
     * @return chunk cost per entry; 1 for single-target sinks.
     */
    int chunkCostPerEntry();

    /**
     * Returns the sink's smoothed demand, updated on the region compute pulse and used
     * by the budget allocator to weight its proportional share.
     *
     * @return a non-negative EWMA request counter.
     */
    long demandWeight();

    /**
     * Decides whether one hot entry may be handed directly from {@code from} to
     * {@code to} - an O(1) transfer of the entry and its active reservation, with zero
     * chunk load/unload cycles.
     *
     * <p>Rules 0 and 1 are preconditions on the sink pair; rule 2 certifies the
     * individual entry:
     * <ol start="0">
     *   <li><b>Preconditions.</b> Neither sink is externally leased, and the footprint
     *       relation {@code from.chunkCostPerEntry() >= to.chunkCostPerEntry()} holds -
     *       a verified subspace of capacity N may serve a request of k &lt;= N, never
     *       the reverse. Subsumption authorizes the size comparison only.</li>
     *   <li><b>Provenance certification.</b> Same cold source by reference and the
     *       destination narrows nothing further, so the entry is certified without
     *       further checks.</li>
     *   <li><b>Entry recheck.</b> The destination has no extrinsic verifier and its own
     *       acceptance check passes against the candidate entry.</li>
     * </ol>
     *
     * <p>Fail direction is one-way: a rejected entry is left in place, never
     * force-transferred. Sinks that fail all rules are balanced through their cold
     * promotion gate instead, and no active reservation is closed to satisfy a quota.
     *
     * @param from  the source sink.
     * @param to    the destination sink.
     * @param entry the candidate entry, currently held by {@code from}.
     * @param <T>   hot entry type
     * @return {@code true} if the transfer is eligible.
     */
    static <T> boolean transferEligible(HotSink<T> from, HotSink<T> to, T entry) {
        if (from == null || to == null || entry == null) return false;
        if (from == to) return false;
        if (from.isExternallyLeased() || to.isExternallyLeased()) return false;
        if (from.chunkCostPerEntry() < to.chunkCostPerEntry()) return false;
        if (from.coldSource() == to.coldSource() && !to.narrowsBeyondColdSource()) return true;
        return !to.hasExtrinsicVerifier() && to.accepts(entry);
    }
}
