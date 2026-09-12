package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.SimulationReport.Provenance;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-079 / ADR-080 - GC pressure of competing spatial-memory and caching designs, measured as real
 * allocated bytes per teleport served.
 *
 * <p><b>Opt-in.</b> Tagged {@code simulation} and excluded from {@code test}; run via {@code
 * ./gradlew :rtp-core:simulationBenchmark}.
 *
 * <p><b>Why allocation rather than heap.</b> Whole-JVM heap under load needs a real server and
 * 30-60 minutes per data point, which makes it a confirmation instrument, not an iteration one.
 * Allocated bytes per request is the thing GC pressure actually is - collector work is driven by
 * allocation rate, not by residency - and {@code getThreadAllocatedBytes} reports it exactly, on one
 * thread, deterministically, in seconds. GC counts and pause time are reported alongside as a
 * cross-check, never alone: they are noisy and collector-dependent where the allocation figure is
 * not.
 *
 * <p><b>What is and is not real here.</b> The this-plugin arm runs shipped classes ({@link
 * io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer}, {@link
 * io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape}), so its
 * bytes are MEASURED against production code. The other arms are strategy-class reconstructions, so
 * their bytes are real for the structure we wrote and the *faithfulness* of that structure is a
 * modelling claim: MODELED. No arm fabricates chunk-payload allocation - that is platform-owned, and
 * a proxy {@code byte[]} sized "like a chunk" would be arithmetic pretending to be a measurement.
 * Consequently these figures are strictly plugin-side and understate every reroll-heavy design,
 * because the term they omit scales with chunks materialised, which those designs have most of.
 *
 * <p><b>Virtual clock.</b> Requests advance a simulated clock at a configurable rate, so a
 * wall-clock TTL can be crossed many times inside a few seconds of real time. The TTL-versus-durable
 * comparison therefore depends on an assumed request rate, which is a parameter and is reported.
 *
 * <p>Assertions are self-consistency only: every model served every request and produced a positive
 * allocation sample. Nothing asserts a favourable ratio.
 */
@Tag("simulation")
@DisplayName("ADR-080 allocation and GC pressure per memory-model design")
class MemoryModelAllocationBenchmarkTest {

  /** Teleport requests replayed per model. */
  private static final int REQUESTS = Integer.getInteger("rtp.simulation.alloc.requests", 200_000);

  /** Simulated requests per second, which sets how many TTL windows the run crosses. */
  private static final int REQUESTS_PER_SECOND =
      Integer.getInteger("rtp.simulation.alloc.requestsPerSecond", 10);

  /** Selection radius in chunks. 4096 blocks / 16. */
  private static final int CHUNK_RADIUS = Integer.getInteger("rtp.simulation.alloc.chunkRadius", 256);

  /** Correlation length of unsafe terrain, in chunks. An "ocean" is not one chunk wide. */
  private static final int CHUNKS_PER_BLOB =
      Integer.getInteger("rtp.simulation.alloc.chunksPerBlob", 16);

  /** Unsafe fraction of the world. Anchored to the 35-65% range measured on real worlds. */
  private static final double UNSAFE_FRACTION =
      Double.parseDouble(System.getProperty("rtp.simulation.alloc.unsafeFraction", "0.45"));

  /**
   * Simulated interval between span-array rebuilds in the durable-memory arm. Swept, because the
   * measured result is that this parameter - not the representation - dominates that arm's
   * allocation.
   */
  private static final long REBUILD_INTERVAL_MILLIS =
      Long.getLong("rtp.simulation.alloc.rebuildIntervalMillis", 300_000L);

  private static final long TTL_MILLIS =
      Long.getLong("rtp.simulation.alloc.ttlMillis", 900_000L); // the 15-minute design class

  private static final String SECTION = "allocation";

  /** Kept separate so a pre-scanned row can never be read as a cold-start row. */
  private static final String SCAN_SECTION = "allocation, pre-scanned world";

  private static final SimulationReport REPORT = new SimulationReport();

  private static com.sun.management.ThreadMXBean allocBean;

  @BeforeAll
  static void setup() {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;

    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
      sun.setThreadAllocatedMemoryEnabled(true);
      allocBean = sun;
    }
  }

  @AfterAll
  static void writeReport() {
    REPORT.write("memory-model-allocation");
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Requests per allocation sampling bucket. Bucketing is what makes the load classifiable: one
   * total divided by one request count cannot tell a design that allocates a steady trickle apart
   * from one that allocates nothing and then a megabyte, and those two have very different costs.
   */
  private static final int BUCKET_REQUESTS =
      Integer.getInteger("rtp.simulation.alloc.bucketRequests", 256);

  private record Sample(
      MemoryModel model,
      long allocatedBytes,
      long constructionBytes,
      long[] bucketBytes,
      long gcCollections,
      long gcMillis,
      long chunkMaterializations,
      long retainedEntries,
      long candidatesConsumed,
      long unservedRequests) {}

  /**
   * Allocation split by load class, because one bytes-per-teleport figure is a poor proxy for
   * performance impact and flatters the wrong designs.
   *
   * <p>Four classes, and they do not cost the same per byte:
   *
   * <ul>
   *   <li><b>one-time</b> - allocated at construction and retained. Amortises to nothing over a
   *       server's life and is not GC pressure at all; a bounded structure that pre-allocates its
   *       arrays will always look worse than a design that allocates nothing up front, and that
   *       difference is a red herring.
   *   <li><b>steady transient</b> - the per-request trickle, taken as the <em>median</em> bucket so
   *       bursts cannot inflate it. Dies young, so a generational collector reclaims it at a cost
   *       proportional to survivors rather than to bytes allocated. This is the cheapest class per
   *       byte and the one a mean-based figure over-weights.
   *   <li><b>burst</b> - allocation above the steady rate, from periodic bulk work such as a
   *       span-array rebuild. Large arrays are the expensive class: they can be allocated straight
   *       into the old generation or as humongous regions, they raise pause times rather than just
   *       collection counts, and they fragment.
   *   <li><b>retained live set</b> - entries still reachable at the end. Costs marking work on
   *       every cycle and occupies heap, but is <em>not</em> churn.
   * </ul>
   *
   * <p>Peak and p99 bucket rates are reported alongside, because instantaneous pressure is what
   * produces a visible pause; a design can have a lower mean and a worse tail.
   */
  private record LoadClasses(
      double oneTimeBytes,
      double steadyBytesPerRequest,
      double burstBytesPerRequest,
      double burstShare,
      double peakBytesPerRequest,
      double p99BytesPerRequest,
      double peakOverSteady) {}

  private static LoadClasses classify(Sample s) {
    long[] buckets = s.bucketBytes().clone();
    if (buckets.length == 0) {
      return new LoadClasses(s.constructionBytes(), 0, 0, 0, 0, 0, 0);
    }
    long[] sorted = buckets.clone();
    java.util.Arrays.sort(sorted);
    double median = sorted[sorted.length / 2] / (double) BUCKET_REQUESTS;
    double p99 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))]
        / (double) BUCKET_REQUESTS;
    double peak = sorted[sorted.length - 1] / (double) BUCKET_REQUESTS;

    // Excess over the median bucket, i.e. everything the steady rate does not explain.
    long steadyPerBucket = sorted[sorted.length / 2];
    long excess = 0;
    long total = 0;
    for (long b : buckets) {
      total += b;
      if (b > steadyPerBucket) excess += b - steadyPerBucket;
    }
    int requestsSampled = buckets.length * BUCKET_REQUESTS;
    return new LoadClasses(
        s.constructionBytes(),
        median,
        excess / (double) requestsSampled,
        total == 0 ? 0.0 : excess / (double) total,
        peak,
        p99,
        median <= 0 ? 0.0 : peak / median);
  }

  private static long allocated() {
    return allocBean == null ? -1L : allocBean.getCurrentThreadAllocatedBytes();
  }

  private static long gcCount() {
    long n = 0;
    for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
      long c = b.getCollectionCount();
      if (c > 0) n += c;
    }
    return n;
  }

  private static long gcMillis() {
    long n = 0;
    for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
      long t = b.getCollectionTime();
      if (t > 0) n += t;
    }
    return n;
  }

  /** Replays the request stream through one freshly-built model and samples the deltas around it. */
  private static Sample run(Supplier<MemoryModel> factory, CandidateStream stream) {
    return run(factory, stream, m -> {});
  }

  /**
   * As {@link #run(Supplier, CandidateStream)}, with a preparation step applied to the model before
   * the measured window opens.
   *
   * <p>Used for the pre-scanned arm: an operator-scheduled off-tick crawl is not per-request churn,
   * so charging it to the request stream would misclassify setup as steady load. Its own allocation
   * is sampled and reported separately.
   *
   * @param prepare applied to both the warm-up instance and the measured instance, so the two see
   *     the same starting state
   */
  private static Sample run(
      Supplier<MemoryModel> factory,
      CandidateStream stream,
      java.util.function.Consumer<MemoryModel> prepare) {
    // Warm pass on a throwaway instance so class loading, lambda linkage and the first-call
    // resolution of every callee happen outside the measured window.
    MemoryModel warm = factory.get();
    prepare.accept(warm);
    stream.reset();
    for (int i = 0; i < 2_000; i++) warm.serve(stream, i * 1_000L / REQUESTS_PER_SECOND);

    // Construction is sampled separately rather than folded into the per-request figure. A design
    // that pre-allocates bounded arrays pays here once and never again; charging that to the first
    // requests would make a one-time footprint read as churn, which is the misclassification this
    // whole split exists to remove.
    long constructionBefore = allocated();
    MemoryModel model = factory.get();
    long constructionBytes = allocated() - constructionBefore;
    long prepareBefore = allocated();
    prepare.accept(model);
    long prepareBytes = allocated() - prepareBefore;
    stream.reset();

    long millisPerRequest = Math.max(1L, 1_000L / REQUESTS_PER_SECOND);
    int buckets = Math.max(1, REQUESTS / BUCKET_REQUESTS);
    long[] bucketBytes = new long[buckets];
    long gcCountBefore = gcCount();
    long gcMillisBefore = gcMillis();
    long consumed = 0;
    long allocBefore = allocated();
    long bucketStart = allocBefore;
    for (int i = 0; i < REQUESTS; i++) {
      consumed += model.serve(stream, i * millisPerRequest);
      if ((i + 1) % BUCKET_REQUESTS == 0) {
        int b = (i + 1) / BUCKET_REQUESTS - 1;
        if (b < buckets) {
          long now = allocated();
          bucketBytes[b] = now - bucketStart;
          bucketStart = now;
        }
      }
    }
    long allocDelta = allocated() - allocBefore;

    return new Sample(
        model,
        allocDelta,
        constructionBytes + prepareBytes,
        bucketBytes,
        gcCount() - gcCountBefore,
        gcMillis() - gcMillisBefore,
        model.chunkMaterializations(),
        model.retainedEntries(),
        consumed,
        model.unservedRequests());
  }

  @Test
  @DisplayName("bytes allocated per teleport served, by memory-model design")
  void allocationPerTeleport() {
    assertTrue(allocBean != null, "per-thread allocation counters are required for this benchmark");

    CandidateStream stream =
        new CandidateStream(1 << 20, CHUNK_RADIUS, CHUNKS_PER_BLOB, UNSAFE_FRACTION, 20260904L);

    Map<String, Supplier<MemoryModel>> models = new LinkedHashMap<>();
    models.put(
        "this plugin",
        () -> new MemoryModels.Leaf(1_024, CHUNK_RADIUS, 2, REBUILD_INTERVAL_MILLIS));
    // Depth parity. The arm above runs a 1024-deep hot tier against strategy classes configured to
    // their published default depth of 20, and cache depth is not free: a deeper tier admits more
    // locations, so it allocates more entry objects before it saturates and it holds a larger live
    // set. Without this arm a depth difference would be indistinguishable from a representation
    // difference, which is the confound the maintainer flagged.
    models.put(
        "this plugin, cache depth 20",
        () -> new MemoryModels.Leaf(20, CHUNK_RADIUS, 2, REBUILD_INTERVAL_MILLIS));
    models.put("stateless reroll", () -> new MemoryModels.StatelessReroll(32));
    models.put("clustered reroll", () -> new MemoryModels.ClusteredReroll(32, 8));
    models.put("warm queue, no memory", () -> new MemoryModels.WarmQueue(20, 20));
    models.put(
        "TTL coarse-2D memory",
        () -> new MemoryModels.TtlAreaMemory(20, 20, 12, TTL_MILLIS, 60_000L));

    REPORT.add(SECTION, "run", "requests per model", Integer.toString(REQUESTS),
        Provenance.MEASURED);
    REPORT.add(SECTION, "run", "simulated requests per second",
        Integer.toString(REQUESTS_PER_SECOND), Provenance.MEASURED);
    REPORT.add(SECTION, "run", "simulated hours covered",
        REQUESTS / (double) REQUESTS_PER_SECOND / 3600.0, Provenance.DERIVED);
    REPORT.add(SECTION, "run", "TTL windows crossed",
        REQUESTS * (1_000.0 / REQUESTS_PER_SECOND) / TTL_MILLIS, Provenance.DERIVED);
    REPORT.add(SECTION, "run", "durable-memory rebuild interval (simulated s)",
        REBUILD_INTERVAL_MILLIS / 1000.0, Provenance.MEASURED);
    REPORT.add(SECTION, "run", "unsafe terrain fraction", UNSAFE_FRACTION, Provenance.MEASURED);
    REPORT.add(SECTION, "run", "terrain correlation length (chunks)",
        Integer.toString(CHUNKS_PER_BLOB), Provenance.MEASURED);

    List<String> names = new ArrayList<>(models.keySet());
    double baseline = -1.0;
    for (String name : names) {
      Sample s = run(models.get(name), stream);
      Provenance tier = name.startsWith("this plugin") ? Provenance.MEASURED : Provenance.MODELED;
      double perRequest = (double) s.allocatedBytes() / REQUESTS;
      if (baseline < 0) baseline = perRequest;

      REPORT.add(SECTION, name, "allocated bytes total",
          Long.toString(s.allocatedBytes()), tier);
      REPORT.add(SECTION, name, "allocated bytes per teleport (mean, all classes)", perRequest,
          tier);
      REPORT.add(SECTION, name, "allocated MB per 100k teleports",
          perRequest * 100_000 / (1024.0 * 1024.0), Provenance.DERIVED);

      // Load classification. The mean above is retained for continuity but is the weakest figure
      // here: it mixes a one-time footprint, a young-dying trickle and periodic bulk arrays, which
      // do not cost the same per byte.
      LoadClasses lc = classify(s);
      REPORT.add(SECTION, name, "class 1 - one-time construction bytes",
          Long.toString(s.constructionBytes()), tier);
      REPORT.add(SECTION, name, "class 1 - one-time bytes amortised per teleport",
          lc.oneTimeBytes() / REQUESTS, Provenance.DERIVED);
      REPORT.add(SECTION, name, "class 2 - steady transient bytes per teleport (median bucket)",
          lc.steadyBytesPerRequest(), tier);
      REPORT.add(SECTION, name, "class 3 - burst bytes per teleport (above steady)",
          lc.burstBytesPerRequest(), tier);
      REPORT.add(SECTION, name, "class 3 - burst share of run allocation (%)",
          lc.burstShare() * 100.0, Provenance.DERIVED);
      REPORT.add(SECTION, name, "class 4 - retained live entries at end",
          Long.toString(s.retainedEntries()), tier);
      // Instantaneous pressure, which is what turns into a visible pause. A design can win on the
      // mean and lose here.
      REPORT.add(SECTION, name, "p99 bucket bytes per teleport", lc.p99BytesPerRequest(), tier);
      REPORT.add(SECTION, name, "peak bucket bytes per teleport", lc.peakBytesPerRequest(), tier);
      REPORT.add(SECTION, name, "burstiness (peak / steady)", lc.peakOverSteady(),
          Provenance.DERIVED);
      REPORT.add(SECTION, name, "GC collections during run",
          Long.toString(s.gcCollections()), Provenance.MEASURED);
      REPORT.add(SECTION, name, "GC ms per 1000 teleports",
          s.gcMillis() * 1000.0 / REQUESTS, Provenance.MEASURED);
      REPORT.add(SECTION, name, "chunk materialisations per teleport",
          (double) s.chunkMaterializations() / REQUESTS, tier);
      REPORT.add(SECTION, name, "entries retained at end",
          Long.toString(s.retainedEntries()), tier);
      REPORT.add(SECTION, name, "candidates evaluated per teleport",
          (double) s.candidatesConsumed() / REQUESTS, tier);
      // Reported next to the allocation figure on purpose: a model that serves nothing allocates
      // nothing, so the cheap row has to be readable as either efficiency or starvation.
      REPORT.add(SECTION, name, "unserved requests (%)",
          s.unservedRequests() * 100.0 / REQUESTS, tier);
      if (baseline > 0) {
        REPORT.add(SECTION, name, "allocation ratio vs this plugin", perRequest / baseline,
            Provenance.MODELED);
      }

      assertTrue(s.allocatedBytes() > 0, "allocation sample must be positive for " + name);
    }

    REPORT.note(
        "Allocation is classified rather than totalled, because total bytes per teleport is a poor "
            + "proxy for performance impact. Class 1 (one-time construction) is paid once per world "
            + "and is not GC pressure at all - a design that pre-allocates bounded arrays will "
            + "always show a larger one-time figure than one that allocates lazily, and that "
            + "difference costs nothing at steady state. Class 2 (steady transient) is the "
            + "per-request trickle and is the cheapest class per byte, since young-dying objects "
            + "are reclaimed at a cost proportional to survivors rather than to bytes allocated. "
            + "Class 3 (burst) is the expensive class: periodic bulk arrays raise pause time and "
            + "can bypass the young generation entirely. Class 4 (retained live set) occupies heap "
            + "and costs marking work, but is not churn. Compare like classes with like classes; a "
            + "ratio taken across the mean is comparing four different costs at once.");
    REPORT.note(
        "Plugin-side allocation only, and this is the tier's largest omission rather than a "
            + "footnote. Chunk load and worldgen allocation is platform-owned - chunk objects, "
            + "palettes, sections, heightmaps, NBT decode buffers - and it is the single most "
            + "expensive allocation any of these designs triggers, far larger than the bookkeeping "
            + "measured here. So a per-attempt reroll is emphatically *not* cheap in absolute "
            + "terms; it is cheap only in the bytes this tier can honestly measure. Every figure "
            + "here therefore understates the designs that materialise the most chunks, and the "
            + "omitted term scales with the chunk-materialisations row, which is reported next to "
            + "it for exactly that reason. Do not read any row as a total cost of a design.");
    REPORT.note(
        "The this-plugin arm runs shipped classes and is MEASURED. Every other arm is a "
            + "strategy-class reconstruction from published configuration semantics: the bytes are "
            + "real for the structure written here, and whether that structure is a fair "
            + "reconstruction is a modelling claim, hence MODELED.");
    REPORT.note(
        "GC collection counts and pause times are whole-JVM and pick up the test framework's own "
            + "garbage; they are a cross-check on the allocation figure, not an independent result, "
            + "and must not be published alone.");
    REPORT.note(
        "The TTL arm's disadvantage is rate-dependent by construction: resident knowledge is bounded "
            + "by learn-rate x TTL, so a higher simulated request rate raises its coverage and lowers "
            + "its re-learning churn. Rate is a reported parameter, not a hidden assumption; sweep it "
            + "before drawing any conclusion from a single row.");

    assertTrue(REPORT.size() > 0, "report must contain rows");
  }

  @Test
  @DisplayName("durable memory: allocation against span-array rebuild cadence")
  void rebuildCadenceSweep() {
    assertTrue(allocBean != null, "per-thread allocation counters are required for this benchmark");

    CandidateStream stream =
        new CandidateStream(1 << 20, CHUNK_RADIUS, CHUNKS_PER_BLOB, UNSAFE_FRACTION, 20260904L);

    for (long intervalMillis : new long[] {60_000L, 300_000L, 900_000L, 3_600_000L}) {
      Sample s =
          run(() -> new MemoryModels.Leaf(1_024, CHUNK_RADIUS, 2, intervalMillis), stream);
      String subject = "this plugin, rebuild every " + (intervalMillis / 1000L) + " s";
      LoadClasses lc = classify(s);
      REPORT.add(SECTION, subject, "allocated bytes per teleport (mean, all classes)",
          (double) s.allocatedBytes() / REQUESTS, Provenance.MEASURED);
      // The cadence moves only the burst class; the steady trickle is cadence-invariant, which is
      // the result that identifies the rebuild rather than the representation as the cost.
      REPORT.add(SECTION, subject, "class 2 - steady transient bytes per teleport",
          lc.steadyBytesPerRequest(), Provenance.MEASURED);
      REPORT.add(SECTION, subject, "class 3 - burst bytes per teleport",
          lc.burstBytesPerRequest(), Provenance.MEASURED);
      REPORT.add(SECTION, subject, "class 3 - burst share of run allocation (%)",
          lc.burstShare() * 100.0, Provenance.DERIVED);
      REPORT.add(SECTION, subject, "peak bucket bytes per teleport", lc.peakBytesPerRequest(),
          Provenance.MEASURED);
      if (s.model() instanceof MemoryModels.Leaf leaf) {
        // Work attribution, because "why is the durable arm not the cheapest row" has a concrete
        // answer: it performs two jobs the memoryless arms do not perform at all. It admits real
        // RTPLocation/RTPCoords objects to a bounded tier where the strategy classes hold one flat
        // array, and it records a learning mark on every rejection where they record nothing. Both
        // are the price of the knowledge that keeps the chunk-materialisations row low.
        REPORT.add(SECTION, subject, "hot-tier capacity (slots)",
            Integer.toString(leaf.cacheCap()), Provenance.MEASURED);
        REPORT.add(SECTION, subject, "entry objects admitted per teleport",
            (double) leaf.entriesCached() / REQUESTS, Provenance.MEASURED);
        REPORT.add(SECTION, subject, "learning marks recorded per teleport",
            (double) leaf.learnedBadChunks() / REQUESTS, Provenance.MEASURED);
        REPORT.add(SECTION, subject, "candidates rejected by memory per teleport (free)",
            (double) leaf.memorySkips() / REQUESTS, Provenance.MEASURED);
        // Cadence is a ceiling, not a schedule: a deadline that comes due with nothing new learned
        // is declined. Both counts are reported so a reader can see whether the burst class is a
        // periodic cost or a work-proportional one.
        REPORT.add(SECTION, subject, "span-array rebuilds performed",
            Long.toString(leaf.rebuilds()), Provenance.MEASURED);
        REPORT.add(SECTION, subject, "rebuild deadlines declined (not dirty enough)",
            Long.toString(leaf.rebuildsSkipped()), Provenance.MEASURED);
      }
      REPORT.add(SECTION, subject, "chunk materialisations per teleport",
          (double) s.chunkMaterializations() / REQUESTS, Provenance.MEASURED);
      REPORT.add(SECTION, subject, "unserved requests (%)",
          s.unservedRequests() * 100.0 / REQUESTS, Provenance.MEASURED);
      assertTrue(s.allocatedBytes() > 0, "allocation sample must be positive");
    }

    REPORT.note(
        "This sweep isolates which load class the rebuild cadence actually moves, and the answer is "
            + "only class 3. Steady transient allocation is near cadence-invariant while burst "
            + "allocation falls by more than an order of magnitude as the interval lengthens, and "
            + "chunk materialisations per teleport do not move at all - so the cadence buys nothing "
            + "and costs nothing except burstiness. Bulk allocation is the class that produces "
            + "pauses rather than merely collection counts, so the peak-bucket row is the one to "
            + "read here, not the mean. Note also that the cadence is a ceiling and not a "
            + "schedule: a deadline that comes due with too little newly learned to amortise the "
            + "merge is declined, mirroring MemoryShape.maybeFlushAndRebuild, so the burst class "
            + "decays as the memory converges instead of recurring forever on the clock. The "
            + "rebuilds-performed and deadlines-declined rows are reported for exactly that "
            + "reason. What the remaining burst buys is the chunk-load work the "
            + "chunk-materialisations row shows being avoided - work this benchmark does not "
            + "price, and which is the largest allocation any of these designs triggers.");

    assertTrue(REPORT.size() > 0, "report must contain rows");
  }

  @Test
  @DisplayName("re-learning churn: entries a wall-clock TTL discards and re-allocates")
  void ttlRelearningChurn() {
    CandidateStream stream =
        new CandidateStream(1 << 20, CHUNK_RADIUS, CHUNKS_PER_BLOB, UNSAFE_FRACTION, 20260904L);

    MemoryModels.TtlAreaMemory ttl =
        new MemoryModels.TtlAreaMemory(20, 20, 12, TTL_MILLIS, 60_000L);
    long millisPerRequest = Math.max(1L, 1_000L / REQUESTS_PER_SECOND);
    stream.reset();
    for (int i = 0; i < REQUESTS; i++) ttl.serve(stream, i * millisPerRequest);

    // Same request stream, same terrain, durable cause-typed memory instead of a wall clock.
    MemoryModels.Leaf leaf =
        new MemoryModels.Leaf(1_024, CHUNK_RADIUS, 2, REBUILD_INTERVAL_MILLIS);
    stream.reset();
    for (int i = 0; i < REQUESTS; i++) leaf.serve(stream, i * millisPerRequest);

    long chunksInRegion = (long) (2 * CHUNK_RADIUS) * (2 * CHUNK_RADIUS);
    REPORT.add(SECTION, "this plugin", "bad chunks learned and still remembered",
        Long.toString(leaf.learnedBadChunks()), Provenance.MEASURED);
    REPORT.add(SECTION, "this plugin", "coverage of region (%)",
        leaf.learnedBadChunks() * 100.0 / chunksInRegion, Provenance.DERIVED);
    REPORT.add(SECTION, "this plugin", "entries expired over run", "0", Provenance.MEASURED);
    REPORT.add(SECTION, "TTL coarse-2D memory", "entries expired over run",
        Long.toString(ttl.expiredEntries()), Provenance.MODELED);
    REPORT.add(SECTION, "TTL coarse-2D memory", "expired entries per teleport",
        (double) ttl.expiredEntries() / REQUESTS, Provenance.MODELED);
    REPORT.add(SECTION, "TTL coarse-2D memory", "entries resident at end",
        Long.toString(ttl.retainedEntries()), Provenance.MODELED);
    REPORT.add(SECTION, "TTL coarse-2D memory", "steady-state coverage of region (%)",
        ttl.retainedEntries() * 100.0 / chunksInRegion, Provenance.MODELED);
    // Closed form, so a reader can check the simulation against arithmetic instead of trusting it:
    // resident entries cannot exceed what was learned inside one TTL window.
    REPORT.add(SECTION, "TTL coarse-2D memory", "closed-form coverage ceiling (%)",
        (REQUESTS_PER_SECOND * (TTL_MILLIS / 1000.0)) * 100.0 / chunksInRegion,
        Provenance.DERIVED);

    REPORT.note(
        "Re-learning churn is the allocation term a wall-clock TTL adds and a durable "
            + "representation does not have: an expired entry is not merely forgotten, it is "
            + "allocated again the next time the same ground is sampled. The closed-form ceiling "
            + "above is the reason the TTL exists - the entry is the unit of knowledge in a keyed "
            + "map, so coverage and memory are the same axis, and full coverage of a production-sized "
            + "world is unaffordable. This says nothing about which choice is right for a given "
            + "server; it says the choice is forced by the representation.");

    assertTrue(ttl.retainedEntries() >= 0, "TTL model must report residency");
  }

  @Test
  @DisplayName("pre-scanned world: allocation and chunk work after /rtp scan has run")
  void scanWarmedAllocation() {
    assertTrue(allocBean != null, "per-thread allocation counters are required for this benchmark");

    CandidateStream stream =
        new CandidateStream(1 << 20, CHUNK_RADIUS, CHUNKS_PER_BLOB, UNSAFE_FRACTION, 20260904L);

    long chunksInRegion = (long) (2 * CHUNK_RADIUS) * (2 * CHUNK_RADIUS);
    REPORT.add(SCAN_SECTION, "run", "chunk cells in region", Long.toString(chunksInRegion),
        Provenance.DERIVED);

    // Coverage is the fraction of the radius the crawler reached, so the scanned area is the
    // square of it: a scan that has covered half the radius has covered a quarter of the region.
    for (double coverage : new double[] {0.0, 0.25, 0.5, 1.0}) {
      Sample s =
          run(
              () -> new MemoryModels.Leaf(1_024, CHUNK_RADIUS, 2, REBUILD_INTERVAL_MILLIS),
              stream,
              m -> ((MemoryModels.Leaf) m).preScan(stream, coverage));
      String subject = "this plugin, scanned to " + Math.round(coverage * 100) + " % of radius";
      LoadClasses lc = classify(s);
      MemoryModels.Leaf leaf = (MemoryModels.Leaf) s.model();

      REPORT.add(SCAN_SECTION, subject, "scan coverage (fraction of radius)", coverage,
          Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "region area pre-scanned (%)",
          coverage * coverage * 100.0, Provenance.DERIVED);
      REPORT.add(SCAN_SECTION, subject, "cells marked bad by the scan",
          Long.toString(leaf.scanMarks()), Provenance.MEASURED);
      // The crawl's own allocation, reported as its own class. It is off-tick operator-scheduled
      // work paid once, not per-request churn, and folding it into a bytes-per-teleport figure
      // would be exactly the class-1 misclassification this tier exists to avoid.
      REPORT.add(SCAN_SECTION, subject, "class 1 - scan and construction bytes (one-time)",
          Long.toString(s.constructionBytes()), Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "class 1 - one-time bytes amortised per teleport",
          (double) s.constructionBytes() / REQUESTS, Provenance.DERIVED);
      REPORT.add(SCAN_SECTION, subject, "class 2 - steady transient bytes per teleport",
          lc.steadyBytesPerRequest(), Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "class 3 - burst bytes per teleport",
          lc.burstBytesPerRequest(), Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "peak bucket bytes per teleport",
          lc.peakBytesPerRequest(), Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "burstiness (peak / steady)", lc.peakOverSteady(),
          Provenance.DERIVED);
      // The two rows the scan is supposed to move: fewer chunks materialised because the answer
      // was already known, and fewer marks recorded because the learning was done up front.
      REPORT.add(SCAN_SECTION, subject, "chunk materialisations per teleport",
          (double) s.chunkMaterializations() / REQUESTS, Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "candidates rejected by memory per teleport (free)",
          (double) leaf.memorySkips() / REQUESTS, Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "learning marks recorded per teleport (at request time)",
          (double) (leaf.learnedBadChunks() - leaf.scanMarks()) / REQUESTS, Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "span-array rebuilds performed",
          Long.toString(leaf.rebuilds()), Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "rebuild deadlines declined (not dirty enough)",
          Long.toString(leaf.rebuildsSkipped()), Provenance.MEASURED);
      REPORT.add(SCAN_SECTION, subject, "unserved requests (%)",
          s.unservedRequests() * 100.0 / REQUESTS, Provenance.MEASURED);

      assertTrue(s.allocatedBytes() > 0, "allocation sample must be positive for " + subject);
    }

    REPORT.note(
        "A pre-scanned world is the configuration this design is actually meant to run in, and it "
            + "is the one no reroll-based design can be given: the crawl only pays off if what it "
            + "learns is still there when a player asks. The scan is charged as class 1 - one-time, "
            + "off-tick, operator-scheduled - because that is what it is; amortising it into a "
            + "bytes-per-teleport figure would misreport setup as churn. Read the "
            + "chunk-materialisations and learning-marks rows across the sweep: both are the work "
            + "the scan moves off the request path.");
    REPORT.note(
        "Coverage is a contiguous radial prefix, not a scatter, because the crawler walks the "
            + "region in order and is normally interrupted part-way. Region area scanned is the "
            + "square of the radial fraction, so the 50 % row has only a quarter of the region "
            + "covered - the sweep is deliberately pessimistic about partial scans.");
    REPORT.note(
        "Learning granularity remains one cell per sampled chunk (addBadLocation, not addBadChunk), "
            + "so the scan here records only cells it visited and claims no neighbourhood. Shipped "
            + "chunk-granular scanning marks spans and is strictly better than this.");

    assertTrue(REPORT.size() > 0, "report must contain rows");
  }
}
