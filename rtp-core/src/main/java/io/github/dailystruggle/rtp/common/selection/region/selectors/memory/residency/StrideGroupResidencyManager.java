package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.residency;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.HybridHazardTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sweep-predicted (Belady) residency for stride-group bins (ADR-092, 2026-09-10).
 *
 * <p>Because the backlog access sequence is known ahead from the selector's cursor
 * ({@link SweepCursor}), residency is driven by predicted distance-to-next-access,
 * not recency. A bounded working set of stride groups is kept resident around the
 * cursor; the group the cursor is about to enter is prefetched on approach; the
 * group behind the cursor is released this rotation. There is no re-promote-on-heat
 * rule - rematerialization is strictly prefetch-on-approach.
 *
 * <p>The stride group is the unified unit: the file shard, the prefetch unit, the
 * devolution unit, and the future DB partition key {@code (world, shape, strideGroup,
 * binIndex)}. Each group owns a bin-local {@link HybridHazardTable} (the resident
 * exact form) derived from a persistent shard (its {@code .bin} form); paging out
 * drops the resident table but keeps the shard, so it is lossless.
 *
 * <p>All group load / scan work happens off-tick via {@link RTP#scheduler} - never
 * a synchronous chunk load on the main thread (S-005). A mark is never dropped on
 * eviction or devolution (S-004 direction).
 */
public final class StrideGroupResidencyManager {

  /** 1024 chunks per Anvil-aligned 1024-chunk bin (P=32 -> 32x32). */
  public static final long CHUNKS_PER_BIN = 1024L;

  private final SweepCursor cursor;
  private final long range;
  private final long binsPerGroup;
  private final long keysPerGroup;
  private final long budgetBytes;
  private final int lookahead;
  private final double devolveDensityFloor;

  private final Map<Long, GroupState> groups = new ConcurrentHashMap<>();

  public StrideGroupResidencyManager(SweepCursor cursor,
                                     long range,
                                     long binsPerGroup,
                                     long budgetBytes,
                                     int lookahead,
                                     double devolveDensityFloor) {
    if (cursor == null) throw new IllegalArgumentException("cursor");
    if (range <= 0) throw new IllegalArgumentException("range");
    if (binsPerGroup <= 0) throw new IllegalArgumentException("binsPerGroup");
    this.cursor = cursor;
    this.range = range;
    this.binsPerGroup = binsPerGroup;
    this.keysPerGroup = binsPerGroup * CHUNKS_PER_BIN;
    this.budgetBytes = budgetBytes;
    this.lookahead = Math.max(1, lookahead);
    this.devolveDensityFloor = devolveDensityFloor;
  }

  /** Residency state for a single stride group. */
  private static final class GroupState {
    final long groupId;
    /** Authoritative persistent shard (the {@code .bin} form): global bad keys. */
    final TreeSet<Long> shard = new TreeSet<>();
    /** Resident exact form; {@code null} when paged out or devolved. */
    HybridHazardTable resident;
    /** Memoryless per-bin occupancy count when devolved; {@code -1} otherwise. */
    long devolvedCount = -1L;

    GroupState(long groupId) {
      this.groupId = groupId;
    }

    boolean isDevolved() {
      return devolvedCount >= 0L;
    }
  }

  public long groupOf(long binIndex) {
    return binIndex / binsPerGroup;
  }

  public long groupOfKey(long key) {
    return groupOf(key / CHUNKS_PER_BIN);
  }

  private long groupStartKey(long groupId) {
    return groupId * keysPerGroup;
  }

  private GroupState state(long groupId) {
    return groups.computeIfAbsent(groupId, GroupState::new);
  }

  // ---------------------------------------------------------------------------
  // Marking / queries (a mark is never dropped - S-004 direction)
  // ---------------------------------------------------------------------------

  /**
   * Records a bad key. The mark lands in the group's persistent shard first (so it
   * survives page-out and devolution) and, when the group is resident-exact, in the
   * resident table too. Never silently discarded.
   */
  public void markBad(long key) {
    if (key < 0 || key >= range) return;
    GroupState g = state(groupOfKey(key));
    synchronized (g) {
      boolean added = g.shard.add(key);
      if (g.resident != null) {
        g.resident.markBad(key - groupStartKey(g.groupId));
      }
      if (added && g.isDevolved()) {
        // A devolved group only holds a count; a new mark must raise it so the
        // count never under-represents the shard (over-scan direction).
        g.devolvedCount = g.shard.size();
      }
    }
  }

  /**
   * Point check. The persistent shard is authoritative, so the answer is correct
   * regardless of residency state (resident, paged out, or devolved). A devolved
   * group answers from the shard under a quota scan rather than under-marking.
   */
  public boolean isKnownBad(long key) {
    if (key < 0 || key >= range) return true;
    GroupState g = groups.get(groupOfKey(key));
    if (g == null) return false;
    synchronized (g) {
      if (g.resident != null) {
        return g.resident.isBad(key - groupStartKey(g.groupId));
      }
      return g.shard.contains(key);
    }
  }

  /**
   * Resolves the {@code localRank}-th good key within a group (ACCUMULATE direction).
   * If the group is not resident-exact it is rematerialized on demand first, so the
   * result is identical to what the resident group would have produced.
   *
   * @return global key, or {@code -1} if out of range
   */
  public long resolveGroupAccumulate(long groupId, long localRank) {
    GroupState g = state(groupId);
    synchronized (g) {
      if (g.resident == null) {
        buildResident(g);
      }
      long local = g.resident.resolveAccumulate(localRank);
      if (local < 0) return -1L;
      return local + groupStartKey(groupId);
    }
  }

  // ---------------------------------------------------------------------------
  // Residency transitions (all heavy work off-tick via RTP.scheduler - S-005)
  // ---------------------------------------------------------------------------

  /**
   * Prefetch-on-approach: materialize the group's exact table off-tick. Also serves
   * as the sole rematerialization path for a devolved group (no re-promote-on-heat).
   */
  public void loadStrideGroup(long groupId) {
    GroupState g = state(groupId);
    Runnable load = () -> {
      synchronized (g) {
        if (g.resident == null) {
          buildResident(g);
        }
      }
    };
    scheduleOffTick(load);
  }

  /**
   * Release-behind: drop the resident exact table once the cursor has passed the
   * group this rotation. Lossless - the persistent shard is retained.
   */
  public void pageOut(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return;
    synchronized (g) {
      g.resident = null;
      g.devolvedCount = -1L;
    }
  }

  /**
   * Devolve a resident group to a memoryless per-bin COUNT (tail case, budget breach
   * only). Permitted only for groups at or above the bitmask-tier density floor;
   * sparse array/run groups are never devolved. The count is reaffirmed by an
   * over-scan of the shard, so it can never under-represent the marks (S-004).
   *
   * @return {@code true} if the group was devolved
   */
  public boolean devolveToCount(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return false;
    synchronized (g) {
      if (!isBitmaskTier(g)) {
        return false;
      }
      long reaffirmed = reaffirmCount(g);
      g.resident = null;
      g.devolvedCount = reaffirmed;
      return true;
    }
  }

  /**
   * Quota scan that reaffirms a devolved group's occupancy count by re-reading its
   * shard. Over-scans (counts every mark) and never under-marks: the returned value
   * is monotonic with respect to any prior count.
   */
  private long reaffirmCount(GroupState g) {
    long scanned = 0L;
    for (Long ignored : g.shard) {
      scanned++;
    }
    long prior = g.devolvedCount;
    return Math.max(prior, scanned);
  }

  private void buildResident(GroupState g) {
    HybridHazardTable table = new HybridHazardTable(keysPerGroup);
    long start = groupStartKey(g.groupId);
    for (long key : g.shard) {
      table.markBad(key - start);
    }
    g.resident = table;
    g.devolvedCount = -1L;
  }

  private void scheduleOffTick(Runnable task) {
    if (RTP.scheduler != null) {
      RTP.scheduler.runTaskAsynchronously(task);
    } else {
      // No scheduler wired (e.g. very early init) - run inline; still off the
      // main-thread chunk path since this touches no chunk I/O.
      task.run();
    }
  }

  // ---------------------------------------------------------------------------
  // Sweep-driven advance
  // ---------------------------------------------------------------------------

  /**
   * Advance the working set from the selector's cursor: prefetch the groups the
   * cursor is about to enter, release the groups behind it, and - only under a
   * budget breach - devolve the groups furthest from their predicted next access.
   */
  public void advance() {
    long[] upcomingBins = cursor.predictUpcomingBins(lookahead);
    LinkedHashSet<Long> upcomingGroups = new LinkedHashSet<>();
    for (long bin : upcomingBins) {
      upcomingGroups.add(groupOf(bin));
    }

    // Prefetch-on-approach (also rematerializes a devolved group about to be hit).
    for (long groupId : upcomingGroups) {
      loadStrideGroup(groupId);
    }

    // Release-behind: page out lossless-ly any resident group the cursor is not
    // about to enter. Preferred over devolution because the shard is retained.
    for (Long groupId : new ArrayList<>(groups.keySet())) {
      if (!upcomingGroups.contains(groupId) && isResidentExact(groupId)) {
        pageOut(groupId);
      }
    }

    enforceBudget(upcomingGroups);
  }

  /**
   * Devolve the resident groups furthest from their predicted next access until the
   * resident footprint is under budget. Only bitmask-tier groups are eligible; if no
   * eligible group remains, the working set is left as-is (sparse groups stay exact).
   */
  private void enforceBudget(LinkedHashSet<Long> upcomingGroups) {
    if (budgetBytes <= 0) return;

    // Predicted distance: index of first appearance in the upcoming order; groups
    // not upcoming are effectively infinitely far.
    List<Long> upcomingOrder = new ArrayList<>(upcomingGroups);
    while (totalResidentBytes() > budgetBytes) {
      long victim = -1L;
      long worstDistance = Long.MIN_VALUE;
      for (Long groupId : groups.keySet()) {
        if (!isResidentExact(groupId)) continue;
        GroupState g = groups.get(groupId);
        boolean eligible;
        synchronized (g) {
          eligible = isBitmaskTier(g);
        }
        if (!eligible) continue;
        int idx = upcomingOrder.indexOf(groupId);
        long distance = idx < 0 ? Long.MAX_VALUE : idx;
        if (distance > worstDistance) {
          worstDistance = distance;
          victim = groupId;
        }
      }
      if (victim < 0) {
        break; // nothing eligible to devolve
      }
      devolveToCount(victim);
    }
  }

  // ---------------------------------------------------------------------------
  // Introspection (residency accounting + test hooks)
  // ---------------------------------------------------------------------------

  public boolean isResidentExact(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return false;
    synchronized (g) {
      return g.resident != null;
    }
  }

  public boolean isDevolved(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return false;
    synchronized (g) {
      return g.isDevolved();
    }
  }

  public long devolvedCount(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return -1L;
    synchronized (g) {
      return g.devolvedCount;
    }
  }

  public long shardCount(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return 0L;
    synchronized (g) {
      return g.shard.size();
    }
  }

  public long residentGroupCount() {
    long n = 0L;
    for (Long groupId : groups.keySet()) {
      if (isResidentExact(groupId)) n++;
    }
    return n;
  }

  public long residentBytes(long groupId) {
    GroupState g = groups.get(groupId);
    if (g == null) return 0L;
    synchronized (g) {
      return g.resident == null ? 0L : g.resident.serializedSize();
    }
  }

  public long totalResidentBytes() {
    long total = 0L;
    for (Long groupId : groups.keySet()) {
      total += residentBytes(groupId);
    }
    return total;
  }

  private boolean isBitmaskTier(GroupState g) {
    double perBin = g.shard.size() / (double) binsPerGroup;
    return perBin >= devolveDensityFloor;
  }
}
