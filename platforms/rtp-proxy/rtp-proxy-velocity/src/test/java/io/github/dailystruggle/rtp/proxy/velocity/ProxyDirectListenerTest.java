package io.github.dailystruggle.rtp.proxy.velocity;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                "127.0.0.1", 0, null, 1,
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
                ProxyDirectWire.writeOpcode(out, ProxyDirectWire.OP_HEARTBEAT);
                ProxyDirectWire.writeSignedPayload(out,
                        BackendHeartbeatCodec.encode(hb("backend-x", List.of("default", "arena"))),
                        null, 1);
                out.flush();
                DataInputStream in = new DataInputStream(s.getInputStream());
                reply = ProxyDirectWire.readList(in, null);
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
                "127.0.0.1", 0, null, 1,
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
            ProxyDirectWire.writeOpcode(out, op);
            ProxyDirectWire.writeSignedPayload(out, request, null, 1);
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            return ProxyDirectWire.readSignedPayload(in, null);
        }
    }

    private static String rpcSingleWithListReq(int port, byte op, List<String> requests) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            // Read timeout must exceed the listener's RPC_AWAIT_MS (2000ms); see rpcSingle.
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            ProxyDirectWire.writeOpcode(out, op);
            ProxyDirectWire.writeList(out, requests, null, 1);
            DataInputStream in = new DataInputStream(s.getInputStream());
            return ProxyDirectWire.readSignedPayload(in, null);
        }
    }
}
