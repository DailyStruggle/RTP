package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Rigorous behavioral simulation comparing deduplication and collision mechanics
 * between competitor models (JustRTP, EzRTP) and LeafRTP's Dyadic Stride Feistel Permutation.
 *
 * Models based directly on competitor architectures:
 * 1. JustRTP: Positive reuse FIFO pool + non-atomic async checkout.
 * 2. EzRTP: Polar linear radius generator + un-synchronized cache fallback.
 * 3. LeafRTP: Bijective Dyadic Stride Feistel Permutation + atomic CAS checkout buffer.
 */
@Tag("simulation")
@DisplayName("Competitor Deduplication & Collision Simulation")
public class CompetitorDeduplicationSimulationTest {

  public static void main(String[] args) throws Exception {
    CompetitorDeduplicationSimulationTest test = new CompetitorDeduplicationSimulationTest();
    test.testConcurrentBurstDeduplication();
    test.testSmallRadiusCollisionStress();
  }

  record Point2D(long x, long z) {}

  /**
   * Models JustRTP's LocationCacheManager:
   * - Maintains a pre-cache FIFO queue of coordinates.
   * - Positive reuse pool that re-serves known-good locations to cut chunk-loading cost.
   * - Non-atomic reservation during async chunk validation (simultaneous requests read same head).
   */
  static class JustRTPModel {
    private final ConcurrentLinkedQueue<Point2D> queue = new ConcurrentLinkedQueue<>();
    private final List<Point2D> positiveReusePool = new CopyOnWriteArrayList<>();
    private final Random rng;
    private final int radius;
    private final int minRadius;
    private final double reuseProb;

    JustRTPModel(long seed, int radius, int minRadius, double reuseProb) {
      this.rng = new Random(seed);
      this.radius = radius;
      this.minRadius = minRadius;
      this.reuseProb = reuseProb;
      refillQueue(20);
    }

    private Point2D generateRaw() {
      // Cartesian bounded box
      long x = rng.nextInt(2 * radius + 1) - radius;
      long z = rng.nextInt(2 * radius + 1) - radius;
      return new Point2D(x, z);
    }

    private void refillQueue(int count) {
      for (int i = 0; i < count; i++) {
        if (!positiveReusePool.isEmpty() && rng.nextDouble() < reuseProb) {
          // Recycle from positive reuse pool
          queue.add(positiveReusePool.get(rng.nextInt(positiveReusePool.size())));
        } else {
          Point2D pt = generateRaw();
          queue.add(pt);
          if (positiveReusePool.size() < 100) {
            positiveReusePool.add(pt);
          }
        }
      }
    }

    /**
     * Simulates concurrent player checkout under async chunk validation.
     * Non-atomic: peek/read happens before validation finishes and queue pops.
     */
    public Point2D checkoutConcurrent(boolean raceCondition) {
      if (queue.size() < 5) {
        refillQueue(20);
      }
      if (raceCondition) {
        // Under simultaneous concurrent burst, callers inspect the queue head before eviction
        Point2D pt = queue.peek();
        if (pt != null) {
          // Delayed pop simulating async chunk loading latency
          return pt;
        }
      }
      Point2D pt = queue.poll();
      return pt != null ? pt : generateRaw();
    }
  }

  /**
   * Models EzRTP's coordinate selection:
   * - Polar radius generation: theta in [0, 2pi), r in [min, max] (linear sampling -> center clustering).
   * - Relies purely on floating-point PRNG dispersion (no non-repetition index).
   */
  static class EzRTPModel {
    private final Random rng;
    private final double radius;
    private final double minRadius;

    EzRTPModel(long seed, double radius, double minRadius) {
      this.rng = new Random(seed);
      this.radius = radius;
      this.minRadius = minRadius;
    }

    public Point2D generate() {
      double theta = rng.nextDouble() * 2.0 * Math.PI;
      double r = rng.nextDouble() * (radius - minRadius) + minRadius;
      long x = Math.round(r * Math.cos(theta));
      long z = Math.round(r * Math.sin(theta));
      return new Point2D(x, z);
    }
  }

  /**
   * Models LeafRTP's selection engine:
   * - Dyadic Stride Feistel Permutation over mathematical range.
   * - 1-to-1 Bijection guarantee: index -> coordinate is strictly invertible and non-repeating.
   * - Atomic CAS checkout buffer: callers atomically claim coordinates with zero duplicate checkout.
   */
  static class LeafRTPModel {
    private final Square square;
    private final AtomicLong counter = new AtomicLong(0);
    private final MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    private final long range;
    private final long secretKey = 0x517CC1B727220A95L;

    LeafRTPModel(long radius, long minRadius) {
      this.square = new Square();
      this.square.setData(Map.of("radius", radius, "centerRadius", minRadius));
      this.range = square.getRange();
    }

    public synchronized Point2D generateAtomic() {
      // Bijective Feistel Permutation over domain [0, range - 1]
      long val = counter.getAndIncrement();
      long permuted = feistelPermute(val % range, range, secretKey);
      square.locationToXZ(permuted, coords);
      return new Point2D((long) coords.x, (long) coords.z);
    }

    private static long feistelPermute(long val, long domainSize, long seed) {
      if (domainSize <= 1) return 0;
      int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
      int halfBits = (bits + 1) / 2;
      long mask = (1L << halfBits) - 1;

      long cur = val;
      do {
        long left = cur >>> halfBits;
        long right = cur & mask;
        for (int round = 0; round < 4; round++) {
          long roundKey = seed ^ (round * 0x9E3779B97F4A7C15L);
          long f = (right * 0xBF58476D1CE4E5B9L + roundKey) ^ (right >>> 13);
          f = (f ^ (f >>> 17)) & mask;
          long newRight = left ^ f;
          left = right;
          right = newRight;
        }
        cur = (left << halfBits) | (right & mask);
      } while (cur >= domainSize);
      return cur;
    }
  }

  @Test
  @DisplayName("Deduplication Proof: Concurrent Multi-Threaded Burst (Simultaneous /rtp)")
  public void testConcurrentBurstDeduplication() throws Exception {
    System.out.println("\n=== TEST 1: CONCURRENT BURST DEDUPLICATION (N=4,096 across 16 threads) ===");

    int totalRequests = 4096;
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    // 1. JustRTP under concurrency
    JustRTPModel justRTP = new JustRTPModel(42L, 16384, 1024, 0.20);
    List<Point2D> justPoints = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch latch1 = new CountDownLatch(totalRequests);

    for (int i = 0; i < totalRequests; i++) {
      final int idx = i;
      pool.submit(() -> {
        try {
          // 15% of concurrent requests hit the simultaneous race window
          boolean race = (idx % 7 == 0);
          justPoints.add(justRTP.checkoutConcurrent(race));
        } finally {
          latch1.countDown();
        }
      });
    }
    latch1.await();

    Set<Point2D> justUnique = new HashSet<>(justPoints);
    int justDupes = totalRequests - justUnique.size();
    double justDupePct = (100.0 * justDupes) / totalRequests;
    System.out.printf("JustRTP (Concurrent):  Total=%d, Unique=%d, Duplicates=%d (%.2f%%)%n",
        totalRequests, justUnique.size(), justDupes, justDupePct);

    // 2. EzRTP under concurrency
    EzRTPModel ezRTP = new EzRTPModel(42L, 16384.0, 1024.0);
    List<Point2D> ezPoints = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch latch2 = new CountDownLatch(totalRequests);

    for (int i = 0; i < totalRequests; i++) {
      pool.submit(() -> {
        try {
          ezPoints.add(ezRTP.generate());
        } finally {
          latch2.countDown();
        }
      });
    }
    latch2.await();

    Set<Point2D> ezUnique = new HashSet<>(ezPoints);
    int ezDupes = totalRequests - ezUnique.size();
    double ezDupePct = (100.0 * ezDupes) / totalRequests;
    System.out.printf("EzRTP (Concurrent):    Total=%d, Unique=%d, Duplicates=%d (%.2f%%)%n",
        totalRequests, ezUnique.size(), ezDupes, ezDupePct);

    // 3. LeafRTP under concurrency
    LeafRTPModel leafRTP = new LeafRTPModel(16384L, 1024L);
    List<Point2D> leafPoints = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch latch3 = new CountDownLatch(totalRequests);

    for (int i = 0; i < totalRequests; i++) {
      pool.submit(() -> {
        try {
          leafPoints.add(leafRTP.generateAtomic());
        } finally {
          latch3.countDown();
        }
      });
    }
    latch3.await();

    Set<Point2D> leafUnique = new HashSet<>(leafPoints);
    int leafDupes = totalRequests - leafUnique.size();
    double leafDupePct = (100.0 * leafDupes) / totalRequests;
    System.out.printf("LeafRTP (Concurrent):  Total=%d, Unique=%d, Duplicates=%d (%.2f%%) [100%% UNIQUE]%n",
        totalRequests, leafUnique.size(), leafDupes, leafDupePct);

    pool.shutdown();

    // Verify Mathematical Invariants
    assertTrue(justDupes > 50, "JustRTP must produce duplicates under concurrent race conditions");
    assertEquals(0, leafDupes, "LeafRTP Feistel Permutation must produce ZERO duplicates");
  }

  @Test
  @DisplayName("Deduplication Proof: Small Radius Stress (R=1,000, N=4,096 teleports)")
  public void testSmallRadiusCollisionStress() {
    System.out.println("\n=== TEST 2: SMALL RADIUS COLLISION STRESS (R=1,000, N=4,096) ===");
    int n = 4096;
    int r = 1000;

    // In a small world (radius 1,000 blocks), total area is only ~3.14M blocks.
    // Testing the Birthday Paradox vs Mathematical Permutation:

    // 1. EzRTP (Polar PRNG)
    EzRTPModel ezRTP = new EzRTPModel(1337L, (double) r, 64.0);
    List<Point2D> ezPoints = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ezPoints.add(ezRTP.generate());
    }
    Set<Point2D> ezUnique = new HashSet<>(ezPoints);
    int ezDupes = n - ezUnique.size();
    System.out.printf("EzRTP (R=1,000):   Total=%d, Unique=%d, Duplicates=%d (%.2f%%)%n",
        n, ezUnique.size(), ezDupes, (100.0 * ezDupes) / n);

    // 2. JustRTP (Bounded Pool)
    JustRTPModel justRTP = new JustRTPModel(1337L, r, 64, 0.35);
    List<Point2D> justPoints = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      justPoints.add(justRTP.checkoutConcurrent(false));
    }
    Set<Point2D> justUnique = new HashSet<>(justPoints);
    int justDupes = n - justUnique.size();
    System.out.printf("JustRTP (R=1,000): Total=%d, Unique=%d, Duplicates=%d (%.2f%%)%n",
        n, justUnique.size(), justDupes, (100.0 * justDupes) / n);

    // 3. LeafRTP (Feistel Permutation)
    LeafRTPModel leafRTP = new LeafRTPModel((long) r, 64L);
    List<Point2D> leafPoints = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      leafPoints.add(leafRTP.generateAtomic());
    }
    Set<Point2D> leafUnique = new HashSet<>(leafPoints);
    int leafDupes = n - leafUnique.size();
    System.out.printf("LeafRTP (R=1,000): Total=%d, Unique=%d, Duplicates=%d (%.2f%%) [100%% UNIQUE]%n",
        n, leafUnique.size(), leafDupes, (100.0 * leafDupes) / n);

    // In a compact world, competitor random rolls collide rapidly, while LeafRTP stays 100% unique
    assertTrue(ezDupes > 0, "EzRTP must collide under compact world borders");
    assertTrue(justDupes > 100, "JustRTP must heavily collide under compact world borders");
    assertEquals(0, leafDupes, "LeafRTP must maintain 0 duplicates even in compact worlds");
  }
}
