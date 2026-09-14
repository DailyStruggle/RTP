package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLeaderLeaseUnitTest {

    private JedisPool pool;
    private Jedis jedis;
    private RedisLeaderLease lease;

    @BeforeEach
    void setUp() {
        pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        lease = new RedisLeaderLease(pool, "test:key", "holder-123");
    }

    @AfterEach
    void tearDown() {
        if (lease != null) {
            lease.close();
        }
    }

    @Test
    void tryAcquire_acquiredFreshLease_returnsTrue() throws Exception {
        when(jedis.set(eq("test:key"), eq("holder-123"), any(SetParams.class))).thenReturn("OK");

        boolean acquired = lease.tryAcquire(Duration.ofSeconds(5)).get();
        assertTrue(acquired);
    }

    @Test
    void tryAcquire_reextendSameHolder_returnsTrue() throws Exception {
        when(jedis.set(eq("test:key"), eq("holder-123"), any(SetParams.class))).thenReturn(null);
        when(jedis.get("test:key")).thenReturn("holder-123");
        when(jedis.set(eq("test:key"), eq("holder-123"), any(SetParams.class))).thenReturn("OK");

        boolean acquired = lease.tryAcquire(Duration.ofSeconds(5)).get();
        assertTrue(acquired);
    }

    @Test
    void tryAcquire_differentHolder_returnsFalse() throws Exception {
        when(jedis.set(eq("test:key"), eq("holder-123"), any(SetParams.class))).thenReturn(null);
        when(jedis.get("test:key")).thenReturn("other-holder");

        boolean acquired = lease.tryAcquire(Duration.ofSeconds(5)).get();
        assertFalse(acquired);
    }

    @Test
    void tryAcquire_nullOrZeroDuration_rejected() {
        assertThrows(NullPointerException.class, () -> lease.tryAcquire(null));
        assertThrows(ExecutionException.class, () -> lease.tryAcquire(Duration.ZERO).get());
        assertThrows(ExecutionException.class, () -> lease.tryAcquire(Duration.ofSeconds(-1)).get());
    }

    @Test
    void release_evalsCompareAndDelete() throws Exception {
        when(jedis.eval(any(String.class), eq(Collections.singletonList("test:key")),
                eq(Collections.singletonList("holder-123")))).thenReturn(1L);

        lease.release().get();

        verify(jedis).eval(any(String.class), eq(Collections.singletonList("test:key")),
                eq(Collections.singletonList("holder-123")));
    }

    @Test
    void closedLease_rejectsOperations() throws Exception {
        lease.close();
        assertFalse(lease.tryAcquire(Duration.ofSeconds(5)).get());
        lease.release().get();
    }
}
