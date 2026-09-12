package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers-backed integration test for {@link RedisPlayerOwnershipTracker}
 * (rtp-proxy-ADR-016). Exercises the claim (SET EX), {@code ownerOf} read,
 * compare-and-DEL release (which must not clobber a peer's fresh tag), argument
 * validation, and closed-instance rejection against a real Redis.
 *
 * <p>Backed by a Testcontainers-managed {@code redis:7-alpine} (item 17 of
 * ENTERPRISE_READINESS.md: use a real Redis, not mocks). Docker-gated via
 * {@link RedisTestContainer#dockerAvailable()} so a Docker-less build skips the
 * class cleanly. Cleanup scrubs only the {@code rtp:net:owner:*} keyspace.</p>
 */
@EnabledIf("io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisTestContainer#dockerAvailable")
class RedisPlayerOwnershipTrackerIT {

    private JedisPool pool;
    private RedisPlayerOwnershipTracker tracker;

    private static void scrubKeyspace(JedisPool p) {
        try (Jedis j = p.getResource()) {
            Set<String> keys = j.keys("rtp:net:owner:*");
            if (keys != null && !keys.isEmpty()) {
                j.del(keys.toArray(new String[0]));
            }
        }
    }

    @BeforeEach
    void open() {
        pool = RedisTestContainer.newPool();
        scrubKeyspace(pool);
        tracker = new RedisPlayerOwnershipTracker(pool);
    }

    @AfterEach
    void close() {
        try {
            if (tracker != null) tracker.close();
        } finally {
            if (pool != null) {
                try {
                    scrubKeyspace(pool);
                } finally {
                    pool.close();
                }
            }
        }
    }

    @Test
    void claim_then_ownerOf_roundTrip() throws Exception {
        UUID pid = UUID.randomUUID();
        tracker.claim(pid, "proxy-A", 30).get(5, TimeUnit.SECONDS);
        assertEquals("proxy-A", tracker.ownerOf(pid).get(5, TimeUnit.SECONDS));
    }

    @Test
    void ownerOf_unknown_isEmptyString() throws Exception {
        assertEquals("", tracker.ownerOf(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
    }

    @Test
    void release_bySelfHolder_removesTag() throws Exception {
        UUID pid = UUID.randomUUID();
        tracker.claim(pid, "proxy-A", 30).get(5, TimeUnit.SECONDS);
        tracker.release(pid, "proxy-A").get(5, TimeUnit.SECONDS);
        assertEquals("", tracker.ownerOf(pid).get(5, TimeUnit.SECONDS));
    }

    @Test
    void release_byStaleHolder_doesNotClobberSuccessor() throws Exception {
        UUID pid = UUID.randomUUID();
        tracker.claim(pid, "proxy-B", 30).get(5, TimeUnit.SECONDS);
        // proxy-A never owned it; its compare-and-DEL must be a no-op.
        tracker.release(pid, "proxy-A").get(5, TimeUnit.SECONDS);
        assertEquals("proxy-B", tracker.ownerOf(pid).get(5, TimeUnit.SECONDS),
                "stale holder's release must not delete the current owner's tag");
    }

    @Test
    void claim_reject_emptyProxyId() {
        UUID pid = UUID.randomUUID();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> tracker.claim(pid, "", 30).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void claim_reject_nonPositiveTtl() {
        UUID pid = UUID.randomUUID();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> tracker.claim(pid, "proxy-A", 0).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void afterClose_operationsRejected() {
        tracker.close();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> tracker.ownerOf(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        // close() is idempotent.
        tracker.close();
    }

    @Test
    void ownedPool_ctor_pingsAndWorks() throws Exception {
        try (RedisPlayerOwnershipTracker owned =
                     new RedisPlayerOwnershipTracker(RedisTestContainer.host(),
                             RedisTestContainer.port(), null)) {
            UUID pid = UUID.randomUUID();
            owned.claim(pid, "proxy-C", 30).get(5, TimeUnit.SECONDS);
            assertEquals("proxy-C", owned.ownerOf(pid).get(5, TimeUnit.SECONDS));
        }
    }
}
