package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkWaitlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-015 / REQ-RTP-NET-015. Proxy-side disconnect listener removes the
 * leaving player's UUID from the shared cross-proxy waitlist exactly once.
 *
 * <p>Multi-proxy correctness: only the proxy the player is currently
 * connected to fires {@link DisconnectEvent} for them, so a single per-proxy
 * UUID match is sufficient. {@link NetworkWaitlist#remove(UUID, NetworkWaitlist.CancelReason)}
 * is idempotent so repeating the event (rare under transport replay) is a
 * safe no-op.</p>
 */
@DisplayName("VelocityWaitlistQuitListener")
class VelocityWaitlistQuitListenerTest {

    @Test
    @DisplayName("DisconnectEvent removes enrolled player by UUID with PLAYER_DISCONNECT reason")
    void disconnectRemovesEnrolledPlayer() throws Exception {
        NetworkWaitlist waitlist = new InMemoryNetworkWaitlist(8);
        UUID playerId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // Pre-enrol so remove() has something to do.
        NetworkWaitlist.EnrolOutcome outcome = waitlist.enrol(
                new NetworkWaitlist.WaitEnvelope(
                        playerId, correlationId,
                        Optional.empty(), Optional.empty(),
                        "lobby-A", System.currentTimeMillis()))
                .get();
        assertEquals(NetworkWaitlist.EnrolOutcome.ACCEPTED, outcome);
        assertEquals(1, waitlist.size().get(), "precondition: player is enrolled");

        VelocityWaitlistQuitListener listener =
                new VelocityWaitlistQuitListener(waitlist, LoggerFactory.getLogger("test"));
        listener.onDisconnect(new DisconnectEvent(fakePlayer(playerId),
                DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        // remove() resolves async; wait briefly for the in-memory executor.
        for (int i = 0; i < 50; i++) {
            if (waitlist.size().get() == 0) break;
            Thread.sleep(10);
        }
        assertEquals(0, waitlist.size().get(),
                "DisconnectEvent must remove the player's entry from the waitlist");
        assertTrue(waitlist.position(playerId).get().isEmpty(),
                "position() must return empty for a removed player");
    }

    @Test
    @DisplayName("DisconnectEvent for a non-enrolled player is a safe no-op (idempotency)")
    void disconnectForUnknownPlayerIsNoOp() throws Exception {
        NetworkWaitlist waitlist = new InMemoryNetworkWaitlist(8);
        UUID known = UUID.randomUUID();
        waitlist.enrol(new NetworkWaitlist.WaitEnvelope(
                known, UUID.randomUUID(),
                Optional.empty(), Optional.empty(),
                "lobby-A", System.currentTimeMillis()))
                .get();
        assertEquals(1, waitlist.size().get());

        VelocityWaitlistQuitListener listener =
                new VelocityWaitlistQuitListener(waitlist, LoggerFactory.getLogger("test"));
        // Disconnect a player who was never enrolled.
        listener.onDisconnect(new DisconnectEvent(fakePlayer(UUID.randomUUID()),
                DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        // Give the executor a beat then assert the known player is still there.
        Thread.sleep(50);
        assertEquals(1, waitlist.size().get(),
                "removing an unknown UUID must not touch other entries");
    }

    @Test
    @DisplayName("Listener swallows exceptions from getPlayer() (S-004)")
    void exceptionFromGetPlayerSwallowed() {
        // Hostile DisconnectEvent whose getPlayer() throws.
        Player throwing = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        throw new IllegalStateException("unit-test: getUniqueId() hostile");
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == Optional.class) return Optional.empty();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
        DisconnectEvent event = new DisconnectEvent(throwing,
                DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);

        NetworkWaitlist waitlist = new InMemoryNetworkWaitlist(8);
        VelocityWaitlistQuitListener listener =
                new VelocityWaitlistQuitListener(waitlist, LoggerFactory.getLogger("test"));
        // Must not propagate the IllegalStateException.
        listener.onDisconnect(event);
    }

    @Test
    @DisplayName("Listener swallows exceptions from waitlist.remove() (S-004)")
    void exceptionFromRemoveSwallowed() {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        NetworkWaitlist hostileWaitlist = (NetworkWaitlist) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NetworkWaitlist.class},
                (proxy, method, args) -> {
                    if ("remove".equals(method.getName())) {
                        throw new IllegalStateException("unit-test: remove hostile");
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == Optional.class) return Optional.empty();
                    return null;
                });

        VelocityWaitlistQuitListener listener =
                new VelocityWaitlistQuitListener(hostileWaitlist, LoggerFactory.getLogger("test"));
        try {
            listener.onDisconnect(new DisconnectEvent(fakePlayer(UUID.randomUUID()),
                    DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        } catch (Throwable t) {
            captured.set(t);
        }
        assertNotNull(hostileWaitlist, "sanity: hostile waitlist constructed");
        // No exception must escape the listener.
        assert captured.get() == null
                : "listener must not propagate hostile remove() exceptions; got "
                        + captured.get();
    }

    private static Player fakePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(
                VelocityWaitlistQuitListenerTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) return id;
                    if ("getCurrentServer".equals(method.getName())) return Optional.empty();
                    Class<?> ret = method.getReturnType();
                    if (ret == Optional.class) return Optional.empty();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
    }
}
