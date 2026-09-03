package io.github.dailystruggle.rtp.common.selection.region;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-scoped index of backlog entries keyed by Anvil region-file coordinate (ADR-028).
 * Groups candidate locations across regions targeting the same world for single-pass verification.
 */
public final class WorldBacklogBinIndex {

  private final Map<RegionFileCoord, WeakReference<List<BacklogLocationBuffer.BacklogEntry>>> bins =
      new ConcurrentHashMap<>();

  /**
   * Adds {@code entry} to the bin identified by {@code key}. If the bin's list
   * has been GC'd (or never existed), a new list is created.
   *
   * @param key   bin coordinate; never {@code null}
   * @param entry entry contributed by some region's
   *              {@link BacklogLocationBuffer}; never {@code null}
   */
  public void insert(RegionFileCoord key, BacklogLocationBuffer.BacklogEntry entry) {
    for (int attempt = 0; attempt < 32; attempt++) {
      WeakReference<List<BacklogLocationBuffer.BacklogEntry>> ref = bins.get(key);
      List<BacklogLocationBuffer.BacklogEntry> list = (ref == null) ? null : ref.get();
      if (list != null) {
        synchronized (list) {
          list.add(entry);
        }
        entry.pinBinList(list);
        return;
      }
      List<BacklogLocationBuffer.BacklogEntry> fresh =
          Collections.synchronizedList(new ArrayList<>(8));
      fresh.add(entry);
      WeakReference<List<BacklogLocationBuffer.BacklogEntry>> freshRef = new WeakReference<>(fresh);
      WeakReference<List<BacklogLocationBuffer.BacklogEntry>> prior = bins.putIfAbsent(key, freshRef);
      if (prior == null) {
        // The map keeps only a weak reference; the entry itself pins the list
        // strongly so the bin stays reachable while any contributing entry is
        // alive in some region's BacklogLocationBuffer. Once every contributing
        // entry has been promoted/dropped, the list becomes GC-eligible.
        entry.pinBinList(fresh);
        return;
      }
      List<BacklogLocationBuffer.BacklogEntry> existing = prior.get();
      if (existing != null) {
        synchronized (existing) {
          existing.add(entry);
        }
        entry.pinBinList(existing);
        return;
      }
      // prior ref was GC'd between get and putIfAbsent; retry.
      bins.remove(key, prior);
    }
  }

  /**
   * Returns an insertion-ordered copy of entries in bin {@code key}, or an empty list if none exist.
   *
   * @param key bin coordinate (never null)
   * @return snapshot of entries in the bin
   */
  public List<BacklogLocationBuffer.BacklogEntry> snapshot(RegionFileCoord key) {
    WeakReference<List<BacklogLocationBuffer.BacklogEntry>> ref = bins.get(key);
    if (ref == null) return Collections.emptyList();
    List<BacklogLocationBuffer.BacklogEntry> list = ref.get();
    if (list == null) {
      bins.remove(key, ref);
      return Collections.emptyList();
    }
    synchronized (list) {
      return new ArrayList<>(list);
    }
  }

  /**
   * @param key bin coordinate; never {@code null}
   * @return {@code true} iff the bin currently has a live list with at least
   *         one entry
   */
  public boolean hasBin(RegionFileCoord key) {
    WeakReference<List<BacklogLocationBuffer.BacklogEntry>> ref = bins.get(key);
    if (ref == null) return false;
    List<BacklogLocationBuffer.BacklogEntry> list = ref.get();
    if (list == null) {
      bins.remove(key, ref);
      return false;
    }
    synchronized (list) {
      return !list.isEmpty();
    }
  }

  /**
   * Sweeps any bins whose weak-referenced list has been GC'd. Optional housekeeping
   * - correctness does not depend on it.
   *
   * @return number of stale bin keys removed
   */
  public int reapStale() {
    int reaped = 0;
    for (Map.Entry<RegionFileCoord, WeakReference<List<BacklogLocationBuffer.BacklogEntry>>> e :
        bins.entrySet()) {
      if (e.getValue().get() == null) {
        if (bins.remove(e.getKey(), e.getValue())) reaped++;
      }
    }
    return reaped;
  }

  /** @return number of distinct bin keys currently tracked (live or GC-pending). */
  public int size() {
    return bins.size();
  }

  /** Clears all bins. Live entries in per-region buffers are unaffected. */
  public void clear() {
    bins.clear();
  }
}
