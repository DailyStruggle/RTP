package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A lock-free, thread-safe circular buffer for storing {@link RTPLocation} objects.
 * This implementation uses atomic operations to manage head and tail pointers,
 * avoiding locks for high-concurrency performance. It is optimized for a
 * single producer and a single consumer.
 */
public class LockFreeLocationBuffer {
    private final AtomicReferenceArray<RTPLocation> buffer;
    private final int mask;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    private Consumer<RTPLocation> onAdd = null;
    private Consumer<RTPLocation> onRemove = null;

    /**
     * Constructs a new buffer with a capacity that is the next power of two
     * greater than or equal to the specified capacity.
     *
     * @param capacity The desired minimum capacity.
     */
    public LockFreeLocationBuffer(int capacity) {
        int actualCapacity = 1;
        while (actualCapacity < capacity) actualCapacity <<= 1;
        this.buffer = new AtomicReferenceArray<>(actualCapacity);
        this.mask = actualCapacity - 1;
    }

    /**
     * Sets callbacks to be executed when items are added or removed from the buffer.
     *
     * @param onAdd    A consumer to be called on addition.
     * @param onRemove A consumer to be called on removal.
     */
    public void setCallbacks(Consumer<RTPLocation> onAdd, Consumer<RTPLocation> onRemove) {
        this.onAdd = onAdd;
        this.onRemove = onRemove;
    }

    /**
     * Tries to add a location to the buffer.
     *
     * @param location The location to add.
     * @return {@code true} if the location was added successfully, {@code false} if the buffer is full.
     */
    public boolean offer(RTPLocation location) {
        if (location == null) return false;
        long currentTail;
        long currentHead;
        do {
            currentTail = tail.get();
            currentHead = head.get();
            if (currentTail - currentHead >= buffer.length()) {
                return false; // Buffer is full
            }
        } while (!tail.compareAndSet(currentTail, currentTail + 1));

        buffer.set((int) (currentTail & mask), location);
        if (onAdd != null) onAdd.accept(location);
        return true;
    }

    /**
     * Adds a location to the buffer, logging an error if it's full.
     *
     * @param location The location to add.
     */
    public void add(RTPLocation location) {
        if (!offer(location)) {
            RTP.serverAccessor.log(Level.SEVERE,"Buffer is full");
        }
    }

    /**
     * Removes all locations from the buffer, closing any associated chunk reservations.
     *
     * <p>The drain is bounded to the buffer's capacity plus a small safety margin so a
     * persistently-racing producer cannot keep this method spinning forever. Callers
     * are expected to stop producers before invoking {@code clear()} (e.g. shutdown);
     * the bound is only a defensive guard against a mis-timed concurrent offer.
     */
    public void clear() {
        RTPLocation location;
        // Capacity + margin: poll() returns null once head catches tail, so in the
        // normal (quiesced-producer) case this loop terminates in at most `size()`
        // iterations. The extra slack absorbs any in-flight offer() that resolves
        // between our poll and the loop condition.
        int maxIterations = buffer.length() + 16;
        int iterations = 0;
        while ((location = poll()) != null) {
            if (location.reservation() != null) {
                location.reservation().close();
            }
            if (++iterations >= maxIterations) {
                RTP.log(Level.WARNING,
                        "LockFreeLocationBuffer.clear() aborted after " + iterations
                                + " iterations; a producer may still be active. size=" + size());
                return;
            }
        }
    }

    /**
     * Removes and returns the location at the head of the buffer.
     *
     * @return The removed location, or {@code null} if the buffer is empty.
     */
    public RTPLocation poll() {
        long currentHead;
        RTPLocation location;
        do {
            currentHead = head.get();
            if (currentHead >= tail.get()) {
                return null; // Buffer is empty
            }
            location = buffer.get((int) (currentHead & mask));
            // offer() advances tail BEFORE publishing the slot, so a concurrent
            // consumer can observe a non-empty tail with a still-null slot. Spin
            // rather than CAS head forward over the gap — advancing over a null
            // slot would silently drop the producer's in-flight entry.
        } while (location == null || !head.compareAndSet(currentHead, currentHead + 1));

        buffer.set((int) (currentHead & mask), null);
        if (onRemove != null) onRemove.accept(location);
        return location;
    }

    /**
     * Retrieves the location at the specified index without removing it.
     *
     * @param index The index of the location to retrieve.
     * @return The location at the index, or {@code null} if the index is out of bounds.
     */
    public RTPLocation get(int index) {
        if (index < 0) return null;
        long currentHead = head.get();
        long currentTail = tail.get();
        if (currentHead + index >= currentTail) {
            return null;
        }
        // Slot may be null mid-publish (offer() bumps tail before writing the slot);
        // return null rather than a partially-published entry. Callers that need a
        // stable read should retry; peek() is the dedicated spin variant.
        return buffer.get((int) ((currentHead + index) & mask));
    }

    /**
     * Replaces the location at the specified index.
     *
     * @param index    The index of the location to replace.
     * @param location The new location.
     */
    public void set(int index, RTPLocation location) {
        long currentHead = head.get();
        if (index >= 0 && index < size()) {
            buffer.set((int) ((currentHead + index) & mask), location);
        }
    }

    /**
     * Returns the number of locations currently in the buffer.
     *
     * @return The size of the buffer.
     */
    public int size() {
        return (int) (tail.get() - head.get());
    }

    /**
     * Checks if the buffer is empty.
     *
     * @return {@code true} if the buffer is empty, {@code false} otherwise.
     */
    public boolean isEmpty() {
        return tail.get() == head.get();
    }

    /**
     * Retrieves the location at the head of the buffer without removing it.
     *
     * @return The location at the head, or {@code null} if the buffer is empty.
     */
    public RTPLocation peek() {
        long currentHead;
        RTPLocation location;
        // offer() advances tail before writing the slot, so an observer that saw
        // the tail bump may still read null. Spin until the slot is published,
        // or until head catches tail (genuinely empty).
        do {
            currentHead = head.get();
            if (currentHead >= tail.get()) {
                return null;
            }
            location = buffer.get((int) (currentHead & mask));
        } while (location == null);
        return location;
    }
}
