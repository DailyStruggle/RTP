package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable block-granularity movement notification for a watched player (ADR-075).
 * Fires only when block coordinates change; zero chunk I/O.
 *
 * @param playerId  player UUID; never null
 * @param worldName world name; never null
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
