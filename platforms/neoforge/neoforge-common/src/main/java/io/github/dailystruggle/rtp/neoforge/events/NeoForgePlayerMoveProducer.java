package io.github.dailystruggle.rtp.neoforge.events;

import io.github.dailystruggle.rtp.common.event.PlayerMoveSampler;

/**
 * NeoForge producer for the ADR-075 platform-neutral player-move event.
 * NeoForge has no native per-move event, so movement is observed by reading each
 * watched player's block position on the server tick and diffing it against the
 * last observed position (the shared {@link PlayerMoveSampler} owns that logic).
 *
 * <p>Driven from {@code RTPNeoForgeMod}'s {@code ServerTickEvent.Post} handler
 * once per server tick. Cost is bounded to the watched set: an unwatched server
 * pays a single has-watchers check per tick.
 *
 * @since 3.1.4
 */
public final class NeoForgePlayerMoveProducer {

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
