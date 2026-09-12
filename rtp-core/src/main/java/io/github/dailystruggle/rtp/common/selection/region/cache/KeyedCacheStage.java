package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A partitioned stage: one bounded {@link CacheStage} per key (ADR-078).
 *
 * <p>Replaces bespoke per-UUID and per-profile queue maps. A keyed stage registers as a
 * single hot sink covering all of its partitions, so it carries one identity for
 * budgeting; because its entries are leased to a specific key it is never a transfer
 * source or destination, and its partitions are balanced only through the cold
 * promotion gate.
 *
 * @param <K> partition key type
 * @param <T> stage entry type
 */
public class KeyedCacheStage<K, T> implements AutoCloseable {
    private final String name;
    private final PartitionFactory<K, T> factory;
    private final Map<K, CacheStage<T>> partitions = new ConcurrentHashMap<>();

    /**
     * Creates a partition on demand.
     *
     * @param <K> partition key type
     * @param <T> stage entry type
     */
    @FunctionalInterface
    public interface PartitionFactory<K, T> {
        /**
         * @param key      the partition key.
         * @param capacity the requested partition capacity.
         * @return a new stage for that key; never null.
         */
        CacheStage<T> create(K key, int capacity);
    }

    /**
     * @param name    identity for logs, metrics, and budget reporting.
     * @param factory partition constructor, supplying storage and callbacks.
     */
    public KeyedCacheStage(String name, PartitionFactory<K, T> factory) {
        if (factory == null) throw new IllegalArgumentException("partition factory must not be null");
        this.name = name == null ? "unnamed" : name;
        this.factory = factory;
    }

    /**
     * Returns this keyed stage's identity.
     *
     * @return a stable, non-null name.
     */
    public String name() {
        return name;
    }

    /**
     * Opens a partition, or returns the existing one. Idempotent: re-opening does not
     * resize or replace a live partition, so an in-flight entry cannot be orphaned.
     *
     * @param key      the partition key.
     * @param capacity the capacity used only when the partition is created.
     * @return the partition for that key.
     */
    public CacheStage<T> open(K key, int capacity) {
        return partitions.computeIfAbsent(key, k -> factory.create(k, capacity));
    }

    /**
     * Returns an already-open partition without creating one.
     *
     * @param key the partition key.
     * @return the partition, or empty if the key is not open.
     */
    public Optional<CacheStage<T>> peek(K key) {
        return Optional.ofNullable(partitions.get(key));
    }

    /**
     * Removes and returns an entry from one partition.
     *
     * @param key the partition key.
     * @return the entry, or empty if the key is not open or the partition is empty.
     */
    public Optional<T> poll(K key) {
        CacheStage<T> stage = partitions.get(key);
        return stage == null ? Optional.empty() : stage.poll();
    }

    /**
     * Adds an entry to an already-open partition. A closed or never-opened key is not
     * created implicitly, so the caller keeps ownership and can dispose deliberately.
     *
     * @param key  the partition key.
     * @param item the entry to add.
     * @return {@code true} if stored; {@code false} if the key is not open or the
     *         partition overflowed (in which case the partition disposed the entry).
     */
    public boolean offer(K key, T item) {
        CacheStage<T> stage = partitions.get(key);
        return stage != null && stage.offer(item);
    }

    /**
     * Drains and removes one partition, disposing every entry it holds.
     *
     * @param key the partition key.
     */
    public void closeKey(K key) {
        CacheStage<T> stage = partitions.remove(key);
        if (stage != null) stage.close();
    }

    /**
     * Returns the total occupancy across partitions.
     *
     * @return summed entry count; approximate under concurrent traffic.
     */
    public int size() {
        int total = 0;
        for (CacheStage<T> stage : partitions.values()) total += stage.size();
        return total;
    }

    /**
     * Returns the number of open partitions.
     *
     * @return open key count.
     */
    public int partitionCount() {
        return partitions.size();
    }

    /**
     * Returns the open partition keys.
     *
     * @return an unmodifiable live view of the open keys.
     */
    public Set<K> keys() {
        return Collections.unmodifiableSet(partitions.keySet());
    }

    /**
     * Drains and removes every partition, disposing all entries.
     */
    @Override
    public void close() {
        for (K key : Set.copyOf(partitions.keySet())) {
            closeKey(key);
        }
    }
}
