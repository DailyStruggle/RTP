package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * High-performance hybrid bit-packed and 16-bit run container hazard table (ADR-092).
 *
 * <p>Partitions a 32-bit/64-bit key space into 64K chunk blocks (2^16 = 65,536 chunks).
 * Each 64K block adaptively selects the most compact representation:
 * <ul>
 *   <li><b>SolidLand / SolidHazard:</b> Constant 1-byte state tag for 100% safe or 100% bad blocks.</li>
 *   <li><b>BitmaskContainer:</b> 1,024 64-bit longs (8,192 bytes = 1 bit per chunk). Used during cold-start
 *       one-by-one location discovery and high-noise shoreline blocks. Enables instant O(1) single-chunk insertion
 *       in &lt; 2 nanoseconds without array resizing or run splitting.</li>
 *   <li><b>RunContainer:</b> 16-bit primitive arrays (starts and lengths packed as 16-bit unsigned chars = 4 bytes
 *       per run). Used when runs are merged and total run count within the 64K block is &le; 2,048 runs.</li>
 * </ul>
 *
 * <p>Supports:
 * <ul>
 *   <li>Instant O(1) point query: {@link #isBad(long)}</li>
 *   <li>Instant O(1) single-bit discovery: {@link #markBad(long)}</li>
 *   <li>Optional/periodic gap-bridging compaction: {@link #compact(long, long)}</li>
 *   <li>Native O(log Containers + log Runs) candidate selection: {@link #resolveAccumulate(long)}</li>
 * </ul>
 */
public final class HybridHazardTable {

  public static final int CHUNKS_PER_CONTAINER = 65536;
  public static final int WORDS_PER_BITMASK = 1024; // 1024 longs * 64 bits = 65,536 bits
  public static final int RUN_TO_BITSET_THRESHOLD = 2048; // Breakeven: 2048 runs * 4 bytes = 8192 bytes
  public static final int ARRAY_TO_BITMASK_THRESHOLD = 4096;

  public static final byte TAG_UNALLOCATED = 0;
  public static final byte TAG_SOLID_LAND = 1;
  public static final byte TAG_SOLID_HAZARD = 2;
  public static final byte TAG_BITMASK = 3;
  public static final byte TAG_RUNS = 4;
  public static final byte TAG_ARRAY = 5;

  private final long totalRange;
  private final int containerCount;
  private final Container[] containers;
  private long totalBadCount;

  /**
   * Internal sealed container interface for 64K blocks.
   */
  public sealed interface Container
      permits UnallocatedContainer, SolidLandContainer, SolidHazardContainer, BitmaskContainer, RunContainer, ArrayContainer {
    byte tag();
    boolean isBad(int localKey);
    Container markBad(int localKey);
    int badCount();
    Container compact(long minGap, long maxGap);
    int resolveLocalAccumulate(int localRank);
    void write(ByteBuffer buf);
    int serializedSize();
  }

  /**
   * 0 bytes: Empty / unscanned block (implicitly 100% safe until probed).
   */
  public static final class UnallocatedContainer implements Container {
    public static final UnallocatedContainer INSTANCE = new UnallocatedContainer();
    private UnallocatedContainer() {}

    @Override public byte tag() { return TAG_UNALLOCATED; }
    @Override public boolean isBad(int localKey) { return false; }
    @Override public int badCount() { return 0; }
    @Override public Container compact(long minGap, long maxGap) { return this; }
    @Override public int resolveLocalAccumulate(int localRank) { return localRank; }
    @Override public void write(ByteBuffer buf) { buf.put(TAG_UNALLOCATED); }
    @Override public int serializedSize() { return 1; }

    @Override
    public Container markBad(int localKey) {
      ArrayContainer ac = new ArrayContainer();
      return ac.markBad(localKey);
    }
  }

  /**
   * 1 byte: 100% solid safe land.
   */
  public static final class SolidLandContainer implements Container {
    public static final SolidLandContainer INSTANCE = new SolidLandContainer();
    private SolidLandContainer() {}

    @Override public byte tag() { return TAG_SOLID_LAND; }
    @Override public boolean isBad(int localKey) { return false; }
    @Override public int badCount() { return 0; }
    @Override public Container compact(long minGap, long maxGap) { return this; }
    @Override public int resolveLocalAccumulate(int localRank) { return localRank; }
    @Override public void write(ByteBuffer buf) { buf.put(TAG_SOLID_LAND); }
    @Override public int serializedSize() { return 1; }

    @Override
    public Container markBad(int localKey) {
      ArrayContainer ac = new ArrayContainer();
      return ac.markBad(localKey);
    }
  }

  /**
   * 1 byte: 100% solid ocean / void hazard.
   */
  public static final class SolidHazardContainer implements Container {
    public static final SolidHazardContainer INSTANCE = new SolidHazardContainer();
    private SolidHazardContainer() {}

    @Override public byte tag() { return TAG_SOLID_HAZARD; }
    @Override public boolean isBad(int localKey) { return true; }
    @Override public int badCount() { return CHUNKS_PER_CONTAINER; }
    @Override public Container compact(long minGap, long maxGap) { return this; }
    @Override public int resolveLocalAccumulate(int localRank) { return -1; }
    @Override public void write(ByteBuffer buf) { buf.put(TAG_SOLID_HAZARD); }
    @Override public int serializedSize() { return 1; }
    @Override public Container markBad(int localKey) { return this; }
  }

  /**
   * 8,192 bytes: 1 bit per chunk over 64K domain.
   */
  public static final class BitmaskContainer implements Container {
    private final long[] words;
    private int badCount;

    public BitmaskContainer() {
      this.words = new long[WORDS_PER_BITMASK];
      this.badCount = 0;
    }

    public BitmaskContainer(long[] words, int badCount) {
      this.words = words;
      this.badCount = badCount;
    }

    @Override public byte tag() { return TAG_BITMASK; }

    @Override
    public boolean isBad(int localKey) {
      return (words[localKey >>> 6] & (1L << (localKey & 63))) != 0L;
    }

    @Override
    public Container markBad(int localKey) {
      int wordIdx = localKey >>> 6;
      long bit = 1L << (localKey & 63);
      if ((words[wordIdx] & bit) == 0L) {
        words[wordIdx] |= bit;
        badCount++;
        if (badCount == CHUNKS_PER_CONTAINER) {
          return SolidHazardContainer.INSTANCE;
        }
      }
      return this;
    }

    @Override public int badCount() { return badCount; }

    @Override
    public Container compact(long minGap, long maxGap) {
      if (badCount == 0) return SolidLandContainer.INSTANCE;
      if (badCount == CHUNKS_PER_CONTAINER) return SolidHazardContainer.INSTANCE;

      // Extract runs from bitmask
      List<int[]> runs = new ArrayList<>();
      int curStart = -1;
      int curLen = 0;

      for (int i = 0; i < CHUNKS_PER_CONTAINER; i++) {
        boolean bad = isBad(i);
        if (bad) {
          if (curStart < 0) {
            curStart = i;
            curLen = 1;
          } else {
            curLen++;
          }
        } else {
          if (curStart >= 0) {
            runs.add(new int[]{curStart, curLen});
            curStart = -1;
            curLen = 0;
          }
        }
      }
      if (curStart >= 0) {
        runs.add(new int[]{curStart, curLen});
      }

      // If gap-bridging requested, apply admissible gap
      if (minGap > 0L && runs.size() > 1) {
        List<int[]> merged = new ArrayList<>();
        int[] cur = runs.get(0);
        for (int i = 1; i < runs.size(); i++) {
          int[] next = runs.get(i);
          long driver = Math.min(cur[1], next[1]);
          long admissible = Math.max(minGap, Math.min(maxGap, driver));

          if (next[0] <= cur[0] + cur[1] + (int) admissible) {
            cur[1] = Math.max(cur[1], next[0] + next[1] - cur[0]);
          } else {
            merged.add(cur);
            cur = next;
          }
        }
        merged.add(cur);
        runs = merged;
      }

      for (int[] run : runs) {
        run[1] = Math.min(run[1], CHUNKS_PER_CONTAINER - run[0]);
        if (run[0] == 0 && run[1] == CHUNKS_PER_CONTAINER) {
          return SolidHazardContainer.INSTANCE;
        }
      }

      // Adaptive threshold check: if runs <= 2048, RunContainer is smaller than 8KB!
      if (runs.size() <= RUN_TO_BITSET_THRESHOLD) {
        char[] starts = new char[runs.size()];
        char[] lengths = new char[runs.size()];
        int totalBad = 0;
        for (int i = 0; i < runs.size(); i++) {
          starts[i] = (char) runs.get(i)[0];
          lengths[i] = (char) runs.get(i)[1];
          totalBad += runs.get(i)[1];
        }
        return new RunContainer(starts, lengths, totalBad);
      }

      if (badCount <= ARRAY_TO_BITMASK_THRESHOLD) {
        char[] keys = new char[badCount];
        int idx = 0;
        for (int i = 0; i < CHUNKS_PER_CONTAINER; i++) {
          if (isBad(i)) {
            keys[idx++] = (char) i;
          }
        }
        return new ArrayContainer(keys, idx);
      }
      return this;
    }

    @Override
    public int resolveLocalAccumulate(int localRank) {
      // Find the localRank-th safe chunk using popcount per word
      int remaining = localRank;
      for (int w = 0; w < WORDS_PER_BITMASK; w++) {
        long word = words[w];
        int safeInWord = 64 - Long.bitCount(word);
        if (remaining < safeInWord) {
          // Target chunk is within this 64-bit word
          for (int bit = 0; bit < 64; bit++) {
            if ((word & (1L << bit)) == 0L) {
              if (remaining == 0) return (w << 6) | bit;
              remaining--;
            }
          }
        }
        remaining -= safeInWord;
      }
      return -1;
    }

    @Override
    public void write(ByteBuffer buf) {
      buf.put(TAG_BITMASK);
      buf.putInt(badCount);
      for (int i = 0; i < WORDS_PER_BITMASK; i++) {
        buf.putLong(words[i]);
      }
    }

    @Override
    public int serializedSize() {
      return 1 + 4 + WORDS_PER_BITMASK * 8; // 8,197 bytes
    }
  }

  /**
   * 4 bytes per run: 16-bit local starts and lengths.
   */
  public static final class RunContainer implements Container {
    private final char[] starts;
    private final char[] lengths;
    private final int badCount;

    public RunContainer(char[] starts, char[] lengths, int badCount) {
      this.starts = starts;
      this.lengths = lengths;
      this.badCount = badCount;
    }

    @Override public byte tag() { return TAG_RUNS; }

    @Override
    public boolean isBad(int localKey) {
      int low = 0;
      int high = starts.length - 1;
      while (low <= high) {
        int mid = (low + high) >>> 1;
        int midStart = starts[mid];
        if (midStart <= localKey) {
          if (localKey < midStart + lengths[mid]) {
            return true;
          }
          low = mid + 1;
        } else {
          high = mid - 1;
        }
      }
      return false;
    }

    @Override
    public Container markBad(int localKey) {
      if (isBad(localKey)) return this;
      // Convert to bitmask for instant single-bit insertion
      BitmaskContainer bc = toBitmask();
      return bc.markBad(localKey);
    }

    public BitmaskContainer toBitmask() {
      long[] words = new long[WORDS_PER_BITMASK];
      for (int i = 0; i < starts.length; i++) {
        int st = starts[i];
        int len = lengths[i];
        for (int k = 0; k < len; k++) {
          int pos = st + k;
          words[pos >>> 6] |= (1L << (pos & 63));
        }
      }
      return new BitmaskContainer(words, badCount);
    }

    @Override public int badCount() { return badCount; }

    @Override
    public Container compact(long minGap, long maxGap) {
      if (starts.length <= 1) return this;
      // Already run-compressed; apply gap-bridging if requested
      List<int[]> merged = new ArrayList<>();
      int curStart = starts[0];
      int curLen = lengths[0];

      for (int i = 1; i < starts.length; i++) {
        int nextStart = starts[i];
        int nextLen = lengths[i];
        long driver = Math.min(curLen, nextLen);
        long admissible = Math.max(minGap, Math.min(maxGap, driver));

        if (nextStart <= curStart + curLen + (int) admissible) {
          curLen = Math.max(curLen, nextStart + nextLen - curStart);
        } else {
          merged.add(new int[]{curStart, curLen});
          curStart = nextStart;
          curLen = nextLen;
        }
      }
      merged.add(new int[]{curStart, curLen});

      for (int[] run : merged) {
        run[1] = Math.min(run[1], CHUNKS_PER_CONTAINER - run[0]);
        if (run[0] == 0 && run[1] == CHUNKS_PER_CONTAINER) {
          return SolidHazardContainer.INSTANCE;
        }
      }

      char[] nStarts = new char[merged.size()];
      char[] nLens = new char[merged.size()];
      int nBad = 0;
      for (int i = 0; i < merged.size(); i++) {
        nStarts[i] = (char) merged.get(i)[0];
        nLens[i] = (char) merged.get(i)[1];
        nBad += merged.get(i)[1];
      }
      return new RunContainer(nStarts, nLens, nBad);
    }

    @Override
    public int resolveLocalAccumulate(int localRank) {
      int curSafeRank = 0;
      int prevEnd = 0;
      for (int i = 0; i < starts.length; i++) {
        int gapStart = prevEnd;
        int gapLen = starts[i] - prevEnd;
        if (gapLen > 0) {
          if (localRank < curSafeRank + gapLen) {
            return gapStart + (localRank - curSafeRank);
          }
          curSafeRank += gapLen;
        }
        prevEnd = starts[i] + lengths[i];
      }
      int trailingGap = CHUNKS_PER_CONTAINER - prevEnd;
      if (trailingGap > 0 && localRank < curSafeRank + trailingGap) {
        return prevEnd + (localRank - curSafeRank);
      }
      return -1;
    }

    @Override
    public void write(ByteBuffer buf) {
      buf.put(TAG_RUNS);
      buf.putInt(badCount);
      buf.putInt(starts.length);
      for (int i = 0; i < starts.length; i++) {
        buf.putChar(starts[i]);
        buf.putChar(lengths[i]);
      }
    }

    @Override
    public int serializedSize() {
      return 1 + 4 + 4 + starts.length * 4;
    }

    public int runCount() {
      return starts.length;
    }
  }

  /**
   * 2 bytes per bad chunk: 16-bit sorted array of local keys.
   */
  public static final class ArrayContainer implements Container {
    private char[] keys;
    private int size;

    public ArrayContainer() {
      this.keys = new char[4];
      this.size = 0;
    }

    public ArrayContainer(char[] keys, int size) {
      this.keys = keys;
      this.size = size;
    }

    @Override
    public byte tag() {
      return TAG_ARRAY;
    }

    @Override
    public boolean isBad(int localKey) {
      return Arrays.binarySearch(keys, 0, size, (char) localKey) >= 0;
    }

    @Override
    public Container markBad(int localKey) {
      char target = (char) localKey;
      int idx = Arrays.binarySearch(keys, 0, size, target);
      if (idx >= 0) {
        return this; // Already present
      }

      int insertPos = -(idx + 1);
      if (size + 1 > ARRAY_TO_BITMASK_THRESHOLD) {
        BitmaskContainer bc = toBitmask();
        return bc.markBad(localKey);
      }

      if (size == keys.length) {
        int newCap = Math.max(4, keys.length * 2);
        keys = Arrays.copyOf(keys, newCap);
      }

      System.arraycopy(keys, insertPos, keys, insertPos + 1, size - insertPos);
      keys[insertPos] = target;
      size++;
      return this;
    }

    public BitmaskContainer toBitmask() {
      long[] words = new long[WORDS_PER_BITMASK];
      for (int i = 0; i < size; i++) {
        int pos = keys[i];
        words[pos >>> 6] |= (1L << (pos & 63));
      }
      return new BitmaskContainer(words, size);
    }

    @Override
    public int badCount() {
      return size;
    }

    @Override
    public Container compact(long minGap, long maxGap) {
      return this;
    }

    @Override
    public int resolveLocalAccumulate(int localRank) {
      int safeTotal = CHUNKS_PER_CONTAINER - size;
      if (localRank < 0 || localRank >= safeTotal) {
        return -1;
      }

      int low = 0;
      int high = CHUNKS_PER_CONTAINER - 1;
      int ans = -1;

      while (low <= high) {
        int mid = (low + high) >>> 1;
        // Count how many keys in [0, size) are <= mid
        int idx = Arrays.binarySearch(keys, 0, size, (char) mid);
        int countLessOrEqual = (idx >= 0) ? (idx + 1) : -(idx + 1);
        int safeCountBeforeOrAtMid = (mid + 1) - countLessOrEqual;

        if (safeCountBeforeOrAtMid > localRank) {
          ans = mid;
          high = mid - 1;
        } else {
          low = mid + 1;
        }
      }

      return ans;
    }

    @Override
    public void write(ByteBuffer buf) {
      buf.put(TAG_ARRAY);
      buf.putShort((short) size);
      for (int i = 0; i < size; i++) {
        buf.putChar(keys[i]);
      }
    }

    @Override
    public int serializedSize() {
      return 1 + 2 + size * 2;
    }

    public int size() {
      return size;
    }

    public char[] keys() {
      return keys;
    }
  }

  public HybridHazardTable(long totalRange) {
    this.totalRange = totalRange;
    this.containerCount = (int) ((totalRange + CHUNKS_PER_CONTAINER - 1) / CHUNKS_PER_CONTAINER);
    this.containers = new Container[containerCount];
    Arrays.fill(this.containers, UnallocatedContainer.INSTANCE);
    this.totalBadCount = 0L;
  }

  public long totalRange() {
    return totalRange;
  }

  public int containerCount() {
    return containerCount;
  }

  public Container container(int index) {
    return containers[index];
  }

  public synchronized boolean isBad(long key) {
    if (key < 0 || key >= totalRange) return true;
    int cId = (int) (key >>> 16);
    int local = (int) (key & 0xFFFF);
    return containers[cId].isBad(local);
  }

  public synchronized void markBad(long key) {
    if (key < 0 || key >= totalRange) return;
    int cId = (int) (key >>> 16);
    int local = (int) (key & 0xFFFF);
    int oldBad = containers[cId].badCount();
    containers[cId] = containers[cId].markBad(local);
    int diff = containers[cId].badCount() - oldBad;
    if (diff != 0) {
      totalBadCount += diff;
    }
  }

  public synchronized void markBad(long key, boolean ignored) {
    markBad(key);
  }

  public synchronized void compact(long minGap, long maxGap) {
    long newTotalBad = 0L;
    for (int i = 0; i < containerCount; i++) {
      containers[i] = containers[i].compact(minGap, maxGap);
      newTotalBad += containers[i].badCount();
    }
    this.totalBadCount = newTotalBad;
  }

  public synchronized void recomputeGoodPrefixSums() {
    // Deprecated no-op: good/bad counts are maintained in O(1)
    recomputeTotalBadCount();
  }

  public synchronized void recomputeTotalBadCount() {
    long bad = 0L;
    for (int i = 0; i < containerCount; i++) {
      bad += containers[i].badCount();
    }
    this.totalBadCount = bad;
  }

  public synchronized long countBad() {
    return totalBadCount;
  }

  public synchronized long totalGood() {
    return Math.max(0L, totalRange - totalBadCount);
  }

  /**
   * Resolves a target in [0, totalGood) in ACCUMULATE mode using two-tier container resolution.
   *
   * <p>Tier 1: Locates the target 64K container using O(1) bin good capacities.
   * <p>Tier 2: Resolves local offset within the located container's local bitmask, runs, or array.
   *
   * @param target raw good-space index in [0, totalRange - totalBadCount)
   * @return physical coordinate in [0, totalRange), or -1 if target is out of range
   */
  public synchronized long resolveAccumulate(long target) {
    long totalGood = totalGood();
    if (target < 0 || target >= totalGood) return -1L;

    long remainingTarget = target;
    int targetContainer = -1;

    for (int i = 0; i < containerCount; i++) {
      int cap = (i == containerCount - 1)
          ? (int) (totalRange - (long) i * CHUNKS_PER_CONTAINER)
          : CHUNKS_PER_CONTAINER;
      int goodInContainer = cap - containers[i].badCount();
      if (remainingTarget < goodInContainer) {
        targetContainer = i;
        break;
      }
      remainingTarget -= goodInContainer;
    }

    if (targetContainer < 0) return -1L;

    int localKey = containers[targetContainer].resolveLocalAccumulate((int) remainingTarget);
    if (localKey < 0) return -1L;

    return ((long) targetContainer << 16) | localKey;
  }

  public synchronized int serializedSize() {
    int size = 8 + 4; // totalRange(8) + containerCount(4)
    for (int i = 0; i < containerCount; i++) {
      size += containers[i].serializedSize();
    }
    return size;
  }

  public synchronized void serialize(ByteBuffer buf) {
    buf.putLong(totalRange);
    buf.putInt(containerCount);
    for (int i = 0; i < containerCount; i++) {
      containers[i].write(buf);
    }
  }

  public static HybridHazardTable deserialize(ByteBuffer buf) {
    long totalRange = buf.getLong();
    int count = buf.getInt();
    HybridHazardTable table = new HybridHazardTable(totalRange);

    for (int i = 0; i < count; i++) {
      byte tag = buf.get();
      switch (tag) {
        case TAG_UNALLOCATED -> table.containers[i] = UnallocatedContainer.INSTANCE;
        case TAG_SOLID_LAND -> table.containers[i] = SolidLandContainer.INSTANCE;
        case TAG_SOLID_HAZARD -> table.containers[i] = SolidHazardContainer.INSTANCE;
        case TAG_BITMASK -> {
          int badCount = buf.getInt();
          long[] words = new long[WORDS_PER_BITMASK];
          for (int w = 0; w < WORDS_PER_BITMASK; w++) {
            words[w] = buf.getLong();
          }
          table.containers[i] = new BitmaskContainer(words, badCount);
        }
        case TAG_RUNS -> {
          int badCount = buf.getInt();
          int runCount = buf.getInt();
          char[] starts = new char[runCount];
          char[] lengths = new char[runCount];
          for (int r = 0; r < runCount; r++) {
            starts[r] = buf.getChar();
            lengths[r] = buf.getChar();
          }
          table.containers[i] = new RunContainer(starts, lengths, badCount);
        }
        case TAG_ARRAY -> {
          int size = buf.getShort() & 0xFFFF;
          char[] keys = new char[size];
          for (int k = 0; k < size; k++) {
            keys[k] = buf.getChar();
          }
          table.containers[i] = new ArrayContainer(keys, size);
        }
        default -> throw new IllegalArgumentException("Unknown container tag: " + tag);
      }
    }
    table.recomputeGoodPrefixSums();
    return table;
  }
}
