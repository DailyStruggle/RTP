package io.github.dailystruggle.rtp.common.network.direct;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link ProxyDirectNetworkBinding}, the player-independent
 * backend-&gt;proxy socket tier (rtp-proxy-ADR-017). A tiny in-test TCP server
 * stands in for the Velocity {@code ProxyDirectListener}: it ingests the
 * backend's pushed heartbeat and replays every stored row back, mirroring the
 * real companion's behaviour over the shared {@link ProxyDirectWire} framing.
 */
class ProxyDirectNetworkBindingTest {

    private FakeProxyServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    /** Minimal proxy-direct listener double: one request/response per connection. */
    private static final class FakeProxyServer {
        private final ServerSocket socket;
        private final HmacVerifier verifier;
        private final int schema;
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(true);

        FakeProxyServer(HmacVerifier verifier, int schema) throws Exception {
            this.verifier = verifier;
            this.schema = schema;
            this.socket = new ServerSocket();
            this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread t = new Thread(this::loop, "fake-proxy-direct");
            t.setDaemon(true);
            t.start();
        }

        int port() { return socket.getLocalPort(); }

        void stop() {
            running.set(false);
            try { socket.close(); } catch (Exception ignored) { }
        }

        private void loop() {
            while (running.get() && !socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    // Every proxy-direct connection is an opcode + signed
                    // request; branch on it (PROPOSAL-proxy-direct-as-remote-store).
                    byte op = ProxyDirectWire.readOpcode(in);
                    String payload = ProxyDirectWire.readSignedPayload(in, verifier);
                    if (op == ProxyDirectWire.OP_FIND_RESERVATION) {
                        // Manufacture a CLAIMED token for the requested player.
                        String reply = "";
                        if (payload != null) {
                            ReservationToken tok = new ReservationToken(
                                    "tok-1", "backend-a",
                                    java.util.UUID.fromString(payload.trim()),
                                    9_999_999_999L, ReservationToken.State.CLAIMED, "arena");
                            reply = ProxyDirectWire.encodeToken(tok);
                        }
                        ProxyDirectWire.writeSignedPayload(out, reply, verifier, schema);
                        out.flush();
                    } else if (op == ProxyDirectWire.OP_REDEEM) {
                        ProxyDirectWire.writeSignedPayload(out,
                                RedeemOutcome.REDEEMED.name(), verifier, schema);
                        out.flush();
                    } else { // OP_HEARTBEAT
                        if (payload != null) {
                            BackendHeartbeat hb = BackendHeartbeatCodec.decode(payload);
                            store.put(hb.serverId(), payload);
                        }
                        ProxyDirectWire.writeList(out, List.copyOf(store.values()), verifier, schema);
                    }
                } catch (Exception e) {
                    if (running.get()) { /* ignore between-accept errors in test */ }
                }
            }
        }
    }

    private static BackendHeartbeat hb(String serverId, List<String> regions) {
        return new BackendHeartbeat(serverId, 1, PluginState.READY, true, 1000L,
                10.0, 0, 100, 0L, 0L, 1, regions, List.of("world"), false);
    }

    @Test
    @DisplayName("publish dials the proxy, pushes the row, and reads the merged snapshot back")
    void publishThenReadSnapshotRoundTrip() throws Exception {
        server = new FakeProxyServer(null, 1); // unsigned
        ProxyDirectNetworkBinding binding = new ProxyDirectNetworkBinding(
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", server.port())),
                null, 1, 5000L, 1000, 2000, () -> 1000L);

        binding.publishBackendHeartbeat(hb("backend-a", List.of("default", "nether"))).get();

        NetworkSnapshot snap = binding.readSnapshot().get();
        assertTrue(snap.backend("backend-a").isPresent(), "the proxy must echo our pushed row back");
        assertEquals(List.of("default", "nether"), snap.backend("backend-a").get().regionsAvailable());
        assertEquals(1, binding.livePeerCount());
        binding.close();
    }

    @Test
    @DisplayName("HMAC-signed round-trip succeeds when both ends share the secret")
    void hmacSignedRoundTrip() throws Exception {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) i;
        HmacVerifier v = HmacVerifier.forTesting(secret, 1, 1);
        server = new FakeProxyServer(v, 1);

        ProxyDirectNetworkBinding binding = new ProxyDirectNetworkBinding(
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", server.port())),
                v, 1, 5000L, 1000, 2000, () -> 1000L);
        binding.publishBackendHeartbeat(hb("backend-b", List.of("default"))).get();

        NetworkSnapshot snap = binding.readSnapshot().get();
        assertTrue(snap.backend("backend-b").isPresent(), "signed row must round-trip");
        binding.close();
    }

    @Test
    @DisplayName("a mismatched HMAC secret causes the proxy to drop the push (no row appears)")
    void hmacMismatchRejected() throws Exception {
        byte[] proxySecret = new byte[32];
        byte[] backendSecret = new byte[32];
        for (int i = 0; i < 32; i++) { proxySecret[i] = (byte) i; backendSecret[i] = (byte) (i + 1); }
        HmacVerifier proxyV = HmacVerifier.forTesting(proxySecret, 1, 1);
        HmacVerifier backendV = HmacVerifier.forTesting(backendSecret, 1, 1);
        server = new FakeProxyServer(proxyV, 1);

        ProxyDirectNetworkBinding binding = new ProxyDirectNetworkBinding(
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", server.port())),
                backendV, 1, 5000L, 1000, 2000, () -> 1000L);
        binding.publishBackendHeartbeat(hb("backend-c", List.of("default"))).get();

        // The proxy rejects the HMAC-invalid push, so its store stays empty and
        // the snapshot carries no rows.
        assertEquals(0, binding.livePeerCount());
        assertFalse(binding.readSnapshot().get().backend("backend-c").isPresent());
        binding.close();
    }

    @Test
    @DisplayName("findReservation + redeem RPC round-trip against the proxy store")
    void findReservationAndRedeemRpc() throws Exception {
        server = new FakeProxyServer(null, 1);
        ProxyDirectNetworkBinding binding = new ProxyDirectNetworkBinding(
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", server.port())),
                null, 1, 5000L, 1000, 2000, () -> 1000L);
        java.util.UUID player = java.util.UUID.randomUUID();

        ReservationToken tok = binding.findReservation(player).get().orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(tok, "findReservation must return the proxy's token");
        assertEquals("backend-a", tok.serverId());
        assertEquals(java.util.Optional.of("arena"), tok.regionKey());

        RedeemOutcome outcome = binding.redeem(tok.tokenId(), player, "backend-a").get();
        assertEquals(RedeemOutcome.REDEEMED, outcome, "redeem must consume the token on arrival");
        binding.close();
    }

    @Test
    @DisplayName("an unreachable proxy is a silent skip, not a thrown error")
    void unreachableProxyIsSilentSkip() throws Exception {
        // Point at a port nothing is listening on; publish must still complete.
        ProxyDirectNetworkBinding binding = new ProxyDirectNetworkBinding(
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", 1)),
                null, 1, 5000L, 300, 300, () -> 1000L);
        binding.publishBackendHeartbeat(hb("backend-a", List.of("default"))).get();
        assertEquals(0, binding.livePeerCount());
        binding.close();
    }
}
