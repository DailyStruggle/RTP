package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.Set;

/**
 * One region as seen by the {@link MemoryTracker} chunk-ticket census and orphan sweep.
 *
 * <p>Owned by {@code tools} so the tracker does not depend on {@code selection.region}
 * (ADR-115, RTP-67); regions supply views through {@link MemoryTracker#setRegionSource}.
 * Every method reads live state, so a view may be queried again after an async hop.
 */
public interface TicketCensusRegion {

    /** Location cache budget: L1 cache cap plus active-chunk cap plus L3 backlog cap (ADR-028). */
    long cacheBudget();

    /** Cap on chunk tickets the region's cached locations may hold. */
    long activeChunkCap();

    /** Locations held in the kept, unkept and backlog queues. */
    long queuedLocations();

    /** Locations held in per-player queues. */
    long perPlayerLocations();

    /** Chunk tickets held by reservations in the kept and per-player queues. */
    long reservedChunkTickets();

    /**
     * {@code true} while generation or a kept promotion is in flight; such a region holds tickets
     * that are not yet in its queues, so the orphan sweep must skip it.
     */
    boolean generationInFlight();

    /** World the region selects in, or {@code null} when unbound. */
    RTPWorld<?> world();

    /**
     * Adds the chunk keys the region still needs: queued locations and destinations of pipelines
     * that target this region and have not completed.
     */
    void collectKeepAliveChunkKeys(Set<Long> out);
}
