package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisPlayerOwnershipTrackerUnitTest {

    private JedisPool pool;
    private Jedis jedis;
    private RedisPlayerOwnershipTracker tracker;

    @BeforeEach
    void setUp() {
        pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        tracker = new RedisPlayerOwnershipTracker(pool);
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) {
            tracker.close();
        }
    }

    @Test
    void claim_validArguments_setsKeyInRedis() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.set(eq("rtp:net:owner:" + pid), eq("proxy-1"), any(SetParams.class))).thenReturn("OK");

        tracker.claim(pid, "proxy-1", 30).get();

        verify(jedis).set(eq("rtp:net:owner:" + pid), eq("proxy-1"), any(SetParams.class));
    }

    @Test
    void claim_invalidArguments_failsExceptionally() {
        UUID pid = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> tracker.claim(null, "p1", 10));
        assertThrows(NullPointerException.class, () -> tracker.claim(pid, null, 10));
        assertThrows(ExecutionException.class, () -> tracker.claim(pid, "", 10).get());
        assertThrows(ExecutionException.class, () -> tracker.claim(pid, "p1", 0).get());
        assertThrows(ExecutionException.class, () -> tracker.claim(pid, "p1", -5).get());
    }

    @Test
    void release_validArguments_evalsCasScript() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.eval(any(String.class), eq(Collections.singletonList("rtp:net:owner:" + pid)),
                eq(Collections.singletonList("proxy-1")))).thenReturn(1L);

        tracker.release(pid, "proxy-1").get();

        verify(jedis).eval(any(String.class), eq(Collections.singletonList("rtp:net:owner:" + pid)),
                eq(Collections.singletonList("proxy-1")));
    }

    @Test
    void ownerOf_returnsValueOrEmpty() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.get("rtp:net:owner:" + pid)).thenReturn("proxy-99");
        assertEquals("proxy-99", tracker.ownerOf(pid).get());

        when(jedis.get("rtp:net:owner:" + pid)).thenReturn(null);
        assertEquals("", tracker.ownerOf(pid).get());
    }

    @Test
    void closedTracker_rejectsOperations() {
        tracker.close();
        UUID pid = UUID.randomUUID();
        assertThrows(ExecutionException.class, () -> tracker.claim(pid, "p1", 10).get());
        assertThrows(ExecutionException.class, () -> tracker.release(pid, "p1").get());
        assertThrows(ExecutionException.class, () -> tracker.ownerOf(pid).get());
    }
}
