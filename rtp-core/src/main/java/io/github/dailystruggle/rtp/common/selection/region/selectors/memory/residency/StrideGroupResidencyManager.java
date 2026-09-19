package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.residency;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.anvil.StorageLatencyProbe;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.HybridHazardTable;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

  /** File format magic number for stride-sharded batch format ('STRD'). */
  public static final int BATCH_FILE_MAGIC = 0x53545244;
  /** Current batch format version. */
  public static final int BATCH_FILE_VERSION = 1;

  private final SweepCursor cursor;
  private final long range;
  private final long binsPerGroup;
  private final long keysPerGroup;
  private final long budgetBytes;
  private final int lookahead;
  private final double devolveDensityFloor;

  private final Map<Long, GroupState> groups = new ConcurrentHashMap<>();

  /** Monitored disk latency in nanoseconds for the most recent batch file operation. */
  private final AtomicLong lastDiskOpNanos = new AtomicLong(0L);
  /** Monitored quota scan latency in nanoseconds for the most recent quota scan. */
  private final AtomicLong lastQuotaScanNanos = new AtomicLong(0L);

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
   * Checks whether the given key belongs to a stride group that is currently resident in RAM
   * and can have its resident table updated immediately.
   *
   * @param key global spiral key
   * @return {@code true} if the key's group is currently resident and updatable
   */
  public boolean canUpdate(long key) {
    if (key < 0 || key >= range) return false;
    return isResidentExact(groupOfKey(key));
  }

  /**
   * Selectively updates the resident table for {@code key} only if the key's stride group
   * is currently resident in memory. Always records into the authoritative persistent shard.
   *
   * @param key global spiral key
   * @return {@code true} if the resident table in RAM was updated, {@code false} if non-resident
   */
  public boolean updateIfResident(long key) {
    if (key < 0 || key >= range) return false;
    GroupState g = state(groupOfKey(key));
    synchronized (g) {
      boolean added = g.shard.add(key);
      if (g.resident != null) {
        g.resident.markBad(key - groupStartKey(g.groupId));
        return true;
      }
      if (added && g.isDevolved()) {
        g.devolvedCount = g.shard.size();
      }
      return false;
    }
  }

  /**
   * Records a bad key. The mark lands in the group's persistent shard first (so it
   * survives page-out and devolution) and, when the group is resident-exact, in the
   * resident table too. Never silently discarded.
   */
  public void markBad(long key) {
    updateIfResident(key);
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
    long t0 = System.nanoTime();
    long scanned = 0L;
    for (Long ignored : g.shard) {
      scanned++;
    }
    long elapsed = System.nanoTime() - t0;
    lastQuotaScanNanos.set(elapsed);
    long prior = g.devolvedCount;
    return Math.max(prior, scanned);
  }

  /**
   * Records elapsed disk operation time in nanoseconds and updates probe statistics.
   *
   * @param nanos elapsed nanoseconds
   * @param bytes bytes transferred
   */
  public void recordDiskTime(long nanos, int bytes) {
    lastDiskOpNanos.set(nanos);
    StorageLatencyProbe.record(nanos, bytes);
  }

  /**
   * Returns the most recent disk operation latency in nanoseconds.
   */
  public long getLastDiskOpNanos() {
    return lastDiskOpNanos.get();
  }

  /**
   * Returns the most recent quota scan latency in nanoseconds.
   */
  public long getLastQuotaScanNanos() {
    return lastQuotaScanNanos.get();
  }

  /**
   * Checks whether monitored disk operations are slower than quota filling.
   * If disk is slower than quota filling (or storage probe classifies disk as SLOW or HDD),
   * residency should fall back to quota filling / count devolution rather than disk paging.
   *
   * @return {@code true} if disk is slower than quota filling
   */
  public boolean isDiskSlowerThanQuotaFilling() {
    StorageLatencyProbe.Device device = StorageLatencyProbe.classifyByLatency();
    if (device == StorageLatencyProbe.Device.HDD || device == StorageLatencyProbe.Device.SLOW) {
      return true;
    }
    long diskNanos = lastDiskOpNanos.get();
    long quotaNanos = lastQuotaScanNanos.get();
    if (diskNanos > 0L && quotaNanos > 0L) {
      return diskNanos > quotaNanos;
    }
    return false;
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

  // ---------------------------------------------------------------------------
  // Stride batch persistence & arbitrary lookup mapping table
  // ---------------------------------------------------------------------------

  /**
   * Directory entry inside the mapping table for arbitrary lookup of a stride group shard.
   */
  public static final class ShardDirectoryEntry {
    public final long groupId;
    public final long fileOffset;
    public final int payloadLength;
    public final int shardCount;
    public final long devolvedCount;

    public ShardDirectoryEntry(long groupId, long fileOffset, int payloadLength, int shardCount, long devolvedCount) {
      this.groupId = groupId;
      this.fileOffset = fileOffset;
      this.payloadLength = payloadLength;
      this.shardCount = shardCount;
      this.devolvedCount = devolvedCount;
    }
  }

  /**
   * Saves all known stride groups to {@code file} in stride-sharded batch format
   * along with a header mapping table directory for arbitrary $O(1)$ group lookup.
   *
   * @param file target file
   * @param stride the stride parameter governing the batch
   * @throws IOException on I/O error
   */
  public void saveBatchShards(File file, long stride) throws IOException {
    long t0 = System.nanoTime();
    List<Long> groupIds = new ArrayList<>(groups.keySet());
    Collections.sort(groupIds);
    int groupCount = groupIds.size();

    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }

    int bytesWritten = 0;
    long directoryOffset = 0L;
    try (FileOutputStream fos = new FileOutputStream(file);
         BufferedOutputStream bos = new BufferedOutputStream(fos);
         DataOutputStream dos = new DataOutputStream(bos)) {

      // Header placeholder:
      // magic (4) + version (4) + stride (8) + range (8) + binsPerGroup (8) + groupCount (4) + directoryOffset (8) = 44 bytes
      dos.writeInt(BATCH_FILE_MAGIC);
      dos.writeInt(BATCH_FILE_VERSION);
      dos.writeLong(stride);
      dos.writeLong(range);
      dos.writeLong(binsPerGroup);
      dos.writeInt(groupCount);
      dos.writeLong(0L); // placeholder for directoryOffset
      dos.flush();
      bytesWritten += 44;

      List<ShardDirectoryEntry> entries = new ArrayList<>(groupCount);
      long currentOffset = 44L;

      for (Long gid : groupIds) {
        GroupState g = groups.get(gid);
        List<Long> keys;
        long devCount;
        synchronized (g) {
          keys = new ArrayList<>(g.shard);
          devCount = g.devolvedCount;
        }

        int count = keys.size();
        int payloadLen = 4 + count * 8; // int count + long[count]
        entries.add(new ShardDirectoryEntry(gid, currentOffset, payloadLen, count, devCount));

        dos.writeInt(count);
        for (Long k : keys) {
          dos.writeLong(k);
        }
        currentOffset += payloadLen;
        bytesWritten += payloadLen;
      }
      dos.flush();

      // Write Mapping Table Directory
      directoryOffset = currentOffset;
      for (ShardDirectoryEntry entry : entries) {
        dos.writeLong(entry.groupId);
        dos.writeLong(entry.fileOffset);
        dos.writeInt(entry.payloadLength);
        dos.writeInt(entry.shardCount);
        dos.writeLong(entry.devolvedCount);
        bytesWritten += 32;
      }
      dos.flush();
    }

    // Backpatch directoryOffset in header
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(36L); // offset where directoryOffset is written
      raf.writeLong(directoryOffset);
    }

    long elapsed = System.nanoTime() - t0;
    recordDiskTime(elapsed, bytesWritten);
  }

  /**
   * Arbitrary lookup: seeks directly via the mapping table to read only the shard
   * for {@code targetGroupId} from {@code file} without parsing other stride groups into heap.
   *
   * @param file batch file
   * @param targetGroupId group ID to look up
   * @return list of bad keys in the shard, or {@code null} if group not in file
   * @throws IOException on I/O error
   */
  public static List<Long> readBatchShardArbitrary(File file, long targetGroupId) throws IOException {
    long t0 = System.nanoTime();
    if (!file.exists()) return null;

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      if (raf.length() < 44L) return null;
      int magic = raf.readInt();
      if (magic != BATCH_FILE_MAGIC) {
        throw new IOException("Invalid batch file magic: " + Integer.toHexString(magic));
      }
      int version = raf.readInt();
      if (version != BATCH_FILE_VERSION) {
        throw new IOException("Unsupported batch file version: " + version);
      }
      raf.readLong(); // stride
      raf.readLong(); // range
      raf.readLong(); // binsPerGroup
      int groupCount = raf.readInt();
      long directoryOffset = raf.readLong();

      if (directoryOffset <= 0L || directoryOffset >= raf.length()) {
        return null;
      }

      // Seek directly to directory
      raf.seek(directoryOffset);
      long payloadOffset = -1L;
      int payloadLength = 0;
      int shardCount = 0;

      for (int i = 0; i < groupCount; i++) {
        long gid = raf.readLong();
        long off = raf.readLong();
        int len = raf.readInt();
        int cnt = raf.readInt();
        raf.readLong(); // devolvedCount

        if (gid == targetGroupId) {
          payloadOffset = off;
          payloadLength = len;
          shardCount = cnt;
          break;
        }
      }

      if (payloadOffset < 0L) {
        return null;
      }

      // Seek directly to the shard payload
      raf.seek(payloadOffset);
      int count = raf.readInt();
      List<Long> keys = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        keys.add(raf.readLong());
      }

      long elapsed = System.nanoTime() - t0;
      StorageLatencyProbe.record(elapsed, payloadLength);
      return keys;
    }
  }

  /**
   * Reads and merges a batch shard for {@code groupId} into this manager using arbitrary lookup.
   *
   * @param file batch file
   * @param groupId target group ID
   * @return {@code true} if shard was found and merged
   * @throws IOException on I/O error
   */
  public boolean loadBatchShard(File file, long groupId) throws IOException {
    List<Long> keys = readBatchShardArbitrary(file, groupId);
    if (keys == null) return false;
    for (Long k : keys) {
      markBad(k);
    }
    return true;
  }
}
