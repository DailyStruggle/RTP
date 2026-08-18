package io.github.dailystruggle.rtp.api.server;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe {@link PlayerLifecycleHook} base that manages join/quit subscriber lists (ADR-049).
 *
 * <p>Dispatches via {@link #fireJoin(UUID)} and {@link #fireQuit(UUID)} with per-subscriber exception isolation.
 *
 * @since 3.0.0-beta.4
 */
public class DispatchingPlayerLifecycleHook implements PlayerLifecycleHook {

    private final CopyOnWriteArrayList<Consumer<UUID>> joinHandlers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<UUID>> quitHandlers = new CopyOnWriteArrayList<>();

    @Override
    public AutoCloseable onPlayerJoin(Consumer<UUID> handler) {
        if (handler == null) throw new IllegalArgumentException("handler must not be null");
        joinHandlers.add(handler);
        return () -> joinHandlers.remove(handler);
    }

    @Override
    public AutoCloseable onPlayerQuit(Consumer<UUID> handler) {
        if (handler == null) throw new IllegalArgumentException("handler must not be null");
        quitHandlers.add(handler);
        return () -> quitHandlers.remove(handler);
    }

    /**
     * Invoke every registered join handler with the supplied UUID. Exceptions
     * thrown by individual handlers are caught and discarded so fan-out
     * continues.
     *
     * @param uuid the joining player's UUID; must not be {@code null}
     */
    public void fireJoin(UUID uuid) {
        if (uuid == null) return;
        for (Consumer<UUID> h : joinHandlers) {
            try {
                h.accept(uuid);
            } catch (Throwable ignored) {
                // intentional: a faulty subscriber must not break fan-out
            }
        }
    }

    /**
     * Invoke every registered quit handler with the supplied UUID. Exceptions
     * thrown by individual handlers are caught and discarded so fan-out
     * continues.
     *
     * @param uuid the quitting player's UUID; must not be {@code null}
     */
    public void fireQuit(UUID uuid) {
        if (uuid == null) return;
        for (Consumer<UUID> h : quitHandlers) {
            try {
                h.accept(uuid);
            } catch (Throwable ignored) {
                // intentional: a faulty subscriber must not break fan-out
            }
        }
    }

    /**
     * @return {@code true} iff at least one handler is registered for either
     *     join or quit
     */
    public boolean hasSubscribers() {
        return !joinHandlers.isEmpty() || !quitHandlers.isEmpty();
    }
}
