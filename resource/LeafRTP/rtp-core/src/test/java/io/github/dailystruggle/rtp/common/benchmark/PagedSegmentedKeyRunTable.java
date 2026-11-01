package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paged Segmented Key Run Table implementing bounded memory management for massive world domains
 * (e.g. 100km to full 30,000km world borders) with low-thrashing single-page swapping.
 *
 * <p>Key architectural properties:
 * <ul>
 *   <li><b>Default State:</b> Unloaded bins are assumed <i>FULL BAD</i> (covered == binSize).
 *       Untouched, ungenerated, or unpaged space costs 0 bytes in heap and requires zero allocations.
 *   <li><b>Solid Bins:</b> 100% full bins (oceans/unexplored) and 100% empty bins (solid safe land)
 *       share static singletons with zero array allocations.
 *   <li><b>Bounded Resident Capacity:</b> Keeps at most {@code maxResidentMixedPages} mixed run
 *       arrays in JVM heap. When a new mixed bin is paged in or accessed, cold mixed bins are dropped
 *       one page at a time (evicted back to paged storage or assumed-full state) using an LRU cache.
 *   <li><b>Low Thrashing Hysteresis:</b> Eviction only occurs when resident mixed pages exceed the
 *       upper limit, dropping one page at a time until within bounds, avoiding wholesale table flushes.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public class PagedSegmentedKeyRunTable {

  public interface PageStorage {
    byte[] readPage(int binIndex);
    void writePage(int binIndex, int[] starts, int[] lengths, int count);
    boolean hasPage(int binIndex);
  }

  public static class InMemoryMockStorage implements PageStorage {
    private final Map<Integer, byte[]> storage = new LinkedHashMap<>();
    private long reads = 0L;
    private long writes = 0L;

    @Override
    public byte[] readPage(int binIndex) {
      reads++;
      return storage.get(binIndex);
    }

    @Override
    public void writePage(int binIndex, int[] starts, int[] lengths, int count) {
      writes++;
      // Compact binary encoding: [count: short] + count * [start: short, len: short]
      byte[] buf = new byte[2 + count * 4];
      buf[0] = (byte) ((count >>> 8) & 0xFF);
      buf[1] = (byte) (count & 0xFF);
      int pos = 2;
      for (int i = 0; i < count; i++) {
        int s = starts[i];
        int l = lengths[i];
        buf[pos++] = (byte) ((s >>> 8) & 0xFF);
        buf[pos++] = (byte) (s & 0xFF);
        buf[pos++] = (byte) ((l >>> 8) & 0xFF);
        buf[pos++] = (byte) (l & 0xFF);
      }
      storage.put(binIndex, buf);
    }

    @Override
    public boolean hasPage(int binIndex) {
      return storage.containsKey(binIndex);
    }

    public long reads() {
      return reads;
    }

    public long writes() {
      return writes;
    }

    public int storedPages() {
      return storage.size();
    }
  }

  public enum BinState {
    SOLID_FULL,      // 100% bad (or unexplored / assumed full) -> 0 array allocations
    SOLID_EMPTY,     // 100% good safe land -> 0 array allocations
    RESIDENT_MIXED,  // Loaded in JVM RAM (occupies LRU page slot)
    PAGED_MIXED      // Stored on disk/Redis; run array dropped from RAM
  }

  private final long totalRange;
  private final long binSize;
  private final int numBins;
  private final BinState[] binStates;
  private final long[] binCoveredCells;
  private final long[] dirBadPrefixSums;

  // LRU cache of resident mixed bins: binIndex -> SegmentedKeyRunTable.Bin
  private final LinkedHashMap<Integer, SegmentedKeyRunTable.Bin> residentPages;
  private final int maxResidentPages;
  private final PageStorage storage;

  private long pageFaults = 0L;
  private long pageEvictions = 0L;

  public PagedSegmentedKeyRunTable(
      long totalRange,
      long binSize,
      int numBins,
      int maxResidentPages,
      PageStorage storage) {
    this.totalRange = totalRange;
    this.binSize = binSize;
    this.numBins = numBins;
    this.maxResidentPages = Math.max(1, maxResidentPages);
    this.storage = storage;

    this.binStates = new BinState[numBins];
    this.binCoveredCells = new long[numBins];
    this.dirBadPrefixSums = new long[numBins];

    // By default, every bin is SOLID_EMPTY (optimistic presumption of validity: 100% available for selection)
    // Allows new/unexplored land to be discovered and generated, costing 0 array allocations.
    for (int i = 0; i < numBins; i++) {
      binStates[i] = BinState.SOLID_EMPTY;
      binCoveredCells[i] = 0L;
      dirBadPrefixSums[i] = 0L;
    }

    // LRU with accessOrder = true
    this.residentPages = new LinkedHashMap<>(maxResidentPages + 1, 1.0f, true);
  }

  /**
   * Initializes or updates a bin's data from known local runs.
   */
  public void putBinData(int binIndex, int[] starts, int[] lengths, int count) {
    if (binIndex < 0 || binIndex >= numBins) return;

    long bStartKey = binIndex * binSize;
    long bSize = Math.min(binSize, totalRange - bStartKey);

    long covered = 0L;
    for (int i = 0; i < count; i++) covered += lengths[i];

    long oldCovered = binCoveredCells[binIndex];
    binCoveredCells[binIndex] = covered;

    // Adjust dirBadPrefixSums from binIndex onward
    long delta = covered - oldCovered;
    if (delta != 0) {
      for (int i = binIndex; i < numBins; i++) {
        dirBadPrefixSums[i] += delta;
      }
    }

    if (count == 0) {
      binStates[binIndex] = BinState.SOLID_EMPTY;
      residentPages.remove(binIndex);
    } else if (count == 1 && starts[0] == 0 && lengths[0] == bSize) {
      binStates[binIndex] = BinState.SOLID_FULL;
      residentPages.remove(binIndex);
    } else {
      // Mixed bin
      SegmentedKeyRunTable.Bin b = new SegmentedKeyRunTable.Bin(
          binIndex, bStartKey, bSize,
          Arrays.copyOf(starts, count),
          Arrays.copyOf(lengths, count),
          count);

      // Save to external storage if provided
      if (storage != null) {
        storage.writePage(binIndex, starts, lengths, count);
      }

      // Evict one page at a time if over budget
      ensureResidentCapacity();

      residentPages.put(binIndex, b);
      binStates[binIndex] = BinState.RESIDENT_MIXED;
    }
  }

  /**
   * Drops cold mixed pages one at a time until within resident bounds.
   */
  private void ensureResidentCapacity() {
    while (residentPages.size() >= maxResidentPages) {
      // Eldest entry in LRU order
      Map.Entry<Integer, SegmentedKeyRunTable.Bin> eldest = residentPages.entrySet().iterator().next();
      int evictBinIdx = eldest.getKey();
      residentPages.remove(evictBinIdx);
      pageEvictions++;

      // If storage exists, mark as PAGED_MIXED; otherwise drops back to SOLID_FULL (assumed full)
      if (storage != null && storage.hasPage(evictBinIdx)) {
        binStates[evictBinIdx] = BinState.PAGED_MIXED;
      } else {
        binStates[evictBinIdx] = BinState.SOLID_FULL;
      }
    }
  }

  /**
   * Retrieves the {@link SegmentedKeyRunTable.Bin} for query, paging it in from storage if necessary.
   */
  public SegmentedKeyRunTable.Bin getOrLoadBin(int binIndex) {
    if (binIndex < 0 || binIndex >= numBins) return null;

    BinState state = binStates[binIndex];
    long bStartKey = binIndex * binSize;
    long bSize = Math.min(binSize, totalRange - bStartKey);

    if (state == BinState.SOLID_FULL) {
      return new SegmentedKeyRunTable.Bin(binIndex, bStartKey, bSize, FULL_START, new int[] {(int) bSize}, 1);
    }
    if (state == BinState.SOLID_EMPTY) {
      return new SegmentedKeyRunTable.Bin(binIndex, bStartKey, bSize, EMPTY_ARRAY, EMPTY_ARRAY, 0);
    }
    if (state == BinState.RESIDENT_MIXED) {
      return residentPages.get(binIndex);
    }

    // state == BinState.PAGED_MIXED: Page fault! Load single page from storage
    pageFaults++;
    byte[] data = storage.readPage(binIndex);
    if (data == null || data.length < 2) {
      // Fallback: assume full
      return new SegmentedKeyRunTable.Bin(binIndex, bStartKey, bSize, FULL_START, new int[] {(int) bSize}, 1);
    }

    int count = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    int[] starts = new int[count];
    int[] lengths = new int[count];
    int pos = 2;
    for (int i = 0; i < count; i++) {
      starts[i] = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
      lengths[i] = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
    }

    ensureResidentCapacity();

    SegmentedKeyRunTable.Bin b = new SegmentedKeyRunTable.Bin(binIndex, bStartKey, bSize, starts, lengths, count);
    residentPages.put(binIndex, b);
    binStates[binIndex] = BinState.RESIDENT_MIXED;
    return b;
  }

  public boolean contains(long key) {
    if (key < 0 || key >= totalRange) return false;
    int b = (int) (key / binSize);
    if (b >= numBins) return false;

    BinState state = binStates[b];
    if (state == BinState.SOLID_FULL) return true;
    if (state == BinState.SOLID_EMPTY) return false;

    SegmentedKeyRunTable.Bin bin = getOrLoadBin(b);
    int offset = (int) (key - (b * binSize));
    return bin.containsOffset(offset);
  }

  public long resolveAccumulate(long target) {
    long totalCovered = dirBadPrefixSums[numBins - 1];
    long totalGood = totalRange - totalCovered;
    if (target < 0 || target >= totalGood) return -1L;

    // Fast initial guess for the target bin
    int b = (int) (target / binSize);
    if (b >= numBins) b = numBins - 1;

    long badBefore = (b > 0) ? dirBadPrefixSums[b - 1] : 0L;
    long goodBefore = b * binSize - badBefore;

    if (target < goodBefore) {
      int low = 0;
      int high = b - 1;
      while (low <= high) {
        int mid = (low + high) >>> 1;
        long bb = (mid > 0) ? dirBadPrefixSums[mid - 1] : 0L;
        long gb = mid * binSize - bb;
        if (gb <= target) {
          b = mid;
          low = mid + 1;
        } else {
          high = mid - 1;
        }
      }
    } else {
      long binCovered = binCoveredCells[b];
      long bSize = Math.min(binSize, totalRange - (b * binSize));
      long binGood = bSize - binCovered;
      if (target >= goodBefore + binGood) {
        int low = b + 1;
        int high = numBins - 1;
        while (low <= high) {
          int mid = (low + high) >>> 1;
          long bb = (mid > 0) ? dirBadPrefixSums[mid - 1] : 0L;
          long gb = mid * binSize - bb;
          if (gb <= target) {
            b = mid;
            low = mid + 1;
          } else {
            high = mid - 1;
          }
        }
      }
    }

    badBefore = (b > 0) ? dirBadPrefixSums[b - 1] : 0L;
    goodBefore = b * binSize - badBefore;
    int localTarget = (int) (target - goodBefore);

    SegmentedKeyRunTable.Bin bin = getOrLoadBin(b);
    int localOffset = bin.resolveLocalAccumulate(localTarget);
    if (localOffset < 0) return -1L;

    return b * binSize + localOffset;
  }

  public int residentPageCount() {
    return residentPages.size();
  }

  public int maxResidentPages() {
    return maxResidentPages;
  }

  /**
   * Cycles equivalent resident pages: evicts a cold/least-recently-used resident page to storage
   * and opportunistically warms up an alternate paged page with equivalent access weight,
   * preventing static residency lock-in and distributing cache warming across the domain.
   *
   * @param candidateBinIndex an alternate paged bin index to cycle in (if PAGED_MIXED)
   * @return true if a cycle occurred
   */
  public boolean cycleEquivalentPage(int candidateBinIndex) {
    if (candidateBinIndex < 0 || candidateBinIndex >= numBins) return false;
    if (binStates[candidateBinIndex] != BinState.PAGED_MIXED) return false;
    if (residentPages.isEmpty()) return false;

    // Evict the eldest LRU page
    Map.Entry<Integer, SegmentedKeyRunTable.Bin> eldest = residentPages.entrySet().iterator().next();
    int evictIdx = eldest.getKey();
    if (evictIdx == candidateBinIndex) return false;

    residentPages.remove(evictIdx);
    pageEvictions++;
    binStates[evictIdx] = BinState.PAGED_MIXED;

    // Load the candidate page
    getOrLoadBin(candidateBinIndex);
    return true;
  }

  public long pageFaults() {
    return pageFaults;
  }

  public long pageEvictions() {
    return pageEvictions;
  }

  public BinState binState(int binIndex) {
    return binStates[binIndex];
  }

  public long totalCovered() {
    return dirBadPrefixSums[numBins - 1];
  }

  public long directoryHeapBytes() {
    // binStates (1B each) + binCoveredCells (8B each) + dirBadPrefixSums (8B each)
    // + residentPages map overhead
    return numBins * 17L + residentPages.size() * 128L;
  }

  private static final int[] EMPTY_ARRAY = new int[0];
  private static final int[] FULL_START = new int[] {0};
}
