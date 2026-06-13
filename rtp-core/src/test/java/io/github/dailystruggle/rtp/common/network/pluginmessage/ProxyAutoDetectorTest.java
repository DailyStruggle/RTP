package io.github.dailystruggle.rtp.common.network.pluginmessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the {@code transport.type: auto} state machine
 * ({@link ProxyAutoDetector}): passive arm, active handshake, timeout to
 * standalone, and re-probe on reconnect. Driven by a manual clock and a
 * loopback {@link NetworkBridge} double so no live server is needed.
 */
class ProxyAutoDetectorTest {

    /** Controllable bridge double recording handshake requests and firing topology. */
    private static final class FakeBridge implements NetworkBridge {
        boolean available = true;
        boolean armed = true;
        final AtomicInteger topologyRequests = new AtomicInteger();
        private Consumer<Topology> topologySink = t -> { };

        @Override public boolean isAvailable() { return available; }
        @Override public Optional<UUID> anyOnlinePlayer() { return Optional.of(UUID.randomUUID()); }
        @Override public void broadcastHeartbeat(byte[] payload) { }
        @Override public void connect(UUID player, String targetServerId) { }
        @Override public void registerInbound(Consumer<byte[]> heartbeatSink) { }
        @Override public ProxyProbe passiveProbe() { return armed ? ProxyProbe.ARMED : ProxyProbe.DISARMED; }
        @Override public void requestTopology() { topologyRequests.incrementAndGet(); }
        @Override public void registerTopology(Consumer<Topology> sink) { this.topologySink = sink; }

        void fireTopology(String own, Set<String> peers) {
            topologySink.accept(new Topology(own, peers));
        }
    }

    private final AtomicLong now = new AtomicLong(0L);

    private ProxyAutoDetector detector(FakeBridge bridge, long timeout) {
        return new ProxyAutoDetector(bridge, timeout, now::get);
    }

    @Test
    @DisplayName("passive probe armed -> ARMED; first carrier -> PROBING; reply -> ENABLED")
    void happyPathToEnabled() {
        FakeBridge bridge = new FakeBridge();
        ProxyAutoDetector d = detector(bridge, 1_500L);

        assertEquals(ProxyAutoDetector.State.UNKNOWN, d.state());
        d.evaluatePassive();
        assertEquals(ProxyAutoDetector.State.ARMED, d.state());

        d.onCarrierAvailable();
        assertEquals(ProxyAutoDetector.State.PROBING, d.state());
        assertEquals(1, bridge.topologyRequests.get());

        bridge.fireTopology("backend-a", Set.of("backend-a", "backend-b"));
        assertTrue(d.isEnabled());
        assertEquals(Optional.of("backend-a"), d.ownServerId());
        assertTrue(d.peerServerIds().contains("backend-b"));
    }

    @Test
    @DisplayName("no topology reply within timeout -> DISABLED (standalone)")
    void handshakeTimeoutDisables() {
        FakeBridge bridge = new FakeBridge();
        ProxyAutoDetector d = detector(bridge, 1_500L);
        d.evaluatePassive();
        d.onCarrierAvailable();
        assertEquals(ProxyAutoDetector.State.PROBING, d.state());

        now.set(1_400L);
        d.tick();
        assertEquals(ProxyAutoDetector.State.PROBING, d.state(), "still within window");

        now.set(1_600L);
        d.tick();
        assertEquals(ProxyAutoDetector.State.DISABLED, d.state());
        assertFalse(d.isEnabled());
    }

    @Test
    @DisplayName("DISABLED re-probes on a later carrier-available event (reconnect)")
    void reprobeFromDisabled() {
        FakeBridge bridge = new FakeBridge();
        ProxyAutoDetector d = detector(bridge, 1_000L);
        d.evaluatePassive();
        d.onCarrierAvailable();
        now.set(2_000L);
        d.tick();
        assertEquals(ProxyAutoDetector.State.DISABLED, d.state());

        // Later, a player joins again (server now attached to a proxy).
        d.onCarrierAvailable();
        assertEquals(ProxyAutoDetector.State.PROBING, d.state());
        assertEquals(2, bridge.topologyRequests.get());

        bridge.fireTopology("backend-a", Set.of("backend-a"));
        assertTrue(d.isEnabled());
    }

    @Test
    @DisplayName("bridge unavailable -> DISABLED and no handshake sent")
    void unavailableBridgeStaysDisabled() {
        FakeBridge bridge = new FakeBridge();
        bridge.available = false;
        ProxyAutoDetector d = detector(bridge, 1_000L);

        d.evaluatePassive();
        assertEquals(ProxyAutoDetector.State.DISABLED, d.state());
        d.onCarrierAvailable();
        assertEquals(ProxyAutoDetector.State.DISABLED, d.state());
        assertEquals(0, bridge.topologyRequests.get());
    }

    @Test
    @DisplayName("late topology reply after timeout is ignored (stays DISABLED)")
    void lateReplyIgnored() {
        FakeBridge bridge = new FakeBridge();
        ProxyAutoDetector d = detector(bridge, 1_000L);
        d.evaluatePassive();
        d.onCarrierAvailable();
        now.set(2_000L);
        d.tick();
        assertEquals(ProxyAutoDetector.State.DISABLED, d.state());

        bridge.fireTopology("backend-a", Set.of("backend-a"));
        assertFalse(d.isEnabled(), "a stale reply must not re-enable a timed-out probe");
    }
}
