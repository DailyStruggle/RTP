package io.github.dailystruggle.mapsapi;

/**
 * Lifecycle handle returned by {@link MapBinding#bindLive} so callers can stop a
 * live chart's refresh loop and release its {@code MemoryTracker} allocation
 * (REQ-RTP-MAP-003).
 *
 * <p>Implementations shall be safe to call {@link #cancel()} more than once;
 * subsequent calls are no-ops. {@link #cancelled()} shall return {@code true}
 * immediately after the first {@code cancel()} call regardless of whether the
 * underlying release has completed.
 */
public interface Cancellation {

    /**
     * Stops the live chart refresh loop and releases any {@code MemoryTracker}
     * allocation held by the binding. Idempotent.
     */
    void cancel();

    /** Returns {@code true} after the first {@link #cancel()} call. */
    boolean cancelled();
}
