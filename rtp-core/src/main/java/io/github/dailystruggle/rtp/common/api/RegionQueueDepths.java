package io.github.dailystruggle.rtp.common.api;

/**
 * Read-only snapshot of a region's cache and queue depths sampled via {@link RtpCoreIntrospection}.
 *
 * @param regionName       canonical region name
 * @param kept             hot L1 buffer depth (chunks loaded)
 * @param unkept           cold L2 buffer depth (verified, chunks released)
 * @param backlog          L3 unverified backlog depth
 * @param login            join-time login-reserve depth
 * @param networkKept      cross-server kept sibling-pool depth
 * @param networkReserved  in-flight cross-server pinned reservations
 * @param waitlist         players currently enrolled on teleport waitlist
 * @param perPlayerQueues  number of open personal coordinate buckets
 */
public record RegionQueueDepths(
        String regionName,
        int kept,
        int unkept,
        int backlog,
        int login,
        int networkKept,
        int networkReserved,
        int waitlist,
        int perPlayerQueues) {

    /**
     * @return the combined "ready to serve without further work" depth,
     * {@code kept + unkept}. Mirrors the {@code rtp-api}
     * {@code RTPAPI.queueDepth(world)} semantics for a single region.
     */
    public int readyDepth() {
        return kept + unkept;
    }
}
