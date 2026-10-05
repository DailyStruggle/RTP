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
 * Validates {@link NetworkConfig#fromMap} against ADR-002 section Validation Rules
 * Role-auto resolution via {@link RTPProxyAccessor},
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
    void emptyProxyIdDefaultsToNonEmptyWhenResolvedToProxy() {
        // proxyId is optional: an empty value on a proxy role defaults to the
        // local hostname (never empty / null) rather than failing fast.
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).put("proxyId", "");
        NetworkConfig cfg = NetworkConfig.fromMap(root, velocityAccessor("p1"));
        assertNotNull(cfg.proxyId());
        assertFalse(cfg.proxyId().isEmpty(), "proxyId must default to a non-empty value");
    }

    @Test
    void missingProxyIdDefaultsToNonEmptyWhenResolvedToProxy() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).remove("proxyId");
        NetworkConfig cfg = NetworkConfig.fromMap(root, velocityAccessor("p1"));
        assertNotNull(cfg.proxyId());
        assertFalse(cfg.proxyId().isEmpty(), "proxyId must default to a non-empty value");
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
    @org.junit.jupiter.api.DisplayName("REQ-RTP-PROXY-007: a set but non-Base64 / weak secret fails closed (not just a non-empty check)")
    void secretEnvSetButInvalidWhileEnabledFailsClosed() {
        Map<String, Object> root = minimalDisabled();
        ((Map<String, Object>) root.get("network")).put("enabled", true);
        // PATH is always set and never a >= 32-byte Base64 secret.
        ((Map<String, Object>) root.get("network")).put("secretEnv", "PATH");
        for (String type : new String[]{"redis", "sql", "proxy-direct"}) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", type);
            root.put("transport", t);
            NetworkConfigException ex = assertThrows(NetworkConfigException.class,
                    () -> NetworkConfig.fromMap(root, velocityAccessor("p1")), type);
            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("disabled"),
                    "message must state network mode is disabled: " + ex.getMessage());
        }
        // The proxy-direct listener on a JVM-local transport also needs the real secret.
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "in-memory");
        t.put("direct", new LinkedHashMap<>(Map.of("enabled", true)));
        root.put("transport", t);
        assertThrows(NetworkConfigException.class, () -> NetworkConfig.fromMap(root, velocityAccessor("p1")));

        // Non-signing JVM-local tier keeps the presence-only check.
        root.put("transport", new LinkedHashMap<>(Map.of("type", "in-memory")));
        org.junit.jupiter.api.Assertions.assertTrue(NetworkConfig.fromMap(root, velocityAccessor("p1")).enabled());
    }

    private static Map<String, Object> withRedis(Map<String, Object> redis) {
        Map<String, Object> root = minimalDisabled();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "redis");
        t.put("redis", redis);
        root.put("transport", t);
        return root;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("REQ-RTP-PROXY-007: redis tls + username compose a secret-free rediss:// host for RespPool")
    void redisTlsAndUsernameComposeUri() {
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", "redis.internal");
        redis.put("port", 6380);
        redis.put("tls", true);
        redis.put("username", "rtp");
        NetworkConfig cfg = NetworkConfig.fromMap(withRedis(redis), velocityAccessor("p1"), k -> null);
        assertEquals("rediss://rtp@redis.internal:6380", cfg.redisHost());
        assertEquals(6380, cfg.redisPort());
        org.junit.jupiter.api.Assertions.assertTrue(cfg.redisTls());
        assertEquals("rtp", cfg.redisUsername());
    }

    @Test
    void redisUrlFormHostAccepted_andPlainHostUnchanged() {
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", "rediss://acl-user@10.0.0.5:6390");
        NetworkConfig cfg = NetworkConfig.fromMap(withRedis(redis), velocityAccessor("p1"), k -> null);
        org.junit.jupiter.api.Assertions.assertTrue(cfg.redisTls());
        assertEquals("acl-user", cfg.redisUsername());
        assertEquals(6390, cfg.redisPort());

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("host", "localhost");
        NetworkConfig p = NetworkConfig.fromMap(withRedis(plain), velocityAccessor("p1"), k -> null);
        assertEquals("localhost", p.redisHost());
        assertFalse(p.redisTls());
    }

    @Test
    void redisUrlWithEmbeddedPasswordRejected() {
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", "rediss://user:hunter2@redis.internal:6380");
        NetworkConfigException ex = assertThrows(NetworkConfigException.class,
                () -> NetworkConfig.fromMap(withRedis(redis), velocityAccessor("p1"), k -> null));
        assertFalse(ex.getMessage().contains("hunter2"), "secret must not be echoed: " + ex.getMessage());
    }

    @Test
    void redisPasswordEnvPreferredOverYaml() {
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", "localhost");
        redis.put("password", "from-yaml");
        redis.put("passwordEnv", "RTP_TEST_REDIS_PW");
        NetworkConfig fromEnv = NetworkConfig.fromMap(withRedis(redis), velocityAccessor("p1"),
                k -> k.equals("RTP_TEST_REDIS_PW") ? "from-env" : null);
        assertEquals("from-env", fromEnv.redisPassword());

        NetworkConfig fallback = NetworkConfig.fromMap(withRedis(redis), velocityAccessor("p1"), k -> null);
        assertEquals("from-yaml", fallback.redisPassword());
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
