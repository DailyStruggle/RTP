package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.WaitEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;

import java.time.Duration;
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

class RedisNetworkWaitlistUnitTest {

    private RespPool pool;
    private RespConnection jedis;
    private RedisNetworkWaitlist waitlist;

    @BeforeEach
    void setUp() throws Exception {
        pool = mock(RespPool.class);
        jedis = mock(RespConnection.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.scriptLoad(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        });
        waitlist = new RedisNetworkWaitlist(pool, 100);
    }

    @AfterEach
    void tearDown() {
        if (waitlist != null) {
            waitlist.close();
        }
    }

    @Test
    void ctor_negativeMaxSize_throws() {
        assertThrows(IllegalArgumentException.class, () -> new RedisNetworkWaitlist(pool, -1));
    }

    @Test
    void enrol_evalsScript_returnsOutcome() throws Exception {
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        WaitEnvelope env = new WaitEnvelope(pid, cid, Optional.of("reg1"), Optional.of("srv1"), "origin-1", System.currentTimeMillis());

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("ACCEPTED");
        EnrolOutcome outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.ACCEPTED, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("ACCEPTED_IDEMPOTENT");
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.ACCEPTED, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("REJECTED_DUPLICATE");
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED_DUPLICATE, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("REJECTED_FULL");
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED_FULL, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("OTHER");
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED_FULL, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(null);
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED_FULL, outcome);
    }

    @Test
    void drainBatch_dispatchesScript() throws Exception {
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(Collections.emptyList());
        var res = waitlist.drainBatch(java.util.Map.of("srv1", 1), 1).get();
        assertNotNull(res);
    }

    @Test
    void drainBatch_peekAndDrain_successAndPartial() throws Exception {
        UUID cid1 = UUID.randomUUID();
        UUID pid1 = UUID.randomUUID();
        WaitEnvelope env1 = new WaitEnvelope(pid1, cid1, Optional.of("reg1"), Optional.of("srv1"), "origin-1", System.currentTimeMillis());
        String json1 = RedisNetworkWaitlist.encode(env1);

        UUID cid2 = UUID.randomUUID();
        UUID pid2 = UUID.randomUUID();
        WaitEnvelope env2 = new WaitEnvelope(pid2, cid2, Optional.empty(), Optional.empty(), "origin-2", System.currentTimeMillis());
        String json2 = RedisNetworkWaitlist.encode(env2);

        // Peek returns json1, json2, and a malformed json
        when(jedis.evalsha(anyString(), any(List.class), any(List.class)))
                .thenReturn(List.of(json1, json2, "malformed-json")) // peek
                .thenReturn(List.of(json1, json2)); // drainBatch returns both

        var res = waitlist.drainBatch(java.util.Map.of("srv1", 1, "srv2", 1), 5).get();
        assertEquals(2, res.values().stream().mapToInt(List::size).sum());

        // Partial drain
        when(jedis.evalsha(anyString(), any(List.class), any(List.class)))
                .thenReturn(List.of(json1, json2)) // peek
                .thenReturn(List.of(json1)); // drainBatch returns only json1

        var partialRes = waitlist.drainBatch(java.util.Map.of("srv1", 2), 5).get();
        assertEquals(1, partialRes.values().stream().mapToInt(List::size).sum());
    }

    @Test
    void drainBatch_emptyPeek_returnsEmpty() throws Exception {
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(Collections.emptyList());
        var res = waitlist.drainBatch(java.util.Map.of("srv1", 1), 0).get();
        assertTrue(res.isEmpty());

        var res2 = waitlist.drainBatch(java.util.Map.of("srv1", 0), 5).get();
        assertTrue(res2.isEmpty());
    }

    @Test
    void encodeDecode_jsonEdgeCases() {
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        WaitEnvelope env = new WaitEnvelope(pid, cid, Optional.of("reg\"escaped\\key\n\r\t"), Optional.of("hint"), "origin\u0001", 123456L);
        String json = RedisNetworkWaitlist.encode(env);
        WaitEnvelope decoded = RedisNetworkWaitlist.decode(json);
        assertNotNull(decoded);
        assertEquals(pid, decoded.playerId());
        assertEquals(cid, decoded.correlationId());
        assertEquals(env.regionKey(), decoded.regionKey());

        // Decode malformed returns null
        assertNotNull(RedisNetworkWaitlist.decode(json));
        assertEquals(null, RedisNetworkWaitlist.decode("not a json"));
        assertEquals(null, RedisNetworkWaitlist.decode("{\"playerId\":\"not-uuid\"}"));
        assertEquals(null, RedisNetworkWaitlist.decode("{\"playerId\":\"00000000-0000-0000-0000-000000000000\"}"));
        assertEquals(null, RedisNetworkWaitlist.decode("{\"playerId\":\"00000000-0000-0000-0000-000000000000\",\"correlationId\":\"00000000-0000-0000-0000-000000000001\"}"));
        assertEquals(null, RedisNetworkWaitlist.decode("{\"playerId\":123}"));
        assertEquals(null, RedisNetworkWaitlist.decode("{\"playerId\":\"00000000-0000-0000-0000-000000000000\",\"correlationId\":\"00000000-0000-0000-0000-000000000001\",\"originServerId\":\"s\",\"enrolledAtMs\":\"not-a-number\"}"));
    }

    @Test
    void reap_zeroOrNegative_returnsZeroImmediately() throws Exception {
        assertEquals(0, waitlist.reap(Duration.ZERO).get());
        assertEquals(0, waitlist.reap(Duration.ofSeconds(-10)).get());
    }

    @Test
    void position_returnsRankOrEmpty() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(5L);
        assertEquals(Optional.of(5), waitlist.position(pid).get());

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(-1L);
        assertEquals(Optional.empty(), waitlist.position(pid).get());
    }

    @Test
    void remove_dispatchesScript() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(1L);
        assertTrue(waitlist.remove(pid, CancelReason.EXPLICIT_REQUEST).get());

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(0L);
        assertFalse(waitlist.remove(pid, CancelReason.PLAYER_DISCONNECT).get());
    }

    @Test
    void reap_dispatchesScript() throws Exception {
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(3L);
        int reaped = waitlist.reap(Duration.ofSeconds(60)).get();
        assertEquals(3, reaped);
    }

    @Test
    void refreshAllTtl_dispatchesScript() throws Exception {
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(2L);
        int count = waitlist.refreshAllTtl().get();
        assertEquals(2, count);
    }

    @Test
    void size_queriesRedisLlen() throws Exception {
        when(jedis.llen(anyString())).thenReturn(7L);
        assertEquals(7, waitlist.size().get());
    }

    @Test
    void closedWaitlist_rejectsOperations() {
        waitlist.close();
        waitlist.close(); // idempotent close
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        WaitEnvelope env = new WaitEnvelope(pid, cid, Optional.empty(), Optional.empty(), "origin-1", System.currentTimeMillis());

        assertEquals(EnrolOutcome.REJECTED_CLOSED, waitlist.enrol(env).join());
        assertEquals(Collections.emptyMap(), waitlist.drainBatch(java.util.Map.of("srv1", 1), 1).join());
        assertThrows(Exception.class, () -> waitlist.position(pid).get());
        assertThrows(Exception.class, () -> waitlist.remove(pid, CancelReason.EXPLICIT_REQUEST).get());
        assertThrows(Exception.class, () -> waitlist.reap(Duration.ofSeconds(10)).get());
        assertThrows(Exception.class, () -> waitlist.refreshAllTtl().get());
        assertThrows(Exception.class, () -> waitlist.size().get());
    }

    @Test
    void drainBatch_coercionAndByteArrays() throws Exception {
        UUID cid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        WaitEnvelope env = new WaitEnvelope(pid, cid, Optional.empty(), Optional.empty(), "origin-1", System.currentTimeMillis());
        String json = RedisNetworkWaitlist.encode(env);

        // evalsha returning byte[]
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(jedis.evalsha(anyString(), any(List.class), any(List.class)))
                .thenReturn(Collections.singletonList(bytes)) // peek
                .thenReturn(Collections.singletonList(bytes)); // drain

        var res = waitlist.drainBatch(java.util.Map.of("srv-1", 1), 1).get();
        assertEquals(1, res.size());
    }

    @Test
    void position_coercionsAndNulls() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(null);
        assertEquals(Optional.empty(), waitlist.position(pid).get());

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(2); // Integer
        assertEquals(Optional.of(2), waitlist.position(pid).get());

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("3"); // String
        assertEquals(Optional.of(3), waitlist.position(pid).get());

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("invalid-number");
        assertEquals(Optional.empty(), waitlist.position(pid).get());
    }
}
