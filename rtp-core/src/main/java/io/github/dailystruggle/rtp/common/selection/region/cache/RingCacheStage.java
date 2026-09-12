package io.github.dailystruggle.rtp.common.selection.region.cache;

import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * A {@link CacheStage} backed by a power-of-two masked ring buffer (ADR-078).
 *
 * <p>Storage mirrors {@code LockFreeLocationBuffer} using lock-free head/tail counters,
 * an {@link AtomicReferenceArray}, and terminal disposal on overflow/teardown.
 *
 * <p>Because a masked ring cannot resize in place, {@link #resizeCapacity(int)} allocates
 * a new power-of-two ring, migrates existing entries preserving publication order, disposes
 * any surplus beyond the new capacity, and atomically swaps the ring reference.
 *
 * @param <T> stage entry type
 */
public final class RingCacheStage<T> implements CacheStage<T> {
    private final String name;
    private final Consumer<T> onAdd;
    private final Consumer<T> onRemove;
    private final Consumer<T> onDispose;

    private static final class Ring<E> {
        final AtomicReferenceArray<E> buffer;
        final int mask;
        final int capacity;
        final AtomicLong head = new AtomicLong(0);
        final AtomicLong tail = new AtomicLong(0);
        final AtomicInteger occupancy = new AtomicInteger(0);

        Ring(int requestedCapacity) {
            int cap = Math.max(1, requestedCapacity);
            int pow2 = 1;
            while (pow2 < cap) {
                pow2 <<= 1;
                if (pow2 <= 0) { // overflow guard
                    pow2 = 1 << 30;
                    break;
                }
            }
            this.capacity = pow2;
            this.buffer = new AtomicReferenceArray<>(pow2);
            this.mask = pow2 - 1;
        }
    }

    private final AtomicReference<Ring<T>> currentRing;
    private final LockFreeLocationBuffer delegate;

    /**
     * @param name      stage identity for logs and metrics.
     * @param capacity  initial occupancy bound; rounded up to the next power of two (min 1).
     * @param onAdd     persistence-visible ingress callback, or null for none.
     * @param onRemove  persistence-visible egress callback, or null for none.
     * @param onDispose terminal disposal handler, or null for none. Must release the
     *                  entry's resources and never re-offer into another stage.
     */
    public RingCacheStage(String name,
                          int capacity,
                          Consumer<T> onAdd,
                          Consumer<T> onRemove,
                          Consumer<T> onDispose) {
        this.name = name == null ? "unnamed" : name;
        this.onAdd = onAdd;
        this.onRemove = onRemove;
        this.onDispose = onDispose;
        this.currentRing = new AtomicReference<>(new Ring<>(capacity));
        this.delegate = null;
    }

    /**
     * Constructs a {@link RingCacheStage} that wraps an existing {@link LockFreeLocationBuffer} storage.
     *
     * @param name      stage identity for logs and metrics.
     * @param delegate  the underlying lock-free location buffer.
     * @param onDispose terminal disposal handler.
     */
    public RingCacheStage(String name,
                          LockFreeLocationBuffer delegate,
                          Consumer<T> onDispose) {
        this(name, delegate, null, null, onDispose);
    }

    /**
     * Constructs a {@link RingCacheStage} that wraps an existing {@link LockFreeLocationBuffer} storage
     * with optional callbacks.
     *
     * @param name      stage identity for logs and metrics.
     * @param delegate  the underlying lock-free location buffer.
     * @param onAdd     persistence-visible ingress callback, or null for none.
     * @param onRemove  persistence-visible egress callback, or null for none.
     * @param onDispose terminal disposal handler.
     */
    @SuppressWarnings("unchecked")
    public RingCacheStage(String name,
                          LockFreeLocationBuffer delegate,
                          Consumer<T> onAdd,
                          Consumer<T> onRemove,
                          Consumer<T> onDispose) {
        if (delegate == null) throw new IllegalArgumentException("delegate buffer must not be null");
        this.name = name == null ? "unnamed" : name;
        this.delegate = delegate;
        this.onAdd = onAdd;
        this.onRemove = onRemove;
        this.onDispose = onDispose;
        this.currentRing = null;
        if (onAdd != null || onRemove != null) {
            delegate.setCallbacks(
                    onAdd != null ? loc -> onAdd.accept((T) loc) : null,
                    onRemove != null ? loc -> onRemove.accept((T) loc) : null
            );
        }
    }

    /**
     * @return the underlying {@link LockFreeLocationBuffer} if wrapped, or {@code null} if self-contained.
     */
    public LockFreeLocationBuffer delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<T> poll() {
        return poll0(true);
    }

    @Override
    public Optional<T> pollSilently() {
        return poll0(false);
    }

    @Override
    public boolean offer(T item) {
        return offer0(item, true);
    }

    @Override
    public boolean offerSilently(T item) {
        return offer0(item, false);
    }

    @SuppressWarnings("unchecked")
    private Optional<T> poll0(boolean fireCallback) {
        if (delegate != null) {
            RTPLocation loc = fireCallback ? delegate.poll() : delegate.pollSilently();
            return Optional.ofNullable((T) loc);
        }
        Ring<T> ring = currentRing.get();
        return pollFromRing(ring, fireCallback);
    }

    private Optional<T> pollFromRing(Ring<T> ring, boolean fireCallback) {
        long currentHead;
        T item;
        do {
            currentHead = ring.head.get();
            if (currentHead >= ring.tail.get()) {
                return Optional.empty();
            }
            item = ring.buffer.get((int) (currentHead & ring.mask));
        } while (item == null || !ring.head.compareAndSet(currentHead, currentHead + 1));

        ring.buffer.set((int) (currentHead & ring.mask), null);
        ring.occupancy.decrementAndGet();
        if (fireCallback && onRemove != null) {
            onRemove.accept(item);
        }
        return Optional.of(item);
    }

    private boolean offer0(T item, boolean fireCallback) {
        if (item == null) return false;
        if (delegate != null) {
            if (!(item instanceof RTPLocation loc)) {
                dispose(item);
                return false;
            }
            boolean accepted = fireCallback ? delegate.offer(loc) : delegate.offerSilently(loc);
            if (!accepted) {
                dispose(item);
                return false;
            }
            return true;
        }
        Ring<T> ring = currentRing.get();
        int reserved = ring.occupancy.incrementAndGet();
        if (reserved > ring.capacity) {
            ring.occupancy.decrementAndGet();
            dispose(item);
            return false;
        }

        long currentTail;
        long currentHead;
        do {
            currentTail = ring.tail.get();
            currentHead = ring.head.get();
            if (currentTail - currentHead >= ring.capacity) {
                ring.occupancy.decrementAndGet();
                dispose(item);
                return false;
            }
        } while (!ring.tail.compareAndSet(currentTail, currentTail + 1));

        ring.buffer.set((int) (currentTail & ring.mask), item);
        if (fireCallback && onAdd != null) {
            onAdd.accept(item);
        }
        return true;
    }

    @Override
    public int size() {
        if (delegate != null) {
            return delegate.size();
        }
        Ring<T> ring = currentRing.get();
        return Math.max(0, ring.occupancy.get());
    }

    @Override
    public int capacity() {
        if (delegate != null) {
            return delegate.capacity();
        }
        return currentRing.get().capacity;
    }

    @Override
    public synchronized int resizeCapacity(int newCapacity) {
        if (delegate != null) {
            return delegate.capacity();
        }
        Ring<T> oldRing = currentRing.get();
        Ring<T> newRing = new Ring<>(newCapacity);

        // First, drain any surplus from oldRing silently from the head (oldest entries)
        // so that the newest entries within newRing.capacity are preserved, or drain
        // preserving publication order consistent with SimpleCacheStage.
        // In SimpleCacheStage:
        // while (occupancy > applied) { surplus = poll(); dispose(surplus); }
        // That means the oldest entries (head) were disposed first as surplus!
        int excess = oldRing.occupancy.get() - newRing.capacity;
        for (int i = 0; i < excess; i++) {
            Optional<T> surplus = pollFromRing(oldRing, false);
            if (surplus.isEmpty()) break;
            dispose(surplus.get());
        }

        // Migrate remaining entries from oldRing to newRing silently preserving publication order
        Optional<T> item;
        while ((item = pollFromRing(oldRing, false)).isPresent()) {
            T val = item.get();
            int reserved = newRing.occupancy.incrementAndGet();
            if (reserved > newRing.capacity) {
                newRing.occupancy.decrementAndGet();
                dispose(val);
            } else {
                long currentTail = newRing.tail.getAndIncrement();
                newRing.buffer.set((int) (currentTail & newRing.mask), val);
            }
        }

        currentRing.set(newRing);
        return newRing.capacity;
    }

    @Override
    public void close() {
        Optional<T> entry;
        while ((entry = poll0(false)).isPresent()) {
            dispose(entry.get());
        }
    }

    private void dispose(T item) {
        if (onDispose != null) {
            onDispose.accept(item);
        }
    }
}
