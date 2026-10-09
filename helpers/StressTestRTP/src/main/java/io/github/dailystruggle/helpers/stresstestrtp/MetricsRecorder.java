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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
         *  Set by {@code Runner#dispatchOne}. {@code -1} until the runnable
         *  fires; {@link #latencyMs()} falls back to {@link #dispatchEpochMs}
         *  in that window so an attempt that completes before the hop runs
         *  (impossible in practice, but defensive) still gets a finite
         *  latency. */
        public volatile long commandDispatchedEpochMs = -1L;
        public volatile long teleportEpochMs = -1L;
        /** Which observation completed this attempt. Recorded because the
         *  channels have different floors: on Folia the external ones
         *  ({@code TELEPORT_EVENT}, {@code POSITION_POLL}) land on the
         *  destination region's tick and read 1-2 ticks after the plugin's own
         *  completion, while {@code PLUGIN_EVENT} (LeafRTP's PostTeleportEvent)
         *  is the plugin's completion instant. Never blank once written. */
        public volatile AttributionSource attributionSource = AttributionSource.NONE;
        /** LeafRTP PreTeleportEvent / PostTeleportEvent wall timestamps, set
         *  by {@link DirectTeleportProbe}. {@code -1L} on every other arm. */
        public volatile long pluginPreTeleportEpochMs = -1L;
        public volatile long pluginPostTeleportEpochMs = -1L;
        /** Folia 5 s TPS of the region owning the dispatching player at
         *  dispatch, from {@link RegionTpsSampler}; {@code -1} off Folia. */
        public volatile double regionTps5sAtDispatch = -1.0;
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

        /** First external observation of this attempt's teleport
         *  ({@code PlayerTeleportEvent} or the position watch), recorded on
         *  EVERY arm including the direct one. This is the channel all arms
         *  share, so {@code external_latency_ms} is the cross-plugin latency;
         *  {@code latency_ms} on the direct arm is the plugin's own instant.
         *  {@code -1L} when no external channel saw the teleport. */
        public volatile long externalSeenEpochMs = -1L;
        /** Landing column inspection ({@link LandingInspector}); null until
         *  a completion channel with a location inspected it. */
        public volatile LandingInspector.Landing landing = null;

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
        private final Object intervalLock = new Object();

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
        public void recordTickInterval(long startNs, long endNs) {
            long width = endNs - startNs;
            if (width <= 0L) return;
            synchronized (intervalLock) {
                tickIntervalCount++;
                tickIntervalTotalNs += width;
                if (width > tickIntervalMaxNs) tickIntervalMaxNs = width;
            }
        }

        public long tickIntervalCount() {
            synchronized (intervalLock) {
                return tickIntervalCount;
            }
        }

        public long tickIntervalTotalNs() {
            synchronized (intervalLock) {
                return tickIntervalTotalNs;
            }
        }

        public long tickIntervalMaxNs() {
            synchronized (intervalLock) {
                return tickIntervalMaxNs;
            }
        }

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

        /** Post minus Pre from the plugin's own events: the teleport call
         *  alone, without the selection work before it. {@code -1L} unless
         *  both were observed. */
        long pluginLatencyMs() {
            if (pluginPreTeleportEpochMs < 0 || pluginPostTeleportEpochMs < 0) return -1L;
            return Math.max(0L, pluginPostTeleportEpochMs - pluginPreTeleportEpochMs);
        }

        /** Dispatch to the first external observation; see
         *  {@link #externalSeenEpochMs}. */
        long externalLatencyMs() {
            if (externalSeenEpochMs <= 0) return -1L;
            long base = commandDispatchedEpochMs > 0 ? commandDispatchedEpochMs : dispatchEpochMs;
            return Math.max(0L, externalSeenEpochMs - base);
        }
    }

    /** Observation channel that completed an attempt. Written literally. */
    public enum AttributionSource {
        /** LeafRTP PostTeleportEvent (plugin's own completion instant). */
        PLUGIN_EVENT,
        /** Bukkit PlayerTeleportEvent. */
        TELEPORT_EVENT,
        /** Position comparison (Folia fallback). */
        POSITION_POLL,
        /** Console line matched a short-circuit failure. */
        CONSOLE,
        /** Per-attempt deadline elapsed. */
        TIMEOUT,
        /** Not yet completed. */
        NONE
    }

    /**
     * Ambient server baseline captured during the pre-phase recovery gap after settling.
     */
    public record IdleBaseline(
            double msptP50,
            double mainCpuCores,
            double processCpuCores,
            long durationMs
    ) {
        public static final IdleBaseline NONE = new IdleBaseline(-1.0, -1.0, -1.0, 0L);
        public boolean available() {
            return durationMs > 0L && (mainCpuCores >= 0.0 || processCpuCores >= 0.0 || msptP50 >= 0.0);
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
                    + "region_file_reads,bin_candidates,bin_occupancy_max,"
                    // Which channel completed the row, the plugin's own
                    // teleport-call span when it exposes one, and the Folia
                    // region TPS where the dispatch landed.
                    + "attribution_source,plugin_latency_ms,region_tps_5s_at_dispatch,"
                    // Shared-channel latency and the landing block column.
                    + "external_latency_ms,to_world,to_y,landing_class,"
                    + "landing_floor,landing_feet,landing_head";

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
            + "attribution_source: PLUGIN_EVENT|TELEPORT_EVENT|POSITION_POLL|CONSOLE|TIMEOUT -" + System.lineSeparator()
            + "  the observation that completed the row and therefore set teleport_epoch_ms." + System.lineSeparator()
            + "  PLUGIN_EVENT is LeafRTP's own PostTeleportEvent (its completion instant);" + System.lineSeparator()
            + "  TELEPORT_EVENT and POSITION_POLL are external and on Folia land on the" + System.lineSeparator()
            + "  destination region's tick, 1-2 ticks after the plugin finished. Compare" + System.lineSeparator()
            + "  latency_ms across arms only within one source." + System.lineSeparator()
            + "plugin_latency_ms: PostTeleportEvent minus PreTeleportEvent, the plugin's own" + System.lineSeparator()
            + "  teleport call without the selection before it. -1 unless both were seen," + System.lineSeparator()
            + "  which is every competitor arm." + System.lineSeparator()
            + "region_tps_5s_at_dispatch: Folia only. 5 s TPS of the region owning the" + System.lineSeparator()
            + "  dispatching player, read from Server#getRegionTPS at dispatch. -1 off Folia" + System.lineSeparator()
            + "  or before the first sample. The tps column on Folia is the GLOBAL region" + System.lineSeparator()
            + "  only and does not see player-region load; this column does." + System.lineSeparator()
            + "phases CSV region_tps_scope / region_tps_samples / region_tps_5s_min /" + System.lineSeparator()
            + "region_tps_5s_mean / region_tps_1m_min / region_tps_below_target_fraction:" + System.lineSeparator()
            + "  Folia per-region TPS over PLAYER-REGION SAMPLES (each online player's owning" + System.lineSeparator()
            + "  region on a fixed async period), not over distinct regions - the API gives" + System.lineSeparator()
            + "  no region id. below_target counts samples at or under a FIXED 19.0 TPS," + System.lineSeparator()
            + "  stated here rather than tuned. scope is FOLIA_PLAYER_REGIONS when measured," + System.lineSeparator()
            + "  SERVER_NATIVE where tps already covers the whole server (Paper), or" + System.lineSeparator()
            + "  GLOBAL_REGION_TIMER on a Folia build without the region-TPS API - in which" + System.lineSeparator()
            + "  case the tps column is the global region only. -1 means NOT MEASURED." + System.lineSeparator()
            + "phases CSV mode_threshold_ms / fast_mode_fraction / *_p50|p95|p99_ms: -1" + System.lineSeparator()
            + "  when the phase could not be split. Per-mode percentiles replace any bare" + System.lineSeparator()
            + "  mean over a bimodal population." + System.lineSeparator()
            + "phases CSV chunks_inclusive_per_attempt: total chunks loaded across the phase" + System.lineSeparator()
            + "  (attributed loads + background async queue warming loads) divided by attempts." + System.lineSeparator()
            + "  Accurately reflects the true server chunk-load footprint per teleport." + System.lineSeparator()
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
            + "  UNCOLLECTED_ALLOCATION_INCLUSIVE, or UNATTRIBUTABLE_CONCURRENT_ALLOCATION" + System.lineSeparator()
            + "  when the growth exceeds 16 MiB per counted chunk - then something else" + System.lineSeparator()
            + "  allocated inside the window and NO heap figure here is the ticket's." + System.lineSeparator()
            + "  The probe waits for the ticketed chunk to actually load (up to 400 ticks)" + System.lineSeparator()
            + "  before its settle window opens; a chunk that never loads is NOT MEASURED," + System.lineSeparator()
            + "  never a 0 / NONE footprint. No collection is forced, because a" + System.lineSeparator()
            + "  System.gc() on a server under measurement would corrupt the GC columns" + System.lineSeparator()
            + "  recorded here, so both figures include transient allocation and are an" + System.lineSeparator()
            + "  UPPER BOUND on retained bytes rather than a settled retained set." + System.lineSeparator()
            + "  Full detail in the setup-phase sidecar ticket-footprint.txt." + System.lineSeparator()
            + "ticket_footprint_heap_used_before_bytes / _after_load_bytes /" + System.lineSeparator()
            + "_after_unload_bytes: absolute used heap at the three probe boundaries -" + System.lineSeparator()
            + "  before the ticket, after the load settled, after the release settled." + System.lineSeparator()
            + "  Recorded absolutely, not only as a delta, so the growth can be read" + System.lineSeparator()
            + "  against the heap it happened on." + System.lineSeparator()
            + "ticket_footprint_heap_retained_after_unload_bytes /" + System.lineSeparator()
            + "ticket_footprint_heap_reclaimed_bytes / ticket_footprint_reclaim_label:" + System.lineSeparator()
            + "  what became of the load-side growth once the ticket was released." + System.lineSeparator()
            + "  Unloading a chunk drops the reference; it does not free the bytes, and" + System.lineSeparator()
            + "  Paper keeps released chunk data resident until a collection has reason" + System.lineSeparator()
            + "  to run. RETAINED_PENDING_COLLECTION is therefore the expected reading" + System.lineSeparator()
            + "  and is NOT a leak; it is the OOM exposure, because a heap sized from" + System.lineSeparator()
            + "  steady-state footprint alone has no room for released-but-uncollected" + System.lineSeparator()
            + "  chunks when a burst demands memory faster than the collector reclaims" + System.lineSeparator()
            + "  them. RECLAIMED_ON_UNLOAD means most growth came back unaided;" + System.lineSeparator()
            + "  NO_NET_GROWTH means there was nothing to reclaim;" + System.lineSeparator()
            + "  GC_DURING_WINDOW_UNATTRIBUTABLE means a collection ran inside the" + System.lineSeparator()
            + "  window, so no heap figure from the probe is attributable to the ticket." + System.lineSeparator()
            + "ticket_footprint_committed_delta_bytes: committed-heap change across the" + System.lineSeparator()
            + "  probe. Non-zero means the JVM grew the heap for ONE ticket, so absolute" + System.lineSeparator()
            + "  heap figures from the run describe heap-growth policy as much as the" + System.lineSeparator()
            + "  plugin. Pin -Xms == -Xmx before quoting any of them." + System.lineSeparator()
            + "ticket_probe_gc_collections: collections observed inside the probe" + System.lineSeparator()
            + "  window. 0 is a measurement and is what makes the heap columns" + System.lineSeparator()
            + "  attributable; -1 means no probe ran." + System.lineSeparator()
            + "heap_pressure_events / heap_pressure_first_heap_used_mb /" + System.lineSeparator()
            + "heap_pressure_first_trigger: RECORDED EVIDENCE of a plugin's own" + System.lineSeparator()
            + "  heap-pressure control loop - the matching log line and the heap-used" + System.lineSeparator()
            + "  level at which it fired. The trigger is recorded; no behavioural response" + System.lineSeparator()
            + "  is inferred, modelled, or attributed from a match. A phase with the" + System.lineSeparator()
            + "  watcher active and no match writes 0, which IS a measurement; -1 means" + System.lineSeparator()
            + "  the watcher was not wired. Full rows live in <stamp>-heap-triggers.csv." + System.lineSeparator()
            + "external_latency_ms: dispatch to the first EXTERNAL sighting of the teleport" + System.lineSeparator()
            + "  (PlayerTeleportEvent or the position watch), recorded on every arm including" + System.lineSeparator()
            + "  LeafRTP's. This is the column to compare across plugins; latency_ms on the" + System.lineSeparator()
            + "  PLUGIN_EVENT arm is LeafRTP's own completion instant. A LeafRTP row is held up" + System.lineSeparator()
            + "  to 1000 ms for the sighting; -1 means none arrived (common on Folia when" + System.lineSeparator()
            + "  pinned-position-watch is off, since teleportAsync may not fire the event)." + System.lineSeparator()
            + "fail_reason NOT_AT_DESTINATION: LeafRTP fired PostTeleportEvent but the player" + System.lineSeparator()
            + "  was more than 3 blocks (XZ) from the task's destination, or in another world." + System.lineSeparator()
            + "  The event fires whether or not the platform teleport succeeded." + System.lineSeparator()
            + "to_world / to_y / landing_class / landing_floor / landing_feet / landing_head:" + System.lineSeparator()
            + "  the block column at the landing, read by the harness with fixed criteria" + System.lineSeparator()
            + "  (LandingInspector), never the plugin's own safety rules. landing_class is" + System.lineSeparator()
            + "  SAFE|LAVA|WATER|SUFFOCATING|NO_FLOOR|HAZARD|VOID, or UNCHECKED_THREAD /" + System.lineSeparator()
            + "  UNCHECKED_UNLOADED / UNCHECKED_NO_LOCATION when the harness could not read" + System.lineSeparator()
            + "  it without loading a chunk or crossing a region. Empty on failed rows." + System.lineSeparator()
            + "phases CSV main_thread_cpu_scope: what main_thread_cpu_ms summed. main-thread" + System.lineSeparator()
            + "  on Spigot/Paper; folia-region-threads:N on Folia, the CPU of all N region" + System.lineSeparator()
            + "  scheduler threads (summed per-thread deltas). Rows without this column read" + System.lineSeparator()
            + "  ONE Folia thread and are not comparable." + System.lineSeparator()
            + "phases CSV chunks_sync_requested / chunks_sync_by_plugin: chunk loads that a" + System.lineSeparator()
            + "  plugin requested synchronously (with blocking frame), named from the ChunkLoadEvent" + System.lineSeparator()
            + "  call stack. chunks_inline_promotions / chunks_inline_by_plugin: inline ticket" + System.lineSeparator()
            + "  promotions on already-resident chunks that fired ChunkLoadEvent without blocking." + System.lineSeparator()
            + "  -1 / empty unless chunks_sync_selftest is PASS: the harness loads one chunk" + System.lineSeparator()
            + "  sync and one async at startup and requires the rule to name itself for the" + System.lineSeparator()
            + "  first and nobody for the second. chunks_on_tick classifies by" + System.lineSeparator()
            + "  firing thread, which Paper and Folia make near 100% for every plugin." + System.lineSeparator()
            + "phases CSV chunks_inclusive_per_attempt (all loads / attempts) is the chunk" + System.lineSeparator()
            + "  figure to publish across plugins. chunks_per_attempt charges on-tick loads to" + System.lineSeparator()
            + "  the most recently dispatched attempt and is not comparable on Folia." + System.lineSeparator()
            + "phases CSV cpu_*_ms: process CPU split by thread name, summed from per-thread" + System.lineSeparator()
            + "  deltas (CpuSampler). server = tick thread; region = Folia region threads;" + System.lineSeparator()
            + "  scheduler = Bukkit async workers, split per plugin in cpu_scheduler_by_plugin" + System.lineSeparator()
            + "  from the name Paper gives a worker while it runs a task (charged to the plugin" + System.lineSeparator()
            + "  named at sample time; (idle) = between tasks); async_scheduler = Paper/Folia" + System.lineSeparator()
            + "  AsyncScheduler; chunk_system = chunk load/generation/IO workers; network =" + System.lineSeparator()
            + "  Netty; other_java = the rest, top names in cpu_other_top. cpu_non_java_ms =" + System.lineSeparator()
            + "  process CPU minus all of these: GC, JIT and VM threads (not Java threads) plus" + System.lineSeparator()
            + "  the last interval of threads that exited between samples. cpu_gc_ms is the" + System.lineSeparator()
            + "  JVM's GC-thread CPU counter (JDK 26+), a subset of non_java; -1 when absent." + System.lineSeparator()
            + "  cpu_breakdown_samples = samples inside the phase (more = less exit loss)." + System.lineSeparator()
            + "  The harness's own async work appears as StressTestRTP in by_plugin." + System.lineSeparator()
            + "phases CSV chunks_landing_area: loads within viewDistance+1 chunks (Chebyshev)" + System.lineSeparator()
            + "  of an account's last successful destination, from that teleport until the" + System.lineSeparator()
            + "  same account's next dispatch (never time-based, so one account's teleports" + System.lineSeparator()
            + "  cannot overlap). Checked before the most-recent-dispatch rule, and excluded" + System.lineSeparator()
            + "  from chunks_loaded_attributed and chunks_loaded_background, so attributed +" + System.lineSeparator()
            + "  landing_area + background = chunks_loaded. chunks_per_teleport =" + System.lineSeparator()
            + "  (attributed + landing_area) / attempts: chunk loads charged to a teleport," + System.lineSeparator()
            + "  independent of how long the plugin's teleports take." + System.lineSeparator()
            + "phases CSV idle_mspt_p50, idle_main_cpu_cores, idle_process_cpu_cores: ambient" + System.lineSeparator()
            + "  server baseline sampled during the pre-phase recovery gap after settling." + System.lineSeparator()
            + "phases CSV net_main_cpu_ms, net_process_cpu_ms: gross CPU minus ambient baseline" + System.lineSeparator()
            + "  rate multiplied by phase wall time (marginal cost of the plugin)." + System.lineSeparator()
            + "phases CSV net_main_cpu_per_attempt, net_process_cpu_per_attempt: net CPU per attempt." + System.lineSeparator();

    public static final String PHASES_CSV_HEADER =
            "phase_label,start_epoch_ms,end_epoch_ms,wall_ms,attempts,successes,"
                    + "process_cpu_ms,main_thread_cpu_ms,"
                    + "cpu_ms_per_attempt_total,cpu_ms_per_attempt_main,"
                    + "chunks_loaded,chunks_loaded_attributed,chunks_loaded_background,"
                    + "chunks_per_attempt,chunks_inclusive_per_attempt,"
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
                    // Heap lifecycle across the probe: growth, and what became
                    // of it after release. An unload frees references, not
                    // bytes, so the post-unload columns are what distinguish a
                    // bounded footprint from deferred reclamation.
                    + "ticket_footprint_heap_used_before_bytes,"
                    + "ticket_footprint_heap_used_after_load_bytes,"
                    + "ticket_footprint_heap_used_after_unload_bytes,"
                    + "ticket_footprint_heap_retained_after_unload_bytes,"
                    + "ticket_footprint_heap_reclaimed_bytes,"
                    + "ticket_footprint_committed_delta_bytes,"
                    + "ticket_probe_gc_collections,ticket_footprint_reclaim_label,"
                    // Heap-pressure control-loop evidence: the trigger only.
                    + "heap_pressure_events,heap_pressure_first_heap_used_mb,"
                    + "heap_pressure_first_trigger,"
                    // Folia per-region TPS over player-region samples. The scope
                    // column names what the tps column itself measured, so a
                    // global-region-only figure is never read as server-wide.
                    + "region_tps_scope,region_tps_samples,region_tps_5s_min,"
                    + "region_tps_5s_mean,region_tps_1m_min,region_tps_below_target_fraction,"
                    // Per-plugin allocation attributed by Flight Recorder
                    // (jdk.ObjectAllocationSample). A GC pause is a global
                    // stop-the-world event that no counter can charge to a
                    // plugin; the honest attributable quantity is the
                    // allocation that forces those collections, sampled and
                    // charged to the first stack frame owned by a known plugin
                    // package. target = the arm measured this phase; total is
                    // the sampled denominator; the full per-package split lives
                    // in <stamp>-jfr-alloc.csv. All -1 / empty when the profiler
                    // is not wired or Flight Recorder is unavailable.
                    + "jfr_alloc_scope,jfr_alloc_samples,jfr_alloc_sampled_bytes_total,"
                    + "jfr_alloc_target_package,jfr_alloc_target_bytes,"
                    + "jfr_alloc_target_bytes_per_attempt,"
                    // What main_thread_cpu_ms summed, and synchronous chunk
                    // loads named by requester (stack attribution, gated on
                    // the startup self-test).
                    + "main_thread_cpu_scope,"
                    + "chunks_sync_requested,chunks_sync_by_plugin,chunks_sync_selftest,"
                    + "chunks_inline_promotions,chunks_inline_by_plugin,"
                    // Process CPU by thread group; -1 / empty when not measured.
                    + "cpu_breakdown_samples,cpu_breakdown_threads,"
                    + "cpu_server_thread_ms,cpu_region_threads_ms,cpu_scheduler_ms,"
                    + "cpu_async_scheduler_ms,cpu_chunk_system_ms,cpu_network_ms,"
                    + "cpu_other_java_ms,cpu_non_java_ms,cpu_gc_ms,"
                    + "cpu_scheduler_by_plugin,cpu_other_top,"
                    + "chunks_landing_area,chunks_landing_area_per_attempt,chunks_per_teleport,"
                    + "idle_mspt_p50,idle_main_cpu_cores,idle_process_cpu_cores,"
                    + "net_main_cpu_ms,net_process_cpu_ms,"
                    + "net_main_cpu_per_attempt,net_process_cpu_per_attempt";

    private final Path csvPath;
    /** Persistent per-attempt CSV writer. Rows arrive from event, region and
     *  async-tick threads; reopening per row cost ~0.4 ms on the server
     *  thread (Windows close). Guarded by {@link #rowLock}; opened lazily so a
     *  write after {@link #close} reopens in append mode instead of failing. */
    private final Object rowLock = new Object();
    private BufferedWriter rowWriter;
    private long lastRowFlushMs = 0L;
    private int rowWriterOpens = 0;
    /** Upper bound on unflushed rows' age: a crash loses at most this much. */
    static final long ROW_FLUSH_MS = 1000L;
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
    @SuppressWarnings("java:S3077") // Volatile reference publication for snapshot record
    private volatile IdleBaseline pendingIdleBaseline;
    @SuppressWarnings("java:S3077") // Volatile reference publication for snapshot record
    private volatile IdleBaseline phaseIdleBaseline;
    /** Optional chunk-load counter (set by the plugin on enable). May be null -
     *  in which case per-attempt and per-phase chunk columns are written empty. */
    private volatile ChunkLoadCounter chunkCounter;
    // Active phase snapshot; written to phases CSV by endPhase().
    private volatile String phaseLabel;
    private volatile long phaseStartEpochMs = -1L;
    private volatile long phaseStartProcessCpuNs = -1L;
    private volatile long phaseStartMainCpuNs = -1L;
    @SuppressWarnings("java:S3077") // Volatile reference publication for snapshot record
    private volatile CpuSampler.Breakdown phaseStartBreakdown;
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
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
    private volatile GcSampler gcSampler;
    /** Optional residency sampler (peak resident chunks, peak plugin tickets). */
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
    private volatile ResidencySampler residencySampler;
    /** Optional heap-pressure trigger recorder. Records; never models. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
    private volatile HeapPressureWatcher heapPressureWatcher;
    /** Optional setup-phase ticket-footprint calibration. May be null, in
     *  which case every ticket_footprint_* column is the -1 / empty
     *  not-measured sentinel. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
    private volatile TicketFootprintProbe ticketProbe;
    /** GC / allocation counters at phase start; deltas are written per phase. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for snapshot record
    private volatile GcSampler.Snapshot phaseStartGc;
    /** Optional world-directory storage characteriser. May be null, in which
     *  case every storage column is the -1 / empty not-measured sentinel and
     *  no storage block sidecar is produced. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
    private volatile StorageProfiler storageProfiler;
    /** Sidecar receiving one storage header block per phase. */
    private final Path storageProfilePath;
    /** Optional per-plugin allocation profiler (Flight Recorder). May be null,
     *  in which case every jfr_alloc_* column is the -1 / empty sentinel. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
    private volatile JfrAllocationProfiler jfrProfiler;
    /** Sidecar holding the full per-package allocation breakdown per phase. */
    private final Path jfrAllocPath;
    /** Parsed JFR result for the phase currently being closed. Set by
     *  {@link #endPhase} right before the row is built and cleared by
     *  {@link #beginPhase}; null (not-measured) during partial-phase flushes,
     *  which must never stop and re-parse the in-flight recording. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for snapshot record
    private volatile JfrAllocationProfiler.Result phaseJfrResult;

    /** Wires the storage characteriser. The recorder only reads its published
     *  fields and asks it to probe at phase start; all I/O is the profiler's
     *  own, off-tick. */
    public void setStorageProfiler(StorageProfiler profiler) { this.storageProfiler = profiler; }

    /** Wires the per-plugin allocation profiler. Optional: without it every
     *  jfr_alloc_* column writes the -1 / empty not-measured sentinel and no
     *  jfr-alloc sidecar is produced. Writes the sidecar header eagerly. */
    public void setJfrAllocationProfiler(JfrAllocationProfiler profiler) {
        this.jfrProfiler = profiler;
        if (profiler != null && profiler.available() && jfrAllocPath != null) {
            try {
                Files.writeString(jfrAllocPath,
                        "phase_label,kind,name,package_prefix,alloc_bytes,alloc_samples"
                                + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
                // Sidecar header failure is diagnostic-only; the phase CSV
                // columns still carry the target/total figures.
            }
        }
    }

    /** Sidecar path holding the per-phase, per-package allocation breakdown. */
    public Path jfrAllocPath() { return jfrAllocPath; }

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

    /**
     * Protected no-op constructor for proxies and test doubles that override recording methods
     * without writing to disk.
     */
    protected MetricsRecorder() {
        this.csvPath = null;
        this.phasesCsvPath = null;
        this.partialPhaseCsvPath = null;
        this.storageProfilePath = null;
        this.jfrAllocPath = null;
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
        this.jfrAllocPath = csvPath.resolveSibling(
                (dot > 0 ? name.substring(0, dot) : name) + "-jfr-alloc.csv");
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
    @SuppressWarnings("java:S3077") // Volatile reference publication for provider interface
    private volatile DirectModeSource directModeSource = null;

    /** Wires the direct-reading source (this plugin's arm only; see
     *  {@link DirectModeSource}). {@code null} disables the direct column. */
    public void setDirectModeSource(DirectModeSource source) { this.directModeSource = source; }

    /** Wires the CPU sampler used by {@link #beginPhase}/{@link #endPhase}. */
    public void setCpuSampler(CpuSampler sampler) { this.cpuSampler = sampler; }
    public CpuSampler cpuSampler() { return this.cpuSampler; }

    public void setNextPhaseIdleBaseline(IdleBaseline baseline) {
        this.pendingIdleBaseline = baseline;
    }

    public IdleBaseline phaseIdleBaseline() {
        return this.phaseIdleBaseline;
    }

    /** Wires the GC / tick-thread-allocation sampler. Optional: without it
     *  every GC and allocation column writes the -1 not-measured sentinel. */
    public void setGcSampler(GcSampler sampler) { this.gcSampler = sampler; }

    /** Wires the residency sampler (peak resident chunks, peak plugin tickets). */
    public void setResidencySampler(ResidencySampler sampler) { this.residencySampler = sampler; }

    /** Wires the setup-phase ticket-footprint calibration. The recorder only
     *  reads its published fields; the probe runs once at plugin enable and is
     *  finished long before any phase begins. */
    public void setTicketFootprintProbe(TicketFootprintProbe probe) { this.ticketProbe = probe; }

    /** Folia per-region TPS sampler. Optional: without it (or off Folia)
     *  every region_tps_* column writes the -1 not-measured sentinel. */
    private volatile RegionTpsSampler regionTpsSampler;
    public void setRegionTpsSampler(RegionTpsSampler sampler) { this.regionTpsSampler = sampler; }

    /** What the {@code tps} column measures on this server; written into
     *  every phase row as {@code region_tps_scope}. Empty means NOT AVAILABLE. */
    private volatile String tpsScope = "";
    public void setTpsScope(String scope) { this.tpsScope = scope == null ? "" : scope; }

    /** Wires the heap-pressure trigger recorder. It supplies evidence columns
     *  only; no behavioural response is inferred from a trigger. */
    public void setHeapPressureWatcher(HeapPressureWatcher watcher) { this.heapPressureWatcher = watcher; }

    /** Optional Folia region monitor (set by the plugin on enable). May be
     *  null - in which case every region column falls back to the {@code -1}
     *  not-measured sentinel, exactly as it does on a non-Folia server. */
    @SuppressWarnings("java:S3077") // Volatile reference publication for monitor component
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
        onComplete(a, success, failReason, toX, toZ, AttributionSource.TELEPORT_EVENT);
    }

    /** Completion with the observation channel stated. */
    public void onComplete(Attempt a, boolean success, String failReason,
                           double toX, double toZ, AttributionSource source) {
        onComplete(a, success, failReason, toX, toZ, source, false);
    }

    /** How long a direct-arm row waits for the external channel before it is
     *  written with {@code external_latency_ms=-1}. On Folia the external
     *  channels land 1-2 ticks after the plugin's own completion. */
    static final long EXTERNAL_WAIT_MS = 1000L;
    /** Completed rows held for {@link #releaseDeferred}; value = deadline. */
    private final ConcurrentHashMap<Attempt, Long> deferredRows = new ConcurrentHashMap<>();

    /**
     * Completion that may hold the CSV row back. With {@code deferRow} the
     * counters, chunk attribution and slot release happen now, and only the
     * row write waits until {@link #releaseDeferred} (the external channel
     * saw the same teleport) or {@link #EXTERNAL_WAIT_MS} elapses.
     */
    public void onComplete(Attempt a, boolean success, String failReason,
                           double toX, double toZ, AttributionSource source,
                           boolean deferRow) {
        if (a.teleportEpochMs > 0) return; // already completed
        a.teleportEpochMs = System.currentTimeMillis();
        a.attributionSource = source == null ? AttributionSource.NONE : source;
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
            if (deferRow) {
                deferredRows.put(a, System.currentTimeMillis() + EXTERNAL_WAIT_MS);
            } else {
                appendRow(a);
                logCompletion(a);
            }
        }
    }

    /** Writes a deferred row now. False if it was not (or no longer) held. */
    public boolean releaseDeferred(Attempt a) {
        if (a == null || deferredRows.remove(a) == null) return false;
        appendRow(a);
        logCompletion(a);
        return true;
    }

    /** True while {@code a}'s row is held for the external channel. */
    public boolean isDeferred(Attempt a) {
        return a != null && deferredRows.containsKey(a);
    }

    /** Writes every held row whose wait has elapsed ({@code all}: every row). */
    public void flushDeferred(boolean all) {
        // Called every runner tick in every mode: drives the time-based flush.
        if (csvPath != null) flushRowsIfDue();
        if (deferredRows.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Attempt, Long> e : deferredRows.entrySet()) {
            if ((all || e.getValue() <= now) && deferredRows.remove(e.getKey(), e.getValue())) {
                appendRow(e.getKey());
                logCompletion(e.getKey());
            }
        }
    }

    /** Called by {@link Runner} when an attempt times out without a teleport. */
    public void onTimeout(Attempt a) {
        if (a.teleportEpochMs > 0) return;
        a.teleportEpochMs = System.currentTimeMillis();
        a.attributionSource = AttributionSource.TIMEOUT;
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
        LandingInspector.Landing landing = a.landing;
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
                coord(a.fromX), coord(a.fromZ),
                coord(a.toX), coord(a.toZ),
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
                Long.toString(a.binOccupancyMax),
                a.attributionSource.name(),
                Long.toString(a.pluginLatencyMs()),
                a.regionTps5sAtDispatch >= 0 ? fmt(a.regionTps5sAtDispatch) : "-1",
                Long.toString(a.externalLatencyMs()),
                landing == null ? "" : csv(landing.world()),
                landing == null ? "" : coord(landing.y()),
                landing == null ? "" : landing.verdict().name(),
                landing == null ? "" : landing.floor(),
                landing == null ? "" : landing.feet(),
                landing == null ? "" : landing.head());
        writeRow(row);
    }

    private void writeRow(String row) {
        synchronized (rowLock) {
            try {
                if (rowWriter == null) {
                    rowWriter = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND);
                    rowWriterOpens++;
                    lastRowFlushMs = System.currentTimeMillis();
                }
                rowWriter.write(row);
                rowWriter.newLine();
                long now = System.currentTimeMillis();
                if (now - lastRowFlushMs >= ROW_FLUSH_MS) {
                    rowWriter.flush();
                    lastRowFlushMs = now;
                }
            } catch (IOException e) {
                closeRowWriterQuietly();
                // CSV write failures are diagnostic-only; the run continues.
                // Logged at the plugin level via Runner's exception path.
                throw new RuntimeException("CSV append failed: " + e.getMessage(), e);
            }
        }
    }

    /** Flushes buffered rows older than {@link #ROW_FLUSH_MS}; covers idle
     *  stretches where no further row arrives to trigger the flush. */
    private void flushRowsIfDue() {
        synchronized (rowLock) {
            if (rowWriter == null) return;
            long now = System.currentTimeMillis();
            if (now - lastRowFlushMs < ROW_FLUSH_MS) return;
            flushRowsLocked(now);
        }
    }

    /** Forces buffered per-attempt rows to disk. */
    public void flushRows() {
        synchronized (rowLock) {
            if (rowWriter != null) flushRowsLocked(System.currentTimeMillis());
        }
    }

    private void flushRowsLocked(long now) {
        try {
            rowWriter.flush();
            lastRowFlushMs = now;
        } catch (IOException e) {
            closeRowWriterQuietly();
            throw new RuntimeException("CSV flush failed: " + e.getMessage(), e);
        }
    }

    /** Flushes and closes the per-attempt CSV writer. Idempotent; a later row
     *  reopens the file in append mode. */
    public void close() {
        synchronized (rowLock) {
            if (rowWriter == null) return;
            try {
                rowWriter.close();
            } catch (IOException e) {
                throw new RuntimeException("CSV close failed: " + e.getMessage(), e);
            } finally {
                rowWriter = null;
            }
        }
    }

    private void closeRowWriterQuietly() {
        if (rowWriter == null) return;
        try {
            rowWriter.close();
        } catch (IOException ignored) {
            // Already failing; the caller reports the original error.
        }
        rowWriter = null;
    }

    /** Number of times the per-attempt writer has been opened. Test hook. */
    int rowWriterOpenCount() {
        synchronized (rowLock) {
            return rowWriterOpens;
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
        this.phaseIdleBaseline = this.pendingIdleBaseline;
        this.pendingIdleBaseline = null;
        phaseLabel = label == null ? "" : label;
        phaseStartEpochMs = System.currentTimeMillis();
        // LeafRTP arm only: load-gate counts at phase start, so the end line yields a delta.
        LoadGateFlush.flush(phaseLabel + ":start");
        CpuSampler s = cpuSampler;
        phaseStartProcessCpuNs = s != null ? s.processCpuTimeNs() : -1L;
        phaseStartMainCpuNs = s != null ? s.mainThreadCpuTimeNs() : -1L;
        phaseStartBreakdown = s != null ? s.sampleBreakdown() : null;
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
        // Start a fresh per-phase allocation recording and drop any parsed
        // result from the previous phase; the result for this phase is parsed
        // once, at endPhase, so partial flushes read the not-measured sentinel.
        phaseJfrResult = null;
        JfrAllocationProfiler jfr = jfrProfiler;
        if (jfr != null) jfr.beginPhase(phaseLabel);
        ResidencySampler resSampler = residencySampler;
        if (resSampler != null) resSampler.resetPhase();
        HeapPressureWatcher hpw = heapPressureWatcher;
        if (hpw != null) hpw.beginPhase(phaseLabel);
        RegionTpsSampler rts = regionTpsSampler;
        if (rts != null) rts.resetPhase();
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
        // Held rows belong to the closing phase's classifier and CSV block.
        flushDeferred(true);
        if (csvPath != null) flushRows();
        if (!recording) return;
        if (phaseLabel == null) return;
        ChunkLoadCounter cc = chunkCounter;
        if (cc != null) {
            cc.reportPhaseListenerTime(phaseLabel);
        }
        long endEpoch = System.currentTimeMillis();
        // Stop and parse this phase's allocation recording exactly once, before
        // the row is built, so buildPhaseRow reads a real result. The full
        // per-package breakdown goes to the jfr-alloc sidecar.
        JfrAllocationProfiler jfr = jfrProfiler;
        if (jfr != null && jfr.available()) {
            JfrAllocationProfiler.Result r = jfr.endPhaseAndParse();
            phaseJfrResult = r;
            if (r != null && r.available() && jfrAllocPath != null) {
                List<String> lines = jfr.sidecarLines(phaseLabel, r);
                if (!lines.isEmpty()) {
                    try (BufferedWriter w = Files.newBufferedWriter(jfrAllocPath,
                            StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                        for (String l : lines) { w.write(l); w.newLine(); }
                    } catch (IOException ignored) {
                        // Sidecar append failure is diagnostic-only; the phase
                        // CSV still carries the target/total columns.
                    }
                }
            }
        }
        LoadGateFlush.flush(phaseLabel + ":end");
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
        phaseStartBreakdown = null;
        phaseIdleBaseline = null;
    }

    /**
     * Periodically snapshots the in-flight phase to {@link #partialPhaseCsvPath}
     * so a mid-phase server crash still leaves the latest partial aggregate of
     * the phase that was running (the per-attempt CSV flushes at least every
     * {@link #ROW_FLUSH_MS}, but the phase summary is only written by {@link #endPhase}
     * at phase end). Safe to call every tick: self-throttled to at most once
     * per {@link #PARTIAL_PHASE_FLUSH_MS} and a no-op when no phase is active or
     * recording is disabled. Read-only with respect to phase/chunk state.
     */
    public void flushPartialPhase() {
        flushDeferred(false);
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
        CpuSampler.Breakdown startBd = phaseStartBreakdown;
        CpuSampler.Breakdown bd = (s != null && startBd != null)
                ? s.sampleBreakdown().minus(startBd) : null;
        if (bd != null && !bd.available) bd = null;
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

        IdleBaseline idle = phaseIdleBaseline;
        long ambientMainCpuMs = (idle != null && idle.mainCpuCores() >= 0.0)
                ? Math.round(idle.mainCpuCores() * (double) wallMs) : -1L;
        long ambientProcCpuMs = (idle != null && idle.processCpuCores() >= 0.0)
                ? Math.round(idle.processCpuCores() * (double) wallMs) : -1L;

        long netMainCpuMs = (mainCpuMs >= 0 && ambientMainCpuMs >= 0)
                ? Math.max(0L, mainCpuMs - ambientMainCpuMs) : -1L;
        long netProcCpuMs = (procCpuMs >= 0 && ambientProcCpuMs >= 0)
                ? Math.max(0L, procCpuMs - ambientProcCpuMs) : -1L;

        double netMainCpuPerAtt = (netMainCpuMs >= 0 && attempts > 0)
                ? (double) netMainCpuMs / attempts : -1.0;
        double netProcCpuPerAtt = (netProcCpuMs >= 0 && attempts > 0)
                ? (double) netProcCpuMs / attempts : -1.0;

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
        double chunksInclusivePerAtt = (chunksLoaded >= 0 && attempts > 0)
                ? (double) chunksLoaded / attempts : -1.0;
        long chunksLanding = cc != null ? cc.phaseLanding() : -1L;
        double chunksLandingPerAtt = (chunksLanding >= 0 && attempts > 0)
                ? (double) chunksLanding / attempts : -1.0;
        double chunksPerTeleport = (chunksLanding >= 0 && chunksAttributed >= 0 && attempts > 0)
                ? (double) (chunksAttributed + chunksLanding) / attempts : -1.0;

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

        // Folia per-region TPS. Read-only accessors; -1 off Folia or before
        // the first sample, never 0.
        RegionTpsSampler rts = regionTpsSampler;
        long rtpsSamples = rts != null ? rts.phaseSamples() : -1L;
        double rtpsMin5s = rts != null ? rts.phaseMin5s() : -1.0;
        double rtpsMean5s = rts != null ? rts.phaseMean5s() : -1.0;
        double rtpsMin1m = rts != null ? rts.phaseMin1m() : -1.0;
        double rtpsBelow = rts != null ? rts.phaseBelowTargetFraction() : -1.0;

        // Per-plugin allocation (Flight Recorder). phaseJfrResult is set at
        // endPhase and null during partial flushes, so every column here falls
        // back to the not-measured sentinel unless a real parse is available.
        JfrAllocationProfiler.Result jfr = phaseJfrResult;
        String jfrScope = (jfrProfiler != null) ? jfrProfiler.scope() : "";
        long jfrSamples = (jfr != null && jfr.available()) ? jfr.samples() : -1L;
        long jfrTotalBytes = (jfr != null && jfr.available()) ? jfr.totalBytes() : -1L;
        String jfrTargetPkg = (jfr != null && jfr.available()) ? jfr.targetPackage() : "";
        long jfrTargetBytes = (jfr != null && jfr.available()) ? jfr.targetBytes() : -1L;
        double jfrTargetPerAtt = (jfrTargetBytes >= 0 && attempts > 0)
                ? (double) jfrTargetBytes / attempts : -1.0;

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
        String tfHeapLabel = tfReady ? tfp.heapLabel() : "";
        long tfHeapBefore = tfReady ? tfp.heapUsedBeforeBytes() : -1L;
        long tfHeapAfterLoad = tfReady ? tfp.heapUsedAfterLoadBytes() : -1L;
        long tfHeapAfterUnload = tfReady ? tfp.heapUsedAfterUnloadBytes() : -1L;
        long tfHeapRetained = tfReady ? tfp.heapRetainedAfterUnloadBytes() : -1L;
        long tfHeapReclaimed = tfReady ? tfp.heapReclaimedBytes() : -1L;
        long tfCommittedDelta = tfReady ? tfp.committedDeltaBytes() : -1L;
        long tfWindowGc = tfReady ? tfp.windowCollections() : -1L;
        String tfReclaimLabel = tfReady ? tfp.reclaimLabel() : "";

        SyncLoadAttributor sync = cc != null ? cc.syncAttributor() : null;

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
                chunksInclusivePerAtt >= 0 ? fmt(chunksInclusivePerAtt) : "",
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
                // Heap lifecycle: growth, then whether release returned it.
                Long.toString(tfHeapBefore),
                Long.toString(tfHeapAfterLoad),
                Long.toString(tfHeapAfterUnload),
                Long.toString(tfHeapRetained),
                Long.toString(tfHeapReclaimed),
                Long.toString(tfCommittedDelta),
                Long.toString(tfWindowGc),
                tfReclaimLabel,
                // Heap-pressure trigger evidence only - no modelled response.
                Long.toString(heapEvents),
                Long.toString(heapFirstMb),
                csv(heapFirstTrigger),
                // Folia per-region TPS over player-region samples.
                csv(tpsScope),
                Long.toString(rtpsSamples),
                rtpsMin5s >= 0 ? fmt(rtpsMin5s) : "-1",
                rtpsMean5s >= 0 ? fmt(rtpsMean5s) : "-1",
                rtpsMin1m >= 0 ? fmt(rtpsMin1m) : "-1",
                rtpsBelow >= 0 ? frac(rtpsBelow) : "-1",
                // Per-plugin allocation (Flight Recorder). null result during a
                // partial flush -> not-measured sentinels; the final endPhase
                // row carries the parsed figures for the arm under test.
                jfrScope,
                jfrSamples >= 0 ? Long.toString(jfrSamples) : "-1",
                jfrTotalBytes >= 0 ? Long.toString(jfrTotalBytes) : "-1",
                csv(jfrTargetPkg),
                jfrTargetBytes >= 0 ? Long.toString(jfrTargetBytes) : "-1",
                jfrTargetPerAtt >= 0 ? fmt(jfrTargetPerAtt) : "-1",
                s != null ? s.mainThreadScope() : "",
                Long.toString(sync != null ? sync.phaseSyncLoads() : -1L),
                sync != null ? csv(sync.phaseByPluginSummary()) : "",
                sync != null ? sync.selfTest().name() : SyncLoadAttributor.SelfTest.NOT_RUN.name(),
                Long.toString(sync != null ? sync.phaseInlinePromotions() : -1L),
                sync != null ? csv(sync.phaseInlineByPluginSummary()) : "",
                Long.toString(bd != null ? bd.samples : -1L),
                Integer.toString(bd != null ? bd.threads : -1),
                cpuMs(bd, CpuSampler.Group.SERVER),
                cpuMs(bd, CpuSampler.Group.REGION),
                cpuMs(bd, CpuSampler.Group.SCHEDULER),
                cpuMs(bd, CpuSampler.Group.ASYNC_SCHEDULER),
                cpuMs(bd, CpuSampler.Group.CHUNK_SYSTEM),
                cpuMs(bd, CpuSampler.Group.NETWORK),
                cpuMs(bd, CpuSampler.Group.OTHER),
                bd != null && bd.nonJavaNs() >= 0 ? Long.toString(bd.nonJavaNs() / 1_000_000L) : "-1",
                bd != null && bd.gcNs >= 0 ? Long.toString(bd.gcNs / 1_000_000L) : "-1",
                bd != null ? csv(CpuSampler.Breakdown.summary(bd.schedulerByPluginNs, 0)) : "",
                bd != null ? csv(CpuSampler.Breakdown.summary(bd.otherByNameNs, 8)) : "",
                chunksLanding >= 0 ? Long.toString(chunksLanding) : "",
                chunksLandingPerAtt >= 0 ? fmt(chunksLandingPerAtt) : "",
                chunksPerTeleport >= 0 ? fmt(chunksPerTeleport) : "",
                idle != null && idle.msptP50() >= 0 ? fmt(idle.msptP50()) : "",
                idle != null && idle.mainCpuCores() >= 0 ? fmt(idle.mainCpuCores()) : "",
                idle != null && idle.processCpuCores() >= 0 ? fmt(idle.processCpuCores()) : "",
                netMainCpuMs >= 0 ? Long.toString(netMainCpuMs) : "",
                netProcCpuMs >= 0 ? Long.toString(netProcCpuMs) : "",
                netMainCpuPerAtt >= 0 ? fmt(netMainCpuPerAtt) : "",
                netProcCpuPerAtt >= 0 ? fmt(netProcCpuPerAtt) : "");
        return row;
    }

    private static String cpuMs(CpuSampler.Breakdown bd, CpuSampler.Group g) {
        return bd != null ? Long.toString(bd.groupNs(g) / 1_000_000L) : "-1";
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

    /** Coordinate formatter. Unlike {@link #fmt}, negative values are written
     *  verbatim: world coordinates are legitimately negative, so treating
     *  {@code < 0} as a no-data sentinel (fmt's contract for count/duration
     *  columns) silently blanked every destination in the western/northern
     *  quadrants. Only {@code NaN} means NOT AVAILABLE here. */
    private static String coord(double d) {
        if (Double.isNaN(d)) return "";
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

    /** Finished attempts for {@code targetLabel} (null: all) dispatched in
     *  {@code [fromEpochMs, toEpochMs)}. Includes warm-up attempts, which are
     *  tracked even while {@link #isRecording()} is false. */
    public List<Attempt> finishedDispatchedBetween(long fromEpochMs, long toEpochMs, String targetLabel) {
        List<Attempt> out = new ArrayList<>();
        for (Attempt a : finished) {
            if (a.dispatchEpochMs < fromEpochMs || a.dispatchEpochMs >= toEpochMs) continue;
            if (targetLabel != null && !targetLabel.equals(a.targetLabel)) continue;
            out.add(a);
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
        if (samples == null || samples.isEmpty()) return -1L;
        long[] arr = samples.stream().mapToLong(Long::longValue).toArray();
        if (arr.length == 0) return -1L;
        Arrays.sort(arr);
        int idx = (int) Math.round((p / 100.0) * (arr.length - 1));
        if (idx >= arr.length) idx = arr.length - 1;
        if (idx < 0) idx = 0;
        return arr[idx];
    }

    public static long median(List<Long> samples) { return percentile(samples, 50.0); }
}
