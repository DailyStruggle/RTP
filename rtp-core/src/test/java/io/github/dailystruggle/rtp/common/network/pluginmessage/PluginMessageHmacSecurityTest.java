package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forged-heartbeat hardening for the plugin-message tiers: HMAC envelope
 * (sign / verify / fail-closed), peer-table cap, payload size caps and the
 * signed proxy-cache snapshot reply path.
 */
@DisplayName("REQ-RTP-PROXY-007 / REQ-RTP-S-004: plugin-message heartbeat authentication and bounds")
class PluginMessageHmacSecurityTest {

    private static final byte[] SECRET = filled((byte) 7);
    private static final byte[] OTHER_SECRET = filled((byte) 9);

    private static byte[] filled(byte b) {
        byte[] s = new byte[32];
        Arrays.fill(s, b);
        return s;
    }

    private static HmacVerifier verifier() {
        return HmacVerifier.forTesting(SECRET, 1, 1);
    }

    /** Bridge double that only captures the inbound sink and outbound payloads. */
    private static final class CapturingBridge implements NetworkBridge {
        Consumer<byte[]> sink;
        final List<byte[]> sent = new ArrayList<>();
        final Map<String, byte[]> proxyCache = new LinkedHashMap<>();

        @Override public boolean isAvailable() { return true; }
        @Override public Optional<UUID> anyOnlinePlayer() { return Optional.of(UUID.randomUUID()); }
        @Override public void broadcastHeartbeat(byte[] payload) { sent.add(payload); }
        @Override public void connect(UUID player, String targetServerId) { }
        @Override public void registerInbound(Consumer<byte[]> heartbeatSink) { this.sink = heartbeatSink; }
        @Override public ProxyProbe passiveProbe() { return ProxyProbe.ARMED; }
        @Override public void pushHeartbeatToProxy(byte[] payload) { sent.add(payload); }

        void inject(byte[] payload) {
            sink.accept(payload);
        }
    }

    private static BackendHeartbeat hb(String serverId, long now) {
        return new BackendHeartbeat(serverId, 1, PluginState.READY, true, now,
                10.0, 0, 100, 0L, 0L, 1,
                List.of("regionA"), List.of("world"), false);
    }

    private static byte[] legacy(BackendHeartbeat row) {
        return BackendHeartbeatCodec.encode(row).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: forged unsigned heartbeat (legacy v1 and unsigned v2) is rejected when a verifier is configured")
    void forgedUnsignedRejected() {
        CapturingBridge bus = new CapturingBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 5000L, () -> 0L, verifier());

        bus.inject(legacy(hb("forged-v1", 0L)));
        bus.inject(PluginMessageEnvelope.seal(hb("forged-v2", 0L), null));

        assertEquals(0, binding.trackedPeerCount(), "unsigned rows must never reach lastSeen");
        assertEquals(PluginMessageEnvelope.Rejection.UNSIGNED,
                PluginMessageEnvelope.open(legacy(hb("x", 0L)), verifier()).rejection());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: tampered signed heartbeat is rejected")
    void tamperedSignedRejected() {
        CapturingBridge bus = new CapturingBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 5000L, () -> 0L, verifier());

        String signed = new String(PluginMessageEnvelope.seal(hb("backend-a", 0L), verifier()), StandardCharsets.UTF_8);
        String tampered = signed.replace("regionsAvailable=regionA", "regionsAvailable=evil");
        assertFalse(signed.equals(tampered), "precondition: payload was actually altered");
        bus.inject(tampered.getBytes(StandardCharsets.UTF_8));
        // Signed with a different secret.
        bus.inject(PluginMessageEnvelope.seal(hb("backend-b", 0L), HmacVerifier.forTesting(OTHER_SECRET, 1, 1)));

        assertEquals(0, binding.trackedPeerCount());
        assertEquals(PluginMessageEnvelope.Rejection.BAD_SIGNATURE,
                PluginMessageEnvelope.open(tampered.getBytes(StandardCharsets.UTF_8), verifier()).rejection());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: valid signed heartbeat is accepted and outbound gossip is signed")
    void validSignedAccepted() throws Exception {
        CapturingBridge bus = new CapturingBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 5000L, () -> 0L, verifier());

        bus.inject(PluginMessageEnvelope.seal(hb("backend-a", 0L), verifier()));
        assertEquals(1, binding.livePeerCount());
        assertEquals(List.of("regionA"),
                binding.readSnapshot().get().backend("backend-a").orElseThrow().regionsAvailable());

        binding.publishBackendHeartbeat(hb("self", 0L)).get();
        assertEquals(1, bus.sent.size());
        String wire = new String(bus.sent.get(0), StandardCharsets.UTF_8);
        assertTrue(wire.startsWith("pmv=2\n"), "envelope version line first");
        assertTrue(wire.contains("\nhmac="), "outbound gossip carries an HMAC");
        assertTrue(PluginMessageEnvelope.open(bus.sent.get(0), verifier()).accepted());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-NET-002: without a verifier, legacy and v2 unsigned rows still interoperate")
    void unsignedModeInteroperates() {
        CapturingBridge bus = new CapturingBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 5000L, () -> 0L);
        bus.inject(legacy(hb("old-peer", 0L)));
        bus.inject(PluginMessageEnvelope.seal(hb("new-peer", 0L), verifier())); // signed is fine too
        assertEquals(2, binding.livePeerCount());
        // Unknown future envelope versions are rejected rather than mis-parsed.
        String v3 = new String(PluginMessageEnvelope.seal(hb("v3", 0L), null), StandardCharsets.UTF_8)
                .replace("pmv=2", "pmv=3");
        assertEquals(PluginMessageEnvelope.Rejection.UNSUPPORTED_VERSION,
                PluginMessageEnvelope.open(v3.getBytes(StandardCharsets.UTF_8), null).rejection());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-S-004: lastSeen is capped; new ids are refused when full, stale entries are evicted first")
    void lastSeenCapEnforced() {
        CapturingBridge bus = new CapturingBridge();
        AtomicLong clock = new AtomicLong(0L);
        HmacVerifier v = verifier();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 1000L, clock::get, v);
        int cap = AbstractPluginMessageNetworkBinding.MAX_TRACKED_SERVERS;
        for (int i = 0; i < cap; i++) {
            bus.inject(PluginMessageEnvelope.seal(hb("srv-" + i, 0L), v));
        }
        assertEquals(cap, binding.trackedPeerCount());

        bus.inject(PluginMessageEnvelope.seal(hb("one-too-many", 0L), v));
        assertEquals(cap, binding.trackedPeerCount(), "new id beyond cap refused");
        assertTrue(binding.lastSeen.get("one-too-many") == null);

        // Known ids still refresh at cap.
        clock.set(500L);
        bus.inject(PluginMessageEnvelope.seal(hb("srv-0", 500L), v));
        assertEquals(500L, binding.lastSeen.get("srv-0").seenAtMs());

        // Past stale window: stale rows evicted, newcomer admitted.
        clock.set(1400L);
        bus.inject(PluginMessageEnvelope.seal(hb("newcomer", 1400L), v));
        assertNotNull(binding.lastSeen.get("newcomer"));
        assertNotNull(binding.lastSeen.get("srv-0"), "fresh row survives eviction");
        assertEquals(2, binding.trackedPeerCount());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-S-004: oversized payloads, line floods, duplicate keys and oversized collections are rejected")
    void oversizedAndOverLimitRejected() {
        CapturingBridge bus = new CapturingBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 5000L, () -> 0L);

        byte[] huge = new byte[PluginMessageEnvelope.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(huge, (byte) 'a');
        assertEquals(PluginMessageEnvelope.Rejection.OVERSIZED, PluginMessageEnvelope.open(huge, null).rejection());
        bus.inject(huge);

        StringBuilder flood = new StringBuilder("serverId=flood");
        for (int i = 0; i < PluginMessageEnvelope.MAX_LINES + 1; i++) flood.append("\nk").append(i).append("=v");
        assertEquals(PluginMessageEnvelope.Rejection.MALFORMED,
                PluginMessageEnvelope.open(flood.toString().getBytes(StandardCharsets.UTF_8), null).rejection());

        String dup = "serverId=a\nserverId=b";
        assertEquals(PluginMessageEnvelope.Rejection.MALFORMED,
                PluginMessageEnvelope.open(dup.getBytes(StandardCharsets.UTF_8), null).rejection());

        List<String> many = new ArrayList<>();
        for (int i = 0; i <= PluginMessageEnvelope.MAX_COLLECTION_ENTRIES; i++) many.add("r" + i);
        BackendHeartbeat wide = new BackendHeartbeat("wide", 1, PluginState.READY, true, 0L,
                0.0, 0, 0, 0L, 0L, 0, many, List.of(), false);
        assertEquals(PluginMessageEnvelope.Rejection.LIMITS,
                PluginMessageEnvelope.open(PluginMessageEnvelope.seal(wide, null), null).rejection());

        String longId = "x".repeat(PluginMessageEnvelope.MAX_SERVER_ID_CHARS + 1);
        assertEquals(PluginMessageEnvelope.Rejection.LIMITS,
                PluginMessageEnvelope.open(PluginMessageEnvelope.seal(hb(longId, 0L), null), null).rejection());

        assertEquals(0, binding.trackedPeerCount());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-S-004: an outbound row exceeding the envelope cap is not sent")
    void oversizedOutboundNotSent() throws Exception {
        CapturingBridge bus = new CapturingBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bus, 5000L, () -> 0L);
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 0; i < 400; i++) meta.put("k" + i, "v".repeat(100));
        BackendHeartbeat fat = new BackendHeartbeat("fat", 1, PluginState.READY, true, 0L,
                0.0, 0, 0, 0L, 0L, 0, List.of(), List.of(), false, 0, 0,
                java.util.Set.of(), Map.of(), meta);
        assertNull(PluginMessageEnvelope.seal(fat, null));
        binding.publishBackendHeartbeat(fat).get();
        assertTrue(bus.sent.isEmpty());
        binding.close();
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: proxy-cache snapshot replies are verified; signed rows pass, forged rows are dropped")
    void snapshotResponseVerified() throws Exception {
        HmacVerifier v = verifier();
        CapturingBridge lobbyBus = new CapturingBridge();
        ProxyCacheNetworkBinding lobby = new ProxyCacheNetworkBinding(lobbyBus, 5000L, () -> 0L, v);

        // Push is signed for the proxy companion.
        lobby.publishBackendHeartbeat(hb("lobby", 0L)).get();
        assertTrue(PluginMessageEnvelope.open(lobbyBus.sent.get(0), v).accepted(), "push carries a valid MAC");

        // Companion replies: one genuine signed row, one forged unsigned, one wrong-key.
        lobbyBus.inject(PluginMessageEnvelope.seal(hb("backend-a", 0L), v));
        lobbyBus.inject(legacy(hb("forged", 0L)));
        lobbyBus.inject(PluginMessageEnvelope.seal(hb("wrong-key", 0L), HmacVerifier.forTesting(OTHER_SECRET, 1, 1)));

        var snap = lobby.readSnapshot().get();
        assertTrue(snap.backend("backend-a").isPresent());
        assertFalse(snap.backend("forged").isPresent());
        assertFalse(snap.backend("wrong-key").isPresent());
        lobby.close();
    }

    @Test
    @DisplayName("REQ-RTP-S-004: rejection warnings are aggregated per window")
    void throttledWarningAggregates() {
        AtomicLong clock = new AtomicLong(0L);
        List<String> lines = new ArrayList<>();
        ThrottledWarning w = new ThrottledWarning(lines::add, 1000L, clock::get);
        assertTrue(w.report("drop"));
        for (int i = 0; i < 50; i++) assertFalse(w.report("drop"));
        clock.set(1000L);
        assertTrue(w.report("drop"));
        assertEquals(2, lines.size(), "one line per window regardless of spam volume");
        assertTrue(lines.get(1).contains("51 occurrence(s)"), lines.get(1));
        assertEquals(52, w.total());
    }
}
