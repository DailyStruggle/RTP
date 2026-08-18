package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reservation-token regression suite covering failure modes: double-redeem
 * rejection, expired-token rejection at lookup time, orphan release via the
 * TTL reaper, and reaper idempotency across repeated passes.
 *
 * <p>Run against {@link InMemoryNetworkStateBinding} because every contract
 * exercised here is SPI-level. SQL-binding parity for the same contracts is
 * covered by {@code SqlNetworkStateBindingH2Test}.</p>
 *
 * <p>Pinned by REQ-RTP-NET-011 (TTL reaper), REQ-RTP-PROXY-004 (atomic
 * state transitions), REQ-RTP-PROXY-VELOCITY-002 (findReservation returns
 * empty on terminal / expired tokens).</p>
 */
class ReservationTokenRegressionTest {

    private InMemoryNetworkStateBinding binding;
    private AtomicReference<Instant> clock;

    @BeforeEach
    void setUp() {
        binding = new InMemoryNetworkStateBinding();
        clock = new AtomicReference<>(Instant.parse("2026-05-19T12:00:00Z"));
        binding.setClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        binding.close();
    }

    @Test
    @DisplayName("Double-redeem: CONSUMED token cannot be redeemed twice; findReservation returns empty")
    void doubleRedeemRejected() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken first = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertNotNull(first);
        assertEquals(ReservationToken.State.CLAIMED, first.state());

        // First redemption: CONSUMED transition + release.
        binding.release(first.tokenId(), ReleaseReason.CONSUMED).get();

        // Second redemption attempt: findReservation must surface no
        // reservation (terminal state filter, REQ-RTP-PROXY-VELOCITY-002).
        Optional<ReservationToken> lookup = binding.findReservation(player).get();
        assertTrue(lookup.isEmpty(),
                "findReservation must not surface a CONSUMED token to the connect-time redeemer");

        // And the player slot must be free for a fresh claim.
        ReservationToken second = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertNotNull(second);
        assertNotEquals(first.tokenId(), second.tokenId(),
                "fresh claim must produce a distinct tokenId");
    }

    @Test
    @DisplayName("Expired token: findReservation returns empty before the reaper sweeps")
    void expiredTokenInvisibleToFindReservation() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertNotNull(token);

        // Advance clock past TTL but do NOT run the reaper.
        clock.set(clock.get().plusSeconds(31));

        Optional<ReservationToken> lookup = binding.findReservation(player).get();
        assertTrue(lookup.isEmpty(),
                "expired token must be invisible to findReservation even before reaper runs");
    }

    @Test
    @DisplayName("Orphan release on TTL: reaper transitions expired token, release(TTL_EXPIRED) dispatched once per winner")
    void orphanReleaseOnTtlViaReaper() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        ReservationToken alive = binding.claim("a", p1, Duration.ofSeconds(120)).get();
        ReservationToken doomed = binding.claim("b", p2, Duration.ofSeconds(30)).get();

        // Doomed expires; alive does not.
        clock.set(clock.get().plusSeconds(60));

        // Drive the reaper end-to-end via reapNow() (synchronous test hook).
        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            int reaped = reaper.reapNow();
            assertEquals(1, reaped, "only the doomed token must be reaped");
            assertEquals(1L, reaper.reapedCount());
        }

        // Doomed slot is free; alive slot is still held.
        assertTrue(binding.findReservation(p2).get().isEmpty(),
                "doomed player slot must be free after reaping");
        Optional<ReservationToken> aliveLookup = binding.findReservation(p1).get();
        assertTrue(aliveLookup.isPresent(),
                "alive token must survive the reaper sweep");
        assertEquals(alive.tokenId(), aliveLookup.orElseThrow().tokenId());

        // Re-claim on the doomed slot succeeds (orphan released).
        ReservationToken reborn = binding.claim("b", p2, Duration.ofSeconds(30)).get();
        assertNotNull(reborn);
        assertNotEquals(doomed.tokenId(), reborn.tokenId());
    }

    @Test
    @DisplayName("Reaper idempotency: a second sweep with nothing to reap reports zero and leaves state untouched")
    void reaperIdempotencyAcrossPasses() throws Exception {
        UUID player = UUID.randomUUID();
        binding.claim("a", player, Duration.ofSeconds(30)).get();
        clock.set(clock.get().plusSeconds(60));

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            assertEquals(1, reaper.reapNow(), "first sweep must reap the one expired token");
            assertEquals(0, reaper.reapNow(), "second sweep must be a no-op (no expired survivors)");
            assertEquals(0, reaper.reapNow(), "third sweep must remain a no-op (idempotent)");
            assertEquals(1L, reaper.reapedCount(), "lifetime count records the single winner");
        }
    }

    @Test
    @DisplayName("Replay safety: concurrent release calls for the same token settle to a single terminal transition")
    void concurrentReleaseIsIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();

        int n = 16;
        AtomicInteger failures = new AtomicInteger();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futs = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            futs[i] = binding.release(token.tokenId(), ReleaseReason.CONSUMED)
                    .handle((v, ex) -> { if (ex != null) failures.incrementAndGet(); return null; });
        }
        CompletableFuture.allOf(futs).get();

        assertEquals(0, failures.get(),
                "release is idempotent; no thread may observe an exception on a repeat call");
        assertTrue(binding.findReservation(player).get().isEmpty(),
                "after all releases, player slot must be free");
        // And a release of the (now-unknown) token id is still a silent no-op.
        binding.release(token.tokenId(), ReleaseReason.CONSUMED).get();
    }

    @Test
    @DisplayName("Reaper does not surface tokens that are already CONSUMED (no spurious release dispatch)")
    void reaperSkipsTerminalTokens() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();

        // Player redeems normally before the TTL fires.
        binding.release(token.tokenId(), ReleaseReason.CONSUMED).get();

        // Now advance past the original TTL.
        clock.set(clock.get().plusSeconds(60));

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            int reaped = reaper.reapNow();
            assertEquals(0, reaped,
                    "a token that already terminated via CONSUMED must not be re-released by the reaper");
        }
    }

    @Test
    @DisplayName("Race-loss surface: claim collision raises IllegalStateException-wrapped CompletionException")
    void claimRaceLossSurface() throws Exception {
        UUID player = UUID.randomUUID();
        binding.claim("a", player, Duration.ofSeconds(30)).get();

        CompletionException ex = assertThrows(CompletionException.class,
                () -> binding.claim("a", player, Duration.ofSeconds(30)).join());
        assertNotNull(ex.getCause());
        assertEquals(IllegalStateException.class, ex.getCause().getClass(),
                "race loss must surface as IllegalStateException, matching SqlNetworkStateBinding.claimSync");
    }

    @Test
    @DisplayName("Reaper on empty token table is a no-op that returns zero")
    void reaperOnEmptyTableIsNoop() {
        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            assertEquals(0, reaper.reapNow());
            assertEquals(0L, reaper.reapedCount());
            assertFalse(reaper.isRunning(), "reapNow must not implicitly start the scheduler");
        }
    }

    @Test
    @DisplayName("Released tokens are not double-counted if reaper races a manual release on the same id")
    void reaperRacesManualReleaseSafely() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();
        clock.set(clock.get().plusSeconds(60));

        // Manual release first.
        binding.release(token.tokenId(), ReleaseReason.OPERATOR).get();

        // Then reaper sees no expired survivors.
        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            assertEquals(0, reaper.reapNow(),
                    "reaper must not double-count a token that was already released manually");
        }
        // Slot is free regardless of which path won.
        ReservationToken reborn = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertNotNull(reborn);
        assertNotEquals(token.tokenId(), reborn.tokenId());
    }
}
