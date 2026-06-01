package io.github.dailystruggle.rtp.proxy.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards for {@link RtpProxy} mirroring the {@code RTP.serverAccessor} S-006
 * contract: unregistered reads throw {@link IllegalStateException}, registered
 * reads return the exact instance, clear resets the slot.
 *
 * <p>See rtp-proxy-ADR-013.</p>
 */
class RtpProxyTest {

    @BeforeEach
    void clear() {
        RtpProxy.clearProxyAccessor();
    }

    @AfterEach
    void clearAgain() {
        RtpProxy.clearProxyAccessor();
    }

    @Test
    void unregisteredReadThrowsIllegalStateException() {
        assertFalse(RtpProxy.isRegistered());
        IllegalStateException ex = assertThrows(IllegalStateException.class, RtpProxy::proxyAccessor);
        assertTrue(ex.getMessage().contains("RtpProxy.proxyAccessor"),
                "exception message must name the missing slot: " + ex.getMessage());
    }

    @Test
    void registeredReadReturnsSameInstance() {
        RTPProxyAccessor a = new FixedAccessor(Role.PROXY_VELOCITY, "p1");
        RtpProxy.setProxyAccessor(a);
        assertTrue(RtpProxy.isRegistered());
        assertSame(a, RtpProxy.proxyAccessor());
    }

    @Test
    void clearResetsSlot() {
        RtpProxy.setProxyAccessor(new FixedAccessor(Role.PROXY_VELOCITY, "p1"));
        RtpProxy.clearProxyAccessor();
        assertFalse(RtpProxy.isRegistered());
        assertThrows(IllegalStateException.class, RtpProxy::proxyAccessor);
    }

    @Test
    void setNullRejected() {
        assertThrows(NullPointerException.class, () -> RtpProxy.setProxyAccessor(null));
    }

    @Test
    void roundTripPreservesRole() {
        RtpProxy.setProxyAccessor(new FixedAccessor(Role.PROXY_BUNGEE, "bungee-1"));
        assertEquals(Role.PROXY_BUNGEE, RtpProxy.proxyAccessor().role());
        assertEquals("bungee-1", RtpProxy.proxyAccessor().proxyId());
    }

    /** Minimal fixed-value accessor for tests; production lives in each adapter. */
    static final class FixedAccessor implements RTPProxyAccessor {
        private final Role role;
        private final String proxyId;
        FixedAccessor(Role role, String proxyId) {
            this.role = role;
            this.proxyId = proxyId;
        }
        @Override public Role role() { return role; }
        @Override public String proxyId() { return proxyId; }
        @Override public void sendMessage(UUID playerId, String message) { /* test no-op */ }
        @Override public CompletableFuture<Void> transferPlayer(UUID playerId, String backendId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
