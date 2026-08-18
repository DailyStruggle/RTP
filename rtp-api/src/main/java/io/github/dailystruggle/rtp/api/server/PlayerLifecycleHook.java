package io.github.dailystruggle.rtp.api.server;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Platform SPI for subscribing to player join and quit events (ADR-049).
 * Subscriptions return an {@link AutoCloseable} unregistration handle.
 *
 * @since 3.0.0-beta.4
 */
public interface PlayerLifecycleHook {

    /**
     * Subscribe to player join events.
     *
     * @param handler invoked with the joining player's UUID; must not be {@code null}
     * @return a closeable that unregisters this handler when closed; never {@code null}
     */
    AutoCloseable onPlayerJoin(Consumer<UUID> handler);

    /**
     * Subscribe to player quit (disconnect) events.
     *
     * @param handler invoked with the quitting player's UUID; must not be {@code null}
     * @return a closeable that unregisters this handler when closed; never {@code null}
     */
    AutoCloseable onPlayerQuit(Consumer<UUID> handler);
}
