package io.github.dailystruggle.rtp.proxy.common.spi;

import io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpiAndModelCoverageTest {

    @Test
    @DisplayName("ReservationClient.ClaimResult validates presence and factory methods")
    void claimResultContracts() {
        ReservationToken token = new ReservationToken(
                "tok-1", "srv-1", UUID.randomUUID(),
                System.currentTimeMillis() + 10000L,
                ReservationToken.State.CLAIMED,
                "reg-1"
        );
        ReservationClient.ClaimResult success = ReservationClient.ClaimResult.success(token);
        assertTrue(success.isSuccess());
        assertTrue(success.token().isPresent());
        assertTrue(success.failure().isEmpty());
        assertEquals(token, success.token().get());

        DispatchOutcome.Failed failure = new DispatchOutcome.Failed(
                DispatchOutcome.Failed.Reason.NO_BACKEND, "none");
        ReservationClient.ClaimResult failed = ReservationClient.ClaimResult.failure(failure);
        assertFalse(failed.isSuccess());
        assertTrue(failed.token().isEmpty());
        assertTrue(failed.failure().isPresent());
        assertEquals(failure, failed.failure().get());

        // Exactly one must be present
        assertThrows(IllegalArgumentException.class, () ->
                new ReservationClient.ClaimResult(Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
                new ReservationClient.ClaimResult(Optional.of(token), Optional.of(failure)));
        assertThrows(NullPointerException.class, () ->
                new ReservationClient.ClaimResult(null, Optional.of(failure)));
        assertThrows(NullPointerException.class, () ->
                new ReservationClient.ClaimResult(Optional.of(token), null));
    }

    @Test
    @DisplayName("ServerRegion validates serverId and regionKey")
    void serverRegionValidation() {
        ServerRegion sr = new ServerRegion("srv-1", "region-1");
        assertEquals("srv-1", sr.serverId());
        assertEquals("region-1", sr.regionKey());

        assertThrows(NullPointerException.class, () -> new ServerRegion(null, "reg"));
        assertThrows(NullPointerException.class, () -> new ServerRegion("srv", null));
        assertThrows(IllegalArgumentException.class, () -> new ServerRegion("", "reg"));
        assertThrows(IllegalArgumentException.class, () -> new ServerRegion("srv", ""));
    }

    @Test
    @DisplayName("MessageKey validates non-blank key")
    void messageKeyValidation() {
        MessageKey key = new MessageKey("rtp.network.routed");
        assertEquals("rtp.network.routed", key.key());

        assertThrows(NullPointerException.class, () -> new MessageKey(null));
        assertThrows(IllegalArgumentException.class, () -> new MessageKey(""));
        assertThrows(IllegalArgumentException.class, () -> new MessageKey("   "));
    }
}
