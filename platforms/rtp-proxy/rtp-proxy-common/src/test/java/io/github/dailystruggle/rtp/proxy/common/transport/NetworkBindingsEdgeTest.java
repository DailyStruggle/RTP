package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor;
import io.github.dailystruggle.rtp.proxy.common.Role;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class NetworkBindingsEdgeTest {

    private static final RTPProxyAccessor ACCESSOR = new RTPProxyAccessor() {
        @Override public Role role() { return Role.BACKEND; }
        @Override public String proxyId() { return "test-proxy"; }
        @Override public void sendMessage(UUID playerId, String message) {}
        @Override public CompletableFuture<Void> transferPlayer(UUID playerId, String backendId) {
            return CompletableFuture.completedFuture(null);
        }
    };

    private static NetworkConfig configWith(String transportType, String secretEnv) {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> net = new HashMap<>();
        net.put("enabled", false); // disabled so secretEnv isn't verified at config parse time
        net.put("secretEnv", secretEnv != null ? secretEnv : "DUMMY_SECRET");
        net.put("role", "backend");
        map.put("network", net);

        Map<String, Object> tr = new HashMap<>();
        if (transportType != null) {
            tr.put("type", transportType);
        }
        Map<String, Object> red = new HashMap<>();
        red.put("host", "127.0.0.1");
        red.put("port", 6379);
        tr.put("redis", red);
        map.put("transport", tr);

        return NetworkConfig.fromMap(map, ACCESSOR);
    }

    @Test
    void open_nullCfg_throws() {
        assertThrows(NullPointerException.class, () -> NetworkBindings.open(null, null));
    }

    @Test
    void open_backendOnlyTypes_fallBackToInMemory() {
        for (String t : new String[]{"plugin-message", "pluginmessage", "auto"}) {
            NetworkConfig cfg = configWith(t, "UNSET_ENV");
            try (NetworkTransport transport = NetworkBindings.open(cfg, null)) {
                assertInstanceOf(InMemoryNetworkStateBinding.class, transport);
            }
        }
    }

    @Test
    void open_sqlWithoutDataSource_throws() {
        NetworkConfig cfg = configWith("sql", "UNSET_ENV");
        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.open(cfg, null));
    }

    @Test
    void open_sqlWithUnsetHmacSecret_fallsBackToInMemory() {
        DataSource ds = mock(DataSource.class);
        NetworkConfig cfg = configWith("sql", "UNSET_ENV_FOR_TEST_XYZ");
        try (NetworkTransport transport = NetworkBindings.open(cfg, ds)) {
            assertInstanceOf(InMemoryNetworkStateBinding.class, transport);
        }
    }

    @Test
    void open_redisWithUnsetHmacSecret_fallsBackToInMemory() {
        NetworkConfig cfg = configWith("redis", "UNSET_ENV_FOR_TEST_XYZ");
        try (NetworkTransport transport = NetworkBindings.open(cfg, null)) {
            assertInstanceOf(InMemoryNetworkStateBinding.class, transport);
        }
    }

    @Test
    void open_unknownTransportType_throws() {
        NetworkConfig cfg = configWith("unsupported-binding-name", "UNSET_ENV");
        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.open(cfg, null));
    }
}
