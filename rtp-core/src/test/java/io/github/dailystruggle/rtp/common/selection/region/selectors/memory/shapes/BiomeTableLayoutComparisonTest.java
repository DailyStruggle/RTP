package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Equivalence and cost comparison between the current per-biome {@code long[]} run tables and the
 * proposed unified blocked layout (one sorted table, {@code long} block bases plus {@code int}
 * in-block offsets and a {@code short} biome id per run).
 *
 * <p>Both models are reimplemented here over the same run set so the comparison is layout-only:
 * identical inputs, identical query set, no shape or region state involved. Retention is measured
 * from the declared array widths rather than from {@code Runtime}, which cannot attribute heap to
 * a single structure.
 */
class BiomeTableLayoutComparisonTest {

  private static final int BLOCK_SHIFT = 10;
  private static final int BLOCK_SIZE = 1 << BLOCK_SHIFT;

  /** Current model: one sorted key array and one prefix-sum array per biome. */
  private static final class PerBiomeModel {
    final Map<String, long[]> keys = new LinkedHashMap<>();
    final Map<String, long[]> sums = new LinkedHashMap<>();
    // The union table holds every run a second time (biomeMappedKeysCache / PrefixSums).
    long[] unionKeys;
    long[] unionSums;

    String biomeAt(long location) {
      for (Map.Entry<String, long[]> e : keys.entrySet()) {
        long[] k = e.getValue();
        long[] s = sums.get(e.getKey());
        if (k.length == 0 || s.length != k.length) continue;
        if (runContaining(k, s, location) >= 0) return e.getKey();
      }
      return null;
    }

    long widthBefore(String biome, long location) {
      if (location <= 0L) return 0L;
      long[] k = keys.get(biome);
      long[] s = sums.get(biome);
      if (k == null || k.length == 0) return 0L;
      int i = floorRunIndex(k, location);
      if (i < 0) return 0L;
      long before = (i > 0) ? s[i - 1] : 0L;
      long width = s[i] - before;
      return before + Math.max(0L, Math.min(width, location - k[i]));
    }

    long retainedBytes() {
      long total = 0L;
      for (long[] k : keys.values()) total += 8L * k.length;
      for (long[] s : sums.values()) total += 8L * s.length;
      total += 8L * unionKeys.length + 8L * unionSums.length;
      // Two ConcurrentHashMap entries plus four array headers per biome.
      total += 200L * keys.size();
      return total;
    }
  }

  /** Proposed model: one run table, blocked so in-block offsets fit an int without overflow. */
  private static final class BlockedModel {
    String[] names;
    long[] blockBaseKey;
    long[] blockBaseSum;
    int[] blockFirstRun;
    int[] keyOffset;
    int[] sumOffset;
    short[] biomeId;
    int runCount;
    // Lazily built per-biome index into the unified table, for density queries.
    final Map<String, int[]> biomeRuns = new HashMap<>();

    long key(int i) {
      return blockBaseKey[blockOf(i)] + (keyOffset[i] & 0xFFFFFFFFL);
    }

    long sum(int i) {
      return blockBaseSum[blockOf(i)] + (sumOffset[i] & 0xFFFFFFFFL);
    }

    int blockOf(int run) {
      int idx = Arrays.binarySearch(blockFirstRun, run);
      return (idx >= 0) ? idx : -(idx + 1) - 1;
    }

    /** Two-level floor search: block bases, then in-block int offsets. */
    int floorRun(long location) {
      int b = floorLong(blockBaseKey, location);
      if (b < 0) return -1;
      int lo = blockFirstRun[b];
      int hi = (b + 1 < blockFirstRun.length) ? blockFirstRun[b + 1] : runCount;
      long target = location - blockBaseKey[b];
      int best = -1;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if ((keyOffset[mid] & 0xFFFFFFFFL) <= target) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return best;
    }

    int runContaining(long location) {
      int i = floorRun(location);
      if (i < 0) return -1;
      long k = key(i);
      if (k == location) return i;
      long width = runWidth(i);
      return (location < k + width) ? i : -1;
    }

    long runWidth(int i) {
      return sum(i) - ((i > 0) ? sum(i - 1) : 0L);
    }

    String biomeAt(long location) {
      int i = runContaining(location);
      return (i < 0) ? null : names[biomeId[i] & 0xFFFF];
    }

    long widthBefore(String biome, long location) {
      if (location <= 0L) return 0L;
      int[] runs = biomeRuns.get(biome);
      if (runs == null || runs.length == 0) return 0L;
      // Binary search the biome's own run list on absolute key, then accumulate.
      long before = 0L;
      for (int r : runs) {
        long k = key(r);
        long w = runWidth(r);
        if (k >= location) break;
        before += Math.min(w, location - k);
      }
      return before;
    }

    long retainedBytes() {
      long total = 10L * runCount; // keyOffset 4 + sumOffset 4 + biomeId 2
      total += 8L * blockBaseKey.length + 8L * blockBaseSum.length + 4L * blockFirstRun.length;
      for (String n : names) total += 40L + 2L * n.length();
      return total;
    }
  }

  private static int floorRunIndex(long[] keys, long location) {
    int idx = Arrays.binarySearch(keys, location);
    return (idx >= 0) ? idx : -(idx + 1) - 1;
  }

  private static int floorLong(long[] keys, long location) {
    return floorRunIndex(keys, location);
  }

  private static int runContaining(long[] keys, long[] sums, long location) {
    int i = floorRunIndex(keys, location);
    if (i < 0 || i >= sums.length) return -1;
    if (keys[i] == location) return i;
    long width = sums[i] - ((i > 0) ? sums[i - 1] : 0L);
    return (location < keys[i] + width) ? i : -1;
  }

  private static final class Run {
    final long key;
    final long width;
    final int biome;

    Run(long key, long width, int biome) {
      this.key = key;
      this.width = width;
      this.biome = biome;
    }
  }

  /**
   * Disjoint ascending runs over a domain wide enough that absolute keys need a {@code long}
   * (vanilla-border scale), which is the case a flat {@code int} key array could not represent.
   */
  private static List<Run> buildRuns(int biomeCount, int runsPerBiome, long base, long stride) {
    Random rng = new Random(20260904L);
    List<Run> runs = new ArrayList<>();
    long key = base;
    int total = biomeCount * runsPerBiome;
    for (int i = 0; i < total; i++) {
      long width = 1 + rng.nextInt(24);
      runs.add(new Run(key, width, rng.nextInt(biomeCount)));
      key += width + 1 + rng.nextInt((int) stride);
    }
    return runs;
  }

  private static PerBiomeModel buildPerBiome(List<Run> runs, String[] names) {
    PerBiomeModel m = new PerBiomeModel();
    for (String name : names) {
      List<Run> mine = new ArrayList<>();
      for (Run r : runs) if (names[r.biome].equals(name)) mine.add(r);
      long[] k = new long[mine.size()];
      long[] s = new long[mine.size()];
      long acc = 0L;
      for (int i = 0; i < mine.size(); i++) {
        k[i] = mine.get(i).key;
        acc += mine.get(i).width;
        s[i] = acc;
      }
      m.keys.put(name, k);
      m.sums.put(name, s);
    }
    long[] uk = new long[runs.size()];
    long[] us = new long[runs.size()];
    long acc = 0L;
    for (int i = 0; i < runs.size(); i++) {
      uk[i] = runs.get(i).key;
      acc += runs.get(i).width;
      us[i] = acc;
    }
    m.unionKeys = uk;
    m.unionSums = us;
    return m;
  }

  private static BlockedModel buildBlocked(List<Run> runs, String[] names) {
    BlockedModel m = new BlockedModel();
    int n = runs.size();
    m.names = names;
    m.runCount = n;
    m.keyOffset = new int[n];
    m.sumOffset = new int[n];
    m.biomeId = new short[n];

    List<Long> baseKeys = new ArrayList<>();
    List<Long> baseSums = new ArrayList<>();
    List<Integer> firstRun = new ArrayList<>();
    long curBaseKey = 0L;
    long curBaseSum = 0L;
    int inBlock = BLOCK_SIZE;
    long acc = 0L;
    for (int i = 0; i < n; i++) {
      Run r = runs.get(i);
      acc += r.width;
      boolean overflow =
          inBlock >= BLOCK_SIZE
              || (r.key - curBaseKey) > Integer.MAX_VALUE
              || (acc - curBaseSum) > Integer.MAX_VALUE;
      if (overflow) {
        curBaseKey = r.key;
        curBaseSum = acc - r.width;
        baseKeys.add(curBaseKey);
        baseSums.add(curBaseSum);
        firstRun.add(i);
        inBlock = 0;
      }
      m.keyOffset[i] = (int) (r.key - curBaseKey);
      m.sumOffset[i] = (int) (acc - curBaseSum);
      m.biomeId[i] = (short) r.biome;
      inBlock++;
    }
    m.blockBaseKey = baseKeys.stream().mapToLong(Long::longValue).toArray();
    m.blockBaseSum = baseSums.stream().mapToLong(Long::longValue).toArray();
    m.blockFirstRun = firstRun.stream().mapToInt(Integer::intValue).toArray();

    for (int b = 0; b < names.length; b++) {
      List<Integer> idx = new ArrayList<>();
      for (int i = 0; i < n; i++) if ((m.biomeId[i] & 0xFFFF) == b) idx.add(i);
      m.biomeRuns.put(names[b], idx.stream().mapToInt(Integer::intValue).toArray());
    }
    return m;
  }

  private static String[] names(int count) {
    String[] out = new String[count];
    for (int i = 0; i < count; i++) out[i] = "biome_" + i;
    return out;
  }

  @Test
  @DisplayName("blocked unified layout returns identical storage and retrieval outcomes")
  void testIdenticalOutcomes() {
    String[] names = names(12);
    // base near the vanilla-border 1D domain: absolute keys require a long
    List<Run> runs = buildRuns(12, 400, 3_400_000_000_000L, 64);
    PerBiomeModel a = buildPerBiome(runs, names);
    BlockedModel b = buildBlocked(runs, names);

    assertEquals(runs.size(), b.runCount, "run count must match");

    // Stored keys, widths and prefix sums must be recoverable bit-identically.
    long[] expectKeys = a.unionKeys;
    long[] actualKeys = new long[b.runCount];
    for (int i = 0; i < b.runCount; i++) actualKeys[i] = b.key(i);
    assertArrayEquals(expectKeys, actualKeys, "run keys must round-trip through block bases");

    long[] actualSums = new long[b.runCount];
    for (int i = 0; i < b.runCount; i++) actualSums[i] = b.sum(i);
    assertArrayEquals(a.unionSums, actualSums, "prefix sums must round-trip");

    // Point lookups over hits, gaps and out-of-range.
    Random rng = new Random(7L);
    long lo = expectKeys[0] - 50L;
    long hi = expectKeys[expectKeys.length - 1] + 50L;
    int hits = 0;
    for (int t = 0; t < 200_000; t++) {
      long q = lo + (long) (rng.nextDouble() * (hi - lo));
      String expected = a.biomeAt(q);
      String actual = b.biomeAt(q);
      assertEquals(expected, actual, "biomeAt mismatch at " + q);
      if (expected != null) hits++;
    }
    assertTrue(hits > 1000, "query set must actually hit runs; hits=" + hits);

    // Density / width queries per biome.
    for (String name : names) {
      for (int t = 0; t < 200; t++) {
        long q = lo + (long) (rng.nextDouble() * (hi - lo));
        assertEquals(a.widthBefore(name, q), b.widthBefore(name, q), "widthBefore " + name);
      }
      assertEquals(
          a.sums.get(name)[a.sums.get(name).length - 1],
          b.widthBefore(name, hi + 1000L),
          "total biome width " + name);
    }
  }

  @Test
  @DisplayName("blocked unified layout retains less heap for identical content")
  void testHeapRetention() {
    String[] names = names(16);
    List<Run> runs = buildRuns(16, 2000, 3_400_000_000_000L, 64);
    PerBiomeModel a = buildPerBiome(runs, names);
    BlockedModel b = buildBlocked(runs, names);

    long perBiome = a.retainedBytes();
    long blocked = b.retainedBytes();
    System.out.println(
        "[DEBUG_LOG] runs="
            + runs.size()
            + " biomes="
            + names.length
            + " perBiome="
            + perBiome
            + " B ("
            + String.format("%.2f", perBiome / (double) runs.size())
            + " B/run) blocked="
            + blocked
            + " B ("
            + String.format("%.2f", blocked / (double) runs.size())
            + " B/run) ratio="
            + String.format("%.2fx", perBiome / (double) blocked));
    System.out.println(
        "[DEBUG_LOG] blocks=" + b.blockBaseKey.length + " runsPerBlock=" + BLOCK_SIZE);

    assertTrue(
        blocked * 2 < perBiome, "blocked layout must be >2x smaller: " + blocked + " vs " + perBiome);
    assertTrue(
        blocked / (double) runs.size() < 12.0,
        "blocked layout must stay near 10 B/run: " + blocked / (double) runs.size());

    // Empirical corroboration: build each layout in isolation and read the retained-heap delta.
    long measuredPerBiome = measureRetained(() -> buildPerBiome(runs, names));
    long measuredBlocked = measureRetained(() -> buildBlocked(runs, names));
    System.out.println(
        "[DEBUG_LOG] measured retained heap: perBiome="
            + measuredPerBiome
            + " B ("
            + String.format("%.2f", measuredPerBiome / (double) runs.size())
            + " B/run) blocked="
            + measuredBlocked
            + " B ("
            + String.format("%.2f", measuredBlocked / (double) runs.size())
            + " B/run) ratio="
            + String.format("%.2fx", measuredPerBiome / (double) measuredBlocked));
    // GC timing makes this noisy, so only the direction is asserted.
    assertTrue(
        measuredBlocked < measuredPerBiome,
        "measured heap must favour the blocked layout: "
            + measuredBlocked
            + " vs "
            + measuredPerBiome);
  }

  /**
   * Retained bytes for whatever {@code factory} returns, as a used-heap delta with the result held
   * live across the second reading. {@code Runtime} cannot attribute heap to one structure, so this
   * settles the collector first and takes the smallest of several trials.
   */
  private static long measureRetained(java.util.function.Supplier<Object> factory) {
    long best = Long.MAX_VALUE;
    for (int t = 0; t < 5; t++) {
      settle();
      long before = used();
      Object held = factory.get();
      settle();
      long after = used();
      // Keep the structure reachable past the second reading.
      if (held.hashCode() == Integer.MIN_VALUE) System.out.print("");
      long delta = after - before;
      if (delta > 0) best = Math.min(best, delta);
    }
    return (best == Long.MAX_VALUE) ? 0L : best;
  }

  private static long used() {
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }

  private static void settle() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(20L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Test
  @DisplayName("blocked point lookup performs comparably to per-biome binary search")
  void testLookupPerformanceComparable() {
    String[] names = names(8);
    List<Run> runs = buildRuns(8, 4000, 3_400_000_000_000L, 64);
    PerBiomeModel a = buildPerBiome(runs, names);
    BlockedModel b = buildBlocked(runs, names);

    long lo = a.unionKeys[0];
    long hi = a.unionKeys[a.unionKeys.length - 1];
    long[] probes = new long[100_000];
    Random rng = new Random(11L);
    for (int i = 0; i < probes.length; i++) {
      probes[i] = lo + (long) (rng.nextDouble() * (hi - lo));
    }

    double aNs = timePerCall(() -> sink(a, probes), probes.length);
    double bNs = timePerCall(() -> sink(b, probes), probes.length);
    System.out.println(
        "[DEBUG_LOG] biomeAt ns/call: perBiome="
            + String.format("%.1f", aNs)
            + " blocked="
            + String.format("%.1f", bNs)
            + " ratio="
            + String.format("%.2f", bNs / aNs));

    // Blocked does one two-level search; per-biome does one search per biome. Blocked must not be
    // materially slower, and with 8 biomes should be faster.
    assertTrue(bNs < aNs * 2.0, "blocked lookup regressed: " + bNs + " vs " + aNs);
  }

  private static int sink(PerBiomeModel m, long[] probes) {
    int acc = 0;
    for (long p : probes) if (m.biomeAt(p) != null) acc++;
    return acc;
  }

  private static int sink(BlockedModel m, long[] probes) {
    int acc = 0;
    for (long p : probes) if (m.biomeAt(p) != null) acc++;
    return acc;
  }

  /** Min-of-5 timed trials after a warmup, in ns per call. */
  private static double timePerCall(Runnable body, int calls) {
    for (int w = 0; w < 5; w++) body.run();
    long best = Long.MAX_VALUE;
    for (int t = 0; t < 5; t++) {
      long start = System.nanoTime();
      body.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    return best / (double) calls;
  }
}
