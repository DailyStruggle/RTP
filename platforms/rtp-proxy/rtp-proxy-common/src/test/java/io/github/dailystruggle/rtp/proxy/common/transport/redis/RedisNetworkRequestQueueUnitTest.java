package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueStatus;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisNetworkRequestQueueUnitTest {

    private JedisPool pool;
    private Jedis jedis;
    private RedisNetworkRequestQueue queue;

    @BeforeEach
    void setUp() {
        pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        });
        queue = new RedisNetworkRequestQueue(pool, 60);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void ctor_negativeTtl_throws() {
        assertThrows(IllegalArgumentException.class, () -> new RedisNetworkRequestQueue(pool, -1));
    }

    @Test
    void enrol_validEnvelope_evalsEnqueueScript() throws Exception {
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        EnrolmentEnvelope env = new EnrolmentEnvelope(pid, cid, Optional.of("reg1"), Optional.of("srv1"), System.currentTimeMillis());

        EnrolOutcome outcome = queue.enrol(env).get();
        assertEquals(EnrolOutcome.ACCEPTED, outcome);
        verify(jedis).evalsha(anyString(), any(List.class), any(List.class));
    }

    @Test
    void flushPending_emptyList_returnsAcceptedImmediately() throws Exception {
        EnrolOutcome outcome = queue.flushPending(Collections.emptyList()).get();
        assertEquals(EnrolOutcome.ACCEPTED, outcome);
    }

    @Test
    void pollStatus_returnsStatusFromScript() throws Exception {
        UUID pid = UUID.randomUUID();
        // Script returns: List of rows, where each row is [pid, state, QUEUED, positionInQueue, 1, serverId, srv1, regionKey, reg1, updatedAtMs, 12345678]
        List<Object> row = Arrays.asList(
                pid.toString(),
                "state", "QUEUED",
                "positionInQueue", "1",
                "serverId", "srv1",
                "regionKey", "reg1",
                "updatedAtMs", "12345678"
        );
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(List.of(row));

        List<QueueStatus> statuses = queue.pollStatus(List.of(pid)).get();
        assertEquals(1, statuses.size());
        assertEquals(QueueState.QUEUED, statuses.get(0).state());
        assertEquals(1, statuses.get(0).positionInQueue());
    }

    @Test
    void pollStatus_notFound_returnsEmpty() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(Collections.emptyList());

        List<QueueStatus> statuses = queue.pollStatus(List.of(pid)).get();
        assertTrue(statuses.isEmpty());
    }

    @Test
    void dequeueReady_returnsEnvelope() throws Exception {
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        // Script returns alternating KV list: [playerId, pid, correlationId, cid, regionKey, reg1, serverHint, srv1, createdAtMs, 1234, dequeuedAtMs, 1234]
        List<Object> scriptRes = Arrays.asList(
                "playerId", pid.toString(),
                "correlationId", cid.toString(),
                "regionKey", "reg1",
                "serverHint", "srv1",
                "createdAtMs", "12345678",
                "dequeuedAtMs", "12345678"
        );
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(scriptRes);

        Optional<QueueEnvelope> dequeued = queue.dequeueReady(java.time.Duration.ofMillis(100)).get();
        assertTrue(dequeued.isPresent());
        assertEquals(cid, dequeued.get().correlationId());
        assertEquals(pid, dequeued.get().playerId());
    }

    @Test
    void transition_dispatchesScript() throws Exception {
        UUID pid = UUID.randomUUID();
        queue.transition(pid, QueueState.ROUTING, Optional.empty()).get();
        verify(jedis).evalsha(anyString(), any(List.class), any(List.class));
    }

    @Test
    void cancel_dispatchesTransitionAndLrem() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(Collections.emptyList());
        queue.cancel(pid, io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason.EXPLICIT_REQUEST).get();
        verify(jedis).evalsha(anyString(), any(List.class), any(List.class));
    }

    @Test
    void closedQueue_rejectsOperations() {
        queue.close();
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        EnrolmentEnvelope env = new EnrolmentEnvelope(pid, cid, Optional.empty(), Optional.empty(), System.currentTimeMillis());

        assertThrows(ExecutionException.class, () -> queue.enrol(env).get());
        assertThrows(ExecutionException.class, () -> queue.flushPending(List.of(env)).get());
        assertThrows(ExecutionException.class, () -> queue.pollStatus(List.of(pid)).get());
        assertThrows(ExecutionException.class, () -> queue.dequeueReady(java.time.Duration.ZERO).get());
        assertThrows(ExecutionException.class, () -> queue.transition(pid, QueueState.FAILED, Optional.of("err")).get());
        assertThrows(ExecutionException.class, () -> queue.cancel(pid, io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason.EXPLICIT_REQUEST).get());
    }
}
