package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for REQ-RTP-PROXY-VELOCITY-001 (Velocity Runtime).
 *
 * <p>Validates the no-op shell wiring without spinning up a Velocity host:
 * <ul>
 *   <li>{@link RtpVelocityPlugin} carries {@link Plugin} with {@code id="rtp"}.</li>
 *   <li>Lifecycle handlers for {@link ProxyInitializeEvent} and {@link ProxyShutdownEvent}
 *       exist and are annotated {@link Subscribe}.</li>
 *   <li>No other {@code @Subscribe} method is present beyond the core lifecycle
 *       (no {@code ServerPreConnectEvent}, no Brigadier registration, no heartbeat task).
 *       This guards REQ-RTP-NET-002 parity by structural means.</li>
 * </ul>
 */
@DisplayName("REQ-RTP-PROXY-VELOCITY-001 — Velocity adapter smoke test")
class ReqRtpProxyVelocity001SmokeTest {

    @Test
    @DisplayName("@Plugin id == \"rtp\"")
    void pluginAnnotationDeclaresRtpId() {
        Plugin plugin = RtpVelocityPlugin.class.getAnnotation(Plugin.class);
        assertNotNull(plugin, "RtpVelocityPlugin must carry @Plugin");
        assertEquals("rtp", plugin.id(), "Plugin id must be \"rtp\" per REQ-RTP-PROXY-VELOCITY-007");
    }

    @Test
    @DisplayName("ProxyInitializeEvent handler present and @Subscribe-annotated")
    void initializeHandlerIsSubscribed() throws NoSuchMethodException {
        Method m = RtpVelocityPlugin.class.getDeclaredMethod("onProxyInitialize", ProxyInitializeEvent.class);
        assertNotNull(m.getAnnotation(Subscribe.class),
                "onProxyInitialize must be @Subscribe-annotated");
    }

    @Test
    @DisplayName("ProxyShutdownEvent handler present and @Subscribe-annotated")
    void shutdownHandlerIsSubscribed() throws NoSuchMethodException {
        Method m = RtpVelocityPlugin.class.getDeclaredMethod("onProxyShutdown", ProxyShutdownEvent.class);
        assertNotNull(m.getAnnotation(Subscribe.class),
                "onProxyShutdown must be @Subscribe-annotated");
    }

    @Test
    @DisplayName("ServerPreConnectEvent handler present and @Subscribe-annotated")
    void serverPreConnectHandlerIsSubscribed() throws NoSuchMethodException {
        Method m = RtpVelocityPlugin.class.getDeclaredMethod("onServerPreConnect", ServerPreConnectEvent.class);
        assertNotNull(m.getAnnotation(Subscribe.class),
                "onServerPreConnect must be @Subscribe-annotated (REQ-RTP-PROXY-VELOCITY-003)");
    }

    @Test
    @DisplayName("Exactly init + shutdown + serverPreConnect @Subscribe methods exist")
    void subscribeSurfaceMatchesPhase2cAlpha() {
        long subscribed = java.util.Arrays.stream(RtpVelocityPlugin.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Subscribe.class) != null)
                .count();
        assertEquals(3, subscribed,
                "Surface: ProxyInitializeEvent + ProxyShutdownEvent + ServerPreConnectEvent only. "
                        + "Further additions require a plan + REQ row.");
    }

    @Test
    @DisplayName("Plugin class is final (no subclassing of the entry-point)")
    void pluginClassIsFinal() {
        assertTrue(java.lang.reflect.Modifier.isFinal(RtpVelocityPlugin.class.getModifiers()),
                "RtpVelocityPlugin must be final — the entry-point is not an extension surface.");
    }
}
