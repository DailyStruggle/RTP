package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Biome-constrained selection scored on <b>accessibility</b> rather than membership, and the
 * consequence that has for the size of a biome table.
 *
 * <p>Three questions, in the order they decide anything:
 *
 * <ol>
 *   <li><b>How much does the predicate change the score?</b> Under membership, any offered chunk
 *       outside the biome is a false positive. Under accessibility it is a correct answer whenever
 *       the biome is within {@code d} walkable chunks - {@code d = 2} being the 5x5 hard-loaded
 *       area and {@code d = 4} the minimum view distance. Every biome figure so far used the
 *       stricter predicate, so every "admissible resolution" figure is conservative by an unknown
 *       amount. This measures the amount.
 *   <li><b>Does the accessible set cost fewer runs?</b> It is a dilation of the desired set, so its
 *       perimeter falls and its holes fill. If the run count falls materially, biome state becomes
 *       coarsenable with a <i>bounded and justified</i> error, which no other lever in this line of
 *       work has managed - all the others traded accuracy for bytes.
 *   <li><b>Does 1D coalescing approximate 2D dilation better on one curve than the other?</b> This
 *       is the falsifiable part. Dilation fills gaps in 2D; {@code spatialResolution} fills them in
 *       1D. On the spiral-addressed Hilbert curve a 1D gap is 2D-local by construction, so
 *       coalescing should land on ground the accessibility predicate already forgives. On the
 *       shipped spiral a 1D gap of {@code g} at chunk-radius {@code k} is a one-chunk-wide arc
 *       spanning {@code g / 8k} of a revolution, reaching ground nowhere near the biome.
 *       <b>Prediction:</b> under accessibility the hybrid's knob is close to free and the spiral's
 *       is not, and the gap widens with {@code d}. If that does not appear, the locality argument
 *       for the hybrid is weaker than ADR-085 claims, and the report says so rather than burying
 *       it.
 * </ol>
 *
 * <p><b>Scope of the false-positive accounting.</b> Only usable chunks are scored. A biome table is
 * orthogonal to the safety table and both constraints apply at selection time, so charging the
 * biome table for offering an ocean chunk would measure the safety table instead.
 *
 * <p><b>Real save only.</b> The mock world has no biomes yet, so a biome figure cannot be produced
 * from it and this suite skips when no save is present. Fitting biome fields into
 * {@link NoiseWorldMask} is the obvious follow-up and is deliberately not done here - measuring
 * first is what tells us whether it is worth fitting.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("biome accessibility as the predicate, and what it does to a biome table")
public class BiomeAccessibilityBenchmarkTest {

  private static final int RADIUS_CHUNKS = 128;

  /** Point edge, in chunks, of the hybrid's coarse addressing. 32 is one Anvil region file. */
  private static final int POINT_CHUNKS = 32;

  /**
   * Accessibility radii, in chunks. 0 is membership - the predicate every earlier figure used. 2 is
   * the hard-loaded 5x5 ticking area, 4 the minimum view distance, 5 a generous upper bound.
   */
  private static final int[] DISTANCES = {0, 2, 4, 5};

  /** Fixed gaps swept. 2 is ADR-085 section 9c's floor, 3 the shipped default. */
  private static final long[] FIXED_GAPS = {2L, 3L, 8L, 16L, 64L, 256L};

  /** The gap the headline assertion is pinned at: coarse enough that the tables actually differ. */
  private static final long HEADLINE_GAP = 16L;

  /** The accessibility radius the headline assertion is pinned at: the minimum view distance. */
  private static final int HEADLINE_DISTANCE = 4;

  private static final int MAX_REGION_FILES = 256;

  private static final String SAVE_ROOT_PROPERTY = "rtp.simulation.saveRoot";

  private static final String DEFAULT_SAVE_ROOT = "C:\\GameServers";

  private static final int SAVE_SEARCH_DEPTH = 8;

  private static final SimulationReport REPORT = new SimulationReport();

  private static int windowChunks;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    windowChunks = largestCommonWindow();
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "The predicate is accessibility, not membership: a chunk satisfies a request for biome B "
            + "when B is within d walkable chunk steps. d=2 is the 5x5 hard-loaded area and d=4 "
            + "the minimum view distance, so the radius is grounded in vanilla behaviour rather "
            + "than chosen. d=0 is membership and is reported alongside so the change of predicate "
            + "is visible rather than assumed.");
    REPORT.note(
        "Distance is geodesic - the spread crosses only usable ground - because a biome across a "
            + "channel is not reachable. The Euclidean spread is reported beside it so the value "
            + "of that refinement is evidenced; where the two agree, the refinement bought "
            + "nothing.");
    REPORT.note(
        "Only usable chunks are scored. A biome table is orthogonal to the safety table and both "
            + "constraints apply at selection, so charging the biome table for offering an ocean "
            + "chunk would measure the safety table.");
    REPORT.note(
        "The biome grouping in BiomeClass is a stated preference and the target class is chosen as "
            + "the most abundant non-aquatic class in the window, so a figure here is about one "
            + "save's terrain composition. Only the spiral-versus-hybrid comparison is invariant "
            + "to that choice, because both curves are scored against the same target.");
    REPORT.write("biome-accessibility");
  }

  @Test
  @DisplayName("accessibility predicate, table cost, and 1D coalescing against 2D dilation")
  public void accessibilityAgainstMembership() {
    RealWorldVerdictMask mask = realSave();
    Assumptions.assumeTrue(mask != null, "no real save with a fully swept window available");

    BiomeClass target = mostAbundantLandClass(mask);
    Assumptions.assumeTrue(target != BiomeClass.UNKNOWN, "no recognisable land biome in the window");

    BiomeAccessibility access =
        new BiomeAccessibility(
            windowChunks, mask::isOccupied, (cx, cz) -> mask.biomeAt(cx, cz) == target);

    REPORT.add("terrain", "window", "half-edge (chunks)", windowChunks, Provenance.MEASURED);
    REPORT.add("terrain", "window", "usable chunks", access.usableChunks(), Provenance.MEASURED);
    REPORT.add("terrain", "target biome", "class", target.name(), Provenance.MEASURED);
    REPORT.add(
        "terrain", "target biome", "chunks in class", access.desiredChunks(), Provenance.MEASURED);
    assertTrue(access.desiredChunks() > 0L, "target class has no chunks");

    for (int d : DISTANCES) {
      REPORT.add(
          "predicate reach",
          "d=" + d,
          "usable chunks admitted",
          access.accessibleChunks(d),
          Provenance.MEASURED);
      REPORT.add(
          "predicate reach",
          "d=" + d,
          "share of usable ground admitted",
          access.accessibleChunks(d) / (double) access.usableChunks(),
          Provenance.DERIVED);
      REPORT.add(
          "predicate reach",
          "d=" + d,
          "chunks Euclidean admits and geodesic refuses",
          access.tunnelledChunks(d),
          Provenance.MEASURED);
    }

    Square spiral = plainSpiral();
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(RADIUS_CHUNKS, POINT_CHUNKS, true);
    assertTrue(
        fullyAddressed(spiral, hybrid, windowChunks),
        "a chunk in the measured window is not addressed by both curves");

    // --- 2D geodesic dilation: the accessible set stored exactly ------------------------
    long exactRuns = 0L;
    for (int d : DISTANCES) {
      long[] sKeys = keysWhere(spiral, (cx, cz) -> access.isAccessible(cx, cz, d));
      long[] hKeys = keysWhere(hybrid, (cx, cz) -> access.isAccessible(cx, cz, d));
      assertEquals(sKeys.length, hKeys.length, "curves were offered different chunk sets at d=" + d);
      KeyRunTable sTable = KeyRunTable.exact(sKeys, sKeys.length);
      KeyRunTable hTable = KeyRunTable.exact(hKeys, hKeys.length);
      if (d == 0) exactRuns = sTable.runs();

      emit("2D dilation", "spiral d=" + d, access, spiral, sTable);
      emit("2D dilation", "hybrid d=" + d, access, hybrid, hTable);
      REPORT.add(
          "2D dilation",
          "d=" + d,
          "spiral runs / hybrid runs",
          hTable.runs() == 0 ? 0.0d : sTable.runs() / (double) hTable.runs(),
          Provenance.DERIVED);
    }
    assertTrue(exactRuns > 0L, "membership table is empty");

    // --- 1D coalescing of the membership set: the shipped lossy knob --------------------
    long[] spiralDesired = keysWhere(spiral, (cx, cz) -> access.isAccessible(cx, cz, 0));
    long[] hybridDesired = keysWhere(hybrid, (cx, cz) -> access.isAccessible(cx, cz, 0));
    KeyRunTable spiralExact = KeyRunTable.exact(spiralDesired, spiralDesired.length);
    KeyRunTable hybridExact = KeyRunTable.exact(hybridDesired, hybridDesired.length);

    // The invariant the whole comparison rests on: an exact membership table cannot offer a chunk
    // outside the biome, on either curve. A non-zero rate here would mean the key round trip is
    // wrong, which is the fault class that has bitten this work twice.
    assertEquals(
        0.0d,
        falsePositiveRate(access, spiral, spiralExact, 0),
        1e-12,
        "exact spiral membership table offers ground outside the biome");
    assertEquals(
        0.0d,
        falsePositiveRate(access, hybrid, hybridExact, 0),
        1e-12,
        "exact hybrid membership table offers ground outside the biome");

    for (long gap : FIXED_GAPS) {
      KeyRunTable sTable = spiralExact.coalesceFixed(gap);
      KeyRunTable hTable = hybridExact.coalesceFixed(gap);
      emit("1D coalescing", "spiral gap=" + gap, access, spiral, sTable);
      emit("1D coalescing", "hybrid gap=" + gap, access, hybrid, hTable);
      for (int d : DISTANCES) {
        double s = falsePositiveRate(access, spiral, sTable, d);
        double h = falsePositiveRate(access, hybrid, hTable, d);
        REPORT.add(
            "prediction gap=" + gap,
            "d=" + d,
            "spiral false positives / hybrid false positives",
            ratio(s, h),
            Provenance.DERIVED);
      }
    }

    assertHeadline(access, spiral, hybrid, spiralExact, hybridExact);
  }

  /**
   * Pins the result the report is quoted for, so a regression in either curve fails rather than
   * quietly changing a published number.
   *
   * <p>The claim is <b>joint</b>, and that is the point: at the shipped-scale gap the hybrid holds
   * fewer runs <i>and</i> makes fewer accessibility errors. Either alone would be an ordinary
   * trade; together they mean the change of predicate is not paid for in memory.
   *
   * <p>Deliberately asserted at one gap rather than swept: at fine gaps the ordering is the other
   * way round on membership, and hiding that behind an "all gaps" assertion would be exactly the
   * kind of flattering aggregate this work keeps having to withdraw.
   */
  private static void assertHeadline(
      BiomeAccessibility access,
      MemoryShape<?> spiral,
      MemoryShape<?> hybrid,
      KeyRunTable spiralExact,
      KeyRunTable hybridExact) {
    KeyRunTable s = spiralExact.coalesceFixed(HEADLINE_GAP);
    KeyRunTable h = hybridExact.coalesceFixed(HEADLINE_GAP);
    assertTrue(
        h.runs() < s.runs(),
        "hybrid no longer holds fewer runs at gap " + HEADLINE_GAP + ": " + h.runs() + " vs " + s.runs());
    double sFp = falsePositiveRate(access, spiral, s, HEADLINE_DISTANCE);
    double hFp = falsePositiveRate(access, hybrid, h, HEADLINE_DISTANCE);
    assertTrue(
        hFp <= sFp,
        "hybrid no longer makes fewer accessibility errors at d="
            + HEADLINE_DISTANCE
            + ": "
            + hFp
            + " vs "
            + sFp);
  }

  /**
   * Ratio that says something when a denominator is zero.
   *
   * <p>Two curves that both make no error are equal, not infinitely far apart; reporting infinity
   * there reads as a landslide win for whichever side happens to be the denominator.
   */
  private static String ratio(double numerator, double denominator) {
    if (numerator <= 0.0d && denominator <= 0.0d) return "both zero";
    if (denominator <= 0.0d) return "hybrid zero";
    return String.format("%.3f", numerator / denominator);
  }

  // -------------------------------------------------------------------------------------
  // measurement
  // -------------------------------------------------------------------------------------

  private static void emit(
      String section,
      String subject,
      BiomeAccessibility access,
      MemoryShape<?> curve,
      KeyRunTable table) {
    REPORT.add(section, subject, "runs", table.runs(), Provenance.MEASURED);
    REPORT.add(section, subject, "offered usable chunks", offered(access, curve, table), Provenance.MEASURED);
    for (int d : DISTANCES) {
      REPORT.add(
          section,
          subject,
          "false positives at d=" + d,
          falsePositiveRate(access, curve, table, d),
          Provenance.MEASURED);
    }
  }

  /**
   * Share of offered usable ground that does not satisfy the predicate.
   *
   * @param d accessibility radius; 0 is membership
   */
  private static double falsePositiveRate(
      BiomeAccessibility access, MemoryShape<?> curve, KeyRunTable table, int d) {
    long offered = 0L;
    long wrong = 0L;
    for (int cx = -windowChunks; cx < windowChunks; cx++) {
      for (int cz = -windowChunks; cz < windowChunks; cz++) {
        if (!isUsable(access, cx, cz)) continue;
        if (!table.contains(curve.xzToLocation(cx, cz))) continue;
        offered++;
        if (!access.isAccessible(cx, cz, d)) wrong++;
      }
    }
    return offered == 0L ? 0.0d : wrong / (double) offered;
  }

  private static long offered(BiomeAccessibility access, MemoryShape<?> curve, KeyRunTable table) {
    long offered = 0L;
    for (int cx = -windowChunks; cx < windowChunks; cx++) {
      for (int cz = -windowChunks; cz < windowChunks; cz++) {
        if (!isUsable(access, cx, cz)) continue;
        if (table.contains(curve.xzToLocation(cx, cz))) offered++;
      }
    }
    return offered;
  }

  /**
   * Usable ground, expressed through the accessibility view so the two never disagree.
   *
   * <p>Any chunk with a finite geodesic distance is usable by construction, and unreachable usable
   * ground is caught by the Euclidean spread, which crosses everything.
   */
  private static boolean isUsable(BiomeAccessibility access, int cx, int cz) {
    return access.isAccessibleEuclidean(cx, cz, Integer.MAX_VALUE - 1);
  }

  private static long[] keysWhere(MemoryShape<?> shape, BiomeAccessibility.ChunkPredicate keep) {
    long[] keys = new long[4 * windowChunks * windowChunks];
    int out = 0;
    for (int cx = -windowChunks; cx < windowChunks; cx++) {
      for (int cz = -windowChunks; cz < windowChunks; cz++) {
        if (!keep.test(cx, cz)) continue;
        long key = shape.xzToLocation(cx, cz);
        if (key < 0L || key >= shape.getRange()) continue;
        keys[out++] = key;
      }
    }
    long[] trimmed = java.util.Arrays.copyOf(keys, out);
    java.util.Arrays.sort(trimmed);
    return trimmed;
  }

  private static BiomeClass mostAbundantLandClass(RealWorldVerdictMask mask) {
    Map<BiomeClass, Long> tally = new EnumMap<>(BiomeClass.class);
    for (int cx = -windowChunks; cx < windowChunks; cx++) {
      for (int cz = -windowChunks; cz < windowChunks; cz++) {
        if (!mask.isOccupied(cx, cz)) continue;
        tally.merge(mask.biomeAt(cx, cz), 1L, Long::sum);
      }
    }
    for (Map.Entry<BiomeClass, Long> e : tally.entrySet()) {
      REPORT.add("biome composition", e.getKey().name(), "usable chunks", e.getValue(), Provenance.MEASURED);
    }
    BiomeClass best = BiomeClass.UNKNOWN;
    long bestCount = 0L;
    for (Map.Entry<BiomeClass, Long> e : tally.entrySet()) {
      BiomeClass c = e.getKey();
      // Aquatic and unknown are excluded as targets: aquatic ground is the shore the proximity work
      // already prices, and unknown is an absence of data rather than a biome anyone requests.
      if (c == BiomeClass.AQUATIC || c == BiomeClass.UNKNOWN) continue;
      if (e.getValue() > bestCount) {
        bestCount = e.getValue();
        best = c;
      }
    }
    return best;
  }

  // -------------------------------------------------------------------------------------
  // fixtures
  // -------------------------------------------------------------------------------------

  private static int largestCommonWindow() {
    Square spiral = plainSpiral();
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(RADIUS_CHUNKS, POINT_CHUNKS, true);
    for (int w = RADIUS_CHUNKS; w >= 8; w--) {
      if (fullyAddressed(spiral, hybrid, w)) return w;
    }
    throw new IllegalStateException("no window of 8 chunks or more is addressed by both curves");
  }

  private static boolean fullyAddressed(MemoryShape<?> spiral, MemoryShape<?> hybrid, int w) {
    for (int cx = -w; cx < w; cx++) {
      for (int cz = -w; cz < w; cz++) {
        long s = spiral.xzToLocation(cx, cz);
        long h = hybrid.xzToLocation(cx, cz);
        if (s < 0L || s >= spiral.getRange() || h < 0L || h >= hybrid.getRange()) return false;
      }
    }
    return true;
  }

  private static Square plainSpiral() {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, (long) RADIUS_CHUNKS);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    return shape;
  }

  private static RealWorldVerdictMask realSave() {
    Path root = Path.of(System.getProperty(SAVE_ROOT_PROPERTY, DEFAULT_SAVE_ROOT));
    List<Path> dirs = RealWorldVerdictMask.discoverRegionDirectories(root, SAVE_SEARCH_DEPTH);
    if (dirs.isEmpty()) return null;
    RealWorldVerdictMask mask =
        RealWorldVerdictMask.load(
            dirs.get(0), RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, MAX_REGION_FILES);
    if (mask.inscribedRadius() < RADIUS_CHUNKS) return null;
    return mask;
  }
}
