package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MetricsKeys;
import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.folia.tasks.RegionKey;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Folia-flavoured {@link MetricsBinding} that publishes per-region tick
 * metrics (Phase M2, Section C of the metrics + multi-server checklist).
 *
 * <p>Folia partitions the server into independently-ticked regions, so the
 * single-thread {@code BukkitTpsSampler} approach is insufficient: each
 * region has its own tick rate. This binding maintains one
 * {@link FoliaRegionTpsSampler} per observed {@link RegionKey}, populated
 * via {@link #recordRegionTick(RegionKey)} which the platform wiring
 * (see {@code FoliaRegionProcessor}, C1.2b) invokes from each region's
 * own thread.
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
        this.nanoClock = nanoClock;
        this.playerCount = playerCount;
        this.softCap = softCap;
        this.globalSampler = new FoliaRegionTpsSampler(nanoClock);
    }

    /**
     * Schedule the steady global heartbeat sampler on the Folia global region
     * scheduler (1-tick period). Best-effort: if {@link RTP#scheduler} is not
     * yet installed, the binding silently degrades to the legacy
     * {@code FoliaRegionProcessor}-driven per-region sampling (scalars may then
     * report UNSAMPLED on an idle server, as before).
     */
    private void scheduleGlobalSampler() {
        try {
            if (RTP.scheduler == null) return;
            globalTaskHandle = RTP.scheduler.runTaskTimer(this::globalTick, 1L, 1L);
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
            if (RTP.metrics == null || RTP.metrics.getBinding() != this) {
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
        FoliaRegionTpsSampler sampler = samplers.get(key);
        if (sampler == null) {
            FoliaRegionTpsSampler created = new FoliaRegionTpsSampler(nanoClock);
            FoliaRegionTpsSampler prev = samplers.putIfAbsent(key, created);
            sampler = (prev != null) ? prev : created;
            if (prev == null) {
                regionIds.put(key, "region-" + regionIdSeq.getAndIncrement());
            }
        }
        sampler.tick();
        regionQueueDepths.put(key, Math.max(0, queueDepth));
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
        for (Map.Entry<RegionKey, FoliaRegionTpsSampler> e : samplers.entrySet()) {
            String id = regionIds.getOrDefault(e.getKey(), "region-?");
            FoliaRegionTpsSampler s = e.getValue();
            // queueDepth is carried by recordRegionTick(key, depth) from
            // FoliaRegionProcessor (C1.2b). playerCount per-region is not
            // attributed in M2 (no cheap region->player map on Folia); the
            // scalar playerCount() above carries the server-wide value.
            int qd = regionQueueDepths.getOrDefault(e.getKey(), 0);
            out.add(new FoliaRegionSample(id, s.tps1m(), s.mspt(), 0, qd));
        }
        return Collections.unmodifiableList(out);
    }

    // --------- aggregation ---------

    private enum SamplerView { TPS_1M, TPS_5M, TPS_15M, MSPT }

    private double aggregateTps(SamplerView view) {
        return aggregate(view, readAggregation(MetricsKeys.foliaAggregationTps, AGG_MEAN));
    }

    private double aggregate(SamplerView view, String mode) {
        evictIdle();
        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (FoliaRegionTpsSampler s : samplers.values()) {
            double v = read(s, view);
            if (Double.isNaN(v) || v == MetricsSnapshot.UNSAMPLED) continue;
            sum += v;
            if (v > max) max = v;
            n++;
        }
        // Fold in the steady global heartbeat sampler so the scalar TPS / MSPT
        // stays sampled even when no per-region sampler has data (zero RTP
        // traffic). The global sampler is not surfaced in foliaRegions().
        if (globalSampled) {
            double v = read(globalSampler, view);
            if (!Double.isNaN(v) && v != MetricsSnapshot.UNSAMPLED) {
                sum += v;
                if (v > max) max = v;
                n++;
            }
        }
        if (n == 0) return MetricsSnapshot.UNSAMPLED;
        return AGG_MAX.equalsIgnoreCase(mode) ? max : (sum / n);
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
                    (ConfigParser<MetricsKeys>) RTP.configs.getParser(MetricsKeys.class);
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
                    (ConfigParser<MetricsKeys>) RTP.configs.getParser(MetricsKeys.class);
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
            return s == null ? 0 : s.getOnlinePlayers().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeMaxPlayers() {
        try {
            Server s = Bukkit.getServer();
            return s == null ? 0 : s.getMaxPlayers();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // --------- test/diagnostics ---------

    /** Visible for tests. */
    int registrySize() { return samplers.size(); }

    /**
     * Test seam: drive the steady global heartbeat sampler one tick, as the
     * scheduled {@link #globalTick()} would in production. Used to exercise the
     * traffic-independent scalar sampling without a live {@code RTP.scheduler}.
     */
    void tickGlobalSamplerForTest() {
        globalSampler.tick();
        globalSampled = true;
    }
}
