package io.github.dailystruggle.rtp.api.server;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * No-op {@link PlayerLifecycleHook} singleton (ADR-049).
 *
 * <p>Subscriptions are discarded and return no-op {@link AutoCloseable} handles.
 */
public final class NoopPlayerLifecycleHook implements PlayerLifecycleHook {

    /** Process-wide singleton. */
    public static final NoopPlayerLifecycleHook INSTANCE = new NoopPlayerLifecycleHook();

    private static final AutoCloseable NOOP_SUBSCRIPTION = () -> {};

    private NoopPlayerLifecycleHook() {
    }

    @Override
    public AutoCloseable onPlayerJoin(Consumer<UUID> handler) {
        return NOOP_SUBSCRIPTION;
    }

    @Override
    public AutoCloseable onPlayerQuit(Consumer<UUID> handler) {
        return NOOP_SUBSCRIPTION;
    }
}
