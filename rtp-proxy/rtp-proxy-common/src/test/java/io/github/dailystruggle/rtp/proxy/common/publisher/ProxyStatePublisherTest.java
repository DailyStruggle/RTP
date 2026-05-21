package io.github.dailystruggle.rtp.proxy.common.publisher;

import io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor;
import io.github.dailystruggle.rtp.proxy.common.Role;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2b skeleton coverage for {@link ProxyStatePublisher}:
 * payload assembly via the registered {@link RTPProxyAccessor}, kill-switch
 * default false (ADR-010), cadence start/stop idempotency, and routing into
 * the bound transport.
 */
class ProxyStatePublisherTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        RtpProxy.clearProxyAccessor();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-state-publisher-test");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        RtpProxy.clearProxyAccessor();
        scheduler.shutdownNow();
    }

    private static RTPProxyAccessor accessor(String proxyId) {
        return new RTPProxyAccessor() {
            @Override public Role role() { return Role.PROXY_VELOCITY; }
            @Override public String proxyId() { return proxyId; }
            @Override public void sendMessage(UUID p, String m) { }
            @Override public CompletableFuture<Void> transferPlayer(UUID p, String s) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static NetworkConfig disabledConfig(String proxyId) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("enabled", false);
        network.put("role", "auto");
        network.put("proxyId", proxyId);
        root.put("network", network);
        return NetworkConfig.fromMap(root, accessor(proxyId));
    }

    @Test
    void publishOnceAttributesProxyAndSetsKillSwitchFalse() {
        RtpProxy.setProxyAccessor(accessor("p1"));
        InMemoryNetworkStateBinding t = new InMemoryNetworkStateBinding();
        ProxyStatePublisher pub = new ProxyStatePublisher(t, disabledConfig("p1"), scheduler);

        pub.publishOnce().join();

        Map<String, ProxyHeartbeat> rows = t.dumpProxies();
        ProxyHeartbeat row = rows.get("p1");
        assertNotNull(row, "heartbeat row must be published for p1");
        assertEquals("p1", row.proxyId());
        assertEquals(1, row.schemaVersion());
        assertFalse(row.killSwitch(), "killSwitch defaults to false per ADR-010");
        assertEquals(0, row.inFlightRequests());
    }

    @Test
    void publishOnceReflectsInFlightRequestsCounter() {
        RtpProxy.setProxyAccessor(accessor("p1"));
        InMemoryNetworkStateBinding t = new InMemoryNetworkStateBinding();
        ProxyStatePublisher pub = new ProxyStatePublisher(t, disabledConfig("p1"), scheduler);

        pub.inFlightRequests().set(7);
        pub.publishOnce().join();

        assertEquals(7, t.dumpProxies().get("p1").inFlightRequests());
    }

    @Test
    void startStopIsIdempotent() throws Exception {
        RtpProxy.setProxyAccessor(accessor("p1"));
        InMemoryNetworkStateBinding t = new InMemoryNetworkStateBinding();
        // Force a fast cadence so the test does not have to wait the default 1s.
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("enabled", false);
        network.put("role", "auto");
        network.put("proxyId", "p1");
        Map<String, Object> hb = new LinkedHashMap<>();
        hb.put("intervalMs", 25L);
        root.put("network", network);
        root.put("heartbeat", hb);
        NetworkConfig cfg = NetworkConfig.fromMap(root, accessor("p1"));

        ProxyStatePublisher pub = new ProxyStatePublisher(t, cfg, scheduler);
        pub.start();
        pub.start(); // second call is a no-op
        assertTrue(pub.isRunning());

        // Wait for at least one fire.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && t.dumpProxies().get("p1") == null) {
            Thread.sleep(10);
        }
        assertNotNull(t.dumpProxies().get("p1"), "publisher must fire at least once within 2s");

        pub.stop();
        pub.stop(); // idempotent
        assertFalse(pub.isRunning());
    }

    @Test
    void publishWithoutRegisteredAccessorThrowsIllegalState() {
        // RtpProxy slot is left clear by @BeforeEach.
        InMemoryNetworkStateBinding t = new InMemoryNetworkStateBinding();
        ProxyStatePublisher pub = new ProxyStatePublisher(t, disabledConfig("p1"), scheduler);
        // disabledConfig() registered nothing (its accessor was a local), so the
        // static slot is null and publishOnce must surface the S-006 failure.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> pub.publishOnce());
    }
}
