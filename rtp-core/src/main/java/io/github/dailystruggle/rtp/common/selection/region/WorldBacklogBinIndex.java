package io.github.dailystruggle.rtp.common.selection.region;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-scoped cross-RTP-region index of L3 backlog entries, keyed by Anvil
 * region-file coordinate (see {@link RegionFileCoord}). Each bin holds a
 * {@link WeakReference} to a list of {@link BacklogLocationBuffer.BacklogEntry}
 * instances contributed by any number of {@link Region}s that target the same
 * world.
 *
 * <p>This index is the &quot;cross-RTP-region amortization&quot; structure required
 * by <a href="../../../../../../../../../../docs/adr/ADR-028-l3-backlog-cache.md">ADR-028</a>:
 * when one region selects a bin to verify, the Anvil pre-filter pass covers
 * <em>every</em> entry contributed to that bin by any region, paying for the
 * single {@code .mca} read once.
 *
 * <h2>Storage of truth</h2>
 * The per-region {@link BacklogLocationBuffer} owns each entry; this index
 * holds only weak references. If the GC reclaims a bin's list (because every
 * referencing buffer has been cleared or its entries replaced), the next
 * insert simply recreates the bin — there is no correctness impact.
 *
 * <h2>Capacity</h2>
 * The index itself is unbounded. Bound is transitively imposed by
 * {@code Σ backlogCacheCap} across all regions targeting the world, since each
 * referenced entry must also be live in some region's buffer.
 *
 * <h2>Thread-safety</h2>
 * Uses {@link ConcurrentHashMap} for bin lookup. The per-bin {@code List} is
 * itself synchronized externally during mutation (see {@link #insert}); reads
 * (verification iteration) take a snapshot copy to avoid concurrent
 * modification during a multi-entry pass. Validity field on each
 * {@link BacklogLocationBuffer.BacklogEntry} is {@code volatile} so a
 * verification write is observable without locking.
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
    while (true) {
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
   * Snapshots the current contents of the bin identified by {@code key}. The
   * returned list is a copy: subsequent mutations on the underlying bin do
   * not affect it.
   *
   * @param key bin coordinate; never {@code null}
   * @return snapshot of entries currently contributed to the bin, in insertion
   *         order; empty if the bin has been GC'd or never existed
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
   * — correctness does not depend on it.
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
