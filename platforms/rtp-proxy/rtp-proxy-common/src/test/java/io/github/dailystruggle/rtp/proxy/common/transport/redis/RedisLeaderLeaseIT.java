package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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
 * <p>Backed by a Testcontainers-managed {@code redis:7-alpine} (item 17 of
 * ENTERPRISE_READINESS.md: use a real Redis, not mocks). Docker-gated via
 * {@link RedisTestContainer#dockerAvailable()} so a Docker-less build skips the
 * class cleanly. Cleanup scrubs only the test-scoped key.</p>
 */
@EnabledIf("io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisTestContainer#dockerAvailable")
class RedisLeaderLeaseIT {

    private static final String TEST_KEY = "rtp:net:waitlist:leader:test:" + UUID.randomUUID();

    private JedisPool pool;

    private void scrub() {
        try (Jedis j = pool.getResource()) {
            j.del(TEST_KEY);
        }
    }

    @BeforeEach
    void open() {
        pool = RedisTestContainer.newPool();
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
        RedisLeaderLease one = new RedisLeaderLease(RedisTestContainer.host(), RedisTestContainer.port(), null);
        RedisLeaderLease two = new RedisLeaderLease(RedisTestContainer.host(), RedisTestContainer.port(), null);
        try {
            assertNotEquals(one.holderId(), two.holderId());
            assertEquals(RedisLeaderLease.DEFAULT_KEY, one.key());
        } finally {
            one.close();
            two.close();
        }
    }
}
