package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import java.lang.management.ManagementFactory;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Baseline cost of {@link MemoryShape#flushAndRebuild} as a function of the already-recorded biome
 * table size, with a fixed number of pending observations.
 *
 * <p>Rebuild is copy-on-write: every call reallocates the affected biome's key and prefix-sum
 * arrays, the two owning maps, and the coalesced union - so the cost of applying one observation is
 * expected to scale with the whole existing table rather than with the pending set. These
 * measurements are the "before" record for the unified blocked-table rework, where closed blocks
 * are shared by reference and only touched blocks reallocate.
 *
 * <p>Wall-clock JUnit timings, not JMH: cite the scaling ratios, not the absolute latencies.
 */
public class MemoryShapeRebuildCostTest {

  private static final int PENDING_PER_REBUILD = 16;
  private static final long RESOLUTION = 1L;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  private static long allocatedBytes() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (bean instanceof com.sun.management.ThreadMXBean sun) {
      return sun.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    return -1L;
  }

  /**
   * A shape carrying {@code runs} isolated runs per biome. Stride exceeds the resolution so the
   * rebuild cannot coalesce them away, keeping the table size honest.
   */
  private static Square seeded(int biomes, int runs, long seed) {
    Square shape = new Square();
    shape.setRng(new Random(seed));
    shape.setSpatialResolution(RESOLUTION);
    for (int b = 0; b < biomes; b++) {
      String biome = "BIOME_" + b;
      for (int k = 0; k < runs; k++) {
        shape.addBiomeLocation((long) b * 1_000_000L + (long) k * 8L, 1L, biome);
      }
    }
    shape.flushAndRebuild(RESOLUTION);
    return shape;
  }

  /** Applies {@link #PENDING_PER_REBUILD} fresh observations, then rebuilds. */
  private static void oneRebuild(Square shape, long base) {
    for (int i = 0; i < PENDING_PER_REBUILD; i++) {
      shape.addBiomeLocation(base + i * 8L, 1L, "BIOME_0");
    }
    shape.flushAndRebuild(RESOLUTION);
  }

  /**
   * The per-attempt cost of the two rebuild cadences: unconditional (what {@code PregenTask} used
   * to do on every attempt) versus the amortizing guard, over the same observation stream.
   *
   * <p>Both arms apply one observation per attempt and then ask for a rebuild, so the only
   * difference is whether the O(recorded runs) merge runs every time or once per batch.
   */
  @Test
  @DisplayName("amortized rebuild cadence costs less per attempt than an unconditional rebuild")
  public void amortizedCadenceBeatsPerAttemptRebuild() {
    int biomes = 8;
    int runs = 4096;
    int attempts = 300;

    double[] nsPer = new double[2];
    double[] bytesPer = new double[2];

    for (boolean amortized : new boolean[] {false, true, false, true}) {
      Square shape = seeded(biomes, runs, 7L);
      long base = 500_000_000L;
      long a0 = allocatedBytes();
      long t0 = System.nanoTime();
      for (int i = 0; i < attempts; i++) {
        shape.addBiomeLocation(base, 1L, "BIOME_0");
        shape.addBadChunk(
            base + 4L,
            io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes
                .biome);
        base += 64L;
        if (amortized) {
          shape.flushAndRebuildIfNeeded(RESOLUTION);
        } else {
          shape.flushAndRebuild(RESOLUTION);
        }
      }
      long elapsed = System.nanoTime() - t0;
      long a1 = allocatedBytes();

      int arm = amortized ? 1 : 0;
      nsPer[arm] = elapsed / (double) attempts;
      bytesPer[arm] = (a0 < 0L) ? -1.0d : (a1 - a0) / (double) attempts;

      System.out.printf(
          "[DEBUG_LOG] cadence: amortized=%s totalRuns=%d attempts=%d"
              + " %.0f ns/attempt %.0f B/attempt%n",
          amortized, biomes * runs, attempts, nsPer[arm], bytesPer[arm]);
    }

    System.out.printf(
        "[DEBUG_LOG] cadence gain: time %.2fx alloc %.2fx%n",
        nsPer[0] / nsPer[1], (bytesPer[1] > 0.0d) ? bytesPer[0] / bytesPer[1] : -1.0d);

    assertTrue(
        nsPer[1] * 2.0d < nsPer[0],
        "amortized cadence must at least halve per-attempt time: " + nsPer[0] + " -> " + nsPer[1]);
    if (bytesPer[0] > 0.0d) {
      assertTrue(
          bytesPer[1] * 2.0d < bytesPer[0],
          "amortized cadence must at least halve per-attempt allocation: "
              + bytesPer[0]
              + " -> "
              + bytesPer[1]);
    }
  }

  @Test
  @DisplayName("rebuild cost per applied observation is recorded against existing table size")
  public void rebuildCostAgainstTableSize() {
    int biomes = 8;
    double[] ns = new double[3];
    double[] bytes = new double[3];
    int[] sizes = {64, 1024, 8192};

    for (int s = 0; s < sizes.length; s++) {
      int runs = sizes[s];
      Square shape = seeded(biomes, runs, 99L + runs);
      long base = 500_000_000L;

      // warm the JIT on this shape, advancing the key base so every rebuild has real work
      for (int w = 0; w < 50; w++) {
        oneRebuild(shape, base);
        base += 100_000L;
      }

      int iterations = 100;
      long a0 = allocatedBytes();
      long t0 = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        oneRebuild(shape, base);
        base += 100_000L;
      }
      long elapsed = System.nanoTime() - t0;
      long a1 = allocatedBytes();

      ns[s] = elapsed / (double) iterations;
      bytes[s] = (a0 < 0L) ? -1.0d : (a1 - a0) / (double) iterations;

      System.out.printf(
          "[DEBUG_LOG] rebuild: biomes=%d runs/biome=%d totalRuns=%d pending=%d"
              + " %.0f ns/rebuild %.0f B/rebuild goodCount=%d%n",
          biomes,
          runs,
          biomes * runs,
          PENDING_PER_REBUILD,
          ns[s],
          bytes[s],
          shape.getEffectiveGoodCount());
    }

    System.out.printf(
        "[DEBUG_LOG] rebuild scaling over a 128x table: time %.2fx alloc %.2fx%n",
        ns[2] / ns[0], (bytes[0] > 0.0d) ? bytes[2] / bytes[0] : -1.0d);

    // Documents the current copy-on-write behaviour: cost tracks the existing table, not the
    // pending set. If the blocked rework lands, this is the assertion that should start failing.
    assertTrue(
        ns[2] > ns[0],
        "rebuild currently reallocates the whole table, so a larger table must cost more: "
            + ns[0]
            + " -> "
            + ns[2]);
  }
}
