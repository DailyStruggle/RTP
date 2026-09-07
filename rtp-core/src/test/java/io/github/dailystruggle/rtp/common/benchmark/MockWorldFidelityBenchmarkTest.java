package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Does a seeded noise world stand in for a real one?
 *
 * <p>Every figure in the ADR-083/084/085 line of work was measured on one hand-copied save. That
 * save is finite, so reaching border scale meant tiling it, and tiling repeats - it cannot produce
 * coastline it does not already contain, and its seams manufactured mixed cells that were mistaken
 * for a model property once already (ADR-084 section 15, ADR-085 section 8). It is also occupancy
 * rather than safety: "the chunk exists on disk" counts a fully generated ocean as usable ground.
 *
 * <p>This suite replaces both limits with something measurable:
 *
 * <ul>
 *   <li>{@link NoiseWorldMask} - unbounded seeded terrain whose usable share is an input, composed
 *       from ocean, river, pond and lava-pool features rather than one threshold.
 *   <li>{@link RealWorldVerdictMask} - real pass/fail from a real save, decided by the shipped Anvil
 *       column probe against the shipped unsafe-block list, so the reference is the question the
 *       plugin actually asks rather than a proxy for it.
 * </ul>
 *
 * <p>The comparison is deliberately <b>not</b> an assertion that the two look alike. Resemblance is
 * reported as four statistics that are known to drive run-length cost - usable share, boundary
 * fraction, mean unusable patch size, and region-aligned mixed fraction - and both worlds are drawn
 * to the same scale with the same colouring so the shapes can be judged rather than asserted. What
 * is asserted is only what the mock is required to do: land in the requested density band, be
 * reproducible from its seed, and produce clustered rather than sprinkled unusable ground.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("mock world fidelity: seeded noise terrain against real Anvil pass/fail")
public class MockWorldFidelityBenchmarkTest {

  private static final long SEED = 20260906L;

  /**
   * Usable-share targets swept, per the request: 35% to 65%, weighted to the low end.
   *
   * <p>The low end is the interesting one - a worst-case world is where a table's run count and a
   * curve's locality are stressed - so 0.35 and 0.45 carry the comparison and 0.65 is present only
   * to show the calibration is not fitted to one point.
   */
  private static final double[] TARGET_SHARES = {0.35d, 0.45d, 0.65d};

  /** Share used for the drawn comparison and the clustering statistics. */
  private static final double PRIMARY_SHARE = 0.45d;

  /** Slack on the band edges, covering the calibration's own convergence tolerance. */
  private static final double BAND_SLACK = 0.01d;

  /** Window half-edge in chunks for the statistics. 512 chunks is 8 km of border. */
  private static final int STATS_RADIUS_CHUNKS = 512;

  /** Window half-edge in chunks for the drawn rasters. Kept small enough to read at a glance. */
  private static final int IMAGE_RADIUS_CHUNKS = 128;

  /** Pixels per chunk in the drawn rasters. */
  private static final int IMAGE_SCALE = 3;

  /** Cell edge, in chunks, of the region-aligned mixed-fraction statistic. One Anvil region. */
  private static final int REGION_CHUNKS = 32;

  /**
   * Real saves the parameter fit runs against.
   *
   * <p>More than one on purpose: knobs fitted to a single save describe that save. Bounded at two
   * because each save pays a full decode plus a coordinate-descent search.
   */
  private static final int MAX_SAVES_MATCHED = 2;

  /**
   * Window half-edge for the parameter fit, in chunks.
   *
   * <p>Smaller than the statistics window: the search evaluates the three shape statistics dozens
   * of times per save, and each evaluation re-calibrates density over the window.
   */
  private static final int MATCH_RADIUS_CHUNKS = 96;

  /** Region files decoded from the real save. Each is up to a few MB and 1024 column probes. */
  private static final int MAX_REGION_FILES = 256;

  /** Where real server saves are looked for, overridable so this is not machine-specific. */
  private static final String SAVE_ROOT_PROPERTY = "rtp.simulation.saveRoot";

  private static final String DEFAULT_SAVE_ROOT = "C:\\GameServers";

  private static final int SAVE_SEARCH_DEPTH = 8;

  private static final int ARGB_USABLE = 0x4C8C4A;
  private static final int ARGB_OCEAN = 0x1B4F8F;
  private static final int ARGB_RIVER = 0x39A0D8;
  private static final int ARGB_POND = 0x7FC9E8;
  private static final int ARGB_LAVA = 0xD1541F;
  private static final int ARGB_OTHER = 0x8A7F6A;
  private static final int ARGB_UNGENERATED = 0x2A323B;
  private static final int ARGB_ISOLATED = 0xB84C8C;

  private static final SimulationReport REPORT = new SimulationReport();

  /** Pass/fail over a chunk window, so the same statistics run on either world. */
  private interface Occupancy {
    boolean usable(int cx, int cz);
  }

  /**
   * The four statistics that drive run-length cost, measured identically on both worlds.
   *
   * @param usableShare share of the window that is a viable starting position
   * @param boundaryFraction share of usable chunks with at least one unusable four-neighbour
   * @param meanPatchChunks mean size of a connected unusable patch, in chunks
   * @param maxPatchChunks largest connected unusable patch, in chunks
   * @param patchCount connected unusable patches found
   * @param mixedRegionFraction share of 32-chunk cells holding both usable and unusable ground
   */
  private record Clustering(
      double usableShare,
      double boundaryFraction,
      double meanPatchChunks,
      long maxPatchChunks,
      long patchCount,
      double mixedRegionFraction) {}

  @AfterAll
  static void report() {
    REPORT.note(
        "The mock is not asserted to resemble the real save. Resemblance is reported as usable "
            + "share, boundary fraction, mean unusable patch size and region-aligned mixed "
            + "fraction, because those are the quantities run-length cost depends on; the images "
            + "are drawn from the same run so the shapes can be judged alongside the numbers.");
    REPORT.note(
        "Real pass/fail is decided by shipped code: AnvilReader.readColumnProbe decodes the chunk "
            + "centre column and the surface heightmap, AnvilPrefilter.DEFAULT_RECONCILER "
            + "normalises palette identifiers, and the surface plus the two blocks above it are "
            + "tested against the shipped unsafeBlocks list. Nothing is written and no chunk is "
            + "generated.");
    REPORT.note(
        "The real-save reference is the chunk centre column, not all 256 columns. The shipped "
            + "prefilter rejects a chunk when any column is unsafe, which is right for an advisory "
            + "filter and wrong for a per-chunk mask - at that rule almost every generated chunk "
            + "carries one unsafe block somewhere. The chunk-granular unit these measurements "
            + "address is the chunk centre, so the centre column decides the cell.");
    REPORT.note(
        "Tag entries (#minecraft:fire, #minecraft:logs) and the waterlogged state predicate are "
            + "omitted from the unsafe list because expanding them needs a platform registry. "
            + "Their absence can only make the real mask more permissive, so a real usable share "
            + "reported here is an upper bound.");
    REPORT.write("mock-world-fidelity");
  }

  @Test
  @DisplayName("noise terrain lands in the requested 35-65% usable band at every target")
  void noiseTerrainLandsInRequestedBand() {
    for (double target : TARGET_SHARES) {
      NoiseWorldMask mask = new NoiseWorldMask(SEED, STATS_RADIUS_CHUNKS, target);
      long land = 0L;
      long ocean = 0L;
      long river = 0L;
      long pond = 0L;
      long lava = 0L;
      long isolated = 0L;
      long total = 0L;
      for (int cz = -STATS_RADIUS_CHUNKS; cz < STATS_RADIUS_CHUNKS; cz++) {
        for (int cx = -STATS_RADIUS_CHUNKS; cx < STATS_RADIUS_CHUNKS; cx++) {
          total++;
          switch (mask.classify(cx, cz)) {
            case LAND -> land++;
            case OCEAN -> ocean++;
            case RIVER -> river++;
            case POND -> pond++;
            case LAVA -> lava++;
            case ISOLATED -> isolated++;
          }
        }
      }
      String subject = String.format("target=%.2f", target);
      double realised = (double) land / total;
      REPORT.add("noise calibration", subject, "realised usable share", realised, Provenance.MEASURED);
      REPORT.add("noise calibration", subject, "sea level (noise units)", mask.seaLevel(), Provenance.MEASURED);
      REPORT.add(
          "noise calibration",
          subject,
          "bisection steps",
          String.valueOf(mask.calibrationIterations()),
          Provenance.MEASURED);
      REPORT.add("noise composition", subject, "ocean share", (double) ocean / total, Provenance.MEASURED);
      REPORT.add("noise composition", subject, "river share", (double) river / total, Provenance.MEASURED);
      REPORT.add("noise composition", subject, "pond share", (double) pond / total, Provenance.MEASURED);
      REPORT.add("noise composition", subject, "lava share", (double) lava / total, Provenance.MEASURED);
      REPORT.add(
          "noise composition",
          subject,
          "stranded-island share",
          (double) isolated / total,
          Provenance.MEASURED);

      // Lava is present for shape, not area: the real save measured 0.000 at chunk-centre
      // granularity, and a lava field large enough to move the density would be the wrong world.
      assertTrue(
          (double) lava / total < 0.005d,
          "lava share " + ((double) lava / total) + " - lava should be rare, not a feature by area");

      // The band the request states, and the target the calibration was given. Both are asserted:
      // the band alone would pass a calibration that always returns the same share.
      //
      // BAND_SLACK exists because the calibration bisects against a strided sample and stops
      // within 0.002 of its target, so a target sitting exactly on a band edge can realise a
      // fraction of a point outside it. Asserting the band to the last decimal would be asserting
      // the sampling error, not the requirement.
      assertTrue(
          realised >= 0.35d - BAND_SLACK && realised <= 0.65d + BAND_SLACK,
          "usable share " + realised + " outside the requested 35-65% band at target " + target);
      assertTrue(
          Math.abs(realised - target) <= 0.04d,
          "usable share " + realised + " missed target " + target + " by more than 4 points");
    }
  }

  @Test
  @DisplayName("noise terrain is reproducible from its seed and clustered rather than sprinkled")
  void noiseTerrainIsReproducibleAndClustered() {
    NoiseWorldMask a = new NoiseWorldMask(SEED, STATS_RADIUS_CHUNKS, PRIMARY_SHARE);
    NoiseWorldMask b = new NoiseWorldMask(SEED, STATS_RADIUS_CHUNKS, PRIMARY_SHARE);
    for (int cz = -64; cz < 64; cz++) {
      for (int cx = -64; cx < 64; cx++) {
        assertTrue(
            a.classify(cx, cz) == b.classify(cx, cz),
            "two masks from the same seed disagreed at (" + cx + "," + cz + ")");
      }
    }

    NoiseWorldMask other = new NoiseWorldMask(SEED + 1L, STATS_RADIUS_CHUNKS, PRIMARY_SHARE);
    long differing = 0L;
    for (int cz = -64; cz < 64; cz++) {
      for (int cx = -64; cx < 64; cx++) {
        if (a.isOccupied(cx, cz) != other.isOccupied(cx, cz)) differing++;
      }
    }
    REPORT.add(
        "noise determinism",
        "seed+1",
        "cells differing over 128x128",
        String.valueOf(differing),
        Provenance.MEASURED);
    assertTrue(differing > 0L, "a different seed produced identical terrain");

    Clustering c = clustering(a::isOccupied, STATS_RADIUS_CHUNKS);
    emit("noise clustering", String.format("target=%.2f", PRIMARY_SHARE), c);

    // Clustering is the property the mask exists for: a sprinkled mask of the same density would
    // manufacture short runs and flatter whichever curve is worse at long ones, which is exactly
    // the trap DensityTargetedOccupancyMask documents. A mean patch of one chunk is sprinkle.
    assertTrue(
        c.meanPatchChunks() > 4.0d,
        "mean unusable patch " + c.meanPatchChunks() + " chunks - terrain is sprinkled, not clustered");
    assertTrue(
        c.boundaryFraction() < 0.85d,
        "boundary fraction " + c.boundaryFraction() + " - almost every usable chunk is on an edge");
  }

  @Test
  @DisplayName("real save pass/fail via the shipped column probe, drawn beside the mock")
  void realSavePassFailDrawnBesideMock() {
    Path root = Path.of(System.getProperty(SAVE_ROOT_PROPERTY, DEFAULT_SAVE_ROOT));
    List<Path> candidates = RealWorldVerdictMask.discoverRegionDirectories(root, SAVE_SEARCH_DEPTH);
    Assumptions.assumeTrue(
        !candidates.isEmpty(),
        "no region directory found under " + root + "; set -D" + SAVE_ROOT_PROPERTY);

    Path regionDir = candidates.get(0);
    long start = System.nanoTime();
    RealWorldVerdictMask real =
        RealWorldVerdictMask.load(
            regionDir, RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, MAX_REGION_FILES);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    Assumptions.assumeTrue(real.classifiedChunks() > 0L, "no chunks classified in " + regionDir);

    REPORT.add("real save", "source", "region directory", regionDir.toString(), Provenance.MEASURED);
    REPORT.add(
        "real save",
        "source",
        "region files decoded",
        String.valueOf(real.regionFilesRead()),
        Provenance.MEASURED);
    REPORT.add(
        "real save", "source", "chunks classified", String.valueOf(real.classifiedChunks()), Provenance.MEASURED);
    REPORT.add("real save", "source", "decode wall time (ms)", String.valueOf(elapsedMs), Provenance.MEASURED);
    REPORT.add(
        "real save", "source", "decode failures", String.valueOf(real.decodeFailures()), Provenance.MEASURED);
    for (RealWorldVerdictMask.Cell cell : RealWorldVerdictMask.Cell.values()) {
      REPORT.add(
          "real save composition",
          cell.name().toLowerCase(java.util.Locale.ROOT),
          "share of classified chunks",
          (double) real.count(cell) / real.classifiedChunks(),
          Provenance.MEASURED);
    }
    REPORT.add(
        "real save", "source", "usable share of generated chunks", real.usableShareOfGenerated(), Provenance.MEASURED);

    assertTrue(
        real.count(RealWorldVerdictMask.Cell.USABLE) > 0L,
        "the shipped probe found no usable chunk in " + regionDir + " - the compute path is wrong");

    // Statistics over the largest fully swept origin-centred window, so ungenerated ground outside
    // the save's footprint is not counted as unusable terrain - that was the fault that produced
    // ADR-084's voided density rows.
    int realRadius = Math.min(STATS_RADIUS_CHUNKS, real.inscribedRadius());
    if (realRadius >= 32) {
      Clustering realStats = clustering(real::isOccupied, realRadius);
      emit("real clustering", "radius=" + realRadius + "ch", realStats);

      NoiseWorldMask matched = new NoiseWorldMask(SEED, realRadius, realStats.usableShare());
      Clustering mockStats = clustering(matched::isOccupied, realRadius);
      emit("mock clustering (density-matched)", "radius=" + realRadius + "ch", mockStats);

      // Ratios rather than differences: these are the multipliers a run count would move by if the
      // mock were substituted for the save, which is the decision the comparison informs.
      REPORT.add(
          "fidelity",
          "mock / real",
          "boundary fraction ratio",
          ratio(mockStats.boundaryFraction(), realStats.boundaryFraction()),
          Provenance.DERIVED);
      REPORT.add(
          "fidelity",
          "mock / real",
          "mean patch size ratio",
          ratio(mockStats.meanPatchChunks(), realStats.meanPatchChunks()),
          Provenance.DERIVED);
      REPORT.add(
          "fidelity",
          "mock / real",
          "mixed region fraction ratio",
          ratio(mockStats.mixedRegionFraction(), realStats.mixedRegionFraction()),
          Provenance.DERIVED);
    } else {
      REPORT.note(
          "No origin-centred window of 32 chunks or more is fully swept in "
              + regionDir
              + ", so clustering statistics were skipped rather than measured over ground the save "
              + "does not contain.");
    }

    drawRasters(real);
  }

  /** Draws both worlds at the same scale, same colouring, with the figures on the image. */
  private void drawRasters(RealWorldVerdictMask real) {
    int cells = IMAGE_RADIUS_CHUNKS * 2;
    CurveImage image = new CurveImage("mock-world", IMAGE_SCALE);

    NoiseWorldMask mock = new NoiseWorldMask(SEED, IMAGE_RADIUS_CHUNKS, PRIMARY_SHARE);
    Clustering mockStats = clustering(mock::isOccupied, IMAGE_RADIUS_CHUNKS);
    CurveImage.Caption mockCaption =
        new CurveImage.Caption("Mock world - seeded noise terrain, pass/fail per chunk")
            .line(
                String.format(
                    "seed %d, target usable %.2f, realised %.3f over this window",
                    SEED, PRIMARY_SHARE, mockStats.usableShare()))
            .line(
                String.format(
                    "boundary fraction %.3f, mean unusable patch %.1f chunks, mixed 32-chunk cells %.3f",
                    mockStats.boundaryFraction(),
                    mockStats.meanPatchChunks(),
                    mockStats.mixedRegionFraction()))
            .line(
                "one pixel block = one chunk at "
                    + IMAGE_SCALE
                    + "px; window "
                    + cells
                    + "x"
                    + cells
                    + " chunks ("
                    + (cells * 16 / 1000.0d)
                    + " km edge), origin at centre")
            .line("colour = which feature the chunk belongs to; green is the only usable state")
            .swatch(ARGB_USABLE, "usable ground - a viable starting position")
            .swatch(ARGB_OCEAN, "ocean - low-frequency elevation below sea level")
            .swatch(ARGB_RIVER, "river - narrow band on one elevation contour")
            .swatch(ARGB_POND, "pond / pool - small water body, including one-chunk speckle")
            .swatch(ARGB_LAVA, "lava pool - one chunk, rare, inland only")
            .swatch(ARGB_ISOLATED, "stranded island - safe ground with no safe neighbour; unusable");
    image.draw(
        "mock-terrain",
        cells,
        (cx, cz) ->
            switch (mock.classify(cx - IMAGE_RADIUS_CHUNKS, cz - IMAGE_RADIUS_CHUNKS)) {
              case LAND -> ARGB_USABLE;
              case OCEAN -> ARGB_OCEAN;
              case RIVER -> ARGB_RIVER;
              case POND -> ARGB_POND;
              case LAVA -> ARGB_LAVA;
              case ISOLATED -> ARGB_ISOLATED;
            },
        mockCaption);

    CurveImage.Caption realCaption =
        new CurveImage.Caption("Real save - shipped Anvil column probe, pass/fail per chunk")
            .line(
                String.format(
                    "%d region files decoded, %d chunks classified, usable share of generated %.3f",
                    real.regionFilesRead(), real.classifiedChunks(), real.usableShareOfGenerated()))
            .line(
                "rule: surface block and the two above it at the chunk centre column, against the "
                    + "shipped unsafeBlocks list")
            .line(
                "one pixel block = one chunk at "
                    + IMAGE_SCALE
                    + "px; window "
                    + cells
                    + "x"
                    + cells
                    + " chunks, origin at centre")
            .line("dark grey is ground the save does not contain, not ground that failed")
            .swatch(ARGB_USABLE, "usable - probe found no unsafe block in the three blocks")
            .swatch(ARGB_OCEAN, "water at the surface (water, bubble column, kelp, seagrass)")
            .swatch(ARGB_LAVA, "lava or magma at the surface")
            .swatch(ARGB_OTHER, "some other configured unsafe block")
            .swatch(ARGB_UNGENERATED, "not generated / not decodable - excluded from every share")
            .swatch(ARGB_ISOLATED, "stranded island - safe ground with no safe neighbour; unusable");
    image.draw(
        "real-terrain",
        cells,
        (cx, cz) ->
            switch (real.filteredCellAt(cx - IMAGE_RADIUS_CHUNKS, cz - IMAGE_RADIUS_CHUNKS)) {
              case USABLE -> ARGB_USABLE;
              case WATER -> ARGB_OCEAN;
              case LAVA -> ARGB_LAVA;
              case OTHER_UNSAFE -> ARGB_OTHER;
              case UNGENERATED -> ARGB_UNGENERATED;
              case ISOLATED -> ARGB_ISOLATED;
            },
        realCaption);
  }

  /**
   * Matches the noise generator to <b>every</b> real save on this machine, one at a time.
   *
   * <p>The point is durability of the evidence rather than a better mock. The terrain knobs were
   * tuned by hand against the first save examined, so "the mock resembles a real world" currently
   * means "the mock resembles <i>that</i> world". Two things follow: a second save is the only way
   * to tell a property of real terrain from a property of one seed of one generator, and a fitted
   * parameter set is what lets the same comparison be re-run after the source saves are wiped -
   * the numbers travel, the gigabytes do not.
   *
   * <p>Search is <b>coordinate descent</b>, two rounds over five knobs, not a grid. A full grid of
   * this space is 162 candidates and each candidate pays a 24-step density calibration over the
   * window, which is minutes per save for a search whose axes are close to separable. Coordinate
   * descent is not guaranteed to find the global optimum and is not claimed to; what it produces is
   * a parameter set that is <i>measurably better than the hand-tuned default on this save</i>, and
   * the default's own score is reported beside it so the improvement is legible.
   *
   * <p>Fidelity is the sum of absolute log ratios over the three shape statistics - boundary
   * fraction, mean unusable patch size, region-aligned mixed fraction. Log ratios because the
   * quantities have different units and a 2x error should cost the same whichever direction it
   * points; usable share is excluded because it is calibrated rather than fitted, so including it
   * would score the calibration instead of the shape.
   */
  @Test
  @DisplayName("noise parameters fitted per real save, so the comparison survives the data")
  void noiseParametersMatchedToEachRealSave() {
    Path root = Path.of(System.getProperty(SAVE_ROOT_PROPERTY, DEFAULT_SAVE_ROOT));
    List<Path> candidates = RealWorldVerdictMask.discoverRegionDirectories(root, SAVE_SEARCH_DEPTH);
    Assumptions.assumeTrue(!candidates.isEmpty(), "no region directory found under " + root);

    int matched = 0;
    for (int i = 0; i < candidates.size() && matched < MAX_SAVES_MATCHED; i++) {
      Path dir = candidates.get(i);
      RealWorldVerdictMask real =
          RealWorldVerdictMask.load(dir, RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, MAX_REGION_FILES);
      int radius = Math.min(MATCH_RADIUS_CHUNKS, real.inscribedRadius());
      if (radius < 64) continue;
      matched++;
      String save = "save " + matched;

      Clustering realStats = clustering(real::isOccupied, radius);
      REPORT.add("save fit " + save, "source", "region directory", dir.toString(), Provenance.MEASURED);
      REPORT.add(
          "save fit " + save, "source", "window radius (chunks)", String.valueOf(radius), Provenance.MEASURED);
      emit("save fit " + save + " real", "radius=" + radius + "ch", realStats);

      NoiseWorldMask.Params best = NoiseWorldMask.Params.defaults();
      double bestError = fidelityError(best, radius, realStats);
      double defaultError = bestError;

      for (int round = 0; round < 2; round++) {
        for (int octaves : new int[] {6, 8, 10}) {
          best =
              betterOf(
                  best,
                  new NoiseWorldMask.Params(
                      octaves,
                      best.oceanPersistence(),
                      best.riverHalfWidth(),
                      best.pondThreshold(),
                      best.speckleRate(),
                      best.tempWavelength(),
                      best.tempOctaves(),
                      best.humidWavelength(),
                      best.humidOctaves()),
                  radius,
                  realStats);
        }
        for (double persistence : new double[] {0.60d, 0.70d, 0.80d}) {
          best =
              betterOf(
                  best,
                  new NoiseWorldMask.Params(
                      best.oceanOctaves(),
                      persistence,
                      best.riverHalfWidth(),
                      best.pondThreshold(),
                      best.speckleRate(),
                      best.tempWavelength(),
                      best.tempOctaves(),
                      best.humidWavelength(),
                      best.humidOctaves()),
                  radius,
                  realStats);
        }
        for (double riverHalf : new double[] {0.006d, 0.012d}) {
          best =
              betterOf(
                  best,
                  new NoiseWorldMask.Params(
                      best.oceanOctaves(),
                      best.oceanPersistence(),
                      riverHalf,
                      best.pondThreshold(),
                      best.speckleRate(),
                      best.tempWavelength(),
                      best.tempOctaves(),
                      best.humidWavelength(),
                      best.humidOctaves()),
                  radius,
                  realStats);
        }
        for (double pond : new double[] {0.38d, 0.42d, 0.46d}) {
          best =
              betterOf(
                  best,
                  new NoiseWorldMask.Params(
                      best.oceanOctaves(),
                      best.oceanPersistence(),
                      best.riverHalfWidth(),
                      pond,
                      best.speckleRate(),
                      best.tempWavelength(),
                      best.tempOctaves(),
                      best.humidWavelength(),
                      best.humidOctaves()),
                  radius,
                  realStats);
        }
        for (double speckle : new double[] {0.004d, 0.008d, 0.015d}) {
          best =
              betterOf(
                  best,
                  new NoiseWorldMask.Params(
                      best.oceanOctaves(),
                      best.oceanPersistence(),
                      best.riverHalfWidth(),
                      best.pondThreshold(),
                      speckle,
                      best.tempWavelength(),
                      best.tempOctaves(),
                      best.humidWavelength(),
                      best.humidOctaves()),
                  radius,
                  realStats);
        }
        bestError = fidelityError(best, radius, realStats);
      }

      NoiseWorldMask fitted =
          new NoiseWorldMask(SEED, radius, realStats.usableShare(), best);
      Clustering fittedStats = clustering(fitted::isOccupied, radius);
      emit("save fit " + save + " mock (fitted)", best.toString(), fittedStats);
      REPORT.add("save fit " + save, "fit", "fitted parameters", best.toString(), Provenance.MEASURED);
      REPORT.add("save fit " + save, "fit", "fidelity error, hand-tuned default", defaultError, Provenance.MEASURED);
      REPORT.add("save fit " + save, "fit", "fidelity error, fitted", bestError, Provenance.MEASURED);
      REPORT.add(
          "save fit " + save,
          "fit",
          "boundary fraction ratio",
          ratio(fittedStats.boundaryFraction(), realStats.boundaryFraction()),
          Provenance.DERIVED);
      REPORT.add(
          "save fit " + save,
          "fit",
          "mean patch size ratio",
          ratio(fittedStats.meanPatchChunks(), realStats.meanPatchChunks()),
          Provenance.DERIVED);
      REPORT.add(
          "save fit " + save,
          "fit",
          "mixed region fraction ratio",
          ratio(fittedStats.mixedRegionFraction(), realStats.mixedRegionFraction()),
          Provenance.DERIVED);

      // Coordinate descent starts from the default, so it can never end worse than the default.
      // Asserting that is asserting the search is wired up, not that the fit is good.
      assertTrue(
          bestError <= defaultError + 1.0e-9d,
          "search returned a worse parameter set than its own starting point on " + dir);
    }

    Assumptions.assumeTrue(matched > 0, "no real save had a fully swept 64-chunk window");
    REPORT.note(
        "Parameters are fitted per save by coordinate descent from the hand-tuned default, scored "
            + "by the summed absolute log ratio of boundary fraction, mean unusable patch size and "
            + "region-aligned mixed fraction. Usable share is excluded from the score because it is "
            + "calibrated rather than fitted. Coordinate descent is not a global search and the "
            + "default's score is reported beside the fitted one so the improvement is legible.");
  }

  private NoiseWorldMask.Params betterOf(
      NoiseWorldMask.Params incumbent,
      NoiseWorldMask.Params challenger,
      int radius,
      Clustering realStats) {
    if (challenger.equals(incumbent)) return incumbent;
    return fidelityError(challenger, radius, realStats)
            < fidelityError(incumbent, radius, realStats)
        ? challenger
        : incumbent;
  }

  /** Summed absolute log ratio over the three shape statistics; lower is a closer match. */
  private double fidelityError(NoiseWorldMask.Params params, int radius, Clustering realStats) {
    NoiseWorldMask mock = new NoiseWorldMask(SEED, radius, realStats.usableShare(), params);
    Clustering stats = clustering(mock::isOccupied, radius);
    return logError(stats.boundaryFraction(), realStats.boundaryFraction())
        + logError(stats.meanPatchChunks(), realStats.meanPatchChunks())
        + logError(stats.mixedRegionFraction(), realStats.mixedRegionFraction());
  }

  private static double logError(double mock, double real) {
    if (mock <= 0.0d || real <= 0.0d) return 10.0d;
    return Math.abs(Math.log(mock / real));
  }

  private static void emit(String section, String subject, Clustering c) {
    REPORT.add(section, subject, "usable share", c.usableShare(), Provenance.MEASURED);
    REPORT.add(section, subject, "boundary fraction", c.boundaryFraction(), Provenance.MEASURED);
    REPORT.add(section, subject, "mean unusable patch (chunks)", c.meanPatchChunks(), Provenance.MEASURED);
    REPORT.add(section, subject, "largest unusable patch (chunks)", String.valueOf(c.maxPatchChunks()), Provenance.MEASURED);
    REPORT.add(section, subject, "unusable patches", String.valueOf(c.patchCount()), Provenance.MEASURED);
    REPORT.add(section, subject, "mixed 32-chunk cell fraction", c.mixedRegionFraction(), Provenance.MEASURED);
  }

  private static String ratio(double mock, double real) {
    return real == 0.0d ? "n/a" : String.format("%.3f", mock / real);
  }

  /**
   * Measures the four clustering statistics over an origin-centred window.
   *
   * <p>Patches are four-connected components of unusable ground, found by an explicit-stack flood
   * fill rather than recursion: a single ocean can span the whole window and a recursive fill would
   * overflow the stack on exactly the case the statistic exists to describe.
   */
  private static Clustering clustering(Occupancy occupancy, int radiusChunks) {
    int edge = radiusChunks * 2;
    boolean[] usable = new boolean[edge * edge];
    long usableCount = 0L;
    for (int z = 0; z < edge; z++) {
      for (int x = 0; x < edge; x++) {
        boolean u = occupancy.usable(x - radiusChunks, z - radiusChunks);
        usable[z * edge + x] = u;
        if (u) usableCount++;
      }
    }

    long boundary = 0L;
    for (int z = 0; z < edge; z++) {
      for (int x = 0; x < edge; x++) {
        if (!usable[z * edge + x]) continue;
        // Window edges are not counted as boundary: whether ground continues outside is unknown,
        // and treating the frame as unusable would inflate the statistic by the window's perimeter.
        if (x == 0 || z == 0 || x == edge - 1 || z == edge - 1) continue;
        if (!usable[z * edge + x - 1]
            || !usable[z * edge + x + 1]
            || !usable[(z - 1) * edge + x]
            || !usable[(z + 1) * edge + x]) {
          boundary++;
        }
      }
    }

    boolean[] seen = new boolean[edge * edge];
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    long patches = 0L;
    long patchCells = 0L;
    long maxPatch = 0L;
    for (int start = 0; start < usable.length; start++) {
      if (usable[start] || seen[start]) continue;
      patches++;
      long size = 0L;
      stack.push(start);
      seen[start] = true;
      while (!stack.isEmpty()) {
        int index = stack.pop();
        size++;
        int x = index % edge;
        int z = index / edge;
        if (x > 0) push(stack, seen, usable, index - 1);
        if (x < edge - 1) push(stack, seen, usable, index + 1);
        if (z > 0) push(stack, seen, usable, index - edge);
        if (z < edge - 1) push(stack, seen, usable, index + edge);
      }
      patchCells += size;
      maxPatch = Math.max(maxPatch, size);
    }

    long mixed = 0L;
    long regions = 0L;
    for (int rz = 0; rz < edge; rz += REGION_CHUNKS) {
      for (int rx = 0; rx < edge; rx += REGION_CHUNKS) {
        regions++;
        boolean anyUsable = false;
        boolean anyUnusable = false;
        for (int z = rz; z < Math.min(edge, rz + REGION_CHUNKS); z++) {
          for (int x = rx; x < Math.min(edge, rx + REGION_CHUNKS); x++) {
            if (usable[z * edge + x]) {
              anyUsable = true;
            } else {
              anyUnusable = true;
            }
          }
        }
        if (anyUsable && anyUnusable) mixed++;
      }
    }

    long total = (long) edge * edge;
    return new Clustering(
        (double) usableCount / total,
        usableCount == 0L ? 0.0d : (double) boundary / usableCount,
        patches == 0L ? 0.0d : (double) patchCells / patches,
        maxPatch,
        patches,
        regions == 0L ? 0.0d : (double) mixed / regions);
  }

  private static void push(
      ArrayDeque<Integer> stack, boolean[] seen, boolean[] usable, int index) {
    if (seen[index] || usable[index]) return;
    seen[index] = true;
    stack.push(index);
  }
}
