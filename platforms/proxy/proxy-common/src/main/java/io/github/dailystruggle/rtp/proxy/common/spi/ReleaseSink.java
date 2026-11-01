package io.github.dailystruggle.rtp.proxy.common.spi;

/**
 * In-process callback fired by
 * {@link io.github.dailystruggle.rtp.proxy.common.transport.ReservationTokenReaper}
 * (and other release-emitting components, future) for every reservation token
 * that transitions to a released terminal state.
 *
 * <p>Intent. A backend that owns the original coordinate (now pinned in
 * {@code RegionQueueManager.networkReservedLocations}) needs to learn when a
 * token TTL-expires (or is otherwise released) so it can call
 * {@code releaseToNetworkKept(networkTokenId)} and return the coordinate to
 * its {@code networkKeptLocations} buffer (REQ-RTP-NET-011).</p>
 *
 * <p>Threading. Fired on the originating component's executor (for the
 * reaper, that is the transport's executor - never the scheduler thread).
 * Implementations must not block; if dispatching to a regional / Folia
 * scheduler is needed, post-and-return.</p>
 *
 * <p>S-004. The reaper catches any {@link RuntimeException} thrown by a sink
 * and logs at {@code WARNING}; a faulty sink does not mask the release event
 * or kill the periodic sweep. Sinks should still surface their own failures
 * via their host's logging channel rather than swallowing silently.</p>
 *
 * <p>Compatibility. The release plumbing pre-dates this hook; callers that
 * do not need an in-process notification pass {@link #NO_OP}.</p>
 *
 */
@FunctionalInterface
public interface ReleaseSink {

    /**
     * Invoked once per released token.
     *
     * @param tokenId opaque token id as issued by
     *                {@link NetworkTransport#claim} (never null)
     * @param reason  why the token was released (never null)
     */
    void onRelease(String tokenId, ReleaseReason reason);

    /** No-op sink; used when a caller does not need in-process notifications. */
    ReleaseSink NO_OP = (tokenId, reason) -> { };
}
