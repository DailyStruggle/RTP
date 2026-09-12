package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-F-005 / REQ-RTP-F-006 - selection throughput of {@link MemoryShape#rand()} at
 * bulk-fill scale.
 *
 * <p>Answers "how fast can we produce selections while filling a cache tier with 100k
 * candidates". Selection is only the first pipeline stage, so this measures the ceiling the rest
 * of the pipeline is bounded by, not end-to-end teleport cost.
 *
 * <p>Three scenarios, because the cost is dominated by learned-state maintenance rather than by
 * the draw itself:
 *
 * <ul>
 *   <li><b>clean</b> - no learned bad locations. The floor: geometry + distribution draw only.
 *   <li><b>learned</b> - a fixed set of bad runs, none added during the run. Adds the binary
 *       search and the ACCUMULATE fixed-point loop, but no rebuild.
 *   <li><b>uniquePlacements</b> - the realistic bulk-fill shape: each selection marks its landing
 *       chunk bad, which dirties the learned state and forces the next selection to rebuild it.
 * </ul>
 *
 * <p>Not a regression gate. Assertions are deliberately loose (order-of-magnitude sanity only) so
 * the test cannot fail on a slow or shared CI box; the numbers are reported via {@code
 * [DEBUG_LOG]} for human comparison.
 */
@DisplayName("REQ-RTP-F-005/F-006 memory shape selection throughput")
public class MemoryShapeSelectionThroughputTest {

  /** Selections per measured scenario. Bulk-fill target from the L3 sizing question. */
  private static final int SELECTIONS =
      Integer.getInteger("rtp.test.throughput.selections", 100_000);

  /** Warmup selections, to let the JIT compile {@code rand()} before timing. */
  private static final int WARMUP = Integer.getInteger("rtp.test.throughput.warmup", 20_000);

  /**
   * Selections for the rebuild-per-call scenario. Lower because the cost per selection grows with
   * the learned-state size, so the full 100k would dominate the suite's runtime.
   */
  private static final int REBUILD_SELECTIONS =
      Integer.getInteger("rtp.test.throughput.rebuildSelections", 5_000);

  /** Selections for the growth curve, split into {@link #GROWTH_BLOCKS} equal blocks. */
  private static final int GROWTH_SELECTIONS =
      Integer.getInteger("rtp.test.throughput.growthSelections", 40_000);

  private static final int GROWTH_BLOCKS = 10;

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  /** The shapes under test, by name, each freshly constructed per scenario. */
  private static Map<String, Supplier<MemoryShape<?>>> shapes() {
    Map<String, Supplier<MemoryShape<?>>> map = new LinkedHashMap<>();
    map.put("Circle", Circle::new);
    map.put("Circle_Normal", Circle_Normal::new);
    map.put("Ellipse", Ellipse::new);
    map.put("Polygon", Polygon::new);
    map.put("Rectangle", Rectangle::new);
    map.put("Square", Square::new);
    map.put("Square_Normal", Square_Normal::new);
    return map;
  }

  /**
   * Configure a knob by name. Uses the string overload so one harness serves all four params
   * enums; unknown keys are ignored by {@code setData}, which is what we want for shapes that do
   * not expose the knob.
   */
  private static void knob(MemoryShape<?> shape, String name, Object value) {
    Map<String, Object> data = new HashMap<>();
    data.put(name, value);
    shape.setData(data);
  }

  /** Deterministic RNG so run-to-run comparisons are not confounded by draw luck. */
  private static MemoryShape<?> prepare(Supplier<MemoryShape<?>> factory, long seed) {
    MemoryShape<?> shape = factory.get();
    shape.setRng(new Random(seed));
    knob(shape, "uniquePlacements", 0);
    return shape;
  }

  private static long drain(MemoryShape<?> shape, int iterations) {
    long sink = 0L;
    for (int i = 0; i < iterations; i++) sink += shape.rand();
    return sink;
  }

  /** @return nanoseconds per selection */
  private static double measure(MemoryShape<?> shape, int iterations) {
    long start = System.nanoTime();
    long sink = drain(shape, iterations);
    long elapsed = System.nanoTime() - start;
    // Consume the sink so the loop cannot be optimized away entirely.
    if (sink == Long.MIN_VALUE) throw new AssertionError("unreachable");
    return (double) elapsed / iterations;
  }

  private static void report(String scenario, String shape, double nsPerOp, int iterations) {
    double perSecond = 1_000_000_000.0 / nsPerOp;
    System.out.printf(
        "[DEBUG_LOG] %-16s %-14s %,10.0f ns/op  %,12.0f sel/s  %,7.1f ms for %,d%n",
        scenario, shape, nsPerOp, perSecond, nsPerOp * iterations / 1_000_000.0, iterations);
  }

  @Test
  @DisplayName("selection throughput with no learned bad locations")
  void throughput_cleanShape() {
    System.out.println("[DEBUG_LOG] --- scenario: clean (no learned bad locations) ---");
    shapes()
        .forEach(
            (name, factory) -> {
              MemoryShape<?> shape = prepare(factory, 12345L);
              measure(shape, WARMUP);
              double nsPerOp = measure(shape, SELECTIONS);
              report("clean", name, nsPerOp, SELECTIONS);
              assertTrue(nsPerOp > 0.0, name + " produced no measurable work");
              assertTrue(
                  nsPerOp < 1_000_000.0,
                  name + " selection cost " + nsPerOp + " ns/op is implausibly slow");
            });
  }

  @Test
  @DisplayName("selection throughput against a static learned-bad set")
  void throughput_withLearnedBadRuns() {
    System.out.println("[DEBUG_LOG] --- scenario: learned (static bad runs, no rebuild) ---");
    shapes()
        .forEach(
            (name, factory) -> {
              MemoryShape<?> shape = prepare(factory, 12345L);
              // Seed sparse bad runs across the range, then rebuild once so the timed loop pays
              // the binary search and ACCUMULATE loop but never a rebuild.
              long range = shape.getRange();
              long stride = Math.max(1L, range / 2_000L);
              for (long i = 0; i < range; i += stride) shape.addBadLocation(i);
              shape.flushAndRebuild(1L);

              measure(shape, WARMUP);
              double nsPerOp = measure(shape, SELECTIONS);
              report("learned", name, nsPerOp, SELECTIONS);
              assertTrue(nsPerOp > 0.0, name + " produced no measurable work");
            });
  }

  @Test
  @DisplayName("selection throughput when every selection marks its chunk (uniquePlacements)")
  void throughput_withUniquePlacements() {
    System.out.println(
        "[DEBUG_LOG] --- scenario: uniquePlacements=1 (rebuild per selection) ---");
    shapes()
        .forEach(
            (name, factory) -> {
              MemoryShape<?> shape = prepare(factory, 12345L);
              knob(shape, "uniquePlacements", 1);

              double nsPerOp = measure(shape, REBUILD_SELECTIONS);
              report("uniquePlacements", name, nsPerOp, REBUILD_SELECTIONS);
              assertTrue(nsPerOp > 0.0, name + " produced no measurable work");
            });
  }

  /**
   * Cost growth check for the bulk-fill scenario: report the per-selection cost of successive
   * blocks so a superlinear trend is visible rather than hidden in an average.
   */
  @Test
  @DisplayName("per-selection cost growth while bulk-filling with uniquePlacements")
  void costGrowth_whileBulkFilling() {
    System.out.println("[DEBUG_LOG] --- scenario: cost growth, Square, uniquePlacements=1 ---");
    MemoryShape<?> shape = prepare(Square::new, 12345L);
    knob(shape, "uniquePlacements", 1);

    // Warm up on a throwaway shape so block 1 is not measuring JIT compilation.
    MemoryShape<?> warm = prepare(Square::new, 999L);
    knob(warm, "uniquePlacements", 1);
    measure(warm, Math.min(2_000, WARMUP));

    int block = Math.max(1, GROWTH_SELECTIONS / GROWTH_BLOCKS);
    for (int b = 0; b < GROWTH_BLOCKS; b++) {
      double nsPerOp = measure(shape, block);
      long learned = shape.getEffectiveBadCount();
      System.out.printf(
          "[DEBUG_LOG] block %2d/%d  after %,7d sel  learned %,8d cells  %,9.0f ns/op  %,9.0f sel/s  %5.2f ns per learned cell%n",
          b + 1,
          GROWTH_BLOCKS,
          (b + 1) * block,
          learned,
          nsPerOp,
          1_000_000_000.0 / nsPerOp,
          learned > 0 ? nsPerOp / learned : Double.NaN);
    }
    assertTrue(shape.getEffectiveBadCount() >= 0L, "learned state must remain readable");
  }
}
