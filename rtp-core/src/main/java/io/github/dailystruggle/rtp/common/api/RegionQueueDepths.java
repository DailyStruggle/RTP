package io.github.dailystruggle.rtp.common.api;

/**
 * Immutable, read-only snapshot of a single region's pre-verified-location
 * cache/buffer depths at the instant {@link RtpCoreIntrospection} sampled it.
 *
 * <p>This is part of the <b>unstable</b> {@code rtp-core} introspection surface
 * described in {@code docs/dev/scratch/PROPOSAL-gui-author-spi.md} §3.2. It is
 * intended for third-party GUI/dashboard authors who want "X locations ready"
 * style tiles. Unlike the {@code rtp-api} surface it is <em>not</em> covered by
 * any semver guarantee: field set and shape may change between minor versions.
 *
 * <p>All counts are best-effort instantaneous reads of lock-free buffers; they
 * are not a transactionally consistent cross-buffer total. A {@code 0} for an
 * optional buffer ({@link #backlog()}, {@link #login()}, {@link #networkKept()})
 * means either the buffer is empty or that buffer is not allocated for this
 * region (the two are deliberately not distinguished here).
 *
 * @param regionName       canonical region name; never {@code null}.
 * @param kept             "hot" L1 buffer depth (chunks loaded, ready to serve).
 * @param unkept           "cold" L2 buffer depth (verified, chunks released).
 * @param backlog          L3 unverified backlog depth ({@code 0} if disabled).
 * @param login            join-time login-reserve depth ({@code 0} if disabled).
 * @param networkKept      cross-server kept sibling-pool depth ({@code 0} if off).
 * @param networkReserved  in-flight cross-server pinned reservations.
 * @param waitlist         players currently enrolled on the teleport waitlist.
 * @param perPlayerQueues  number of open per-player coordinate buckets.
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
