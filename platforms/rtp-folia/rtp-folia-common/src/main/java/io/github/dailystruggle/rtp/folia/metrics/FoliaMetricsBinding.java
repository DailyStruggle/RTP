package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MetricsKeys;
import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.folia.tasks.RegionKey;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Folia-flavoured {@link MetricsBinding} that publishes per-region tick
 * metrics.
 *
 * <p>Folia partitions the server into independently-ticked regions, so the
 * single-thread {@code BukkitTpsSampler} approach is insufficient: each
 * region has its own tick rate. This binding maintains one
 * {@link FoliaRegionTpsSampler} per observed {@link RegionKey}, populated
 * via {@link #recordRegionTick(RegionKey)} from each region's own thread.
 *
 * <p>Per-region feed: the Folia API has no per-region tick event, so every
 * region RTP dispatches into ({@link #observeRegion}) gets a 1-tick repeating
 * region-scheduler probe whose inter-run interval is that region's tick
 * interval. Probes are capped at {@value #MAX_REGION_PROBES}, cancel
 * themselves {@value #PROBE_IDLE_NANOS} ns after the last observed dispatch,
 * and discard gaps over {@value #PROBE_GAP_NANOS} ns (region unloaded and
 * re-created) instead of folding them into the EMA.
 *
 * <p>The scalar fields (`tps1m`, `tps5m`, `tps15m`, `mspt`) are aggregated
 * across all currently-known per-region samplers per the keys in
 * {@link MetricsKeys}:
 * <ul>
 *   <li>{@code foliaAggregationTps} = {@code mean} (default) | {@code max}</li>
 *   <li>{@code foliaAggregationMspt} = {@code max} (default) | {@code mean}</li>
 * </ul>
 * Tick-budget aggregation is derived from {@link #mspt()} via the snapshot.
 *
 * <p>The {@link #foliaRegions()} list is empty when {@code foliaIncludeRegions}
 * is {@code false} (op-out), or when no per-region sample has yet been
 * recorded; otherwise it contains one {@link FoliaRegionSample} per active
 * region, with an opaque, lifetime-stable {@code regionId} of the form
 * {@code region-N}.
 *
 * <p>Idle eviction: a sampler that has not been ticked for
 * {@value #IDLE_EVICTION_NANOS} nanoseconds (60 seconds) is dropped on the
 * next aggregation read. This keeps the registry bounded as regions on
 * Folia load/unload.
 *
 * <p>All public {@link MetricsBinding} accessors are non-blocking and
 * never throw on the calling thread; failures degrade to
 * {@link MetricsSnapshot#UNSAMPLED} or empty list.
 */
public final class FoliaMetricsBinding implements MetricsBinding {

    /** 60 seconds in nanoseconds; samplers idle longer than this are evicted. */
    static final long IDLE_EVICTION_NANOS = 60L * 1_000_000_000L;

    /** {@code mean} aggregation token (default for tps*). */
    static final String AGG_MEAN = "mean";
    /** {@code max} aggregation token (default for mspt). */
    static final String AGG_MAX = "max";

    private final ConcurrentHashMap<RegionKey, FoliaRegionTpsSampler> samplers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, String> regionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegionKey, Integer> regionQueueDepths = new ConcurrentHashMap<>();
    private final AtomicInteger regionIdSeq = new AtomicInteger(0);

    /**
     * Steady, RTP-traffic-independent heartbeat sampler. Ticked once per
     * server tick from the Folia global region scheduler (see
     * {@link #scheduleGlobalSampler()}), so the scalar TPS / MSPT aggregation
     * stays sampled even when no {@code /rtp} is in flight and therefore no
     * {@code FoliaRegionProcessor} is running. Decouples the binding's clock
     * from {@code FoliaRegionProcessor}, which only fires while a region has
     * queued RTP tasks; without this, an idle server reported UNSAMPLED after
     * the 60s idle-eviction window and bStats / {@code /rtp info} had no data.
     */
    private final FoliaRegionTpsSampler globalSampler;
    private volatile boolean globalSampled = false;
    /** Handle of the steady sampler task; cancelled when the binding is replaced. */
    private volatile Object globalTaskHandle = null;

    /** Upper bound on concurrently probed regions. */
    static final int MAX_REGION_PROBES = 32;
    /** A probe cancels itself after this long without an observed RTP dispatch (30 s). */
    static final long PROBE_IDLE_NANOS = 30L * 1_000_000_000L;
    /** Probe gaps longer than this (5 s) mean the region unloaded; the sampler is re-seeded. */
    static final long PROBE_GAP_NANOS = 5L * 1_000_000_000L;
    /**
     * Folia ticks regions on a fixed 50 ms schedule, so the sampled interval sits at ~50 ms on a
     * healthy region regardless of real work time. Only intervals above this (5 % overrun) prove
     * the tick ran long; below it {@link #worstMspt()} reports UNSAMPLED instead of a value that
     * would trip any {@code maxMSPT} under 50.
     */
    static final double OVERRUN_INTERVAL_MS = 52.5;

    /** Starts and cancels the 1-tick region probes; test seam over {@link RTPRuntime#scheduler()}. */
    interface RegionTimers {
        Object start(RTPWorld<?> world, int cx, int cz, Runnable tick);

        void cancel(Object handle);
    }

    static final RegionTimers SCHEDULER_TIMERS = new RegionTimers() {
        @Override
        public Object start(RTPWorld<?> world, int cx, int cz, Runnable tick) {
            RTPScheduler s = RTPRuntime.scheduler();
            return s == null ? null : s.runTaskTimer(world, cx, cz, tick, 1L, 1L);
        }

        @Override
        public void cancel(Object handle) {
            RTPScheduler s = RTPRuntime.scheduler();
            if (s != null && handle != null) s.cancelTask(handle);
        }
    };

    private static final class RegionProbe {
        final RegionKey key;
        final long startedNanos;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        volatile long lastObservedNanos;
        volatile long lastTickNanos = Long.MIN_VALUE;
        volatile Object handle;

        RegionProbe(RegionKey key, long now) {
            this.key = key;
            this.startedNanos = now;
            this.lastObservedNanos = now;
        }
    }

    private final ConcurrentHashMap<RegionKey, RegionProbe> probes = new ConcurrentHashMap<>();
    private final RegionTimers timers;

    private final LongSupplier nanoClock;
    private final IntSupplier playerCount;
    private final IntSupplier softCap;

    /** Production constructor. */
    public FoliaMetricsBinding() {
        this(System::nanoTime,
                FoliaMetricsBinding::safePlayerCount,
                FoliaMetricsBinding::safeMaxPlayers);
        scheduleGlobalSampler();
    }

    /** Test seam. */
    FoliaMetricsBinding(LongSupplier nanoClock, IntSupplier playerCount, IntSupplier softCap) {
        this(nanoClock, playerCount, softCap, SCHEDULER_TIMERS);
    }

    /** Test seam with injectable region probe timers. */
    FoliaMetricsBinding(LongSupplier nanoClock, IntSupplier playerCount, IntSupplier softCap,
                        RegionTimers timers) {
        this.nanoClock = nanoClock;
        this.playerCount = playerCount;
        this.softCap = softCap;
        this.timers = timers;
        this.globalSampler = new FoliaRegionTpsSampler(nanoClock);
    }

    /**
     * Schedule the steady global heartbeat sampler on the Folia global region
     * scheduler (1-tick period). Best-effort: if {@link RTPRuntime#scheduler()} is not
     * yet installed, the binding silently degrades to the legacy
     * {@code FoliaRegionProcessor}-driven per-region sampling (scalars may then
     * report UNSAMPLED on an idle server, as before).
     */
    private void scheduleGlobalSampler() {
        try {
            if (RTPRuntime.scheduler() == null) return;
            globalTaskHandle = RTPRuntime.scheduler().runTaskTimer(this::globalTick, 1L, 1L);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINER,
                    "FoliaMetricsBinding global sampler schedule failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * One steady heartbeat tick. Self-terminates (cancels its own task) once
     * this binding is no longer the active binding, so a hot reload / uninstall
     * does not leak the task.
     */
    private void globalTick() {
        try {
            // wraps(): a spark decorator around this binding still counts as installed.
            if (CoreMetrics.instance() == null || !CoreMetrics.instance().getBinding().wraps(this)) {
                cancelGlobalSampler();
                return;
            }
            globalSampler.tick();
            globalSampled = true;
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINER,
                    "FoliaMetricsBinding global tick failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void cancelGlobalSampler() {
        Object handle = globalTaskHandle;
        globalTaskHandle = null;
        if (handle == null) return;
        try {
            handle.getClass().getMethod("cancel").invoke(handle);
        } catch (Throwable ignored) {
            // Best-effort; the self-terminating guard in globalTick() prevents
            // any further work even if the explicit cancel fails.
        }
    }

    /**
     * Record one tick of the region identified by {@code key}. Intended to
     * be invoked from the region's own scheduler thread.
     *
     * <p>If {@code key} is {@code null}, the call is a no-op. The first
     * tick for a previously-unseen region allocates a new sampler and
     * mints its stable {@code region-N} id.
     */
    public void recordRegionTick(RegionKey key) {
        recordRegionTick(key, 0);
    }

    /**
     * Record one tick of the region identified by {@code key}, carrying the
     * region's current pending-task queue depth for per-region attribution
     * in {@link #foliaRegions()}. Intended to be invoked from the region's
     * own scheduler thread.
     *
     * <p>If {@code key} is {@code null}, the call is a no-op.
     */
    public void recordRegionTick(RegionKey key, int queueDepth) {
        if (key == null) return;
        tickSampler(key);
        regionQueueDepths.put(key, Math.max(0, queueDepth));
    }

    private void tickSampler(RegionKey key) {
        FoliaRegionTpsSampler sampler = samplers.get(key);
        if (sampler == null) {
            FoliaRegionTpsSampler created = new FoliaRegionTpsSampler(nanoClock);
            FoliaRegionTpsSampler prev = samplers.putIfAbsent(key, created);
            sampler = (prev != null) ? prev : created;
            if (prev == null) {
                regionIds.putIfAbsent(key, "region-" + regionIdSeq.getAndIncrement());
            }
        }
        sampler.tick();
    }

    @Override
    public void observeRegion(Object world, int cx, int cz) {
        if (world instanceof RTPWorld<?> rtpWorld) {
            observeRegion(rtpWorld, cx, cz);
        } else if (world instanceof org.bukkit.World bukkitWorld && RTPRuntime.serverAccessor() != null) {
            RTPWorld<?> rtpWorld = RTPRuntime.serverAccessor().getRTPWorld(bukkitWorld.getUID());
            if (rtpWorld != null) {
                observeRegion(rtpWorld, cx, cz);
            }
        }
    }

    /**
     * Note that RTP dispatched work to chunk {@code (cx, cz)}; starts a bounded 1-tick probe on
     * that region if none is live. Non-blocking, any thread.
     */
    public void observeRegion(RTPWorld<?> world, int cx, int cz) {
        if (world == null) return;
        UUID worldId = world.id();
        if (worldId == null) return;
        RegionKey key = new RegionKey(worldId, cx >> 5, cz >> 5);
        long now = nanoClock.getAsLong();
        RegionProbe existing = probes.get(key);
        if (existing != null) {
            existing.lastObservedNanos = now;
            long lastTick = existing.lastTickNanos;
            long silent = now - (lastTick == Long.MIN_VALUE ? existing.startedNanos : lastTick);
            if (silent <= PROBE_GAP_NANOS) return;
            stopProbe(existing); // its region unloaded, so it never reached its idle check
        }
        if (probes.size() >= MAX_REGION_PROBES) return;
        RegionProbe probe = new RegionProbe(key, now);
        if (probes.putIfAbsent(key, probe) != null) return;
        try {
            Object handle = timers.start(world, cx, cz, () -> probeTick(probe));
            if (handle == null) {
                probes.remove(key, probe);
                return;
            }
            probe.handle = handle;
            if (probe.stopped.get()) timers.cancel(handle); // stopped before the handle was published
        } catch (Throwable t) {
            probes.remove(key, probe);
            RTP.log(java.util.logging.Level.FINER, "FoliaMetricsBinding region probe start failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void probeTick(RegionProbe probe) {
        if (probe.stopped.get()) {
            Object h = probe.handle;
            if (h != null) cancelQuietly(h);
            return;
        }
        long now = nanoClock.getAsLong();
        if (now - probe.lastObservedNanos > PROBE_IDLE_NANOS) {
            stopProbe(probe);
            return;
        }
        long prev = probe.lastTickNanos;
        probe.lastTickNanos = now;
        if (prev != Long.MIN_VALUE && now - prev > PROBE_GAP_NANOS) {
            samplers.remove(probe.key); // re-seed: a reload gap is not tick lag
        }
        tickSampler(probe.key);
        regionQueueDepths.putIfAbsent(probe.key, 0);
    }

    private void stopProbe(RegionProbe probe) {
        if (!probe.stopped.compareAndSet(false, true)) return;
        probes.remove(probe.key, probe);
        Object h = probe.handle;
        if (h != null) cancelQuietly(h);
        samplers.remove(probe.key);
        regionIds.remove(probe.key);
        regionQueueDepths.remove(probe.key);
    }

    private void cancelQuietly(Object handle) {
        try {
            timers.cancel(handle);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINER, "FoliaMetricsBinding region probe cancel failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public double tps1m() { return aggregateTps(SamplerView.TPS_1M); }

    @Override
    public double tps5m() { return aggregateTps(SamplerView.TPS_5M); }

    @Override
    public double tps15m() { return aggregateTps(SamplerView.TPS_15M); }

    @Override
    public double mspt() {
        return aggregate(SamplerView.MSPT, readAggregation(MetricsKeys.foliaAggregationMspt, AGG_MAX));
    }

    /** Lowest 1m TPS over every active sampled region and the global heartbeat. */
    @Override
    public double worstTps1m() {
        evictIdle();
        double worst = Double.POSITIVE_INFINITY;
        long now = nanoClock.getAsLong();
        for (FoliaRegionTpsSampler s : samplers.values()) {
            long last = s.lastTickWallNanos();
            if (last != Long.MIN_VALUE && (now - last) > ACTIVE_REGION_WINDOW_NANOS) continue;
            double v = s.tps1m();
            if (isSampled(v) && v < worst) worst = v;
        }
        if (globalSampled) {
            double v = globalSampler.tps1m();
            if (isSampled(v) && v < worst) worst = v;
        }
        return worst == Double.POSITIVE_INFINITY ? MetricsSnapshot.UNSAMPLED : worst;
    }

    /**
     * Highest tick interval over every active sampled region and the global heartbeat, reported only
     * when it proves an overrun (above {@link #OVERRUN_INTERVAL_MS}); otherwise UNSAMPLED.
     */
    @Override
    public double worstMspt() {
        evictIdle();
        double worst = Double.NEGATIVE_INFINITY;
        long now = nanoClock.getAsLong();
        for (FoliaRegionTpsSampler s : samplers.values()) {
            long last = s.lastTickWallNanos();
            if (last != Long.MIN_VALUE && (now - last) > ACTIVE_REGION_WINDOW_NANOS) continue;
            double v = s.mspt();
            if (isSampled(v) && v > worst) worst = v;
        }
        if (globalSampled) {
            double v = globalSampler.mspt();
            if (isSampled(v) && v > worst) worst = v;
        }
        return worst > OVERRUN_INTERVAL_MS ? worst : MetricsSnapshot.UNSAMPLED;
    }

    @Override
    public boolean regionalTicking() { return true; }

    /** 1m TPS of the region owning the chunk; UNSAMPLED when that region has no live sampler. */
    @Override
    public double regionTps1m(UUID worldId, int chunkX, int chunkZ) {
        FoliaRegionTpsSampler s = liveSampler(worldId, chunkX, chunkZ);
        return s == null ? MetricsSnapshot.UNSAMPLED : s.tps1m();
    }

    /** Tick interval of the region owning the chunk, only when it proves an overrun; see {@link #worstMspt()}. */
    @Override
    public double regionMspt(UUID worldId, int chunkX, int chunkZ) {
        FoliaRegionTpsSampler s = liveSampler(worldId, chunkX, chunkZ);
        if (s == null) return MetricsSnapshot.UNSAMPLED;
        double v = s.mspt();
        return isSampled(v) && v > OVERRUN_INTERVAL_MS ? v : MetricsSnapshot.UNSAMPLED;
    }

    /**
     * Sampler keyed like {@link #observeRegion}; null when absent or past the idle-eviction
     * window. Single-entry staleness check instead of a full evictIdle() scan: called per task slice.
     */
    private FoliaRegionTpsSampler liveSampler(UUID worldId, int chunkX, int chunkZ) {
        if (worldId == null) return null;
        FoliaRegionTpsSampler s = samplers.get(new RegionKey(worldId, chunkX >> 5, chunkZ >> 5));
        if (s == null) return null;
        long last = s.lastTickWallNanos();
        if (last != Long.MIN_VALUE && nanoClock.getAsLong() - last > IDLE_EVICTION_NANOS) return null;
        return s;
    }

    private static boolean isSampled(double v) {
        return !Double.isNaN(v) && v != MetricsSnapshot.UNSAMPLED;
    }

    @Override
    public int playerCount() { return playerCount.getAsInt(); }

    @Override
    public int softCap() { return softCap.getAsInt(); }

    @Override
    public List<FoliaRegionSample> foliaRegions() {
        if (!readIncludeRegions()) return Collections.emptyList();
        evictIdle();
        if (samplers.isEmpty()) return Collections.emptyList();
        List<FoliaRegionSample> out = new ArrayList<>(samplers.size());
        long now = nanoClock.getAsLong();
        for (Map.Entry<RegionKey, FoliaRegionTpsSampler> e : samplers.entrySet()) {
            String id = regionIds.getOrDefault(e.getKey(), "region-?");
            FoliaRegionTpsSampler s = e.getValue();
            // queueDepth is carried by recordRegionTick(key, depth) from
            // FoliaRegionProcessor (C1.2b). playerCount per-region is not
            // attributed in M2 (no cheap region->player map on Folia); the
            // scalar playerCount() above carries the server-wide value.
            int qd = regionQueueDepths.getOrDefault(e.getKey(), 0);
            out.add(new FoliaRegionSample(id, s.tps1m(), s.mspt(), 0, qd, s.sampleCount(), s.ageMillis(now)));
        }
        return Collections.unmodifiableList(out);
    }

    // --------- aggregation ---------

    /**
     * Active tick window in nanoseconds; regions that have not ticked within
     * this duration are considered idle or unloaded and excluded from the
     * server-wide aggregate TPS.
     */
    static final long ACTIVE_REGION_WINDOW_NANOS = 2L * 1_000_000_000L;

    /**
     * Minimum tick samples required before a region's EMA is considered mature for
     * aggregate TPS, preventing transient startup jitter from distorting the mean.
     */
    static final long MIN_SAMPLES = 20L;

    /** Window in ticks for weighting mature regions in the sample-count weighted mean. */
    private static final long MAX_WEIGHT_TICKS = (long) (60.0 * 20.0); // 1200 ticks

    private enum SamplerView { TPS_1M, TPS_5M, TPS_15M, MSPT }

    private double aggregateTps(SamplerView view) {
        return aggregate(view, readAggregation(MetricsKeys.foliaAggregationTps, AGG_MEAN));
    }

    private double aggregate(SamplerView view, String mode) {
        evictIdle();
        boolean isMax = AGG_MAX.equalsIgnoreCase(mode);
        long now = nanoClock.getAsLong();

        boolean hasMature = (globalSampled && globalSampler.sampleCount() >= MIN_SAMPLES);
        if (!hasMature) {
            for (FoliaRegionTpsSampler s : samplers.values()) {
                long last = s.lastTickWallNanos();
                if (last != Long.MIN_VALUE && (now - last) > ACTIVE_REGION_WINDOW_NANOS) continue;
                if (s.sampleCount() >= MIN_SAMPLES && isSampled(read(s, view))) {
                    hasMature = true;
                    break;
                }
            }
        }

        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        double totalWeight = 0.0;
        int n = 0;

        for (FoliaRegionTpsSampler s : samplers.values()) {
            if (hasMature && s.sampleCount() < MIN_SAMPLES) continue;
            long last = s.lastTickWallNanos();
            if (last != Long.MIN_VALUE && (now - last) > ACTIVE_REGION_WINDOW_NANOS) continue;
            double v = read(s, view);
            if (!isSampled(v)) continue;
            double weight = (double) Math.max(1L, Math.min(s.sampleCount(), MAX_WEIGHT_TICKS));
            sum += v * weight;
            totalWeight += weight;
            if (v > max) max = v;
            n++;
        }

        if (globalSampled) {
            double v = read(globalSampler, view);
            if (isSampled(v) && (!hasMature || globalSampler.sampleCount() >= MIN_SAMPLES)) {
                double weight = (double) Math.max(1L, Math.min(globalSampler.sampleCount(), MAX_WEIGHT_TICKS));
                sum += v * weight;
                totalWeight += weight;
                if (v > max) max = v;
                n++;
            }
        }

        if (n == 0) return MetricsSnapshot.UNSAMPLED;
        if (isMax) return max;
        return totalWeight > 0.0 ? (sum / totalWeight) : (sum / n);
    }

    private static double read(FoliaRegionTpsSampler s, SamplerView view) {
        switch (view) {
            case TPS_1M:  return s.tps1m();
            case TPS_5M:  return s.tps5m();
            case TPS_15M: return s.tps15m();
            case MSPT:    return s.mspt();
            default:      return MetricsSnapshot.UNSAMPLED;
        }
    }

    private void evictIdle() {
        long now = nanoClock.getAsLong();
        for (RegionProbe probe : new java.util.ArrayList<>(probes.values())) {
            long lastTick = probe.lastTickNanos;
            long silent = now - (lastTick == Long.MIN_VALUE ? probe.startedNanos : lastTick);
            if (silent > PROBE_IDLE_NANOS || (now - probe.lastObservedNanos > PROBE_IDLE_NANOS)) {
                stopProbe(probe);
            }
        }
        for (Map.Entry<RegionKey, FoliaRegionTpsSampler> e : samplers.entrySet()) {
            long last = e.getValue().lastTickWallNanos();
            if (last == Long.MIN_VALUE) continue;
            if (now - last > IDLE_EVICTION_NANOS) {
                samplers.remove(e.getKey(), e.getValue());
                regionIds.remove(e.getKey());
                regionQueueDepths.remove(e.getKey());
            }
        }
    }

    // --------- config readers ---------

    private static boolean readIncludeRegions() {
        try {
            if (RTP.getInstance() == null) return true;
            @SuppressWarnings("unchecked")
            ConfigParser<MetricsKeys> parser =
                    (ConfigParser<MetricsKeys>) ActiveConfigs.get().getParser(MetricsKeys.class);
            if (parser == null) return true;
            Object v = parser.getConfigValue(MetricsKeys.foliaIncludeRegions, Boolean.TRUE);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return Boolean.parseBoolean((String) v);
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String readAggregation(MetricsKeys key, String defaultMode) {
        try {
            if (RTP.getInstance() == null) return defaultMode;
            @SuppressWarnings("unchecked")
            ConfigParser<MetricsKeys> parser =
                    (ConfigParser<MetricsKeys>) ActiveConfigs.get().getParser(MetricsKeys.class);
            if (parser == null) return defaultMode;
            Object v = parser.getConfigValue(key, defaultMode);
            if (v == null) return defaultMode;
            String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
            if (AGG_MEAN.equals(s) || AGG_MAX.equals(s)) return s;
            return defaultMode;
        } catch (Throwable ignored) {
            return defaultMode;
        }
    }

    // --------- Bukkit safe readers (server-wide fields) ---------

    private static int safePlayerCount() {
        try {
            Server s = Bukkit.getServer();
            return s.getOnlinePlayers().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeMaxPlayers() {
        try {
            Server s = Bukkit.getServer();
            return s.getMaxPlayers();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // --------- test/diagnostics ---------

    /** Visible for tests. */
    int registrySize() { return samplers.size(); }

    /** Visible for tests. */
    int probeCount() { return probes.size(); }

    /**
     * Test seam: drive the steady global heartbeat sampler one tick, as the
     * scheduled {@link #globalTick()} would in production. Used to exercise the
     * traffic-independent scalar sampling without a live {@code RTPRuntime.scheduler()}.
     */
    void tickGlobalSamplerForTest() {
        globalSampler.tick();
        globalSampled = true;
    }
}
