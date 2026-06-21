package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe registry of {@link PrefabAppliedEvent} subscribers, modelled on
 * {@code DispatchingPlayerLifecycleHook}: it maintains a
 * {@link CopyOnWriteArrayList} of subscribers, hands back an
 * {@link AutoCloseable} for unregistration, and isolates each subscriber under
 * a {@code try}/{@code catch} during fan-out so one faulty addon cannot abort
 * delivery to the others.
 *
 * <p>An instance of this dispatcher is created eagerly by {@code RTPAPI}, so
 * subscribers may register at any time (including before {@code rtp-core} has
 * finished loading). {@code rtp-core} invokes {@link #fire(PrefabAppliedEvent)}
 * after a prefab has been applied.
 *
 * <p><b>Threading:</b> subscribers may be invoked from whatever thread fires
 * the event (the thread executing the prefab confirm command). Exceptions
 * thrown by individual subscribers are caught and discarded so fan-out
 * continues; subscribers that need diagnostic visibility should log inside
 * their own consumer.
 *
 * @since 3.1.4
 */
@PublicApi
public final class PrefabEventDispatcher {

    private final CopyOnWriteArrayList<Consumer<PrefabAppliedEvent>> handlers =
            new CopyOnWriteArrayList<>();

    /**
     * Register a subscriber to be notified after a prefab is applied.
     *
     * @param handler the subscriber; must not be {@code null}.
     * @return an {@link AutoCloseable} that unregisters the subscriber when
     *     closed.
     * @throws IllegalArgumentException if {@code handler} is {@code null}.
     */
    @PublicApi
    public AutoCloseable subscribe(Consumer<PrefabAppliedEvent> handler) {
        if (handler == null) throw new IllegalArgumentException("handler must not be null");
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    /**
     * Invoke every registered subscriber with {@code event}. Exceptions thrown
     * by individual subscribers are caught and discarded so fan-out continues.
     *
     * @param event the event to deliver; a {@code null} event is ignored.
     */
    public void fire(PrefabAppliedEvent event) {
        if (event == null) return;
        for (Consumer<PrefabAppliedEvent> h : handlers) {
            try {
                h.accept(event);
            } catch (Throwable ignored) {
                // intentional: a faulty subscriber must not break fan-out
            }
        }
    }

    /**
     * @return {@code true} iff at least one subscriber is registered.
     */
    public boolean hasSubscribers() {
        return !handlers.isEmpty();
    }
}
