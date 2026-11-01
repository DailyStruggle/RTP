package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared algorithms and bit manipulation utilities for Hazard Tables (ADR-092).
 */
public final class HazardTableUtils {

  private HazardTableUtils() {}

  /**
   * Compacts / merges adjacent or close runs based on admissible gap width.
   */
  public static List<int[]> mergeRunsWithGap(List<int[]> runs, long minGap, long maxGap) {
    if (minGap <= 0L || runs.size() <= 1) {
      return runs;
    }
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
    return merged;
  }

  /**
   * Finds the localRank-th safe chunk in a bitmask using 64-bit popcount per word.
   */
  public static int resolveBitmaskAccumulate(long[] words, int numWords, int localRank) {
    int remaining = localRank;
    for (int w = 0; w < numWords; w++) {
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

  /**
   * Binary searches a 16-bit run container (starts and lengths) to test if localOffset is bad.
   */
  public static boolean isRunBad(char[] starts, char[] lengths, int localOffset) {
    int low = 0;
    int high = starts.length - 1;
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

  /**
   * Resolves localRank safe offset within a run container.
   */
  public static int resolveRunAccumulate(char[] starts, char[] lengths, int totalChunks, int localRank) {
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
    int trailingGap = totalChunks - prevEnd;
    if (trailingGap > 0 && localRank < curSafeRank + trailingGap) {
      return prevEnd + (localRank - curSafeRank);
    }
    return -1;
  }
}
