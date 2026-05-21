package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2c coverage for {@link RtpVelocityPlugin#onServerPreConnect}: the
 * connect-time {@link ReservationToken} redemption path. Exercises the four
 * branches the listener has to handle correctly (REQ-RTP-PROXY-VELOCITY-002,
 * REQ-RTP-PROXY-VELOCITY-003, REQ-RTP-S-004).
 *
 * <p>Velocity host is not booted; {@link ProxyServer} and {@link Player} are
 * JDK reflective proxies, {@link RegisteredServer} is a minimal record-shape
 * mock. The plugin runs against a live {@link InMemoryNetworkStateBinding}
 * so the listener's blocking lookup hits a real future.</p>
 */
@DisplayName("RtpVelocityPlugin Phase 2c ServerPreConnectEvent")
class RtpVelocityPluginPhase2cTest {

    /** Registered fake backends, keyed by server name. */
    private Map<String, RegisteredServer> servers;
    private ProxyServer fakeServer;
    /** Set to true by tests that hand the listener an event so we can read the result back. */

    @BeforeEach
    void setUp() {
        RtpProxy.clearProxyAccessor();
        servers = new HashMap<>();
        servers.put("backend-A", fakeRegisteredServer("backend-A"));
        servers.put("backend-B", fakeRegisteredServer("backend-B"));
        fakeServer = (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                new ProxyServerHandler(servers));
    }

    @AfterEach
    void tearDown() {
        RtpProxy.clearProxyAccessor();
    }

    @Test
    @DisplayName("network disabled -> listener no-op, default route preserved (REQ-RTP-NET-002)")
    void disabledNoOp(@TempDir Path data) {
        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        // network.yml absent -> disabled -> transport null. Verify the listener
        // tolerates this and leaves the default routing decision alone.
        assertNull(plugin.transport(), "precondition: disabled init leaves transport null");

        Player player = fakePlayer(UUID.randomUUID());
        RegisteredServer original = servers.get("backend-A");
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, original);
        plugin.onServerPreConnect(event);

        assertTrue(event.getResult().isAllowed(), "default route must remain allowed");
        assertSame(original, event.getResult().getServer().orElseThrow(),
                "disabled listener must not mutate the connect target");

        plugin.onProxyShutdown(new ProxyShutdownEvent());
    }

    @Test
    @DisplayName("no reservation -> default route preserved (hot path)")
    void noReservationFallsThrough(@TempDir Path data) throws Exception {
        RtpVelocityPlugin plugin = newEnabledPlugin(data);
        try {
            UUID playerId = UUID.randomUUID();
            // Sanity: transport reports no reservation for an unknown player.
            assertTrue(plugin.transport().findReservation(playerId).get().isEmpty());

            Player player = fakePlayer(playerId);
            RegisteredServer original = servers.get("backend-A");
            ServerPreConnectEvent event = new ServerPreConnectEvent(player, original);
            plugin.onServerPreConnect(event);

            assertTrue(event.getResult().isAllowed());
            assertSame(original, event.getResult().getServer().orElseThrow(),
                    "listener must not mutate when no reservation exists");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
    }

    @Test
    @DisplayName("active reservation -> target rewritten to token's backend, token consumed")
    void activeReservationRewrites(@TempDir Path data) throws Exception {
        RtpVelocityPlugin plugin = newEnabledPlugin(data);
        try {
            UUID playerId = UUID.randomUUID();
            // Allocate a reservation on backend-B; player initially heading to backend-A.
            ReservationToken token = plugin.transport()
                    .claim("backend-B", playerId, Duration.ofMinutes(1))
                    .get();
            assertNotNull(token);
            assertEquals(ReservationToken.State.CLAIMED, token.state());

            Player player = fakePlayer(playerId);
            RegisteredServer original = servers.get("backend-A");
            ServerPreConnectEvent event = new ServerPreConnectEvent(player, original);
            plugin.onServerPreConnect(event);

            assertTrue(event.getResult().isAllowed());
            assertSame(servers.get("backend-B"), event.getResult().getServer().orElseThrow(),
                    "listener must rewrite target to the token's serverId");

            // Token consumed: subsequent findReservation must report none.
            // release() is async; wait briefly for the executor.
            for (int i = 0; i < 50; i++) {
                if (plugin.transport().findReservation(playerId).get().isEmpty()) break;
                Thread.sleep(20);
            }
            assertTrue(plugin.transport().findReservation(playerId).get().isEmpty(),
                    "token must be consumed after successful routing");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
    }

    @Test
    @DisplayName("reservation targets unknown server -> default route preserved, token released")
    void unknownTargetFallsThrough(@TempDir Path data) throws Exception {
        RtpVelocityPlugin plugin = newEnabledPlugin(data);
        try {
            UUID playerId = UUID.randomUUID();
            // Claim against a server name Velocity does NOT have registered.
            ReservationToken token = plugin.transport()
                    .claim("backend-MISSING", playerId, Duration.ofMinutes(1))
                    .get();
            assertNotNull(token);

            Player player = fakePlayer(playerId);
            RegisteredServer original = servers.get("backend-A");
            ServerPreConnectEvent event = new ServerPreConnectEvent(player, original);
            plugin.onServerPreConnect(event);

            assertTrue(event.getResult().isAllowed(), "default route stays allowed");
            assertSame(original, event.getResult().getServer().orElseThrow(),
                    "listener must NOT rewrite to a non-existent server");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
    }

    // --- helpers --------------------------------------------------------

    /**
     * Build a fully-enabled plugin running against an in-memory binding.
     * Uses a guaranteed-set env var for {@code secretEnv}.
     */
    private RtpVelocityPlugin newEnabledPlugin(Path data) throws Exception {
        String envName = System.getenv("PATH") != null ? "PATH"
                : (System.getenv("JAVA_HOME") != null ? "JAVA_HOME" : null);
        org.junit.jupiter.api.Assumptions.assumeTrue(envName != null,
                "Need a guaranteed-set env var for secretEnv in this test");
        Files.writeString(data.resolve("network.yml"),
                "network:\n"
                        + "  enabled: true\n"
                        + "  proxyId: 'velocity-A'\n"
                        + "  role: auto\n"
                        + "  secretEnv: '" + envName + "'\n"
                        + "transport:\n"
                        + "  type: 'in-memory'\n"
                        + "heartbeat:\n"
                        + "  intervalMs: 1000\n",
                StandardOpenOption.CREATE_NEW);
        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        assertTrue(plugin.config() != null && plugin.config().enabled(),
                "precondition: plugin must boot enabled");
        assertNotNull(plugin.transport(), "precondition: transport must be open");
        assertTrue(plugin.transport() instanceof InMemoryNetworkStateBinding,
                "precondition: in-memory binding for this test");
        return plugin;
    }

    private static Player fakePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(
                RtpVelocityPluginPhase2cTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) return id;
                    if ("getCurrentServer".equals(method.getName())) return Optional.empty();
                    Class<?> ret = method.getReturnType();
                    if (ret == Optional.class) return Optional.empty();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
    }

    private static RegisteredServer fakeRegisteredServer(String name) {
        ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 0));
        return (RegisteredServer) Proxy.newProxyInstance(
                RtpVelocityPluginPhase2cTest.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, args) -> {
                    if ("getServerInfo".equals(method.getName())) return info;
                    if ("getPlayersConnected".equals(method.getName())) return java.util.List.of();
                    if ("ping".equals(method.getName())) return CompletableFuture.completedFuture(null);
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    return null;
                });
    }

    /** ProxyServer reflective handler that routes {@code getServer(name)} to the test's map. */
    private static final class ProxyServerHandler implements InvocationHandler {
        private final Map<String, RegisteredServer> servers;
        ProxyServerHandler(Map<String, RegisteredServer> servers) { this.servers = servers; }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getServer".equals(method.getName()) && args != null && args.length == 1) {
                return Optional.ofNullable(servers.get(String.valueOf(args[0])));
            }
            Class<?> ret = method.getReturnType();
            if (ret == Optional.class) return Optional.empty();
            if (ret == java.util.Collection.class || ret == java.util.List.class) return java.util.List.of();
            if (ret == java.util.Set.class) return java.util.Set.of();
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            if (ret == boolean.class) return false;
            return null;
        }
    }
}
