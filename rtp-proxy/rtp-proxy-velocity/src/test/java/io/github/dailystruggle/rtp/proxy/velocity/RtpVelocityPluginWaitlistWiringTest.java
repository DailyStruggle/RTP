package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import io.github.dailystruggle.rtp.proxy.common.dispatch.NetworkWaitlistDrainer;
import io.github.dailystruggle.rtp.proxy.common.dispatch.RequestQueueStatusSink;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.AlwaysLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkWaitlist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-015 / REQ-RTP-NET-015 wiring smoke test for {@link RtpVelocityPlugin}.
 * Confirms that with {@code network.waitlist.enabled = true} the plugin
 * constructs and exposes:
 *
 * <ul>
 *   <li>a non-null {@link NetworkWaitlist} ({@link InMemoryNetworkWaitlist}
 *       on {@code transport.type: in-memory});</li>
 *   <li>a non-null {@link WaitlistLeaderLease} ({@link AlwaysLeaderLease}
 *       on in-memory);</li>
 *   <li>a non-null {@link NetworkWaitlistDrainer} and scheduled drainer
 *       task;</li>
 *   <li>a non-null reaper task;</li>
 *   <li>a non-null {@link VelocityWaitlistQuitListener};</li>
 * </ul>
 *
 * <p>And that with {@code network.waitlist.enabled = false} every one of the
 * above is {@code null}, preserving the pre-Slice-2 dispatcher path
 * byte-for-byte.</p>
 *
 * <p>Velocity host is not booted; {@link ProxyServer} and its event manager
 * are JDK reflective proxies, matching the pattern in
 * {@code RtpVelocityPluginPhase2cTest}.</p>
 */
@DisplayName("RtpVelocityPlugin waitlist wiring (ADR-015)")
class RtpVelocityPluginWaitlistWiringTest {

    private ProxyServer fakeServer;

    @BeforeEach
    void setUp() {
        RtpProxy.clearProxyAccessor();
        fakeServer = (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, args) -> defaultProxyMethod(method));
    }

    @AfterEach
    void tearDown() {
        RtpProxy.clearProxyAccessor();
    }

    @Test
    @DisplayName("waitlist.enabled=true wires waitlist + lease + drainer + reaper + quit listener")
    void enabledWiresFullStack(@TempDir Path data) throws Exception {
        writeNetworkYaml(data, /* waitlistEnabled */ true);

        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        try {
            assertTrue(plugin.config() != null && plugin.config().enabled(),
                    "precondition: plugin must boot enabled");
            assertNotNull(plugin.transport(), "transport must be open");

            assertNotNull(plugin.waitlist(), "waitlist must be constructed when enabled");
            assertInstanceOf(InMemoryNetworkWaitlist.class, plugin.waitlist(),
                    "transport=in-memory must yield InMemoryNetworkWaitlist");

            assertNotNull(plugin.leaderLease(), "leader lease must be constructed when enabled");
            assertInstanceOf(AlwaysLeaderLease.class, plugin.leaderLease(),
                    "transport=in-memory must yield AlwaysLeaderLease");

            assertNotNull(plugin.waitlistDrainer(), "drainer must be constructed");
            assertNotNull(plugin.waitlistDrainerTask(), "drainer task must be scheduled");
            assertNotNull(plugin.waitlistReaperTask(), "reaper task must be scheduled");
            assertNotNull(plugin.waitlistQuitListener(), "quit listener must be constructed");

            assertNotNull(plugin.dispatcher(), "dispatcher must be constructed");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }

        // Post-shutdown idempotency: a second shutdown call must be a no-op.
        plugin.onProxyShutdown(new ProxyShutdownEvent());
    }

    @Test
    @DisplayName("waitlist.enabled=false preserves the pre-Slice-2 dispatcher path")
    void disabledLeavesWaitlistNull(@TempDir Path data) throws Exception {
        writeNetworkYaml(data, /* waitlistEnabled */ false);

        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        try {
            assertTrue(plugin.config() != null && plugin.config().enabled(),
                    "precondition: plugin must boot enabled");
            assertNotNull(plugin.transport(), "transport must be open");

            assertNull(plugin.waitlist(), "waitlist must be null when disabled");
            assertNull(plugin.leaderLease(), "lease must be null when disabled");
            assertNull(plugin.waitlistDrainer(), "drainer must be null when disabled");
            assertNull(plugin.waitlistDrainerTask(), "drainer task must be null when disabled");
            assertNull(plugin.waitlistReaperTask(), "reaper task must be null when disabled");
            assertNull(plugin.waitlistQuitListener(), "quit listener must be null when disabled");

            assertNotNull(plugin.dispatcher(),
                    "dispatcher is still constructed (pre-Slice-2 path) when waitlist disabled");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
    }

    @Test
    @DisplayName("RequestQueueStatusSink is wired when both waitlist + request queue exist")
    void statusSinkIsRequestQueueBackedWhenWaitlistEnabled(@TempDir Path data) throws Exception {
        writeNetworkYaml(data, /* waitlistEnabled */ true);

        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        try {
            assertNotNull(plugin.requestQueue(), "precondition: request queue must be open");
            assertNotNull(plugin.waitlist(), "precondition: waitlist must be open");

            // Reflect into the dispatcher to confirm its statusSink is a
            // RequestQueueStatusSink, not StatusSink.NO_OP.
            Object dispatcher = plugin.dispatcher();
            assertNotNull(dispatcher);
            java.lang.reflect.Field f = dispatcher.getClass().getDeclaredField("statusSink");
            f.setAccessible(true);
            Object sink = f.get(dispatcher);
            assertInstanceOf(RequestQueueStatusSink.class, sink,
                    "dispatcher must hold a RequestQueueStatusSink when waitlist + queue are wired");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
    }

    // --- helpers --------------------------------------------------------

    private static void writeNetworkYaml(Path data, boolean waitlistEnabled) throws Exception {
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
                        + "  waitlist:\n"
                        + "    enabled: " + waitlistEnabled + "\n"
                        + "    maxSize: 32\n"
                        + "    drainIntervalMs: 50\n"
                        + "    drainPauseMs: 100\n"
                        + "    leaderLeaseHoldMs: 500\n"
                        + "    reapIntervalMs: 500\n"
                        + "    reapMaxAgeMs: 60000\n"
                        + "transport:\n"
                        + "  type: 'in-memory'\n"
                        + "heartbeat:\n"
                        + "  intervalMs: 1000\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Defaults for any reflective {@link ProxyServer} call that isn't
     * specifically intercepted. {@code getEventManager()} returns a no-op
     * proxy so {@code register(...)} does not NPE during init.
     */
    private Object defaultProxyMethod(Method method) {
        if ("getEventManager".equals(method.getName())) {
            return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{com.velocitypowered.api.event.EventManager.class},
                    (p, m, a) -> defaultEventManagerMethod(m));
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

    private static Object defaultEventManagerMethod(Method method) {
        // register(...) returns void; everything else just returns null/defaults.
        Class<?> ret = method.getReturnType();
        if (ret == Optional.class) return Optional.empty();
        if (ret == boolean.class) return false;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        return null;
    }
}
