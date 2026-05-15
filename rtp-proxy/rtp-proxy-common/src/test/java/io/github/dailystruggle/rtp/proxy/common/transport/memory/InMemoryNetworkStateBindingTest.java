package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises rtp-proxy-ADR-003 contracts on
 * {@link InMemoryNetworkStateBinding}: claim atomicity / idempotency
 * (REQ-RTP-PROXY-004), heartbeat fan-out, and snapshot/close lifecycle.
 */
class InMemoryNetworkStateBindingTest {

    private InMemoryNetworkStateBinding binding;

    @BeforeEach
    void setUp() {
        binding = new InMemoryNetworkStateBinding();
    }

    @AfterEach
    void tearDown() {
        binding.close();
    }

    @Test
    @DisplayName("Snapshot reflects published backends (async hop preserved)")
    void snapshotReadsPublishedBackends() throws Exception {
        binding.publishBackendHeartbeat(new BackendHeartbeat(
                "a", 1, PluginState.READY, true, System.currentTimeMillis(),
                10, 0, 20, 0, 0, 0, List.of(), List.of()));
        NetworkSnapshot snap = binding.readSnapshot().get();
        assertEquals(1, snap.all().size());
        assertEquals("a", snap.backend("a").orElseThrow().serverId());
    }

    @Test
    @DisplayName("Single claim succeeds and yields a CLAIMED token (REQ-RTP-PROXY-004)")
    void claimSucceeds() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertNotNull(token);
        assertEquals(ReservationToken.State.CLAIMED, token.state());
        assertEquals("a", token.serverId());
        assertEquals(player, token.playerId());
        assertEquals(1, binding.dumpTokens().size());
    }

    @Test
    @DisplayName("Concurrent claims for the same player: exactly one succeeds")
    void concurrentClaimsAreIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        int n = 16;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futs = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            futs[i] = binding.claim("a", player, Duration.ofSeconds(30))
                    .handle((t, ex) -> {
                        if (ex == null) successes.incrementAndGet();
                        else failures.incrementAndGet();
                        return null;
                    });
        }
        CompletableFuture.allOf(futs).get();
        assertEquals(1, successes.get(), "exactly one claim must win");
        assertEquals(n - 1, failures.get(), "all others must reject");
        assertEquals(1, binding.dumpTokens().size());
    }

    @Test
    @DisplayName("Release frees the slot for a subsequent claim")
    void releaseAllowsReclaim() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken first = binding.claim("a", player, Duration.ofSeconds(30)).get();
        binding.release(first.tokenId(), ReleaseReason.CONSUMED).get();
        ReservationToken second = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertEquals(ReservationToken.State.CLAIMED, second.state());
    }

    @Test
    @DisplayName("Release of unknown token is a no-op (idempotent)")
    void releaseUnknownIsNoop() throws Exception {
        binding.release("nope", ReleaseReason.OPERATOR).get();
        assertEquals(0, binding.dumpTokens().size());
    }

    @Test
    @DisplayName("Subscribers receive heartbeats; cancel stops delivery")
    void subscriberLifecycle() {
        AtomicInteger seen = new AtomicInteger();
        Subscription sub = binding.subscribeBackendHeartbeats(h -> seen.incrementAndGet());
        binding.publishBackendHeartbeat(new BackendHeartbeat(
                "a", 1, PluginState.READY, true, System.currentTimeMillis(),
                10, 0, 20, 0, 0, 0, List.of(), List.of()));
        assertEquals(1, seen.get());
        sub.close();
        assertTrue(sub.isClosed());
        binding.publishBackendHeartbeat(new BackendHeartbeat(
                "b", 1, PluginState.READY, true, System.currentTimeMillis(),
                10, 0, 20, 0, 0, 0, List.of(), List.of()));
        assertEquals(1, seen.get(), "closed subscriber must not be re-notified");
    }

    @Test
    @DisplayName("Close prevents further SPI calls")
    void closeIsTerminal() {
        binding.close();
        assertThrows(IllegalStateException.class,
                () -> binding.readSnapshot());
        // Async path: claim wraps into CompletionException at .get(); skip here.
    }

    @Test
    @DisplayName("Claim race: handled exception is CompletionException wrapping IllegalStateException")
    void claimRaceWrappingShape() {
        UUID player = UUID.randomUUID();
        binding.claim("a", player, Duration.ofSeconds(30)).join();
        CompletionException ex = assertThrows(CompletionException.class,
                () -> binding.claim("a", player, Duration.ofSeconds(30)).join());
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "underlying cause must be IllegalStateException");
    }
}
