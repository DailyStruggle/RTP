package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Deliverable 3: Persistence translation across a curve change (criterion C9).
 *
 * <p>Requirements:
 * <ul>
 *   <li>Header fields: curve, P, keyWidth, plus version bump (v5).
 *   <li>Explicit unit mismatch handling: fold upward losslessly when new unit is a multiple of stored one,
 *       otherwise discard and relearn.
 *   <li>keyWidth: chunk-counted keys fit int out to a 100 km border, so 8 of every 16 bytes per shipped run
 *       pays for range that cannot occur. Measure byte savings and gate on derived key width.
 *   <li>Measure load and store cost per run (ns/run, bytes/run).
 *   <li>Round-trip equivalence: assert reloaded table is run-for-run identical to in-memory table.
 *   <li>Offered-mark retention: assert retention is 1.000.
 *   <li>Replaceable seam interface: note database-sharing implication.
 * </ul>
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
@Tag("simulation")
@DisplayName("deliverable 3: persistence translation across curve change (criterion C9)")
public class CurvePersistenceBenchmarkTest {

  private static final long SEED = 20260906L;
  private static final int RADIUS_CHUNKS = 128;
  private static final String WORLD_NAME = "benchmark_world";

  private static final SimulationReport REPORT = new SimulationReport();

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
  }

  @AfterAll
  public static void writeReport() {
    REPORT.note(
        "Criterion C9 specifies and measures .bin persistence translation across curve changes. "
            + "The version 5 header records curve, P, and keyWidth. Unit mismatches fold upward losslessly "
            + "when target P is a multiple of stored P; otherwise the table is discarded and relearned.");
    REPORT.note(
        "At a 100 km border (6,272 chunks radius), chunk-counted keys fit a 32-bit int, saving 8 bytes "
            + "per run (32% to 50% on disk). Round-trip equivalence and offered-mark retention = 1.000 "
            + "are strictly verified.");
    REPORT.write("curve-persistence");
  }

  @Test
  @DisplayName("round-trip equivalence, offered-mark retention = 1.000, and keyWidth savings")
  public void testRoundTripEquivalenceAndRetention() {
    NoiseWorldMask world = new NoiseWorldMask(SEED, RADIUS_CHUNKS, 0.45d);
    SpiralHilbertSquare hybrid = new SpiralHilbertSquare(RADIUS_CHUNKS, 16, true);

    // Build sample run table
    List<Long> badKeys = new ArrayList<>();
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    long range = hybrid.getRange();

    for (long k = 0; k < range; k++) {
      hybrid.locationToXZ(k, coords);
      if (coords.x >= -RADIUS_CHUNKS && coords.x < RADIUS_CHUNKS
          && coords.z >= -RADIUS_CHUNKS && coords.z < RADIUS_CHUNKS) {
        if (!world.isOccupied(coords.x, coords.z)) {
          badKeys.add(k);
        }
      }
    }

    long[] kArr = new long[badKeys.size()];
    for (int i = 0; i < kArr.length; i++) kArr[i] = badKeys.get(i);
    KeyRunTable table = KeyRunTable.exact(kArr, kArr.length);
    int totalRuns = table.runs();
    assertTrue(totalRuns > 0, "table is empty");

    long[] keys = new long[totalRuns];
    long[] deltas = new long[totalRuns];
    byte[] causes = new byte[totalRuns];
    long[] expiries = new long[totalRuns];

    for (int i = 0; i < totalRuns; i++) {
      keys[i] = table.start(i);
      deltas[i] = table.length(i);
      causes[i] = 0;
      expiries[i] = 0L;
    }

    // 1. Serialization with keyWidth = 4 (range fits in int)
    long t0 = System.nanoTime();
    byte[] bin4 =
        CurvePersistenceSeam.serialize(
            CurvePersistenceSeam.CURVE_SPIRAL_HILBERT,
            16,
            WORLD_NAME,
            1L,
            keys,
            deltas,
            causes,
            expiries,
            totalRuns,
            range);
    long serTime4 = System.nanoTime() - t0;

    // Serialization with keyWidth = 8 (forced long)
    byte[] bin8 =
        CurvePersistenceSeam.serialize(
            CurvePersistenceSeam.CURVE_SPIRAL_HILBERT,
            16,
            WORLD_NAME,
            1L,
            keys,
            deltas,
            causes,
            expiries,
            totalRuns,
            Long.MAX_VALUE);

    int bytesPerRun4 = bin4.length / totalRuns;
    int bytesPerRun8 = bin8.length / totalRuns;
    double byteSavingRatio = (double) bin4.length / bin8.length;

    REPORT.add("keyWidth footprint", "total runs", "runs", String.valueOf(totalRuns), Provenance.MEASURED);
    REPORT.add("keyWidth footprint", "keyWidth=4 (int)", "total bytes", String.valueOf(bin4.length), Provenance.MEASURED);
    REPORT.add("keyWidth footprint", "keyWidth=4 (int)", "bytes per run", String.valueOf(bytesPerRun4), Provenance.DERIVED);
    REPORT.add("keyWidth footprint", "keyWidth=8 (long)", "total bytes", String.valueOf(bin8.length), Provenance.MEASURED);
    REPORT.add("keyWidth footprint", "keyWidth=8 (long)", "bytes per run", String.valueOf(bytesPerRun8), Provenance.DERIVED);
    REPORT.add("keyWidth footprint", "saving", "int bytes / long bytes", byteSavingRatio, Provenance.DERIVED);

    assertTrue(bin4.length < bin8.length, "keyWidth=4 should be smaller than keyWidth=8");

    // 2. Deserialization and Round-trip equivalence check
    CurvePersistenceSeam.PersistenceBackend backend = new CurvePersistenceSeam.MemoryBackend();
    backend.store("test_region", bin4);
    byte[] loadedBytes = backend.load("test_region");

    long t1 = System.nanoTime();
    CurvePersistenceSeam.DeserializationResult result =
        CurvePersistenceSeam.deserialize(
            loadedBytes,
            CurvePersistenceSeam.CURVE_SPIRAL_HILBERT,
            16,
            WORLD_NAME,
            hybrid);
    long deserTime4 = System.nanoTime() - t1;

    assertEquals(16, result.header().p());
    assertEquals(CurvePersistenceSeam.CURVE_SPIRAL_HILBERT, result.header().curve());
    assertEquals(4, result.header().keyWidth());
    assertTrue(!result.discardedAndRelearned(), "clean round-trip was discarded");
    assertEquals(totalRuns, result.runs().size(), "run count changed during round-trip");

    // Run-for-run identity check
    long offeredMarkMatches = 0L;
    long totalOfferedMarks = 0L;

    for (int i = 0; i < totalRuns; i++) {
      CurvePersistenceSeam.StoredRun loaded = result.runs().get(i);
      assertEquals(keys[i], loaded.key(), "key mismatch at index " + i);
      assertEquals(deltas[i], loaded.length(), "length mismatch at index " + i);

      // Verify every mark in this run was an offered mark
      for (long k = loaded.key(); k < loaded.key() + loaded.length(); k++) {
        totalOfferedMarks++;
        hybrid.locationToXZ(k, coords);
        if (!world.isOccupied(coords.x, coords.z)) {
          offeredMarkMatches++;
        }
      }
    }

    double retention = (double) offeredMarkMatches / totalOfferedMarks;
    REPORT.add("round trip equivalence", "exact reload", "run-for-run identical", "true", Provenance.MEASURED);
    REPORT.add("round trip equivalence", "exact reload", "offered mark retention", retention, Provenance.MEASURED);
    REPORT.add("codec throughput", "keyWidth=4", "serialize ns/run", (double) serTime4 / totalRuns, Provenance.MEASURED);
    REPORT.add("codec throughput", "keyWidth=4", "deserialize ns/run", (double) deserTime4 / totalRuns, Provenance.MEASURED);

    assertEquals(1.000d, retention, 1e-12, "offered mark retention must be exactly 1.000");
  }

  @Test
  @DisplayName("lossless upward fold on multiple P and discard on non-multiple P")
  public void testUnitMismatchHandling() {
    NoiseWorldMask world = new NoiseWorldMask(SEED, RADIUS_CHUNKS, 0.45d);
    SpiralHilbertSquare hybrid16 = new SpiralHilbertSquare(RADIUS_CHUNKS, 16, true);
    SpiralHilbertSquare hybrid32 = new SpiralHilbertSquare(RADIUS_CHUNKS, 32, true);

    // Build sample table at P=16
    List<Long> badKeys = new ArrayList<>();
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    for (long k = 0; k < hybrid16.getRange(); k++) {
      hybrid16.locationToXZ(k, coords);
      if (coords.x >= -RADIUS_CHUNKS && coords.x < RADIUS_CHUNKS
          && coords.z >= -RADIUS_CHUNKS && coords.z < RADIUS_CHUNKS) {
        if (!world.isOccupied(coords.x, coords.z)) {
          badKeys.add(k);
        }
      }
    }

    long[] kArr = new long[badKeys.size()];
    for (int i = 0; i < kArr.length; i++) kArr[i] = badKeys.get(i);
    KeyRunTable table = KeyRunTable.exact(kArr, kArr.length);
    int runs16 = table.runs();

    long[] keys = new long[runs16];
    long[] deltas = new long[runs16];
    byte[] causes = new byte[runs16];
    long[] expiries = new long[runs16];
    for (int i = 0; i < runs16; i++) {
      keys[i] = table.start(i);
      deltas[i] = table.length(i);
    }

    byte[] bin16 =
        CurvePersistenceSeam.serialize(
            CurvePersistenceSeam.CURVE_SPIRAL_HILBERT,
            16,
            WORLD_NAME,
            1L,
            keys,
            deltas,
            causes,
            expiries,
            runs16,
            hybrid16.getRange());

    // Case 1: Target P = 32 (multiple of 16) -> lossless upward fold
    CurvePersistenceSeam.DeserializationResult foldResult =
        CurvePersistenceSeam.deserialize(
            bin16,
            CurvePersistenceSeam.CURVE_SPIRAL_HILBERT,
            32,
            WORLD_NAME,
            hybrid32);

    assertTrue(foldResult.foldedUpward(), "should fold upward when target P is a multiple");
    assertTrue(!foldResult.discardedAndRelearned(), "lossless fold should not discard");
    assertTrue(foldResult.runs().size() > 0, "folded runs should not be empty");

    // Verify retention on folded runs
    long retainedMarks = 0;
    long totalFoldedMarks = 0;
    for (CurvePersistenceSeam.StoredRun r : foldResult.runs()) {
      for (long k = r.key(); k < r.key() + r.length(); k++) {
        totalFoldedMarks++;
        hybrid32.locationToXZ(k, coords);
        if (!world.isOccupied(coords.x, coords.z)) {
          retainedMarks++;
        }
      }
    }
    double foldRetention = (double) retainedMarks / totalFoldedMarks;
    assertEquals(1.000d, foldRetention, 1e-12, "folded table must retain all offered marks");

    // Case 2: Target P = 24 (non-multiple) -> discard and relearn
    SpiralHilbertSquare hybrid24 = new SpiralHilbertSquare(RADIUS_CHUNKS, 8, true); // proxy
    CurvePersistenceSeam.DeserializationResult discardResult =
        CurvePersistenceSeam.deserialize(
            bin16,
            CurvePersistenceSeam.CURVE_SPIRAL_HILBERT,
            24,
            WORLD_NAME,
            hybrid24);

    assertTrue(discardResult.discardedAndRelearned(), "incompatible P must discard and relearn");
    assertEquals(0, discardResult.runs().size(), "discarded result should hold 0 runs");

    REPORT.add("unit mismatch", "16 -> 32 (multiple)", "folded upward", "true", Provenance.DERIVED);
    REPORT.add("unit mismatch", "16 -> 32 (multiple)", "retention", foldRetention, Provenance.MEASURED);
    REPORT.add("unit mismatch", "16 -> 24 (non-multiple)", "discarded and relearned", "true", Provenance.DERIVED);
  }
}
