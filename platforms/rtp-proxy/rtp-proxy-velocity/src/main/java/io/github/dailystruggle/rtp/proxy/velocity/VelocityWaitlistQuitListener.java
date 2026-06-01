package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;

/**
 * ADR-015 / REQ-RTP-NET-015. Proxy-side disconnect listener that point-removes
 * the leaving player's entry from the shared cross-proxy
 * {@link NetworkWaitlist}, so a queued envelope is dropped the instant the
 * player goes away rather than waiting on the TTL reaper.
 *
 * <p><strong>Multi-proxy correctness.</strong> A player is connected to
 * exactly one proxy at any moment in time; only that proxy fires
 * {@link DisconnectEvent} for them. So this listener is effectively a
 * "first UUID match on this proxy" policy without any inter-proxy
 * coordination: other proxies never observe the event and never need to.
 * Crashed-proxy orphans are handled separately by the reaper task
 * scheduled in {@code RtpVelocityPlugin}.</p>
 *
 * <p>{@link NetworkWaitlist#remove(UUID, NetworkWaitlist.CancelReason)} is
 * idempotent per Slice 1 SPI contract: calling it for a player who is not
 * (or no longer) on the waitlist is a no-op, so we never branch on whether
 * the player was actually enrolled.</p>
 *
 * <p>S-004: the remove future's exception path is logged at WARN and never
 * propagates out of the event dispatch.</p>
 */
public final class VelocityWaitlistQuitListener {

    private final NetworkWaitlist waitlist;
    private final Logger logger;

    public VelocityWaitlistQuitListener(NetworkWaitlist waitlist, Logger logger) {
        this.waitlist = Objects.requireNonNull(waitlist, "waitlist");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId;
        try {
            playerId = event.getPlayer().getUniqueId();
        } catch (Throwable t) {
            // Velocity's mocks during early-init can throw here; never let it
            // surface to the event dispatcher (S-004).
            logger.warn("RTP waitlist: could not resolve player id from DisconnectEvent: {}",
                    t.getMessage(), t);
            return;
        }
        try {
            waitlist.remove(playerId, NetworkWaitlist.CancelReason.PLAYER_DISCONNECT)
                    .whenComplete((removed, err) -> {
                        if (err != null) {
                            logger.warn("RTP waitlist: remove({}) failed on disconnect: {}",
                                    playerId, err.getMessage(), err);
                        }
                    });
        } catch (Throwable t) {
            logger.warn("RTP waitlist: remove({}) threw synchronously on disconnect: {}",
                    playerId, t.getMessage(), t);
        }
    }
}
