package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, platform-neutral notification that a watched player has crossed
 * into a new block. It is normalized to <em>block granularity</em>: it fires
 * only when the player's block coordinate changes, never on sub-block motion.
 *
 * <p>Each platform adapter is responsible for producing this normalized signal
 * from whatever its runtime offers (filter Bukkit's {@code PlayerMoveEvent} down
 * to block changes; diff the tick-sampled position on Fabric/NeoForge) and for
 * firing it only for players in the {@linkplain PlayerMoveDispatcher watched
 * set}. Register interest through
 * {@code RTPAPI.watchPlayerMove(UUID, java.util.function.Consumer)} (ADR-075).
 *
 * <p>This is a pure notification: it cannot veto or mutate movement (move-veto
 * does not exist cleanly off Bukkit). Consumers that enforce a boundary do so by
 * relocating the player (safe pull-back), routing that relocation through the RTP
 * scheduler / server accessor themselves.
 *
 * <p><b>Threading:</b> the event is delivered on the platform's natural thread
 * for that player (e.g. the moving entity's region thread on Folia). Subscribers
 * that touch the world must re-schedule onto the RTP scheduler rather than
 * working inline. The event itself performs no chunk I/O.
 *
 * @param playerId  the UUID of the player that moved; never {@code null}.
 * @param worldName the name of the world the player is in; never {@code null}.
 * @param fromX     the block X the player moved from.
 * @param fromY     the block Y the player moved from.
 * @param fromZ     the block Z the player moved from.
 * @param toX       the block X the player moved to.
 * @param toY       the block Y the player moved to.
 * @param toZ       the block Z the player moved to.
 *
 * @since 3.1.4
 */
@PublicApi
public record PlayerMoveEvent(
        UUID playerId,
        String worldName,
        int fromX,
        int fromY,
        int fromZ,
        int toX,
        int toY,
        int toZ
) {
    public PlayerMoveEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(worldName, "worldName");
    }
}
