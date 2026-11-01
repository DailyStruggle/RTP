package io.github.dailystruggle.rtp.common.playerData;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * A concurrent, time-binned cache extending {@link ConcurrentHashMap} that organizes keys into generational epoch buckets.
 * Allows $O(1)$ lookups with standard ConcurrentHashMap performance and bulk removal of expired bins without per-entry timers.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class TimeBinnedCache<K, V> extends ConcurrentHashMap<K, V> {
  private static final long serialVersionUID = 1L;

  private final long binWidthMs;
  private volatile long retentionMs;

  private final ConcurrentNavigableMap<Long, Set<K>> bins = new ConcurrentSkipListMap<>();
  private final ConcurrentHashMap<K, Long> keyToBin = new ConcurrentHashMap<>();

  public TimeBinnedCache(long binWidthMs, long retentionMs) {
    super();
    if (binWidthMs <= 0) throw new IllegalArgumentException("binWidthMs must be positive: " + binWidthMs);
    if (retentionMs <= 0) throw new IllegalArgumentException("retentionMs must be positive: " + retentionMs);
    this.binWidthMs = binWidthMs;
    this.retentionMs = retentionMs;
  }

  public long getBinWidthMs() {
    return binWidthMs;
  }

  public long getRetentionMs() {
    return retentionMs;
  }

  public void setRetentionMs(long retentionMs) {
    if (retentionMs > 0) {
      this.retentionMs = retentionMs;
    }
  }

  public long getBinId(long timestamp) {
    return timestamp / binWidthMs;
  }

  public long currentBinId() {
    return System.currentTimeMillis() / binWidthMs;
  }

  /**
   * Purges all bins older than the retention threshold.
   *
   * @param now the current epoch timestamp in milliseconds
   * @return the number of evicted entries
   */
  public int purgeExpired(long now) {
    long cutoffBin = (now - retentionMs) / binWidthMs;
    ConcurrentNavigableMap<Long, Set<K>> expiredBins = bins.headMap(cutoffBin, false);
    if (expiredBins.isEmpty()) return 0;

    int evictedCount = 0;
    Set<Long> expiredBinIds = new HashSet<>(expiredBins.keySet());
    for (Long expiredBinId : expiredBinIds) {
      Set<K> keys = bins.remove(expiredBinId);
      if (keys != null) {
        for (K key : keys) {
          keyToBin.remove(key, expiredBinId);
          if (super.remove(key) != null) {
            evictedCount++;
          }
        }
      }
    }
    return evictedCount;
  }

  @Override
  public V put(K key, V value) {
    return put(key, value, System.currentTimeMillis());
  }

  public V put(K key, V value, long timestamp) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(value, "value must not be null");

    V oldVal = super.put(key, value);
    long binId = getBinId(timestamp);
    bins.computeIfAbsent(binId, b -> ConcurrentHashMap.newKeySet()).add(key);

    Long previousBinId = keyToBin.put(key, binId);
    if (previousBinId != null && previousBinId != binId) {
      Set<K> prevSet = bins.get(previousBinId);
      if (prevSet != null) {
        prevSet.remove(key);
      }
    }
    return oldVal;
  }

  @Override
  public V putIfAbsent(K key, V value) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(value, "value must not be null");

    V existing = super.putIfAbsent(key, value);
    if (existing == null) {
      long binId = getBinId(System.currentTimeMillis());
      bins.computeIfAbsent(binId, b -> ConcurrentHashMap.newKeySet()).add(key);
      keyToBin.put(key, binId);
    }
    return existing;
  }

  @Override
  public V remove(Object key) {
    if (key == null) return null;
    V old = super.remove(key);
    Long binId = keyToBin.remove(key);
    if (binId != null) {
      Set<K> b = bins.get(binId);
      if (b != null) b.remove(key);
    }
    return old;
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (key == null || value == null) return false;
    boolean removed = super.remove(key, value);
    if (removed) {
      Long binId = keyToBin.remove(key);
      if (binId != null) {
        Set<K> b = bins.get(binId);
        if (b != null) b.remove(key);
      }
    }
    return removed;
  }

  @Override
  public void clear() {
    super.clear();
    keyToBin.clear();
    bins.clear();
  }
}
