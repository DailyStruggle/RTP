package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@code listActiveForServer(serverId)} on the
 * in-memory binding. The boot-time reconcile in {@code NetworkModeBootstrap}
 * relies on this method to return only PENDING/CLAIMED tokens whose
 * {@code serverId} matches and whose {@code expiresAtMs > now}.
 */
class InMemoryNetworkStateBindingListActiveForServerTest {

    private InMemoryNetworkStateBinding binding;

    @BeforeEach
    void setUp() {
        binding = new InMemoryNetworkStateBinding();
    }

    @AfterEach
    void tearDown() {
        if (binding != null) binding.close();
    }

    @Test
    @DisplayName("listActiveForServer on empty store returns empty list")
    void empty_returnsEmpty() throws Exception {
        List<ReservationToken> active = binding.listActiveForServer("backend-a")
                .get();
        assertNotNull(active);
        assertTrue(active.isEmpty(), "expected empty list, got " + active);
    }

    @Test
    @DisplayName("listActiveForServer filters by serverId")
    void filtersByServerId() throws Exception {
        ReservationToken a1 = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMinutes(5)).get();
        ReservationToken a2 = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMinutes(5)).get();
        ReservationToken b1 = binding.claim("backend-b", UUID.randomUUID(), Duration.ofMinutes(5)).get();

        List<ReservationToken> aActive = binding.listActiveForServer("backend-a").get();
        List<ReservationToken> bActive = binding.listActiveForServer("backend-b").get();

        assertEquals(2, aActive.size(), "backend-a expected 2 active tokens");
        assertEquals(1, bActive.size(), "backend-b expected 1 active token");
        assertTrue(aActive.stream().allMatch(t -> "backend-a".equals(t.serverId())));
        assertTrue(bActive.stream().allMatch(t -> "backend-b".equals(t.serverId())));
        // Cross-check tokenIds present on the a-side list.
        assertTrue(aActive.stream().anyMatch(t -> t.tokenId().equals(a1.tokenId())));
        assertTrue(aActive.stream().anyMatch(t -> t.tokenId().equals(a2.tokenId())));
        assertTrue(bActive.stream().anyMatch(t -> t.tokenId().equals(b1.tokenId())));
    }

    @Test
    @DisplayName("listActiveForServer filters out RELEASED tokens")
    void filtersReleasedTokens() throws Exception {
        ReservationToken alive = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMinutes(5)).get();
        ReservationToken doomed = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMinutes(5)).get();
        binding.release(doomed.tokenId(), ReleaseReason.OPERATOR).get();

        List<ReservationToken> active = binding.listActiveForServer("backend-a").get();

        assertEquals(1, active.size(), "expected only the non-released token");
        assertEquals(alive.tokenId(), active.get(0).tokenId());
    }

    @Test
    @DisplayName("listActiveForServer filters out CONSUMED tokens")
    void filtersConsumedTokens() throws Exception {
        UUID playerA = UUID.randomUUID();
        ReservationToken consumed = binding.claim("backend-a", playerA, Duration.ofMinutes(5)).get();
        binding.redeem(consumed.tokenId(), playerA, "backend-a").get();

        ReservationToken alive = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMinutes(5)).get();

        List<ReservationToken> active = binding.listActiveForServer("backend-a").get();

        assertEquals(1, active.size(), "expected only the non-consumed token");
        assertEquals(alive.tokenId(), active.get(0).tokenId());
    }

    @Test
    @DisplayName("listActiveForServer filters out expired tokens")
    void filtersExpiredTokens() throws Exception {
        // Tiny TTL so it expires by the time we list. Sleep is bounded.
        ReservationToken shortLived = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMillis(1)).get();
        Thread.sleep(20L);

        ReservationToken alive = binding.claim("backend-a", UUID.randomUUID(), Duration.ofMinutes(5)).get();

        List<ReservationToken> active = binding.listActiveForServer("backend-a").get();

        assertEquals(1, active.size(),
                "expected only the non-expired token, got " + active);
        assertEquals(alive.tokenId(), active.get(0).tokenId());
        // tokenId of the short-lived row is intentionally not asserted absent
        // (the binding may or may not have reaped it; the contract only requires
        // it be omitted from the active list).
        assertTrue(active.stream().noneMatch(t -> t.tokenId().equals(shortLived.tokenId())));
    }

    @Test
    @DisplayName("listActiveForServer(null) throws NPE")
    void nullServerId_throws() {
        assertThrows(NullPointerException.class,
                () -> binding.listActiveForServer(null));
    }
}
