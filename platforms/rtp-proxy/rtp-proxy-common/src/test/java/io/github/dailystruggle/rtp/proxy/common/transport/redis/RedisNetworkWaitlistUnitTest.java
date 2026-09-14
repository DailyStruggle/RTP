package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.WaitEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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

    private JedisPool pool;
    private Jedis jedis;
    private RedisNetworkWaitlist waitlist;

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

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("REJECTED_DUPLICATE");
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED_DUPLICATE, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("REJECTED_FULL");
        outcome = waitlist.enrol(env).get();
        assertEquals(EnrolOutcome.REJECTED_FULL, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("OTHER");
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
}
