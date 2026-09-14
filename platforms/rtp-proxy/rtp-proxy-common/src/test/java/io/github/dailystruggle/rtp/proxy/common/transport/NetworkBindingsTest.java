package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.PlayerOwnershipTracker;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.AlwaysLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkBindingsTest {

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:nb_test_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        this.ds = new H2DataSource(url);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE rtp_net_proxy_heartbeats (proxy_id VARCHAR(64) PRIMARY KEY, schema_version INT, last_heartbeat_epoch_ms BIGINT, registered_servers INT, active_teleports INT);");
            s.execute("CREATE TABLE rtp_net_backend_heartbeats (server_id VARCHAR(64) PRIMARY KEY, schema_version INT, plugin_state VARCHAR(32), is_online BOOLEAN, last_heartbeat_epoch_ms BIGINT, unreserved_cached_locations INT, total_regions INT, tps DOUBLE, active_reservations INT, total_reservations_served INT, load_score INT, available_worlds VARCHAR(1024), available_regions VARCHAR(1024));");
            s.execute("CREATE TABLE rtp_net_reservations (token_id VARCHAR(64) PRIMARY KEY, server_id VARCHAR(64), player_id VARCHAR(36), state VARCHAR(32), region_key VARCHAR(64), expires_at_ms BIGINT, redeemed_at_ms BIGINT, terminal_reason VARCHAR(32));");
            s.execute("CREATE TABLE rtp_net_request_queue (correlation_id VARCHAR(36) PRIMARY KEY, player_id VARCHAR(36) UNIQUE, region_key VARCHAR(64), server_hint VARCHAR(64), created_at_ms BIGINT, status VARCHAR(32), assigned_server VARCHAR(64), assigned_region VARCHAR(64), updated_at_ms BIGINT, error_message VARCHAR(256));");
        }
    }

    private static final class H2DataSource implements DataSource {
        private final String url;
        H2DataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("h2"); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private NetworkConfig config(String transport, boolean enabled, boolean waitlist) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        java.util.Map<String, Object> net = new java.util.HashMap<>();
        net.put("enabled", enabled);
        net.put("secretEnv", "PATH");
        net.put("role", "backend");
        java.util.Map<String, Object> wl = new java.util.HashMap<>();
        wl.put("enabled", waitlist);
        net.put("waitlist", wl);
        map.put("network", net);

        java.util.Map<String, Object> tr = new java.util.HashMap<>();
        tr.put("type", transport);
        java.util.Map<String, Object> red = new java.util.HashMap<>();
        red.put("host", "127.0.0.1");
        red.put("port", 6379);
        tr.put("redis", red);
        map.put("transport", tr);

        io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor accessor = new io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor() {
            @Override public io.github.dailystruggle.rtp.proxy.common.Role role() { return io.github.dailystruggle.rtp.proxy.common.Role.BACKEND; }
            @Override public String proxyId() { return "test-proxy"; }
            @Override public void sendMessage(UUID playerId, String message) {}
            @Override public java.util.concurrent.CompletableFuture<Void> transferPlayer(UUID playerId, String backendId) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        };
        // if enabled is true, NetworkConfig.fromMap checks secretEnv in environment.
        // But for unit tests where enabled is false or mocked, let's see:
        return NetworkConfig.fromMap(map, accessor);
    }

    @Test
    void open_inMemory_andPluginMessage() {
        NetworkTransport mem = NetworkBindings.open(config("in-memory", true, true), null);
        assertTrue(mem instanceof InMemoryNetworkStateBinding);

        NetworkTransport pm = NetworkBindings.open(config("plugin-message", true, true), null);
        assertTrue(pm instanceof InMemoryNetworkStateBinding);

        NetworkTransport auto = NetworkBindings.open(config("auto", true, true), null);
        assertTrue(auto instanceof InMemoryNetworkStateBinding);
    }

    @Test
    void open_sql_withAndWithoutDataSource() {
        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.open(config("sql", true, true), null));

        NetworkTransport sql = NetworkBindings.open(config("sql", true, true), ds);
        // If PATH is not valid Base64 >= 32 bytes, HmacVerifier.loadFromEnv catches NetworkConfigException
        // and falls back to InMemoryNetworkStateBinding!
        assertNotNull(sql);
        sql.close();
    }

    @Test
    void open_redis_withUnreachableHost_fallsBackOrThrows() {
        // If PATH is not valid Base64, HMAC load fails and falls back to in-memory
        NetworkTransport redis = NetworkBindings.open(config("redis", true, true), null);
        assertNotNull(redis);
        redis.close();
    }

    @Test
    void open_unrecognisedTransport_throws() {
        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.open(config("bogus", true, true), null));
    }

    @Test
    void openRequestQueue_allTransports() {
        NetworkRequestQueue mem = NetworkBindings.openRequestQueue(config("in-memory", true, true));
        assertTrue(mem instanceof InMemoryNetworkRequestQueue);

        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.openRequestQueue(config("sql", true, true), null));

        NetworkRequestQueue sql = NetworkBindings.openRequestQueue(config("sql", true, true), ds);
        assertTrue(sql instanceof SqlNetworkRequestQueue);

        // Redis unreachable -> falls back to in-memory queue
        NetworkRequestQueue redisQueue = NetworkBindings.openRequestQueue(config("redis", true, true), null);
        assertTrue(redisQueue instanceof InMemoryNetworkRequestQueue);

        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.openRequestQueue(config("unknown", true, true)));
    }

    @Test
    void openWaitlist_allTransports() {
        assertNull(NetworkBindings.openWaitlist(config("in-memory", true, false), null));

        NetworkWaitlist mem = NetworkBindings.openWaitlist(config("in-memory", true, true), null);
        assertTrue(mem instanceof InMemoryNetworkWaitlist);

        NetworkWaitlist sql = NetworkBindings.openWaitlist(config("sql", true, true), ds);
        assertTrue(sql instanceof InMemoryNetworkWaitlist);

        // Redis fallback
        NetworkWaitlist redisWaitlist = NetworkBindings.openWaitlist(config("redis", true, true), null);
        assertTrue(redisWaitlist instanceof InMemoryNetworkWaitlist);

        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.openWaitlist(config("unknown", true, true), null));
    }

    @Test
    void openLeaderLease_allTransports() {
        assertNull(NetworkBindings.openLeaderLease(config("in-memory", true, false), null));

        WaitlistLeaderLease mem = NetworkBindings.openLeaderLease(config("in-memory", true, true), null);
        assertTrue(mem instanceof AlwaysLeaderLease);

        WaitlistLeaderLease sql = NetworkBindings.openLeaderLease(config("sql", true, true), ds);
        assertTrue(sql instanceof AlwaysLeaderLease);

        // Redis fallback
        WaitlistLeaderLease redisLease = NetworkBindings.openLeaderLease(config("redis", true, true), null);
        assertNotNull(redisLease);

        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.openLeaderLease(config("unknown", true, true), null));
    }

    @Test
    void openOwnershipTracker_allTransports() {
        PlayerOwnershipTracker mem = NetworkBindings.openOwnershipTracker(config("in-memory", true, true));
        assertSame(PlayerOwnershipTracker.NO_OP, mem);

        PlayerOwnershipTracker sql = NetworkBindings.openOwnershipTracker(config("sql", true, true));
        assertSame(PlayerOwnershipTracker.NO_OP, sql);

        // Redis fallback
        PlayerOwnershipTracker redisTracker = NetworkBindings.openOwnershipTracker(config("redis", true, true));
        assertSame(PlayerOwnershipTracker.NO_OP, redisTracker);

        assertThrows(IllegalArgumentException.class, () -> NetworkBindings.openOwnershipTracker(config("unknown", true, true)));
    }
}
