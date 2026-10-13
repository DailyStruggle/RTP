package io.github.dailystruggle.rtp.proxy.velocity;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectAllowlist;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectTlsConfig;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.Direction;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.WireContext;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link ProxyDirectListener}, the RPC server half of the
 * {@code proxy-direct} transport. A raw
 * socket client stands in for a backend's {@code ProxyDirectNetworkBinding}: it
 * issues {@link ProxyDirectWire} opcodes and the listener dispatches them onto
 * the proxy's own in-memory {@code NetworkTransport} + {@code NetworkRequestQueue},
 * so {@code proxy-direct} behaves as "just another data-management transport"
 * over a socket.
 */
class ProxyDirectListenerTest {

    private static final HmacVerifier V = verifier();

    private static HmacVerifier verifier() {
        byte[] s = new byte[32];
        for (int i = 0; i < s.length; i++) s[i] = (byte) (i * 3 + 1);
        return HmacVerifier.forTesting(s, 1, 1);
    }

    private static BackendHeartbeat hb(String serverId, List<String> regions) {
        return new BackendHeartbeat(serverId, 1, PluginState.READY, true, 1000L,
                10.0, 0, 100, 0L, 0L, 1, regions, List.of("world"), false);
    }

    @Test
    @DisplayName("OP_HEARTBEAT populates the cache and the snapshot reply echoes it back")
    void heartbeatThenSnapshot() throws Exception {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of(), 5000L, () -> 1000L);
        ProxyDirectListener listener = new ProxyDirectListener(
                "127.0.0.1", 0, V, 1,
                payload -> cache.onPush(BackendHeartbeatCodec.decode(payload)),
                () -> {
                    List<String> rows = new ArrayList<>();
                    for (BackendHeartbeat h : cache.snapshot()) rows.add(BackendHeartbeatCodec.encode(h));
                    return rows;
                },
                LoggerFactory.getLogger("test"));
        listener.start();
        try {
            int port = listener.boundPort();
            List<String> reply;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                s.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                WireContext ctx = ProxyDirectWire.writeRequestHeader(out, ProxyDirectWire.OP_HEARTBEAT);
                ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST,
                        BackendHeartbeatCodec.encode(hb("backend-x", List.of("default", "arena"))),
                        V, 1);
                out.flush();
                DataInputStream in = new DataInputStream(s.getInputStream());
                reply = ProxyDirectWire.readList(in, ctx, Direction.RESPONSE, V);
            }
            assertEquals(1, reply.size(), "snapshot reply must carry the just-pushed row");
            BackendHeartbeat got = BackendHeartbeatCodec.decode(reply.get(0));
            assertEquals("backend-x", got.serverId());
            assertEquals(List.of("default", "arena"), got.regionsAvailable());
            assertTrue(cache.snapshot().stream().anyMatch(h -> h.serverId().equals("backend-x")));
        } finally {
            listener.stop();
        }
    }

    @Test
    @DisplayName("flushPending enqueues into the proxy queue; findReservation + redeem RPCs hit the proxy store")
    void requestQueueAndReservationRpc() throws Exception {
        InMemoryNetworkStateBinding transport = new InMemoryNetworkStateBinding();
        InMemoryNetworkRequestQueue queue = new InMemoryNetworkRequestQueue();
        ProxyDirectListener listener = new ProxyDirectListener(
                "127.0.0.1", 0, V, 1,
                payload -> { /* heartbeat unused here */ },
                List::of,
                transport, queue,
                LoggerFactory.getLogger("test"));
        listener.start();
        try {
            int port = listener.boundPort();
            UUID player = UUID.randomUUID();

            // OP_FLUSH_PENDING: a backend enrols one cross-server request.
            NetworkRequestQueue.EnrolmentEnvelope env = new NetworkRequestQueue.EnrolmentEnvelope(
                    player, UUID.randomUUID(), Optional.of("default"), Optional.of("backend-a"), 1000L);
            assertEquals("ACCEPTED", rpcSingleWithListReq(port,
                    ProxyDirectWire.OP_FLUSH_PENDING, List.of(ProxyDirectWire.encodeEnvelope(env))),
                    "flushPending must enqueue and ACCEPT");
            Optional<NetworkRequestQueue.QueueEnvelope> popped =
                    queue.dequeueReady(Duration.ofSeconds(1)).get();
            assertTrue(popped.isPresent(), "the enrolment must land in the proxy's own queue");
            assertEquals(player, popped.get().playerId());

            // Proxy claims a reservation against its store (as the dispatcher would).
            ReservationToken token = transport.claim("backend-a", player,
                    Duration.ofSeconds(30), Optional.of("default")).get();
            assertNotNull(token);

            // OP_FIND_RESERVATION: the arriving backend looks it up over RPC.
            String found = rpcSingle(port, ProxyDirectWire.OP_FIND_RESERVATION, player.toString());
            ReservationToken decoded = ProxyDirectWire.decodeToken(found);
            assertNotNull(decoded, "findReservation must return the claimed token");
            assertEquals("backend-a", decoded.serverId());
            assertEquals(Optional.of("default"), decoded.regionKey());

            // OP_REDEEM: the backend consumes it on arrival.
            String redeem = rpcSingle(port, ProxyDirectWire.OP_REDEEM,
                    decoded.tokenId() + ProxyDirectWire.FS + player + ProxyDirectWire.FS + "backend-a");
            assertEquals(RedeemOutcome.REDEEMED.name(), redeem);
        } finally {
            listener.stop();
            transport.close();
        }
    }

    private static String rpcSingle(int port, byte op, String request) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            // Read timeout must exceed the listener's RPC_AWAIT_MS (2000ms) so a
            // handler that blocks near its full await budget cannot race the client
            // read into a spurious SocketTimeoutException.
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            WireContext ctx = ProxyDirectWire.writeRequestHeader(out, op);
            ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, request, V, 1);
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            return ProxyDirectWire.readSignedPayload(in, ctx, Direction.RESPONSE, V);
        }
    }

    private static String rpcSingleWithListReq(int port, byte op, List<String> requests) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            // Read timeout must exceed the listener's RPC_AWAIT_MS (2000ms); see rpcSingle.
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            WireContext ctx = ProxyDirectWire.writeRequestHeader(out, op);
            ProxyDirectWire.writeList(out, ctx, Direction.REQUEST, requests, V, 1);
            DataInputStream in = new DataInputStream(s.getInputStream());
            return ProxyDirectWire.readSignedPayload(in, ctx, Direction.RESPONSE, V);
        }
    }

    /** Heartbeat-only listener on an injected clock that counts accepted pushes. */
    private static ProxyDirectListener countingListener(AtomicInteger pushes, LongSupplier clock) {
        return new ProxyDirectListener("127.0.0.1", 0, V, 1,
                payload -> pushes.incrementAndGet(), () -> List.of("row-a"), null, null,
                null, null, LoggerFactory.getLogger("test"), clock);
    }

    /** Sends raw request bytes; returns the first reply byte, or -1 when closed unanswered. */
    private static int sendRaw(int port, byte[] request) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s.setSoTimeout(5000);
            s.getOutputStream().write(request);
            s.getOutputStream().flush();
            try {
                return s.getInputStream().read();
            } catch (IOException reset) {
                return -1;
            }
        }
    }

    /** A complete signed heartbeat request stamped by {@code sender}. */
    private static byte[] heartbeatRequest(LongSupplier sender) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        WireContext ctx = ProxyDirectWire.writeRequestHeader(out, ProxyDirectWire.OP_HEARTBEAT, sender);
        ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "hb", V, 1);
        out.flush();
        return baos.toByteArray();
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: unknown opcode is closed unanswered and never handled as a heartbeat")
    void unknownOpcodeRejected() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        ProxyDirectListener listener = countingListener(pushes, System::currentTimeMillis);
        listener.start();
        try {
            byte unknown = 0x2A;
            WireContext ctx = new WireContext(unknown, System.currentTimeMillis(), "0123456789abcdef");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(unknown);
            out.writeByte(ProxyDirectWire.FRAME_VERSION);
            out.writeLong(ctx.sentAtMs());
            out.writeUTF(ctx.nonce());
            ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "hb", V, 1);
            assertEquals(-1, sendRaw(listener.boundPort(), baos.toByteArray()));
            assertEquals(0, pushes.get(), "unknown opcode must not reach the heartbeat handler");

            // A well-formed heartbeat still works.
            assertTrue(sendRaw(listener.boundPort(), heartbeatRequest(System::currentTimeMillis)) >= 0);
            assertEquals(1, pushes.get());
        } finally {
            listener.stop();
        }
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a v1 frame is closed unanswered")
    void v1FrameRejected() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        ProxyDirectListener listener = countingListener(pushes, System::currentTimeMillis);
        listener.start();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(ProxyDirectWire.OP_HEARTBEAT);
            out.writeInt(1);
            out.writeUTF(V.sign(1, "hb"));
            out.writeUTF("hb");
            assertEquals(-1, sendRaw(listener.boundPort(), baos.toByteArray()));
            assertEquals(0, pushes.get());
        } finally {
            listener.stop();
        }
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a replayed request nonce is rejected after the first use")
    void replayedNonceRejected() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        ProxyDirectListener listener = countingListener(pushes, System::currentTimeMillis);
        listener.start();
        try {
            byte[] request = heartbeatRequest(System::currentTimeMillis);
            assertTrue(sendRaw(listener.boundPort(), request) >= 0, "first use must be answered");
            assertEquals(1, pushes.get());
            assertEquals(-1, sendRaw(listener.boundPort(), request), "replay must be closed unanswered");
            assertEquals(1, pushes.get(), "replay must not be executed");
        } finally {
            listener.stop();
        }
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a request timestamp outside the clock-skew window is rejected")
    void staleTimestampRejected() throws Exception {
        long now = 1_800_000_000_000L;
        AtomicInteger pushes = new AtomicInteger();
        ProxyDirectListener listener = countingListener(pushes, () -> now);
        listener.start();
        try {
            long skew = ProxyDirectWire.MAX_CLOCK_SKEW_MS;
            assertEquals(-1, sendRaw(listener.boundPort(), heartbeatRequest(() -> now - skew - 1)));
            assertEquals(-1, sendRaw(listener.boundPort(), heartbeatRequest(() -> now + skew + 1)));
            assertEquals(0, pushes.get(), "stale or future-dated requests must not be executed");
            assertTrue(sendRaw(listener.boundPort(), heartbeatRequest(() -> now - skew)) >= 0,
                    "edge of the window is accepted");
            assertEquals(1, pushes.get());
        } finally {
            listener.stop();
        }
    }

    private static ProxyDirectListener discoveryListener(String bindHost, HmacVerifier v,
                                                         ProxyDirectTlsConfig tls,
                                                         ProxyDirectAllowlist allowlist) {
        return new ProxyDirectListener(bindHost, 0, v, 1,
                payload -> { }, List::of, null, null, tls, allowlist,
                LoggerFactory.getLogger("test"));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: listener refuses to start without an HMAC verifier")
    void refusesToStartUnsigned() {
        ProxyDirectListener listener = discoveryListener("127.0.0.1", null, null, null);
        assertThrows(IllegalStateException.class, listener::start);
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: default bind is loopback; non-loopback plain TCP needs explicit tls: false")
    void nonLoopbackPlainRequiresExplicitOptOut() throws Exception {
        assertEquals("127.0.0.1", ProxyDirectListener.DEFAULT_BIND_HOST);
        assertTrue(ProxyDirectListener.isLoopback("127.0.0.1"));
        assertTrue(ProxyDirectListener.isLoopback("localhost"));
        org.junit.jupiter.api.Assertions.assertFalse(ProxyDirectListener.isLoopback("0.0.0.0"));

        ProxyDirectListener wildcard = discoveryListener("0.0.0.0", V, ProxyDirectTlsConfig.unset(), null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, wildcard::start);
        assertTrue(ex.getMessage().contains("tls"), ex.getMessage());

        // Blank bindHost falls back to loopback and starts plain.
        ProxyDirectListener dflt = discoveryListener("", V, null, null);
        dflt.start();
        try {
            assertTrue(dflt.boundPort() > 0);
        } finally {
            dflt.stop();
        }
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: clients outside allowedClients are closed before any RPC")
    void allowlistRejectsOtherClients() throws Exception {
        ProxyDirectListener listener = discoveryListener("127.0.0.1", V, null,
                ProxyDirectAllowlist.parse(List.of("10.255.255.0/24")));
        listener.start();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", listener.boundPort()), 1000);
            s.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            try {
                WireContext ctx = ProxyDirectWire.writeRequestHeader(out, ProxyDirectWire.OP_HEARTBEAT);
                ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "x", V, 1);
                out.flush();
                assertThrows(IOException.class, () -> ProxyDirectWire.readList(in, ctx, Direction.RESPONSE, V));
            } catch (IOException expected) {
                // Connection was closed by listener
            }
        } finally {
            listener.stop();
        }

        ProxyDirectAllowlist loopback = ProxyDirectAllowlist.parse(List.of("127.0.0.0/8", "::1"));
        assertTrue(loopback.permits(java.net.InetAddress.getByName("127.0.0.1")));
        org.junit.jupiter.api.Assertions.assertFalse(
                loopback.permits(java.net.InetAddress.getByName("192.168.1.1")));
        assertThrows(IllegalArgumentException.class, () -> ProxyDirectAllowlist.parse(List.of("proxy.example")));
        assertThrows(IllegalArgumentException.class, () -> ProxyDirectAllowlist.parse(List.of("10.0.0.0/33")));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: per-address connection cap closes excess sockets unread and frees slots on close")
    void perAddressConnectionCap() throws Exception {
        ProxyDirectListener listener = discoveryListener("127.0.0.1", V, null, null);
        listener.start();
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < ProxyDirectListener.MAX_CONNECTIONS_PER_ADDRESS; i++) {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", listener.boundPort()), 1000);
                held.add(s);
            }
            long deadline = System.currentTimeMillis() + 2_000L;
            while (listener.trackedAddressCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            try (Socket extra = new Socket()) {
                extra.connect(new InetSocketAddress("127.0.0.1", listener.boundPort()), 1000);
                extra.setSoTimeout(2000);
                // Closed by the listener before any byte is read: EOF (or reset), never a hang.
                int read;
                try {
                    read = extra.getInputStream().read();
                } catch (IOException reset) {
                    read = -1;
                }
                assertEquals(-1, read, "over-cap connection must be closed");
            }
        } finally {
            for (Socket s : held) s.close();
        }
        try {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (listener.trackedAddressCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(0, listener.trackedAddressCount(), "slots must be released when clients close");
        } finally {
            listener.stop();
        }
    }

    private static void keytool(String... args) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool failed: " + cmd);
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: TLS listener + hostname-verified TLS client round-trip a signed heartbeat")
    void tlsRoundTrip(@TempDir Path dir) throws Exception {
        String pass = "changeit";
        Path ks = dir.resolve("server.p12");
        Path cert = dir.resolve("server.cer");
        Path ts = dir.resolve("trust.p12");
        keytool("-genkeypair", "-alias", "rtp", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", pass, "-keypass", pass);
        keytool("-exportcert", "-alias", "rtp", "-keystore", ks.toString(), "-storepass", pass,
                "-file", cert.toString());
        keytool("-importcert", "-noprompt", "-alias", "rtp", "-file", cert.toString(),
                "-storetype", "PKCS12", "-keystore", ts.toString(), "-storepass", pass);

        ProxyDirectTlsConfig serverTls = ProxyDirectTlsConfig.enabled(ks.toString(), pass.toCharArray(),
                null, null, true);
        ProxyDirectListener listener = new ProxyDirectListener("127.0.0.1", 0, V, 1,
                payload -> { }, () -> List.of("row-a"), null, null, serverTls, null,
                LoggerFactory.getLogger("test"));
        listener.start();
        try {
            ProxyDirectTlsConfig clientTls = ProxyDirectTlsConfig.enabled(null, null,
                    ts.toString(), pass.toCharArray(), true);
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress("127.0.0.1", listener.boundPort()), 1000);
            plain.setSoTimeout(5000);
            try (Socket s = clientTls.wrapClient(plain, "localhost", listener.boundPort())) {
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                WireContext ctx = ProxyDirectWire.writeRequestHeader(out, ProxyDirectWire.OP_HEARTBEAT);
                ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "not-a-heartbeat", V, 1);
                out.flush();
                assertEquals(List.of("row-a"), ProxyDirectWire.readList(
                        new DataInputStream(s.getInputStream()), ctx, Direction.RESPONSE, V));
            }

            // Hostname verification: the cert does not cover this name.
            Socket plain2 = new Socket();
            plain2.connect(new InetSocketAddress("127.0.0.1", listener.boundPort()), 1000);
            plain2.setSoTimeout(5000);
            assertThrows(IOException.class, () -> clientTls.wrapClient(plain2, "not-the-proxy.example", 1));
            plain2.close();

            // Plain client against the TLS listener gets no usable reply.
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress("127.0.0.1", listener.boundPort()), 1000);
                raw.setSoTimeout(3000);
                DataOutputStream out = new DataOutputStream(raw.getOutputStream());
                try {
                    WireContext ctx = ProxyDirectWire.writeRequestHeader(out, ProxyDirectWire.OP_HEARTBEAT);
                    ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "x", V, 1);
                    out.flush();
                    DataInputStream rawIn = new DataInputStream(raw.getInputStream());
                    assertThrows(IOException.class, () -> ProxyDirectWire.readList(rawIn, ctx, Direction.RESPONSE, V));
                } catch (IOException expected) {
                    // Connection was closed by listener
                }
            }
        } finally {
            listener.stop();
        }
    }
}
