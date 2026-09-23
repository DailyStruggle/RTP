package io.github.dailystruggle.rtp.common.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmark comparing point-by-point evaluation against overlapping bin/Hilbert
 * memory repackaging with pinned out-of-bounds exclusion, selecting 16 safe points.
 */
@Tag("simulation")
public class SubspaceMemoryRepackagingBenchmarkTest {

  private static final int SEED = 1337;
  private static final int BENCHMARK_ITERATIONS = 50_000;

  /**
   * Mock Parent World with 32x32 Anvil Bins (16 longs = 1024 bits per bin).
   */
  static class ParentWorld {
    final int binsX;
    final int binsZ;
    // Each bin is 16 longs (1024 bits)
    final long[][] binWords;

    ParentWorld(int binsX, int binsZ, Random rng) {
      this.binsX = binsX;
      this.binsZ = binsZ;
      this.binWords = new long[binsX * binsZ][16];

      // Seed realistic hazard patterns (e.g. 20% bins discarded, 30% mixed)
      for (int b = 0; b < binsX * binsZ; b++) {
        double r = rng.nextDouble();
        if (r < 0.20) {
          // Full discarded (all 1s)
          for (int w = 0; w < 16; w++) binWords[b][w] = -1L;
        } else if (r < 0.50) {
          // Mixed bin (clusters of bad chunks)
          for (int w = 0; w < 16; w++) {
            binWords[b][w] = rng.nextLong() & rng.nextLong(); // ~25% bits set
          }
        }
        // else 0% bad (clean bin)
      }
    }

    boolean isBad(int chunkX, int chunkZ) {
      if (chunkX < 0 || chunkZ < 0) return true;
      int bx = chunkX / 32;
      int bz = chunkZ / 32;
      if (bx >= binsX || bz >= binsZ) return true;
      int lx = chunkX % 32;
      int lz = chunkZ % 32;
      int localOffset = lz * 32 + lx;
      long word = binWords[bz * binsX + bx][localOffset >>> 6];
      return (word & (1L << (localOffset & 63))) != 0L;
    }

    long[] getBinWords(int bx, int bz) {
      if (bx < 0 || bx >= binsX || bz < 0 || bz >= binsZ) return null;
      return binWords[bz * binsX + bx];
    }
  }

  // ---------------------------------------------------------------------------------
  // Baseline: Point-by-Point Shuffling and Scanning (Current Subspace Approach)
  // ---------------------------------------------------------------------------------
  static class PointByPointSelector {
    static List<int[]> select16(ParentWorld parent, int anchorX, int anchorZ, int radius, int centerRadius) {
      List<int[]> candidates = new ArrayList<>(256);
      int r2 = radius * radius;
      int cr2 = centerRadius * centerRadius;

      // Enumerate candidate cells and filter known-bad individually
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          int d2 = dx * dx + dz * dz;
          if (d2 > r2 || d2 < cr2) continue; // Annular ring check
          int worldX = anchorX + dx;
          int worldZ = anchorZ + dz;
          if (parent.isBad(worldX, worldZ)) continue; // Point-by-point hazard check
          candidates.add(new int[] {worldX, worldZ});
        }
      }

      if (candidates.size() < 16) return Collections.emptyList();

      Collections.shuffle(candidates);
      return candidates.subList(0, 16);
    }
  }

  // ---------------------------------------------------------------------------------
  // True Accumulate Overlap Repackager:
  // - Clones the 1 to 4 overlapping 32x32 bins (16 longs each = 128 bytes)
  // - Applies pre-calculated perimeter mask via bitwise OR (pinning outside annular ring)
  // - Selects 16 points via direct accumulate / bit-scan without any point-by-point calls
  // ---------------------------------------------------------------------------------
  static class BinAccumulateRepackager {
    // 4 bins max for any subspace of radius <= 16 chunks
    private static final int MAX_BINS = 4;
    private final long[][] clonedBins = new long[MAX_BINS][16];
    private final int[] binBx = new int[MAX_BINS];
    private final int[] binBz = new int[MAX_BINS];
    private final int[] binSafeCount = new int[MAX_BINS];
    private final int[] prefixSums = new int[MAX_BINS];

    List<int[]> select16(ParentWorld parent, int anchorX, int anchorZ, int radius, int centerRadius, Random rng) {
      int r2 = radius * radius;
      int cr2 = centerRadius * centerRadius;

      int minX = anchorX - radius;
      int maxX = anchorX + radius;
      int minZ = anchorZ - radius;
      int maxZ = anchorZ + radius;

      int minBx = minX / 32;
      int maxBx = maxX / 32;
      int minBz = minZ / 32;
      int maxBz = maxZ / 32;

      int binCount = 0;
      int totalSafe = 0;

      // Clone the 1-4 overlapping bins and pin out-of-bounds perimeter
      for (int bz = minBz; bz <= maxBz; bz++) {
        for (int bx = minBx; bx <= maxBx; bx++) {
          int bIdx = binCount++;
          binBx[bIdx] = bx;
          binBz[bIdx] = bz;

          long[] src = parent.getBinWords(bx, bz);
          long[] dst = clonedBins[bIdx];

          if (src == null) {
            // Entirely outside world
            for (int w = 0; w < 16; w++) dst[w] = -1L;
          } else {
            // Clone 16 longs (128 bytes) in one copy
            System.arraycopy(src, 0, dst, 0, 16);
          }

          // Pin the perimeter (out of bounds or outside annular ring) for this bin
          // We can do this in word chunks:
          int binOriginX = bx * 32;
          int binOriginZ = bz * 32;

          for (int lz = 0; lz < 32; lz++) {
            int wz = binOriginZ + lz;
            int dz = wz - anchorZ;
            int dz2 = dz * dz;

            int rowWordIdx = (lz * 32) >>> 6;
            int bitShift = (lz * 32) & 63; // 0 or 32

            long oobMask = 0L;
            for (int lx = 0; lx < 32; lx++) {
              int wx = binOriginX + lx;
              int dx = wx - anchorX;
              int d2 = dx * dx + dz2;
              if (d2 > r2 || d2 < cr2) {
                oobMask |= (1L << (bitShift + lx));
              }
            }
            dst[rowWordIdx] |= oobMask; // Pin outside annular ring as hazard!
          }

          // Compute safe count for this bin
          int badInBin = 0;
          for (int w = 0; w < 16; w++) {
            badInBin += Long.bitCount(dst[w]);
          }
          int safeInBin = 1024 - badInBin;
          binSafeCount[bIdx] = safeInBin;
          totalSafe += safeInBin;
          prefixSums[bIdx] = totalSafe;
        }
      }

      if (totalSafe < 16) return Collections.emptyList();

      // Accumulate selection of 16 points (direct rank accumulate)
      List<int[]> result = new ArrayList<>(16);
      for (int i = 0; i < 16; i++) {
        int rank = rng.nextInt(totalSafe);

        // Binary search / scan bin prefix sums to find which bin owns this rank
        int targetBin = 0;
        int localRank = rank;
        for (int b = 0; b < binCount; b++) {
          if (rank < prefixSums[b]) {
            targetBin = b;
            localRank = (b == 0) ? rank : rank - prefixSums[b - 1];
            break;
          }
        }

        // Resolve within target bin via word bitCount
        long[] words = clonedBins[targetBin];
        int remaining = localRank;
        int localOffset = -1;

        for (int w = 0; w < 16; w++) {
          long word = words[w];
          int safeInWord = 64 - Long.bitCount(word);
          if (remaining < safeInWord) {
            for (int bit = 0; bit < 64; bit++) {
              if ((word & (1L << bit)) == 0L) {
                if (remaining == 0) {
                  localOffset = (w << 6) | bit;
                  words[w] |= (1L << bit); // Mark picked to prevent duplicates
                  totalSafe--;
                  // Update prefix sums
                  for (int pb = targetBin; pb < binCount; pb++) prefixSums[pb]--;
                  break;
                }
                remaining--;
              }
            }
            break;
          }
          remaining -= safeInWord;
        }

        if (localOffset >= 0) {
          int bx = binBx[targetBin];
          int bz = binBz[targetBin];
          int lx = localOffset % 32;
          int lz = localOffset / 32;
          result.add(new int[] {bx * 32 + lx, bz * 32 + lz});
        }
      }

      return result;
    }
  }

  @Test
  @DisplayName("Verify correctness and benchmark Point-by-Point vs True Bin Accumulate Repackaging")
  void testSubspaceSelectionBenchmark() {
    Random rng = new Random(SEED);
    ParentWorld world = new ParentWorld(16, 16, rng); // 16x16 MCA regions (512x512 chunks)

    int radius = 16;      // Subspace radius: 16 chunks
    int centerRadius = 4; // Annular ring: hollow out center 4 chunks

    PointByPointSelector baseline = new PointByPointSelector();
    BinAccumulateRepackager repackager = new BinAccumulateRepackager();

    // 1. Correctness Check
    int anchorX = 256;
    int anchorZ = 256;
    List<int[]> resBase = PointByPointSelector.select16(world, anchorX, anchorZ, radius, centerRadius);
    List<int[]> resRepack = repackager.select16(world, anchorX, anchorZ, radius, centerRadius, rng);

    assertEquals(16, resBase.size(), "Baseline should find 16 points");
    assertEquals(16, resRepack.size(), "Bin Accumulate Repackager should find 16 points");

    for (List<int[]> res : List.of(resBase, resRepack)) {
      for (int[] pt : res) {
        int dx = pt[0] - anchorX;
        int dz = pt[1] - anchorZ;
        int d2 = dx * dx + dz * dz;
        assertTrue(d2 <= radius * radius, "Point must be within outer radius");
        assertTrue(d2 >= centerRadius * centerRadius, "Point must be outside centerRadius");
        assertFalse(world.isBad(pt[0], pt[1]), "Point must not be in a bad chunk/location");
      }
    }

    // 2. Performance Micro-Benchmark
    System.out.println("\n[DEBUG_LOG] === SUBSPACE SELECTION BENCHMARK (16 Points, " + BENCHMARK_ITERATIONS + " iterations) ===");

    // Warm-up
    for (int i = 0; i < 5_000; i++) {
      int ax = 50 + rng.nextInt(400);
      int az = 50 + rng.nextInt(400);
      PointByPointSelector.select16(world, ax, az, radius, centerRadius);
      repackager.select16(world, ax, az, radius, centerRadius, rng);
    }

    // Benchmark 1: Point-by-Point (baseline)
    long t0 = System.nanoTime();
    int baseSuccess = 0;
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      int ax = 50 + (i % 400);
      int az = 50 + ((i * 7) % 400);
      List<int[]> pts = PointByPointSelector.select16(world, ax, az, radius, centerRadius);
      if (!pts.isEmpty()) baseSuccess++;
    }
    long durBase = System.nanoTime() - t0;
    double nsBase = (double) durBase / BENCHMARK_ITERATIONS;

    // Benchmark 2: True Bin Accumulate Repackaging
    long t1 = System.nanoTime();
    int repackSuccess = 0;
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      int ax = 50 + (i % 400);
      int az = 50 + ((i * 7) % 400);
      List<int[]> pts = repackager.select16(world, ax, az, radius, centerRadius, rng);
      if (!pts.isEmpty()) repackSuccess++;
    }
    long durRepack = System.nanoTime() - t1;
    double nsRepack = (double) durRepack / BENCHMARK_ITERATIONS;

    System.out.printf("[DEBUG_LOG] Point-by-Point Baseline         : %8.1f ns/op (%d/%d success)%n", nsBase, baseSuccess, BENCHMARK_ITERATIONS);
    System.out.printf("[DEBUG_LOG] Bin Accumulate Repackaging       : %8.1f ns/op (%d/%d success)%n", nsRepack, repackSuccess, BENCHMARK_ITERATIONS);
    System.out.printf("[DEBUG_LOG] Speedup of Bin Accumulate Repack : %.2fx%n", nsBase / nsRepack);
    System.out.println("[DEBUG_LOG] =========================================================================\n");
  }
}
