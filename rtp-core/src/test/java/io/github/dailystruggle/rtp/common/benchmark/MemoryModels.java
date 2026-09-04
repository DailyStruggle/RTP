package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reconstructions of the plugin-side memory models under comparison (ADR-080).
 *
 * <p><b>Naming discipline.</b> Only {@link Leaf} is this plugin, built from shipped classes and
 * therefore MEASURED. Every other model is a <em>strategy class</em> named after the design it
 * implements, not after a plugin: reconstructions from published configuration semantics are
 * MODELED, and attaching a rival's name to a number we produced ourselves would be exactly the kind
 * of benchmark-as-marketing ADR-080 exists to prevent. Where a strategy class is recognisably one
 * plugin's design, the configuration keys that motivated each parameter are cited in the javadoc so
 * a reader can check the reconstruction rather than trust it.
 *
 * <p><b>What is deliberately not allocated.</b> No model fabricates a chunk payload. Chunk load and
 * worldgen allocation belongs to the server, and a proxy {@code byte[]} would turn arithmetic into a
 * fake measurement. These models allocate their bookkeeping and nothing else.
 */
final class MemoryModels {

  private MemoryModels() {}

  /** Interned once: a per-entry copy of the world name would be a harness artefact in every arm. */
  private static final String WORLD = "world".intern();

  /**
   * A platform-style location entry as a reroll/warm-cache design holds it: world reference,
   * three coordinates widened to double, yaw/pitch, and a stamp. Deliberately an {@code Object[]}
   * rather than a purpose-built record, because that is the shape of the cost - a header, a
   * reference array, and boxed numerics - and a record with primitive fields would understate it.
   */
  private static Object[] heavyweightEntry(int cx, int cz, long nowMillis) {
    return new Object[] {
      WORLD, (double) (cx * 16 + 8), 64.0d, (double) (cz * 16 + 8), 0.0f, 0.0f, nowMillis
    };
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * This plugin: bounded {@link LockFreeLocationBuffer} hot tier plus persistent run-length 1D
   * spatial memory ({@link MemoryShape}, cause-typed expiry per ADR-079). Both are shipped classes,
   * so the allocation measured here is the allocation production performs.
   *
   * <p>Order of operations is the point: a candidate is tested against spatial memory <em>before</em>
   * anything is materialised, so a known-bad pick costs one primitive lookup and allocates nothing.
   *
   * <p><b>Learning granularity is deliberately the pessimistic one.</b> Failures are recorded with
   * {@code addBadLocation}, one 1D cell per sampled chunk, not with {@code addBadChunk}. The shipped
   * chunk-granular call marks the 1D-contiguous span around a location, and 1D-contiguous is not
   * 2D-contiguous under the spiral bijection - against a 2D terrain oracle it would mark ground the
   * oracle calls safe, inflating both coverage and this arm's apparent advantage. One cell per
   * learned bad chunk is also exactly the knowledge unit the TTL map stores, so the two arms are
   * like-for-like. Real chunk-granular learning is strictly better than what is measured here.
   */
  static final class Leaf implements MemoryModel {
    private final LockFreeLocationBuffer hot;
    private final MemoryShape<?> memory;
    private final int cacheCap;
    private final int chunkRadius;
    private final int refillBudget;
    private long chunkMaterializations;
    private long learnedBadChunks;
    private long memorySkips;
    private long entriesCached;
    private long scanMarks;
    private long unservedRequests;
    private long rebuilds;
    private long rebuildsSkipped;
    private long learnedAtLastRebuild;
    private long rebuildAt;
    private final long rebuildIntervalMillis;

    /**
     * @param chunkRadius shape radius in <em>chunk</em> cells - {@link MemoryShape} indexes region
     *     cells, not blocks, so feeding it block coordinates would put every key on the wrong ring
     */
    Leaf(int cacheCap, int chunkRadius, int refillBudget, long rebuildIntervalMillis) {
      this.cacheCap = cacheCap;
      this.chunkRadius = chunkRadius;
      this.refillBudget = refillBudget;
      this.rebuildIntervalMillis = rebuildIntervalMillis;
      this.hot = new LockFreeLocationBuffer(cacheCap);
      MemoryShape<?> shape = new Square();
      shape.setRng(new Random(4242L));
      Map<String, Object> data = new HashMap<>();
      data.put("radius", chunkRadius);
      data.put("centerRadius", 0);
      data.put("uniquePlacements", 0);
      shape.setData(data);
      this.memory = shape;
    }

    @Override
    public String name() {
      return "persistent 1D memory + bounded hot cache (this plugin)";
    }

    @Override
    public int serve(CandidateStream stream, long nowMillis) {
      int consumed = 0;
      if (hot.pollSilently() == null) {
        // Cold: the request has to wait on production rather than being served from the tier.
        consumed += produce(stream, nowMillis, 1);
        if (hot.pollSilently() == null) unservedRequests++;
      }
      // Refill toward capacity behind the served request, the way the queue manager does.
      consumed += produce(stream, nowMillis, refillBudget);
      return consumed;
    }

    private int produce(CandidateStream stream, long nowMillis, int budget) {
      int consumed = 0;
      for (int i = 0; i < budget && hot.size() < cacheCap; i++) {
        long packed = stream.nextUniform();
        consumed++;
        int cx = CandidateStream.unpackX(packed);
        int cz = CandidateStream.unpackZ(packed);
        long key = memory.xzToLocation(cx, cz);
        // Verification before materialisation: a remembered failure is rejected off-tick with no
        // chunk touched and no allocation at all.
        if (memory.isKnownBad(key)) {
          memorySkips++;
          continue;
        }
        chunkMaterializations++;
        if (!stream.safeGround(packed)) {
          // Cause-typed (ADR-079): expiry is decided by why the ground failed, not by one blanket
          // wall clock. Unsafe terrain is an immutable fact and does not need re-learning.
          memory.addBadLocation(key, LocationGenerator.FailTypes.safety);
          learnedBadChunks++;
          continue;
        }
        hot.offerSilently(new RTPLocation(new RTPCoords(WORLD, cx * 16 + 8, 64, cz * 16 + 8), 1L, null));
        entriesCached++;
      }
      if (nowMillis >= rebuildAt) {
        // Cadence is a ceiling, not a schedule. A rebuild is a full merge of the run array, so
        // production gates it on dirtiness and on a batch large enough to amortise the merge
        // (MemoryShape.maybeFlushAndRebuild: return unless a mark is pending, and require
        // min(256, runs/8) pending marks). Rebuilding unconditionally on the clock would charge
        // this arm for merges a converged memory never performs, which is precisely the artefact
        // that makes the burst class look like a property of the representation.
        long pending = learnedBadChunks - learnedAtLastRebuild;
        // Batch threshold is derived from the learned-cell count rather than from
        // badKeysSnapshot().length, even though production reads the run array directly: the
        // snapshot copies the whole array, and calling it on every declined deadline made the
        // harness itself the dominant allocator (32 865 B/teleport at a 60 s cadence, against
        // 176 B once this was primitive). Run count and learned-cell count agree to ~1.0 runs per
        // bad chunk, measured in the retained-footprint tier, so the threshold is equivalent.
        long batch = Math.min(256L, Math.max(1L, learnedAtLastRebuild / 8));
        if (pending >= batch) {
          // The argument is the run-merge tolerance in cells, not a timestamp: 1 means only
          // genuinely adjacent cells coalesce, which is the cell-exact resolution this arm is held
          // to. Passing the clock here would set a merge tolerance of millions and mark the whole
          // region bad.
          memory.flushAndRebuild(1L);
          rebuilds++;
          learnedAtLastRebuild = learnedBadChunks;
          rebuildAt = nowMillis + rebuildIntervalMillis;
        } else {
          rebuildsSkipped++;
        }
      }
      return consumed;
    }

    @Override
    public long retainedEntries() {
      // Structural entries, so the figure is comparable with a map's entry count: occupied cache
      // slots plus run-length spans. getEffectiveBadCount() counts 1D cells, which is knowledge,
      // not storage, and would not be the same unit as a map entry.
      return hot.size() + memory.badKeysSnapshot().length;
    }

    /** Bad chunks learned and still remembered. Never expires by wall clock. */
    long learnedBadChunks() {
      return learnedBadChunks;
    }

    /**
     * Pre-populate spatial memory the way {@code /rtp scan} does: crawl the region off-tick and
     * record the ground the safety check rejects, before any player asks for a location.
     *
     * <p>Coverage is a contiguous radial prefix rather than a scatter, because the crawler walks
     * the region in order and is normally interrupted part-way, so a real partially-scanned world
     * has a scanned core and an unscanned rim. Marking a uniform random subset instead would model
     * a scan nobody runs and would spread thin knowledge over the whole region.
     *
     * <p>Called before the measured window opens: the crawl is setup cost, not per-request churn,
     * and its allocation is reported as its own class rather than folded into a bytes-per-teleport
     * figure. On a live server it is off-tick work the operator schedules deliberately.
     *
     * @param stream terrain oracle, consulted the way the scanner's safety check would be
     * @param coverageFraction fraction of the radius the crawl reached, 0 for an unscanned world
     * @return cells marked bad by the crawl
     */
    long preScan(CandidateStream stream, double coverageFraction) {
      if (coverageFraction <= 0.0) return 0L;
      int scanned = (int) Math.round(chunkRadius() * Math.min(1.0, coverageFraction));
      for (int cx = -scanned; cx < scanned; cx++) {
        for (int cz = -scanned; cz < scanned; cz++) {
          if (stream.safeGround(CandidateStream.pack(cx, cz))) continue;
          memory.addBadLocation(memory.xzToLocation(cx, cz), LocationGenerator.FailTypes.safety);
          scanMarks++;
          // Scan marks are knowledge like any other, so they count toward the total the dirtiness
          // gate compares against. Tracked separately in scanMarks only so the report can subtract
          // them and show what was learned at request time.
          learnedBadChunks++;
        }
      }
      memory.flushAndRebuild(1L);
      learnedAtLastRebuild = learnedBadChunks;
      return scanMarks;
    }

    /** Cells marked bad by {@link #preScan}, i.e. knowledge held before the first request. */
    long scanMarks() {
      return scanMarks;
    }

    /** Candidates rejected by spatial memory: no chunk touched, no allocation. */
    long memorySkips() {
      return memorySkips;
    }

    /** Locations actually admitted to the hot tier, i.e. entry objects allocated. */
    long entriesCached() {
      return entriesCached;
    }

    /** Hot-tier capacity, reported so a depth difference is never a hidden confound. */
    int cacheCap() {
      return cacheCap;
    }

    private int chunkRadius() {
      return chunkRadius;
    }

    /**
     * Span-array rebuilds performed. This arm's dominant allocation term, so it is reported: a
     * rebuild re-materialises the whole compacted representation, and its cost scales with the
     * knowledge retained rather than with the request that triggered it.
     */
    long rebuilds() {
      return rebuilds;
    }

    /**
     * Cadence deadlines that came due and were declined because the memory was not dirty enough to
     * amortise a merge. Reported because it is the difference between a periodic cost and a
     * work-proportional one: as the memory converges, learning slows, so rebuilds stop happening
     * and the burst class decays on its own rather than recurring forever on the clock.
     */
    long rebuildsSkipped() {
      return rebuildsSkipped;
    }

    @Override
    public long unservedRequests() {
      return unservedRequests;
    }

    @Override
    public long chunkMaterializations() {
      return chunkMaterializations;
    }
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Stateless reroll, uniform selection, no cache and no spatial memory: retry until a candidate
   * verifies, discard everything learned. The design class of a plugin whose only tunable is a
   * maximum-attempts count.
   *
   * <p>Its cost is bounded and identical on every request, which is its one virtue - and it never
   * improves, which is the point being measured.
   */
  static final class StatelessReroll implements MemoryModel {
    private final int maxAttempts;
    private long chunkMaterializations;
    private long unservedRequests;

    StatelessReroll(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    @Override
    public String name() {
      return "stateless reroll, uniform (strategy class)";
    }

    @Override
    public int serve(CandidateStream stream, long nowMillis) {
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        long packed = stream.nextUniform();
        chunkMaterializations++;
        // A candidate object is built before it can be verified: nothing is remembered, so this
        // allocation recurs for coordinates already known-bad on a previous request.
        Object[] candidate =
            heavyweightEntry(
                CandidateStream.unpackX(packed), CandidateStream.unpackZ(packed), nowMillis);
        if (stream.safeGround(packed) && candidate.length > 0) return attempt;
      }
      // Attempt budget exhausted: this design class's failure mode is telling the player no.
      unservedRequests++;
      return maxAttempts;
    }

    @Override
    public long retainedEntries() {
      return 0L;
    }

    @Override
    public long chunkMaterializations() {
      return chunkMaterializations;
    }

    @Override
    public long unservedRequests() {
      return unservedRequests;
    }
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Reroll with clustered selection: sample near the previous pick so consecutive attempts share
   * loaded chunks. Buys chunk reuse, pays in distribution quality - and inside a correlated failure
   * region it searches harder in exactly the wrong place, because unsafe terrain is correlated at
   * the same scale the cluster spans.
   *
   * <p>Also carries a per-request result container, the shape of an implementation that collects
   * candidates before choosing among them.
   */
  static final class ClusteredReroll implements MemoryModel {
    private final int maxAttempts;
    private final int spread;
    private long previous = CandidateStream.pack(0, 0);
    private long chunkMaterializations;
    private long unservedRequests;

    ClusteredReroll(int maxAttempts, int spread) {
      this.maxAttempts = maxAttempts;
      this.spread = spread;
    }

    @Override
    public String name() {
      return "clustered reroll (strategy class)";
    }

    @Override
    public int serve(CandidateStream stream, long nowMillis) {
      List<Object[]> considered = new ArrayList<>(maxAttempts);
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        long packed = stream.nextNear(previous, spread);
        chunkMaterializations++;
        considered.add(
            heavyweightEntry(
                CandidateStream.unpackX(packed), CandidateStream.unpackZ(packed), nowMillis));
        if (stream.safeGround(packed)) {
          previous = packed;
          return attempt;
        }
      }
      // Budget exhausted inside the cluster, so the cluster is abandoned and re-seeded uniformly.
      previous = stream.nextUniform();
      unservedRequests++;
      return maxAttempts;
    }

    @Override
    public long retainedEntries() {
      return 0L;
    }

    @Override
    public long chunkMaterializations() {
      return chunkMaterializations;
    }

    @Override
    public long unservedRequests() {
      return unservedRequests;
    }
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Bounded warm queue of pre-verified locations, no spatial memory: cache hits are near-free and
   * cache misses cost a full cold search, so latency is bimodal and sustained throughput equals
   * refill rate. Refill cost never improves, because nothing is remembered between refills.
   */
  static final class WarmQueue implements MemoryModel {
    private final ArrayDeque<Object[]> queue = new ArrayDeque<>();
    private final int cap;
    private final int refillBudget;
    private long chunkMaterializations;
    private long unservedRequests;

    WarmQueue(int cap, int refillBudget) {
      this.cap = cap;
      this.refillBudget = refillBudget;
    }

    @Override
    public String name() {
      return "warm queue, no spatial memory (strategy class)";
    }

    @Override
    public int serve(CandidateStream stream, long nowMillis) {
      int consumed = 0;
      if (queue.isEmpty()) consumed += refill(stream, nowMillis, refillBudget);
      if (queue.pollFirst() == null) unservedRequests++;
      consumed += refill(stream, nowMillis, 1);
      return consumed;
    }

    private int refill(CandidateStream stream, long nowMillis, int budget) {
      int consumed = 0;
      for (int i = 0; i < budget && queue.size() < cap; i++) {
        long packed = stream.nextUniform();
        consumed++;
        chunkMaterializations++;
        if (!stream.safeGround(packed)) continue;
        queue.addLast(
            heavyweightEntry(
                CandidateStream.unpackX(packed), CandidateStream.unpackZ(packed), nowMillis));
      }
      return consumed;
    }

    @Override
    public long retainedEntries() {
      return queue.size();
    }

    @Override
    public long chunkMaterializations() {
      return chunkMaterializations;
    }

    @Override
    public long unservedRequests() {
      return unservedRequests;
    }
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Coarse-2D failed-area memory with a wall-clock TTL, plus a bounded warm cache and in-chunk
   * column rescan. Reconstructed from published configuration semantics of that design class
   * ({@code location_cache} depth, {@code failed_area_memory} with a 15-minute expiry cleared on
   * reload, {@code chunk_scan_columns} alternate columns inside an already-materialised chunk).
   *
   * <p>Two properties drive its allocation profile, and both follow from the representation rather
   * than from any implementation detail we are guessing at. The unit of knowledge is a map entry, so
   * memory and coverage are the same axis - which is what forces the TTL. And the TTL means the
   * entry churns: every remembered failure is allocated, expires, and is allocated again, forever.
   */
  static final class TtlAreaMemory implements MemoryModel {
    private final Map<Long, Long> failedAreas = new HashMap<>();
    private final ArrayDeque<Object[]> cache = new ArrayDeque<>();
    private final int cacheCap;
    private final int refillBudget;
    private final int scanColumns;
    private final long ttlMillis;
    private final long sweepIntervalMillis;
    private long nextSweep;
    private long chunkMaterializations;
    private long expiredEntries;
    private long unservedRequests;

    TtlAreaMemory(
        int cacheCap, int refillBudget, int scanColumns, long ttlMillis, long sweepIntervalMillis) {
      this.cacheCap = cacheCap;
      this.refillBudget = refillBudget;
      this.scanColumns = scanColumns;
      this.ttlMillis = ttlMillis;
      this.sweepIntervalMillis = sweepIntervalMillis;
    }

    @Override
    public String name() {
      return "TTL coarse-2D memory + warm cache (strategy class)";
    }

    @Override
    public int serve(CandidateStream stream, long nowMillis) {
      int consumed = 0;
      if (cache.isEmpty()) consumed += refill(stream, nowMillis, refillBudget);
      if (cache.pollFirst() == null) unservedRequests++;
      consumed += refill(stream, nowMillis, 1);
      sweep(nowMillis);
      return consumed;
    }

    private int refill(CandidateStream stream, long nowMillis, int budget) {
      int consumed = 0;
      for (int i = 0; i < budget && cache.size() < cacheCap; i++) {
        long packed = stream.nextUniform();
        consumed++;
        // Boxed lookup: the key must be autoboxed to be asked about, on every single probe.
        Long expiry = failedAreas.get(packed);
        if (expiry != null && expiry > nowMillis) continue;
        chunkMaterializations++;
        if (!stream.safeGround(packed)) {
          // Alternate columns inside the chunk already paid for. Correlated terrain means they
          // mostly share the verdict, so this optimises at the wrong scale - but it is what the
          // design does, so it is what is modelled.
          for (int c = 0; c < scanColumns; c++) {
            if (stream.safeGround(packed)) break;
          }
          failedAreas.put(packed, nowMillis + ttlMillis);
          continue;
        }
        cache.addLast(
            heavyweightEntry(
                CandidateStream.unpackX(packed), CandidateStream.unpackZ(packed), nowMillis));
      }
      return consumed;
    }

    /** Wall-clock expiry over the whole map: allocates an iterator and discards live knowledge. */
    private void sweep(long nowMillis) {
      if (nowMillis < nextSweep) return;
      nextSweep = nowMillis + sweepIntervalMillis;
      Iterator<Map.Entry<Long, Long>> it = failedAreas.entrySet().iterator();
      while (it.hasNext()) {
        if (it.next().getValue() <= nowMillis) {
          it.remove();
          expiredEntries++;
        }
      }
    }

    @Override
    public long retainedEntries() {
      return (long) failedAreas.size() + cache.size();
    }

    @Override
    public long chunkMaterializations() {
      return chunkMaterializations;
    }

    long expiredEntries() {
      return expiredEntries;
    }

    @Override
    public long unservedRequests() {
      return unservedRequests;
    }
  }
}
