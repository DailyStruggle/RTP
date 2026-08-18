package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe, opt-in registry for {@link PlayerMoveEvent} notifications (ADR-075).
 * Only watched players incur platform adapter movement tracking cost.
 *
 * @since 3.1.4
 */
@PublicApi
public final class PlayerMoveDispatcher {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Consumer<PlayerMoveEvent>>> watchers =
            new ConcurrentHashMap<>();

    /**
     * Registers interest in block-granularity movement for {@code player}.
     *
     * @param player  player to watch; non-null
     * @param handler callback for block changes; non-null
     * @return handle that withdraws interest when closed
     * @throws IllegalArgumentException if {@code player} or {@code handler} is null
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
