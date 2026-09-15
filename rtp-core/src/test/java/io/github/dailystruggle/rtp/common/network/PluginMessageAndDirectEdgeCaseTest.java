package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge;
import io.github.dailystruggle.rtp.common.network.pluginmessage.PluginMessageNetworkBinding;
import io.github.dailystruggle.rtp.common.network.pluginmessage.ProxyCacheNetworkBinding;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMessageAndDirectEdgeCaseTest {

    private static class StubNetworkBridge implements NetworkBridge {
        Consumer<byte[]> sink;
        boolean pushThrows = false;
        boolean snapshotThrows = false;

        @Override public boolean isAvailable() { return true; }
        @Override public Optional<UUID> anyOnlinePlayer() { return Optional.of(UUID.randomUUID()); }
        @Override public void broadcastHeartbeat(byte[] payload) {}
        @Override public void connect(UUID player, String targetServerId) {}
        @Override public void registerInbound(Consumer<byte[]> heartbeatSink) { this.sink = heartbeatSink; }
        @Override public ProxyProbe passiveProbe() { return ProxyProbe.ARMED; }

        @Override public void pushHeartbeatToProxy(byte[] payload) {
            if (pushThrows) throw new RuntimeException("simulated push error");
        }

        @Override public void requestSnapshot() {
            if (snapshotThrows) throw new RuntimeException("simulated snapshot error");
        }
    }

    private static BackendHeartbeat sampleHeartbeat(String serverId) {
        return new BackendHeartbeat(
                serverId, 1, BackendHeartbeat.PluginState.READY, true, System.currentTimeMillis(),
                20.0, 0, 100, 0L, 1L, 0, List.of("wild"), List.of("world"), false
        );
    }

    @Test
    @DisplayName("PluginMessageNetworkBinding covers closed state, malformed inbound, throwing subscriber, and double close")
    void pluginMessageEdgeCases() throws Exception {
        StubNetworkBridge bridge = new StubNetworkBridge();
        PluginMessageNetworkBinding binding = new PluginMessageNetworkBinding(bridge);

        assertNotNull(binding);
        assertEquals(0, binding.livePeerCount());

        // Malformed or empty inbound handling
        assertDoesNotThrow(() -> bridge.sink.accept(null));
        assertDoesNotThrow(() -> bridge.sink.accept(new byte[0]));
        assertDoesNotThrow(() -> bridge.sink.accept("not-json".getBytes(StandardCharsets.UTF_8)));

        // Throwing subscriber isolation
        List<String> received = new ArrayList<>();
        Subscription subFail = binding.subscribeBackendHeartbeats(hb -> {
            throw new RuntimeException("subscriber failure");
        });
        Subscription subOk = binding.subscribeBackendHeartbeats(hb -> received.add(hb.serverId()));

        byte[] validBytes = BackendHeartbeatCodec.encode(sampleHeartbeat("server1")).getBytes(StandardCharsets.UTF_8);
        bridge.sink.accept(validBytes);

        assertEquals(List.of("server1"), received);
        assertEquals(1, binding.livePeerCount());

        // Subscription close idempotence
        assertFalse(subOk.isClosed());
        subOk.close();
        assertTrue(subOk.isClosed());
        subOk.close(); // idempotent

        // publishProxyHeartbeat is no-op
        assertDoesNotThrow(() -> binding.publishProxyHeartbeat(new ProxyHeartbeat("proxy1", 1, System.currentTimeMillis(), 10, 0, false)).get());

        // Close binding
        binding.close();
        binding.close(); // idempotent

        // After close, publish fails
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> binding.publishBackendHeartbeat(sampleHeartbeat("server1")).get());
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    @DisplayName("ProxyCacheNetworkBinding covers closed state, malformed inbound, throwing subscriber, and error catching")
    void proxyCacheEdgeCases() throws Exception {
        StubNetworkBridge bridge = new StubNetworkBridge();
        ProxyCacheNetworkBinding binding = new ProxyCacheNetworkBinding(bridge);

        assertNotNull(binding);
        assertEquals(0, binding.livePeerCount());

        // Malformed or empty inbound handling
        assertDoesNotThrow(() -> bridge.sink.accept(null));
        assertDoesNotThrow(() -> bridge.sink.accept(new byte[0]));
        assertDoesNotThrow(() -> bridge.sink.accept("not-json".getBytes(StandardCharsets.UTF_8)));

        // Throwing subscriber isolation
        List<String> received = new ArrayList<>();
        Subscription subFail = binding.subscribeBackendHeartbeats(hb -> {
            throw new RuntimeException("subscriber failure");
        });
        Subscription subOk = binding.subscribeBackendHeartbeats(hb -> received.add(hb.serverId()));

        byte[] validBytes = BackendHeartbeatCodec.encode(sampleHeartbeat("server2")).getBytes(StandardCharsets.UTF_8);
        bridge.sink.accept(validBytes);

        assertEquals(List.of("server2"), received);
        assertEquals(1, binding.livePeerCount());

        // Subscription close idempotence
        assertFalse(subOk.isClosed());
        subOk.close();
        assertTrue(subOk.isClosed());
        subOk.close();

        // Push throws and snapshot request throws are caught and logged at FINE
        bridge.pushThrows = true;
        bridge.snapshotThrows = true;
        assertDoesNotThrow(() -> binding.publishBackendHeartbeat(sampleHeartbeat("server2")).get());

        // publishProxyHeartbeat is no-op
        assertDoesNotThrow(() -> binding.publishProxyHeartbeat(new ProxyHeartbeat("proxy1", 1, System.currentTimeMillis(), 10, 0, false)).get());

        // Close binding
        binding.close();
        binding.close();

        // After close, publish fails
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> binding.publishBackendHeartbeat(sampleHeartbeat("server2")).get());
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }
}
