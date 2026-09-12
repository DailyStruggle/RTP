package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.util.Arrays;

/**
 * Segmented two-tier secondary table with active span pinning over a continuous 1D key space.
 *
 * <p>Splits a continuous key space $[0, \text{totalRange})$ into fixed-capacity bins (e.g. 256, 512, 1024),
 * pinning straddling run spans at bin boundary offset 0. This guarantees:
 * <ul>
 *   <li><b>Zero backward pointer chasing:</b> queries landing in bin $b$ inspect only bin $b$'s local runs.
 *   <li><b>Cache locality:</b> local binary search inside a bin of 6-22 runs executes inside on-chip CPU cache ({@code < 15 ns}).
 *   <li><b>O(1) Accumulate offset resolution:</b> Tier 1 bisection over {@code dirBadPrefixSums} ($\le 64$ ints in hardware cache)
 *       finds the target bin in 1–2 steps, and Tier 2 resolves locally inside the bin.
 *   <li><b>Reconciliation locality:</b> inserting a mark mutates only a single local bin.
 * </ul>
 */
public final class SegmentedKeyRunTable {

  public static final class Bin {
    private final int binIndex;
    private final long binStartKey;
    private final long binSize;
    private final int[] starts;
    private final int[] lengths;
    private final int count;
    private final long coveredCells;
    // Prefix sums of bad lengths within this bin: badPrefixSums[i] = sum(lengths[0..i])
    private final int[] badPrefixSums;

    public Bin(int binIndex, long binStartKey, long binSize, int[] starts, int[] lengths, int count) {
      this.binIndex = binIndex;
      this.binStartKey = binStartKey;
      this.binSize = binSize;
      this.starts = starts;
      this.lengths = lengths;
      this.count = count;
      this.badPrefixSums = new int[count];
      long covered = 0L;
      for (int i = 0; i < count; i++) {
        covered += lengths[i];
        badPrefixSums[i] = (int) covered;
      }
      this.coveredCells = covered;
    }

    public int binIndex() {
      return binIndex;
    }

    public int count() {
      return count;
    }

    public long coveredCells() {
      return coveredCells;
    }

    public int[] badPrefixSums() {
      return badPrefixSums;
    }

    public boolean isFull() {
      return count == 1 && starts[0] == 0 && lengths[0] == binSize;
    }

    public boolean isEmpty() {
      return count == 0;
    }

    /**
     * Resolves local offset for ACCUMULATE mode within this bin using local bad runs.
     *
     * @param localTarget number of good chunks to skip into this bin (0-indexed)
     * @return physical offset in [0, binSize), or -1 if localTarget exceeds good chunks in bin
     */
    public int resolveLocalAccumulate(int localTarget) {
      if (count == 0) {
        return (localTarget < binSize) ? localTarget : -1;
      }
      if (isFull()) {
        return -1;
      }

      int currentBad = 0;
      while (true) {
        int guess = localTarget + currentBad;
        if (guess >= binSize) return -1;

        int idx = Arrays.binarySearch(starts, 0, count, guess);
        if (idx < 0) {
          idx = -idx - 1;
        } else {
          idx = idx + 1;
        }
        if (idx > count) idx = count;

        int newBad = (idx > 0) ? badPrefixSums[idx - 1] : 0;
        if (newBad == currentBad) break;
        currentBad = newBad;
      }
      int res = localTarget + currentBad;
      return (res < binSize) ? res : -1;
    }

    /**
     * Local binary search within this bin.
     *
     * @param offset relative offset within this bin in [0, binSize)
     * @return true if covered
     */
    public boolean containsOffset(int offset) {
      if (count == 0) return false;
      if (isFull()) return true;
      int idx = Arrays.binarySearch(starts, 0, count, offset);
      if (idx >= 0) return true;
      int floor = -(idx + 1) - 1;
      if (floor < 0) return false;
      return offset < starts[floor] + lengths[floor];
    }
  }

  private final long totalRange;
  private final long binSize;
  private final int numBins;
  private final Bin[] bins;
  private final long totalRuns;
  private final long totalCovered;
  // Directory prefix sums of bad chunk counts across bins:
  // dirBadPrefixSums[i] = total bad chunks in bins[0..i]
  private final long[] dirBadPrefixSums;

  private SegmentedKeyRunTable(long totalRange, long binSize, Bin[] bins) {
    this.totalRange = totalRange;
    this.binSize = binSize;
    this.numBins = bins.length;
    this.bins = bins;
    long runs = 0L;
    long covered = 0L;
    this.dirBadPrefixSums = new long[bins.length];
    for (int i = 0; i < bins.length; i++) {
      Bin b = bins[i];
      runs += b.count();
      covered += b.coveredCells();
      dirBadPrefixSums[i] = covered;
    }
    this.totalRuns = runs;
    this.totalCovered = covered;
  }

  /**
   * Constructs a segmented key run table from sorted starts and lengths arrays.
   *
   * @param starts run start coordinates
   * @param lengths run lengths
   * @param flatCount number of valid runs
   * @param totalRange maximum key range (domain chunk count)
   * @param binSize number of keys per bin (e.g. 256, 512, 1024)
   * @param fullCollapseTolerance maximum remaining usable chunks in a bin to collapse to FULL
   * @return partitioned table
   */
  public static SegmentedKeyRunTable fromRuns(
      long[] starts, long[] lengths, int flatCount, long totalRange, long binSize, long fullCollapseTolerance) {
    if (binSize <= 0) throw new IllegalArgumentException("binSize must be > 0: " + binSize);
    int numBins = (int) ((totalRange + binSize - 1) / binSize);
    if (numBins <= 0) numBins = 1;

    int[][] tempStarts = new int[numBins][];
    int[][] tempLengths = new int[numBins][];
    int[] tempCounts = new int[numBins];

    for (int b = 0; b < numBins; b++) {
      tempStarts[b] = new int[8];
      tempLengths[b] = new int[8];
    }

    for (int i = 0; i < flatCount; i++) {
      long rStart = starts[i];
      long rLen = lengths[i];
      long rEnd = rStart + rLen;

      if (rStart >= totalRange) break;
      if (rEnd > totalRange) rEnd = totalRange;

      int startBin = (int) (rStart / binSize);
      int endBin = (int) ((rEnd - 1) / binSize);

      if (startBin >= numBins) continue;
      if (endBin >= numBins) endBin = numBins - 1;

      for (int b = startBin; b <= endBin; b++) {
        long bStartKey = b * binSize;
        long bEndKey = Math.min(totalRange, (b + 1) * binSize);

        long clampedStart = Math.max(rStart, bStartKey);
        long clampedEnd = Math.min(rEnd, bEndKey);
        if (clampedStart >= clampedEnd) continue;

        int localStart = (int) (clampedStart - bStartKey);
        int localLen = (int) (clampedEnd - clampedStart);

        int c = tempCounts[b];
        if (c > 0 && localStart <= tempStarts[b][c - 1] + tempLengths[b][c - 1]) {
          tempLengths[b][c - 1] = Math.max(tempLengths[b][c - 1], localStart + localLen - tempStarts[b][c - 1]);
        } else {
          if (c == tempStarts[b].length) {
            tempStarts[b] = Arrays.copyOf(tempStarts[b], c * 2);
            tempLengths[b] = Arrays.copyOf(tempLengths[b], c * 2);
          }
          tempStarts[b][c] = localStart;
          tempLengths[b][c] = localLen;
          tempCounts[b]++;
        }
      }
    }

    Bin[] bins = new Bin[numBins];
    for (int b = 0; b < numBins; b++) {
      int c = tempCounts[b];
      long bStartKey = b * binSize;
      long bSize = Math.min(binSize, totalRange - bStartKey);

      long covered = 0L;
      for (int i = 0; i < c; i++) covered += tempLengths[b][i];

      if (c == 0) {
        bins[b] = new Bin(b, bStartKey, bSize, EMPTY_ARRAY, EMPTY_ARRAY, 0);
      } else if ((c == 1 && tempStarts[b][0] == 0 && tempLengths[b][0] == bSize)
          || (bSize - covered <= fullCollapseTolerance && fullCollapseTolerance > 0)) {
        bins[b] = new Bin(b, bStartKey, bSize, FULL_START, new int[] {(int) bSize}, 1);
      } else {
        int[] s = Arrays.copyOf(tempStarts[b], c);
        int[] l = Arrays.copyOf(tempLengths[b], c);
        bins[b] = new Bin(b, bStartKey, bSize, s, l, c);
      }
    }

    return new SegmentedKeyRunTable(totalRange, binSize, bins);
  }

  /**
   * Domain scale below which the table devolves to a single flat bin.
   *
   * <p>Below this many chunks a directory of {@code totalRange / binSize} mostly-empty bins costs
   * more (object headers, pointers, directory prefix array) than it saves: the domain is a
   * near-perfect spiral with only a handful of holes, so one flat run array is both cheaper to
   * retain and no slower to resolve. The value is the measured memory crossover from
   * {@code DevolutionThresholdBenchmarkTest} (density 0.30, meanRun 12): segmented retained bytes
   * first fall below the flat table at ~16K chunks.
   */
  public static final long DEVOLUTION_THRESHOLD = 16_384L;

  /**
   * Derives an optimal bin size in powers of two according to domain scale,
   * targeting between 32 and 128 total bins, or a single whole-range bin below
   * {@link #DEVOLUTION_THRESHOLD}.
   *
   * @param totalRange total chunks in domain
   * @return derived bin size (power of two); {@code >= totalRange} when below the devolution
   *     threshold so the resulting table holds exactly one bin
   */
  public static long deriveOptimalBinSize(long totalRange) {
    if (totalRange <= 0) return 256L;
    long pow2 = 128L;
    if (totalRange < DEVOLUTION_THRESHOLD) {
      // Devolve to a single flat bin: pick the smallest power of two that spans the whole range.
      while (pow2 < totalRange) {
        pow2 <<= 1;
      }
      return pow2;
    }
    long target = totalRange / 64L;
    if (target < 128L) target = 128L;
    if (target > 4096L) target = 4096L;
    while (pow2 < target) {
      pow2 <<= 1;
    }
    return pow2;
  }

  private static final int[] EMPTY_ARRAY = new int[0];
  private static final int[] FULL_START = new int[] {0};

  /**
   * Strictly local lookup: computes the bin index and binary searches only within that bin.
   *
   * @param key 1D key in [0, totalRange)
   * @return true if covered
   */
  public boolean contains(long key) {
    if (key < 0 || key >= totalRange) return false;
    int b = (int) (key / binSize);
    if (b >= numBins) return false;
    int offset = (int) (key - (b * binSize));
    return bins[b].containsOffset(offset);
  }

  public long totalRange() {
    return totalRange;
  }

  public long binSize() {
    return binSize;
  }

  public int numBins() {
    return numBins;
  }

  public Bin bin(int index) {
    return bins[index];
  }

  public long totalRuns() {
    return totalRuns;
  }

  public long totalCovered() {
    return totalCovered;
  }

  public long dirBadSumAt(int binIdx) {
    if (binIdx < 0) return 0L;
    if (binIdx >= numBins) return totalCovered;
    return dirBadPrefixSums[binIdx];
  }

  /**
   * Resolves a target in [0, totalGood) in ACCUMULATE mode using two-tier bad prefix sums.
   *
   * @param target raw good-space index in [0, totalRange - totalCovered)
   * @return physical coordinate in [0, totalRange), or -1 if target is out of range
   */
  public long resolveAccumulate(long target) {
    long totalGood = totalRange - totalCovered;
    if (target < 0 || target >= totalGood) return -1L;

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
      Bin curBin = bins[b];
      long binGood = curBin.binSize - curBin.coveredCells();
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

    int localOffset = bins[b].resolveLocalAccumulate(localTarget);
    if (localOffset < 0) return -1L;

    return b * binSize + localOffset;
  }

  /**
   * Approximate payload heap bytes.
   */
  public long retainedBytes() {
    long bytes = 48L; // Object header + fields
    bytes += (long) numBins * 8L + 24L; // Bin[] array
    bytes += (long) dirBadPrefixSums.length * 8L + 24L; // dirBadPrefixSums array
    for (Bin b : bins) {
      bytes += 48L; // Bin object header
      if (!b.isEmpty() && !b.isFull()) {
        bytes += (long) b.starts.length * 4L + 24L;
        bytes += (long) b.lengths.length * 4L + 24L;
        bytes += (long) b.badPrefixSums.length * 4L + 24L;
      }
    }
    return bytes;
  }
}
