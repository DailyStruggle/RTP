package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.StorageLatencyProbe;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Coefficient calibration for a cost-based selection-model planner.
 *
 * <p>This harness exists because a planner that switches storage models at runtime cannot be
 * built on assumed constants. It measures the four coefficient families a switching rule needs -
 * resident bytes, selection cost, reconciliation cost, and the storage-latency term - over the one
 * axis the models are actually being chosen for: how each behaves as {@code range} grows.
 *
 * <p><b>It also tests a premise rather than a proposal.</b> ADR-083's Context asserts that the
 * spiral cuts a compact 2D feature "once per spiral revolution, so its run count scales with
 * feature <i>area</i> rather than <i>perimeter</i>". Three documents rest on that sentence and it
 * has never been measured. {@link #runCountScalesWithFeatureSize()} measures it directly, and is
 * written to report the observed exponent rather than to confirm the claim.
 *
 * <p>Wall-clock JUnit timings, not JMH: cite ratios and exponents, not absolute latencies.
 */
@Tag("simulation")
@DisplayName("selection-model cost calibration")
public class ModelCalibrationBenchmarkTest {

  private static final long RESOLUTION = 1L;
  private static final long SEED = 20260905L;

  /** Bytes per spiral run counted as key + prefix sum, matching the prior ADR-083 footprint row. */
  private static final int SPIRAL_RUN_BYTES = 16;

  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  @AfterAll
  public static void writeReport() {
    REPORT.write("model-calibration");
  }

  // ---------------------------------------------------------------------------------------
  // masks
  // ---------------------------------------------------------------------------------------

  /**
   * A square block of marked cells - the "compact 2D feature" ADR-083's Context is about.
   *
   * @return marked cell count
   */
  private static int markBlock(Square shape, int originX, int originZ, int edge) {
    int marked = 0;
    for (int dx = 0; dx < edge; dx++) {
      for (int dz = 0; dz < edge; dz++) {
        shape.addBadLocation(shape.xzToLocation(originX + dx, originZ + dz), FailTypes.biome);
        marked++;
      }
    }
    return marked;
  }

  /**
   * Clustered blobs at deterministic positions inside {@code radius}, which is what real learned
   * state looks like: marks arrive in spatially correlated clumps, not uniformly at random.
   *
   * @return marked cell count
   */
  private static int markBlobs(Square shape, int radius, int blobs, int edge, long seed) {
    Random placer = new Random(seed);
    int span = Math.max(1, 2 * radius - edge - 2);
    int marked = 0;
    for (int b = 0; b < blobs; b++) {
      int x = placer.nextInt(span) - radius + 1;
      int z = placer.nextInt(span) - radius + 1;
      marked += markBlock(shape, x, z, edge);
    }
    return marked;
  }

  // ---------------------------------------------------------------------------------------
  // measurement
  // ---------------------------------------------------------------------------------------

  private static Square shapeAt(long radius) {
    Square shape = new Square();
    shape.setRng(new Random(SEED));
    shape.setSpatialResolution(RESOLUTION);
    shape.set(GenericMemoryShapeParams.radius, radius);
    return shape;
  }

  /** Run count of the authoritative bad-run table after a rebuild. */
  private static int badRuns(Square shape) {
    long[] keys = shape.badKeysSnapshot();
    return keys == null ? 0 : keys.length;
  }

  /** Run count of the probation table, where a fresh mark is staged under ADR-079. */
  private static int probationRuns(Square shape) {
    long[] keys = shape.probationKeysSnapshot();
    return keys == null ? 0 : keys.length;
  }

  /**
   * Total resident runs. Bad and probation runs are both resident learned state and both cost
   * heap, so a footprint figure that counted only one of them would understate the model.
   */
  private static int runs(Square shape) {
    return badRuns(shape) + probationRuns(shape);
  }

  /**
   * Resident payload of the spiral's learned state, primitive bytes only.
   *
   * <p>Counted the same way the ADR-083 footprint rows were counted (key + prefix sum per run),
   * so the two are comparable. Cause and expiry arrays are reported separately rather than folded
   * in, because they carry information the descriptor tier does not hold at all.
   */
  private static long spiralRunBytes(Square shape) {
    return (long) runs(shape) * SPIRAL_RUN_BYTES;
  }

  private static long spiralFullBytes(Square shape) {
    long bytes = spiralRunBytes(shape);
    byte[] causes = shape.badCausesSnapshot();
    long[] expiries = shape.badExpiriesSnapshot();
    long[] probationKeys = shape.probationKeysSnapshot();
    long[] probationSums = shape.probationPrefixSumsSnapshot();
    byte[] probationCauses = shape.probationCausesSnapshot();
    long[] probationExpiries = shape.probationExpiriesSnapshot();
    if (causes != null) bytes += causes.length;
    if (expiries != null) bytes += 8L * expiries.length;
    if (probationKeys != null) bytes += 8L * probationKeys.length;
    if (probationSums != null) bytes += 8L * probationSums.length;
    if (probationCauses != null) bytes += probationCauses.length;
    if (probationExpiries != null) bytes += 8L * probationExpiries.length;
    return bytes;
  }

  /** Mean ns per {@code rand()} over the shipped selection path. */
  private static double selectNanos(Square shape, int iterations) {
    for (int i = 0; i < iterations / 4; i++) {
      shape.rand();
    }
    long t0 = System.nanoTime();
    long sink = 0L;
    for (int i = 0; i < iterations; i++) {
      sink += shape.rand();
    }
    long elapsed = System.nanoTime() - t0;
    if (sink == Long.MIN_VALUE) throw new AssertionError("unreachable sink guard");
    return elapsed / (double) iterations;
  }

  /**
   * Mean ns to reconcile one fresh mark, including the rebuild it triggers.
   *
   * <p>Each mark lands at a fresh key so no rebuild is a no-op, which is what makes this the
   * coefficient a planner needs rather than a best case.
   */
  private static double reconcileNanos(Square shape, int radius, int marks) {
    Random placer = new Random(SEED ^ radius);
    int span = Math.max(1, 2 * radius - 2);
    long t0 = System.nanoTime();
    for (int i = 0; i < marks; i++) {
      int x = placer.nextInt(span) - radius + 1;
      int z = placer.nextInt(span) - radius + 1;
      shape.addBadLocation(shape.xzToLocation(x, z), FailTypes.biome);
      shape.flushAndRebuild(RESOLUTION);
    }
    long elapsed = System.nanoTime() - t0;
    return elapsed / (double) marks;
  }

  /** Least-squares exponent of {@code y = a * x^k} over positive samples. */
  private static double exponent(double[] xs, double[] ys) {
    int n = 0;
    double sx = 0.0d;
    double sy = 0.0d;
    double sxx = 0.0d;
    double sxy = 0.0d;
    for (int i = 0; i < xs.length; i++) {
      if (xs[i] <= 0.0d || ys[i] <= 0.0d) continue;
      double lx = Math.log(xs[i]);
      double ly = Math.log(ys[i]);
      sx += lx;
      sy += ly;
      sxx += lx * lx;
      sxy += lx * ly;
      n++;
    }
    if (n < 2) return Double.NaN;
    double denom = n * sxx - sx * sx;
    if (Math.abs(denom) < 1e-12d) return Double.NaN;
    return (n * sxy - sx * sy) / denom;
  }

  // ---------------------------------------------------------------------------------------
  // 1. the premise: does run count scale with feature area or with feature perimeter?
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("spiral run count vs. compact-feature size (ADR-083 Context premise)")
  public void runCountScalesWithFeatureSize() {
    int radius = 1024;
    int[] edges = {8, 16, 32, 64, 128};
    double[] areas = new double[edges.length];
    double[] runCounts = new double[edges.length];

    // Features are placed off-centre. An origin-centred block records almost nothing (see the
    // control row below), so measuring the premise there would measure that instead.
    int offset = radius / 2;

    for (int i = 0; i < edges.length; i++) {
      int edge = edges[i];
      Square shape = shapeAt(radius);
      int marked = markBlock(shape, offset, offset, edge);
      shape.flushAndRebuild(RESOLUTION);

      int runCount = runs(shape);
      areas[i] = (double) edge * edge;
      runCounts[i] = runCount;

      String subject = "spiral edge=" + edge;
      REPORT.add("premise", subject, "bad runs", String.valueOf(badRuns(shape)),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("premise", subject, "probation runs", String.valueOf(probationRuns(shape)),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("premise", subject, "effective bad cells",
          String.valueOf(shape.getEffectiveBadCount()), SimulationReport.Provenance.MEASURED);
      REPORT.add("premise", subject, "feature area (cells)", String.valueOf(edge * edge),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("premise", subject, "marked cells", String.valueOf(marked),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("premise", subject, "runs", String.valueOf(runCount),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("premise", subject, "runs / edge", runCount / (double) edge,
          SimulationReport.Provenance.DERIVED);
      REPORT.add("premise", subject, "bits / marked cell",
          runCount * (double) SPIRAL_RUN_BYTES * 8.0d / Math.max(1.0d, marked),
          SimulationReport.Provenance.DERIVED);
    }

    // area^1.0 would confirm the ADR; area^0.5 means runs track the feature's linear extent,
    // i.e. perimeter-order, and the premise is wrong as written.
    double k = exponent(areas, runCounts);
    REPORT.add("premise", "spiral", "run-count exponent vs. area", k,
        SimulationReport.Provenance.DERIVED);
    REPORT.note(
        "Run count ~ area^"
            + String.format("%.3f", k)
            + ". ADR-083's Context claims area-order growth (exponent ~1.0); an exponent near 0.5 "
            + "means the spiral cuts a compact feature into one run per row, which is "
            + "perimeter-order and makes the stated motivation for a hierarchical replacement "
            + "unsupported on this axis.");

    // Control: the same feature at the domain centre. Recorded because the difference is large
    // and unexplained, not because this test is about it.
    Square centred = shapeAt(radius);
    int centredMarks = markBlock(centred, -64, -64, 128);
    centred.flushAndRebuild(RESOLUTION);
    REPORT.add("premise", "origin-centred control", "marks applied",
        String.valueOf(centredMarks), SimulationReport.Provenance.MEASURED);
    REPORT.add("premise", "origin-centred control", "effective bad cells",
        String.valueOf(centred.getEffectiveBadCount()), SimulationReport.Provenance.MEASURED);
    REPORT.add("premise", "origin-centred control", "runs", String.valueOf(runs(centred)),
        SimulationReport.Provenance.MEASURED);

    assertTrue(Double.isFinite(k), "exponent must be computable: " + k);
    assertTrue(runCounts[runCounts.length - 1] > runCounts[0], "a larger feature must cost runs");
  }

  // ---------------------------------------------------------------------------------------
  // 2. coefficient sweep across range at a fixed learned-state size
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("spiral coefficients across range at fixed mark population")
  public void spiralCoefficientsAcrossRange() {
    int[] radii = {256, 512, 1024, 2048, 4096};
    int blobs = 64;
    int edge = 16;
    double[] xs = new double[radii.length];
    double[] bytesPerMark = new double[radii.length];
    double[] reconcile = new double[radii.length];

    for (int i = 0; i < radii.length; i++) {
      int radius = radii[i];
      Square shape = shapeAt(radius);
      int marked = markBlobs(shape, radius, blobs, edge, SEED + radius);
      shape.flushAndRebuild(RESOLUTION);

      int runCount = runs(shape);
      long runBytes = spiralRunBytes(shape);
      long fullBytes = spiralFullBytes(shape);
      long effectiveBad = shape.getEffectiveBadCount();
      double bitsPerMark = runBytes * 8.0d / Math.max(1L, effectiveBad);
      double selectNs = selectNanos(shape, 20000);
      double reconcileNs = reconcileNanos(shape, radius, 200);

      xs[i] = radius;
      bytesPerMark[i] = bitsPerMark;
      reconcile[i] = reconcileNs;

      String subject = "spiral r=" + radius;
      REPORT.add("coefficients", subject, "domain cells",
          String.valueOf(4L * radius * radius), SimulationReport.Provenance.DERIVED);
      REPORT.add("coefficients", subject, "marked cells requested", String.valueOf(marked),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "effective bad cells", String.valueOf(effectiveBad),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "runs", String.valueOf(runCount),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "run bytes", String.valueOf(runBytes),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("coefficients", subject, "full learned-state bytes", String.valueOf(fullBytes),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("coefficients", subject, "bits / marked cell", bitsPerMark,
          SimulationReport.Provenance.DERIVED);
      REPORT.add("coefficients", subject, "t_select ns/op", selectNs,
          SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "t_rebuild ns/mark", reconcileNs,
          SimulationReport.Provenance.MEASURED);
    }

    double bitsExponent = exponent(xs, bytesPerMark);
    double reconcileExponent = exponent(xs, reconcile);
    REPORT.add("coefficients", "spiral", "bits/mark exponent vs. radius", bitsExponent,
        SimulationReport.Provenance.DERIVED);
    REPORT.add("coefficients", "spiral", "t_rebuild exponent vs. radius", reconcileExponent,
        SimulationReport.Provenance.DERIVED);
    REPORT.note(
        "Fixed mark population, growing domain. A bits/mark exponent near zero means the spiral's "
            + "storage cost is set by the learned state and not by the domain, which is the "
            + "property a scaling claim needs.");

    assertTrue(Double.isFinite(bitsExponent), "bits/mark exponent must be computable");
    assertTrue(Double.isFinite(reconcileExponent), "rebuild exponent must be computable");
  }

  // ---------------------------------------------------------------------------------------
  // 3. the two-tier descriptor's compile cost, which is the term ADR-083 never measured
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("two-tier descriptor compile cost scales with domain, not with mark population")
  public void twoTierCompileScalesWithDomain() {
    int[] radii = {128, 256, 512, 1024};
    double[] xs = new double[radii.length];
    double[] compileMs = new double[radii.length];

    for (int i = 0; i < radii.length; i++) {
      int radius = radii[i];
      TwoTierCellRouter router = new TwoTierCellRouter(4, 32, SEED);
      router.setRng(new Random(SEED));
      router.setSpatialResolution(RESOLUTION);
      router.set(GenericMemoryShapeParams.radius, (long) radius);

      // Warm, then time. The descriptor is recompiled from scratch every pulse, which is exactly
      // the cost ADR-083 section 6 defers to an unimplemented incremental delta map.
      router.compile();
      long t0 = System.nanoTime();
      TwoTierCellRouter.Snapshot snapshot = router.compile();
      double ms = (System.nanoTime() - t0) / 1_000_000.0d;

      xs[i] = radius;
      compileMs[i] = ms;

      String subject = "two-tier r=" + radius;
      REPORT.add("coefficients", subject, "domain cells", String.valueOf(4L * radius * radius),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("coefficients", subject, "drawable macro-cells",
          String.valueOf(snapshot.macroCount()), SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "descriptor bytes",
          String.valueOf(snapshot.residentBytes()), SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "compile pulse ms", ms,
          SimulationReport.Provenance.MEASURED);
      REPORT.add("coefficients", subject, "compile ns / domain cell",
          ms * 1_000_000.0d / Math.max(1.0d, 4.0d * radius * radius),
          SimulationReport.Provenance.DERIVED);
    }

    double k = exponent(xs, compileMs);
    REPORT.add("coefficients", "two-tier", "compile exponent vs. radius", k,
        SimulationReport.Provenance.DERIVED);
    REPORT.note(
        "Compile cost ~ radius^"
            + String.format("%.3f", k)
            + ". An exponent near 2.0 confirms the descriptor recompiles per domain cell, so its "
            + "rebuild term grows with the area of the world while the spiral's grows with the "
            + "learned state. Any planner must price this, and ADR-083 section 6's incremental "
            + "delta map is the only thing that would change it.");

    assertTrue(Double.isFinite(k), "exponent must be computable: " + k);
    assertTrue(
        compileMs[compileMs.length - 1] > compileMs[0],
        "a larger domain must cost more to compile: " + compileMs[0] + " -> "
            + compileMs[compileMs.length - 1]);
  }

  // ---------------------------------------------------------------------------------------
  // 4. the storage term and the heap-derived budget the planner switches on
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("storage-latency term and heap-derived resident budget are both observable")
  public void storageAndHeapTermsAreObservable() {
    // The probe is passive, so with no prefilter traffic in this JVM it must read as unsampled
    // rather than as a fast device. A planner has to distinguish "no data" from "cheap disk".
    StorageLatencyProbe.reset();
    StorageLatencyProbe.Snapshot cold = StorageLatencyProbe.snapshot();
    REPORT.add("storage", "probe", "samples before traffic", String.valueOf(cold.samples()),
        SimulationReport.Provenance.MEASURED);
    REPORT.add("storage", "probe", "device before traffic", cold.device().name(),
        SimulationReport.Provenance.MEASURED);

    Runtime runtime = Runtime.getRuntime();
    long max = runtime.maxMemory();
    long used = runtime.totalMemory() - runtime.freeMemory();
    long headroom = Math.max(0L, max - used);

    REPORT.add("storage", "heap", "max heap bytes", String.valueOf(max),
        SimulationReport.Provenance.MEASURED);
    REPORT.add("storage", "heap", "headroom bytes", String.valueOf(headroom),
        SimulationReport.Provenance.MEASURED);

    // Candidate budget rule, stated so it can be argued with: a fraction of headroom, never a
    // fraction of max heap. Sizing against max heap over-commits a server that is already full.
    for (double share : new double[] {0.01d, 0.05d, 0.10d}) {
      REPORT.add("storage", "heap", String.format("budget @ %.0f%% headroom", share * 100.0d),
          String.valueOf((long) (headroom * share)), SimulationReport.Provenance.MODELED);
    }

    // Device-conditional storage term, in the units the objective needs. These are nominal
    // per-read figures for a ~4 MiB region file, replaced at runtime by the probe's EWMA.
    long[] nominalIoNanos = {1_300_000L, 8_000_000L, 33_000_000L};
    String[] deviceNames = {"NVME", "SSD", "HDD"};
    for (int i = 0; i < deviceNames.length; i++) {
      REPORT.add("storage", "t_io " + deviceNames[i], "nominal ns / 4 MiB read",
          String.valueOf(nominalIoNanos[i]), SimulationReport.Provenance.MODELED);
      // One avoided read pays for this many selections at the fastest measured selection cost.
      REPORT.add("storage", "t_io " + deviceNames[i], "selections per avoided read (@500 ns)",
          nominalIoNanos[i] / 500.0d, SimulationReport.Provenance.MODELED);
    }

    REPORT.note(
        "The storage term dominates the compute term by three to five orders of magnitude, so a "
            + "model that avoids one read is worth thousands of extra nanoseconds of selection "
            + "work. That asymmetry - not selection latency - is what should drive the switch, "
            + "and it is why a slow device should raise the resident budget rather than lower it.");

    assertTrue(max > 0L, "max heap must be reported");
    assertTrue(cold.samples() == 0L, "a passive probe must not fabricate samples");
    assertTrue(
        cold.device() == StorageLatencyProbe.Device.UNKNOWN,
        "unsampled probe must not claim a device class");
  }
}
