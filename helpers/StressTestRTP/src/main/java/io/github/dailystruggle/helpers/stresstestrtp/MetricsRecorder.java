package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-attempt CSV recorder + rolling-aggregate computer.
 *
 * <p>Columns are aligned to the front-page comparison table:
 * <ul>
 *   <li>per-attempt CSV → raw evidence</li>
 *   <li>summary.txt → cold-start, warm-queue (median), TPS-burst, MSPT, heap</li>
 * </ul>
 *
 * <p>All public methods are thread-safe. The class never touches Bukkit
 * directly - it is fed values from {@link TeleportProbe} and
 * {@link TpsMsptHeapSampler}.
 */
public class MetricsRecorder {

    /** Single attempt record. Fields mirror the CSV header. */
    public static final class Attempt {
        public final UUID attemptId;
        public final String player;
        public final String world;
        public final String targetLabel;
        public final long dispatchEpochMs;
        /** Wall-clock epoch (ms) at which the command was actually handed to
         *  {@code Bukkit.dispatchCommand} - i.e. <em>after</em> the
         *  {@code Sched.runOnPlayer} hop. On Spigot/Paper that hop is a
         *  {@code BukkitScheduler.runTask} which defers to the next tick
         *  boundary (0-50 ms, avg ~25 ms); attributing that wait to the
         *  target plugin would inflate every measurement by up to one tick.
         *  Set by {@link Runner#dispatchOne}. {@code -1} until the runnable
         *  fires; {@link #latencyMs()} falls back to {@link #dispatchEpochMs}
         *  in that window so an attempt that completes before the hop runs
         *  (impossible in practice, but defensive) still gets a finite
         *  latency. */
        public volatile long commandDispatchedEpochMs = -1L;
        public volatile long teleportEpochMs = -1L;
        public volatile boolean success = false;
        public volatile String failReason = "";
        public volatile double fromX, fromZ, toX, toZ;
        public volatile double distance = -1.0;
        public volatile double tpsAtDispatch = -1.0;
        public volatile double msptAtDispatch = -1.0;
        public volatile long heapUsedMbAtDispatch = -1L;
        /** Chunk loads attributed to this attempt by
         *  {@link ChunkLoadCounter}'s per-attempt attribution chain (Paper
         *  plugin-ticket lookup, then main-thread temporal fallback). Set by
         *  {@link ChunkLoadCounter#endAttempt} on completion or timeout.
         *  Remains {@code -1L} when no counter is wired or the attempt was
         *  never registered. Replaces the old
         *  {@code chunkLoadsAtDispatch}/{@code chunkLoadsAtComplete} snapshot
         *  pair, which double-counted concurrent attempts because the
         *  underlying counter was global. */
        public volatile long attributedChunkLoads = -1L;
        /** Chunk loads attributed to this attempt after removing the
         *  post-teleport arrival ring (the render-distance square the server
         *  loads around the player on arrival, which is plugin-independent and
         *  scales with view distance). Computed by {@link ChunkLoadCounter#endAttempt}
         *  from the recorded load coordinates and the destination. Reflects the
         *  plugin's destination-selection work (typically ~1). Falls back to
         *  {@link #attributedChunkLoads} for timeouts / failures where the
         *  destination is unknown, and remains {@code -1L} when no counter is
         *  wired. */
        public volatile long selectionChunkLoads = -1L;
        /** Inferred serving mode, derived at row-write time from this attempt's
         *  latency and {@link #selectionChunkLoads} against the phase-derived
         *  threshold in effect at that instant. Never reads plugin internals. */
        public volatile ModeClassifier.Mode servedMode = ModeClassifier.Mode.UNKNOWN;
        /** Threshold (ms) the row-write-time classification used, {@code -1L}
         *  when the phase population had not yet reached
         *  {@link ModeClassifier#MIN_SAMPLES}. Recorded per row so the
         *  classification is re-derivable from the CSV alone. */
        public volatile long servedModeThresholdMs = -1L;
        /** Direct mode reading, available only for this plugin's own arm (a
         *  competitor's cache state is not observable). {@code null} means no
         *  direct source was wired for this attempt. Reported alongside the
         *  inferred value; deliberately never used to calibrate it. */
        public volatile ModeClassifier.Mode directServedMode = null;
        /** Chunk loads attributed to this attempt that fired on a server tick
         *  thread - on Spigot/Paper the single tick thread, on Folia the region
         *  thread that owns the loaded chunk (see {@link TickThreadDetector}).
         *  This is the foreground half of the split: these loads are charged to
         *  the tick budget and are what produces the MSPT tail. Set by
         *  {@link ChunkLoadCounter#endAttempt}; {@code -1L} means NOT MEASURED. */
        public volatile long onTickChunkLoads = -1L;
        /** Chunk loads attributed to this attempt that fired off every tick
         *  thread (chunk-system / async threads). The background half of the
         *  split. {@code onTickChunkLoads + offTickChunkLoads} equals
         *  {@link #attributedChunkLoads} whenever all three are measured.
         *  {@code -1L} means NOT MEASURED. */
        public volatile long offTickChunkLoads = -1L;
        /** Folia region-context acquisitions booked against this attempt: the
         *  number of distinct occasions on which harness code for it executed
         *  on a region-owning thread (the entity-scheduler dispatch hop, and
         *  the PlayerTeleportEvent delivered on the owning region thread).
         *  Maintained by {@link FoliaRegionMonitor}; stays {@code -1L} on every
         *  non-Folia platform, where the concept does not exist, so a blank can
         *  never be read as "zero region hops". */
        public volatile long regionContextAcquisitions = -1L;
        /** Distinct region files this attempt's selection loads implied - one
         *  per 32x32 chunk bin touched, because a chunk cannot materialise
         *  without its region file being read and repeated chunks inside one
         *  bin cost one read. Set by {@link ChunkLoadCounter#endAttempt};
         *  {@code -1L} means NOT MEASURED, never "no reads". */
        public volatile long regionFileReads = -1L;
        /** Selection candidates counted into those bins. Paired with
         *  {@link #regionFileReads} in the same row so reads-per-teleport can
         *  be regressed against occupancy offline instead of assuming a batch
         *  size. {@code -1L} means NOT MEASURED. */
        public volatile long binCandidates = -1L;
        /** Largest single-bin occupancy in this attempt, so the mean is never
         *  read without its peak. {@code -1L} means NOT MEASURED. */
        public volatile long binOccupancyMax = -1L;

        /** Observed tick-thread occupancy intervals for this attempt,
         *  summarised to count / total / max rather than retained as a list.
         *  Totals alone cannot validate queue discipline - a plugin that spends
         *  8 ms once and one that spends 0.5 ms sixteen times have the same
         *  total and completely different tick tails - so the count and the
         *  widest single interval are kept alongside it.
         *
         *  <p>Guarded by {@code this} rather than atomics: the monitor is
         *  per-attempt and effectively uncontended, and it keeps the accumulator
         *  allocation-free (no {@code AtomicLong} triplet per attempt). A count
         *  of {@code 0} at row-write time is reported as the {@code -1}
         *  not-measured sentinel, since "no interval was ever observed" is not
         *  the same claim as "the plugin used no tick time". */
        private long tickIntervalCount = 0L;
        private long tickIntervalTotalNs = 0L;
        private long tickIntervalMaxNs = 0L;

        /**
         * Records one observed span of plugin work on a tick thread. Callers
         * must already have established that they are on a tick thread
         * ({@link TickThreadDetector#onTickThread()}); this method does not
         * re-check, so it stays usable from a closing burst whose thread
         * identity was captured earlier.
         *
         * <p>Zero-width and negative spans are ignored rather than clamped, so
         * a clock hiccup cannot manufacture occupancy.
         */
        public synchronized void recordTickInterval(long startNs, long endNs) {
            long width = endNs - startNs;
            if (width <= 0L) return;
            tickIntervalCount++;
            tickIntervalTotalNs += width;
            if (width > tickIntervalMaxNs) tickIntervalMaxNs = width;
        }

        public synchronized long tickIntervalCount()   { return tickIntervalCount; }
        public synchronized long tickIntervalTotalNs() { return tickIntervalTotalNs; }
        public synchronized long tickIntervalMaxNs()   { return tickIntervalMaxNs; }

        public Attempt(UUID id, String player, String world, String targetLabel, long dispatchEpochMs,
                       double fromX, double fromZ,
                       double tps, double mspt, long heapMb) {
            this.attemptId = id;
            this.player = player;
            this.world = world;
            this.targetLabel = targetLabel == null ? "" : targetLabel;
            this.dispatchEpochMs = dispatchEpochMs;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.tpsAtDispatch = tps;
            this.msptAtDispatch = mspt;
            this.heapUsedMbAtDispatch = heapMb;
        }

        long latencyMs() {
            if (teleportEpochMs <= 0) return -1L;
            // Prefer the post-scheduler-hop timestamp so we measure the
            // target plugin's work, not the 1-tick BukkitScheduler.runTask
            // wait that {@code Sched.runOnPlayer} adds on Spigot/Paper.
            long base = commandDispatchedEpochMs > 0 ? commandDispatchedEpochMs : dispatchEpochMs;
            return teleportEpochMs - base;
        }
    }

    public static final String CSV_HEADER =
            "attempt_id,player,world,target_label,dispatch_epoch_ms,teleport_epoch_ms,latency_ms,"
                    + "success,fail_reason,from_x,from_z,to_x,to_z,distance,"
                    + "tps_at_dispatch,mspt_at_dispatch,heap_used_mb_at_dispatch,"
                    + "chunks_loaded_during_attempt,chunk_load_cost_ms,chunks_selection,"
                    + "served_mode,served_mode_source,served_mode_threshold_ms,served_mode_direct,"
                    + "chunks_on_tick,chunks_off_tick,"
                    + "tick_intervals,tick_interval_total_ms,tick_interval_max_ms,"
                    + "region_context_acquisitions,"
                    // Region-file read accounting. reads are per 32x32 bin, so
                    // bin_candidates / region_file_reads is the MEASURED batch
                    // size the cost model previously assumed to be 64.
                    + "region_file_reads,bin_candidates,bin_occupancy_max";

    /** No-data sentinel documentation for the columns this recorder writes.
     *  Emitted to a sidecar {@code <stamp>-schema.txt} rather than as a
     *  {@code #} line inside the CSVs, because the analysis scripts parse the
     *  first line as the header via {@code csv.DictReader}. */
    public static final String SCHEMA_NOTES =
            "StressTestRTP CSV schema notes - no-data sentinels" + System.lineSeparator()
            + "Counts and durations: -1 means NOT MEASURED. It never means zero." + System.lineSeparator()
            + "Strings: empty means NOT AVAILABLE." + System.lineSeparator()
            + "served_mode: FAST|COLD|UNKNOWN, inferred from observable signals only" + System.lineSeparator()
            + "  (latency_ms and chunks_selection). UNKNOWN is written literally." + System.lineSeparator()
            + "served_mode_source: INFERRED (observables) or NONE. Never DIRECT: the" + System.lineSeparator()
            + "  direct reading has its own column so the two are never conflated." + System.lineSeparator()
            + "served_mode_threshold_ms: fast/cold boundary in effect when the row was" + System.lineSeparator()
            + "  written; -1 before the phase population reached the minimum sample count." + System.lineSeparator()
            + "served_mode_direct: FAST|COLD, read directly from the plugin under test." + System.lineSeparator()
            + "  Empty for every competitor arm - a competitor's cache state is not" + System.lineSeparator()
            + "  observable and is never inferred as an internal." + System.lineSeparator()
            + "phases CSV mode_threshold_ms / fast_mode_fraction / *_p50|p95|p99_ms: -1" + System.lineSeparator()
            + "  when the phase could not be split. Per-mode percentiles replace any bare" + System.lineSeparator()
            + "  mean over a bimodal population." + System.lineSeparator()
            + "chunks_on_tick / chunks_off_tick: foreground/background split of the SAME" + System.lineSeparator()
            + "  loads counted by chunks_loaded_during_attempt (per attempt) and" + System.lineSeparator()
            + "  chunks_loaded_attributed (per phase). On-tick means the load fired on a" + System.lineSeparator()
            + "  server tick thread: the single tick thread on Spigot/Paper, or the region" + System.lineSeparator()
            + "  thread owning the loaded chunk on Folia. -1 means NOT MEASURED - a" + System.lineSeparator()
            + "  competitor arm with no attribution must never read as zero foreground work." + System.lineSeparator()
            + "chunks_off_tick_share: chunks_off_tick / (on + off), per phase. This is the" + System.lineSeparator()
            + "  directly measured form of the async share that was previously derived; the" + System.lineSeparator()
            + "  pre-existing aggregate columns are unchanged and still carry the derived" + System.lineSeparator()
            + "  foreground chunk-load cost term. -1 when neither half was measured." + System.lineSeparator()
            + "tick_intervals / tick_interval_total_ms / tick_interval_max_ms: observed" + System.lineSeparator()
            + "  tick-thread occupancy as INTERVALS, not a total. Count of observed spans," + System.lineSeparator()
            + "  their sum, and the widest single span. The tail is produced by when" + System.lineSeparator()
            + "  foreground work lands relative to the tick, so a total alone cannot" + System.lineSeparator()
            + "  validate queue discipline. -1 means no interval was observed, which is" + System.lineSeparator()
            + "  NOT the same claim as zero tick time." + System.lineSeparator()
            + "tick_region_ownership: folia-region when Folia's per-chunk region-ownership" + System.lineSeparator()
            + "  query classified the loads, single-tick-thread otherwise. Empty means NOT" + System.lineSeparator()
            + "  AVAILABLE. Detected at runtime; there is no build variant or config" + System.lineSeparator()
            + "  toggle for it." + System.lineSeparator()
            + "region_context_acquisitions: Folia only. Count of distinct occasions on" + System.lineSeparator()
            + "  which harness code for the attempt (per attempt) or for the phase (per" + System.lineSeparator()
            + "  phase) executed on a region-owning thread: the entity-scheduler dispatch" + System.lineSeparator()
            + "  hop plus the PlayerTeleportEvent delivery. -1 means NOT MEASURED, which" + System.lineSeparator()
            + "  is what every non-Folia platform writes - the columns exist there so the" + System.lineSeparator()
            + "  schema is platform-independent, and Folia is detected at runtime." + System.lineSeparator()
            + "region_freeze_threshold_ms: the FIXED detection threshold, stated in the" + System.lineSeparator()
            + "  row rather than assumed. A region freeze is a wall-clock stall of at" + System.lineSeparator()
            + "  least this many ms on a Folia region thread, seen through exactly two" + System.lineSeparator()
            + "  channels: (1) tick stall - the gap between consecutive invocations of a" + System.lineSeparator()
            + "  1-tick global-region timer (nominal 50 ms); (2) hop stall - the wait for" + System.lineSeparator()
            + "  the player-owning region's entity scheduler to run a dispatch. The" + System.lineSeparator()
            + "  threshold is a compile-time constant, chosen before any run was read, and" + System.lineSeparator()
            + "  is never tuned to make a result agree with an expectation." + System.lineSeparator()
            + "region_freezes / region_freezes_tick_stall / region_freezes_hop_stall /" + System.lineSeparator()
            + "region_worst_freeze_ms: Folia only, per phase. region_freezes is the union" + System.lineSeparator()
            + "  of the two channels. -1 means NOT MEASURED (non-Folia); a Folia phase" + System.lineSeparator()
            + "  with no freeze writes 0, which IS a measurement." + System.lineSeparator()
            + "region_file_reads: distinct region files implied by an attempt's SELECTION" + System.lineSeparator()
            + "  chunk loads - one per 32x32 chunk bin touched, since a chunk cannot" + System.lineSeparator()
            + "  materialise without its region file being read and repeated chunks in" + System.lineSeparator()
            + "  one bin cost one read. It is a count IMPLIED BY OBSERVED CHUNK LOADS," + System.lineSeparator()
            + "  not an intercepted syscall count: it cannot see region bytes read" + System.lineSeparator()
            + "  WITHOUT materialising a chunk, which is exactly what this project's own" + System.lineSeparator()
            + "  prefilter does, so it under-states this plugin's avoided reads and" + System.lineSeparator()
            + "  never flatters it. -1 means NOT MEASURED." + System.lineSeparator()
            + "bin_candidates / bin_occupancy_max: selection candidates counted into those" + System.lineSeparator()
            + "  bins, and the largest single-bin occupancy. bin_candidates /" + System.lineSeparator()
            + "  region_file_reads is the MEASURED candidates-per-binned-batch that the" + System.lineSeparator()
            + "  cost model otherwise assumes to be 64. Numerator and denominator are" + System.lineSeparator()
            + "  both written per row so reads-per-teleport can be regressed against" + System.lineSeparator()
            + "  occupancy offline. -1 means NOT MEASURED." + System.lineSeparator()
            + "phases CSV storage_class: NVME|SATA_SSD|SPINNING|NETWORK|UNKNOWN for the" + System.lineSeparator()
            + "  world directory's device. UNKNOWN is written literally and never means" + System.lineSeparator()
            + "  'fast'. storage_class_method records HOW the verdict was reached next to" + System.lineSeparator()
            + "  the verdict itself; empty means NOT AVAILABLE." + System.lineSeparator()
            + "phases CSV storage_read_p50_us / p90 / p99 / max_us: measured read latency" + System.lineSeparator()
            + "  of the ACTUAL device, so a downstream model selects a cost distribution" + System.lineSeparator()
            + "  instead of assuming one. storage_read_label states what the distribution" + System.lineSeparator()
            + "  actually is: FIRST_TOUCH_UNKNOWN_PAGE_CACHE. Each sample is the" + System.lineSeparator()
            + "  profiler's first touch of a distinct region file, farthest-from-origin" + System.lineSeparator()
            + "  first, and no file is ever probed twice - so no sample measures pages" + System.lineSeparator()
            + "  the profiler itself warmed. Pages already resident from the server or a" + System.lineSeparator()
            + "  previous run still read warm and are indistinguishable (no page-cache" + System.lineSeparator()
            + "  drop is portable from a JVM), so these figures are a LOWER BOUND on true" + System.lineSeparator()
            + "  device cold-read latency. -1 means NOT MEASURED." + System.lineSeparator()
            + "phases CSV region_reads_per_attempt / bin_candidates_per_batch: per-phase" + System.lineSeparator()
            + "  reads per teleport and measured bin occupancy. -1 means NOT MEASURED." + System.lineSeparator()
            + "gc_young_collections / gc_young_time_ms / gc_old_collections /" + System.lineSeparator()
            + "gc_old_time_ms / gc_unclassified_collections / gc_total_collections /" + System.lineSeparator()
            + "gc_total_time_ms: per-phase DELTAS of GarbageCollectorMXBean counters," + System.lineSeparator()
            + "  split young/old by collector name. A collector whose generation is not" + System.lineSeparator()
            + "  in the name table is counted in the totals and in" + System.lineSeparator()
            + "  gc_unclassified_collections only, never folded into a split, so an" + System.lineSeparator()
            + "  unfamiliar collector cannot read as 'no old-gen activity'. Heap-used" + System.lineSeparator()
            + "  alone cannot separate retained from churned memory; these columns are" + System.lineSeparator()
            + "  the churn term. -1 means NOT MEASURED." + System.lineSeparator()
            + "gc_time_fraction_of_wall: gc_total_time_ms / wall_ms. Collector time is" + System.lineSeparator()
            + "  summed across parallel GC threads, so this can exceed 1.0 and is not a" + System.lineSeparator()
            + "  pause fraction. -1 means NOT MEASURED." + System.lineSeparator()
            + "tick_thread_alloc_bytes / tick_thread_alloc_bytes_per_attempt: phase delta" + System.lineSeparator()
            + "  of getThreadAllocatedBytes for the ONE recorded tick thread - not a" + System.lineSeparator()
            + "  JVM-wide allocation total. tick_alloc_scope states which thread that is:" + System.lineSeparator()
            + "  MAIN_THREAD on Spigot/Paper, FOLIA_GLOBAL_REGION_PARTIAL on Folia, where" + System.lineSeparator()
            + "  there is no single tick thread and the figure is one region thread's" + System.lineSeparator()
            + "  share. Empty scope means NOT AVAILABLE and the bytes columns are -1." + System.lineSeparator()
            + "peak_resident_chunks: peak loaded-chunk count during the phase, tracked as" + System.lineSeparator()
            + "  a load/unload delta over a one-time baseline. It is the honest observable" + System.lineSeparator()
            + "  proxy for the platform-owned retained term no JVM counter attributes to a" + System.lineSeparator()
            + "  plugin. A peak, deliberately: an average hides retention. -1 means NOT" + System.lineSeparator()
            + "  MEASURED." + System.lineSeparator()
            + "peak_plugin_tickets / peak_target_plugin_tickets: peak plugin chunk tickets" + System.lineSeparator()
            + "  held server-wide, and by the arm's target plugin alone, sampled on a" + System.lineSeparator()
            + "  low-frequency timer (never per attempt). Requires Paper's" + System.lineSeparator()
            + "  World#getPluginChunkTickets(); -1 on Spigot and on Folia, where the query" + System.lineSeparator()
            + "  is not region-safe. -1 never means zero tickets held." + System.lineSeparator()
            + "phases CSV ticket_footprint_chunks / ticket_footprint_shape: chunks the" + System.lineSeparator()
            + "  platform made resident in response to ONE plugin chunk ticket, measured" + System.lineSeparator()
            + "  once at setup before any teleport was recorded, by applying a single" + System.lineSeparator()
            + "  ticket to an unloaded chunk far from origin and counting ChunkLoadEvents" + System.lineSeparator()
            + "  around it. This is the multiplier between a cached location and its" + System.lineSeparator()
            + "  resident-chunk cost, and it is a PLATFORM decision, not a plugin one:" + System.lineSeparator()
            + "  vanilla propagates ticket levels outward, and Paper and Folia each" + System.lineSeparator()
            + "  reimplemented that subsystem. It is measured rather than assumed because" + System.lineSeparator()
            + "  assuming it scales every bytes-per-entry inference by the factor assumed." + System.lineSeparator()
            + "  shape names an exact odd square (1x1, 3x3, 5x5) or reports IRREGULAR;" + System.lineSeparator()
            + "  NONE means the ticket produced no load. -1 / empty means NOT MEASURED," + System.lineSeparator()
            + "  which is NOT the same claim as a one-chunk footprint." + System.lineSeparator()
            + "ticket_footprint_released: chunks unloaded after the probe ticket was" + System.lineSeparator()
            + "  removed. Equal to ticket_footprint_chunks means retention is bounded and" + System.lineSeparator()
            + "  symmetric; a shortfall means the ticket did not fully release and every" + System.lineSeparator()
            + "  residency figure in the run should be read as accumulating." + System.lineSeparator()
            + "ticket_probe_noise_loads: chunk loads seen OUTSIDE the attribution radius" + System.lineSeparator()
            + "  during the probe window. 0 is a measurement and means the window was" + System.lineSeparator()
            + "  quiet, so the footprint is attributable to the ticket. Non-zero means" + System.lineSeparator()
            + "  unrelated chunk traffic overlapped the window and ticket_footprint_chunks" + System.lineSeparator()
            + "  is an UPPER BOUND. -1 means no probe ran." + System.lineSeparator()
            + "ticket_footprint_heap_bytes / ticket_footprint_bytes_per_chunk: used-heap" + System.lineSeparator()
            + "  delta across the probe window and that delta per chunk loaded." + System.lineSeparator()
            + "  ticket_footprint_heap_label states what the figure is:" + System.lineSeparator()
            + "  UNCOLLECTED_ALLOCATION_INCLUSIVE. No collection is forced, because a" + System.lineSeparator()
            + "  System.gc() on a server under measurement would corrupt the GC columns" + System.lineSeparator()
            + "  recorded here, so both figures include transient allocation and are an" + System.lineSeparator()
            + "  UPPER BOUND on retained bytes rather than a settled retained set." + System.lineSeparator()
            + "  Full detail in the setup-phase sidecar ticket-footprint.txt." + System.lineSeparator()
            + "heap_pressure_events / heap_pressure_first_heap_used_mb /" + System.lineSeparator()
            + "heap_pressure_first_trigger: RECORDED EVIDENCE of a plugin's own" + System.lineSeparator()
            + "  heap-pressure control loop - the matching log line and the heap-used" + System.lineSeparator()
            + "  level at which it fired. The trigger is recorded; no behavioural response" + System.lineSeparator()
            + "  is inferred, modelled, or attributed from a match. A phase with the" + System.lineSeparator()
            + "  watcher active and no match writes 0, which IS a measurement; -1 means" + System.lineSeparator()
            + "  the watcher was not wired. Full rows live in <stamp>-heap-triggers.csv." + System.lineSeparator();

    public static final String PHASES_CSV_HEADER =
            "phase_label,start_epoch_ms,end_epoch_ms,wall_ms,attempts,successes,"
                    + "process_cpu_ms,main_thread_cpu_ms,"
                    + "cpu_ms_per_attempt_total,cpu_ms_per_attempt_main,"
                    + "chunks_loaded,chunks_loaded_attributed,chunks_loaded_background,"
                    + "chunks_per_attempt,"
                    + "chunk_load_cost_ms,cpu_ms_with_chunks,cpu_ms_with_chunks_per_attempt,"
                    + "chunks_selection,chunks_selection_per_attempt,"
                    + "mode_threshold_ms,mode_threshold_method,mode_classified_attempts,"
                    + "fast_mode_attempts,cold_mode_attempts,unknown_mode_attempts,fast_mode_fraction,"
                    + "fast_p50_ms,fast_p95_ms,fast_p99_ms,cold_p50_ms,cold_p95_ms,cold_p99_ms,"
                    + "direct_fast_mode_attempts,direct_cold_mode_attempts,direct_fast_mode_fraction,"
                    + "chunks_on_tick,chunks_off_tick,chunks_off_tick_share,"
                    + "tick_intervals,tick_interval_total_ms,tick_interval_max_ms,"
                    + "tick_region_ownership,"
                    // Folia region accounting. region_freeze_threshold_ms states the
                    // fixed detection criterion in every row so no reader has to
                    // assume it: a freeze is a >= threshold wall stall on a region
                    // thread, seen either as a gap between consecutive 1-tick
                    // global-region timer invocations (nominal 50 ms) or as the wait
                    // for a player-owning region's entity scheduler to run a dispatch.
                    // All counts are -1 off Folia, never 0.
                    + "region_context_acquisitions,region_context_acquisitions_per_attempt,"
                    + "region_freeze_threshold_ms,region_freezes,"
                    + "region_freezes_tick_stall,region_freezes_hop_stall,"
                    + "region_worst_freeze_ms,"
                    // Storage characterisation of the world directory, so the
                    // read-cost figures stop being machine-relative. The method
                    // that produced the verdict is recorded next to the verdict,
                    // and the latency label states the page-cache caveat rather
                    // than leaving it silent.
                    + "storage_class,storage_class_method,storage_filesystem,"
                    + "storage_probe_reads,storage_read_label,"
                    + "storage_read_p50_us,storage_read_p90_us,storage_read_p99_us,"
                    + "storage_read_max_us,"
                    // Region-file read accounting and measured bin occupancy.
                    + "region_file_reads,region_reads_per_attempt,"
                    + "bin_candidates,bin_candidates_per_batch,bin_occupancy_max,"
                    // GC and residency accounting. Heap-used is already sampled
                    // every 50 ms, but a heap curve cannot separate retained from
                    // churned memory: GC deltas supply the churn term, tick-thread
                    // allocation the rate that produces it, and the residency peaks
                    // the platform-owned retained term no JVM counter attributes to
                    // a plugin. Every count is -1 when not measured, never 0.
                    + "gc_young_collections,gc_young_time_ms,"
                    + "gc_old_collections,gc_old_time_ms,"
                    + "gc_unclassified_collections,"
                    + "gc_total_collections,gc_total_time_ms,gc_time_fraction_of_wall,"
                    + "tick_thread_alloc_bytes,tick_thread_alloc_bytes_per_attempt,"
                    + "tick_alloc_scope,"
                    + "peak_resident_chunks,peak_plugin_tickets,peak_target_plugin_tickets,"
                    // Setup-phase ticket-footprint calibration. Constant for the
                    // whole run by construction (measured once, before any
                    // teleport), and repeated on every phase row so a row is
                    // interpretable on its own: peak_resident_chunks cannot be
                    // converted into a per-cached-location cost without it.
                    + "ticket_footprint_chunks,ticket_footprint_shape,"
                    + "ticket_footprint_released,ticket_probe_noise_loads,"
                    + "ticket_footprint_heap_bytes,ticket_footprint_bytes_per_chunk,"
                    + "ticket_footprint_heap_label,"
                    // Heap-pressure control-loop evidence: the trigger only.
                    + "heap_pressure_events,heap_pressure_first_heap_used_mb,"
                    + "heap_pressure_first_trigger";

    private final Path csvPath;
    private final Path phasesCsvPath;
    /** Sidecar holding a periodically-refreshed snapshot of the in-flight
     *  phase, so a mid-phase server crash (e.g. a competitor plugin stalling
     *  the main thread to death) still leaves the latest partial aggregate of
     *  the phase that was running. Overwritten in place on each flush and
     *  removed when the phase closes normally. */
    private final Path partialPhaseCsvPath;
    /** Wall-clock throttle so {@link #flushPartialPhase} can be called every
     *  tick cheaply; the sidecar is only rewritten at most once per interval. */
    private static final long PARTIAL_PHASE_FLUSH_MS = 2000L;
    private volatile long lastPartialFlushMs = 0L;
    private final ConcurrentLinkedQueue<Attempt> finished = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger successes = new AtomicInteger(0);

    /** First completed (success) attempt's latency, used for cold-start. */
    private volatile long coldStartLatencyMs = -1L;

    /** Optional CPU sampler for phase-aggregate CPU/TP measurement. May be null. */
    private volatile CpuSampler cpuSampler;
    /** Optional chunk-load counter (set by the plugin on enable). May be null -
     *  in which case per-attempt and per-phase chunk columns are written empty. */
    private volatile ChunkLoadCounter chunkCounter;
    // Active phase snapshot; written to phases CSV by endPhase().
    private volatile String phaseLabel;
    private volatile long phaseStartEpochMs = -1L;
    private volatile long phaseStartProcessCpuNs = -1L;
    private volatile long phaseStartMainCpuNs = -1L;
    private volatile int phaseStartTotal = 0;
    private volatile int phaseStartSuccesses = 0;

    /** Phase-level roll-up of the per-attempt tick-occupancy intervals.
     *  Accumulated at row-write time (never on the tick thread) from each
     *  finalised attempt, so the phase row reports interval count, summed
     *  width and the widest single interval seen anywhere in the phase. The
     *  max is a max-of-maxes, which is the number that matters: it is the
     *  worst single stall the phase inflicted on a tick. */
    private final AtomicLong phaseTickIntervals = new AtomicLong();
    private final AtomicLong phaseTickIntervalTotalNs = new AtomicLong();
    private final AtomicLong phaseTickIntervalMaxNs = new AtomicLong();

    /** Optional GC / tick-thread-allocation sampler. May be null, in which
     *  case every GC and allocation column is the -1 not-measured sentinel. */
    private volatile GcSampler gcSampler;
    /** Optional residency sampler (peak resident chunks, peak plugin tickets). */
    private volatile ResidencySampler residencySampler;
    /** Optional heap-pressure trigger recorder. Records; never models. */
    private volatile HeapPressureWatcher heapPressureWatcher;
    /** Optional setup-phase ticket-footprint calibration. May be null, in
     *  which case every ticket_footprint_* column is the -1 / empty
     *  not-measured sentinel. */
    private volatile TicketFootprintProbe ticketProbe;
    /** GC / allocation counters at phase start; deltas are written per phase. */
    private volatile GcSampler.Snapshot phaseStartGc;
    /** Optional world-directory storage characteriser. May be null, in which
     *  case every storage column is the -1 / empty not-measured sentinel and
     *  no storage block sidecar is produced. */
    private volatile StorageProfiler storageProfiler;
    /** Sidecar receiving one storage header block per phase. */
    private final Path storageProfilePath;

    /** Wires the storage characteriser. The recorder only reads its published
     *  fields and asks it to probe at phase start; all I/O is the profiler's
     *  own, off-tick. */
    public void setStorageProfiler(StorageProfiler profiler) { this.storageProfiler = profiler; }

    /** Sidecar path holding the per-phase storage header blocks. */
    public Path storageProfilePath() { return storageProfilePath; }

    /** Folds one finalised attempt's interval summary into the phase roll-up. */
    private void accumulatePhaseTickIntervals(Attempt a) {
        long count = a.tickIntervalCount();
        if (count <= 0L) return;
        phaseTickIntervals.addAndGet(count);
        phaseTickIntervalTotalNs.addAndGet(a.tickIntervalTotalNs());
        long max = a.tickIntervalMaxNs();
        phaseTickIntervalMaxNs.accumulateAndGet(max, (l, r) -> Math.max(l, r));
    }

    public MetricsRecorder(Path csvPath) throws IOException {
        this.csvPath = csvPath;
        // Sibling CSV next to the main per-attempt CSV: <stamp>.csv → <stamp>-phases.csv
        String name = csvPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String phasesName = (dot > 0 ? name.substring(0, dot) : name) + "-phases"
                + (dot > 0 ? name.substring(dot) : ".csv");
        this.phasesCsvPath = csvPath.resolveSibling(phasesName);
        String partialName = (dot > 0 ? name.substring(0, dot) : name) + "-phases-partial"
                + (dot > 0 ? name.substring(dot) : ".csv");
        this.partialPhaseCsvPath = csvPath.resolveSibling(partialName);
        this.storageProfilePath = csvPath.resolveSibling(
                (dot > 0 ? name.substring(0, dot) : name) + "-storage.txt");
        Files.createDirectories(csvPath.getParent());
        Files.writeString(csvPath, CSV_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(phasesCsvPath, PHASES_CSV_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String schemaName = (dot > 0 ? name.substring(0, dot) : name) + "-schema.txt";
        Files.writeString(csvPath.resolveSibling(schemaName), SCHEMA_NOTES,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Supplies a direct serving-mode reading for an attempt. Only this
     * plugin's own arm can implement this - a competitor's cache state is not
     * observable from outside, and fabricating one is forbidden (ADR-080).
     * The reading is reported in its own CSV column and never feeds the
     * inferred classifier's threshold.
     */
    public interface DirectModeSource {
        /** Direct mode for the given attempt, or {@code null} if unavailable. */
        ModeClassifier.Mode directModeFor(Attempt a);
    }

    /** Per-phase teleport-mode classifier. A measurement phase is one target
     *  arm's window, so the derived threshold is per-arm by construction. */
    private final ModeClassifier modeClassifier = new ModeClassifier();
    private volatile DirectModeSource directModeSource = null;

    /** Wires the direct-reading source (this plugin's arm only; see
     *  {@link DirectModeSource}). {@code null} disables the direct column. */
    public void setDirectModeSource(DirectModeSource source) { this.directModeSource = source; }

    /** Wires the CPU sampler used by {@link #beginPhase}/{@link #endPhase}. */
    public void setCpuSampler(CpuSampler sampler) { this.cpuSampler = sampler; }

    /** Wires the GC / tick-thread-allocation sampler. Optional: without it
     *  every GC and allocation column writes the -1 not-measured sentinel. */
    public void setGcSampler(GcSampler sampler) { this.gcSampler = sampler; }

    /** Wires the residency sampler (peak resident chunks, peak plugin tickets). */
    public void setResidencySampler(ResidencySampler sampler) { this.residencySampler = sampler; }

    /** Wires the setup-phase ticket-footprint calibration. The recorder only
     *  reads its published fields; the probe runs once at plugin enable and is
     *  finished long before any phase begins. */
    public void setTicketFootprintProbe(TicketFootprintProbe probe) { this.ticketProbe = probe; }

    /** Wires the heap-pressure trigger recorder. It supplies evidence columns
     *  only; no behavioural response is inferred from a trigger. */
    public void setHeapPressureWatcher(HeapPressureWatcher watcher) { this.heapPressureWatcher = watcher; }

    /** Optional Folia region monitor (set by the plugin on enable). May be
     *  null - in which case every region column falls back to the {@code -1}
     *  not-measured sentinel, exactly as it does on a non-Folia server. */
    private volatile FoliaRegionMonitor regionMonitor;

    /** Wires the Folia region-context / freeze monitor. Active only when
     *  {@link Sched#isFolia()}; the columns are written regardless so the CSV
     *  schema does not depend on the platform. */
    public void setRegionMonitor(FoliaRegionMonitor monitor) { this.regionMonitor = monitor; }

    /** The wired region monitor, or {@code null}. Read by {@link Runner} to
     *  book the entity-scheduler dispatch hop. */
    public FoliaRegionMonitor regionMonitor() { return regionMonitor; }

    /** Wires the chunk-load counter used by {@link #onComplete}/{@link #onTimeout}
     *  (per-attempt deltas) and {@link #beginPhase}/{@link #endPhase} (per-phase
     *  totals). Setting to {@code null} disables chunk accounting; the
     *  corresponding CSV columns are written empty. */
    public void setChunkCounter(ChunkLoadCounter counter) { this.chunkCounter = counter; }

    /** Calibration: nanoseconds of computation per chunk-load, used to derive
     *  {@code chunk_load_cost_ms} (per-attempt) and {@code cpu_ms_with_chunks}
     *  (per-phase). Obtain by running {@code /rtp test chunk-probe-perf} on the
     *  test server and reading the {@code full avg=Nµs} field from its log
     *  output. {@code 0} (default) leaves the chunk-cost columns empty. */
    private volatile long chunkLoadCostNs = 0L;
    public void setChunkLoadCostNs(long ns) { this.chunkLoadCostNs = Math.max(0L, ns); }
    public long chunkLoadCostNs() { return chunkLoadCostNs; }
    /** Read-only accessor for the global chunk-load total. Retained for
     *  diagnostics; per-attempt attribution now flows through
     *  {@link ChunkLoadCounter#beginAttempt(Attempt)} /
     *  {@link ChunkLoadCounter#endAttempt(Attempt)} rather than dispatch /
     *  completion snapshots. Returns {@code -1L} when no counter is wired. */
    public long chunkLoadsTotal() {
        ChunkLoadCounter c = chunkCounter;
        return c == null ? -1L : c.total();
    }

    /** Optional console logger for per-attempt completion lines. When set
     *  and {@link #logSuccessful} or {@link #logFailures} is true, each
     *  completion is announced via this logger so operators can see
     *  forward progress live without tailing the CSV. */
    private volatile java.util.logging.Logger attemptLogger = null;
    private volatile boolean logSuccessful = false;
    private volatile boolean logFailures = true;
    public void setAttemptLogger(java.util.logging.Logger logger,
                                 boolean logSuccessful, boolean logFailures) {
        this.attemptLogger = logger;
        this.logSuccessful = logSuccessful;
        this.logFailures = logFailures;
    }

    /** When false, attempts are still tracked in-memory (so the runner's
     *  in-flight bookkeeping stays correct) but no CSV rows or phase rows
     *  are written. Used during JIT warm-up so warm-up dispatches don't
     *  pollute the measurement table. Defaults to true. */
    private volatile boolean recording = true;
    public void setRecording(boolean enabled) { this.recording = enabled; }
    public boolean isRecording() { return recording; }

    public Path csvPath() { return csvPath; }

    public void onDispatch(Attempt a) {
        inFlight.incrementAndGet();
        total.incrementAndGet();
        // Register the attempt with the chunk counter so that subsequent
        // ChunkLoadEvents can be attributed to it via the per-attempt
        // attribution chain (Paper plugin-ticket lookup, then main-thread
        // temporal fallback). Replaces the old
        // a.chunkLoadsAtDispatch = counter.total() snapshot, which silently
        // double-counted concurrent attempts.
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.beginAttempt(a);
        FoliaRegionMonitor rm = regionMonitor;
        if (rm != null) rm.beginAttempt(a);
    }

    /** Called by {@link TeleportProbe} when a PlayerTeleportEvent is attributed. */
    public void onComplete(Attempt a, boolean success, String failReason,
                           double toX, double toZ) {
        if (a.teleportEpochMs > 0) return; // already completed
        a.teleportEpochMs = System.currentTimeMillis();
        a.success = success;
        a.failReason = failReason == null ? "" : failReason;
        a.toX = toX;
        a.toZ = toZ;
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.endAttempt(a);
        // On Folia the PlayerTeleportEvent is delivered on the thread owning
        // the player's region, so observing the completion is itself a
        // region-context acquisition. No-op on every other platform.
        FoliaRegionMonitor rm = regionMonitor;
        if (rm != null) rm.noteAcquisition(a);
        if (success) {
            double dx = a.toX - a.fromX, dz = a.toZ - a.fromZ;
            a.distance = Math.sqrt(dx * dx + dz * dz);
            successes.incrementAndGet();
            if (coldStartLatencyMs < 0) coldStartLatencyMs = a.latencyMs();
        }
        inFlight.decrementAndGet();
        finished.add(a);
        if (recording) {
            appendRow(a);
            logCompletion(a);
        }
    }

    /** Called by {@link Runner} when an attempt times out without a teleport. */
    public void onTimeout(Attempt a) {
        if (a.teleportEpochMs > 0) return;
        a.teleportEpochMs = System.currentTimeMillis();
        a.success = false;
        a.failReason = "TIMEOUT";
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.endAttempt(a);
        inFlight.decrementAndGet();
        finished.add(a);
        if (recording) {
            appendRow(a);
            logCompletion(a);
        }
    }

    /** Console echo of a finished attempt, gated by {@link #attemptLogger}
     *  and the per-side flags. Format chosen to be one line, scannable, and
     *  immediately useful for operators tailing the server log during a long
     *  benchmark run. */
    private void logCompletion(Attempt a) {
        java.util.logging.Logger lg = attemptLogger;
        if (lg == null) return;
        if (a.success) {
            if (!logSuccessful) return;
            lg.info(String.format(java.util.Locale.ROOT,
                    "[StressTestRTP] %s -> %s OK %dms (%.0f, %.0f)",
                    a.targetLabel, a.player, a.latencyMs(), a.toX, a.toZ));
        } else {
            if (!logFailures) return;
            String reason = a.failReason == null || a.failReason.isEmpty()
                    ? "FAIL" : a.failReason;
            lg.info(String.format(java.util.Locale.ROOT,
                    "[StressTestRTP] %s -> %s %s %dms",
                    a.targetLabel, a.player, reason, a.latencyMs()));
        }
    }

    private void appendRow(Attempt a) {
        classifyMode(a);
        String chunkDelta = chunkDeltaCol(a);
        String chunkCostMs;
        long ns = chunkLoadCostNs;
        if (ns <= 0L || chunkDelta.isEmpty()) {
            chunkCostMs = "";
        } else {
            long delta = Long.parseLong(chunkDelta);
            chunkCostMs = fmt(((double) delta * (double) ns) / 1_000_000.0d);
        }
        // Read the interval summary once: three synchronized reads, off the
        // tick thread, at row-write time only.
        long tickCount = a.tickIntervalCount();
        accumulatePhaseTickIntervals(a);
        String row = String.join(",",
                a.attemptId.toString(),
                csv(a.player),
                csv(a.world),
                csv(a.targetLabel),
                Long.toString(a.dispatchEpochMs),
                Long.toString(a.teleportEpochMs),
                Long.toString(a.latencyMs()),
                Boolean.toString(a.success),
                csv(a.failReason),
                fmt(a.fromX), fmt(a.fromZ),
                fmt(a.toX), fmt(a.toZ),
                fmt(a.distance),
                fmt(a.tpsAtDispatch),
                fmt(a.msptAtDispatch),
                Long.toString(a.heapUsedMbAtDispatch),
                chunkDelta,
                chunkCostMs,
                a.selectionChunkLoads >= 0 ? Long.toString(a.selectionChunkLoads) : "",
                a.servedMode.token(),
                (a.servedMode == ModeClassifier.Mode.UNKNOWN
                        ? ModeClassifier.Source.NONE : ModeClassifier.Source.INFERRED).token(),
                Long.toString(a.servedModeThresholdMs),
                a.directServedMode == null ? "" : a.directServedMode.token(),
                // Foreground/background split of this attempt's own loads.
                Long.toString(a.onTickChunkLoads),
                Long.toString(a.offTickChunkLoads),
                // Tick-thread occupancy as intervals. Count of 0 = never
                // observed, reported as the -1 not-measured sentinel.
                tickCount > 0 ? Long.toString(tickCount) : "-1",
                tickCount > 0 ? fmt(a.tickIntervalTotalNs() / 1_000_000.0d) : "-1",
                tickCount > 0 ? fmt(a.tickIntervalMaxNs() / 1_000_000.0d) : "-1",
                // Folia region-context acquisitions; -1 on every other
                // platform, where the concept does not exist.
                Long.toString(a.regionContextAcquisitions),
                // Region-file reads implied by this attempt's selection loads,
                // plus the bin occupancy that produced them. Both halves are
                // written so the batch size is measured, not assumed.
                Long.toString(a.regionFileReads),
                Long.toString(a.binCandidates),
                Long.toString(a.binOccupancyMax));
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            w.write(row);
            w.newLine();
        } catch (IOException e) {
            // CSV write failures are diagnostic-only; the run continues.
            // Logged at the plugin level via Runner's exception path.
            throw new RuntimeException("CSV append failed: " + e.getMessage(), e);
        }
    }

    /**
     * Classifies one finished attempt from observable signals only - its
     * latency and its attributed selection chunk loads - against the
     * threshold the phase population has produced so far, and records the
     * direct reading separately when one is available.
     *
     * <p>Rows written before the phase reaches
     * {@link ModeClassifier#MIN_SAMPLES} are {@code UNKNOWN} with a
     * {@code -1} row threshold; both classification inputs stay in the row, so
     * they are reclassifiable offline against the final phase threshold
     * without re-running the benchmark.
     */
    private void classifyMode(Attempt a) {
        long latency = a.latencyMs();
        a.servedMode = modeClassifier.record(latency, a.selectionChunkLoads);
        a.servedModeThresholdMs = modeClassifier.thresholdMs();
        DirectModeSource src = directModeSource;
        if (src != null) {
            ModeClassifier.Mode direct = src.directModeFor(a);
            if (direct != null) {
                a.directServedMode = direct;
                modeClassifier.recordDirect(direct);
            }
        }
    }

    public Path phasesCsvPath() { return phasesCsvPath; }

    /**
     * Marks the start of a measurement phase (one TIMED run, one BURST, or
     * one SEQUENCE per-target window). Captures the CPU baselines and the
     * current attempt counters; the deltas are written by {@link #endPhase}.
     *
     * <p>If a previous phase is still active when called, it is implicitly
     * closed first via {@link #endPhase} so the phases CSV stays consistent
     * even if the operator switches modes mid-run.
     */
    public void beginPhase(String label) {
        if (!recording) return;
        if (phaseLabel != null) endPhase(phaseLabel);
        phaseLabel = label == null ? "" : label;
        phaseStartEpochMs = System.currentTimeMillis();
        CpuSampler s = cpuSampler;
        phaseStartProcessCpuNs = s != null ? s.processCpuTimeNs() : -1L;
        phaseStartMainCpuNs = s != null ? s.mainThreadCpuTimeNs() : -1L;
        phaseStartTotal = total.get();
        phaseStartSuccesses = successes.get();
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) cc.resetPhase();
        // Characterise the device this phase's region reads will come from.
        // Scheduled off-tick and never waited on: the phase row reads whatever
        // the probe has published by the time the row is written, and -1
        // otherwise. The probe deliberately reads only region files it has
        // never touched, farthest from origin first, so it cannot warm the
        // pages this phase then measures (see StorageProfiler).
        StorageProfiler sp = storageProfiler;
        if (sp != null) sp.probePhaseAsync(phaseLabel, storageProfilePath);
        FoliaRegionMonitor rm = regionMonitor;
        if (rm != null) rm.resetPhase();
        GcSampler gs = gcSampler;
        phaseStartGc = gs != null ? gs.snapshot() : null;
        ResidencySampler resSampler = residencySampler;
        if (resSampler != null) resSampler.resetPhase();
        HeapPressureWatcher hpw = heapPressureWatcher;
        if (hpw != null) hpw.beginPhase(phaseLabel);
        modeClassifier.reset();
        phaseTickIntervals.set(0L);
        phaseTickIntervalTotalNs.set(0L);
        phaseTickIntervalMaxNs.set(0L);
    }

    /**
     * Closes the current phase (if any) and appends one row to the phases
     * CSV. The {@code label} argument is allowed to differ from the
     * {@code beginPhase} label (e.g. SEQUENCE end-of-target uses the
     * advancing target's name) - the recorded label is whichever was active.
     */
    public void endPhase(@SuppressWarnings("unused") String label) {
        if (!recording) return;
        if (phaseLabel == null) return;
        long endEpoch = System.currentTimeMillis();
        String row = buildPhaseRow(endEpoch);
        try (BufferedWriter w = Files.newBufferedWriter(phasesCsvPath, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            w.write(row);
            w.newLine();
        } catch (IOException e) {
            // Phases CSV write failures are diagnostic-only; the run continues.
            throw new RuntimeException("phases CSV append failed: " + e.getMessage(), e);
        }
        // The phase closed normally; its summary is now in the durable phases
        // CSV, so the partial sidecar is no longer needed.
        try {
            Files.deleteIfExists(partialPhaseCsvPath);
        } catch (IOException ignored) { /* sidecar cleanup is best-effort */ }
        lastPartialFlushMs = 0L;
        // Clear phase state.
        phaseLabel = null;
        phaseStartEpochMs = -1L;
        phaseStartProcessCpuNs = -1L;
        phaseStartMainCpuNs = -1L;
    }

    /**
     * Periodically snapshots the in-flight phase to {@link #partialPhaseCsvPath}
     * so a mid-phase server crash still leaves the latest partial aggregate of
     * the phase that was running (the per-attempt and heap-series CSVs already
     * flush per row, but the phase summary is only written by {@link #endPhase}
     * at phase end). Safe to call every tick: self-throttled to at most once
     * per {@link #PARTIAL_PHASE_FLUSH_MS} and a no-op when no phase is active or
     * recording is disabled. Read-only with respect to phase/chunk state.
     */
    public void flushPartialPhase() {
        if (!recording) return;
        if (phaseLabel == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPartialFlushMs < PARTIAL_PHASE_FLUSH_MS) return;
        lastPartialFlushMs = now;
        String row = buildPhaseRow(now);
        try (BufferedWriter w = Files.newBufferedWriter(partialPhaseCsvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(PHASES_CSV_HEADER);
            w.newLine();
            w.write(row);
            w.newLine();
        } catch (IOException ignored) {
            // Partial-phase sidecar failures are best-effort and intentionally
            // quiet: the durable phases CSV is still written at phase end, and
            // the per-attempt CSV is unaffected.
        }
    }

    /**
     * Builds one phases-CSV row for the currently-active phase, measured up to
     * {@code endEpoch}. Read-only: does not clear phase state or reset the
     * chunk counter, so it is reused for both the final {@link #endPhase} row
     * and the periodic {@link #flushPartialPhase} snapshot.
     */
    private String buildPhaseRow(long endEpoch) {
        CpuSampler s = cpuSampler;
        long endProcessCpu = s != null ? s.processCpuTimeNs() : -1L;
        long endMainCpu = s != null ? s.mainThreadCpuTimeNs() : -1L;
        long wallMs = Math.max(0L, endEpoch - phaseStartEpochMs);
        int attempts = Math.max(0, total.get() - phaseStartTotal);
        int succ = Math.max(0, successes.get() - phaseStartSuccesses);

        long procCpuMs = -1L;
        if (phaseStartProcessCpuNs >= 0 && endProcessCpu >= 0) {
            procCpuMs = Math.max(0L, (endProcessCpu - phaseStartProcessCpuNs) / 1_000_000L);
        }
        long mainCpuMs = -1L;
        if (phaseStartMainCpuNs >= 0 && endMainCpu >= 0) {
            mainCpuMs = Math.max(0L, (endMainCpu - phaseStartMainCpuNs) / 1_000_000L);
        }
        double perTotal = (procCpuMs >= 0 && attempts > 0) ? (double) procCpuMs / attempts : -1.0;
        double perMain  = (mainCpuMs >= 0 && attempts > 0) ? (double) mainCpuMs / attempts : -1.0;

        ChunkLoadCounter cc = chunkCounter;
        long chunksLoaded = cc != null ? cc.phaseTotal() : -1L;
        long chunksAttributed = cc != null ? cc.phaseAttributed() : -1L;
        long chunksBackground = cc != null ? cc.phaseBackground() : -1L;
        // Selection loads: attributed minus the post-teleport arrival ring.
        // This is the view-distance-corrected per-attempt chunk metric.
        long chunksSelection = cc != null ? cc.phaseSelection() : -1L;
        double chunksSelectionPerAtt = (chunksSelection >= 0 && attempts > 0)
                ? (double) chunksSelection / attempts : -1.0;
        // chunks_per_attempt now reports attributed loads (i.e. loads charged
        // to a specific in-flight teleport via plugin-ticket / main-thread
        // attribution) rather than the global phase total. The total and
        // background columns remain available for sanity checking; pre-fix
        // runs that compared global-total/attempt across plugins were
        // measuring "all chunk loads anywhere on the server" / attempts and
        // double-counted with concurrent dispatch.
        double chunksPerAtt = (chunksAttributed >= 0 && attempts > 0)
                ? (double) chunksAttributed / attempts : -1.0;

        // Chunk-load cost amendment. When a calibration value is set
        // (chunkLoadCostNs > 0, typically obtained from `/rtp test
        // chunk-probe-perf`), we estimate the CPU cost the server attributed
        // to its own chunk-system threads - work that the per-process JMX
        // sampler counts in process_cpu_ms but that the per-thread main
        // sampler does NOT, and which the cpu_ms_per_attempt_total column
        // therefore underweights for plugins that synchronously load many
        // chunks per teleport (BetterRTP's PreloadRadius is the motivating
        // case). Use the attributed count rather than the phase total so
        // background loads (view-distance follow-ups, other-plugin loads)
        // do not inflate per-plugin CPU.
        long ns = chunkLoadCostNs;
        long chunksForCost = chunksAttributed >= 0 ? chunksAttributed : chunksLoaded;
        double chunkLoadCostMs = (ns > 0L && chunksForCost > 0)
                ? ((double) chunksForCost * (double) ns) / 1_000_000.0d : -1.0;
        long cpuMsWithChunks = (procCpuMs >= 0 && chunkLoadCostMs >= 0)
                ? procCpuMs + Math.round(chunkLoadCostMs) : -1L;
        double cpuWithChunksPerAtt = (cpuMsWithChunks >= 0 && attempts > 0)
                ? (double) cpuMsWithChunks / attempts : -1.0;

        // Bimodal latency split. Read-only, so the periodic partial-phase
        // snapshot can reuse it without disturbing the running classification.
        ModeClassifier.Summary mode = modeClassifier.summarise();

        // Foreground/background chunk-load split, aggregated over the phase.
        // Added alongside the pre-existing chunk columns, which are untouched:
        // the derived foreground chunk-load cost term still reads exactly the
        // same inputs (process_cpu_ms, chunks_loaded_attributed,
        // chunk_load_cost_ms) at exactly the same column names.
        long chunksOnTick = cc != null ? cc.phaseOnTick() : -1L;
        long chunksOffTick = cc != null ? cc.phaseOffTick() : -1L;
        long splitTotal = (chunksOnTick >= 0 && chunksOffTick >= 0)
                ? chunksOnTick + chunksOffTick : -1L;
        double offTickShare = (splitTotal > 0) ? (double) chunksOffTick / splitTotal : -1.0;

        // Folia region accounting. Read-only accessors, so the periodic
        // partial-phase snapshot reuses them without disturbing the phase
        // window. Every value is -1 off Folia (or with no monitor wired); a
        // Folia phase that simply never froze reports 0, which is a
        // measurement and not a blank.
        FoliaRegionMonitor rm = regionMonitor;
        long regionAcq = rm != null ? rm.phaseAcquisitions() : -1L;
        double regionAcqPerAtt = (regionAcq >= 0 && attempts > 0)
                ? (double) regionAcq / attempts : -1.0;
        long regionFreezes = rm != null ? rm.phaseFreezes() : -1L;
        long regionFreezesTick = rm != null ? rm.phaseTickStalls() : -1L;
        long regionFreezesHop = rm != null ? rm.phaseHopStalls() : -1L;
        long regionWorstFreezeMs = rm != null ? rm.phaseWorstFreezeMs() : -1L;

        long phaseTicks = phaseTickIntervals.get();
        long phaseTickTotalNs = phaseTickIntervalTotalNs.get();
        long phaseTickMaxNs = phaseTickIntervalMaxNs.get();

        // Region-file read accounting. reads are counted per distinct 32x32
        // bin, so bin_candidates / region_file_reads is the measured
        // candidates-per-binned-batch the cost model previously assumed.
        long regionReads = cc != null ? cc.phaseRegionReads() : -1L;
        long binCandidates = cc != null ? cc.phaseBinCandidates() : -1L;
        long binOccMax = cc != null ? cc.phaseBinOccupancyMax() : -1L;
        double regionReadsPerAtt = (regionReads >= 0 && attempts > 0)
                ? (double) regionReads / attempts : -1.0;
        double binPerBatch = (binCandidates >= 0 && regionReads > 0)
                ? (double) binCandidates / regionReads : -1.0;

        // GC / allocation phase deltas. Both ends of every counter must be
        // available or the column stays at the not-measured sentinel: a
        // missing baseline would otherwise publish the JVM's whole lifetime
        // as this phase's churn.
        GcSampler gs = gcSampler;
        GcSampler.Snapshot gcStart = phaseStartGc;
        GcSampler.Snapshot gcEnd = gs != null ? gs.snapshot() : null;
        long gcYoungC = -1L, gcYoungT = -1L, gcOldC = -1L, gcOldT = -1L;
        long gcUnC = -1L, gcTotC = -1L, gcTotT = -1L, allocBytes = -1L;
        if (gcStart != null && gcEnd != null) {
            gcYoungC = GcSampler.delta(gcStart.youngCollections(), gcEnd.youngCollections());
            gcYoungT = GcSampler.delta(gcStart.youngTimeMs(), gcEnd.youngTimeMs());
            gcOldC = GcSampler.delta(gcStart.oldCollections(), gcEnd.oldCollections());
            gcOldT = GcSampler.delta(gcStart.oldTimeMs(), gcEnd.oldTimeMs());
            gcUnC = GcSampler.delta(gcStart.unclassifiedCollections(),
                    gcEnd.unclassifiedCollections());
            gcTotC = GcSampler.delta(gcStart.totalCollections(), gcEnd.totalCollections());
            gcTotT = GcSampler.delta(gcStart.totalTimeMs(), gcEnd.totalTimeMs());
            allocBytes = GcSampler.delta(gcStart.tickThreadAllocatedBytes(),
                    gcEnd.tickThreadAllocatedBytes());
        }
        // Collector time is summed over parallel GC threads, so this fraction
        // is not a pause fraction and may exceed 1.0; the schema says so.
        double gcTimeFraction = (gcTotT >= 0 && wallMs > 0) ? (double) gcTotT / wallMs : -1.0;
        double allocPerAtt = (allocBytes >= 0 && attempts > 0)
                ? (double) allocBytes / attempts : -1.0;
        String allocScope = (gs != null && allocBytes >= 0) ? gs.allocationScope() : "";

        // Residency peaks. Read-only accessors, so the periodic partial-phase
        // snapshot reuses them without moving the peak window.
        ResidencySampler res = residencySampler;
        long peakChunks = res != null ? res.peakResidentChunks() : -1L;
        long peakTickets = res != null ? res.peakPluginTickets() : -1L;
        long peakTargetTickets = res != null ? res.peakTargetPluginTickets() : -1L;

        // Heap-pressure control-loop evidence. A wired watcher that saw no
        // match writes 0, which is a measurement; -1 means no watcher.
        HeapPressureWatcher hpw = heapPressureWatcher;
        long heapEvents = hpw != null ? hpw.phaseEventCount() : -1L;
        long heapFirstMb = hpw != null ? hpw.phaseFirstHeapUsedMb() : -1L;
        String heapFirstTrigger = hpw != null ? hpw.phaseFirstTrigger() : "";

        // Storage characterisation of the world directory. Read-only volatile
        // fields, populated by an off-tick probe; every numeric stays -1 until
        // a probe has completed, and UNKNOWN is written literally.
        StorageProfiler sp = storageProfiler;
        boolean scReady = sp != null && sp.everProfiled();
        String sc = scReady ? sp.storageClass().token()
                : StorageProfiler.StorageClass.UNKNOWN.token();
        String scMethod = scReady ? sp.classificationMethod() : "";
        String scFs = scReady ? sp.fsDescription() : "";
        long scProbeReads = scReady ? sp.probeReads() : -1L;
        String scLabel = scReady ? StorageProfiler.LATENCY_LABEL : "";
        long scP50 = scReady ? sp.coldReadP50Us() : -1L;
        long scP90 = scReady ? sp.coldReadP90Us() : -1L;
        long scP99 = scReady ? sp.coldReadP99Us() : -1L;
        long scMax = scReady ? sp.coldReadMaxUs() : -1L;

        // Setup-phase ticket-footprint calibration. Measured once before any
        // teleport, so it is constant across every row of the run; it is
        // repeated per row so peak_resident_chunks can be divided into a
        // per-cached-location cost without joining another file.
        TicketFootprintProbe tfp = ticketProbe;
        boolean tfReady = tfp != null && tfp.everProbed();
        long tfChunks = tfReady ? tfp.chunksPerTicket() : -1L;
        String tfShape = tfReady ? tfp.shape() : "";
        long tfReleased = tfReady ? tfp.chunksReleased() : -1L;
        long tfNoise = tfReady ? tfp.noiseLoads() : -1L;
        long tfHeap = tfReady ? tfp.heapDeltaBytes() : -1L;
        long tfBytesPerChunk = tfReady ? tfp.bytesPerChunk() : -1L;
        String tfHeapLabel = tfReady ? TicketFootprintProbe.HEAP_LABEL : "";

        String row = String.join(",",
                csv(phaseLabel),
                Long.toString(phaseStartEpochMs),
                Long.toString(endEpoch),
                Long.toString(wallMs),
                Integer.toString(attempts),
                Integer.toString(succ),
                procCpuMs >= 0 ? Long.toString(procCpuMs) : "",
                mainCpuMs >= 0 ? Long.toString(mainCpuMs) : "",
                perTotal >= 0 ? fmt(perTotal) : "",
                perMain  >= 0 ? fmt(perMain)  : "",
                chunksLoaded >= 0 ? Long.toString(chunksLoaded) : "",
                chunksAttributed >= 0 ? Long.toString(chunksAttributed) : "",
                chunksBackground >= 0 ? Long.toString(chunksBackground) : "",
                chunksPerAtt >= 0 ? fmt(chunksPerAtt) : "",
                chunkLoadCostMs >= 0 ? fmt(chunkLoadCostMs) : "",
                cpuMsWithChunks >= 0 ? Long.toString(cpuMsWithChunks) : "",
                cpuWithChunksPerAtt >= 0 ? fmt(cpuWithChunksPerAtt) : "",
                chunksSelection >= 0 ? Long.toString(chunksSelection) : "",
                chunksSelectionPerAtt >= 0 ? fmt(chunksSelectionPerAtt) : "",
                // Mode split. Every numeric below uses -1 for "not measured";
                // a phase that could not be split must never read as a phase
                // measured to have zero fast-mode service.
                Long.toString(mode.thresholdMs),
                mode.thresholdMethod,
                Long.toString(mode.classified),
                Long.toString(mode.fastCount),
                Long.toString(mode.coldCount),
                Long.toString(mode.unknownCount),
                frac(mode.fastFraction),
                Long.toString(mode.fastP50),
                Long.toString(mode.fastP95),
                Long.toString(mode.fastP99),
                Long.toString(mode.coldP50),
                Long.toString(mode.coldP95),
                Long.toString(mode.coldP99),
                Long.toString(mode.directFastCount),
                Long.toString(mode.directColdCount),
                frac(mode.directFastFraction),
                // Measured foreground/background split. -1 = NOT MEASURED.
                Long.toString(chunksOnTick),
                Long.toString(chunksOffTick),
                offTickShare >= 0 ? fmt(offTickShare) : "-1",
                phaseTicks > 0 ? Long.toString(phaseTicks) : "-1",
                phaseTicks > 0 ? fmt(phaseTickTotalNs / 1_000_000.0d) : "-1",
                phaseTicks > 0 ? fmt(phaseTickMaxNs / 1_000_000.0d) : "-1",
                TickThreadDetector.regionOwnershipAvailable()
                        ? "folia-region" : "single-tick-thread",
                // Folia region-context accounting and freeze detection. The
                // threshold is emitted unconditionally because it is the
                // stated criterion, not a measurement; the counts beside it
                // are -1 wherever the criterion could not be applied.
                Long.toString(regionAcq),
                regionAcqPerAtt >= 0 ? fmt(regionAcqPerAtt) : "-1",
                Long.toString(FoliaRegionMonitor.FREEZE_THRESHOLD_MS),
                Long.toString(regionFreezes),
                Long.toString(regionFreezesTick),
                Long.toString(regionFreezesHop),
                Long.toString(regionWorstFreezeMs),
                // Storage characterisation. The verdict, the method that
                // produced it, and the label describing what the latency
                // distribution actually is all travel together, so a read cost
                // can never be quoted without the device it was measured on.
                sc,
                csv(scMethod),
                csv(scFs),
                Long.toString(scProbeReads),
                scLabel,
                Long.toString(scP50),
                Long.toString(scP90),
                Long.toString(scP99),
                Long.toString(scMax),
                // Region-file reads and measured bin occupancy.
                Long.toString(regionReads),
                regionReadsPerAtt >= 0 ? fmt(regionReadsPerAtt) : "-1",
                Long.toString(binCandidates),
                binPerBatch >= 0 ? fmt(binPerBatch) : "-1",
                Long.toString(binOccMax),
                // GC churn, tick-thread allocation, and residency peaks. The
                // scope label travels with the allocation figure so a Folia
                // region thread's share is never read as the server's tick
                // allocation. Every count is -1 when not measured.
                Long.toString(gcYoungC),
                Long.toString(gcYoungT),
                Long.toString(gcOldC),
                Long.toString(gcOldT),
                Long.toString(gcUnC),
                Long.toString(gcTotC),
                Long.toString(gcTotT),
                gcTimeFraction >= 0 ? frac(gcTimeFraction) : "-1",
                Long.toString(allocBytes),
                allocPerAtt >= 0 ? fmt(allocPerAtt) : "-1",
                allocScope,
                Long.toString(peakChunks),
                Long.toString(peakTickets),
                Long.toString(peakTargetTickets),
                // Ticket-footprint calibration: the measured multiplier from
                // one cached location to resident chunks, plus the evidence
                // needed to judge it (release symmetry and window quietness).
                Long.toString(tfChunks),
                tfShape,
                Long.toString(tfReleased),
                Long.toString(tfNoise),
                Long.toString(tfHeap),
                Long.toString(tfBytesPerChunk),
                tfHeapLabel,
                // Heap-pressure trigger evidence only - no modelled response.
                Long.toString(heapEvents),
                Long.toString(heapFirstMb),
                csv(heapFirstTrigger));
        return row;
    }

    private static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Per-attempt chunk-load count, attributed via the {@link ChunkLoadCounter}
     *  chain (Paper plugin-ticket lookup, then main-thread temporal fallback).
     *  Empty when no counter is wired or the attempt was never registered. */
    private static String chunkDeltaCol(Attempt a) {
        if (a.attributedChunkLoads < 0) return "";
        return Long.toString(a.attributedChunkLoads);
    }

    /** Fraction column writer: keeps the explicit {@code -1} no-data sentinel
     *  instead of {@link #fmt}'s blank, so a missing fraction cannot be read
     *  as a measured 0.000. */
    private static String frac(double d) {
        if (Double.isNaN(d) || d < 0) return "-1";
        return String.format(java.util.Locale.ROOT, "%.4f", d);
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || d < 0) return "";
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    public int totalAttempts()  { return total.get(); }
    public int successCount()   { return successes.get(); }
    public int inFlightCount()  { return inFlight.get(); }
    public long coldStartLatencyMs() { return coldStartLatencyMs; }

    /** Snapshot of finished attempts for percentile/median computation. */
    public List<Long> latenciesSnapshot(boolean successOnly) {
        return latenciesSnapshot(successOnly, null);
    }

    /**
     * Snapshot of finished attempts, optionally filtered to a single target
     * label. {@code targetLabel == null} means "all targets".
     */
    public List<Long> latenciesSnapshot(boolean successOnly, String targetLabel) {
        List<Long> out = new ArrayList<>(finished.size());
        for (Attempt a : finished) {
            if (successOnly && !a.success) continue;
            if (targetLabel != null && !targetLabel.equals(a.targetLabel)) continue;
            long l = a.latencyMs();
            if (l >= 0) out.add(l);
        }
        return out;
    }

    /** Distinct target labels observed across finished attempts, in first-seen order. */
    public List<String> observedTargetLabels() {
        List<String> out = new ArrayList<>();
        for (Attempt a : finished) {
            if (!out.contains(a.targetLabel)) out.add(a.targetLabel);
        }
        return out;
    }

    /** Cold-start latency (first success) per target label. */
    public long coldStartLatencyMs(String targetLabel) {
        for (Attempt a : finished) {
            if (!a.success) continue;
            if (targetLabel == null || targetLabel.equals(a.targetLabel)) {
                return a.latencyMs();
            }
        }
        return -1L;
    }

    /** Inclusive percentile (0..100). Returns -1 when sample is empty. */
    public static long percentile(List<Long> samples, double p) {
        if (samples.isEmpty()) return -1L;
        long[] arr = samples.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(arr);
        int idx = (int) Math.min(arr.length - 1L,
                Math.max(0L, Math.round((p / 100.0) * (arr.length - 1))));
        return arr[idx];
    }

    public static long median(List<Long> samples) { return percentile(samples, 50.0); }
}
