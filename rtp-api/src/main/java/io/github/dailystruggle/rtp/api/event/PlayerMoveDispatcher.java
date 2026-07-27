package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe, opt-in, per-player registry for {@link PlayerMoveEvent}
 * notifications (ADR-075).
 *
 * <p>Unlike a "listen to everyone" model, a consumer must explicitly
 * {@linkplain #watch(UUID, Consumer) watch} the specific players it cares about
 * and withdraws interest by closing the returned handle. Platform adapters call
 * {@link #isWatched(UUID)} to decide whether to do any movement work for a
 * player at all, so per-move cost scales with the number of watched players, not
 * the total online count. This makes edge-triggered features (arm on teleport,
 * watch only the armed set) cheap.
 *
 * <p>An instance of this dispatcher is created eagerly by {@code RTPAPI}, so
 * consumers may register at any time (including before {@code rtp-core} has
 * finished loading). Each platform adapter invokes {@link #fire(PlayerMoveEvent)}
 * when a watched player crosses into a new block.
 *
 * <p><b>Threading:</b> handlers are invoked on whatever thread fires the event
 * (the platform's natural thread for that player). Exceptions thrown by an
 * individual handler are caught and discarded so fan-out continues; handlers
 * that need diagnostic visibility should log inside their own consumer.
 *
 * @since 3.1.4
 */
@PublicApi
public final class PlayerMoveDispatcher {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Consumer<PlayerMoveEvent>>> watchers =
            new ConcurrentHashMap<>();

    /**
     * Register interest in a specific player's block-granularity movement.
     *
     * <p>Multiple handlers may watch the same player; each is delivered every
     * event and each is unregistered independently through the returned handle.
     * The player leaves the {@linkplain #isWatched(UUID) watched set} only once
     * the last handler for that player is closed.
     *
     * @param player  the player to watch; must not be {@code null}.
     * @param handler the handler to notify on each block change; must not be
     *                {@code null}.
     * @return an {@link AutoCloseable} that withdraws this handler when closed.
     * @throws IllegalArgumentException if {@code player} or {@code handler} is
     *                                  {@code null}.
     */
    @PublicApi
    public AutoCloseable watch(UUID player, Consumer<PlayerMoveEvent> handler) {
        if (player == null) throw new IllegalArgumentException("player must not be null");
        if (handler == null) throw new IllegalArgumentException("handler must not be null");
        watchers.computeIfAbsent(player, p -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> {
            CopyOnWriteArrayList<Consumer<PlayerMoveEvent>> list = watchers.get(player);
            if (list != null) {
                list.remove(handler);
                // Drop the empty bucket so isWatched reports false and adapters
                // stop doing movement work for this player. Guard against a
                // concurrent add racing the removal.
                watchers.computeIfPresent(player, (p, l) -> l.isEmpty() ? null : l);
            }
        };
    }

    /**
     * @param player the player to test; a {@code null} player is never watched.
     * @return {@code true} iff at least one handler is currently watching
     *     {@code player}. Platform adapters gate their per-player movement work
     *     on this so unwatched players cost nothing.
     */
    @PublicApi
    public boolean isWatched(UUID player) {
        if (player == null) return false;
        CopyOnWriteArrayList<Consumer<PlayerMoveEvent>> list = watchers.get(player);
        return list != null && !list.isEmpty();
    }

    /**
     * @return an immutable snapshot of the currently watched player UUIDs.
     */
    @PublicApi
    public Set<UUID> watchedPlayers() {
        return Set.copyOf(watchers.keySet());
    }

    /**
     * @return {@code true} iff at least one player is currently watched.
     */
    public boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    /**
     * Deliver {@code event} to every handler watching the moved player.
     * Exceptions thrown by individual handlers are caught and discarded so
     * fan-out continues. A {@code null} event, or an event for a player that is
     * not watched, is ignored.
     *
     * @param event the event to deliver.
     */
    public void fire(PlayerMoveEvent event) {
        if (event == null) return;
        CopyOnWriteArrayList<Consumer<PlayerMoveEvent>> list = watchers.get(event.playerId());
        if (list == null) return;
        for (Consumer<PlayerMoveEvent> h : list) {
            try {
                h.accept(event);
            } catch (Throwable ignored) {
                // intentional: a faulty handler must not break fan-out
            }
        }
    }

    /**
     * Remove all handlers for {@code player} (e.g. on disconnect), taking the
     * player out of the watched set in one call.
     *
     * @param player the player to stop watching; a {@code null} player is ignored.
     */
    @PublicApi
    public void unwatchAll(UUID player) {
        if (player == null) return;
        watchers.remove(Objects.requireNonNull(player));
    }
}
