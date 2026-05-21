package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.dailystruggle.rtp.proxy.common.Role;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2b participant-skeleton coverage for {@link RtpVelocityPlugin}:
 * accessor registration, no-op behaviour when {@code network.yml} is absent
 * or {@code enabled:false}, transport + publisher wiring when enabled, and
 * clean teardown on shutdown.
 *
 * <p>The Velocity host is not booted; {@link ProxyServer} is a JDK reflective
 * proxy returning empty optionals for everything. Phase 2b only touches the
 * proxy server reference at transfer time, which this test does not exercise.</p>
 */
@DisplayName("RtpVelocityPlugin Phase 2b participant skeleton")
class RtpVelocityPluginPhase2bTest {

    private ProxyServer fakeServer;

    @BeforeEach
    void setUp() {
        RtpProxy.clearProxyAccessor();
        fakeServer = (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                new ReturnEmptyHandler());
    }

    @AfterEach
    void tearDown() {
        RtpProxy.clearProxyAccessor();
    }

    @Test
    @DisplayName("No network.yml present -> disabled, accessor registered, no publisher")
    void absentNetworkYmlYieldsDisabledNoOp(@TempDir Path data) {
        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());

        assertNotNull(plugin.accessor(), "accessor must be registered even when network is absent");
        assertEquals(Role.PROXY_VELOCITY, plugin.accessor().role());
        assertTrue(RtpProxy.isRegistered(), "RtpProxy.proxyAccessor slot must be set BEFORE config load");
        // With no network.yml on disk, fromMap() sees an empty document and fails fast
        // on the (missing) proxyId for the PROXY_VELOCITY role. The plugin must catch
        // the NetworkConfigException and degrade to disabled - config stays null,
        // accessor stays registered, no transport / publisher.
        assertNull(plugin.config(), "absent network.yml degrades to disabled; config left null");
        assertNull(plugin.publisher(), "publisher must not be wired when network is disabled");
        assertNull(plugin.transport(), "transport must not be opened when network is disabled");

        plugin.onProxyShutdown(new ProxyShutdownEvent());
        org.junit.jupiter.api.Assertions.assertFalse(RtpProxy.isRegistered(),
                "shutdown must clear the proxy-accessor slot");
    }

    @Test
    @DisplayName("Disabled network.yml -> accessor registered, no publisher, no transport")
    void explicitlyDisabledStaysNoOp(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve("network.yml"),
                "network:\n"
                        + "  enabled: false\n"
                        + "  proxyId: 'velocity-A'\n"
                        + "  role: auto\n",
                StandardOpenOption.CREATE_NEW);

        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());

        assertEquals("velocity-A", plugin.accessor().proxyId());
        assertEquals("velocity-A", plugin.config().proxyId());
        org.junit.jupiter.api.Assertions.assertFalse(plugin.config().enabled());
        assertNull(plugin.publisher());
        assertNull(plugin.transport());

        plugin.onProxyShutdown(new ProxyShutdownEvent());
    }

    @Test
    @DisplayName("Enabled network.yml + RTP_NET_SECRET set -> publisher started + transport open")
    void enabledWiresPublisherAndTransport(@TempDir Path data) throws Exception {
        // Choose a secretEnv that is always present in the test environment
        // (PATH on Windows; falls back to JAVA_HOME otherwise). This avoids
        // mutating process env vars from a JUnit test.
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
        try {
            plugin.onProxyInitialize(new ProxyInitializeEvent());

            assertTrue(plugin.config().enabled());
            assertNotNull(plugin.transport(), "transport must be opened when enabled");
            assertNotNull(plugin.publisher(), "publisher must be wired when enabled");
            assertTrue(plugin.publisher().isRunning(),
                    "publisher must be running after init");
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
        // After shutdown the slot is cleared and publisher stopped.
        org.junit.jupiter.api.Assertions.assertFalse(RtpProxy.isRegistered());
    }

    @Test
    @DisplayName("Invalid network.yml (proxyId empty) -> disabled, plugin still loads")
    void invalidConfigDegradesToDisabled(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve("network.yml"),
                "network:\n"
                        + "  enabled: false\n"
                        + "  proxyId: ''\n"
                        + "  role: auto\n",
                StandardOpenOption.CREATE_NEW);

        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        // Empty proxyId fails fast in NetworkConfig; plugin should have caught and
        // logged. Config slot stays null but accessor is registered.
        assertNotNull(plugin.accessor());
        assertNull(plugin.config(), "config must be null after fail-fast validation");
        assertNull(plugin.publisher());
        assertNull(plugin.transport());

        plugin.onProxyShutdown(new ProxyShutdownEvent());
    }

    /**
     * Reflective handler that returns default values for any ProxyServer
     * method invoked during Phase 2b init. The init path itself does not call
     * the ProxyServer, but Guice would inject one in production - this lets
     * the test construct the plugin directly.
     */
    private static final class ReturnEmptyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            Class<?> ret = method.getReturnType();
            if (ret == java.util.Optional.class) return java.util.Optional.empty();
            if (ret == java.util.Collection.class || ret == java.util.List.class) return java.util.List.of();
            if (ret == java.util.Set.class) return java.util.Set.of();
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            if (ret == boolean.class) return false;
            return null;
        }
    }
}
