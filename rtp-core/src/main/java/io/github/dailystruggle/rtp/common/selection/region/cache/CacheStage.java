package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.Optional;

/**
 * A bounded pipeline stage holding entries of one residency class (ADR-078).
 *
 * <p>Storage is pluggable: an implementation may wrap an existing buffer rather than
 * own a queue, which is what lets the migration wrap {@code LockFreeLocationBuffer}
 * without replacing it.
 *
 * <p>Two contract rules are load-bearing and easy to get wrong:
 * <ul>
 *   <li><b>Disposal is terminal.</b> The disposal handler releases resources - for a hot
 *       stage it closes the chunk reservation (REQ-RTP-S-002) - and does nothing else.
 *       It never re-offers into another stage, so no stage's disposal can trigger
 *       another's. Recycling a demoted entry into the cold stage is the transition
 *       layer's job.</li>
 *   <li><b>Internal movement is silent.</b> {@link #poll()} / {@link #offer(Object)} are the
 *       persistence-visible pipeline boundary and fire the configured add/remove
 *       callbacks; {@link #pollSilently()} / {@link #offerSilently(Object)} fire neither and
 *       are the pair stage-to-stage promotion and demotion shall use. Firing a remove
 *       callback for a location that still exists deletes a live database row.</li>
 * </ul>
 *
 * <p>Capacity is advisory-bounded rather than strictly locked: sizes are read from
 * lock-free counters, so transient overshoot by concurrent producers is permitted and
 * reconciled on the next region compute pulse.
 *
 * @param <T> stage entry type
 */
public interface CacheStage<T> extends AutoCloseable {
    /**
     * Returns the stage's identity, used in logs, metrics, and budget reporting.
     *
     * @return a stable, non-null stage name.
     */
    String name();

    /**
     * Removes the next entry and transfers ownership of any resource it holds
     * (chunk reservation) to the caller. Fires the remove callback.
     *
     * @return the removed entry, or empty if the stage is empty.
     */
    Optional<T> poll();

    /**
     * Adds an entry, firing the add callback. On overflow the entry is passed to the
     * disposal handler rather than dropped silently, so no reservation is ever leaked.
     *
     * @param item the entry to add; a null entry is rejected.
     * @return {@code true} if stored, {@code false} if the entry was disposed on overflow.
     */
    boolean offer(T item);

    /**
     * Removes the next entry without firing the remove callback. For internal movement
     * between stages of the same pipeline, where the persisted row still describes a
     * live location.
     *
     * @return the removed entry, or empty if the stage is empty.
     */
    Optional<T> pollSilently();

    /**
     * Adds an entry without firing the add callback. For internal movement between
     * stages of the same pipeline. Overflow still disposes.
     *
     * @param item the entry to add; a null entry is rejected.
     * @return {@code true} if stored, {@code false} if the entry was disposed on overflow.
     */
    boolean offerSilently(T item);

    /**
     * Returns the current occupancy.
     *
     * @return entry count, read from lock-free counters and therefore approximate under
     *         concurrent traffic.
     */
    int size();

    /**
     * Returns the current bound on occupancy.
     *
     * @return the applied capacity, which may exceed the value originally requested.
     */
    int capacity();

    /**
     * Re-bounds the stage, disposing any surplus entries when down-sizing.
     *
     * <p>Implementations backed by a masked ring cannot resize in place and round the
     * request up to a power of two, so callers shall use the returned value rather than
     * assuming the requested one.
     *
     * @param newCapacity the requested capacity.
     * @return the capacity actually applied.
     */
    int resizeCapacity(int newCapacity);

    /**
     * Drains the stage, applying the disposal handler to every entry. Recycles nothing,
     * so shutdown cannot re-offer into a sibling stage that is itself being torn down.
     */
    @Override
    void close();
}
