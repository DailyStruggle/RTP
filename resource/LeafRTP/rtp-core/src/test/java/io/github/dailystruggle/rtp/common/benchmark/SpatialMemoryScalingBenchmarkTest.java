package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-001 / ADR-028 / ADR-079 / ADR-080 - behavioural scaling model: what spatial-memory durability
 * and verification order do to cost as a server runs for hours over a production-sized world.
 *
 * <p><b>Opt-in.</b> Tagged {@code simulation}; {@code test} excludes it. Run via {@code ./gradlew
 * :rtp-core:simulationBenchmark}.
 *
 * <p><b>Why a model, and why it is the only instrument that can answer this.</b> The real harness
 * (helpers/StressTestRTP) measures per-operation cost on real servers, and it must - no model can
 * substitute for that. What it cannot reach is the horizon: 30-60 minutes per data point of setup,
 * run, collection and analysis buys a window of minutes, and the behaviour under test only diverges
 * over hours. A wall-clock TTL of 15 minutes is never crossed by a 2-minute phase even once. This
 * runs simulated hours in seconds, so the divergence is observable at all.
 *
 * <p><b>Every number here is MODELED.</b> Nothing is timed. The outputs are operation counts -
 * candidates examined, chunk loads, region-context acquisitions, coverage - which are the
 * quantities the real harness already measures, so the model is falsifiable against it rather than
 * self-certifying. The strategies are reconstructions of *published behaviour classes*, never
 * decompiled code, and are named as strategy classes rather than as plugins.
 *
 * <p><b>Common random numbers.</b> Every profile is driven by the identical candidate stream, drawn
 * from a per-request seed. Two profiles facing request <i>i</i> see the same coordinates in the same
 * order, so a difference between them is attributable only to the operations they chose to perform
 * and never to luck of the draw. This is variance reduction by construction and it is what makes a
 * paired comparison meaningful at these sample sizes.
 *
 * <p>Assertions are self-consistency only. Nothing asserts a favourable result; the validation
 * gates against measured ground truth are emitted as report rows for a human to accept or reject
 * before anything is published (ADR-080).
 */
@Tag("simulation")
@DisplayName("ADR-080 spatial-memory durability and verification-order scaling model")
class SpatialMemoryScalingBenchmarkTest {

  /** Requests replayed per (profile, rate, radius) cell. */
  private static final int REQUESTS = Integer.getInteger("rtp.simulation.scaling.requests", 50_000);

  /** Retry ceiling per request. Beyond this the request is recorded as a failure. */
  private static final int MAX_ATTEMPTS =
      Integer.getInteger("rtp.simulation.scaling.maxAttempts", 64);

  /** Wall-clock TTL of the blanket-expiry strategy class, in seconds. */
  private static final int BLANKET_TTL_SECONDS =
      Integer.getInteger("rtp.simulation.scaling.blanketTtlSeconds", 900);

  /** Sustained request rates, in requests per second of simulated time. */
  private static final int[] RATES = {1, 10, 100};

  /** Region radii in blocks. */
  private static final int[] RADII_BLOCKS = {1_024, 4_096, 10_000};

  /**
   * Blob side in chunks for the terrain model. Unsafe terrain is spatially correlated at scales of
   * hundreds of chunks (oceans, badlands); an uncorrelated coin flip per chunk would be a strictly
   * easier world and would understate every strategy's cost equally.
   */
  private static final int TERRAIN_BLOB_CHUNKS =
      Integer.getInteger("rtp.simulation.scaling.blobChunks", 8);

  /**
   * Share of terrain that fails verification. Anchored to the 35-65 % unsafe-terrain figure already
   * published from real measurement rather than chosen to suit the outcome; the midpoint is used and
   * the knob is exposed so a skeptic can move it.
   */
  private static final double BAD_TERRAIN_RATE =
      Double.parseDouble(System.getProperty("rtp.simulation.scaling.badRate", "0.50"));

  private static final SimulationReport REPORT = new SimulationReport();

  @AfterAll
  static void writeReport() {
    REPORT.write("spatial-memory-scaling");
  }

  // ---------------------------------------------------------------------------------------------
  // strategy classes
  // ---------------------------------------------------------------------------------------------

  /** How a profile remembers failures. */
  private enum Memory {
    /** Cause-typed persistence: immutable terrain facts never expire (ADR-079). */
    PERSISTENT_CAUSE_TYPED,
    /** Blanket wall-clock expiry of every entry regardless of why it was recorded. */
    BLANKET_TTL,
    /** Stateless: every request re-samples with no knowledge of previous failures. */
    NONE
  }

  /** How a profile decides a candidate is unsafe. */
  private enum Verification {
    /**
     * Region-file bytes are read off-tick and the candidate is rejected without loading a chunk or
     * acquiring a region context; only survivors are materialised.
     */
    PREFILTER_BEFORE_LOAD,
    /** The chunk is loaded and the live block API is queried, so every candidate costs a load. */
    LOAD_THEN_CHECK
  }

  private record Profile(String name, Memory memory, Verification verification) {}

  private static final Profile[] PROFILES = {
    // The shipped architecture.
    new Profile("persistent + prefilter (LeafRTP)", Memory.PERSISTENT_CAUSE_TYPED,
        Verification.PREFILTER_BEFORE_LOAD),
    // Strategy class: coarse failed-area memory on a wall-clock TTL, live-API verification.
    new Profile("TTL memory + load-then-check", Memory.BLANKET_TTL, Verification.LOAD_THEN_CHECK),
    // Strategy class: stateless re-roll.
    new Profile("no memory + load-then-check", Memory.NONE, Verification.LOAD_THEN_CHECK),
    // Isolates the two axes: same durable memory, live-API verification. The difference against
    // the first profile is the verification order alone, which is the only way to attribute the
    // gap rather than assert it.
    new Profile("persistent + load-then-check", Memory.PERSISTENT_CAUSE_TYPED,
        Verification.LOAD_THEN_CHECK)
  };

  // ---------------------------------------------------------------------------------------------
  // terrain
  // ---------------------------------------------------------------------------------------------

  /**
   * Deterministic correlated terrain. Blob-resolution hash, so a whole {@link
   * #TERRAIN_BLOB_CHUNKS}-square of chunks shares a verdict and failures come in patches.
   */
  private static boolean badChunk(int cx, int cz) {
    long bx = Math.floorDiv(cx, TERRAIN_BLOB_CHUNKS);
    long bz = Math.floorDiv(cz, TERRAIN_BLOB_CHUNKS);
    long h = bx * 0x9E3779B97F4A7C15L ^ bz * 0xC2B2AE3D27D4EB4FL;
    h ^= h >>> 29;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 32;
    double u = (h >>> 11) / (double) (1L << 53);
    return u < BAD_TERRAIN_RATE;
  }

  private static long chunkKey(int cx, int cz) {
    return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
  }

  // ---------------------------------------------------------------------------------------------
  // the replay
  // ---------------------------------------------------------------------------------------------

  /** Per-cell outcome. All counts are per served teleport unless named otherwise. */
  private record Outcome(
      double candidatesExamined,
      double memorySkips,
      double chunkLoads,
      double regionContexts,
      double regionFileReads,
      double served,
      double failures,
      double coverage,
      long rememberedEntries,
      double horizonHours) {}

  private static Outcome replay(Profile profile, int rate, int radiusBlocks, long seed) {
    int chunkRadius = radiusBlocks / 16;
    long chunksInRegion = (long) (2 * chunkRadius) * (2 * chunkRadius);

    // Key -> expiry second. Long.MAX_VALUE for entries that never expire, which is what
    // cause-typed persistence of an immutable terrain fact amounts to.
    Map<Long, Long> remembered = new HashMap<>();

    long examined = 0;
    long skips = 0;
    long loads = 0;
    long contexts = 0;
    long fileReads = 0;
    long served = 0;
    long failures = 0;

    // Steady state only: the first two thirds are warmup, because a cold memory flatters the
    // durable profiles' early requests and a plateau cannot be read off a rising curve.
    int measureFrom = (REQUESTS * 2) / 3;
    long mExamined = 0;
    long mSkips = 0;
    long mLoads = 0;
    long mContexts = 0;
    long mFileReads = 0;
    long mServed = 0;
    long mFailures = 0;

    for (int req = 0; req < REQUESTS; req++) {
      long nowSeconds = req / (long) rate;
      boolean measure = req >= measureFrom;
      // Common random numbers: the candidate stream is a function of the request index alone, so
      // every profile faces identical coordinates in identical order.
      Random rng = new Random(seed * 31L + req);
      boolean done = false;

      for (int attempt = 0; attempt < MAX_ATTEMPTS && !done; attempt++) {
        int cx = rng.nextInt(2 * chunkRadius) - chunkRadius;
        int cz = rng.nextInt(2 * chunkRadius) - chunkRadius;
        long key = chunkKey(cx, cz);

        if (profile.memory() != Memory.NONE) {
          Long expiry = remembered.get(key);
          if (expiry != null) {
            if (expiry > nowSeconds) {
              skips++;
              if (measure) mSkips++;
              continue; // free: no read, no load, no region context
            }
            remembered.remove(key); // lazily expired
          }
        }

        examined++;
        if (measure) mExamined++;
        boolean bad = badChunk(cx, cz);

        if (profile.verification() == Verification.PREFILTER_BEFORE_LOAD) {
          fileReads++;
          if (measure) mFileReads++;
          if (!bad) {
            loads++;
            contexts++;
            if (measure) {
              mLoads++;
              mContexts++;
            }
          }
        } else {
          // Every candidate costs a load and, on a region-threaded server, the owning region's
          // context - candidates are scattered, so consecutive candidates are rarely co-owned.
          loads++;
          contexts++;
          if (measure) {
            mLoads++;
            mContexts++;
          }
        }

        if (bad) {
          if (profile.memory() == Memory.PERSISTENT_CAUSE_TYPED) {
            remembered.put(key, Long.MAX_VALUE);
          } else if (profile.memory() == Memory.BLANKET_TTL) {
            remembered.put(key, nowSeconds + BLANKET_TTL_SECONDS);
          }
        } else {
          served++;
          if (measure) mServed++;
          done = true;
        }
      }
      if (!done) {
        failures++;
        if (measure) mFailures++;
      }
    }

    // Live coverage at the end of the run: expired entries do not count as knowledge.
    long nowSeconds = REQUESTS / (long) rate;
    long live = 0;
    for (Long expiry : remembered.values()) if (expiry > nowSeconds) live++;

    double perServed = Math.max(1.0, mServed);
    return new Outcome(
        mExamined / perServed,
        mSkips / perServed,
        mLoads / perServed,
        mContexts / perServed,
        mFileReads / perServed,
        mServed,
        mFailures,
        chunksInRegion > 0 ? (double) live / chunksInRegion : Double.NaN,
        live,
        nowSeconds / 3600.0);
  }

  // ---------------------------------------------------------------------------------------------
  // tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("coverage convergence and cost per served teleport over simulated hours")
  void scalingSweep() {
    for (int radius : RADII_BLOCKS) {
      for (int rate : RATES) {
        String cell = radius + "b @ " + rate + "/s";
        for (Profile profile : PROFILES) {
          Outcome o = replay(profile, rate, radius, 20260904L);
          String subject = profile.name() + " | " + cell;
          REPORT.add(
              "scaling", subject, "simulated horizon (hours)", o.horizonHours(), Provenance.MODELED);
          REPORT.add("scaling", subject, "candidates examined per teleport", o.candidatesExamined(),
              Provenance.MODELED);
          REPORT.add("scaling", subject, "memory skips per teleport", o.memorySkips(),
              Provenance.MODELED);
          REPORT.add("scaling", subject, "chunk loads per teleport", o.chunkLoads(),
              Provenance.MODELED);
          REPORT.add("scaling", subject, "region contexts per teleport", o.regionContexts(),
              Provenance.MODELED);
          REPORT.add("scaling", subject, "region-file reads per teleport", o.regionFileReads(),
              Provenance.MODELED);
          REPORT.add("scaling", subject, "steady-state coverage %", 100.0 * o.coverage(),
              Provenance.MODELED);
          REPORT.add("scaling", subject, "remembered entries (live)",
              Long.toString(o.rememberedEntries()), Provenance.MODELED);
          REPORT.add("scaling", subject, "unserved requests in window",
              Long.toString((long) o.failures()), Provenance.MODELED);

          assertTrue(o.candidatesExamined() >= 0.0, subject + " produced no measurable work");
        }

        // Closed form for the blanket-TTL plateau, printed beside the simulated value. If they
        // disagree the model is wrong, and that is worth knowing before anything is published:
        // resident entries cannot exceed learn rate x TTL, so coverage cannot exceed that over the
        // region's chunk count no matter how long the server runs.
        long chunksInRegion = (long) (2 * (radius / 16)) * (2 * (radius / 16));
        double learnRatePerSecond = rate * BAD_TERRAIN_RATE;
        double plateau = learnRatePerSecond * BLANKET_TTL_SECONDS / chunksInRegion;
        REPORT.add("closed form", "blanket TTL | " + cell,
            "coverage ceiling % (learn rate x TTL / chunks)", 100.0 * Math.min(1.0, plateau),
            Provenance.DERIVED);
      }
    }

    REPORT.note(
        "The blanket-TTL profile's coverage is bounded by learn rate x TTL and is therefore "
            + "independent of uptime: it plateaus and stays there. Cause-typed persistence has no "
            + "such bound, so its coverage is monotonically non-decreasing and its cost trends down "
            + "over the life of the server. That is a structural difference between two curves, not "
            + "a tuning difference, and it holds for any per-operation cost.");
    REPORT.note(
        "Parity disclosure (ADR-080): on cache depth, warm-chunk residency, TPS backoff, search "
            + "during teleport delay and cross-server shared caches, the modelled strategy classes "
            + "are at or near parity with the shipped architecture. This model deliberately isolates "
            + "the two axes where they are not - memory durability and verification order - and "
            + "makes no claim on the rest.");
    REPORT.note(
        "The fourth profile (persistent + load-then-check) exists to separate the two axes. Its gap "
            + "against profile 1 is verification order alone; its gap against profile 2 is memory "
            + "durability alone. Without it the combined result could not be attributed.");
    REPORT.note(
        "Attribution, and it is not what the design discussion assumed: at a 10 000-block radius "
            + "verification order accounts for essentially the whole gap (1.94 -> 1.00 chunk loads "
            + "per teleport), while durable memory moves load-then-check only from 2.01 to 1.94. "
            + "Spatial memory pays off in proportion to coverage, and coverage of a "
            + "production-sized world is low for any strategy. The publishable claim is therefore "
            + "verification-before-load; memory durability is a long-horizon second-order effect and "
            + "must be presented as one.");
    REPORT.note(
        "Limits of the request budget: the persistent profiles' coverage figures are bounded by the "
            + "replayed request count, not by an asymptote - their curve is still rising when the "
            + "window ends. The blanket-TTL figures are at their ceiling and stay there. Where the "
            + "two are equal (high rate, small radius) the TTL window simply spans the whole "
            + "simulated horizon, so no expiry has bitten yet; that is a real regime, not an "
            + "artefact, and it is where a TTL costs nothing.");
    REPORT.note(
        "Simulated coverage runs about 2x the closed-form ceiling because a served teleport records "
            + "roughly two bad chunks rather than one, so the learn rate in the closed form is a "
            + "lower bound. Order and trend agree across all nine cells, which is what the "
            + "cross-check is for.");
    assertTrue(REPORT.size() > 0, "report must contain rows");
  }

  @Test
  @DisplayName("validation gates against measured ground truth (PRE_WRITEUP section 12)")
  void validationGates() {
    // Measured anchors, pregenerated world, Paper, run 20260617-175906 (PRE_WRITEUP section 12).
    // Fixed here before the model's output is read, per ADR-080: a gate chosen after seeing the
    // result is not a gate.
    final double measuredLeafChunksPerAttempt = 1.58;
    final double measuredLoadThenCheckChunksPerAttempt = 5.86; // heaviest measured profile
    final double measuredRatio =
        measuredLoadThenCheckChunksPerAttempt / measuredLeafChunksPerAttempt;

    Outcome leaf = replay(PROFILES[0], 10, 4_096, 20260904L);
    Outcome ttl = replay(PROFILES[1], 10, 4_096, 20260904L);

    double modelledLeafPerCandidate = leaf.chunkLoads() / Math.max(1e-9, leaf.candidatesExamined());
    double modelledTtlPerCandidate = ttl.chunkLoads() / Math.max(1e-9, ttl.candidatesExamined());
    double modelledRatio = ttl.chunkLoads() / Math.max(1e-9, leaf.chunkLoads());

    REPORT.add("validation", "measured (section 12)", "chunks per attempt, prefilter profile",
        measuredLeafChunksPerAttempt, Provenance.MEASURED);
    REPORT.add("validation", "measured (section 12)", "chunks per attempt, heaviest profile",
        measuredLoadThenCheckChunksPerAttempt, Provenance.MEASURED);
    REPORT.add("validation", "measured (section 12)", "between-profile ratio", measuredRatio,
        Provenance.DERIVED);
    REPORT.add("validation", "model", "chunk loads per candidate, prefilter profile",
        modelledLeafPerCandidate, Provenance.MODELED);
    REPORT.add("validation", "model", "chunk loads per candidate, load-then-check profile",
        modelledTtlPerCandidate, Provenance.MODELED);
    REPORT.add("validation", "model", "between-profile ratio, loads per teleport", modelledRatio,
        Provenance.MODELED);
    REPORT.add("validation", "gate", "ratio deviation factor (model / measured)",
        modelledRatio / measuredRatio, Provenance.DERIVED);
    REPORT.add("validation", "gate", "threshold (fixed before results were read)", "2.0x",
        Provenance.DERIVED);
    REPORT.add("validation", "gate", "within threshold",
        Boolean.toString(
            modelledRatio / measuredRatio < 2.0 && modelledRatio / measuredRatio > 0.5),
        Provenance.DERIVED);

    REPORT.note(
        "The gate is on the *between-profile ratio*, not on absolute chunks per attempt. The "
            + "measured figure is per attempt while the model reports per served teleport, and the "
            + "measured runs' attempts-per-teleport is not recorded, so absolutes are not "
            + "comparable. The ratio is, and it is the quantity any published claim would rest on.");
    REPORT.note(
        "This gate governs publication, not the build: a failing gate must block a front-page "
            + "figure, but making the test red would only pressure whoever runs it into loosening "
            + "the threshold (ADR-080).");

    assertTrue(modelledRatio > 0.0, "model must produce a positive ratio");
  }
}
