package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 1:1 Anvil Region Bin Hazard Table (ADR-092).
 *
 * <p>Each bin maps 1:1 to a 32x32 chunk Minecraft Anvil (.mca) region file (1,024 chunks).
 * Discarded regions (100% deep ocean, void, or 16/16 rejected trials) are treated as
 * "Full Bins" ({@code covered == 1024}) with zero internal run or bitmask allocations.
 *
 * <p>Mixed bins adaptively store either:
 * <ul>
 *   <li><b>16-bit Run Container:</b> 4 bytes per run when runs &le; 32.</li>
 *   <li><b>16-Long Bitmask:</b> 128 bytes (16 longs) when runs &gt; 32 or during cold-start.</li>
 * </ul>
 */
public final class AnvilRegionBinHazardTable {

  public static final int CHUNKS_PER_BIN = 1024; // 32x32 chunks
  public static final int LONGS_PER_BIN = 16;    // 16 longs * 64 bits = 1024 bits
  public static final int RLE_BREAKEVEN_RUNS = 32; // 32 runs * 4 bytes = 128 bytes (bitmask size)

  public static final byte TAG_UNALLOCATED = 0; // 0 chunks bad
  public static final byte TAG_FULL_DISCARDED = 1; // 1,024 chunks bad (Full Bin, ocean/void)
  public static final byte TAG_BITMASK = 2; // 16 longs = 128 bytes
  public static final byte TAG_RUNS = 3; // char[] starts, char[] lens

  private final long totalRange;
  private final int binCount;
  private final BinContainer[] bins;
  private final long[] binGoodPrefixSums;
  private long totalGoodCount;

  public sealed interface BinContainer
      permits UnallocatedBin, FullDiscardedBin, BitmaskBin, RunBin {
    byte tag();
    boolean isBad(int localOffset);
    BinContainer markBad(int localOffset);
    int badCount();
    BinContainer compact(long minGap, long maxGap);
    int resolveLocalAccumulate(int localRank);
    void write(ByteBuffer buf);
    int serializedSize();
  }

  /**
   * 0 chunks bad: Unscanned / untouched bin (100% safe).
   */
  public static final class UnallocatedBin implements BinContainer {
    public static final UnallocatedBin INSTANCE = new UnallocatedBin();
    private UnallocatedBin() {}

    @Override public byte tag() { return TAG_UNALLOCATED; }
    @Override public boolean isBad(int localOffset) { return false; }
    @Override public int badCount() { return 0; }
    @Override public BinContainer compact(long minGap, long maxGap) { return this; }
    @Override public int resolveLocalAccumulate(int localRank) { return localRank; }
    @Override public void write(ByteBuffer buf) { buf.put(TAG_UNALLOCATED); }
    @Override public int serializedSize() { return 1; }

    @Override
    public BinContainer markBad(int localOffset) {
      BitmaskBin b = new BitmaskBin();
      return b.markBad(localOffset);
    }
  }

  /**
   * 1,024 chunks bad: Solid deep ocean / void / discarded region file.
   * Zero memory overhead!
   */
  public static final class FullDiscardedBin implements BinContainer {
    public static final FullDiscardedBin INSTANCE = new FullDiscardedBin();
    private FullDiscardedBin() {}

    @Override public byte tag() { return TAG_FULL_DISCARDED; }
    @Override public boolean isBad(int localOffset) { return true; }
    @Override public int badCount() { return CHUNKS_PER_BIN; }
    @Override public BinContainer compact(long minGap, long maxGap) { return this; }
    @Override public int resolveLocalAccumulate(int localRank) { return -1; }
    @Override public void write(ByteBuffer buf) { buf.put(TAG_FULL_DISCARDED); }
    @Override public int serializedSize() { return 1; }
    @Override public BinContainer markBad(int localOffset) { return this; }
  }

  /**
   * 128 bytes: 16 longs bitmask for mixed or cold-start bins.
   */
  public static final class BitmaskBin implements BinContainer {
    private final long[] words;
    private int badCount;

    public BitmaskBin() {
      this.words = new long[LONGS_PER_BIN];
      this.badCount = 0;
    }

    public BitmaskBin(long[] words, int badCount) {
      this.words = words;
      this.badCount = badCount;
    }

    @Override public byte tag() { return TAG_BITMASK; }

    @Override
    public boolean isBad(int localOffset) {
      return (words[localOffset >>> 6] & (1L << (localOffset & 63))) != 0L;
    }

    @Override
    public BinContainer markBad(int localOffset) {
      int w = localOffset >>> 6;
      long mask = 1L << (localOffset & 63);
      if ((words[w] & mask) == 0L) {
        words[w] |= mask;
        badCount++;
        if (badCount == CHUNKS_PER_BIN) {
          return FullDiscardedBin.INSTANCE; // Collapses into Full Bin!
        }
      }
      return this;
    }

    @Override public int badCount() { return badCount; }

    @Override
    public BinContainer compact(long minGap, long maxGap) {
      if (badCount == 0) return UnallocatedBin.INSTANCE;
      if (badCount == CHUNKS_PER_BIN) return FullDiscardedBin.INSTANCE;

      List<int[]> runs = new ArrayList<>();
      int curStart = -1, curLen = 0;

      for (int i = 0; i < CHUNKS_PER_BIN; i++) {
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

      // If runs <= 32, RLE takes <= 128 bytes (smaller than bitmask!)
      if (runs.size() <= RLE_BREAKEVEN_RUNS) {
        char[] starts = new char[runs.size()];
        char[] lens = new char[runs.size()];
        int bCount = 0;
        for (int i = 0; i < runs.size(); i++) {
          starts[i] = (char) runs.get(i)[0];
          lens[i] = (char) runs.get(i)[1];
          bCount += runs.get(i)[1];
        }
        if (bCount >= CHUNKS_PER_BIN) return FullDiscardedBin.INSTANCE;
        return new RunBin(starts, lens, bCount);
      }

      return this; // Stay in bitmask mode
    }

    @Override
    public int resolveLocalAccumulate(int localRank) {
      int remaining = localRank;
      for (int w = 0; w < LONGS_PER_BIN; w++) {
        long word = words[w];
        int safeInWord = 64 - Long.bitCount(word);
        if (remaining < safeInWord) {
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
      buf.putShort((short) badCount);
      for (int i = 0; i < LONGS_PER_BIN; i++) {
        buf.putLong(words[i]);
      }
    }

    @Override
    public int serializedSize() {
      return 1 + 2 + LONGS_PER_BIN * 8; // 131 bytes
    }
  }

  /**
   * 4 bytes per run: 16-bit local starts and lengths inside 1,024-chunk bin.
   */
  public static final class RunBin implements BinContainer {
    private final char[] starts;
    private final char[] lengths;
    private final int badCount;

    public RunBin(char[] starts, char[] lengths, int badCount) {
      this.starts = starts;
      this.lengths = lengths;
      this.badCount = badCount;
    }

    @Override public byte tag() { return TAG_RUNS; }

    @Override
    public boolean isBad(int localOffset) {
      int low = 0, high = starts.length - 1;
      while (low <= high) {
        int mid = (low + high) >>> 1;
        int mStart = starts[mid];
        if (mStart <= localOffset) {
          if (localOffset < mStart + lengths[mid]) return true;
          low = mid + 1;
        } else {
          high = mid - 1;
        }
      }
      return false;
    }

    @Override
    public BinContainer markBad(int localOffset) {
      if (isBad(localOffset)) return this;
      BitmaskBin bb = toBitmask();
      return bb.markBad(localOffset);
    }

    public BitmaskBin toBitmask() {
      long[] words = new long[LONGS_PER_BIN];
      for (int i = 0; i < starts.length; i++) {
        int st = starts[i];
        int len = lengths[i];
        for (int k = 0; k < len; k++) {
          int pos = st + k;
          words[pos >>> 6] |= (1L << (pos & 63));
        }
      }
      return new BitmaskBin(words, badCount);
    }

    @Override public int badCount() { return badCount; }

    @Override
    public BinContainer compact(long minGap, long maxGap) {
      if (starts.length <= 1) return this;
      List<int[]> merged = new ArrayList<>();
      int curStart = starts[0];
      int curLen = lengths[0];

      for (int i = 1; i < starts.length; i++) {
        int nStart = starts[i];
        int nLen = lengths[i];
        long driver = Math.min(curLen, nLen);
        long admissible = Math.max(minGap, Math.min(maxGap, driver));

        if (nStart <= curStart + curLen + (int) admissible) {
          curLen = Math.max(curLen, nStart + nLen - curStart);
        } else {
          merged.add(new int[]{curStart, curLen});
          curStart = nStart;
          curLen = nLen;
        }
      }
      merged.add(new int[]{curStart, curLen});

      char[] nStarts = new char[merged.size()];
      char[] nLens = new char[merged.size()];
      int bCount = 0;
      for (int i = 0; i < merged.size(); i++) {
        nStarts[i] = (char) merged.get(i)[0];
        nLens[i] = (char) merged.get(i)[1];
        bCount += merged.get(i)[1];
      }
      if (bCount >= CHUNKS_PER_BIN) return FullDiscardedBin.INSTANCE;
      return new RunBin(nStarts, nLens, bCount);
    }

    @Override
    public int resolveLocalAccumulate(int localRank) {
      int curSafeRank = 0;
      int prevEnd = 0;
      for (int i = 0; i < starts.length; i++) {
        int gapLen = starts[i] - prevEnd;
        if (gapLen > 0) {
          if (localRank < curSafeRank + gapLen) {
            return prevEnd + (localRank - curSafeRank);
          }
          curSafeRank += gapLen;
        }
        prevEnd = starts[i] + lengths[i];
      }
      int trailingGap = CHUNKS_PER_BIN - prevEnd;
      if (trailingGap > 0 && localRank < curSafeRank + trailingGap) {
        return prevEnd + (localRank - curSafeRank);
      }
      return -1;
    }

    @Override
    public void write(ByteBuffer buf) {
      buf.put(TAG_RUNS);
      buf.putShort((short) badCount);
      buf.putShort((short) starts.length);
      for (int i = 0; i < starts.length; i++) {
        buf.putChar(starts[i]);
        buf.putChar(lengths[i]);
      }
    }

    @Override
    public int serializedSize() {
      return 1 + 2 + 2 + starts.length * 4;
    }

    public int runCount() {
      return starts.length;
    }
  }

  public AnvilRegionBinHazardTable(long totalRange) {
    this.totalRange = totalRange;
    this.binCount = (int) ((totalRange + CHUNKS_PER_BIN - 1) / CHUNKS_PER_BIN);
    this.bins = new BinContainer[binCount];
    Arrays.fill(this.bins, UnallocatedBin.INSTANCE);
    this.binGoodPrefixSums = new long[binCount];
    recomputeGoodPrefixSums();
  }

  public long totalRange() {
    return totalRange;
  }

  public int binCount() {
    return binCount;
  }

  public BinContainer bin(int index) {
    return bins[index];
  }

  /**
   * Marks an entire MCA bin as a discarded / full hazard bin (e.g. 100% deep ocean or 16/16 rejected).
   */
  public synchronized void discardBin(int binIndex) {
    if (binIndex < 0 || binIndex >= binCount) return;
    bins[binIndex] = FullDiscardedBin.INSTANCE;
  }

  public synchronized boolean isBinDiscarded(int binIndex) {
    if (binIndex < 0 || binIndex >= binCount) return true;
    return bins[binIndex] == FullDiscardedBin.INSTANCE || bins[binIndex].badCount() == CHUNKS_PER_BIN;
  }

  public synchronized boolean isBad(long key) {
    if (key < 0 || key >= totalRange) return true;
    int bId = (int) (key >>> 10); // key / 1024
    int local = (int) (key & 1023); // key % 1024
    return bins[bId].isBad(local);
  }

  public synchronized void markBad(long key) {
    if (key < 0 || key >= totalRange) return;
    int bId = (int) (key >>> 10);
    int local = (int) (key & 1023);
    bins[bId] = bins[bId].markBad(local);
  }

  public synchronized void compact(long minGap, long maxGap) {
    for (int i = 0; i < binCount; i++) {
      bins[i] = bins[i].compact(minGap, maxGap);
    }
    recomputeGoodPrefixSums();
  }

  public synchronized void recomputeGoodPrefixSums() {
    long sum = 0L;
    for (int i = 0; i < binCount; i++) {
      int cap = (i == binCount - 1)
          ? (int) (totalRange - (long) i * CHUNKS_PER_BIN)
          : CHUNKS_PER_BIN;
      int good = Math.max(0, cap - bins[i].badCount());
      sum += good;
      binGoodPrefixSums[i] = sum;
    }
    this.totalGoodCount = sum;
  }

  public long totalGood() {
    return totalGoodCount;
  }

  public synchronized long resolveAccumulate(long virtualRank) {
    if (virtualRank < 0 || virtualRank >= totalGoodCount) return -1L;

    int low = 0, high = binCount - 1, targetBin = -1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      long midSum = binGoodPrefixSums[mid];
      if (midSum > virtualRank) {
        targetBin = mid;
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }

    if (targetBin < 0) return -1L;

    long prevSum = (targetBin > 0) ? binGoodPrefixSums[targetBin - 1] : 0L;
    int localRank = (int) (virtualRank - prevSum);

    int localKey = bins[targetBin].resolveLocalAccumulate(localRank);
    if (localKey < 0) return -1L;

    return ((long) targetBin << 10) | localKey;
  }

  public synchronized int serializedSize() {
    int size = 8 + 4;
    for (int i = 0; i < binCount; i++) {
      size += bins[i].serializedSize();
    }
    return size;
  }

  public synchronized void serialize(ByteBuffer buf) {
    buf.putLong(totalRange);
    buf.putInt(binCount);
    for (int i = 0; i < binCount; i++) {
      bins[i].write(buf);
    }
  }

  public static AnvilRegionBinHazardTable deserialize(ByteBuffer buf) {
    long totalRange = buf.getLong();
    int count = buf.getInt();
    AnvilRegionBinHazardTable table = new AnvilRegionBinHazardTable(totalRange);

    for (int i = 0; i < count; i++) {
      byte tag = buf.get();
      switch (tag) {
        case TAG_UNALLOCATED -> table.bins[i] = UnallocatedBin.INSTANCE;
        case TAG_FULL_DISCARDED -> table.bins[i] = FullDiscardedBin.INSTANCE;
        case TAG_BITMASK -> {
          int badCount = buf.getShort() & 0xFFFF;
          long[] words = new long[LONGS_PER_BIN];
          for (int w = 0; w < LONGS_PER_BIN; w++) {
            words[w] = buf.getLong();
          }
          table.bins[i] = new BitmaskBin(words, badCount);
        }
        case TAG_RUNS -> {
          int badCount = buf.getShort() & 0xFFFF;
          int runCount = buf.getShort() & 0xFFFF;
          char[] starts = new char[runCount];
          char[] lens = new char[runCount];
          for (int r = 0; r < runCount; r++) {
            starts[r] = buf.getChar();
            lens[r] = buf.getChar();
          }
          table.bins[i] = new RunBin(starts, lens, badCount);
        }
        default -> throw new IllegalArgumentException("Unknown tag: " + tag);
      }
    }
    table.recomputeGoodPrefixSums();
    return table;
  }
}
