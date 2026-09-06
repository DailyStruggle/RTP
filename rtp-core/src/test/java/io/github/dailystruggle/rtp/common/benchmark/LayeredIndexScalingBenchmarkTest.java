package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Scale sweep for the layered index: outer spiral over coarse cells, inner RLE over Hilbert order.
 *
 * <p>The question is not which encoding is smallest. It is <b>how much RAM a given range costs, and
 * what that leaves for time optimization</b>, so every row reports resident bytes for the part that
 * must stay in memory separately from the part that can be paged.
 *
 * <p>Measured at small and mid range only, because the sweep is bounded by test runtime; each
 * scaling law is fit as an exponent so the large-range figures are extrapolations with a stated
 * basis rather than assertions.
 *
 * <p><b>Mark model:</b> fixed <i>density</i>, not fixed population. Real unusable terrain - ocean,
 * deep water, protected land - occupies a roughly constant fraction of any area, so mark count must
 * grow with the domain. That is the only regime in which a memory ceiling can exist at all.
 */
@Tag("simulation")
@DisplayName("layered index scale sweep")
public class LayeredIndexScalingBenchmarkTest {

  private static final long SEED = 20260905L;
  private static final int SPIRAL_RUN_BYTES = 16;

  /**
   * Blocks per addressed cell. Every radius, outer edge and feature size in this class is counted
   * in <b>chunks</b>, and this constant exists only to report the block equivalent.
   *
   * <p>A previous version of this class labelled the same numbers as blocks and swept inner cell
   * edges of 1, 2, 4 and 8 blocks. Those resolutions do not exist: safety selection places a
   * candidate at the centre of a chunk, so one chunk is the finest cell that carries information
   * and there is no curve inside a chunk to address. The inner Hilbert curve runs over the chunks
   * within an outer cell. Relabelling is the whole correction for the sweep rows - the structures
   * were always addressing one cell per mark - but it moves every reported radius up by this
   * factor, and it voids the old precision sweep outright, since four of its five points were the
   * same physical resolution as the fifth.
   */
  private static final int BLOCKS_PER_CHUNK = 16;

  /** Fraction of the domain marked bad, held constant across radii. */
  private static final double DENSITY = 0.03d;

  /** Edge of one clustered bad feature, in chunks: 64 chunks is a 1 024-block ocean-scale patch. */
  private static final int FEATURE_EDGE = 64;

  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  @AfterAll
  public static void writeReport() {
    REPORT.write("layered-index-scaling");
  }

  // -------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------

  /** Deterministic clustered feature origins covering {@link #DENSITY} of the domain. */
  private static int[][] featureOrigins(int radius) {
    long domain = 4L * radius * radius;
    int features = (int) Math.max(1L, (long) (domain * DENSITY) / (FEATURE_EDGE * FEATURE_EDGE));
    Random placer = new Random(SEED ^ radius);
    int span = Math.max(1, 2 * radius - FEATURE_EDGE);
    int[][] origins = new int[features][2];
    for (int i = 0; i < features; i++) {
      origins[i][0] = placer.nextInt(span) - radius;
      origins[i][1] = placer.nextInt(span) - radius;
    }
    return origins;
  }

  private static LayeredHilbertIndex layered(int radius, int outerEdge, int[][] origins) {
    LayeredHilbertIndex index = new LayeredHilbertIndex(radius, outerEdge, 1, SEED);
    for (int[] origin : origins) {
      for (int dx = 0; dx < FEATURE_EDGE; dx++) {
        for (int dz = 0; dz < FEATURE_EDGE; dz++) {
          index.addBadLocation(origin[0] + dx, origin[1] + dz);
        }
      }
    }
    index.flush();
    return index;
  }

  private static Square spiral(int radius, int[][] origins) {
    Square shape = new Square();
    shape.setRng(new Random(SEED));
    shape.setSpatialResolution(1L);
    shape.set(GenericMemoryShapeParams.radius, (long) radius);
    for (int[] origin : origins) {
      for (int dx = 0; dx < FEATURE_EDGE; dx++) {
        for (int dz = 0; dz < FEATURE_EDGE; dz++) {
          shape.addBadLocation(
              shape.xzToLocation(origin[0] + dx, origin[1] + dz), FailTypes.biome);
        }
      }
    }
    shape.flushAndRebuild(1L);
    return shape;
  }

  private static int spiralRuns(Square shape) {
    long[] bad = shape.badKeysSnapshot();
    long[] probation = shape.probationKeysSnapshot();
    return (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
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

  /** Coefficient {@code a} of {@code y = a * x^k}, so the fit can be extrapolated. */
  private static double coefficient(double[] xs, double[] ys, double k) {
    double sum = 0.0d;
    int n = 0;
    for (int i = 0; i < xs.length; i++) {
      if (xs[i] <= 0.0d || ys[i] <= 0.0d) continue;
      sum += Math.log(ys[i]) - k * Math.log(xs[i]);
      n++;
    }
    return n == 0 ? Double.NaN : Math.exp(sum / n);
  }

  private static double selectNanos(LayeredHilbertIndex index, int iterations) {
    for (int i = 0; i < iterations / 4; i++) {
      index.rand();
    }
    long t0 = System.nanoTime();
    long sink = 0L;
    for (int i = 0; i < iterations; i++) {
      sink += index.rand();
    }
    long elapsed = System.nanoTime() - t0;
    if (sink == Long.MIN_VALUE) throw new AssertionError("unreachable sink guard");
    return elapsed / (double) iterations;
  }

  // -------------------------------------------------------------------------------------
  // 1. correctness: the model must be valid before its cost means anything
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("layered selection never returns a marked cell and covers the domain uniformly")
  public void selectionIsValid() {
    int radius = 256;
    int outerEdge = 64;
    LayeredHilbertIndex index = new LayeredHilbertIndex(radius, outerEdge, 1, SEED);

    // One feature, off-centre, so a hit on it is unambiguous. Coordinates are chunks.
    boolean[][] bad = new boolean[2 * radius][2 * radius];
    for (int dx = 0; dx < 128; dx++) {
      for (int dz = 0; dz < 128; dz++) {
        int x = 64 + dx;
        int z = 64 + dz;
        index.addBadLocation(x, z);
        bad[x + radius][z + radius] = true;
      }
    }
    index.flush();

    long expectedBad = 128L * 128L;
    assertEquals(expectedBad, index.totalBad(), "every mark must be retained at full precision");
    assertEquals(
        4L * radius * radius - expectedBad, index.totalGood(), "good count must be exact");

    int draws = 200_000;
    int hits = 0;
    long[] perOuter = new long[index.outerCellCount()];
    for (int i = 0; i < draws; i++) {
      long packed = index.rand();
      int x = (int) (packed & 0xFFFFFFFFL);
      int z = (int) (packed >> 32);
      assertTrue(
          x >= -radius && x < radius && z >= -radius && z < radius,
          "draw must stay inside the domain: " + x + "," + z);
      if (bad[x + radius][z + radius]) hits++;
      int ox = (x + radius) / outerEdge;
      int oz = (z + radius) / outerEdge;
      perOuter[ox * (2 * radius / outerEdge) + oz]++;
    }
    assertEquals(0, hits, "a marked cell must never be drawn");

    // Uniformity over good cells implies an outer cell's share equals its good-count share.
    // The wholly-good cells all carry identical weight, so their counts must agree closely.
    long minFull = Long.MAX_VALUE;
    long maxFull = 0L;
    int fullCells = 0;
    int cellsEdge = 2 * radius / outerEdge;
    int innerCells = outerEdge * outerEdge;
    for (int ox = 0; ox < cellsEdge; ox++) {
      for (int oz = 0; oz < cellsEdge; oz++) {
        int marked = 0;
        for (int dx = 0; dx < outerEdge; dx++) {
          for (int dz = 0; dz < outerEdge; dz++) {
            if (bad[ox * outerEdge + dx][oz * outerEdge + dz]) marked++;
          }
        }
        if (marked != 0) continue;
        long count = perOuter[ox * cellsEdge + oz];
        minFull = Math.min(minFull, count);
        maxFull = Math.max(maxFull, count);
        fullCells++;
      }
    }
    double expected = draws * (double) innerCells / index.totalGood();
    REPORT.add("validity", "layered r=256", "draws", String.valueOf(draws),
        SimulationReport.Provenance.MEASURED);
    REPORT.add("validity", "layered r=256", "marked cells drawn", String.valueOf(hits),
        SimulationReport.Provenance.MEASURED);
    REPORT.add("validity", "layered r=256", "fully-good outer cells",
        String.valueOf(fullCells), SimulationReport.Provenance.MEASURED);
    REPORT.add("validity", "layered r=256", "expected draws per full cell", expected,
        SimulationReport.Provenance.DERIVED);
    REPORT.add("validity", "layered r=256", "observed min per full cell",
        String.valueOf(minFull), SimulationReport.Provenance.MEASURED);
    REPORT.add("validity", "layered r=256", "observed max per full cell",
        String.valueOf(maxFull), SimulationReport.Provenance.MEASURED);

    // Generous band: this asserts no cell is systematically starved or favoured, which is the
    // failure a weighted directory would produce. It is not a distributional test.
    assertTrue(minFull > expected * 0.6d, "no full cell may be starved: " + minFull);
    assertTrue(maxFull < expected * 1.4d, "no full cell may be favoured: " + maxFull);
  }

  // -------------------------------------------------------------------------------------
  // 2. the scale sweep: what does a given range cost in RAM, layered vs. spiral?
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("layered and spiral resident cost across small and mid range, at fixed density")
  public void residentCostAcrossRange() {
    // Chunks. One outer cell is one Anvil region file, which is 32 chunks square.
    int[] radii = {128, 256, 512, 1024, 2048};
    int outerEdge = 32;

    double[] xs = new double[radii.length];
    double[] layeredResident = new double[radii.length];
    double[] layeredFull = new double[radii.length];
    double[] spiralBytes = new double[radii.length];

    for (int i = 0; i < radii.length; i++) {
      int radius = radii[i];
      int[][] origins = featureOrigins(radius);
      long marks = (long) origins.length * FEATURE_EDGE * FEATURE_EDGE;

      // At radius 128 the domain is smaller than one region cell, so the outer level degenerates
      // to a single cell. That is the small-range regime, and it is reported rather than skipped.
      int edge = Math.min(outerEdge, 2 * radius);
      LayeredHilbertIndex index = layered(radius, edge, origins);
      Square shape = spiral(radius, origins);

      long domain = 4L * radius * radius;
      xs[i] = radius;
      layeredResident[i] = index.residentDirectoryBytes();
      layeredFull[i] = index.fullyResidentBytes();
      spiralBytes[i] = (long) spiralRuns(shape) * SPIRAL_RUN_BYTES;

      String subject = "r=" + radius + "ch (" + radius * BLOCKS_PER_CHUNK + " blocks)";
      REPORT.add("scaling", subject, "domain cells", String.valueOf(domain),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "outer cell edge", String.valueOf(edge),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "outer cells", String.valueOf(index.outerCellCount()),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "marks applied", String.valueOf(marks),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "layered bad cells", String.valueOf(index.totalBad()),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "spiral effective bad cells",
          String.valueOf(shape.getEffectiveBadCount()), SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "layered runs", String.valueOf(index.totalRuns()),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "spiral runs", String.valueOf(spiralRuns(shape)),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "layered resident directory bytes",
          String.valueOf((long) layeredResident[i]), SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "layered blob bytes (pageable)",
          String.valueOf(index.blobBytes()), SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "layered fully-resident bytes",
          String.valueOf((long) layeredFull[i]), SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "spiral run bytes",
          String.valueOf((long) spiralBytes[i]), SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "layered mean blob bytes", index.meanBlobBytes(),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "candidates per blob read",
          String.valueOf(index.candidatesPerBlobRead()), SimulationReport.Provenance.DERIVED);
      REPORT.add("scaling", subject, "dead outer cells (never read)",
          String.valueOf(index.deadOuterCells()), SimulationReport.Provenance.MEASURED);
      REPORT.add("scaling", subject, "layered select ns/op", selectNanos(index, 100_000),
          SimulationReport.Provenance.MEASURED);
    }

    double kResident = exponent(xs, layeredResident);
    double kFull = exponent(xs, layeredFull);
    double kSpiral = exponent(xs, spiralBytes);
    REPORT.add("scaling", "layered", "resident-directory exponent vs. radius", kResident,
        SimulationReport.Provenance.DERIVED);
    REPORT.add("scaling", "layered", "fully-resident exponent vs. radius", kFull,
        SimulationReport.Provenance.DERIVED);
    REPORT.add("scaling", "spiral", "run-bytes exponent vs. radius", kSpiral,
        SimulationReport.Provenance.DERIVED);

    // Extrapolation. Stated as MODELED because it is a fit, not a measurement: the sweep stops at
    // mid range and a 100 km border is two decades beyond it.
    double aResident = coefficient(xs, layeredResident, kResident);
    double aFull = coefficient(xs, layeredFull, kFull);
    double aSpiral = coefficient(xs, spiralBytes, kSpiral);
    // Chunk radii. 6 250 chunks is a 100 km border, i.e. the largest range worth projecting to.
    int[] projected = {512, 2048, 6250};
    for (int radius : projected) {
      String subject = "projected r=" + radius + "ch (" + radius * BLOCKS_PER_CHUNK + " blocks)";
      // The directory is a closed form - one slot per outer cell - so it is projected exactly
      // rather than fitted. The fitted exponent is reported alongside only to expose that the
      // fit is contaminated by the degenerate small-radius rows where the domain is one cell.
      long outerCells = (long) Math.ceil(2.0d * radius / outerEdge);
      outerCells *= outerCells;
      REPORT.add("extrapolation", subject, "layered resident directory bytes (closed form)",
          String.valueOf(outerCells * 8L), SimulationReport.Provenance.DERIVED);
      REPORT.add("extrapolation", subject, "layered resident directory bytes (fitted)",
          aResident * Math.pow(radius, kResident), SimulationReport.Provenance.MODELED);
      REPORT.add("extrapolation", subject, "layered fully-resident bytes",
          aFull * Math.pow(radius, kFull), SimulationReport.Provenance.MODELED);
      REPORT.add("extrapolation", subject, "spiral run bytes",
          aSpiral * Math.pow(radius, kSpiral), SimulationReport.Provenance.MODELED);
      REPORT.add("extrapolation", subject, "resident fraction if only directory is held",
          (outerCells * 8L) / (aFull * Math.pow(radius, kFull)),
          SimulationReport.Provenance.MODELED);
    }

    REPORT.note(
        "Fixed density, growing domain. Both models' footprints are driven by mark population, "
            + "which grows as radius^2 under fixed density, so an exponent near 2.0 is the "
            + "expected floor rather than a defect. What separates them is the split: the "
            + "layered model's resident half is the directory alone and its run storage is "
            + "pageable, while the spiral's run table is resident in full.");
    assertTrue(Double.isFinite(kResident), "resident exponent must be computable");
    assertTrue(Double.isFinite(kSpiral), "spiral exponent must be computable");
  }

  // -------------------------------------------------------------------------------------
  // 3. outer granularity: the knob that decides expand resolution and blob size
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("outer granularity trades expansion resolution against blob size and fragmentation")
  public void outerGranularitySweep() {
    int radius = 1024;
    int[][] origins = featureOrigins(radius);
    int[] edges = {16, 64, 256, 512};

    for (int edge : edges) {
      LayeredHilbertIndex index = layered(radius, edge, origins);
      String subject = "r=1024 outerEdge=" + edge;
      REPORT.add("granularity", subject, "outer cells",
          String.valueOf(index.outerCellCount()), SimulationReport.Provenance.DERIVED);
      REPORT.add("granularity", subject, "expand step (blocks)", String.valueOf(edge),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("granularity", subject, "expand step / radius", edge / (double) radius,
          SimulationReport.Provenance.DERIVED);
      REPORT.add("granularity", subject, "runs", String.valueOf(index.totalRuns()),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("granularity", subject, "occupied blobs",
          String.valueOf(index.occupiedBlobs()), SimulationReport.Provenance.MEASURED);
      REPORT.add("granularity", subject, "dead outer cells (never read)",
          String.valueOf(index.deadOuterCells()), SimulationReport.Provenance.MEASURED);
      REPORT.add("granularity", subject, "resident directory bytes",
          String.valueOf(index.residentDirectoryBytes()), SimulationReport.Provenance.MEASURED);
      REPORT.add("granularity", subject, "blob bytes (pageable)",
          String.valueOf(index.blobBytes()), SimulationReport.Provenance.MEASURED);
      REPORT.add("granularity", subject, "mean blob bytes", index.meanBlobBytes(),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("granularity", subject, "candidates per blob read",
          String.valueOf(index.candidatesPerBlobRead()), SimulationReport.Provenance.DERIVED);
      REPORT.add("granularity", subject, "select ns/op", selectNanos(index, 100_000),
          SimulationReport.Provenance.MEASURED);
    }

    REPORT.note(
        "Granularity is a ratio, not a constant: expand step / radius is what matters, so a "
            + "512-block outer cell is 50% of a 1 km radius and 0.5% of a 100 km one. The "
            + "coarsest edge that keeps that ratio small is also the one that maximizes "
            + "candidates served per storage read, so the two requirements are anti-correlated "
            + "rather than in conflict.");
  }

  // -------------------------------------------------------------------------------------
  // 4. reconciliation: the axis the model is actually being chosen for
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("reconciliation cost per mark is local to one blob, not global to the curve")
  public void reconciliationIsLocal() {
    int[] radii = {256, 512, 1024, 2048};
    int outerEdge = 512;
    int marks = 200;

    double[] xs = new double[radii.length];
    double[] layeredNanos = new double[radii.length];
    double[] spiralNanos = new double[radii.length];

    for (int i = 0; i < radii.length; i++) {
      int radius = radii[i];
      int[][] origins = featureOrigins(radius);
      int edge = Math.min(outerEdge, 2 * radius);

      LayeredHilbertIndex index = layered(radius, edge, origins);
      Square shape = spiral(radius, origins);

      Random placer = new Random(SEED ^ radius);
      int span = Math.max(1, 2 * radius - 2);
      int[] px = new int[marks];
      int[] pz = new int[marks];
      for (int m = 0; m < marks; m++) {
        px[m] = placer.nextInt(span) - radius + 1;
        pz[m] = placer.nextInt(span) - radius + 1;
      }

      long t0 = System.nanoTime();
      for (int m = 0; m < marks; m++) {
        index.addBadLocation(px[m], pz[m]);
        index.flush();
      }
      double layeredNs = (System.nanoTime() - t0) / (double) marks;

      t0 = System.nanoTime();
      for (int m = 0; m < marks; m++) {
        shape.addBadLocation(shape.xzToLocation(px[m], pz[m]), FailTypes.biome);
        shape.flushAndRebuild(1L);
      }
      double spiralNs = (System.nanoTime() - t0) / (double) marks;

      xs[i] = radius;
      layeredNanos[i] = layeredNs;
      spiralNanos[i] = spiralNs;

      String subject = "r=" + radius;
      REPORT.add("reconciliation", subject, "layered ns / mark", layeredNs,
          SimulationReport.Provenance.MEASURED);
      REPORT.add("reconciliation", subject, "spiral ns / mark", spiralNs,
          SimulationReport.Provenance.MEASURED);
      REPORT.add("reconciliation", subject, "spiral / layered", spiralNs / layeredNs,
          SimulationReport.Provenance.DERIVED);
      REPORT.add("reconciliation", subject, "blobs rebuilt per mark",
          index.flushedBlobs() / (double) marks, SimulationReport.Provenance.MEASURED);
    }

    double kLayered = exponent(xs, layeredNanos);
    double kSpiral = exponent(xs, spiralNanos);
    REPORT.add("reconciliation", "layered", "ns/mark exponent vs. radius", kLayered,
        SimulationReport.Provenance.DERIVED);
    REPORT.add("reconciliation", "spiral", "ns/mark exponent vs. radius", kSpiral,
        SimulationReport.Provenance.DERIVED);
    REPORT.note(
        "Reconciliation is the axis a hierarchical model was proposed to improve. A negative "
            + "exponent for the layered model means a mark's cost falls as range grows, because "
            + "the work is bounded by one blob while a larger domain spreads marks over more "
            + "blobs. The spiral's positive exponent of the same magnitude is the global "
            + "reconciliation cost the layering exists to remove. Caveat: at radius 256 and "
            + "below the domain is a single outer cell, so the layered model degenerates to the "
            + "spiral and is measurably no better - the crossover, not the asymptote, is what a "
            + "small-server default has to respect.");
    assertTrue(Double.isFinite(kLayered), "layered exponent must be computable");
  }

  // -------------------------------------------------------------------------------------
  // 5. precision lever: coarsening as Hilbert-bit truncation
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("coarsening by dropping low-order Hilbert bits trades precision for bytes")
  public void precisionLever() {
    int radius = 1024;
    int outerEdge = 32;
    int[][] origins = featureOrigins(radius);
    // Inner cell edges in chunks. 1 is full precision; 32 is one region file per inner cell, which
    // collapses the inner curve entirely and is the coarsest the outer grid can express here.
    int[] resolutions = {1, 2, 4, 8, 16, 32};

    for (int res : resolutions) {
      LayeredHilbertIndex index = new LayeredHilbertIndex(radius, outerEdge, res, SEED);
      for (int[] origin : origins) {
        for (int dx = 0; dx < FEATURE_EDGE; dx++) {
          for (int dz = 0; dz < FEATURE_EDGE; dz++) {
            index.addBadLocation(origin[0] + dx, origin[1] + dz);
          }
        }
      }
      index.flush();

      long badChunks = index.totalBad() * (long) res * res;
      String subject = "r=1024ch innerCell=" + res + "ch";
      REPORT.add("precision", subject, "inner cell edge (chunks)", String.valueOf(res),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("precision", subject, "inner cells per outer cell",
          String.valueOf(index.innerCellCount()), SimulationReport.Provenance.DERIVED);
      REPORT.add("precision", subject, "bad inner cells", String.valueOf(index.totalBad()),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("precision", subject, "chunks excluded", String.valueOf(badChunks),
          SimulationReport.Provenance.DERIVED);
      REPORT.add("precision", subject, "runs", String.valueOf(index.totalRuns()),
          SimulationReport.Provenance.MEASURED);
      REPORT.add("precision", subject, "fully-resident bytes",
          String.valueOf(index.fullyResidentBytes()), SimulationReport.Provenance.MEASURED);
    }

    REPORT.note(
        "Coarsening here is truncation of low-order Hilbert bits, so a merged region is always a "
            + "quadtree-aligned square. Chunks excluded above the mark count is the over-exclusion "
            + "the operator pays for the byte saving, and it is bounded by the inner cell area - "
            + "which is what makes a hard floor on inner resolution a meaningful guarantee.");
  }
}
