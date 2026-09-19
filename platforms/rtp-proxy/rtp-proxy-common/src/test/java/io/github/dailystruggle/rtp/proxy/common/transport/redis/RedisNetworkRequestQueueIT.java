package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live-Redis integration test for {@link RedisNetworkRequestQueue}
 * Exercises end-to-end script atomicity, correlationId
 * idempotency, LPOS-derived position, and terminal-state cleanup that the
 * no-Redis {@code RedisNetworkRequestQueueTest} cannot reach.
 *
 * <p>Gated by {@code RTP_REDIS_IT=true}; CI/local default builds skip this
 * class entirely. To run locally:</p>
 * <pre>
 *   docker run --rm -p 6379:6379 redis:7-alpine
 *   $env:RTP_REDIS_IT = "true"
 *   .\gradlew :rtp-proxy:rtp-proxy-common:test --tests "*RedisNetworkRequestQueueIT*"
 * </pre>
 *
 * <p>Connection target is {@code 127.0.0.1:6379} (override via
 * {@code RTP_REDIS_IT_HOST} / {@code RTP_REDIS_IT_PORT} / {@code RTP_REDIS_IT_PASSWORD}).
 * Cleanup scrubs only the namespaced {@code rtp:net:wq:*} keyspace so the
 * test never touches unrelated data on a shared dev Redis.</p>
 */
@EnabledIfEnvironmentVariable(named = "RTP_REDIS_IT", matches = "true")
class RedisNetworkRequestQueueIT {

    private JedisPool pool;
    private RedisNetworkRequestQueue queue;

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static void scrubKeyspace(JedisPool p) {
        try (Jedis j = p.getResource()) {
            Set<String> keys = j.keys("rtp:net:wq:*");
            if (keys != null && !keys.isEmpty()) {
                j.del(keys.toArray(new String[0]));
            }
        }
    }

    @BeforeEach
    void open() {
        String host = envOr("RTP_REDIS_IT_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOr("RTP_REDIS_IT_PORT", "6379"));
        String password = System.getenv("RTP_REDIS_IT_PASSWORD"); // null OK
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        cfg.setMaxIdle(2);
        pool = (password == null || password.isEmpty())
                ? new JedisPool(cfg, host, port, 2000)
                : new JedisPool(cfg, host, port, 2000, password);
        scrubKeyspace(pool);
        queue = new RedisNetworkRequestQueue(pool, 0);
    }

    @AfterEach
    void close() {
        try {
            if (queue != null) queue.close();
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

    private static EnrolmentEnvelope env(UUID pid, UUID cid, String region, String hint) {
        return new EnrolmentEnvelope(
                pid,
                cid,
                region == null ? Optional.empty() : Optional.of(region),
                hint == null ? Optional.empty() : Optional.of(hint),
                System.currentTimeMillis());
    }

    @Test
    void enrol_then_dequeue_roundTrip() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        EnrolOutcome o = queue.flushPending(List.of(env(pid, cid, "default", "backend-a")))
                .get(5, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, o);

        Optional<QueueEnvelope> popped =
                queue.dequeueReady(Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);
        assertTrue(popped.isPresent(), "expected dequeue to return the enrolled envelope");
        assertEquals(pid, popped.get().playerId());
        assertEquals(cid, popped.get().correlationId());
        assertEquals(Optional.of("default"), popped.get().regionKey());
        assertEquals(Optional.of("backend-a"), popped.get().serverHint());
    }

    @Test
    void correlationId_idempotent_acrossTwoFlushes() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        EnrolmentEnvelope e = env(pid, cid, "default", null);

        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(List.of(e)).get(5, TimeUnit.SECONDS));
        // Second flush with the same correlationId must NOT double-enqueue.
        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(List.of(e)).get(5, TimeUnit.SECONDS));

        Optional<QueueEnvelope> first =
                queue.dequeueReady(Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);
        assertTrue(first.isPresent(), "first dequeue should return the single enrolled envelope");
        Optional<QueueEnvelope> second =
                queue.dequeueReady(Duration.ofMillis(200)).get(5, TimeUnit.SECONDS);
        assertTrue(second.isEmpty(), "second dequeue must be empty - correlationId guard prevented duplicate");
    }

    @Test
    void pollStatus_reportsPositionInQueue() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        queue.flushPending(Arrays.asList(
                env(p1, UUID.randomUUID(), "default", null),
                env(p2, UUID.randomUUID(), "default", null),
                env(p3, UUID.randomUUID(), "default", null)))
                .get(5, TimeUnit.SECONDS);

        List<QueueStatus> rows =
                queue.pollStatus(Arrays.asList(p1, p2, p3)).get(5, TimeUnit.SECONDS);
        assertNotNull(rows);
        assertEquals(3, rows.size(), "all three enrolled players should have status rows");
        // FIFO order: p1 -> 0, p2 -> 1, p3 -> 2 (LPOS is 0-indexed).
        for (QueueStatus s : rows) {
            if (s.playerId().equals(p1)) assertEquals(0, s.positionInQueue());
            else if (s.playerId().equals(p2)) assertEquals(1, s.positionInQueue());
            else if (s.playerId().equals(p3)) assertEquals(2, s.positionInQueue());
        }
    }

    @Test
    void transition_terminalCompleted_evictsEnvAndStatus() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        queue.flushPending(List.of(env(pid, cid, "default", null))).get(5, TimeUnit.SECONDS);

        // Drain the FIFO so terminal cleanup is the only thing the next pollStatus could see.
        Optional<QueueEnvelope> popped =
                queue.dequeueReady(Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);
        assertTrue(popped.isPresent());

        Optional<QueueStatus> after = queue.transition(
                pid, QueueState.COMPLETED, Optional.of("ok")).get(5, TimeUnit.SECONDS);
        // Terminal transitions DEL env + status HASHes per D3 transition.lua.
        // The returned status may be empty (already deleted) or carry the final state - both are valid.
        if (after.isPresent()) {
            assertEquals(QueueState.COMPLETED, after.get().state());
        }

        // Subsequent pollStatus must not surface the deleted row.
        List<QueueStatus> rows =
                queue.pollStatus(List.of(pid)).get(5, TimeUnit.SECONDS);
        assertTrue(rows.isEmpty(), "terminal transition must evict status row from pollStatus");

        // And the `seen` SET membership for the correlationId must be scrubbed:
        // a fresh flush of the same correlationId should now re-enqueue cleanly.
        EnrolOutcome o = queue.flushPending(List.of(env(pid, cid, "default", null)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, o);
        assertTrue(queue.dequeueReady(Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS).isPresent(),
                "post-terminal re-enrolment of the same correlationId should be visible to dequeue");
    }

    @Test
    void cancel_idempotent() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        queue.flushPending(List.of(env(pid, cid, "default", null))).get(5, TimeUnit.SECONDS);

        queue.cancel(pid, CancelReason.EXPLICIT_REQUEST).get(5, TimeUnit.SECONDS);
        // Second cancel must complete normally (idempotent).
        queue.cancel(pid, CancelReason.EXPLICIT_REQUEST).get(5, TimeUnit.SECONDS);

        // Status should be gone (terminal CANCELLED scrubs env + status).
        List<QueueStatus> rows =
                queue.pollStatus(List.of(pid)).get(5, TimeUnit.SECONDS);
        assertTrue(rows.isEmpty(), "cancel must evict status row");
    }

    @Test
    void dequeueReady_emptyQueue_returnsEmptyAfterTimeout() throws Exception {
        long t0 = System.nanoTime();
        Optional<QueueEnvelope> r =
                queue.dequeueReady(Duration.ofMillis(150)).get(5, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(r.isEmpty(), "empty FIFO must yield empty Optional");
        assertTrue(elapsedMs >= 100,
                "dequeueReady should respect the deadline (>=~150ms); got " + elapsedMs + "ms");
    }

    @Test
    void pollStatus_emptyList_emptyResult() throws Exception {
        List<QueueStatus> rows =
                queue.pollStatus(Collections.emptyList()).get(5, TimeUnit.SECONDS);
        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }
}
