package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checklist row 7f: {@link RtpVelocityPlugin#onProxyShutdown(ProxyShutdownEvent)}
 * must be idempotent - a second invocation is a clean no-op.
 *
 * <p>Also exercises the disabled-config shutdown path (network.yml absent),
 * which is the most common in-test shape and therefore the most important
 * to keep crash-free.</p>
 */
@DisplayName("RtpVelocityPlugin shutdown idempotence (L1 row 7f)")
class RtpVelocityPluginShutdownIdempotenceTest {

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
    @DisplayName("Two shutdowns in a row on a disabled plugin do not throw")
    void disabledShutdownIsIdempotent(@TempDir Path data) {
        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());

        assertDoesNotThrow(() -> plugin.onProxyShutdown(new ProxyShutdownEvent()),
                "first shutdown must not throw");
        assertDoesNotThrow(() -> plugin.onProxyShutdown(new ProxyShutdownEvent()),
                "second shutdown must be a clean no-op");

        assertFalse(RtpProxy.isRegistered(),
                "accessor slot must remain cleared after the second shutdown");
    }

    @Test
    @DisplayName("Two shutdowns in a row on an enabled plugin do not throw")
    void enabledShutdownIsIdempotent(@TempDir Path data) throws Exception {
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
                        + "  queue:\n"
                        + "    workerThreads: 1\n"
                        + "    pollIntervalMs: 200\n"
                        + "transport:\n"
                        + "  type: 'in-memory'\n"
                        + "heartbeat:\n"
                        + "  intervalMs: 1000\n",
                StandardOpenOption.CREATE_NEW);

        RtpVelocityPlugin plugin = new RtpVelocityPlugin(
                fakeServer, LoggerFactory.getLogger("test"), data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());

        // The Brigadier /rtp command is not registered on the proxy;
        // the new transport-driven trigger source must be running.
        org.junit.jupiter.api.Assertions.assertNotNull(plugin.requestTriggerSource(),
                "TransportRequestTriggerSource must be wired on enabled init");
        org.junit.jupiter.api.Assertions.assertTrue(plugin.requestTriggerSource().isRunning(),
                "TransportRequestTriggerSource must be running before shutdown");

        assertDoesNotThrow(() -> plugin.onProxyShutdown(new ProxyShutdownEvent()),
                "first shutdown must not throw");
        org.junit.jupiter.api.Assertions.assertFalse(plugin.requestTriggerSource().isRunning(),
                "TransportRequestTriggerSource must be stopped after the first shutdown");
        assertDoesNotThrow(() -> plugin.onProxyShutdown(new ProxyShutdownEvent()),
                "second shutdown on enabled plugin must be a clean no-op");

        assertFalse(RtpProxy.isRegistered());
    }

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
