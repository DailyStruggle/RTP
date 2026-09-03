package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A {@link CacheStage} backed by a {@link ConcurrentLinkedQueue} (ADR-078).
 *
 * <p>For pipelines with no existing storage to wrap. Capacity is enforced by a separate
 * counter rather than by the queue, so occupancy is advisory-bounded exactly as the
 * contract describes: a concurrent producer may overshoot transiently.
 *
 * @param <T> stage entry type
 */
public final class SimpleCacheStage<T> implements CacheStage<T> {
    private final String name;
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger occupancy = new AtomicInteger();
    private final AtomicInteger capacity;
    private final Consumer<T> onAdd;
    private final Consumer<T> onRemove;
    private final Consumer<T> onDispose;

    /**
     * @param name      stage identity for logs and metrics.
     * @param capacity  initial occupancy bound; values below 1 are clamped to 1.
     * @param onAdd     persistence-visible ingress callback, or null for none.
     * @param onRemove  persistence-visible egress callback, or null for none.
     * @param onDispose terminal disposal handler, or null for none. Must release the
     *                  entry's resources and never re-offer into another stage.
     */
    public SimpleCacheStage(String name,
                            int capacity,
                            Consumer<T> onAdd,
                            Consumer<T> onRemove,
                            Consumer<T> onDispose) {
        this.name = name == null ? "unnamed" : name;
        this.capacity = new AtomicInteger(Math.max(1, capacity));
        this.onAdd = onAdd;
        this.onRemove = onRemove;
        this.onDispose = onDispose;
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

    private Optional<T> poll0(boolean fireCallback) {
        T item = queue.poll();
        if (item == null) return Optional.empty();
        occupancy.decrementAndGet();
        if (fireCallback && onRemove != null) onRemove.accept(item);
        return Optional.of(item);
    }

    private boolean offer0(T item, boolean fireCallback) {
        if (item == null) return false;
        // Reserve a slot before publishing so two producers cannot both admit the last
        // entry; on overflow the reservation is rolled back and the entry disposed
        // rather than dropped (REQ-RTP-S-002).
        int reserved = occupancy.incrementAndGet();
        if (reserved > capacity.get()) {
            occupancy.decrementAndGet();
            dispose(item);
            return false;
        }
        queue.offer(item);
        if (fireCallback && onAdd != null) onAdd.accept(item);
        return true;
    }

    @Override
    public int size() {
        return Math.max(0, occupancy.get());
    }

    @Override
    public int capacity() {
        return capacity.get();
    }

    @Override
    public int resizeCapacity(int newCapacity) {
        int applied = Math.max(1, newCapacity);
        capacity.set(applied);
        while (occupancy.get() > applied) {
            Optional<T> surplus = poll0(false);
            if (surplus.isEmpty()) break;
            dispose(surplus.get());
        }
        return applied;
    }

    @Override
    public void close() {
        Optional<T> entry;
        while ((entry = poll0(false)).isPresent()) {
            dispose(entry.get());
        }
    }

    private void dispose(T item) {
        if (onDispose != null) onDispose.accept(item);
    }
}
