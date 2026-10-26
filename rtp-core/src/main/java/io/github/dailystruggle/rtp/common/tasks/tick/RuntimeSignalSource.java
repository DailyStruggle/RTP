package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.rtp.common.bit.CoreFallbackCbitCounters;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.governor.ComputeSignals;
import io.github.dailystruggle.rtp.common.governor.SignalSource;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.metrics.HeapSampler;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor;
import io.github.dailystruggle.rtp.common.tools.ServerHealthGate;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production {@link SignalSource} reading {@link MetricsBinding}, post-GC {@link HeapSampler},
 * JVM {@link OperatingSystemMXBean} / {@link ThreadMXBean}, {@link GenerationBudget}, and
 * per-region demand from {@link ActiveSelection} (ADR-087 Section 4.1 & 7.3).
 */
public final class RuntimeSignalSource implements SignalSource {

    public static final long MSPT_LAG_NANOS = TimeUnit.SECONDS.toNanos(10);
    public static final long TPS_LAG_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long PROCESS_CPU_LAG_NANOS = TimeUnit.SECONDS.toNanos(2);
    public static final long HEAP_LAG_NANOS = TimeUnit.SECONDS.toNanos(30);
    public static final long CHUNK_LATENCY_LAG_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final RuntimeSignalSource INSTANCE = new RuntimeSignalSource();

    public static RuntimeSignalSource instance() {
        return INSTANCE;
    }

    private static final OperatingSystemMXBean OS_BEAN = ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean THREAD_BEAN = initThreadBean();
    private static final Method PROCESS_CPU_LOAD_METHOD = resolveMethod(OS_BEAN, "getProcessCpuLoad");
    private static final Method PROCESS_CPU_TIME_METHOD = resolveMethod(OS_BEAN, "getProcessCpuTime");

    private final AtomicLong accumulatedRtpBgCpuNanos = new AtomicLong(0L);
    private final AtomicLong lastSampledRtpBgCpuNanos = new AtomicLong(0L);
    private final AtomicLong lastSampledProcessCpuNanos = new AtomicLong(-1L);
    private final AtomicBoolean processCpuLoadDegraded = new AtomicBoolean(false);
    private final AtomicBoolean processCpuTimeDegraded = new AtomicBoolean(false);
    private final AtomicBoolean threadCpuTimeDegraded = new AtomicBoolean(false);
    private final AtomicBoolean configLookupDegraded = new AtomicBoolean(false);
    private final AtomicBoolean metricsBindingDegraded = new AtomicBoolean(false);

    private final AtomicLong lastMsptBits = new AtomicLong(Double.doubleToRawLongBits(Double.NaN));
    private final AtomicLong lastMsptSampleNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastTpsBits = new AtomicLong(Double.doubleToRawLongBits(Double.NaN));
    private final AtomicLong lastTpsSampleNanos = new AtomicLong(Long.MIN_VALUE);

    private static ThreadMXBean initThreadBean() {
        try {
            ThreadMXBean tb = ManagementFactory.getThreadMXBean();
            if (tb != null && tb.isCurrentThreadCpuTimeSupported() && !tb.isThreadCpuTimeEnabled()) {
                tb.setThreadCpuTimeEnabled(true);
            }
            return tb;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveMethod(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns current thread CPU time in nanoseconds, or {@code -1L} if unsupported. */
    public static long currentThreadCpuTimeNanos() {
        if (THREAD_BEAN == null) return -1L;
        try {
            return THREAD_BEAN.isCurrentThreadCpuTimeSupported() ? THREAD_BEAN.getCurrentThreadCpuTime() : -1L;
        } catch (Throwable e) {
            if (INSTANCE.threadCpuTimeDegraded.compareAndSet(false, true)) {
                CoreFallbackCbitCounters.recordLifecycleCleanupDegraded(
                        "RuntimeSignalSource.currentThreadCpuTimeNanos", e);
            }
            return -1L;
        }
    }

    /** Records CPU time spent on the RTP async background drain. */
    public void recordRtpBackgroundCpuNanos(long cpuDeltaNanos) {
        if (cpuDeltaNanos > 0L) {
            accumulatedRtpBgCpuNanos.addAndGet(cpuDeltaNanos);
        }
    }

    @Override
    public ComputeSignals sample(long nowNanos) {
        boolean autoTune = TickBudget.autoTune();
        boolean observeOnly = io.github.dailystruggle.rtp.common.governor.ComputeGovernor.isObservePropertyEnabled();

        double minTps = ComputeSignals.DEFAULT_MIN_TPS;
        double maxMspt = ComputeSignals.DEFAULT_MAX_MSPT;
        double maxHeapPercent = ComputeSignals.DEFAULT_MAX_HEAP_PERCENT;
        int viewDistanceSelect = 0;

        try {
            if (ActiveConfigs.get() != null) {
                @SuppressWarnings("unchecked")
                ConfigParser<PerformanceKeys> perf =
                        (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
                if (perf != null) {
                    minTps = perf.getNumber(PerformanceKeys.minTPS, ComputeSignals.DEFAULT_MIN_TPS).doubleValue();
                    maxMspt = perf.getNumber(PerformanceKeys.maxMSPT, ComputeSignals.DEFAULT_MAX_MSPT).doubleValue();
                    maxHeapPercent = perf.getNumber(PerformanceKeys.maxHeapPercent, ComputeSignals.DEFAULT_MAX_HEAP_PERCENT).doubleValue();
                    viewDistanceSelect = Math.max(0, perf.getNumber(PerformanceKeys.viewDistanceSelect, 0).intValue());
                }
            }
        } catch (Throwable e) {
            if (configLookupDegraded.compareAndSet(false, true)) {
                CoreFallbackCbitCounters.recordConfigLookupDegraded(
                        "RuntimeSignalSource.sample.performanceConfig", e);
            }
        }

        MetricsBinding metrics = null;
        try {
            metrics = CoreMetrics.instance().getBinding();
        } catch (Throwable e) {
            if (metricsBindingDegraded.compareAndSet(false, true)) {
                CoreFallbackCbitCounters.recordLifecycleCleanupDegraded(
                        "RuntimeSignalSource.sample.metricsBinding", e);
            }
        }

        boolean regionalTicking = metrics != null && metrics.regionalTicking();
        boolean tickStalled = ServerHealthGate.tickStalled(nowNanos);
        boolean hardGatePaused = ServerHealthGate.shouldPause() || HeapPressureMonitor.underPressure();

        // 1. MSPT (single-loop only) and Mean TPS
        ComputeSignals.SampledSignal msptSignal = ComputeSignals.SampledSignal.neverSampled();
        ComputeSignals.SampledSignal tpsSignal = ComputeSignals.SampledSignal.neverSampled();

        if (metrics != null) {
            if (!regionalTicking) {
                double rawMspt = metrics.worstMspt();
                if (!Double.isNaN(rawMspt) && !Double.isInfinite(rawMspt) && rawMspt > 0.0) {
                    if (tickStalled) {
                        long prevTime = lastMsptSampleNanos.get();
                        long staleFor = prevTime == Long.MIN_VALUE ? MSPT_LAG_NANOS : Math.max(0L, nowNanos - prevTime);
                        msptSignal = ComputeSignals.SampledSignal.stale(rawMspt, MSPT_LAG_NANOS, staleFor);
                    } else {
                        lastMsptBits.set(Double.doubleToRawLongBits(rawMspt));
                        lastMsptSampleNanos.set(nowNanos);
                        msptSignal = ComputeSignals.SampledSignal.fresh(rawMspt, MSPT_LAG_NANOS);
                    }
                } else {
                    double prev = Double.longBitsToDouble(lastMsptBits.get());
                    long prevTime = lastMsptSampleNanos.get();
                    if (!Double.isNaN(prev) && prevTime != Long.MIN_VALUE) {
                        msptSignal = ComputeSignals.SampledSignal.stale(prev, MSPT_LAG_NANOS, Math.max(0L, nowNanos - prevTime));
                    }
                }
            }

            double rawTps = metrics.tps1m();
            if (Double.isNaN(rawTps) || rawTps <= 0.0) {
                rawTps = metrics.worstTps1m();
            }
            if (!Double.isNaN(rawTps) && !Double.isInfinite(rawTps) && rawTps > 0.0) {
                if (tickStalled) {
                    long prevTime = lastTpsSampleNanos.get();
                    long staleFor = prevTime == Long.MIN_VALUE ? TPS_LAG_NANOS : Math.max(0L, nowNanos - prevTime);
                    tpsSignal = ComputeSignals.SampledSignal.stale(rawTps, TPS_LAG_NANOS, staleFor);
                } else {
                    lastTpsBits.set(Double.doubleToRawLongBits(rawTps));
                    lastTpsSampleNanos.set(nowNanos);
                    tpsSignal = ComputeSignals.SampledSignal.fresh(rawTps, TPS_LAG_NANOS);
                }
            } else {
                double prev = Double.longBitsToDouble(lastTpsBits.get());
                long prevTime = lastTpsSampleNanos.get();
                if (!Double.isNaN(prev) && prevTime != Long.MIN_VALUE) {
                    tpsSignal = ComputeSignals.SampledSignal.stale(prev, TPS_LAG_NANOS, Math.max(0L, nowNanos - prevTime));
                }
            }
        }

        // 2. Process CPU load and RTP async CPU share
        ComputeSignals.SampledSignal procCpuSignal = sampleProcessCpuLoad();
        ComputeSignals.SampledSignal rtpShareSignal = sampleRtpAsyncCpuShare();

        // 3. Post-GC tenured heap usage (post-GC only, ADR-087 Section 4.1)
        long postGcBytes = HeapSampler.postGcTenuredUsedBytes();
        long maxHeapBytes = HeapSampler.tenuredMaxBytes();
        ComputeSignals.SampledSignal postGcSignal = (postGcBytes > 0L)
                ? ComputeSignals.SampledSignal.fresh((double) postGcBytes, HEAP_LAG_NANOS)
                : ComputeSignals.SampledSignal.neverSampled();

        // 4. Chunk-system latency & in-flight
        double ewmaLatency = GenerationBudget.server().ewmaLoadLatencyMs();
        double minLatency = GenerationBudget.server().minWindowedLoadLatencyMs(nowNanos);
        ComputeSignals.SampledSignal latencySignal = (!Double.isNaN(ewmaLatency) && ewmaLatency > 0.0)
                ? ComputeSignals.SampledSignal.fresh(ewmaLatency, CHUNK_LATENCY_LAG_NANOS)
                : ComputeSignals.SampledSignal.neverSampled();

        // 5. Per-region demand & held tickets
        Map<String, ComputeSignals.RegionDemand> regionDemands = new LinkedHashMap<>();
        int totalTickets = 0;
        int cellsPerHot = (2 * viewDistanceSelect + 1) * (2 * viewDistanceSelect + 1);
        SelectionAPI sel = ActiveSelection.get();
        if (sel != null) {
            for (Region region : sel.permRegionLookup.values()) {
                if (region == null || region.getWorld() == null) continue;
                int curHot = region.queueManager != null ? region.queueManager.keptLocations.size() : 0;
                int cfgHotCap = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, region.getSettings().activeChunkCap()));
                long deficit = region.cacheDeficit();
                double rate = region.sampleConsumptionRatePerTick(nowNanos);
                boolean idle10m = region.isIdleForTenMinutes(nowNanos);
                int consumers = region.distinctRecentConsumers(nowNanos);
                totalTickets += curHot * Math.max(1, cellsPerHot);
                regionDemands.put(region.name, new ComputeSignals.RegionDemand(
                        region.name, deficit, rate, curHot, cfgHotCap, viewDistanceSelect, idle10m, consumers));
            }
        }

        return new ComputeSignals(
                autoTune,
                observeOnly,
                regionalTicking,
                tickStalled,
                hardGatePaused,
                msptSignal,
                tpsSignal,
                procCpuSignal,
                rtpShareSignal,
                postGcSignal,
                maxHeapBytes,
                latencySignal,
                minLatency,
                GenerationBudget.server().inFlight(),
                totalTickets,
                TickBudget.asyncNanos(),
                TickBudget.syncNanos(),
                TickBudget.periodTicks(),
                ComputeSignals.DEFAULT_PROMOTIONS_PER_TURN,
                GenerationBudget.configuredServerCap(),
                minTps,
                maxMspt,
                maxHeapPercent,
                regionDemands
        );
    }

    private ComputeSignals.SampledSignal sampleProcessCpuLoad() {
        return sampleProcessCpuLoad(PROCESS_CPU_LOAD_METHOD, OS_BEAN);
    }

    public ComputeSignals.SampledSignal sampleProcessCpuLoad(Method loadMethod, Object osBean) {
        if (loadMethod == null || osBean == null) {
            return ComputeSignals.SampledSignal.neverSampled();
        }
        try {
            Object res = loadMethod.invoke(osBean);
            if (res instanceof Number n) {
                double load = n.doubleValue();
                if (!Double.isNaN(load) && load >= 0.0 && load <= 1.0) {
                    return ComputeSignals.SampledSignal.fresh(load, PROCESS_CPU_LAG_NANOS);
                }
            }
        } catch (Throwable e) {
            if (processCpuLoadDegraded.compareAndSet(false, true)) {
                CoreFallbackCbitCounters.recordLifecycleCleanupDegraded(
                        "RuntimeSignalSource.sampleProcessCpuLoad", unwrapInvocationError(e));
            }
        }
        return ComputeSignals.SampledSignal.neverSampled();
    }

    private ComputeSignals.SampledSignal sampleRtpAsyncCpuShare() {
        return sampleRtpAsyncCpuShare(PROCESS_CPU_TIME_METHOD, OS_BEAN);
    }

    public ComputeSignals.SampledSignal sampleRtpAsyncCpuShare(Method timeMethod, Object osBean) {
        if (timeMethod == null || osBean == null) {
            return ComputeSignals.SampledSignal.neverSampled();
        }
        try {
            Object res = timeMethod.invoke(osBean);
            if (res instanceof Number n) {
                long procNanos = n.longValue();
                if (procNanos > 0L) {
                    long rtpNanos = accumulatedRtpBgCpuNanos.get();
                    long prevRtp = lastSampledRtpBgCpuNanos.getAndSet(rtpNanos);
                    long prevProc = lastSampledProcessCpuNanos.getAndSet(procNanos);
                    if (prevProc > 0L && procNanos > prevProc) {
                        double share = Math.max(0.0, Math.min(1.0,
                                (double) Math.max(0L, rtpNanos - prevRtp) / (double) (procNanos - prevProc)));
                        return ComputeSignals.SampledSignal.fresh(share, PROCESS_CPU_LAG_NANOS);
                    }
                }
            }
        } catch (Throwable e) {
            if (processCpuTimeDegraded.compareAndSet(false, true)) {
                CoreFallbackCbitCounters.recordLifecycleCleanupDegraded(
                        "RuntimeSignalSource.sampleRtpAsyncCpuShare", unwrapInvocationError(e));
            }
        }
        return ComputeSignals.SampledSignal.neverSampled();
    }

    /** Clears once-per-session CBIT latches for unit test isolation. */
    public void resetDegradedLatches() {
        processCpuLoadDegraded.set(false);
        processCpuTimeDegraded.set(false);
        threadCpuTimeDegraded.set(false);
        configLookupDegraded.set(false);
        metricsBindingDegraded.set(false);
    }

    private static Throwable unwrapInvocationError(Throwable error) {
        if (error instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        return error;
    }
}
