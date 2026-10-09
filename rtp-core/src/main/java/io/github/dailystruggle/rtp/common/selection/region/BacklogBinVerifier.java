package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.anvil.AnvilIoPool;
import io.github.dailystruggle.rtp.anvil.ChunkCoord;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry.Provider.Decision;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Batched backlog verification: one {@link Provider#classifyBatch} call per region-file bin,
 * dispatched to {@link AnvilIoPool} so the async pulse never blocks on region-file I/O (S-005).
 *
 * <p>Each {@link #pulse} first applies every finished batch on the calling (pulse) thread - all
 * validity writes, stats and bad-chunk marks happen there, never on an I/O thread - then submits
 * the oldest unclaimed bins until {@link #depth()} batches are in flight. Depth 2 reads the next
 * region file while the previous result waits to be applied (read-ahead along backlog order).
 * Depth 0 classifies inline on the pulse thread (one bin per pulse).</p>
 *
 * <p>Bins are claimed world-wide in {@link WorldBacklogBinIndex} so regions sharing a world never
 * read the same file concurrently. A batch exceeding {@link #TIMEOUT_NANOS} is abandoned and its
 * entries fall back to live load as UNKNOWN (S-004). Not thread-safe beyond its own monitor.</p>
 */
final class BacklogBinVerifier {

  /** JVM flag for A/B runs: batches in flight per region (0 = inline, default 2). */
  static final String DEPTH_PROPERTY = "rtp.backlog.inflightDepth";
  private static final int DEFAULT_DEPTH = 2;
  private static final int MAX_DEPTH = 8;

  /** Abandon a batch, and expire a world-wide bin claim, after this long. */
  static final long TIMEOUT_NANOS =
      Math.max(1L, Long.getLong("rtp.backlog.batchTimeoutMillis", 10_000L)) * 1_000_000L;

  private static final AtomicLong SUBMITTED = new AtomicLong();
  private static final AtomicLong APPLIED = new AtomicLong();
  private static final AtomicLong TIMED_OUT = new AtomicLong();
  private static final AtomicLong CHUNKS = new AtomicLong();

  private final Executor executor;
  private final ArrayDeque<Batch> inFlight = new ArrayDeque<>();

  private record Batch(
      RegionFileCoord key,
      WorldBacklogBinIndex index,
      List<BacklogLocationBuffer.BacklogEntry> entries,
      CompletableFuture<Map<ChunkCoord, Decision>> future,
      long submittedNanos) {}

  BacklogBinVerifier() {
    this(null);
  }

  /** @param executor batch executor; {@code null} resolves {@link AnvilIoPool#get()} per submit */
  BacklogBinVerifier(Executor executor) {
    this.executor = executor;
  }

  /** Configured in-flight depth, clamped to {@code [0, 8]}. */
  static int depth() {
    int d = Integer.getInteger(DEPTH_PROPERTY, DEFAULT_DEPTH);
    return Math.max(0, Math.min(MAX_DEPTH, d));
  }

  /**
   * One verification step. {@code provider == null} keeps the provider-less behaviour: the oldest
   * unverified bin is validated without disk reads.
   *
   * @param onReject invoked on the pulse thread for each entry a batch invalidates
   */
  synchronized void pulse(RTPWorld<?> world, BacklogLocationBuffer backlog, WorldBacklogBinIndex index,
                          Provider provider, Consumer<BacklogLocationBuffer.BacklogEntry> onReject) {
    long now = System.nanoTime();
    drain(now, onReject);
    if (backlog == null || index == null) return;
    if (provider == null) {
      BacklogLocationBuffer.BacklogEntry oldest = backlog.peekOldestUnverified(e -> claimed(index, e, now));
      if (oldest == null) return;
      for (BacklogLocationBuffer.BacklogEntry e : binEntries(index, oldest)) {
        e.setValidity(BacklogLocationBuffer.Validity.VALIDATED);
      }
      return;
    }
    int depth = depth();
    if (depth == 0) {
      BacklogLocationBuffer.BacklogEntry oldest = backlog.peekOldestUnverified(e -> claimed(index, e, now));
      if (oldest == null) return;
      RegionFileCoord key = RegionFileCoord.of(oldest.location().coords());
      if (!index.tryClaim(key, now, TIMEOUT_NANOS)) return;
      try {
        List<BacklogLocationBuffer.BacklogEntry> entries = binEntries(index, oldest);
        apply(entries, classify(provider, world, chunksOf(entries)), onReject);
      } finally {
        index.release(key);
      }
      return;
    }
    while (inFlight.size() < depth) {
      BacklogLocationBuffer.BacklogEntry oldest = backlog.peekOldestUnverified(e -> claimed(index, e, now));
      if (oldest == null) return;
      RegionFileCoord key = RegionFileCoord.of(oldest.location().coords());
      if (!index.tryClaim(key, now, TIMEOUT_NANOS)) return;
      List<BacklogLocationBuffer.BacklogEntry> entries = binEntries(index, oldest);
      List<ChunkCoord> chunks = chunksOf(entries);
      CompletableFuture<Map<ChunkCoord, Decision>> future;
      try {
        Executor exec = (executor != null) ? executor : AnvilIoPool.get();
        future = CompletableFuture.supplyAsync(() -> classify(provider, world, chunks), exec);
      } catch (RuntimeException e) {
        // Rejected submit: fail closed to UNKNOWN (live load) rather than leave the bin stuck.
        future = CompletableFuture.completedFuture(Collections.emptyMap());
      }
      SUBMITTED.incrementAndGet();
      inFlight.addLast(new Batch(key, index, entries, future, now));
    }
  }

  /** Applies finished batches; abandons ones past the timeout as UNKNOWN. */
  private void drain(long now, Consumer<BacklogLocationBuffer.BacklogEntry> onReject) {
    for (Iterator<Batch> it = inFlight.iterator(); it.hasNext(); ) {
      Batch b = it.next();
      Map<ChunkCoord, Decision> results;
      if (b.future().isDone()) {
        // Done: getNow never blocks.
        results = b.future().isCompletedExceptionally()
            ? Collections.emptyMap() : b.future().getNow(Collections.emptyMap());
        APPLIED.incrementAndGet();
      } else if (now - b.submittedNanos() > TIMEOUT_NANOS) {
        b.future().cancel(false);
        TIMED_OUT.incrementAndGet();
        RTP.log(Level.WARNING, "[RTP] Backlog batch for region file " + b.key() + " exceeded "
            + (TIMEOUT_NANOS / 1_000_000L) + " ms; " + b.entries().size()
            + " candidate(s) fall back to live load");
        results = Collections.emptyMap();
      } else {
        continue;
      }
      it.remove();
      b.index().release(b.key());
      apply(b.entries(), results, onReject);
    }
  }

  /** Cancels in-flight batches and releases their claims; entries stay UNVERIFIED for re-pick. */
  synchronized void close() {
    for (Batch b : inFlight) {
      b.future().cancel(false);
      b.index().release(b.key());
    }
    inFlight.clear();
  }

  /** Batches currently in flight. Test/diagnostic hook. */
  synchronized int inFlight() {
    return inFlight.size();
  }

  private static Map<ChunkCoord, Decision> classify(Provider provider, RTPWorld<?> world, List<ChunkCoord> chunks) {
    try {
      Map<ChunkCoord, Decision> out = provider.classifyBatch(world, chunks);
      CHUNKS.addAndGet(chunks.size());
      return (out == null) ? Collections.emptyMap() : out;
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] Backlog classifyBatch failed; batch falls back to live load: " + t);
      return Collections.emptyMap();
    }
  }

  private static void apply(List<BacklogLocationBuffer.BacklogEntry> entries, Map<ChunkCoord, Decision> results,
                            Consumer<BacklogLocationBuffer.BacklogEntry> onReject) {
    for (BacklogLocationBuffer.BacklogEntry e : entries) {
      if (e.validity() != BacklogLocationBuffer.Validity.UNVERIFIED) continue;
      Decision d = results.get(chunkOf(e));
      if (d == Decision.REJECT) {
        e.setValidity(BacklogLocationBuffer.Validity.INVALIDATED);
        // Shared world bins hold other regions' entries; the owner learns, not the verifier.
        Consumer<BacklogLocationBuffer.BacklogEntry> sink = e.ownerOnReject();
        if (sink == null) sink = onReject;
        if (sink != null) sink.accept(e);
      } else {
        // ACCEPT, UNKNOWN, missing or failed: live load stays authoritative.
        e.setValidity(BacklogLocationBuffer.Validity.VALIDATED);
      }
    }
  }

  private static boolean claimed(WorldBacklogBinIndex index, BacklogLocationBuffer.BacklogEntry e, long now) {
    return index.isClaimed(RegionFileCoord.of(e.location().coords()), now, TIMEOUT_NANOS);
  }

  /** Unverified entries of {@code oldest}'s bin, always including {@code oldest} itself. */
  private static List<BacklogLocationBuffer.BacklogEntry> binEntries(
      WorldBacklogBinIndex index, BacklogLocationBuffer.BacklogEntry oldest) {
    List<BacklogLocationBuffer.BacklogEntry> out = new ArrayList<>();
    boolean sawOldest = false;
    for (BacklogLocationBuffer.BacklogEntry e : index.snapshot(RegionFileCoord.of(oldest.location().coords()))) {
      if (e.validity() != BacklogLocationBuffer.Validity.UNVERIFIED) continue;
      out.add(e);
      sawOldest |= (e == oldest);
    }
    if (!sawOldest) out.add(oldest);
    return out;
  }

  private static List<ChunkCoord> chunksOf(List<BacklogLocationBuffer.BacklogEntry> entries) {
    LinkedHashSet<ChunkCoord> out = new LinkedHashSet<>();
    for (BacklogLocationBuffer.BacklogEntry e : entries) out.add(chunkOf(e));
    return List.copyOf(out);
  }

  private static ChunkCoord chunkOf(BacklogLocationBuffer.BacklogEntry e) {
    RTPCoords c = e.location().coords();
    return new ChunkCoord(c.x() >> 4, c.z() >> 4);
  }

  /** Cumulative batches submitted to the I/O pool. */
  static long submitted() {
    return SUBMITTED.get();
  }

  /** Cumulative batches applied after completing. */
  static long applied() {
    return APPLIED.get();
  }

  /** Cumulative batches abandoned past {@link #TIMEOUT_NANOS}. */
  static long timedOut() {
    return TIMED_OUT.get();
  }

  /** Cumulative chunks classified through {@link Provider#classifyBatch}. */
  static long chunksClassified() {
    return CHUNKS.get();
  }

  /** Zeros the counters. Test hook. */
  static void resetStats() {
    SUBMITTED.set(0);
    APPLIED.set(0);
    TIMED_OUT.set(0);
    CHUNKS.set(0);
  }
}
