package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Learned-state footprint of both curves at a <b>100 km border</b>, on terrain that is roughly 45%
 * usable.
 *
 * <p>Two things distinguish this suite from the small-radius rows in ADR-085.
 *
 * <p><b>Scale.</b> A 100 km border is a 6250-chunk radius: about 156 million addressed chunks, more
 * than half of them unusable. That mark set cannot be pushed through {@code addBadLocation} in a
 * test JVM, so the table is counted by {@link KeySpaceRunEncoder} - one ascending pass over the key
 * space, histogramming the gaps between consecutive bad keys, which reconstructs the coalesced table
 * at every {@code spatialResolution} at once in constant memory. That encoder is arithmetic rather
 * than shipped code, so {@link #encoderAgreesWithShippedRebuild()} pins it to a real {@code
 * flushAndRebuild} at radii where the shape can actually be built. Every border-scale figure here
 * rests on that check.
 *
 * <p><b>Density.</b> Every earlier row ran on the real save tiled outward, which is usable on about
 * a quarter of its chunks. Run count depends on the boundary length between usable and unusable
 * ground, so density is not a neutral parameter - it has to be stated and varied rather than
 * inherited. {@link DensityTargetedOccupancyMask} raises the usable share to a target by promoting
 * clustered blocks, and the realised share is counted over the addressed domain rather than assumed.
 *
 * <p>The comparison itself is unchanged and stays narrow: both shapes address one chunk per key,
 * see identical occupancy, and inherit identical machinery. Only the bijection differs.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("border-scale footprint: both curves at a 100 km radius on ~45% usable terrain")
public class BorderScaleFootprintBenchmarkTest {

  private static final long SEED = 20260906L;

  /** Usable share aimed for. Stated as a target; the realised share is counted and reported. */
  private static final double TARGET_USABLE = 0.45d;

  /** Accepted band around the target, per the request: 45% give or take 5 points. */
  private static final double USABLE_TOLERANCE = 0.05d;

  /**
   * A 100 km border, in chunks, rounded to a whole number of points.
   *
   * <p>100 000 blocks is 6250 chunks; 6272 is the nearest multiple of the 32-chunk point above it,
   * i.e. 100.35 km. Rounding matters because the hybrid's coarse grid covers whole points only, so
   * an arbitrary radius leaves it a ragged outer shell the spiral does not have.
   */
  private static final int BORDER_100KM_CHUNKS = 6_272;

  /**
   * Radii swept, in chunks: 16 km, 64 km and 100 km of border.
   *
   * <p>The two smaller radii exist so the border row is read as the end of a trend rather than as a
   * single point, since one point cannot distinguish a scaling property from an accident. All are
   * multiples of the point edge.
   */
  private static final int[] RADII_CHUNKS = {512, 2_048, BORDER_100KM_CHUNKS};

  /** Radii at which the encoder is checked against a real rebuild. Small enough to build. */
  private static final int[] VALIDATION_RADII = {128, 256};

  /** Chunks per point edge for the hybrid. 32 is one Anvil region file. */
  private static final int POINT_CHUNKS = 32;

  /**
   * Coalescing gaps swept, in 1D key units - the shipped {@code spatialResolution} knob.
   *
   * <p>{@code 1} is retained only as a reference row. It is not an operating point: a run merges at
   * gap {@code resolution + 1}, so {@code 1} is already lossy, and a single usable chunk isolated
   * between unusable ones is not a viable starting position anyway - a one-chunk island or a nether
   * pocket is not somewhere a player should land. The floor for any decision is {@link
   * #MIN_RESOLUTION}.
   */
  private static final long[] RESOLUTIONS = {
    1L, 2L, 4L, 8L, 16L, 64L, 256L, 1_024L, 4_096L, 16_384L, 65_536L
  };

  /**
   * Smallest {@code spatialResolution} treated as admissible.
   *
   * <p>Below this the table is paying to retain ground no teleport should target, so a curve that
   * happens to hold fewer entries at gap 1 is winning on a setting nobody runs.
   */
  private static final long MIN_RESOLUTION = 2L;

  /** Accuracy budgets at which the two curves are compared on equal terms. */
  private static final double[] LOSS_CAPS = {0.05d, 0.10d, 0.20d};

  /** Bytes per entry in the shipped table: a {@code long} key and a {@code long} width. */
  private static final int SHIPPED_BYTES_PER_RUN = 16;

  private static final SimulationReport REPORT = new SimulationReport();

  private static DensityTargetedOccupancyMask mask;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    if (!WorldOccupancyMask.available()) return;
    Path dir = WorldOccupancyMask.resolveDirectory();
    TiledOccupancyMask tiled = new TiledOccupancyMask(WorldOccupancyMask.load(dir));
    mask = new DensityTargetedOccupancyMask(tiled, TARGET_USABLE, SEED);
    REPORT.add("world", "source save", "usable share", mask.baseUsableShare(), Provenance.MEASURED);
    REPORT.add("world", "adjusted", "block promotion rate", mask.promotionRate(), Provenance.DERIVED);
    REPORT.add(
        "world", "adjusted", "expected usable share", mask.expectedUsableShare(), Provenance.DERIVED);
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Border-scale rows are counted by a gap histogram over one ascending pass of the key space, "
            + "not by building the table: 156 million addressed chunks at a 100 km border cannot be "
            + "marked through addBadLocation in a test JVM. The histogram reproduces the shipped "
            + "merge test exactly - nextKey <= curEnd + resolution over unit-width runs - and the "
            + "equivalence is asserted against a real flushAndRebuild at radii 128 and 256.");
    REPORT.note(
        "Usable share is an input here rather than whatever the source save happens to be. Run "
            + "count follows the boundary length between usable and unusable ground, so density "
            + "moves the answer and has to be stated. Added ground is promoted in blocks of 8 "
            + "chunks so it stays clustered; promoting individual chunks would manufacture short "
            + "runs and flatter whichever curve handles long ones worse.");
    REPORT.note(
        "Bytes are reported at the shipped width of 16 per entry. At chunk precision the key space "
            + "of a 100 km border fits an int, so half of every entry pays for range that cannot "
            + "occur - a free halving available to either curve and therefore excluded from the "
            + "comparison between them.");
    REPORT.note(
        "spatialResolution 1 is reported but is not an operating point. A run merges at gap "
            + "resolution + 1, so 1 already bridges a lone usable chunk between two unusable ones, "
            + "and such a chunk is not a viable starting position - a one-chunk island or a nether "
            + "pocket is not somewhere a player should land. The cap comparison therefore admits "
            + "settings of 2 and above only, and the finer settings 2, 4 and 8 are swept so the "
            + "cost of that floor is a measured row rather than an interpolation between 1 and 16.");
    REPORT.write("border-scale-footprint");
  }

  private static KeySpaceRunEncoder.Occupancy occupancy() {
    return (cx, cz) -> mask.isOccupied(cx, cz);
  }

  private static Square plainSpiral(int radiusChunks) {
    Square s = new Square("BORDER_SPIRAL");
    s.set(GenericMemoryShapeParams.radius, (long) radiusChunks);
    s.set(GenericMemoryShapeParams.centerRadius, 0L);
    s.set(GenericMemoryShapeParams.centerX, 0L);
    s.set(GenericMemoryShapeParams.centerZ, 0L);
    return s;
  }

  private static KeySpaceRunEncoder encode(MemoryShape<?> shape, int windowChunks) {
    KeySpaceRunEncoder encoder = new KeySpaceRunEncoder();
    encoder.encode(shape, windowChunks, occupancy());
    return encoder;
  }

  /**
   * Chunk window both curves address in full, given a shape radius that is a multiple of the point
   * edge.
   *
   * <p>The hybrid's coarse grid holds points at ring index up to {@code radius / P - 1}, so its
   * addressed band is {@code [-radius + P, radius - 1]} - shifted, not symmetric. Pulling the window
   * in by one point on both sides is the largest square both curves cover, and a footprint
   * comparison over anything else compares domains rather than curves.
   */
  private static int commonWindow(int radiusChunks) {
    return radiusChunks - POINT_CHUNKS;
  }

  /**
   * Smallest table among the admissible settings whose loss stays inside the cap.
   *
   * <p>Settings below {@link #MIN_RESOLUTION} are excluded, so neither curve is credited for a
   * table it only holds at a precision that is not an operating point.
   */
  private static long smallestWithin(KeySpaceRunEncoder encoder, double cap) {
    long best = Long.MAX_VALUE;
    for (long resolution : RESOLUTIONS) {
      if (resolution < MIN_RESOLUTION) continue;
      if (encoder.lossAt(resolution) > cap) continue;
      best = Math.min(best, encoder.runsAt(resolution));
    }
    return best;
  }

  // -------------------------------------------------------------------------------------
  // 1. the terrain is what it claims to be
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("adjusted terrain lands inside the requested usable band")
  public void terrainIsOnTarget() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    // Counted exhaustively over a window large enough to cover many tile periods, so the figure is
    // the realised share of ground and not the arithmetic that chose the promotion rate.
    int radius = 2_000;
    long usable = 0L;
    long total = 0L;
    for (int cx = -radius; cx < radius; cx++) {
      for (int cz = -radius; cz < radius; cz++) {
        total++;
        if (mask.isOccupied(cx, cz)) usable++;
      }
    }
    double share = usable / (double) total;
    REPORT.add("terrain", "r=" + radius + "ch", "realised usable share", share, Provenance.MEASURED);
    REPORT.add(
        "terrain",
        "r=" + radius + "ch",
        "chunks counted",
        String.valueOf(total),
        Provenance.MEASURED);

    assertTrue(
        Math.abs(share - TARGET_USABLE) <= USABLE_TOLERANCE,
        "usable share " + share + " outside " + TARGET_USABLE + " +/- " + USABLE_TOLERANCE);
  }

  // -------------------------------------------------------------------------------------
  // 2. the fast counter is the slow counter
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("gap-histogram encoder reproduces a real flushAndRebuild")
  public void encoderAgreesWithShippedRebuild() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int radius : VALIDATION_RADII) {
      for (boolean hybrid : new boolean[] {false, true}) {
        String curve = hybrid ? "hybrid" : "spiral";
        int window = commonWindow(radius);
        KeySpaceRunEncoder encoder =
            encode(
                hybrid ? new SpiralHilbertSquare(radius, POINT_CHUNKS, true) : plainSpiral(radius),
                window);

        for (long resolution : new long[] {1L, 64L, 256L}) {
          MemoryShape<?> shape =
              hybrid ? new SpiralHilbertSquare(radius, POINT_CHUNKS, true) : plainSpiral(radius);
          shape.setSpatialResolution(resolution);
          // Marks are confined to the same window the encoder counts, so the two are comparing the
          // same table and not the same code path over different ground.
          for (int cx = -window; cx < window; cx++) {
            for (int cz = -window; cz < window; cz++) {
              if (mask.isOccupied(cx, cz)) continue;
              long key = shape.xzToLocation(cx, cz);
              if (key < 0L) continue;
              shape.addBadLocation(key, FailTypes.biome);
            }
          }
          shape.flushAndRebuild(resolution);

          long[] bad = shape.badKeysSnapshot();
          long[] probation = shape.probationKeysSnapshot();
          long builtRuns = (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
          long countedRuns = encoder.runsAt(resolution);

          long good = 0L;
          long lost = 0L;
          for (int cx = -window; cx < window; cx++) {
            for (int cz = -window; cz < window; cz++) {
              if (!mask.isOccupied(cx, cz)) continue;
              good++;
              if (shape.isKnownBad(cx, cz)) lost++;
            }
          }
          double builtLoss = good == 0L ? 0.0d : lost / (double) good;
          double countedLoss = encoder.lossAt(resolution);

          String subject = curve + " r=" + radius + "ch res=" + resolution;
          REPORT.add(subject, "runs", "built", String.valueOf(builtRuns), Provenance.MEASURED);
          REPORT.add(subject, "runs", "counted", String.valueOf(countedRuns), Provenance.MEASURED);
          REPORT.add(subject, "loss", "built", builtLoss, Provenance.MEASURED);
          REPORT.add(subject, "loss", "counted", countedLoss, Provenance.MEASURED);

          assertEquals(builtRuns, countedRuns, "run count disagrees for " + subject);
          assertTrue(
              Math.abs(builtLoss - countedLoss) <= 1e-6d,
              "loss disagrees for " + subject + ": " + builtLoss + " vs " + countedLoss);
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------
  // 3. footprint at border scale
  // -------------------------------------------------------------------------------------

  @Test
  @DisplayName("footprint before and after, out to a 100 km border")
  public void footprintAtBorderScale() {
    Assumptions.assumeTrue(mask != null, "no world data available");

    for (int radius : RADII_CHUNKS) {
      int window = commonWindow(radius);
      KeySpaceRunEncoder spiral = encode(plainSpiral(radius), window);
      KeySpaceRunEncoder hybrid =
          encode(new SpiralHilbertSquare(radius, POINT_CHUNKS, true), window);

      String domain = "r=" + window + "ch (" + ((window * 16L) / 1000L) + " km)";
      REPORT.add(
          domain,
          "domain",
          "addressed chunks",
          String.valueOf(spiral.addressedCells()),
          Provenance.MEASURED);
      REPORT.add(domain, "domain", "realised usable share", spiral.usableShare(), Provenance.MEASURED);
      REPORT.add(
          domain, "domain", "bad chunks", String.valueOf(spiral.badKeys()), Provenance.MEASURED);

      // The hybrid addresses the same chunks through a larger key space, since its coarse point
      // grid is rounded up to whole points. Asserting the addressed sets match is what makes the
      // byte comparison a comparison of curves rather than of domains.
      assertEquals(
          spiral.addressedCells(),
          hybrid.addressedCells(),
          "curves addressed different chunk counts at " + domain);
      assertEquals(spiral.badKeys(), hybrid.badKeys(), "curves saw different marks at " + domain);

      for (long resolution : RESOLUTIONS) {
        emit(domain, "spiral", resolution, spiral);
        emit(domain, "hybrid", resolution, hybrid);
      }

      for (double cap : LOSS_CAPS) {
        long spiralRuns = smallestWithin(spiral, cap);
        long hybridRuns = smallestWithin(hybrid, cap);
        if (spiralRuns == Long.MAX_VALUE || hybridRuns == Long.MAX_VALUE) {
          REPORT.add(
              domain,
              "cap " + cap,
              "no setting inside cap",
              (spiralRuns == Long.MAX_VALUE ? "spiral" : "") + (hybridRuns == Long.MAX_VALUE ? " hybrid" : ""),
              Provenance.MEASURED);
          continue;
        }
        REPORT.add(
            domain,
            "cap " + cap + " spiral",
            "bytes",
            String.valueOf(spiralRuns * SHIPPED_BYTES_PER_RUN),
            Provenance.DERIVED);
        REPORT.add(
            domain,
            "cap " + cap + " hybrid",
            "bytes",
            String.valueOf(hybridRuns * SHIPPED_BYTES_PER_RUN),
            Provenance.DERIVED);
        REPORT.add(
            domain,
            "cap " + cap,
            "hybrid bytes / spiral bytes",
            hybridRuns / (double) spiralRuns,
            Provenance.DERIVED);
      }

      // Load-bearing claim, at the largest domain only: at a stated accuracy budget the new curve's
      // table is smaller. It is asserted rather than reported because it is the reason to change
      // the curve at all, and because the earlier levers all failed exactly here.
      if (radius == BORDER_100KM_CHUNKS) {
        long spiralRuns = smallestWithin(spiral, 0.20d);
        long hybridRuns = smallestWithin(hybrid, 0.20d);
        assertTrue(
            hybridRuns < spiralRuns,
            "at a 20% accuracy budget the hybrid table is not smaller: "
                + hybridRuns
                + " against "
                + spiralRuns);
      }
    }
  }

  private static void emit(String domain, String curve, long resolution, KeySpaceRunEncoder e) {
    long runs = e.runsAt(resolution);
    REPORT.add(
        domain, curve + " res=" + resolution, "runs", String.valueOf(runs), Provenance.MEASURED);
    REPORT.add(
        domain,
        curve + " res=" + resolution,
        "table bytes",
        String.valueOf(runs * SHIPPED_BYTES_PER_RUN),
        Provenance.DERIVED);
    REPORT.add(
        domain,
        curve + " res=" + resolution,
        "usable ground discarded",
        e.lossAt(resolution),
        Provenance.MEASURED);
  }
}
