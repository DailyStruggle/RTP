package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cross-network reservation handle. One token represents one coordinate
 * allocation against a chosen backend; the {@link #state()} field follows the
 * <code>PENDING → CLAIMED → CONSUMED</code> machine (rtp-proxy-ADR-003
 * §Atomic claim primitive; ADR-036 §5).
 *
 * <p>Instances are thread-safe: {@link #state} is held in an
 * {@link AtomicReference} and transitions are compare-and-set under the
 * transport's row-count atomicity primitive (REQ-RTP-PROXY-004).</p>
 */
public final class ReservationToken {

    /** Reservation lifecycle states. */
    public enum State {
        /** Token allocated but not yet locked to a transferring player. */
        PENDING,
        /** Successfully transitioned to "this proxy owns this slot". */
        CLAIMED,
        /** Player has used the slot or it was released. Terminal. */
        CONSUMED,
        /** Released for any reason without consumption. Terminal. */
        RELEASED
    }

    private final String tokenId;
    private final String serverId;
    private final UUID playerId;
    private final long expiresEpochMs;
    private final AtomicReference<State> state;

    /**
     * Optional region constraint carried verbatim from the player's
     * {@code /rtp region=<server>:<region>} command (parsed by
     * {@code NetworkRouter.parseRegionArgQualified} into a {@code serverHint}
     * + {@code regionKey}). {@code null} when the request carried no region
     * constraint. The destination backend's {@code JoinTriggerSource}
     * re-attaches it as {@code rtp region=<regionKey>} on arrival so the
     * cross-server teleport lands in the requested region rather than the
     * backend default. See the multi-region note in
     * {@code JoinTriggerSource.findRegionForReservation}.
     */
    private final String regionKey;

    public ReservationToken(String tokenId, String serverId, UUID playerId,
                            long expiresEpochMs, State initial) {
        this(tokenId, serverId, playerId, expiresEpochMs, initial, null);
    }

    public ReservationToken(String tokenId, String serverId, UUID playerId,
                            long expiresEpochMs, State initial, String regionKey) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.expiresEpochMs = expiresEpochMs;
        this.state = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.regionKey = (regionKey == null || regionKey.isEmpty()) ? null : regionKey;
    }

    public String tokenId()            { return tokenId; }
    public String serverId()           { return serverId; }
    public UUID playerId()             { return playerId; }
    public long expiresEpochMs()       { return expiresEpochMs; }
    public State state()               { return state.get(); }

    /** Optional requested region key; empty when the request was regionless. */
    public Optional<String> regionKey() { return Optional.ofNullable(regionKey); }

    /**
     * Atomic state transition. Returns {@code true} only when the witnessed
     * pre-state matched {@code expected}. This is the row-count atomicity
     * primitive REQ-RTP-PROXY-004 mandates.
     */
    public boolean transition(State expected, State next) {
        return state.compareAndSet(expected, next);
    }

    @Override
    public String toString() {
        return "ReservationToken[" + tokenId + " " + serverId + " " + playerId
                + " " + state.get() + " exp=" + expiresEpochMs
                + (regionKey == null ? "" : " region=" + regionKey) + "]";
    }
}
