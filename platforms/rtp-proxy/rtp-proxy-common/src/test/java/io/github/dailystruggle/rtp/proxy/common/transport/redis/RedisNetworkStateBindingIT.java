package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers-backed integration test for {@link RedisNetworkStateBinding}
 * (rtp-proxy-ADR-005). Exercises the heartbeat publish + {@code SCAN}-based
 * snapshot read, pub/sub backend fan-out, the SHA1-verified Lua
 * claim/redeem/release scripts, {@code findReservation}, {@code listActiveForServer},
 * {@code reapExpired}, and the closed-instance lifecycle guard against a real Redis.
 *
 * <p>Backed by a Testcontainers-managed {@code redis:7-alpine} (item 17 of
 * ENTERPRISE_READINESS.md: use a real Redis, not mocks). Docker-gated via
 * {@link RedisTestContainer#dockerAvailable()} so a Docker-less build skips the
 * class cleanly. Each test opens a fresh binding after flushing the DB.</p>
 */
@EnabledIf("io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisTestContainer#dockerAvailable")
class RedisNetworkStateBindingIT {

    private RedisNetworkStateBinding binding;

    private static void flush() {
        try (JedisPool p = RedisTestContainer.newPool(); Jedis j = p.getResource()) {
            Set<String> keys = j.keys("rtp:net:*");
            if (keys != null && !keys.isEmpty()) {
                j.del(keys.toArray(new String[0]));
            }
        }
    }

    private static BackendHeartbeat backend(String id) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, System.currentTimeMillis(),
                10, 0, 20, 0, 0, 0, List.of(), List.of());
    }

    @BeforeEach
    void open() {
        flush();
        binding = new RedisNetworkStateBinding(
                RedisTestContainer.host(), RedisTestContainer.port(), null, 1000L);
    }

    @AfterEach
    void close() {
        try {
            if (binding != null) binding.close();
        } finally {
            flush();
        }
    }

    @Test
    void publishBackend_thenReadSnapshot_seesRow() throws Exception {
        binding.publishBackendHeartbeat(backend("srv-a")).get(5, TimeUnit.SECONDS);
        NetworkSnapshot snap = binding.readSnapshot().get(5, TimeUnit.SECONDS);
        assertEquals(1, snap.all().size());
        assertEquals("srv-a", snap.backend("srv-a").orElseThrow().serverId());
    }

    @Test
    void publishProxyHeartbeat_doesNotAppearInBackendSnapshot() throws Exception {
        binding.publishProxyHeartbeat(new ProxyHeartbeat("proxy-1", 1,
                System.currentTimeMillis(), 5, 0)).get(5, TimeUnit.SECONDS);
        NetworkSnapshot snap = binding.readSnapshot().get(5, TimeUnit.SECONDS);
        assertTrue(snap.all().isEmpty(), "proxy rows are not backend rows");
    }

    @Test
    void subscribeBackendHeartbeats_receivesPublishedRow() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BackendHeartbeat> got = new AtomicReference<>();
        Subscription sub = binding.subscribeBackendHeartbeats(hb -> {
            got.set(hb);
            latch.countDown();
        });
        // Poll-publish until the subscriber thread has connected and delivers.
        for (int i = 0; i < 20 && latch.getCount() > 0; i++) {
            binding.publishBackendHeartbeat(backend("srv-sub")).get(5, TimeUnit.SECONDS);
            if (latch.await(250, TimeUnit.MILLISECONDS)) break;
        }
        assertTrue(latch.getCount() == 0, "subscriber should receive a fan-out heartbeat");
        assertNotNull(got.get());
        assertEquals("srv-sub", got.get().serverId());
        sub.close();
        assertTrue(sub.isClosed());
    }

    @Test
    void claim_yieldsClaimedToken_andFindReservationResolvesIt() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("srv-a", player, Duration.ofSeconds(30))
                .get(5, TimeUnit.SECONDS);
        assertNotNull(token);
        assertEquals(ReservationToken.State.CLAIMED, token.state());
        assertEquals("srv-a", token.serverId());
        assertEquals(player, token.playerId());

        Optional<ReservationToken> found = binding.findReservation(player).get(5, TimeUnit.SECONDS);
        assertTrue(found.isPresent());
        assertEquals(token.tokenId(), found.get().tokenId());

        List<ReservationToken> active =
                binding.listActiveForServer("srv-a").get(5, TimeUnit.SECONDS);
        assertEquals(1, active.size());
        assertEquals(token.tokenId(), active.get(0).tokenId());
    }

    @Test
    void redeem_transitionsClaimedToConsumed_thenIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("srv-a", player, Duration.ofSeconds(30))
                .get(5, TimeUnit.SECONDS);

        RedeemOutcome first = binding.redeem(token.tokenId(), player, "srv-a")
                .get(5, TimeUnit.SECONDS);
        assertEquals(RedeemOutcome.REDEEMED, first);

        RedeemOutcome second = binding.redeem(token.tokenId(), player, "srv-a")
                .get(5, TimeUnit.SECONDS);
        assertEquals(RedeemOutcome.ALREADY_CONSUMED, second);
    }

    @Test
    void redeem_wrongServer_isRejected() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("srv-a", player, Duration.ofSeconds(30))
                .get(5, TimeUnit.SECONDS);
        RedeemOutcome outcome = binding.redeem(token.tokenId(), player, "srv-b")
                .get(5, TimeUnit.SECONDS);
        assertEquals(RedeemOutcome.WRONG_SERVER, outcome);
    }

    @Test
    void redeem_unknownToken_isNotFound() throws Exception {
        RedeemOutcome outcome = binding.redeem("does-not-exist", UUID.randomUUID(), "srv-a")
                .get(5, TimeUnit.SECONDS);
        assertEquals(RedeemOutcome.NOT_FOUND, outcome);
    }

    @Test
    void release_isIdempotent_andClearsReservation() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("srv-a", player, Duration.ofSeconds(30))
                .get(5, TimeUnit.SECONDS);
        binding.release(token.tokenId(), ReleaseReason.CONSUMED).get(5, TimeUnit.SECONDS);
        // Re-release of a terminal / missing token is a no-op.
        binding.release(token.tokenId(), ReleaseReason.OPERATOR).get(5, TimeUnit.SECONDS);
        assertTrue(binding.findReservation(player).get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void reapExpired_returnsTokensPastTtl() throws Exception {
        UUID player = UUID.randomUUID();
        binding.claim("srv-a", player, Duration.ofMillis(200)).get(5, TimeUnit.SECONDS);
        Thread.sleep(400);
        List<String> reaped = binding.reapExpired(Instant.now()).get(5, TimeUnit.SECONDS);
        assertFalse(reaped.isEmpty(), "an expired token should be reaped");
    }

    @Test
    void close_isTerminal_rejectsFurtherCalls() {
        binding.close();
        assertThrows(IllegalStateException.class, () -> binding.readSnapshot());
        // close() is idempotent.
        binding.close();
    }

    @Test
    void construct_rejectsNonPositiveHeartbeatInterval() {
        assertThrows(IllegalArgumentException.class, () -> new RedisNetworkStateBinding(
                RedisTestContainer.host(), RedisTestContainer.port(), null, 0L));
    }
}
