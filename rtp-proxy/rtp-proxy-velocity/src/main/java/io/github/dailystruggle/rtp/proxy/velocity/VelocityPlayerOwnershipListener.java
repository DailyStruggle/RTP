package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import io.github.dailystruggle.rtp.proxy.common.spi.PlayerOwnershipTracker;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Velocity-side {@link PlayerOwnershipTracker} driver (rtp-proxy-ADR-016,
 * REQ-RTP-NET-016).
 *
 * <p>On {@link PostLoginEvent} we record {@code (uuid, thisProxyId)} in the
 * shared store with a short TTL; on {@link DisconnectEvent} we compare-and-DEL
 * so a stale tag from a previous session cannot clobber a fresh one set by
 * the other proxy a player just reconnected to.</p>
 *
 * <p>S-004 contract: every handler wraps the async call in a {@code Throwable}
 * catch so a transient transport hiccup cannot break Velocity's event
 * dispatcher.</p>
 */
public final class VelocityPlayerOwnershipListener {

    private final PlayerOwnershipTracker tracker;
    private final String thisProxyId;
    private final int ttlSeconds;
    private final Logger logger;

    public VelocityPlayerOwnershipListener(PlayerOwnershipTracker tracker,
                                           String thisProxyId,
                                           int ttlSeconds,
                                           Logger logger) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.thisProxyId = Objects.requireNonNull(thisProxyId, "thisProxyId");
        if (thisProxyId.isEmpty()) {
            throw new IllegalArgumentException("thisProxyId must be non-empty");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be > 0");
        }
        this.ttlSeconds = ttlSeconds;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        try {
            tracker.claim(event.getPlayer().getUniqueId(), thisProxyId, ttlSeconds)
                    .whenComplete((v, err) -> {
                        if (err != null) {
                            logger.warn("RTP ownership claim failed for {}: {}",
                                    event.getPlayer().getUniqueId(), err.getMessage());
                        }
                    });
        } catch (Throwable t) {
            logger.warn("RTP ownership PostLoginEvent handler threw: {}", t.getMessage(), t);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        try {
            tracker.release(event.getPlayer().getUniqueId(), thisProxyId)
                    .whenComplete((v, err) -> {
                        if (err != null) {
                            logger.warn("RTP ownership release failed for {}: {}",
                                    event.getPlayer().getUniqueId(), err.getMessage());
                        }
                    });
        } catch (Throwable t) {
            logger.warn("RTP ownership DisconnectEvent handler threw: {}", t.getMessage(), t);
        }
    }

    /**
     * Refresh the ownership tag for every currently connected player. Called
     * periodically (heartbeat cadence) so the TTL never expires under the
     * owning proxy. Idempotent and safe to invoke concurrently with login /
     * disconnect handlers.
     */
    public void refreshAll(java.util.Collection<java.util.UUID> connectedPlayerIds) {
        if (connectedPlayerIds == null || connectedPlayerIds.isEmpty()) return;
        for (java.util.UUID id : connectedPlayerIds) {
            if (id == null) continue;
            try {
                tracker.claim(id, thisProxyId, ttlSeconds);
            } catch (Throwable t) {
                logger.warn("RTP ownership refresh failed for {}: {}", id, t.getMessage());
            }
        }
    }
}
