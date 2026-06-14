package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

/**
 * Lightweight allocation/dispatch counters for hunting CompletableFuture leaks.
 *
 * <p>Added 2026-05-08 to pinpoint the source of the heap-histogram signature
 * observed on Fabric (~96 M {@link java.util.concurrent.CompletableFuture},
 * ~94 M {@code CoCompletion}, ~48 M {@code BiApply}, ~46 M {@code BiRelay} in
 * 117 s of uptime). Both the {@code BiApply}/{@code BiRelay} ratio and the
 * fact that the only call sites in {@code rtp-core} producing those node
 * shapes are {@link java.util.concurrent.CompletableFuture#allOf} fan-outs
 * point at the neighbour-grid loads in {@code PregenTask} / {@code QueueTask},
 * but the multiplier (the rate at which those fan-outs are issued) was not
 * directly observable. This class makes that rate visible.</p>
 *
 * <p>Counters are bumped from a small set of well-known hot paths and are
 * read by a self-scheduled periodic INFO dump. The log prefix
 * {@code [RTP][CFDIAG]} is deliberately searchable so a future commit can
 * batch-demote to FINE (or remove) once the leak is closed and the data is
 * captured.</p>
 *
 * <p><b>Cost.</b> {@link LongAdder#increment()} is sub-nanosecond on amd64;
 * one increment per allOf dispatch and one per attempt is well below the
 * noise floor of the surrounding chunk-I/O cost. The periodic dump is a
 * single async task scheduled every 10 s.</p>
 *
 * <p><b>Thread safety.</b> All counters are {@link LongAdder}; the periodic
 * dump runs on RTP's async scheduler. {@link #ensureStarted()} uses CAS so
 * the dump is started exactly once across all caller threads.</p>
 *
 * <p>Search-and-replace targets when retiring this scaffolding:
 * <ul>
 *   <li>{@code CfDiag.}</li>
 *   <li>{@code [RTP][CFDIAG]}</li>
 * </ul>
 */
public final class CfDiag {

    /** Hot-path counters. Increment from the documented call sites only. */
    public static final LongAdder pregenAttemptStart       = new LongAdder();
    /** PregenTask allOf dispatch count. */
    public static final LongAdder pregenAllOfDispatch      = new LongAdder();
    /** PregenTask allOf timeout count. */
    public static final LongAdder pregenAllOfTimeout       = new LongAdder();
    /** QueueTask allOf dispatch count. */
    public static final LongAdder queueAllOfDispatch       = new LongAdder();
    /** QueueTask allOf timeout count. */
    public static final LongAdder queueAllOfTimeout        = new LongAdder();
    /** RegionCacheTask issue count. */
    public static final LongAdder regionCacheTaskIssue     = new LongAdder();
    /** LocationGenerator pregen entry count. */
    public static final LongAdder locationGenPregenEntry   = new LongAdder();
    /** LocationGenerator queue entry count. */
    public static final LongAdder locationGenQueueEntry    = new LongAdder();
    /** PregenTask attempt resubmits via {@code rescheduleNextAttempt} (the retry-storm proxy). */
    public static final LongAdder pregenReschedule         = new LongAdder();

    // -- Widened instrumentation (2026-05-08, second pass) --------------------
    // Each ChunkSet(...) ctor wires an internal CompletableFuture.allOf over its
    // chunk futures (rtp-api/.../ChunkSet.java:21). That allOf is the smallest
    // BiApply/BiRelay-shaped construct in the codebase, so per-callsite ChunkSet
    // ctor rates fingerprint which call path multiplies CFs.
    /** PregenTask single-chunk live-load reservation ChunkSet ctor count. */
    public static final LongAdder chunkSetPregenLive       = new LongAdder();
    /** PregenTask multi-chunk neighbour reservation ChunkSet ctor count. */
    public static final LongAdder chunkSetPregenVerified   = new LongAdder();
    /** QueueTask live-load ChunkSet ctor count. */
    public static final LongAdder chunkSetQueueLive        = new LongAdder();
    /** QueueTask view-distance set ChunkSet ctor count. */
    public static final LongAdder chunkSetQueueVd          = new LongAdder();
    /** Region ChunkSet ctor count. */
    public static final LongAdder chunkSetRegion           = new LongAdder();
    /** RegionCacheTask ChunkSet ctor count. */
    public static final LongAdder chunkSetRegionCache      = new LongAdder();
    /** TeleportPipelineTask ChunkSet ctor count. */
    public static final LongAdder chunkSetPipeline         = new LongAdder();
    /** FabricRTPWorld.getChunkAtAsync ChunkSet ctor count. */
    public static final LongAdder chunkSetFabricGetChunk   = new LongAdder();
    /** Fabric per-call entry counter for getChunkAtAsync (one ChunkSet allocation per call). */
    public static final LongAdder fabricGetChunkAtAsync    = new LongAdder();
    /** Fabric loadLiveChunk entry. */
    public static final LongAdder fabricLoadLiveChunk      = new LongAdder();

    // -- Third pass (2026-05-08, idle-leak hunt) ------------------------------
    // CFDIAG showed all instrumented sites near zero while heap allocated
    // ~6 M CFs/sec with 0 players online. The remaining hot driver with NO
    // instrumentation is ScanTask: ScanTask.run() iterates testPos() per
    // spiral position, gated only by inFlightGate (MAX_PENDING_CHUNKS
    // permits). Each testPos -> runFullLoadPath does
    // getOrLoadChunk(cx,cz).orTimeout(30 s).whenComplete(...) — a 30 s
    // pinning ceiling and a chain of CFs per call. Region.execute's
    // cold-promote deficit loop is also a candidate. Instrument both.
    /** ScanTask.run() entry count. */
    public static final LongAdder scanRunBatch            = new LongAdder();
    /** ScanTask.testPos entry count. */
    public static final LongAdder scanTestPos             = new LongAdder();
    /** ScanTask.runFullLoadPath entry count. */
    public static final LongAdder scanRunFullLoad         = new LongAdder();
    /** Region.execute pulse entry count. */
    public static final LongAdder regionExecute           = new LongAdder();
    /** Region.execute deficit-loop getChunkAtAsync dispatch count. */
    public static final LongAdder regionDeficitDispatch   = new LongAdder();

    /** Snapshot of last-emitted totals for delta computation. */
    private static long s_pregenAttemptStart, s_pregenAllOfDispatch, s_pregenAllOfTimeout;
    private static long s_queueAllOfDispatch, s_queueAllOfTimeout, s_regionCacheTaskIssue;
    private static long s_locationGenPregenEntry, s_locationGenQueueEntry, s_pregenReschedule;
    private static long s_chunkSetPregenLive, s_chunkSetPregenVerified;
    private static long s_chunkSetQueueLive, s_chunkSetQueueVd;
    private static long s_chunkSetRegion, s_chunkSetRegionCache, s_chunkSetPipeline;
    private static long s_chunkSetFabricGetChunk, s_fabricGetChunkAtAsync, s_fabricLoadLiveChunk;
    private static long s_scanRunBatch, s_scanTestPos, s_scanRunFullLoad;
    private static long s_regionExecute, s_regionDeficitDispatch;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong LAST_DUMP_NANOS = new AtomicLong(System.nanoTime());

    /**
     * Period between dumps in <b>ticks</b> (matches the {@code RTPScheduler}
     * unit, see {@code PerformanceTracker.start}). 200 ticks = 10 s gives
     * enough samples to see a runaway without flooding logs.
     */
    private static final long DUMP_PERIOD_TICKS = 200L;

    private CfDiag() {}

    /**
     * Idempotent. First caller wins and schedules the periodic dump on RTP's
     * async scheduler. Subsequent calls are O(1) load + CAS-fail.
     */
    public static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            RTP.serverAccessor.getScheduler().runTaskTimerAsynchronously(
                    CfDiag::dumpOnce, DUMP_PERIOD_TICKS, DUMP_PERIOD_TICKS);
        } catch (Throwable t) {
            // Fall back: scheduler not ready (early init). Reset so a later
            // caller can re-arm. Best-effort; never propagate.
            STARTED.set(false);
        }
    }

    private static void dumpOnce() {
        long nowNanos = System.nanoTime();
        long lastNanos = LAST_DUMP_NANOS.getAndSet(nowNanos);
        double elapsedSec = Math.max(1e-9, (nowNanos - lastNanos) / 1e9);

        long n_pregenAttemptStart     = pregenAttemptStart.sum();
        long n_pregenAllOfDispatch    = pregenAllOfDispatch.sum();
        long n_pregenAllOfTimeout     = pregenAllOfTimeout.sum();
        long n_queueAllOfDispatch     = queueAllOfDispatch.sum();
        long n_queueAllOfTimeout      = queueAllOfTimeout.sum();
        long n_regionCacheTaskIssue   = regionCacheTaskIssue.sum();
        long n_locationGenPregenEntry = locationGenPregenEntry.sum();
        long n_locationGenQueueEntry  = locationGenQueueEntry.sum();
        long n_pregenReschedule       = pregenReschedule.sum();
        long n_chunkSetPregenLive     = chunkSetPregenLive.sum();
        long n_chunkSetPregenVerified = chunkSetPregenVerified.sum();
        long n_chunkSetQueueLive      = chunkSetQueueLive.sum();
        long n_chunkSetQueueVd        = chunkSetQueueVd.sum();
        long n_chunkSetRegion         = chunkSetRegion.sum();
        long n_chunkSetRegionCache    = chunkSetRegionCache.sum();
        long n_chunkSetPipeline       = chunkSetPipeline.sum();
        long n_chunkSetFabricGetChunk = chunkSetFabricGetChunk.sum();
        long n_fabricGetChunkAtAsync  = fabricGetChunkAtAsync.sum();
        long n_fabricLoadLiveChunk    = fabricLoadLiveChunk.sum();
        long n_scanRunBatch           = scanRunBatch.sum();
        long n_scanTestPos            = scanTestPos.sum();
        long n_scanRunFullLoad        = scanRunFullLoad.sum();
        long n_regionExecute          = regionExecute.sum();
        long n_regionDeficitDispatch  = regionDeficitDispatch.sum();

        long d_pregenAttemptStart     = n_pregenAttemptStart     - s_pregenAttemptStart;
        long d_pregenAllOfDispatch    = n_pregenAllOfDispatch    - s_pregenAllOfDispatch;
        long d_pregenAllOfTimeout     = n_pregenAllOfTimeout     - s_pregenAllOfTimeout;
        long d_queueAllOfDispatch     = n_queueAllOfDispatch     - s_queueAllOfDispatch;
        long d_queueAllOfTimeout      = n_queueAllOfTimeout      - s_queueAllOfTimeout;
        long d_regionCacheTaskIssue   = n_regionCacheTaskIssue   - s_regionCacheTaskIssue;
        long d_locationGenPregenEntry = n_locationGenPregenEntry - s_locationGenPregenEntry;
        long d_locationGenQueueEntry  = n_locationGenQueueEntry  - s_locationGenQueueEntry;
        long d_pregenReschedule       = n_pregenReschedule       - s_pregenReschedule;
        long d_chunkSetPregenLive     = n_chunkSetPregenLive     - s_chunkSetPregenLive;
        long d_chunkSetPregenVerified = n_chunkSetPregenVerified - s_chunkSetPregenVerified;
        long d_chunkSetQueueLive      = n_chunkSetQueueLive      - s_chunkSetQueueLive;
        long d_chunkSetQueueVd        = n_chunkSetQueueVd        - s_chunkSetQueueVd;
        long d_chunkSetRegion         = n_chunkSetRegion         - s_chunkSetRegion;
        long d_chunkSetRegionCache    = n_chunkSetRegionCache    - s_chunkSetRegionCache;
        long d_chunkSetPipeline       = n_chunkSetPipeline       - s_chunkSetPipeline;
        long d_chunkSetFabricGetChunk = n_chunkSetFabricGetChunk - s_chunkSetFabricGetChunk;
        long d_fabricGetChunkAtAsync  = n_fabricGetChunkAtAsync  - s_fabricGetChunkAtAsync;
        long d_fabricLoadLiveChunk    = n_fabricLoadLiveChunk    - s_fabricLoadLiveChunk;
        long d_scanRunBatch           = n_scanRunBatch           - s_scanRunBatch;
        long d_scanTestPos            = n_scanTestPos            - s_scanTestPos;
        long d_scanRunFullLoad        = n_scanRunFullLoad        - s_scanRunFullLoad;
        long d_regionExecute          = n_regionExecute          - s_regionExecute;
        long d_regionDeficitDispatch  = n_regionDeficitDispatch  - s_regionDeficitDispatch;

        s_pregenAttemptStart     = n_pregenAttemptStart;
        s_pregenAllOfDispatch    = n_pregenAllOfDispatch;
        s_pregenAllOfTimeout     = n_pregenAllOfTimeout;
        s_queueAllOfDispatch     = n_queueAllOfDispatch;
        s_queueAllOfTimeout      = n_queueAllOfTimeout;
        s_regionCacheTaskIssue   = n_regionCacheTaskIssue;
        s_locationGenPregenEntry = n_locationGenPregenEntry;
        s_locationGenQueueEntry  = n_locationGenQueueEntry;
        s_pregenReschedule       = n_pregenReschedule;
        s_chunkSetPregenLive     = n_chunkSetPregenLive;
        s_chunkSetPregenVerified = n_chunkSetPregenVerified;
        s_chunkSetQueueLive      = n_chunkSetQueueLive;
        s_chunkSetQueueVd        = n_chunkSetQueueVd;
        s_chunkSetRegion         = n_chunkSetRegion;
        s_chunkSetRegionCache    = n_chunkSetRegionCache;
        s_chunkSetPipeline       = n_chunkSetPipeline;
        s_chunkSetFabricGetChunk = n_chunkSetFabricGetChunk;
        s_fabricGetChunkAtAsync  = n_fabricGetChunkAtAsync;
        s_fabricLoadLiveChunk    = n_fabricLoadLiveChunk;
        s_scanRunBatch           = n_scanRunBatch;
        s_scanTestPos            = n_scanTestPos;
        s_scanRunFullLoad        = n_scanRunFullLoad;
        s_regionExecute          = n_regionExecute;
        s_regionDeficitDispatch  = n_regionDeficitDispatch;

        // Skip the noise of an all-zero line. If literally nothing happened in
        // the last 10 s, no operator wants to grep around 0,0,0,0...
        if ((d_pregenAttemptStart | d_pregenAllOfDispatch | d_pregenAllOfTimeout
                | d_queueAllOfDispatch | d_queueAllOfTimeout | d_regionCacheTaskIssue
                | d_locationGenPregenEntry | d_locationGenQueueEntry | d_pregenReschedule
                | d_chunkSetPregenLive | d_chunkSetPregenVerified
                | d_chunkSetQueueLive | d_chunkSetQueueVd
                | d_chunkSetRegion | d_chunkSetRegionCache | d_chunkSetPipeline
                | d_chunkSetFabricGetChunk | d_fabricGetChunkAtAsync
                | d_fabricLoadLiveChunk
                | d_scanRunBatch | d_scanTestPos | d_scanRunFullLoad
                | d_regionExecute | d_regionDeficitDispatch) == 0L) {
            return;
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("[RTP][CFDIAG] window=").append(String.format("%.2fs", elapsedSec))
          .append(" locGen{pregen=").append(rate(d_locationGenPregenEntry, elapsedSec))
          .append("/s queue=").append(rate(d_locationGenQueueEntry, elapsedSec))
          .append("/s} regionCache=").append(rate(d_regionCacheTaskIssue, elapsedSec))
          .append("/s pregen{attempt=").append(rate(d_pregenAttemptStart, elapsedSec))
          .append("/s reschedule=").append(rate(d_pregenReschedule, elapsedSec))
          .append("/s allOf=").append(rate(d_pregenAllOfDispatch, elapsedSec))
          .append("/s timeout=").append(rate(d_pregenAllOfTimeout, elapsedSec))
          .append("/s} queue{allOf=").append(rate(d_queueAllOfDispatch, elapsedSec))
          .append("/s timeout=").append(rate(d_queueAllOfTimeout, elapsedSec))
          .append("/s} chunkSet{pregenLive=").append(rate(d_chunkSetPregenLive, elapsedSec))
          .append("/s pregenVer=").append(rate(d_chunkSetPregenVerified, elapsedSec))
          .append("/s queueLive=").append(rate(d_chunkSetQueueLive, elapsedSec))
          .append("/s queueVd=").append(rate(d_chunkSetQueueVd, elapsedSec))
          .append("/s region=").append(rate(d_chunkSetRegion, elapsedSec))
          .append("/s regionCache=").append(rate(d_chunkSetRegionCache, elapsedSec))
          .append("/s pipeline=").append(rate(d_chunkSetPipeline, elapsedSec))
          .append("/s fabricGCA=").append(rate(d_chunkSetFabricGetChunk, elapsedSec))
          .append("/s} fabric{getChunkAtAsync=").append(rate(d_fabricGetChunkAtAsync, elapsedSec))
          .append("/s loadLiveChunk=").append(rate(d_fabricLoadLiveChunk, elapsedSec))
          .append("/s} scan{run=").append(rate(d_scanRunBatch, elapsedSec))
          .append("/s testPos=").append(rate(d_scanTestPos, elapsedSec))
          .append("/s fullLoad=").append(rate(d_scanRunFullLoad, elapsedSec))
          .append("/s} region{execute=").append(rate(d_regionExecute, elapsedSec))
          .append("/s deficitDispatch=").append(rate(d_regionDeficitDispatch, elapsedSec))
          .append("/s} totals{pregenAttempt=").append(n_pregenAttemptStart)
          .append(" pregenAllOf=").append(n_pregenAllOfDispatch)
          .append(" queueAllOf=").append(n_queueAllOfDispatch)
          .append(" regionCache=").append(n_regionCacheTaskIssue)
          .append(" chunkSetPregenVer=").append(n_chunkSetPregenVerified)
          .append(" chunkSetQueueVd=").append(n_chunkSetQueueVd)
          .append(" chunkSetFabricGCA=").append(n_chunkSetFabricGetChunk)
          .append(" fabricGetChunkAtAsync=").append(n_fabricGetChunkAtAsync)
          .append(" fabricLoadLiveChunk=").append(n_fabricLoadLiveChunk)
          .append(" scanTestPos=").append(n_scanTestPos)
          .append(" scanRunFullLoad=").append(n_scanRunFullLoad)
          .append(" regionExecute=").append(n_regionExecute)
          .append(" regionDeficitDispatch=").append(n_regionDeficitDispatch)
          .append("}");
        RTP.log(Level.FINER, sb.toString());
    }

    private static String rate(long delta, double elapsedSec) {
        double r = delta / elapsedSec;
        if (r >= 1000.0)        return String.format("%.0f", r);
        else if (r >= 10.0)     return String.format("%.1f", r);
        else                    return String.format("%.2f", r);
    }
}
