package io.github.dailystruggle.rtp.common.benchmark;

import java.util.Arrays;

/**
 * A segmented run table over 1D keys partitioned into fixed-capacity bins (e.g. 256, 512, or 1024
 * chunks), with <b>pinned straddling endpoints</b> at bin boundaries.
 *
 * <p><b>The Hazard Solved.</b> In a naive segmented table where runs are indexed only by their start
 * keys, a bad run longer than a bin (such as an ocean crossing several bins) leaves intermediate
 * bins with no start keys. A query landing in such a bin would see an empty table and falsely accept
 * the ocean chunk, unless the search probes backward into preceding bins (destroying O(1) bin
 * lookup and thrashing cache lines).
 *
 * <p><b>Active Span Pinning (Clamped Slices).</b> Each bin covers a contiguous key interval
 * {@code [b * binSize, (b + 1) * binSize)}. Any run {@code [start, start + length)} that overlaps
 * the bin is clamped to {@code [max(start, b * binSize), min(start + length, (b + 1) * binSize))}.
 * If the run starts before the bin, its clamped start is <i>pinned at offset 0</i> of that bin.
 * Consequently:
 * <ul>
 *   <li>Every query {@code contains(key)} is <b>strictly local</b>: {@code bin = key / binSize},
 *       followed by a binary search solely within {@code bin}. Zero backward probing.
 *   <li>If a bin is completely covered by a run, it has a single clamped entry spanning the full bin
 *       ({@code [0, binSize)}), or can be marked fully bad.
 *   <li>Each bin holds a small array that fits in L1/L2 cache (typically <= 64 entries).
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class SegmentedKeyRunTable {

  public static final class Bin {
    private final int binIndex;
    private final long binStartKey;
    private final long binSize;
    // Offsets within the bin: [0, binSize)
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
        // Completely empty (all good): physical offset is exactly localTarget
        return (localTarget < binSize) ? localTarget : -1;
      }
      if (isFull()) {
        return -1; // No good chunks
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
     * @param offset offset from binStartKey, in [0, binSize)
     * @return true if covered
     */
    public boolean containsOffset(int offset) {
      if (count == 0) return false;
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
   * Partitions an existing flat {@link KeyRunTable} into segmented bins with pinned endpoints,
   * collapsing bins that are completely full or within {@code fullCollapseTolerance} of full.
   *
   * @param flat source table
   * @param totalRange maximum key range (domain chunk count)
   * @param binSize number of keys per bin (e.g. 256, 512, 1024)
   * @param fullCollapseTolerance maximum remaining usable chunks in a bin to collapse to FULL (e.g. spatialResolution)
   * @return partitioned table
   */
  public static SegmentedKeyRunTable fromFlat(
      KeyRunTable flat, long totalRange, long binSize, long fullCollapseTolerance) {
    if (binSize <= 0) throw new IllegalArgumentException("binSize must be > 0: " + binSize);
    int numBins = (int) ((totalRange + binSize - 1) / binSize);
    if (numBins <= 0) numBins = 1;

    // Temporary accumulators per bin
    int[][] tempStarts = new int[numBins][];
    int[][] tempLengths = new int[numBins][];
    int[] tempCounts = new int[numBins];

    // Initial capacity estimate: small power of two
    for (int b = 0; b < numBins; b++) {
      tempStarts[b] = new int[8];
      tempLengths[b] = new int[8];
    }

    int flatCount = flat.runs();
    for (int i = 0; i < flatCount; i++) {
      long rStart = flat.start(i);
      long rLen = flat.length(i);
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
        // If the bin already has a run that abuts or overlaps this clamped run, merge them
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
        // Completely empty bin (100% good land): zero run arrays
        bins[b] = new Bin(b, bStartKey, bSize, EMPTY_ARRAY, EMPTY_ARRAY, 0);
      } else if ((c == 1 && tempStarts[b][0] == 0 && tempLengths[b][0] == bSize)
          || (bSize - covered <= fullCollapseTolerance && fullCollapseTolerance > 0)) {
        // Full bin or near-full within spatialResolution/tolerance: collapse to FULL
        bins[b] = new Bin(b, bStartKey, bSize, FULL_START, new int[] {(int) bSize}, 1);
      } else {
        int[] s = Arrays.copyOf(tempStarts[b], c);
        int[] l = Arrays.copyOf(tempLengths[b], c);
        bins[b] = new Bin(b, bStartKey, bSize, s, l, c);
      }
    }

    return new SegmentedKeyRunTable(totalRange, binSize, bins);
  }

  public static SegmentedKeyRunTable fromFlat(KeyRunTable flat, long totalRange, long binSize) {
    return fromFlat(flat, totalRange, binSize, 0L);
  }

  /**
   * Automatically derives an optimal bin size in powers of two according to domain scale,
   * targeting between 32 and 128 total bins to cap directory overhead while keeping bins L1-sized.
   *
   * @param totalRange total chunks in domain
   * @return derived bin size (power of two, in [128, 4096])
   */
  public static long deriveOptimalBinSize(long totalRange) {
    if (totalRange <= 0) return 256L;
    // Target ~64 bins
    long target = totalRange / 64L;
    // Clamp to [128, 4096]
    if (target < 128L) target = 128L;
    if (target > 4096L) target = 4096L;
    // Round up to power of two
    long pow2 = 128L;
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
   * Resolves a target in [0, totalGood) in ACCUMULATE mode using the two-tier bad prefix sums.
   *
   * <p>Tier 1: Bisection/search over {@code dirBadPrefixSums} in L1 cache (<= 128 elements)
   * to locate the target bin in 1-2 steps.
   * <p>Tier 2: Resolves local offset within the located bin's local bad runs (<= 16 runs).
   *
   * @param target raw good-space index in [0, totalRange - totalCovered)
   * @return physical coordinate in [0, totalRange), or -1 if target is out of range
   */
  public long resolveAccumulate(long target) {
    long totalGood = totalRange - totalCovered;
    if (target < 0 || target >= totalGood) return -1L;

    // Fast initial guess for the target bin
    int b = (int) (target / binSize);
    if (b >= numBins) b = numBins - 1;

    // Fixed-point convergence across bins using dirBadPrefixSums:
    // We want bin b such that target + badBefore(b) lands inside bin b's good range.
    // That is: goodBefore(b) <= target < goodBefore(b+1)
    // where goodBefore(b) = b * binSize - badBefore(b)
    long badBefore = (b > 0) ? dirBadPrefixSums[b - 1] : 0L;
    long goodBefore = b * binSize - badBefore;

    if (target < goodBefore) {
      // Search backward
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
      // Check if target extends past bin b
      Bin curBin = bins[b];
      long binGood = curBin.binSize - curBin.coveredCells();
      if (target >= goodBefore + binGood) {
        // Search forward
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

    // Tier 2: local resolution within bin b
    int localOffset = bins[b].resolveLocalAccumulate(localTarget);
    if (localOffset < 0) return -1L;

    return b * binSize + localOffset;
  }

  /**
   * Approximate payload heap bytes: object headers + directory array + int[] per bin.
   */
  public long retainedBytes() {
    long bytes = 64L; // base object
    bytes += (long) numBins * 8L; // Bin references
    for (Bin b : bins) {
      bytes += 48L; // Bin object header + fields
      if (b.count() > 0 && !b.isFull()) {
        bytes += 16L + (long) b.count() * 4L; // starts[]
        bytes += 16L + (long) b.count() * 4L; // lengths[]
      }
    }
    return bytes;
  }
}
