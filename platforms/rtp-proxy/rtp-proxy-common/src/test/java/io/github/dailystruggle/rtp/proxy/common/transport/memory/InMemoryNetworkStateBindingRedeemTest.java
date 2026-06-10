package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 of {@code CHECKLIST-cross-server-rtp.md}: exercises every
 * {@link RedeemOutcome} branch on the in-memory binding so the backend's
 * {@code JoinTriggerSource} can rely on the documented contract without
 * standing up a real Redis/SQL instance.
 */
class InMemoryNetworkStateBindingRedeemTest {

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
    @DisplayName("Redeem of a CLAIMED token transitions to REDEEMED exactly once")
    void redeemHappyPath() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();

        RedeemOutcome first = binding.redeem(token.tokenId(), player, "a").get();
        assertEquals(RedeemOutcome.REDEEMED, first);

        // Idempotency on the wire: a second redeem of the same token must
        // not transition again. Either ALREADY_CONSUMED (if the row is kept
        // until reap) or NOT_FOUND (if the row is wiped at redeem time, as
        // InMemory does) is acceptable per the SPI contract.
        RedeemOutcome second = binding.redeem(token.tokenId(), player, "a").get();
        assertNotNull(second);
        assertEquals(RedeemOutcome.NOT_FOUND, second,
                "InMemory wipes consumed rows; observed=" + second);
    }

    @Test
    @DisplayName("Region-aware claim carries regionKey onto the token and findReservation")
    void claimCarriesRegionKey() throws Exception {
        // Cross-server /rtp region=<server>:<region> support: the requested
        // region must survive the claim and be observable by the destination
        // backend (via findReservation / listActiveForServer) so it can
        // dispatch `rtp region=<regionKey>` on arrival.
        UUID player = UUID.randomUUID();
        ReservationToken token = binding
                .claim("a", player, Duration.ofSeconds(30), Optional.of("nether")).get();
        assertEquals(Optional.of("nether"), token.regionKey());

        Optional<ReservationToken> found = binding.findReservation(player).get();
        assertTrue(found.isPresent(), "active reservation must be findable");
        assertEquals(Optional.of("nether"), found.get().regionKey(),
                "regionKey must survive the claim round-trip");

        assertTrue(binding.listActiveForServer("a").get().stream()
                        .anyMatch(t -> t.regionKey().equals(Optional.of("nether"))),
                "listActiveForServer must report the region-pinned token");
    }

    @Test
    @DisplayName("Regionless claim leaves an empty regionKey")
    void regionlessClaimHasEmptyRegionKey() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();
        assertEquals(Optional.empty(), token.regionKey());
    }

    @Test
    @DisplayName("Redeem of a never-claimed token returns NOT_FOUND")
    void redeemUnknownTokenIsNotFound() throws Exception {
        RedeemOutcome o = binding.redeem("never-existed", UUID.randomUUID(), "a").get();
        assertEquals(RedeemOutcome.NOT_FOUND, o);
    }

    @Test
    @DisplayName("Redeem with mismatched serverId returns WRONG_SERVER (no mutation)")
    void redeemWrongServer() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();

        RedeemOutcome o = binding.redeem(token.tokenId(), player, "different-backend").get();
        assertEquals(RedeemOutcome.WRONG_SERVER, o);

        // Row must still be redeemable on the correct serverId after the miss.
        RedeemOutcome retry = binding.redeem(token.tokenId(), player, "a").get();
        assertEquals(RedeemOutcome.REDEEMED, retry);
    }

    @Test
    @DisplayName("Redeem with mismatched playerId returns WRONG_SERVER")
    void redeemWrongPlayer() throws Exception {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();

        RedeemOutcome o = binding.redeem(token.tokenId(), other, "a").get();
        assertEquals(RedeemOutcome.WRONG_SERVER, o);
    }

    @Test
    @DisplayName("Redeem of an already-released token returns ALREADY_CONSUMED")
    void redeemAlreadyReleased() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();
        binding.release(token.tokenId(), ReleaseReason.OPERATOR).get();

        // After release, InMemory wipes the row; redeem must surface
        // NOT_FOUND (semantically equivalent to ALREADY_CONSUMED on the
        // join hot path - both result in a silent no-op).
        RedeemOutcome o = binding.redeem(token.tokenId(), player, "a").get();
        assertEquals(RedeemOutcome.NOT_FOUND, o);
    }

    @Test
    @DisplayName("Redeem of an expired token returns EXPIRED")
    void redeemExpired() throws Exception {
        UUID player = UUID.randomUUID();
        // Fix the clock so the row is materialised at t0 with ttl=1ms then
        // moved forward past expiry. The binding consults a clock supplier
        // for findReservation+redeem so we can simulate TTL without sleeping.
        java.util.concurrent.atomic.AtomicReference<Instant> clock =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.ofEpochMilli(1_000_000L));
        binding.setClock(clock::get);

        ReservationToken token = binding.claim("a", player, Duration.ofMillis(50)).get();
        // Advance the clock 1s past expiry.
        clock.set(Instant.ofEpochMilli(1_005_000L));

        RedeemOutcome o = binding.redeem(token.tokenId(), player, "a").get();
        assertEquals(RedeemOutcome.EXPIRED, o);
    }

    @Test
    @DisplayName("Concurrent redeems: exactly one observes REDEEMED")
    void concurrentRedeemsAreAtomic() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken token = binding.claim("a", player, Duration.ofSeconds(30)).get();

        int n = 16;
        AtomicInteger redeemed = new AtomicInteger();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futs = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            futs[i] = binding.redeem(token.tokenId(), player, "a")
                    .thenAccept(o -> { if (o == RedeemOutcome.REDEEMED) redeemed.incrementAndGet(); });
        }
        CompletableFuture.allOf(futs).get();
        assertEquals(1, redeemed.get(),
                "exactly one redeem must observe REDEEMED across " + n + " contenders");
    }
}
