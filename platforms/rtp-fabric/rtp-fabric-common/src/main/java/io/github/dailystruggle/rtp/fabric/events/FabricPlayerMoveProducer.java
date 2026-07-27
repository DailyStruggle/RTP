package io.github.dailystruggle.rtp.fabric.events;

import io.github.dailystruggle.rtp.common.event.PlayerMoveSampler;

/**
 * Fabric producer for the ADR-075 platform-neutral player-move event. Fabric has
 * no native per-move event, so movement is observed by reading each watched
 * player's block position on the server tick and diffing it against the last
 * observed position (the shared {@link PlayerMoveSampler} owns that logic).
 *
 * <p>Driven from {@code FabricEventBridge}'s end-of-server-tick hook once per
 * server tick.
 * Cost is bounded to the watched set: an unwatched server pays a single
 * has-watchers check per tick.
 *
 * @since 3.1.4
 */
public final class FabricPlayerMoveProducer {

    private final PlayerMoveSampler sampler = new PlayerMoveSampler();

    /** Sample watched players' positions and fire move events for block changes. */
    public void tick() {
        sampler.sample();
    }

    /** Drop all baseline state (server stop). */
    public void clear() {
        sampler.clear();
    }
}
