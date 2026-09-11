package io.github.dailystruggle.rtp.common.benchmark;

import java.util.Arrays;

/**
 * A sorted run table over 1D keys, with two coalescing rules: the shipped fixed gap, and a
 * <b>dynamic</b> gap derived from the lengths of the runs being joined.
 *
 * <p>Why a test-scope table rather than the shipped one: {@code MemoryShape} exposes run start keys
 * but not run widths, and the dynamic rule needs widths on both sides of every gap. Rather than
 * widen shipped API for a measurement, the fixed rule here is a transcription of
 * {@code MemoryShape#coalesceRuns} - merge when {@code nextKey <= curEnd + gap} - and
 * {@code ProximityWeightedLossBenchmarkTest} pins it against a real {@code flushAndRebuild} so the
 * transcription is checked rather than trusted. Every earlier fault in this line of work was a
 * harness fault, so the baseline is asserted equal to shipped behaviour before anything is compared
 * against it.
 *
 * <p><b>The dynamic rule.</b> A fixed {@code spatialResolution} asks one question - "are these two
 * bad runs within N keys of each other" - and answers it identically whether the runs are a
 * 20 000-cell ocean or two single-chunk pools. That is the defect the measurements keep running
 * into: the setting that merges an ocean's fragments usefully is the same setting that swallows
 * prime inland ground around a pond. The dynamic rule instead scales the admissible gap with the
 * evidence either side of it:
 *
 * <pre>
 *   admissibleGap = clamp(alpha * min(leftLength, rightLength), 0, maxGap)
 * </pre>
 *
 * <p>{@code min} rather than {@code max} on purpose: a gap is only bridged when <i>both</i> sides
 * are substantial. Taking the maximum would let one ocean fragment justify absorbing the ground
 * around every pool near it, which is the failure mode being avoided rather than a different
 * trade-off. {@code maxGap} is the ocean-width floor named throughout this work - without it a
 * large enough body licenses an unbounded merge.
 *
 * <p>Merging is a single greedy left-to-right pass, and the accumulator's grown length is what the
 * next gap is tested against, so a long run absorbs progressively wider gaps as it grows. That is
 * intended - it is how a fragmented ocean consolidates - but it makes the rule <b>order-dependent
 * and not idempotent</b>, and a second pass can merge further. One pass is measured, and the pass
 * count is stated rather than iterated to a fixed point, because "merge until nothing changes" is a
 * different rule with a different loss.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class KeyRunTable {

  private final long[] starts;
  private final long[] lengths;
  private final int count;

  private KeyRunTable(long[] starts, long[] lengths, int count) {
    this.starts = starts;
    this.lengths = lengths;
    this.count = count;
  }

  /**
   * Builds the finest possible table: one run per maximal stretch of consecutive keys.
   *
   * <p>This is the lossless starting point, not {@code spatialResolution = 1}. The shipped rule
   * merges at {@code nextKey <= curEnd + gap}, so a gap of 1 already bridges one key and swallows a
   * lone usable cell between two bad ones - which is exactly why ADR-085 section 9c adopted 2 as
   * the operating floor rather than calling 1 lossless.
   *
   * @param keys bad keys, ascending, duplicates tolerated
   * @param keyCount live entries in {@code keys}
   * @return the exact table
   */
  public static KeyRunTable exact(long[] keys, int keyCount) {
    long[] starts = new long[Math.max(1, keyCount)];
    long[] lengths = new long[Math.max(1, keyCount)];
    int out = 0;
    for (int i = 0; i < keyCount; i++) {
      long key = keys[i];
      if (out > 0 && key <= starts[out - 1] + lengths[out - 1]) {
        lengths[out - 1] = Math.max(lengths[out - 1], key + 1L - starts[out - 1]);
        continue;
      }
      starts[out] = key;
      lengths[out] = 1L;
      out++;
    }
    return new KeyRunTable(starts, lengths, out);
  }

  /**
   * The shipped rule: merge whenever the next run starts no later than {@code curEnd + gap}.
   *
   * @param gap {@code spatialResolution}, in key units
   * @return a new table; this one is untouched
   */
  public KeyRunTable coalesceFixed(long gap) {
    return coalesce((leftLength, rightLength) -> gap);
  }

  /**
   * The dynamic rule: the admissible gap is {@code alpha * min(leftLength, rightLength)}, capped.
   *
   * @param alpha gap admitted per cell of the shorter adjacent run
   * @param maxGap hard ceiling, the ocean-width floor
   * @return a new table; this one is untouched
   */
  public KeyRunTable coalesceDynamic(double alpha, long maxGap) {
    return coalesce(
        (leftLength, rightLength) -> {
          long driver = Math.min(leftLength, rightLength);
          double admissible = alpha * driver;
          if (admissible >= maxGap) return maxGap;
          return (long) admissible;
        });
  }

  /** Decides the gap admitted between two adjacent runs. */
  private interface GapRule {
    long admissible(long leftLength, long rightLength);
  }

  private KeyRunTable coalesce(GapRule rule) {
    if (count == 0) return new KeyRunTable(new long[1], new long[1], 0);
    long[] outStarts = new long[count];
    long[] outLengths = new long[count];
    int out = 0;
    long curStart = starts[0];
    long curLength = lengths[0];
    for (int i = 1; i < count; i++) {
      long nextStart = starts[i];
      long nextLength = lengths[i];
      long curEnd = curStart + curLength;
      if (nextStart <= curEnd + rule.admissible(curLength, nextLength)) {
        curLength = Math.max(curLength, nextStart + nextLength - curStart);
        continue;
      }
      outStarts[out] = curStart;
      outLengths[out] = curLength;
      out++;
      curStart = nextStart;
      curLength = nextLength;
    }
    outStarts[out] = curStart;
    outLengths[out] = curLength;
    out++;
    return new KeyRunTable(outStarts, outLengths, out);
  }

  /**
   * @param key a 1D key
   * @return true when some run covers it
   */
  public boolean contains(long key) {
    int idx = Arrays.binarySearch(starts, 0, count, key);
    if (idx >= 0) return true;
    int floor = -(idx + 1) - 1;
    if (floor < 0) return false;
    return key < starts[floor] + lengths[floor];
  }

  /** @return runs held */
  public int runs() {
    return count;
  }

  public long start(int index) {
    return starts[index];
  }

  public long length(int index) {
    return lengths[index];
  }

  /** @return cells covered by the runs, i.e. keys the table refuses */
  public long coveredCells() {
    long total = 0L;
    for (int i = 0; i < count; i++) total += lengths[i];
    return total;
  }

  /** @return mean run length, or 0 when empty */
  public double meanRunLength() {
    return count == 0 ? 0.0d : coveredCells() / (double) count;
  }

  /**
   * Resolves a target in [0, totalGood) in ACCUMULATE mode using the flat binary search fixed-point loop,
   * exactly mirroring {@code MemoryShape#resolve}.
   *
   * @param target raw good-space index in [0, totalRange - coveredCells())
   * @param totalRange maximum key range
   * @return physical coordinate in [0, totalRange), or -1 if target is out of range
   */
  public long resolveAccumulate(long target, long totalRange) {
    long totalGood = totalRange - coveredCells();
    if (target < 0 || target >= totalGood) return -1L;

    long[] prefixSums = new long[count];
    long running = 0L;
    for (int i = 0; i < count; i++) {
      running += lengths[i];
      prefixSums[i] = running;
    }

    long currentBadSum = 0L;
    while (true) {
      int index = Arrays.binarySearch(starts, 0, count, target + currentBadSum);
      if (index < 0) {
        index = -index - 1;
      } else {
        index = index + 1;
      }
      if (index > count) index = count;

      long newBadSum = (index > 0) ? prefixSums[index - 1] : 0L;
      if (newBadSum == currentBadSum) break;
      currentBadSum = newBadSum;
    }
    long location = target + currentBadSum;
    return (location < totalRange) ? location : -1L;
  }

  /**
   * Adapts this flat run table into the shipped {@link
   * io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable}
   * so benchmarks exercise the production backend rather than a forked test copy. Runs are handed
   * verbatim to {@code fromRuns}, which partitions/pins identically to the retired {@code fromFlat}.
   *
   * @param totalRange maximum key range (domain chunk count)
   * @param binSize keys per bin
   * @param fullCollapseTolerance remaining usable chunks in a bin to collapse to FULL
   * @return the shipped segmented table over these runs
   */
  public io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable
      toSegmented(long totalRange, long binSize, long fullCollapseTolerance) {
    long[] runStarts = new long[count];
    long[] runLengths = new long[count];
    for (int i = 0; i < count; i++) {
      runStarts[i] = starts[i];
      runLengths[i] = lengths[i];
    }
    return io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable
        .fromRuns(runStarts, runLengths, count, totalRange, binSize, fullCollapseTolerance);
  }

  /**
   * Convenience overload with no FULL-collapse tolerance.
   *
   * @param totalRange maximum key range (domain chunk count)
   * @param binSize keys per bin
   * @return the shipped segmented table over these runs
   */
  public io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable
      toSegmented(long totalRange, long binSize) {
    return toSegmented(totalRange, binSize, 0L);
  }
}
