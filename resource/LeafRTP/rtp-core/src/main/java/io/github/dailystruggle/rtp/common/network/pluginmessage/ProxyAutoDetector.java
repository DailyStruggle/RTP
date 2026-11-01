package io.github.dailystruggle.rtp.common.network.pluginmessage;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxy auto-detection state machine for {@code transport.type: auto} (UNKNOWN -> ARMED -> PROBING -> ENABLED/DISABLED).
 * Drives passive configuration checks and active plugin-message handshake.
 */
public final class ProxyAutoDetector {

    private static final Logger LOG = Logger.getLogger(ProxyAutoDetector.class.getName());

    /** Default window to wait for a {@code GetServer}/{@code GetServers} reply. */
    public static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 1_500L;

    /** Auto-detection lifecycle states. */
    public enum State {
        /** Initial state, before any passive probe. */
        UNKNOWN,
        /** Passive config probe indicates a proxy; awaiting a carrier player. */
        ARMED,
        /** Active handshake sent; awaiting a topology reply. */
        PROBING,
        /** Proxy confirmed; heartbeat gossip is legitimate. */
        ENABLED,
        /** Standalone (or handshake timed out); re-probeable on reconnect. */
        DISABLED
    }

    private final NetworkBridge bridge;
    private final long handshakeTimeoutMillis;
    private final LongSupplier clock;

    private final AtomicReference<State> state = new AtomicReference<>(State.UNKNOWN);
    private volatile long probeStartedAtMs = 0L;
    private volatile String ownServerId = null;
    private volatile Set<String> peerServerIds = Set.of();

    public ProxyAutoDetector(NetworkBridge bridge) {
        this(bridge, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    public ProxyAutoDetector(NetworkBridge bridge, long handshakeTimeoutMillis, LongSupplier clock) {
        this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
        this.handshakeTimeoutMillis =
                handshakeTimeoutMillis > 0 ? handshakeTimeoutMillis : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        bridge.registerTopology(this::onTopology);
    }

    public State state() {
        return state.get();
    }

    public boolean isEnabled() {
        return state.get() == State.ENABLED;
    }

    /** This backend's own proxy name once the handshake has completed. */
    public Optional<String> ownServerId() {
        return Optional.ofNullable(ownServerId);
    }

    /** Peer backend names discovered via {@code GetServers} (empty until ENABLED). */
    public Set<String> peerServerIds() {
        return peerServerIds;
    }

    /**
     * Cheap, traffic-free passive evaluation. Moves {@code UNKNOWN} to
     * {@code ARMED} when the platform's proxy-forwarding config is on, or to
     * {@code DISABLED} (standalone) otherwise. A no-op once probing/enabled so
     * a passive re-read does not knock down a confirmed proxy.
     */
    public void evaluatePassive() {
        State s = state.get();
        if (s == State.PROBING || state.get() == State.ENABLED) {
            return;
        }
        if (!bridge.isAvailable()) {
            state.set(State.DISABLED);
            return;
        }
        NetworkBridge.ProxyProbe probe;
        try {
            probe = bridge.passiveProbe();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[RTP] passive proxy probe failed: " + t.getMessage());
            probe = NetworkBridge.ProxyProbe.DISARMED;
        }
        state.set(probe != null && probe.armed() ? State.ARMED : State.DISABLED);
    }

    /**
     * Signal that a carrier player is now available (first join, or a reconnect
     * to a proxy). Begins the active handshake from {@code ARMED} <em>or</em>
     * {@code DISABLED} (the re-probe path), provided the bridge is available.
     * A no-op while already {@code PROBING} or {@code ENABLED}.
     */
    public void onCarrierAvailable() {
        State s = state.get();
        if (s == State.ENABLED || s == State.PROBING) {
            return;
        }
        if (!bridge.isAvailable()) {
            state.set(State.DISABLED);
            return;
        }
        // Re-probe path: a previously-standalone server may now be proxied.
        if (s == State.UNKNOWN) {
            evaluatePassive();
            if (state.get() == State.DISABLED) {
                // Passive probe disarmed; still attempt the active handshake
                // since the passive read is only a hint (proxy may forward
                // without the local config flag). Fall through to PROBING.
                state.set(State.ARMED);
            }
        }
        probeStartedAtMs = clock.getAsLong();
        state.set(State.PROBING);
        try {
            bridge.requestTopology();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[RTP] topology handshake request skipped: " + t.getMessage());
        }
    }

    /**
     * Pump from a scheduled task. Fails an over-due handshake to
     * {@code DISABLED} (standalone) so {@code auto} stops waiting; the next
     * {@link #onCarrierAvailable()} re-probes.
     */
    public void tick() {
        if (state.get() != State.PROBING) {
            return;
        }
        if (clock.getAsLong() - probeStartedAtMs > handshakeTimeoutMillis) {
            state.compareAndSet(State.PROBING, State.DISABLED);
        }
    }

    /** Inbound topology reply sink (registered with the bridge). */
    private void onTopology(NetworkBridge.Topology topology) {
        if (topology == null) {
            return;
        }
        // Accept a late reply only while we are still probing.
        if (state.get() != State.PROBING) {
            return;
        }
        this.ownServerId = topology.ownServerId();
        Set<String> peers = topology.peerServerIds();
        this.peerServerIds = peers == null ? Set.of() : Collections.unmodifiableSet(peers);
        state.set(State.ENABLED);
    }
}
