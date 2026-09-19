package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

class RedisNetworkStateBindingUnitTest {

    private JedisPool pool;
    private Jedis jedis;
    private Jedis subJedis;
    private RedisNetworkStateBinding binding;

    @BeforeEach
    void setUp() {
        pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        subJedis = mock(Jedis.class);
        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        }).when(subJedis).subscribe(any(), anyString());
        when(pool.getResource()).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().startsWith("rtp-redis-sub-")) {
                return subJedis;
            }
            return jedis;
        });
        when(jedis.scriptLoad(anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(0);
            for (String name : List.of("claim", "release", "reap", "redeem")) {
                RedisLuaScripts s = RedisLuaScripts.load(name);
                if (s.body().equals(script)) {
                    return s.sha1();
                }
            }
            return RedisLuaScripts.load("claim").sha1();
        });
        binding = new RedisNetworkStateBinding(pool, 1000L, null, 1);
    }

    @AfterEach
    void tearDown() {
        if (binding != null) {
            binding.close();
        }
    }

    @Test
    void ctor_invalidHeartbeatInterval_throws() {
        assertThrows(IllegalArgumentException.class, () -> new RedisNetworkStateBinding(pool, 0L, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisNetworkStateBinding(pool, -10L, null, 1));
    }

    @Test
    void publishProxyHeartbeat_writesHsetAndExpire() throws Exception {
        ProxyHeartbeat row = new ProxyHeartbeat("proxy-1", 1, System.currentTimeMillis(), 10, 2);
        binding.publishProxyHeartbeat(row).get();

        verify(jedis).hset(eq("rtp:net:proxy:proxy-1"), any(Map.class));
        verify(jedis).expire(eq("rtp:net:proxy:proxy-1"), eq(3L));
    }

    @Test
    void publishBackendHeartbeat_writesHsetExpireAndPublishes() throws Exception {
        BackendHeartbeat row = new BackendHeartbeat(
                "srv-1", 1, BackendHeartbeat.PluginState.READY, true, System.currentTimeMillis(),
                5, 0, 20, 0, 0, 0, List.of("w1"), List.of("r1"));
        binding.publishBackendHeartbeat(row).get();

        verify(jedis).hset(eq("rtp:net:backend:srv-1"), any(Map.class));
        verify(jedis).expire(eq("rtp:net:backend:srv-1"), eq(3L));
        verify(jedis).publish(eq("rtp:net:backend"), anyString());
    }

    @Test
    void readSnapshot_scansAndDecodesRows() throws Exception {
        ScanResult<String> scanResult = new ScanResult<>(ScanParams.SCAN_POINTER_START, List.of("rtp:net:backend:srv-1"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class))).thenReturn(scanResult);

        Map<String, String> hash = new java.util.HashMap<>();
        hash.put("serverId", "srv-1");
        hash.put("schemaVersion", "1");
        hash.put("pluginState", "READY");
        hash.put("online", "true");
        hash.put("lastHeartbeatEpochMs", String.valueOf(System.currentTimeMillis()));
        hash.put("totalRegions", "1");
        hash.put("unreservedCachedLocations", "5");
        hash.put("tps", "20.0");
        hash.put("activeReservations", "0");
        hash.put("totalReservationsServed", "0");
        hash.put("loadScore", "0");
        hash.put("availableWorlds", "w1");
        hash.put("availableRegions", "r1");
        when(jedis.hgetAll("rtp:net:backend:srv-1")).thenReturn(hash);

        NetworkSnapshot snapshot = binding.readSnapshot().get();
        assertNotNull(snapshot);
        assertTrue(snapshot.backend("srv-1").isPresent());
        assertEquals("srv-1", snapshot.backend("srv-1").get().serverId());
    }

    @Test
    void subscribeBackendHeartbeats_returnsCancellableSubscription() {
        Subscription sub = binding.subscribeBackendHeartbeats(hb -> {});
        assertNotNull(sub);
        assertFalse(sub.isClosed());
        sub.close();
        assertTrue(sub.isClosed());
    }

    @Test
    void claim_evalsScriptAndReturnsToken() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(1L);

        ReservationToken token = binding.claim("srv-1", pid, Duration.ofSeconds(60), Optional.of("reg-1")).get();
        assertNotNull(token);
        assertEquals(pid, token.playerId());
        assertEquals("srv-1", token.serverId());
        assertEquals(Optional.of("reg-1"), token.regionKey());
    }

    @Test
    void claim_evalsScriptRejected_throwsExecutionException() {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(0L);

        assertThrows(ExecutionException.class, () -> binding.claim("srv-1", pid, Duration.ofSeconds(60), Optional.empty()).get());
    }

    @Test
    void release_callsReleaseScript() throws Exception {
        binding.release("tok-1", ReleaseReason.TTL_EXPIRED).get();
        verify(jedis).evalsha(anyString(), any(List.class), any(List.class));
    }

    @Test
    void redeem_callsRedeemScript() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("REDEEMED");
        RedeemOutcome outcome = binding.redeem("tok-1", pid, "srv-1").get();
        assertEquals(RedeemOutcome.REDEEMED, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("ALREADY_CONSUMED");
        outcome = binding.redeem("tok-1", pid, "srv-1").get();
        assertEquals(RedeemOutcome.ALREADY_CONSUMED, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("WRONG_SERVER");
        outcome = binding.redeem("tok-1", pid, "srv-1").get();
        assertEquals(RedeemOutcome.WRONG_SERVER, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("NOT_FOUND");
        outcome = binding.redeem("tok-1", pid, "srv-1").get();
        assertEquals(RedeemOutcome.NOT_FOUND, outcome);

        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn("UNKNOWN_LUA_RETURN");
        outcome = binding.redeem("tok-1", pid, "srv-1").get();
        assertEquals(RedeemOutcome.BAD_STATE, outcome);
    }

    @Test
    void findReservation_resolvesFromActiveIndex() throws Exception {
        UUID pid = UUID.randomUUID();
        when(jedis.get("rtp:net:tokactive:" + pid)).thenReturn("tok-1");
        Map<String, String> tokHash = Map.of(
                "tokenId", "tok-1",
                "serverId", "srv-1",
                "playerId", pid.toString(),
                "expiresAtMs", String.valueOf(System.currentTimeMillis() + 60000L),
                "state", "CLAIMED",
                "regionKey", "r1"
        );
        when(jedis.hgetAll("rtp:net:tok:tok-1")).thenReturn(tokHash);

        Optional<ReservationToken> res = binding.findReservation(pid).get();
        assertTrue(res.isPresent());
        assertEquals("tok-1", res.get().tokenId());
    }

    @Test
    void listActiveForServer_scansAndFilters() throws Exception {
        ScanResult<String> scanResult = new ScanResult<>(ScanParams.SCAN_POINTER_START, List.of("rtp:net:tok:tok-1"));
        when(jedis.scan(eq(ScanParams.SCAN_POINTER_START), any(ScanParams.class))).thenReturn(scanResult);

        UUID pid = UUID.randomUUID();
        Map<String, String> tokHash = Map.of(
                "tokenId", "tok-1",
                "serverId", "srv-1",
                "playerId", pid.toString(),
                "expiresAtMs", String.valueOf(System.currentTimeMillis() + 60000L),
                "state", "CLAIMED"
        );
        when(jedis.hgetAll("rtp:net:tok:tok-1")).thenReturn(tokHash);

        List<ReservationToken> tokens = binding.listActiveForServer("srv-1").get();
        assertEquals(1, tokens.size());
        assertEquals("tok-1", tokens.get(0).tokenId());
    }

    @Test
    void reapExpired_callsReapScript() throws Exception {
        when(jedis.evalsha(anyString(), any(List.class), any(List.class))).thenReturn(List.of("tok-1"));
        List<String> reaped = binding.reapExpired(java.time.Instant.now()).get();
        assertEquals(1, reaped.size());
        assertEquals("tok-1", reaped.get(0));
    }

    @Test
    void closedBinding_rejectsOperations() {
        binding.close();
        assertThrows(IllegalStateException.class, () -> binding.publishProxyHeartbeat(new ProxyHeartbeat("p", 1, 0, 0, 0)));
        assertThrows(IllegalStateException.class, () -> binding.publishBackendHeartbeat(new BackendHeartbeat("s", 1, BackendHeartbeat.PluginState.READY, true, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of())));
        assertThrows(IllegalStateException.class, () -> binding.readSnapshot());
        assertThrows(IllegalStateException.class, () -> binding.claim("s", UUID.randomUUID(), Duration.ofSeconds(60), Optional.empty()));
        assertThrows(IllegalStateException.class, () -> binding.release("t", ReleaseReason.TTL_EXPIRED));
        assertThrows(IllegalStateException.class, () -> binding.redeem("t", UUID.randomUUID(), "s"));
        assertThrows(IllegalStateException.class, () -> binding.findReservation(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> binding.listActiveForServer("s"));
        assertThrows(IllegalStateException.class, () -> binding.reapExpired(java.time.Instant.now()));
    }
}
