package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.WaitEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live-Redis integration test for {@link RedisNetworkWaitlist}
 * Exercises script
 * atomicity, FIFO drain, point-remove, position lookup, reap, and
 * refreshAllTtl that the no-Redis script-load smoke test cannot reach.
 *
 * <p>Gated by {@code RTP_REDIS_IT=true}; default builds skip this entire
 * class, same gating as the seven pre-existing Redis ITs. To run locally:
 * <pre>
 *   docker run --rm -p 6379:6379 redis:7-alpine
 *   $env:RTP_REDIS_IT = "true"
 *   .\gradlew :rtp-proxy:rtp-proxy-common:test --tests "*RedisNetworkWaitlistIT*"
 * </pre>
 *
 * <p>Connection target: {@code 127.0.0.1:6379} (override via
 * {@code RTP_REDIS_IT_HOST} / {@code RTP_REDIS_IT_PORT} /
 * {@code RTP_REDIS_IT_PASSWORD}). Cleanup scrubs only the
 * {@code rtp:net:waitlist:*} keyspace.</p>
 */
@EnabledIfEnvironmentVariable(named = "RTP_REDIS_IT", matches = "true")
class RedisNetworkWaitlistIT {

    private JedisPool pool;
    private RedisNetworkWaitlist waitlist;

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static void scrubKeyspace(JedisPool p) {
        try (Jedis j = p.getResource()) {
            Set<String> keys = j.keys("rtp:net:waitlist:*");
            if (keys != null && !keys.isEmpty()) {
                j.del(keys.toArray(new String[0]));
            }
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
        scrubKeyspace(pool);
        // maxSize = 16 keeps the FULL test deterministic.
        waitlist = new RedisNetworkWaitlist(pool, 16);
    }

    @AfterEach
    void close() {
        try {
            if (waitlist != null) waitlist.close();
        } finally {
            if (pool != null) {
                try { scrubKeyspace(pool); } finally { pool.close(); }
            }
        }
    }

    private static WaitEnvelope env(UUID pid, UUID cid, String region) {
        return new WaitEnvelope(
                pid, cid,
                region == null ? Optional.empty() : Optional.of(region),
                Optional.empty(),
                "lobby-A",
                System.currentTimeMillis());
    }

    @Test
    void enrol_accepted_and_size_increments() throws Exception {
        EnrolOutcome r = waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), "default"))
                .get(5, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, r);
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
    }

    @Test
    void enrol_duplicate_player_rejected() throws Exception {
        UUID pid = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED,
                waitlist.enrol(env(pid, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.REJECTED_DUPLICATE,
                waitlist.enrol(env(pid, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS));
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
    }

    @Test
    void enrol_correlationId_replay_is_idempotent() throws Exception {
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        WaitEnvelope e1 = env(pid, cid, null);
        assertEquals(EnrolOutcome.ACCEPTED, waitlist.enrol(e1).get(5, TimeUnit.SECONDS));
        // Same correlationId, different player: idempotent ACCEPTED without insert.
        WaitEnvelope e2 = new WaitEnvelope(UUID.randomUUID(), cid,
                Optional.empty(), Optional.empty(), "lobby-A", System.currentTimeMillis());
        assertEquals(EnrolOutcome.ACCEPTED, waitlist.enrol(e2).get(5, TimeUnit.SECONDS));
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
    }

    @Test
    void enrol_rejected_full_at_cap() throws Exception {
        for (int i = 0; i < 16; i++) {
            assertEquals(EnrolOutcome.ACCEPTED,
                    waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), null))
                            .get(5, TimeUnit.SECONDS));
        }
        assertEquals(EnrolOutcome.REJECTED_FULL,
                waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), null))
                        .get(5, TimeUnit.SECONDS));
    }

    @Test
    void position_reports_fifo_rank() throws Exception {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID(), p3 = UUID.randomUUID();
        waitlist.enrol(env(p1, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS);
        waitlist.enrol(env(p2, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS);
        waitlist.enrol(env(p3, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of(1), waitlist.position(p1).get(5, TimeUnit.SECONDS));
        assertEquals(Optional.of(2), waitlist.position(p2).get(5, TimeUnit.SECONDS));
        assertEquals(Optional.of(3), waitlist.position(p3).get(5, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), waitlist.position(UUID.randomUUID())
                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void remove_uuid_point_removes_and_is_idempotent() throws Exception {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        waitlist.enrol(env(p1, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS);
        waitlist.enrol(env(p2, UUID.randomUUID(), null)).get(5, TimeUnit.SECONDS);
        assertTrue(waitlist.remove(p1, CancelReason.PLAYER_DISCONNECT)
                .get(5, TimeUnit.SECONDS));
        assertFalse(waitlist.remove(p1, CancelReason.PLAYER_DISCONNECT)
                .get(5, TimeUnit.SECONDS));
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
        // p2 became rank 1 after the point-remove.
        assertEquals(Optional.of(1), waitlist.position(p2).get(5, TimeUnit.SECONDS));
    }

    @Test
    void drainBatch_fifo_with_per_backend_caps() throws Exception {
        for (int i = 0; i < 5; i++) {
            waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), "default"))
                    .get(5, TimeUnit.SECONDS);
        }
        Map<String, List<WaitEnvelope>> drained = waitlist.drainBatch(
                Map.of("backend-a", 2, "backend-b", 2), 10).get(5, TimeUnit.SECONDS);
        int total = drained.values().stream().mapToInt(List::size).sum();
        assertEquals(4, total);
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
    }

    @Test
    void drainBatch_empty_waitlist_returns_empty() throws Exception {
        Map<String, List<WaitEnvelope>> drained = waitlist.drainBatch(
                Map.of("backend-a", 4), 4).get(5, TimeUnit.SECONDS);
        assertTrue(drained.isEmpty());
    }

    @Test
    void drainBatch_zero_caps_returns_empty() throws Exception {
        waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), null))
                .get(5, TimeUnit.SECONDS);
        Map<String, List<WaitEnvelope>> drained = waitlist.drainBatch(
                Map.of("backend-a", 0), 4).get(5, TimeUnit.SECONDS);
        assertTrue(drained.isEmpty());
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
    }

    @Test
    void reap_removes_only_aged_entries() throws Exception {
        // Hand-craft an envelope with an old enrolledAtMs to avoid sleeping.
        long old = System.currentTimeMillis() - 10_000L;
        UUID pidOld = UUID.randomUUID();
        WaitEnvelope aged = new WaitEnvelope(pidOld, UUID.randomUUID(),
                Optional.empty(), Optional.empty(), "lobby-A", old);
        waitlist.enrol(aged).get(5, TimeUnit.SECONDS);
        waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), null))
                .get(5, TimeUnit.SECONDS);
        int reaped = waitlist.reap(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertEquals(1, reaped);
        assertEquals(Optional.empty(), waitlist.position(pidOld).get(5, TimeUnit.SECONDS));
        assertEquals(1, waitlist.size().get(5, TimeUnit.SECONDS));
    }

    @Test
    void refreshAllTtl_bumps_enrolledAtMs_and_preserves_order() throws Exception {
        long old = System.currentTimeMillis() - 10_000L;
        WaitEnvelope aged1 = new WaitEnvelope(UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), Optional.empty(), "lobby-A", old);
        WaitEnvelope aged2 = new WaitEnvelope(UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), Optional.empty(), "lobby-A", old);
        waitlist.enrol(aged1).get(5, TimeUnit.SECONDS);
        waitlist.enrol(aged2).get(5, TimeUnit.SECONDS);
        int refreshed = waitlist.refreshAllTtl().get(5, TimeUnit.SECONDS);
        assertEquals(2, refreshed);
        // After refresh, a 5s reap finds nothing (everything is fresh).
        int reaped = waitlist.reap(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertEquals(0, reaped);
        assertEquals(2, waitlist.size().get(5, TimeUnit.SECONDS));
        // FIFO preserved.
        assertEquals(Optional.of(1), waitlist.position(aged1.playerId())
                .get(5, TimeUnit.SECONDS));
        assertEquals(Optional.of(2), waitlist.position(aged2.playerId())
                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void cross_instance_visibility() throws Exception {
        waitlist.enrol(env(UUID.randomUUID(), UUID.randomUUID(), null))
                .get(5, TimeUnit.SECONDS);
        // A second client over the same pool sees the parked entry.
        try (RedisNetworkWaitlist other = new RedisNetworkWaitlist(pool, 16)) {
            assertEquals(1, other.size().get(5, TimeUnit.SECONDS));
        }
    }
}
