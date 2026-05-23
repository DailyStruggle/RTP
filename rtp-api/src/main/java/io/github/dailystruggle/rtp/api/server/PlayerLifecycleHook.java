package io.github.dailystruggle.rtp.api.server;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Platform SPI for subscribing to player join and quit events without
 * leaking platform-specific event types into platform-agnostic code.
 *
 * <p>Each backend platform (Bukkit / Paper / Folia / Fabric) supplies an
 * implementation that translates its native event surface (Bukkit
 * {@code PlayerJoinEvent} / {@code PlayerQuitEvent}, Fabric
 * {@code ServerPlayConnectionEvents.JOIN} / {@code DISCONNECT}, etc.)
 * into {@link Consumer Consumer&lt;UUID&gt;} callbacks. Implementations are
 * obtained via {@link RTPServerAccessor#getPlayerLifecycleHook()}; the
 * default implementation is a no-op ({@code NoopPlayerLifecycleHook}) so
 * platforms without lifecycle events (mocks, headless test harnesses)
 * compile and run unchanged.
 *
 * <p>Subscriptions are reference-counted: each call to
 * {@link #onPlayerJoin(Consumer)} / {@link #onPlayerQuit(Consumer)} returns
 * an {@link AutoCloseable} that, when closed, unregisters that specific
 * handler. Implementations are free to lazily install (and uninstall, when
 * the last handler is closed) the underlying platform listener.
 *
 * <p><b>Threading:</b> callbacks may be invoked from any thread the
 * platform delivers join / quit events on. Subscribers that require a
 * specific thread context shall hop via {@link RTPServerAccessor#getScheduler()}.
 *
 * <p>Introduced by <a href="../../../../../../../../../docs/adr/ADR-049-network-mode-platform-neutral-lift.md">ADR-049</a>.
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
