package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live-Redis integration test for {@link RedisLeaderLease}.
 * Verifies the {@code SET NX PX} acquire
 * path, idempotent {@code SET XX PX} re-extend by the same holder, mutual
 * exclusion between two holders, compare-and-delete release semantics, and
 * TTL-expiry handover.
 *
 * <p>Gated by {@code RTP_REDIS_IT=true}; CI/local default builds skip this
 * class entirely. To run locally:</p>
 * <pre>
 *   docker run --rm -p 6379:6379 redis:7-alpine
 *   $env:RTP_REDIS_IT = "true"
 *   .\gradlew :rtp-proxy:rtp-proxy-common:test --tests "*RedisLeaderLeaseIT*"
 * </pre>
 *
 * <p>Connection target is {@code 127.0.0.1:6379} (override via
 * {@code RTP_REDIS_IT_HOST} / {@code RTP_REDIS_IT_PORT} /
 * {@code RTP_REDIS_IT_PASSWORD}). Cleanup scrubs only the test-scoped key so
 * the suite never touches unrelated data on a shared dev Redis.</p>
 */
@EnabledIfEnvironmentVariable(named = "RTP_REDIS_IT", matches = "true")
class RedisLeaderLeaseIT {

    private static final String TEST_KEY = "rtp:net:waitlist:leader:test:" + UUID.randomUUID();

    private JedisPool pool;

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private void scrub() {
        try (Jedis j = pool.getResource()) {
            j.del(TEST_KEY);
        }
    }

    @BeforeEach
    void open() {
        String host = envOr("RTP_REDIS_IT_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOr("RTP_REDIS_IT_PORT", "6379"));
        String password = System.getenv("RTP_REDIS_IT_PASSWORD");
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        cfg.setMaxIdle(2);
        pool = (password == null || password.isEmpty())
                ? new JedisPool(cfg, host, port, 2000)
                : new JedisPool(cfg, host, port, 2000, password);
        scrub();
    }

    @AfterEach
    void closeAll() {
        scrub();
        pool.close();
    }

    @Test
    void tryAcquire_setsKeyToHolderId_whenUncontested() throws Exception {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A")) {
            Boolean ok = a.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS);
            assertTrue(ok);
            try (Jedis j = pool.getResource()) {
                assertEquals("holder-A", j.get(TEST_KEY));
                Long ttl = j.pttl(TEST_KEY);
                assertTrue(ttl > 0 && ttl <= 5000, "PTTL should be in (0, 5000]: " + ttl);
            }
        }
    }

    @Test
    void tryAcquire_returnsFalse_whenAnotherHolderHasIt() throws Exception {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A");
             RedisLeaderLease b = new RedisLeaderLease(pool, TEST_KEY, "holder-B")) {
            assertTrue(a.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS));
            Boolean bGot = b.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS);
            assertFalse(bGot);
            try (Jedis j = pool.getResource()) {
                assertEquals("holder-A", j.get(TEST_KEY));
            }
        }
    }

    @Test
    void tryAcquire_isIdempotent_forSameHolder() throws Exception {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A")) {
            assertTrue(a.tryAcquire(Duration.ofMillis(800)).get(2, TimeUnit.SECONDS));
            // Re-acquire by same holder extends rather than failing.
            assertTrue(a.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS));
            try (Jedis j = pool.getResource()) {
                Long ttl = j.pttl(TEST_KEY);
                assertTrue(ttl > 1000, "Re-acquire should have extended TTL > 1000ms: " + ttl);
            }
        }
    }

    @Test
    void release_deletesKey_whenSelfHolder() throws Exception {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A")) {
            assertTrue(a.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS));
            a.release().get(2, TimeUnit.SECONDS);
            try (Jedis j = pool.getResource()) {
                assertNull(j.get(TEST_KEY));
            }
        }
    }

    @Test
    void release_doesNotStomp_aSuccessorsLease() throws Exception {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A");
             RedisLeaderLease b = new RedisLeaderLease(pool, TEST_KEY, "holder-B")) {
            // A grabs a very short lease, lets it expire.
            assertTrue(a.tryAcquire(Duration.ofMillis(150)).get(2, TimeUnit.SECONDS));
            Thread.sleep(250);
            // B now wins the next probe.
            assertTrue(b.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS));
            // A (the original holder) calls release. Must NOT delete B's lease.
            a.release().get(2, TimeUnit.SECONDS);
            try (Jedis j = pool.getResource()) {
                assertEquals("holder-B", j.get(TEST_KEY),
                        "Stale holder's release must not delete the successor's lease");
            }
        }
    }

    @Test
    void ttlExpiry_allowsHandoverToNextProbe() throws Exception {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A");
             RedisLeaderLease b = new RedisLeaderLease(pool, TEST_KEY, "holder-B")) {
            assertTrue(a.tryAcquire(Duration.ofMillis(120)).get(2, TimeUnit.SECONDS));
            assertFalse(b.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertTrue(b.tryAcquire(Duration.ofSeconds(5)).get(2, TimeUnit.SECONDS));
            try (Jedis j = pool.getResource()) {
                assertEquals("holder-B", j.get(TEST_KEY));
            }
        }
    }

    @Test
    void tryAcquire_rejectsZeroOrNegativeHoldFor() {
        try (RedisLeaderLease a = new RedisLeaderLease(pool, TEST_KEY, "holder-A")) {
            assertTrue(a.tryAcquire(Duration.ZERO).isCompletedExceptionally());
            assertTrue(a.tryAcquire(Duration.ofMillis(-1)).isCompletedExceptionally());
        }
    }

    @Test
    void distinctHolderIds_areGenerated_byPublicCtor() {
        RedisLeaderLease one = new RedisLeaderLease("127.0.0.1", 6379, null);
        RedisLeaderLease two = new RedisLeaderLease("127.0.0.1", 6379, null);
        try {
            assertNotEquals(one.holderId(), two.holderId());
            assertEquals(RedisLeaderLease.DEFAULT_KEY, one.key());
        } finally {
            one.close();
            two.close();
        }
    }
}
