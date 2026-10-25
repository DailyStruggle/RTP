package io.github.dailystruggle.rtp.api.server;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Thread-safe {@link PlayerLifecycleHook} base that manages join/quit subscriber lists (ADR-049).
 *
 * <p>Dispatches via {@link #fireJoin(UUID)} and {@link #fireQuit(UUID)} with per-subscriber exception isolation.
 *
 * @since 3.0.0-beta.4
 */
public class DispatchingPlayerLifecycleHook
        extends io.github.dailystruggle.server.api.DispatchingPlayerLifecycleHook
        implements PlayerLifecycleHook {

    @Override
    public AutoCloseable onPlayerJoin(Consumer<UUID> handler) {
        return super.onPlayerJoin(handler);
    }

    @Override
    public AutoCloseable onPlayerQuit(Consumer<UUID> handler) {
        return super.onPlayerQuit(handler);
    }

    /**
     * Invoke every registered join handler with the supplied UUID. Exceptions
     * thrown by individual handlers are caught and discarded so fan-out
     * continues.
     *
     * @param uuid the joining player's UUID; must not be {@code null}
     */
    @Override
    public void fireJoin(UUID uuid) {
        super.fireJoin(uuid);
    }

    /**
     * Invoke every registered quit handler with the supplied UUID. Exceptions
     * thrown by individual handlers are caught and discarded so fan-out
     * continues.
     *
     * @param uuid the quitting player's UUID; must not be {@code null}
     */
    @Override
    public void fireQuit(UUID uuid) {
        super.fireQuit(uuid);
    }

    /**
     * @return {@code true} iff at least one handler is registered for either
     *     join or quit
     */
    @Override
    public boolean hasSubscribers() {
        return super.hasSubscribers();
    }
}
