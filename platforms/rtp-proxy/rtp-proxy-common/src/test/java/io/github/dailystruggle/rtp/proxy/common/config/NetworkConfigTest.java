package io.github.dailystruggle.rtp.proxy.common.config;

import io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor;
import io.github.dailystruggle.rtp.proxy.common.Role;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates {@link NetworkConfig#fromMap} against ADR-002 §Validation Rules
 * (Phase 2b subset): role-auto resolution via {@link RTPProxyAccessor},
 * proxyId fail-fast when role resolves to a proxy, secretEnv check only when
 * enabled, defaults preserved.
 */
class NetworkConfigTest {

    private static RTPProxyAccessor velocityAccessor(String proxyId) {
        return new RTPProxyAccessor() {
            @Override public Role role() { return Role.PROXY_VELOCITY; }
            @Override public String proxyId() { return proxyId; }
            @Override public void sendMessage(UUID p, String m) { }
            @Override public CompletableFuture<Void> transferPlayer(UUID p, String s) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static Map<String, Object> minimalDisabled() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("enabled", false);
        network.put("role", "auto");
        network.put("proxyId", "p1");
        root.put("network", network);
        return root;
    }

    @Test
    void disabledDefaultsAcceptedWithProxyId() {
        NetworkConfig cfg = NetworkConfig.fromMap(minimalDisabled(), velocityAccessor("p1"));
        assertFalse(cfg.enabled());
        assertEquals(Role.PROXY_VELOCITY, cfg.role());
        assertEquals("p1", cfg.proxyId());
        assertEquals("in-memory", cfg.transportType());
        assertEquals(1000L, cfg.heartbeatIntervalMs());
        assertEquals(5000L, cfg.heartbeatStaleAfterMs());
        assertEquals(1, cfg.schemaVersion());
    }

    @Test
    void roleAutoResolvesViaAccessor() {
        NetworkConfig cfg = NetworkConfig.fromMap(minimalDisabled(), velocityAccessor("p1"));
        assertEquals(Role.PROXY_VELOCITY, cfg.role());
    }

    @Test
    void emptyProxyIdFailsFastWhenResolvedToProxy() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).put("proxyId", "");
        NetworkConfigException ex = assertThrows(NetworkConfigException.class,
                () -> NetworkConfig.fromMap(root, velocityAccessor("p1")));
        assertNotNull(ex.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("proxyId"),
                "message must name proxyId: " + ex.getMessage());
    }

    @Test
    void missingProxyIdFailsFastWhenResolvedToProxy() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).remove("proxyId");
        assertThrows(NetworkConfigException.class,
                () -> NetworkConfig.fromMap(root, velocityAccessor("p1")));
    }

    @Test
    void explicitRoleProxyAgreesWithAccessor() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).put("role", "proxy");
        NetworkConfig cfg = NetworkConfig.fromMap(root, velocityAccessor("p1"));
        assertEquals(Role.PROXY_VELOCITY, cfg.role());
    }

    @Test
    void unrecognisedRoleRejected() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).put("role", "ringmaster");
        assertThrows(NetworkConfigException.class,
                () -> NetworkConfig.fromMap(root, velocityAccessor("p1")));
    }

    @Test
    void secretEnvUnsetWhileDisabledIgnored() {
        // enabled=false skips secretEnv resolution; this must not throw even
        // for a guaranteed-absent env var name.
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network"))
                .put("secretEnv", "RTP_TEST_DEFINITELY_UNSET_" + UUID.randomUUID().toString().replace('-', '_'));
        NetworkConfig cfg = NetworkConfig.fromMap(root, velocityAccessor("p1"));
        assertFalse(cfg.enabled());
    }

    @Test
    void secretEnvUnsetWhileEnabledFailsFast() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).put("enabled", true);
        ((Map<String, Object>) root.get("network"))
                .put("secretEnv", "RTP_TEST_DEFINITELY_UNSET_" + UUID.randomUUID().toString().replace('-', '_'));
        NetworkConfigException ex = assertThrows(NetworkConfigException.class,
                () -> NetworkConfig.fromMap(root, velocityAccessor("p1")));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("secretEnv"),
                "message must name secretEnv: " + ex.getMessage());
    }

    @Test
    void customHeartbeatIntervalsHonored() {
        Map<String, Object> root = minimalDisabled();
        Map<String, Object> hb = new LinkedHashMap<>();
        hb.put("intervalMs", 250L);
        hb.put("staleAfterMs", 2000L);
        root.put("heartbeat", hb);
        NetworkConfig cfg = NetworkConfig.fromMap(root, velocityAccessor("p1"));
        assertEquals(250L, cfg.heartbeatIntervalMs());
        assertEquals(2000L, cfg.heartbeatStaleAfterMs());
    }

    @Test
    void transportTypeOverridable() {
        Map<String, Object> root = minimalDisabled();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "redis");
        root.put("transport", t);
        NetworkConfig cfg = NetworkConfig.fromMap(root, velocityAccessor("p1"));
        assertEquals("redis", cfg.transportType());
    }
}
