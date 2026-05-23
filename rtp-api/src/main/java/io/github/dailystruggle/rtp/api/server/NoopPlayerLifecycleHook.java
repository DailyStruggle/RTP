package io.github.dailystruggle.rtp.api.server;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * No-op {@link PlayerLifecycleHook} returned by
 * {@link RTPServerAccessor#getPlayerLifecycleHook()} when the platform has
 * not installed a real lifecycle source.
 *
 * <p>Subscriptions are accepted and silently discarded; the returned
 * {@link AutoCloseable#close()} is a no-op. Used by {@code MockRTPServerAccessor},
 * the (interface-level) default, and any platform that does not deliver
 * join / quit events.
 *
 * <p>Singleton: {@link #INSTANCE}. Introduced by
 * <a href="../../../../../../../../../docs/adr/ADR-049-network-mode-platform-neutral-lift.md">ADR-049</a>.
 *
 * @since 3.0.0-beta.4
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
