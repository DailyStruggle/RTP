package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;
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

    public ReservationToken(String tokenId, String serverId, UUID playerId,
                            long expiresEpochMs, State initial) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.expiresEpochMs = expiresEpochMs;
        this.state = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public String tokenId()            { return tokenId; }
    public String serverId()           { return serverId; }
    public UUID playerId()             { return playerId; }
    public long expiresEpochMs()       { return expiresEpochMs; }
    public State state()               { return state.get(); }

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
                + " " + state.get() + " exp=" + expiresEpochMs + "]";
    }
}
