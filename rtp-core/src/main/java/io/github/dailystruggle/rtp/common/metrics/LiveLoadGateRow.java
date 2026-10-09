package io.github.dailystruggle.rtp.common.metrics;

/**
 * Immutable per-region view of the purpose gate on native chunk loads (ADR-110).
 *
 * @param region             region name
 * @param readResolved       speculative candidates served from a resident chunk or the region file
 * @param deferredCenter     speculative candidates deferred: center chunk needed a native load and no pin slot
 * @param deferredNeighbour  speculative candidates deferred: a safety neighbour needed a native load
 * @param pinnedLoads        speculative candidates escalated to a pinned native load
 * @param pinnedKept         pinned results accepted into the kept (pinned) queue
 * @param pinnedOverflow     pinned results the kept queue refused (reservation closed, stored unpinned)
 * @param ringsSkipped       success rings not preloaded because the result was not pinned
 * @param pinsInFlight       pin slots currently held by in-flight attempts
 * @param usedResident       consumed locations whose chunk was still resident in RTP's cache
 * @param usedEvicted        consumed locations whose chunk had been evicted before use
 * @param usedPinnedResident consumed pinned locations still resident
 * @param usedPinnedEvicted  consumed pinned locations evicted despite the pin
 */
public record LiveLoadGateRow(
        String region,
        long readResolved,
        long deferredCenter,
        long deferredNeighbour,
        long pinnedLoads,
        long pinnedKept,
        long pinnedOverflow,
        long ringsSkipped,
        int pinsInFlight,
        long usedResident,
        long usedEvicted,
        long usedPinnedResident,
        long usedPinnedEvicted) {
}
