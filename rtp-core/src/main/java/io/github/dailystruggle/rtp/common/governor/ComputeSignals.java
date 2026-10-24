package io.github.dailystruggle.rtp.common.governor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Immutable snapshot of all runtime signals, configured ceilings, and per-region demand inputs
 * sampled at the start of a {@link ComputeGovernor} evaluation (ADR-087 Section 2 & 4.1).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor)
 */
public record ComputeSignals(
        boolean autoTune,
        boolean observeOnly,
        boolean regionalTicking,
        boolean tickStalled,
        boolean hardGatePaused,
        SampledSignal mspt,
        SampledSignal meanTps,
        SampledSignal processCpuLoad,
        SampledSignal rtpAsyncCpuShare,
        SampledSignal postGcTenuredUsedBytes,
        long maxHeapBytes,
        SampledSignal chunkLoadLatencyMs,
        double minWindowedChunkLoadLatencyMs,
        int generationInFlight,
        int totalHeldTickets,
        long configuredAsyncNanos,
        long configuredSyncNanos,
        long configuredPeriodTicks,
        int configuredPromotionsPerTurn,
        int configuredGenerationCap,
        double minTps,
        double maxMspt,
        double maxHeapPercent,
        Map<String, RegionDemand> regions
) {

    public static final long DEFAULT_ASYNC_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    public static final long DEFAULT_SYNC_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    public static final long DEFAULT_PERIOD_TICKS = 20L;
    public static final int DEFAULT_PROMOTIONS_PER_TURN = 8;
    public static final int DEFAULT_GENERATION_CAP = 2;
    public static final double DEFAULT_MIN_TPS = 19.0;
    public static final double DEFAULT_MAX_MSPT = 45.0;
    public static final double DEFAULT_MAX_HEAP_PERCENT = 85.0;

    public ComputeSignals {
        if (mspt == null) mspt = SampledSignal.neverSampled();
        if (meanTps == null) meanTps = SampledSignal.neverSampled();
        if (processCpuLoad == null) processCpuLoad = SampledSignal.neverSampled();
        if (rtpAsyncCpuShare == null) rtpAsyncCpuShare = SampledSignal.neverSampled();
        if (postGcTenuredUsedBytes == null) postGcTenuredUsedBytes = SampledSignal.neverSampled();
        if (chunkLoadLatencyMs == null) chunkLoadLatencyMs = SampledSignal.neverSampled();
        configuredAsyncNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), configuredAsyncNanos);
        configuredSyncNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), configuredSyncNanos);
        configuredPeriodTicks = Math.max(1L, configuredPeriodTicks);
        configuredPromotionsPerTurn = Math.max(1, configuredPromotionsPerTurn);
        configuredGenerationCap = Math.max(1, configuredGenerationCap);
        regions = (regions == null || regions.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(regions));
    }

    /**
     * Single scalar telemetry reading with its {@link SignalState}, averaging window lag {@code L_s},
     * and age since last fresh sample (ADR-087 Section 4.1).
     *
     * @param value          sampled numeric value ({@code Double.NaN} when unsampled)
     * @param state          sampling state ({@code NEVER_SAMPLED}, {@code FRESH}, {@code STALE})
     * @param lagNanos       signal averaging window lag {@code L_s} in nanoseconds
     * @param staleForNanos  duration in nanoseconds that this signal has been stale (0 when fresh/unsampled)
     */
    public record SampledSignal(double value, SignalState state, long lagNanos, long staleForNanos) {
        public SampledSignal {
            if (state == null) state = SignalState.NEVER_SAMPLED;
            lagNanos = Math.max(0L, lagNanos);
            staleForNanos = Math.max(0L, staleForNanos);
        }

        public static SampledSignal neverSampled() {
            return new SampledSignal(Double.NaN, SignalState.NEVER_SAMPLED, 0L, 0L);
        }

        public static SampledSignal fresh(double value, long lagNanos) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return neverSampled();
            }
            return new SampledSignal(value, SignalState.FRESH, lagNanos, 0L);
        }

        public static SampledSignal stale(double lastValue, long lagNanos, long staleForNanos) {
            return new SampledSignal(lastValue, SignalState.STALE, lagNanos, staleForNanos);
        }

        public boolean isFresh() {
            return state == SignalState.FRESH && !Double.isNaN(value) && !Double.isInfinite(value);
        }

        public boolean isStale() {
            return state == SignalState.STALE;
        }

        public boolean isNeverSampled() {
            return state == SignalState.NEVER_SAMPLED;
        }
    }

    /**
     * Per-RTP-region demand and capacity snapshot (ADR-087 Section 4.1, 4.3, ADR-124).
     *
     * @param region                 region name
     * @param deficit                cache deficit {@code D_r} (entries below configured target)
     * @param consumptionRatePerTick EWMA of locations consumed per tick {@code lambda_r}
     * @param currentHot             current hot (kept) entry count in this region
     * @param configuredHotCap       configured {@code activeChunkCap} ceiling for this region
     * @param viewDistanceSelect     configured {@code viewDistanceSelect} radius in chunks
     * @param idleForTenMinutes      {@code true} when {@code lambda_r == 0} for at least 10 minutes
     * @param distinctConsumers      distinct active players {@code n_r} in the last 10 minutes (ADR-124; >= 1)
     */
    public record RegionDemand(
            String region,
            long deficit,
            double consumptionRatePerTick,
            int currentHot,
            int configuredHotCap,
            int viewDistanceSelect,
            boolean idleForTenMinutes,
            int distinctConsumers
    ) {
        public RegionDemand {
            if (region == null) region = "";
            deficit = Math.max(0L, deficit);
            if (Double.isNaN(consumptionRatePerTick) || Double.isInfinite(consumptionRatePerTick) || consumptionRatePerTick < 0.0) {
                consumptionRatePerTick = 0.0;
            }
            currentHot = Math.max(0, currentHot);
            configuredHotCap = Math.max(0, configuredHotCap);
            viewDistanceSelect = Math.max(0, viewDistanceSelect);
            distinctConsumers = Math.max(1, distinctConsumers);
        }

        public RegionDemand(String region,
                            long deficit,
                            double consumptionRatePerTick,
                            int currentHot,
                            int configuredHotCap,
                            int viewDistanceSelect,
                            boolean idleForTenMinutes) {
            this(region, deficit, consumptionRatePerTick, currentHot, configuredHotCap, viewDistanceSelect, idleForTenMinutes, 1);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean autoTune = true;
        private boolean observeOnly = false;
        private boolean regionalTicking = false;
        private boolean tickStalled = false;
        private boolean hardGatePaused = false;
        private SampledSignal mspt = SampledSignal.neverSampled();
        private SampledSignal meanTps = SampledSignal.neverSampled();
        private SampledSignal processCpuLoad = SampledSignal.neverSampled();
        private SampledSignal rtpAsyncCpuShare = SampledSignal.neverSampled();
        private SampledSignal postGcTenuredUsedBytes = SampledSignal.neverSampled();
        private long maxHeapBytes = 0L;
        private SampledSignal chunkLoadLatencyMs = SampledSignal.neverSampled();
        private double minWindowedChunkLoadLatencyMs = Double.NaN;
        private int generationInFlight = 0;
        private int totalHeldTickets = 0;
        private long configuredAsyncNanos = DEFAULT_ASYNC_NANOS;
        private long configuredSyncNanos = DEFAULT_SYNC_NANOS;
        private long configuredPeriodTicks = DEFAULT_PERIOD_TICKS;
        private int configuredPromotionsPerTurn = DEFAULT_PROMOTIONS_PER_TURN;
        private int configuredGenerationCap = DEFAULT_GENERATION_CAP;
        private double minTps = DEFAULT_MIN_TPS;
        private double maxMspt = DEFAULT_MAX_MSPT;
        private double maxHeapPercent = DEFAULT_MAX_HEAP_PERCENT;
        private final Map<String, RegionDemand> regions = new LinkedHashMap<>();

        public Builder autoTune(boolean autoTune) {
            this.autoTune = autoTune;
            return this;
        }

        public Builder observeOnly(boolean observeOnly) {
            this.observeOnly = observeOnly;
            return this;
        }

        public Builder regionalTicking(boolean regionalTicking) {
            this.regionalTicking = regionalTicking;
            return this;
        }

        public Builder tickStalled(boolean tickStalled) {
            this.tickStalled = tickStalled;
            return this;
        }

        public Builder hardGatePaused(boolean hardGatePaused) {
            this.hardGatePaused = hardGatePaused;
            return this;
        }

        public Builder mspt(SampledSignal mspt) {
            this.mspt = mspt;
            return this;
        }

        public Builder meanTps(SampledSignal meanTps) {
            this.meanTps = meanTps;
            return this;
        }

        public Builder processCpuLoad(SampledSignal processCpuLoad) {
            this.processCpuLoad = processCpuLoad;
            return this;
        }

        public Builder rtpAsyncCpuShare(SampledSignal rtpAsyncCpuShare) {
            this.rtpAsyncCpuShare = rtpAsyncCpuShare;
            return this;
        }

        public Builder postGcTenuredUsedBytes(SampledSignal postGcTenuredUsedBytes) {
            this.postGcTenuredUsedBytes = postGcTenuredUsedBytes;
            return this;
        }

        public Builder maxHeapBytes(long maxHeapBytes) {
            this.maxHeapBytes = maxHeapBytes;
            return this;
        }

        public Builder chunkLoadLatencyMs(SampledSignal chunkLoadLatencyMs) {
            this.chunkLoadLatencyMs = chunkLoadLatencyMs;
            return this;
        }

        public Builder minWindowedChunkLoadLatencyMs(double minWindowedChunkLoadLatencyMs) {
            this.minWindowedChunkLoadLatencyMs = minWindowedChunkLoadLatencyMs;
            return this;
        }

        public Builder generationInFlight(int generationInFlight) {
            this.generationInFlight = generationInFlight;
            return this;
        }

        public Builder totalHeldTickets(int totalHeldTickets) {
            this.totalHeldTickets = totalHeldTickets;
            return this;
        }

        public Builder configuredAsyncNanos(long configuredAsyncNanos) {
            this.configuredAsyncNanos = configuredAsyncNanos;
            return this;
        }

        public Builder configuredSyncNanos(long configuredSyncNanos) {
            this.configuredSyncNanos = configuredSyncNanos;
            return this;
        }

        public Builder configuredPeriodTicks(long configuredPeriodTicks) {
            this.configuredPeriodTicks = configuredPeriodTicks;
            return this;
        }

        public Builder configuredPromotionsPerTurn(int configuredPromotionsPerTurn) {
            this.configuredPromotionsPerTurn = configuredPromotionsPerTurn;
            return this;
        }

        public Builder configuredGenerationCap(int configuredGenerationCap) {
            this.configuredGenerationCap = configuredGenerationCap;
            return this;
        }

        public Builder minTps(double minTps) {
            this.minTps = minTps;
            return this;
        }

        public Builder maxMspt(double maxMspt) {
            this.maxMspt = maxMspt;
            return this;
        }

        public Builder maxHeapPercent(double maxHeapPercent) {
            this.maxHeapPercent = maxHeapPercent;
            return this;
        }

        public Builder region(RegionDemand demand) {
            if (demand != null) {
                this.regions.put(demand.region(), demand);
            }
            return this;
        }

        public Builder regions(Map<String, RegionDemand> map) {
            this.regions.clear();
            if (map != null) {
                this.regions.putAll(map);
            }
            return this;
        }

        public ComputeSignals build() {
            return new ComputeSignals(
                    autoTune,
                    observeOnly,
                    regionalTicking,
                    tickStalled,
                    hardGatePaused,
                    mspt,
                    meanTps,
                    processCpuLoad,
                    rtpAsyncCpuShare,
                    postGcTenuredUsedBytes,
                    maxHeapBytes,
                    chunkLoadLatencyMs,
                    minWindowedChunkLoadLatencyMs,
                    generationInFlight,
                    totalHeldTickets,
                    configuredAsyncNanos,
                    configuredSyncNanos,
                    configuredPeriodTicks,
                    configuredPromotionsPerTurn,
                    configuredGenerationCap,
                    minTps,
                    maxMspt,
                    maxHeapPercent,
                    regions
            );
        }
    }
}
