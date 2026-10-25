package io.github.dailystruggle.rtp.common.governor;

import io.github.dailystruggle.rtp.common.predict.AimdLimiter;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.rtp.common.predict.EwmaLinearFit;
import io.github.dailystruggle.rtp.common.predict.WaterFillingAllocator;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;

/**
 * Adaptive Compute Governor evaluating server headroom and regional demand to publish an
 * immutable {@link ComputeBudget} snapshot (ADR-087).
 *
 * <p>Evaluated at most once every {@value #EVAL_INTERVAL_MS} ms at the start of an async pulse.
 * Pure control law over {@link ComputeSignals}; holds zero locks and creates zero threads
 * (ADR-087 Principle 6 & 7, Invariant I8).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor)
 * @trace REQ-RTP-OPS-001
 * @trace REQ-RTP-OPS-002
 * @invariant I2: Per-pulse and windowed spend bounded by configured limits with overrun carry-forward
 * @invariant I3: autoTune disabled publishes configured values
 * @invariant I4: Player-facing budgets, queue order, and admission rules unchanged
 * @invariant I5: Single-loop hosts start no background sync work while B_cpu < 1 ms
 * @invariant I6: Never-sampled signals leave knobs at configured values; stale signals never raise them
 * @invariant I8: Zero thread creation and zero locks in governor package and actuation paths
 * @invariant I11: Windowed regional delivery bounded by plan integral plus initial credit cap
 * @invariant I12: Fresh non-breaching signals converge within max(5 s, L) + 50 s
 */
public final class ComputeGovernor {

    public static final String OBSERVE_PROPERTY = "rtp.governor.observe";
    public static final long EVAL_INTERVAL_MS = 250L;
    public static final long EVAL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(EVAL_INTERVAL_MS);
    public static final long LOG_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long FLOOR_WARNING_NANOS = TimeUnit.MINUTES.toNanos(10);
    public static final long STALE_WARNING_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long CONTRACTION_EXCLUSION_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long MEMORY_FIT_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(10);
    public static final long NANOS_PER_TICK = TimeUnit.MILLISECONDS.toNanos(50);
    public static final long ONE_MS_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    public static final double DEFAULT_CHUNK_MEMORY_PRIOR_BYTES = 256.0 * 1024.0;
    public static final double HEADROOM_SAFETY_FACTOR = 0.5;
    public static final double ASYNC_PLAN_MARGIN = 1.25;
    public static final double PROCESS_CPU_SOFT_TARGET = 0.90;
    public static final double MIN_RTP_ASYNC_CPU_SHARE = 0.10;
    public static final long DEFICIT_SMOOTHING_MIN_TICKS = 600L;

    private static volatile ComputeGovernor active = new ComputeGovernor();

    /** Returns the process-wide active governor instance. */
    @NotNull
    public static ComputeGovernor active() {
        return active;
    }

    /** Replaces the process-wide active governor instance (for tests). */
    public static void setActive(ComputeGovernor governor) {
        if (governor != null) {
            active = governor;
        }
    }

    /** Returns the latest published {@link ComputeBudget} snapshot without locks. */
    public static ComputeBudget currentBudget() {
        return active.budget();
    }

    /** Returns {@code true} when {@code -Drtp.governor.observe=true} is set. */
    public static boolean isObservePropertyEnabled() {
        return Boolean.getBoolean(OBSERVE_PROPERTY);
    }

    private record RegionState(
            double credit,
            double plannedRate,
            double creditCap,
            int publishedHotCap,
            AimdLimiter hotLimiter
    ) {}

    private volatile SignalSource signalSource;
    private volatile ComputeBudget currentBudget = ComputeBudget.configuredDefault();
    private volatile ComputeSignals lastSignals;
    private volatile Boolean useConsumerAttributionOverride = null;
    private final ConsumerLedger consumerLedger = new ConsumerLedger();

    private final AtomicLong lastEvalNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastCreditUpdateNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastEvalFailureLogNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong evaluationFailures = new AtomicLong(0L);
    private final AtomicReference<String> lastFailureMessage = new AtomicReference<>("");

    // Overrun carry-forward debts (ADR-087 Section 4.3 items 3 & 4, Invariant I2)
    private final AtomicLong asyncDebtNanos = new AtomicLong(0L);
    private final AtomicLong syncDebtNanos = new AtomicLong(0L);

    // Measured unit costs & pulse remainders (EWMA alpha = 0.1, window = 19)
    private final EwmaEstimator deltaTAsync = EwmaEstimator.ofAlpha(0.1);
    private final EwmaEstimator deltaTTick = EwmaEstimator.ofAlpha(0.1, 0.0);
    private final EwmaEstimator deltaTProm = EwmaEstimator.ofAlpha(0.1);
    private final EwmaEstimator bgAsyncRemainder = EwmaEstimator.ofAlpha(0.1);
    private final EwmaEstimator rtpBgTickMs = EwmaEstimator.ofAlpha(0.1, 0.0);
    private final EwmaEstimator chunksPerEntry = EwmaEstimator.ofAlpha(0.1, 1.0);
    private final double chunkMemoryPriorBytes;
    private final EwmaLinearFit.WindowedOlsSlope chunkMemorySlope;

    // Limiters (ADR-087 Section 4.4)
    private final AimdLimiter asyncLimiter;
    private final AimdLimiter syncLimiter;
    private final AimdLimiter generationLimiter;
    private final AimdLimiter hotScaleLimiter;

    // Control state updated on evaluation
    private final AtomicLong lastBCpuBits = new AtomicLong(Double.doubleToRawLongBits(Double.NaN));
    private final AtomicInteger consecutiveProcCpuHigh = new AtomicInteger(0);
    private final AtomicBoolean asyncWasIdle = new AtomicBoolean(false);
    private final AtomicLong publishedAsyncNanos = new AtomicLong(ComputeSignals.DEFAULT_ASYNC_NANOS);
    private final ConcurrentHashMap<String, RegionState> regionStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> turnAsyncCarryNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GovernorReason> lastLoggedReason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastLogTimeNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> breachCountsBySignal = new ConcurrentHashMap<>();

    // ADR-124 Go/No-Go 1 observe counters (constrained refill frequency & consumer skew)
    private final AtomicLong evaluationsTotal = new AtomicLong(0L);
    private final AtomicLong constrainedEvaluationsTotal = new AtomicLong(0L);
    private final AtomicLong maxConsumerSkewBits = new AtomicLong(Double.doubleToRawLongBits(1.0));
    private volatile Map<String, Integer> lastDistinctConsumers = Collections.emptyMap();
    private volatile Map<String, Double> lastWouldBeAttributedRates = Collections.emptyMap();

    public ComputeGovernor() {
        this(null, DEFAULT_CHUNK_MEMORY_PRIOR_BYTES);
    }

    public ComputeGovernor(SignalSource signalSource) {
        this(signalSource, DEFAULT_CHUNK_MEMORY_PRIOR_BYTES);
    }

    public ComputeGovernor(SignalSource signalSource, double chunkMemoryPriorBytes) {
        this.signalSource = signalSource;
        this.chunkMemoryPriorBytes = Math.max(1024.0, chunkMemoryPriorBytes);
        this.chunkMemorySlope = new EwmaLinearFit.WindowedOlsSlope(
                MEMORY_FIT_WINDOW_NANOS,
                CONTRACTION_EXCLUSION_NANOS,
                20,
                0.25,
                this.chunkMemoryPriorBytes,
                this.chunkMemoryPriorBytes,
                4.0 * this.chunkMemoryPriorBytes
        );
        this.asyncLimiter = new AimdLimiter(ONE_MS_NANOS, ComputeSignals.DEFAULT_ASYNC_NANOS);
        this.syncLimiter = new AimdLimiter(ONE_MS_NANOS, ComputeSignals.DEFAULT_SYNC_NANOS);
        this.generationLimiter = new AimdLimiter(1.0, ComputeSignals.DEFAULT_GENERATION_CAP);
        this.hotScaleLimiter = new AimdLimiter(0.10, 1.0);
    }

    public void setSignalSource(SignalSource signalSource) {
        this.signalSource = signalSource;
    }

    public SignalSource signalSource() {
        return signalSource;
    }

    public void setUseConsumerAttribution(boolean enabled) {
        this.useConsumerAttributionOverride = enabled;
    }

    public boolean useConsumerAttribution() {
        Boolean override = this.useConsumerAttributionOverride;
        if (override != null) {
            return override;
        }
        ComputeSignals s = this.lastSignals;
        return s == null || (s.autoTune() && !s.observeOnly());
    }

    public ConsumerLedger consumerLedger() {
        return consumerLedger;
    }

    /**
     * Returns the current measured or fallback unit refill cost ({@code DeltaT_async + DeltaT_tick})
     * in nanoseconds for {@link ConsumerLedger#recordConsumption} (ADR-124 Section 1).
     */
    public double estimatedUnitCostNanos() {
        ComputeBudget b = this.currentBudget;
        long aCfg = (b != null) ? b.configuredAsyncNanos() : ComputeSignals.DEFAULT_ASYNC_NANOS;
        int promCfg = (b != null) ? b.configuredPromotionsPerTurn() : ComputeSignals.DEFAULT_PROMOTIONS_PER_TURN;
        return resolveDeltaTAsyncNanos(aCfg, promCfg) + resolveDeltaTTickNanos();
    }

    public ComputeBudget budget() {
        return currentBudget;
    }

    public ComputeSignals lastSignals() {
        return lastSignals;
    }

    public long evaluationFailures() {
        return evaluationFailures.get();
    }

    public String lastFailureMessage() {
        return lastFailureMessage.get();
    }

    public Map<String, Long> breachCountsBySignal() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(breachCountsBySignal));
    }

    public long evaluationsTotal() {
        return evaluationsTotal.get();
    }

    public long constrainedEvaluationsTotal() {
        return constrainedEvaluationsTotal.get();
    }

    public double maxConsumerSkewRatio() {
        return Double.longBitsToDouble(maxConsumerSkewBits.get());
    }

    public Map<String, Double> wouldBeAttributedPlannedRates() {
        return lastWouldBeAttributedRates;
    }

    /**
     * Counts the number of driving telemetry signals currently in {@link ComputeSignals.SignalHealth#STALE}
     * state (ADR-087 Section 6.3 {@code rtp_governor_stale_signals}).
     */
    public int staleSignalsCount() {
        ComputeSignals s = this.lastSignals;
        if (s == null) return 0;
        int count = 0;
        if (s.mspt().isStale()) count++;
        if (s.meanTps().isStale()) count++;
        if (s.processCpuLoad().isStale()) count++;
        if (s.rtpAsyncCpuShare().isStale()) count++;
        if (s.postGcTenuredUsedBytes().isStale()) count++;
        if (s.chunkLoadLatencyMs().isStale()) count++;
        return count;
    }

    /**
     * Captures an immutable {@link GovernorSnapshot} for {@code metrics-api} and {@code /rtp info}
     * (ADR-087 Section 6.2 & 6.3, ADR-124 Section 7).
     */
    public GovernorSnapshot snapshot() {
        ComputeBudget b = this.currentBudget;
        if (b == null) {
            b = ComputeBudget.configuredDefault();
        }
        ComputeSignals s = this.lastSignals;
        int regInflight = (s != null) ? Math.max(0, s.generationInFlight()) : 0;
        int regInflightCap = (s != null && s.configuredGenerationCap() > 0)
                ? s.configuredGenerationCap()
                : b.configuredGenerationCap();
        double bgMs = rtpBgTickMs.mean();
        if (Double.isNaN(bgMs) || bgMs < 0.0) bgMs = 0.0;
        long snapNanos = lastEvalNanos.get();
        if (snapNanos == Long.MIN_VALUE) {
            snapNanos = System.nanoTime();
        }
        Map<String, Double> refillShares = ConsumerLedger.computeRegionRefillShares(b.regionPlannedRates());
        return new GovernorSnapshot(
                b.autoTune(),
                b.observeOnly(),
                b.configuredPeriodTicks(),
                b.effectivePeriodTicks(),
                b.periodReason(),
                TimeUnit.NANOSECONDS.toMillis(b.configuredAsyncNanos()),
                TimeUnit.NANOSECONDS.toMillis(b.effectiveAsyncNanos()),
                b.asyncReason(),
                TimeUnit.NANOSECONDS.toMillis(b.configuredSyncNanos()),
                TimeUnit.NANOSECONDS.toMillis(b.effectiveSyncNanos()),
                b.syncReason(),
                b.configuredPromotionsPerTurn(),
                b.effectivePromotionsPerTurn(),
                b.promotionsReason(),
                b.configuredGenerationCap(),
                b.effectiveGenerationCap(),
                b.generationReason(),
                b.regionHotCaps(),
                b.regionConfiguredHotCaps(),
                b.regionHotCapReasons(),
                b.regionUnitsPerTurn(),
                breachCountsBySignal(),
                staleSignalsCount(),
                regInflight,
                regInflightCap,
                bgMs,
                chunkMemoryEstimateBytes(),
                evaluationsTotal.get(),
                constrainedEvaluationsTotal.get(),
                maxConsumerSkewRatio(),
                lastDistinctConsumers,
                lastWouldBeAttributedRates,
                consumerLedger.costP99MsPerMin(snapNanos),
                refillShares,
                consumerLedger.topConsumers(5, snapNanos)
        );
    }

    /**
     * Returns the active governor's {@link GovernorSnapshot}, or {@link GovernorSnapshot#EMPTY} if none.
     */
    public static GovernorSnapshot currentSnapshot() {
        ComputeGovernor gov = active;
        return (gov != null) ? gov.snapshot() : GovernorSnapshot.EMPTY;
    }

    /**
     * Evaluates the control law if at least {@value #EVAL_INTERVAL_MS} ms have elapsed since the
     * last evaluation. Retains the previous snapshot and logs a rate-limited warning on failure (S-004).
     *
     * @param nowNanos monotonic timestamp in nanoseconds
     * @return current budget snapshot
     */
    public ComputeBudget maybeEvaluate(long nowNanos) {
        long last = lastEvalNanos.get();
        if (last != Long.MIN_VALUE && nowNanos - last < EVAL_INTERVAL_NANOS) {
            return currentBudget;
        }
        SignalSource src = this.signalSource;
        if (src == null) {
            return currentBudget;
        }
        try {
            ComputeSignals signals = src.sample(nowNanos);
            if (signals == null) {
                return currentBudget;
            }
            return evaluate(signals, nowNanos);
        } catch (Throwable t) {
            recordEvaluationFailure(nowNanos, t);
            return currentBudget;
        }
    }

    /**
     * Records an evaluation failure for CBIT diagnostics and rate-limited console logging (ADR-087 Section 7.2).
     */
    public void recordEvaluationFailure(long nowNanos, Throwable t) {
        evaluationFailures.incrementAndGet();
        String msg = t != null && t.getMessage() != null ? t.getMessage() : (t != null ? t.getClass().getSimpleName() : "unknown");
        lastFailureMessage.set(msg);
        long lastLog = lastEvalFailureLogNanos.get();
        if (lastLog == Long.MIN_VALUE || nowNanos - lastLog >= LOG_COOLDOWN_NANOS) {
            if (lastEvalFailureLogNanos.compareAndSet(lastLog, nowNanos)) {
                RTPRuntime.log(Level.WARNING, "[RTP] ComputeGovernor evaluation failed; keeping previous budget snapshot: " + msg, t);
            }
        }
    }

    /**
     * Evaluates the pure control law on {@code signals} at {@code nowNanos} and publishes the resulting
     * {@link ComputeBudget} snapshot.
     *
     * @param signals  sampled telemetry and configuration inputs
     * @param nowNanos monotonic clock reading in nanoseconds
     * @return published budget snapshot
     */
    public ComputeBudget evaluate(ComputeSignals signals, long nowNanos) {
        if (signals == null) {
            throw new IllegalArgumentException("ComputeSignals cannot be null");
        }
        this.lastSignals = signals;
        this.lastEvalNanos.set(nowNanos);

        long aCfg = signals.configuredAsyncNanos();
        long sCfg = signals.configuredSyncNanos();
        long periodCfg = signals.configuredPeriodTicks();
        int promCfg = signals.configuredPromotionsPerTurn();
        int genCfg = signals.configuredGenerationCap();

        // Update limiter bounds if configured ceilings changed
        asyncLimiter.updateBounds(Math.min(ONE_MS_NANOS, aCfg), aCfg);
        syncLimiter.updateBounds(Math.min(ONE_MS_NANOS, sCfg), sCfg);
        generationLimiter.updateBounds(1.0, genCfg);

        // 1. Accrue integral credit over the interval [prevCreditNanos, nowNanos] using the previous plan
        long prevCreditNanos = lastCreditUpdateNanos.getAndSet(nowNanos);
        double dtTicks = (prevCreditNanos == Long.MIN_VALUE)
                ? 0.0
                : Math.max(0.0, (double) (nowNanos - prevCreditNanos) / (double) NANOS_PER_TICK);
        accrueCredits(dtTicks);

        double tCpuMs = (signals.maxMspt() > 0.0) ? (0.8 * signals.maxMspt()) : 40.0;
        double tHeapFrac = (signals.maxHeapPercent() > 0.0 && signals.maxHeapPercent() < 100.0)
                ? ((signals.maxHeapPercent() - 10.0) / 100.0)
                : 0.80;

        // Fast path when autoTune is disabled and observeOnly is false (Invariant I3)
        if (!signals.autoTune() && !signals.observeOnly()) {
            asyncLimiter.reset();
            syncLimiter.reset();
            generationLimiter.reset();
            hotScaleLimiter.reset();
            publishedAsyncNanos.set(aCfg);
            Map<String, Integer> cfgUnits = new LinkedHashMap<>();
            Map<String, Integer> cfgHotCaps = new LinkedHashMap<>();
            Map<String, GovernorReason> cfgReasons = new LinkedHashMap<>();
            Map<String, Double> cfgPlans = new LinkedHashMap<>();
            Map<String, Double> cfgCredits = new LinkedHashMap<>();
            Map<String, Double> cfgCreditCaps = new LinkedHashMap<>();
            for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
                cfgUnits.put(rd.region(), Integer.MAX_VALUE);
                cfgHotCaps.put(rd.region(), rd.configuredHotCap());
                cfgReasons.put(rd.region(), GovernorReason.CONFIGURED);
                cfgPlans.put(rd.region(), 0.0);
                cfgCredits.put(rd.region(), 0.0);
                cfgCreditCaps.put(rd.region(), 0.0);
            }
            ComputeBudget disabled = new ComputeBudget(
                    false,
                    false,
                    aCfg, aCfg, GovernorReason.CONFIGURED,
                    sCfg, sCfg, GovernorReason.CONFIGURED,
                    periodCfg, periodCfg, GovernorReason.CONFIGURED,
                    promCfg, promCfg, GovernorReason.CONFIGURED,
                    genCfg, genCfg, GovernorReason.CONFIGURED,
                    tCpuMs,
                    Double.NaN,
                    0.0,
                    cfgUnits,
                    cfgHotCaps,
                    cfgHotCaps,
                    cfgReasons,
                    cfgPlans,
                    cfgCredits,
                    cfgCreditCaps
            );
            this.currentBudget = disabled;
            return disabled;
        }

        // 2. Compute single-loop CPU headroom B_cpu (skipped on Folia via regionalTicking, Section 4.1 & 4.3)
        double bCpuMs = computeCpuHeadroomMs(signals, tCpuMs);

        // 3. Evaluate breaches per signal (Section 4.3 & 4.4)
        boolean tickStall = signals.tickStalled();
        boolean tickBreach;
        long tickLagNanos;
        boolean tickStale;
        boolean tickNeverSampled;
        if (signals.regionalTicking()) {
            tickBreach = signals.meanTps().isFresh() && signals.minTps() > 0.0 && signals.meanTps().value() < signals.minTps();
            tickLagNanos = signals.meanTps().lagNanos();
            tickStale = signals.meanTps().isStale();
            tickNeverSampled = signals.meanTps().isNeverSampled();
        } else {
            // Raw MSPT including RTP's own background tick time (Section 4.4)
            tickBreach = signals.mspt().isFresh() && signals.mspt().value() > tCpuMs;
            tickLagNanos = signals.mspt().lagNanos();
            tickStale = signals.mspt().isStale();
            tickNeverSampled = signals.mspt().isNeverSampled();
        }

        boolean procHighNow = signals.processCpuLoad().isFresh()
                && signals.processCpuLoad().value() > PROCESS_CPU_SOFT_TARGET
                && (signals.rtpAsyncCpuShare().isNeverSampled()
                        || (signals.rtpAsyncCpuShare().isFresh() && signals.rtpAsyncCpuShare().value() >= MIN_RTP_ASYNC_CPU_SHARE));
        int procStreak = procHighNow ? consecutiveProcCpuHigh.incrementAndGet() : 0;
        if (!procHighNow) {
            consecutiveProcCpuHigh.set(0);
        }
        boolean processCpuBreach = procStreak >= 2;

        boolean heapBreach = signals.postGcTenuredUsedBytes().isFresh()
                && signals.maxHeapBytes() > 0L
                && signals.postGcTenuredUsedBytes().value() > (tHeapFrac * signals.maxHeapBytes());

        boolean chunkLatencyBreach = signals.chunkLoadLatencyMs().isFresh()
                && !Double.isNaN(signals.minWindowedChunkLoadLatencyMs())
                && signals.minWindowedChunkLoadLatencyMs() > 0.0
                && signals.chunkLoadLatencyMs().value() > 2.0 * signals.minWindowedChunkLoadLatencyMs();

        if (tickBreach) incrementBreach("tick");
        if (processCpuBreach) incrementBreach("process_cpu");
        if (heapBreach) incrementBreach("heap");
        if (chunkLatencyBreach) incrementBreach("chunk_latency");
        if (tickStall) incrementBreach("tick_stall");

        // 4. Async limiter l_A -> f_A (cuts on processCpuBreach or tickStall)
        boolean asyncBreach = processCpuBreach || tickStall;
        long asyncLag = processCpuBreach ? signals.processCpuLoad().lagNanos() : 0L;
        boolean asyncStale = signals.processCpuLoad().isStale() || signals.rtpAsyncCpuShare().isStale();
        boolean asyncNeverSampled = signals.processCpuLoad().isNeverSampled() && !tickStall;
        asyncLimiter.evaluate(aCfg, asyncBreach, asyncLag, asyncStale, asyncNeverSampled, nowNanos);
        double fA = clamp(asyncLimiter.rawLimit() / (double) aCfg,
                (double) Math.min(ONE_MS_NANOS, aCfg) / (double) aCfg,
                1.0);

        // 5. Single-loop background sync budget S_eff (Section 4.3 item 4, Invariant I5)
        long sEff;
        GovernorReason syncReason;
        if (signals.regionalTicking()) {
            sEff = sCfg;
            syncReason = GovernorReason.CONFIGURED;
        } else {
            boolean syncBreach = tickBreach || tickStall;
            long syncLag = tickBreach ? tickLagNanos : 0L;
            boolean syncNeverSampled = tickNeverSampled && !tickStall;
            if (!Double.isNaN(bCpuMs) && bCpuMs < 1.0) {
                syncLimiter.evaluate(ONE_MS_NANOS, syncBreach, syncLag, tickStale, syncNeverSampled, nowNanos);
                sEff = 0L;
                syncReason = (signals.hardGatePaused() || tickStall) ? GovernorReason.BACKSTOP : GovernorReason.CPU_HEADROOM;
            } else {
                double syncModelTarget = Double.isNaN(bCpuMs)
                        ? (double) sCfg
                        : clamp(bCpuMs * 1_000_000.0, (double) Math.min(ONE_MS_NANOS, sCfg), (double) sCfg);
                double pubSync = syncLimiter.evaluate(syncModelTarget, syncBreach, syncLag, tickStale, syncNeverSampled, nowNanos);
                sEff = clampLong(Math.round(pubSync), Math.min(ONE_MS_NANOS, sCfg), sCfg);
                if (sEff >= sCfg) {
                    syncReason = tickStale ? GovernorReason.STALE_SIGNAL : GovernorReason.CONFIGURED;
                } else if (tickStall || signals.hardGatePaused()) {
                    syncReason = GovernorReason.BACKSTOP;
                } else if (tickStale) {
                    syncReason = GovernorReason.STALE_SIGNAL;
                } else {
                    syncReason = GovernorReason.CPU_HEADROOM;
                }
            }
        }

        // 6. Deliverable demand d_bar_r, target unit rate r*, water-filling x_r, and credit c_r (Section 4.3 item 1)
        double deltaTAsync = Math.max(1_000.0, resolveDeltaTAsyncNanos(aCfg, promCfg));
        double deltaTTick = resolveDeltaTTickNanos();
        int uMax = Math.max(1, (int) Math.floor((double) aCfg / deltaTAsync));

        Map<String, Double> deliverableDemands = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Double> consumerWeights = new LinkedHashMap<>();
        Map<String, Integer> distinctConsumersMap = new LinkedHashMap<>();
        double sumDeliverableDemand = 0.0;
        int regionsWithDemand = 0;
        int minConsumersWithDemand = Integer.MAX_VALUE;
        int maxConsumersWithDemand = 0;
        long smoothingH = Math.max(DEFICIT_SMOOTHING_MIN_TICKS, periodCfg);

        boolean effectiveAttribution = useConsumerAttribution();

        for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
            double dBar = Math.min(rd.consumptionRatePerTick() + ((double) rd.deficit() / (double) periodCfg), (double) uMax);
            if (Double.isNaN(dBar) || dBar < 0.0) dBar = 0.0;
            deliverableDemands.put(rd.region(), dBar);
            sumDeliverableDemand += dBar;
            int nR = Math.max(Math.max(1, rd.distinctConsumers()), consumerLedger.distinctConsumers(rd.region(), nowNanos));
            distinctConsumersMap.put(rd.region(), nR);
            consumerWeights.put(rd.region(), (double) nR);
            if (dBar > 0.0) {
                regionsWithDemand++;
                minConsumersWithDemand = Math.min(minConsumersWithDemand, nR);
                maxConsumersWithDemand = Math.max(maxConsumersWithDemand, nR);
            }
            double w = effectiveAttribution
                    ? (double) nR
                    : (rd.consumptionRatePerTick() + ((double) rd.deficit() / (double) smoothingH));
            weights.put(rd.region(), Math.max(0.0, w));
        }

        double aHatBg = resolveEstimatedBgAsyncNanos(aCfg);
        double asyncRateCap = aHatBg / deltaTAsync;
        double tickRateCap = Double.POSITIVE_INFINITY;
        if (!signals.regionalTicking() && !Double.isNaN(bCpuMs) && deltaTTick >= 1_000.0) {
            tickRateCap = (bCpuMs < 1.0) ? 0.0 : ((bCpuMs * 1_000_000.0) / deltaTTick);
        }

        double rStar;
        if (sumDeliverableDemand <= 0.0 || signals.hardGatePaused() || tickStall) {
            rStar = 0.0;
        } else {
            rStar = Math.min(fA * Math.min(sumDeliverableDemand, asyncRateCap), tickRateCap);
            if (Double.isNaN(rStar) || Double.isInfinite(rStar) || rStar < 0.0) {
                rStar = 0.0;
            }
        }

        evaluationsTotal.incrementAndGet();
        if (sumDeliverableDemand > 0.0 && rStar + 1e-9 < sumDeliverableDemand) {
            constrainedEvaluationsTotal.incrementAndGet();
            if (regionsWithDemand > 0 && minConsumersWithDemand > 0 && minConsumersWithDemand != Integer.MAX_VALUE) {
                double skew = (double) maxConsumersWithDemand / (double) minConsumersWithDemand;
                maxConsumerSkewBits.updateAndGet(prevBits -> {
                    double prev = Double.longBitsToDouble(prevBits);
                    return (Double.isNaN(prev) || skew > prev) ? Double.doubleToRawLongBits(skew) : prevBits;
                });
            }
        }
        this.lastDistinctConsumers = Collections.unmodifiableMap(distinctConsumersMap);
        Map<String, Double> attributedPlans = computeWaterFilling(deliverableDemands, consumerWeights, rStar);
        this.lastWouldBeAttributedRates = Collections.unmodifiableMap(attributedPlans);

        Map<String, Double> plannedRates = effectiveAttribution
                ? attributedPlans
                : computeWaterFilling(deliverableDemands, weights, rStar);
        Map<String, Integer> unitsPerTurn = new LinkedHashMap<>();
        Map<String, Double> creditsOut = new LinkedHashMap<>();
        Map<String, Double> creditCapsOut = new LinkedHashMap<>();

        for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
            String rName = rd.region();
            double xR = plannedRates.getOrDefault(rName, 0.0);
            double bR = (xR > 0.0) ? Math.max(1.0, xR * (double) periodCfg) : 0.0;
            RegionState prev = regionStates.get(rName);
            double cR;
            int prevHotCap;
            AimdLimiter regHotLimiter;
            if (prev == null) {
                // First time seeing this region: if it already has a positive plan, initialize credit to b_r
                // so pulse 0 can serve a turn while keeping c_r(0) <= b_r(0) (Invariants I11 & I12).
                cR = (xR > 0.0) ? bR : 0.0;
                prevHotCap = rd.configuredHotCap();
                regHotLimiter = new AimdLimiter(Math.min(1, rd.configuredHotCap()), Math.max(0, rd.configuredHotCap()));
            } else {
                cR = Math.min(prev.credit(), bR);
                prevHotCap = prev.publishedHotCap();
                regHotLimiter = prev.hotLimiter();
                regHotLimiter.updateBounds(Math.min(1, rd.configuredHotCap()), Math.max(0, rd.configuredHotCap()));
            }
            int uR = (xR <= 0.0 && cR < 1.0) ? 0 : Math.min((int) Math.floor(cR), uMax);
            if (signals.hardGatePaused() || tickStall) {
                uR = 0;
            }
            regionStates.put(rName, new RegionState(cR, xR, bR, prevHotCap, regHotLimiter));
            unitsPerTurn.put(rName, Math.max(0, uR));
            creditsOut.put(rName, cR);
            creditCapsOut.put(rName, bR);
        }

        // 7. Background async budget A_eff (Section 4.3 item 3)
        long floorAsync = Math.min(ONE_MS_NANOS, aCfg);
        long aEff;
        GovernorReason asyncReason;
        if (signals.hardGatePaused() || tickStall) {
            aEff = floorAsync;
            asyncReason = GovernorReason.BACKSTOP;
            publishedAsyncNanos.set(aEff);
            asyncWasIdle.set(false);
        } else if (sumDeliverableDemand <= 0.0) {
            aEff = floorAsync;
            asyncReason = GovernorReason.IDLE;
            publishedAsyncNanos.set(aEff);
            asyncWasIdle.set(true);
        } else {
            double rawTarget = Math.min((double) aCfg,
                    Math.max((double) floorAsync, ASYNC_PLAN_MARGIN * Math.ceil(rStar) * deltaTAsync));
            long targetNanos = clampLong(Math.round(rawTarget), floorAsync, aCfg);
            boolean wasIdle = asyncWasIdle.getAndSet(false);
            long prevPub = publishedAsyncNanos.get();
            if (asyncStale && !asyncBreach && targetNanos > prevPub) {
                targetNanos = prevPub;
            } else if (!wasIdle && targetNanos > prevPub && targetNanos < aCfg) {
                // Apply 10% deadband on intermediate increases when recovering from a cut
                long threshold = prevPub + Math.round(prevPub * AimdLimiter.DEFAULT_DEADBAND_FRACTION);
                if (targetNanos <= threshold) {
                    targetNanos = prevPub;
                }
            }
            aEff = clampLong(targetNanos, floorAsync, aCfg);
            publishedAsyncNanos.set(aEff);
            if (aEff >= aCfg) {
                asyncReason = asyncStale ? GovernorReason.STALE_SIGNAL : GovernorReason.CONFIGURED;
            } else if (asyncStale && !asyncBreach) {
                asyncReason = GovernorReason.STALE_SIGNAL;
            } else if (fA < 1.0 - 1e-6 || processCpuBreach) {
                asyncReason = GovernorReason.ASYNC_CPU;
            } else if (!signals.regionalTicking() && tickRateCap < asyncRateCap) {
                asyncReason = GovernorReason.CPU_HEADROOM;
            } else {
                asyncReason = GovernorReason.IDLE;
            }
        }

        // 8. Promotions per turn p_eff (Section 4.3 item 2)
        int pEff;
        GovernorReason promotionsReason;
        if (signals.regionalTicking()) {
            pEff = promCfg;
            promotionsReason = GovernorReason.CONFIGURED;
        } else if (sEff <= 0L) {
            pEff = 0;
            promotionsReason = syncReason;
        } else {
            double deltaTProm = Math.max(1_000.0, resolveDeltaTPromNanos(sCfg, promCfg));
            long rawP = (long) Math.floor((double) sEff / ((double) Math.max(1, regionsWithDemand) * deltaTProm));
            pEff = (int) clampLong(rawP, 1L, promCfg);
            promotionsReason = (pEff >= promCfg) ? GovernorReason.CONFIGURED : syncReason;
        }

        // 9. Hot cache capacity hotCap(region) (Section 4.3 item 6)
        updateChunkMemoryEstimate(signals, nowNanos);
        Map<String, Integer> regionHotCaps = new LinkedHashMap<>();
        Map<String, Integer> regionConfiguredHotCaps = new LinkedHashMap<>();
        Map<String, GovernorReason> regionHotReasons = new LinkedHashMap<>();
        computeHotCaps(signals, tHeapFrac, heapBreach, nowNanos, effectiveAttribution, attributedPlans,
                distinctConsumersMap, regionHotCaps, regionConfiguredHotCaps, regionHotReasons);

        // 10. Generation concurrency g_eff (Section 4.3 item 7)
        boolean genBreach = chunkLatencyBreach || processCpuBreach || tickBreach || tickStall;
        long genLag = 0L;
        if (chunkLatencyBreach) genLag = Math.max(genLag, signals.chunkLoadLatencyMs().lagNanos());
        if (processCpuBreach) genLag = Math.max(genLag, signals.processCpuLoad().lagNanos());
        if (tickBreach) genLag = Math.max(genLag, tickLagNanos);
        if (!genBreach) {
            genLag = Math.max(signals.chunkLoadLatencyMs().lagNanos(),
                    Math.max(signals.processCpuLoad().lagNanos(), tickLagNanos));
        }
        boolean genStale = signals.chunkLoadLatencyMs().isStale()
                || signals.processCpuLoad().isStale()
                || tickStale;
        boolean genNeverSampled = signals.chunkLoadLatencyMs().isNeverSampled()
                && signals.processCpuLoad().isNeverSampled()
                && tickNeverSampled
                && !tickStall;
        double pubGen = generationLimiter.evaluate(
                genCfg, genBreach, genLag, genStale, genNeverSampled, nowNanos);
        int gEff = (int) clampLong(Math.round(pubGen), 1L, genCfg);
        GovernorReason generationReason;
        if (gEff >= genCfg) {
            generationReason = genStale ? GovernorReason.STALE_SIGNAL : GovernorReason.CONFIGURED;
        } else if (tickStall || signals.hardGatePaused()) {
            generationReason = GovernorReason.BACKSTOP;
        } else if (genStale && !genBreach) {
            generationReason = GovernorReason.STALE_SIGNAL;
        } else if (chunkLatencyBreach) {
            generationReason = GovernorReason.CHUNK_LATENCY;
        } else if (processCpuBreach) {
            generationReason = GovernorReason.ASYNC_CPU;
        } else {
            generationReason = GovernorReason.CPU_HEADROOM;
        }

        // 11. Idle period cadence (Section 4.3 item 1)
        long effectivePeriodTicks = (regionsWithDemand == 0) ? (8L * periodCfg) : periodCfg;
        GovernorReason periodReason = (regionsWithDemand == 0) ? GovernorReason.IDLE : GovernorReason.CONFIGURED;

        ComputeBudget nextBudget = new ComputeBudget(
                signals.autoTune(),
                signals.observeOnly(),
                aCfg, aEff, asyncReason,
                sCfg, sEff, syncReason,
                periodCfg, effectivePeriodTicks, periodReason,
                promCfg, pEff, promotionsReason,
                genCfg, gEff, generationReason,
                tCpuMs,
                bCpuMs,
                rStar,
                unitsPerTurn,
                regionHotCaps,
                regionConfiguredHotCaps,
                regionHotReasons,
                plannedRates,
                creditsOut,
                creditCapsOut
        );

        this.currentBudget = nextBudget;
        logTransitionsIfNeeded(nextBudget, nowNanos);
        return nextBudget;
    }

    private double computeCpuHeadroomMs(ComputeSignals signals, double tCpuMs) {
        if (signals.regionalTicking()) {
            return Double.NaN;
        }
        ComputeSignals.SampledSignal mspt = signals.mspt();
        if (mspt.isNeverSampled()) {
            return Double.NaN;
        }
        double rtpBgMs = rtpBgTickMs.mean();
        if (Double.isNaN(rtpBgMs) || rtpBgMs < 0.0) rtpBgMs = 0.0;
        double rawVal = mspt.value();
        double computed = Double.isNaN(rawVal)
                ? Double.NaN
                : HEADROOM_SAFETY_FACTOR * Math.max(0.0, tCpuMs - Math.max(0.0, rawVal - rtpBgMs));
        if (mspt.isStale()) {
            double prev = Double.longBitsToDouble(lastBCpuBits.get());
            if (!Double.isNaN(prev)) {
                return Double.isNaN(computed) ? prev : Math.min(prev, computed);
            }
        }
        if (!Double.isNaN(computed)) {
            lastBCpuBits.set(Double.doubleToRawLongBits(computed));
        }
        return computed;
    }

    private void computeHotCaps(
            ComputeSignals signals,
            double tHeapFrac,
            boolean heapBreach,
            long nowNanos,
            boolean effectiveAttribution,
            Map<String, Double> attributedPlans,
            Map<String, Integer> distinctConsumersMap,
            Map<String, Integer> regionHotCaps,
            Map<String, Integer> regionConfiguredHotCaps,
            Map<String, GovernorReason> regionHotReasons
    ) {
        ComputeSignals.SampledSignal postGc = signals.postGcTenuredUsedBytes();
        boolean heapNeverSampled = postGc.isNeverSampled() || signals.maxHeapBytes() <= 0L;
        boolean heapStale = postGc.isStale();
        long heapLag = postGc.lagNanos();

        if (heapNeverSampled) {
            for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
                regionConfiguredHotCaps.put(rd.region(), rd.configuredHotCap());
                regionHotCaps.put(rd.region(), rd.configuredHotCap());
                regionHotReasons.put(rd.region(), GovernorReason.CONFIGURED);
            }
            return;
        }

        int totalCurrentHot = 0;
        int maxVdSelect = 0;
        int totalCfgHot = 0;
        for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
            totalCurrentHot += rd.currentHot();
            maxVdSelect = Math.max(maxVdSelect, rd.viewDistanceSelect());
            totalCfgHot += rd.configuredHotCap();
            regionConfiguredHotCaps.put(rd.region(), rd.configuredHotCap());
        }

        double nChunks = Math.max((2.0 * maxVdSelect + 1.0) * (2.0 * maxVdSelect + 1.0),
                chunksPerEntry.mean());
        double mChunk = chunkMemorySlope.estimate();
        double deltaMUnit = Math.max(1024.0, nChunks * mChunk);

        double headroomBytes = Math.max(0.0, (tHeapFrac * signals.maxHeapBytes()) - postGc.value());
        double bMem = headroomBytes + ((double) totalCurrentHot * deltaMUnit);
        int nTotal = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) Math.floor(bMem / deltaMUnit)));

        double scale = hotScaleLimiter.evaluate(1.0, heapBreach, heapLag, heapStale, false, nowNanos);
        int effectiveTotal = (int) Math.floor(Math.min((double) totalCfgHot, (double) nTotal) * scale);

        // Allocate effectiveTotal across regions honoring floor min(1, cfgCap) and idle ceiling ceil(cfgCap / 4)
        Map<String, Integer> ceilings = new LinkedHashMap<>();
        Map<String, Integer> floors = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Double> extraHeadrooms = new LinkedHashMap<>();
        int sumFloors = 0;

        for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
            int cfgCap = rd.configuredHotCap();
            int floor = Math.min(1, cfgCap);
            int ceiling = rd.idleForTenMinutes()
                    ? Math.max(floor, (int) Math.ceil(cfgCap / 4.0))
                    : cfgCap;
            floors.put(rd.region(), floor);
            ceilings.put(rd.region(), ceiling);
            extraHeadrooms.put(rd.region(), (double) Math.max(0, ceiling - floor));
            sumFloors += floor;
            double w;
            if (effectiveAttribution) {
                double cappedPlan = (attributedPlans != null) ? attributedPlans.getOrDefault(rd.region(), 0.0) : 0.0;
                int nR = (distinctConsumersMap != null)
                        ? distinctConsumersMap.getOrDefault(rd.region(), rd.distinctConsumers())
                        : rd.distinctConsumers();
                w = (cappedPlan > 0.0) ? cappedPlan : (double) Math.max(1, nR);
            } else {
                w = rd.consumptionRatePerTick() + ((double) rd.deficit() / 600.0);
            }
            weights.put(rd.region(), Math.max(0.0, w));
        }

        int remaining = Math.max(0, effectiveTotal - sumFloors);
        Map<String, Double> waterFilledExtras = effectiveAttribution
                ? computeWaterFilling(extraHeadrooms, weights, (double) remaining)
                : Collections.emptyMap();
        double totalWeight = 0.0;
        for (double w : weights.values()) totalWeight += w;

        boolean anyContracted = false;
        for (ComputeSignals.RegionDemand rd : signals.regions().values()) {
            String rName = rd.region();
            int cfgCap = rd.configuredHotCap();
            int floor = floors.get(rName);
            int ceiling = ceilings.get(rName);
            int target;
            if (cfgCap == 0) {
                target = 0;
            } else if (effectiveTotal <= sumFloors) {
                target = floor;
            } else if (effectiveAttribution) {
                int extra = (int) Math.floor(waterFilledExtras.getOrDefault(rName, 0.0) + 1e-9);
                target = Math.min(ceiling, floor + extra);
            } else if (totalWeight > 0.0) {
                int extra = (int) Math.floor(remaining * (weights.get(rName) / totalWeight));
                target = Math.min(ceiling, floor + extra);
            } else {
                int extra = remaining / Math.max(1, signals.regions().size());
                target = Math.min(ceiling, floor + extra);
            }

            RegionState state = regionStates.get(rName);
            int prevHot = state != null ? state.publishedHotCap() : cfgCap;
            AimdLimiter regLimiter = state != null
                    ? state.hotLimiter()
                    : new AimdLimiter(floor, cfgCap);
            regLimiter.updateBounds(floor, cfgCap);
            double pubHot = regLimiter.evaluate(target, heapBreach, heapLag, heapStale, false, nowNanos);
            int hotEff = (int) clampLong(Math.round(pubHot), floor, cfgCap);

            if (hotEff < prevHot) {
                anyContracted = true;
            }
            if (state != null) {
                regionStates.put(rName, new RegionState(state.credit(), state.plannedRate(), state.creditCap(), hotEff, regLimiter));
            }
            regionHotCaps.put(rName, hotEff);

            GovernorReason reason;
            if (hotEff >= cfgCap) {
                reason = heapStale ? GovernorReason.STALE_SIGNAL : GovernorReason.CONFIGURED;
            } else if (heapStale && !heapBreach) {
                reason = GovernorReason.STALE_SIGNAL;
            } else if (rd.idleForTenMinutes() && hotEff == ceiling && nTotal >= totalCfgHot && !heapBreach) {
                reason = GovernorReason.IDLE;
            } else {
                reason = GovernorReason.HEAP_HEADROOM;
            }
            regionHotReasons.put(rName, reason);
        }

        if (anyContracted) {
            chunkMemorySlope.recordExclusionEvent(nowNanos);
        }
    }

    /**
     * Constrained water-filling solver (ADR-087 Section 4.3 item 1, ADR-124 Section 2):
     * {@code x_r = min(d_bar_r, theta * w_r)} such that {@code sum_r x_r = rStar}.
     */
    public static Map<String, Double> computeWaterFilling(
            Map<String, Double> deliverableDemands,
            Map<String, Double> weights,
            double rStar
    ) {
        return WaterFillingAllocator.allocate(deliverableDemands, weights, rStar);
    }

    private void accrueCredits(double dtTicks) {
        if (dtTicks <= 0.0) return;
        for (Map.Entry<String, RegionState> e : regionStates.entrySet()) {
            RegionState prev = e.getValue();
            if (prev == null) continue;
            double accrued = Math.min(prev.creditCap(), prev.credit() + prev.plannedRate() * dtTicks);
            regionStates.put(e.getKey(), new RegionState(
                    accrued, prev.plannedRate(), prev.creditCap(), prev.publishedHotCap(), prev.hotLimiter()));
        }
    }

    /**
     * Debits {@code completedUnits} from {@code region}'s credit bucket after a region turn completes
     * and refreshes the published snapshot's {@code unitsPerTurn(region)} (ADR-087 Section 4.3 item 1, Invariant I11).
     *
     * @param region         region name
     * @param completedUnits background units completed during the turn
     */
    public void debitRegionUnits(String region, int completedUnits) {
        if (region == null || completedUnits <= 0) return;
        RegionState prev = regionStates.get(region);
        if (prev == null) return;
        double nextCredit = Math.max(0.0, prev.credit() - completedUnits);
        regionStates.put(region, new RegionState(
                nextCredit, prev.plannedRate(), prev.creditCap(), prev.publishedHotCap(), prev.hotLimiter()));

        ComputeBudget cur = this.currentBudget;
        long aCfg = cur.configuredAsyncNanos();
        int promCfg = cur.configuredPromotionsPerTurn();
        int uMax = Math.max(1, (int) Math.floor((double) aCfg / Math.max(1_000.0, resolveDeltaTAsyncNanos(aCfg, promCfg))));
        int nextU = Math.min((int) Math.floor(nextCredit), uMax);
        Map<String, Integer> nextUnits = new LinkedHashMap<>(cur.regionUnitsPerTurn());
        nextUnits.put(region, Math.max(0, nextU));
        Map<String, Double> nextCredits = new LinkedHashMap<>(cur.regionCredits());
        nextCredits.put(region, nextCredit);
        this.currentBudget = new ComputeBudget(
                cur.autoTune(), cur.observeOnly(),
                cur.configuredAsyncNanos(), cur.effectiveAsyncNanos(), cur.asyncReason(),
                cur.configuredSyncNanos(), cur.effectiveSyncNanos(), cur.syncReason(),
                cur.configuredPeriodTicks(), cur.effectivePeriodTicks(), cur.periodReason(),
                cur.configuredPromotionsPerTurn(), cur.effectivePromotionsPerTurn(), cur.promotionsReason(),
                cur.configuredGenerationCap(), cur.effectiveGenerationCap(), cur.generationReason(),
                cur.softCpuTargetMs(), cur.cpuHeadroomMs(), cur.targetUnitRate(),
                nextUnits, cur.regionHotCaps(), cur.regionConfiguredHotCaps(), cur.regionHotCapReasons(),
                cur.regionPlannedRates(), nextCredits, cur.regionCreditCaps()
        );
    }

    /**
     * Returns regions with credit {@code c_r >= 1.0} ordered by largest credit first, preserving
     * tie-breaking order from {@code candidateOrder} (ADR-087 Section 4.3 item 1).
     */
    public List<String> orderRegionsByCredit(Iterable<String> candidateOrder) {
        List<String> eligible = new ArrayList<>();
        if (candidateOrder == null) return eligible;
        for (String r : candidateOrder) {
            if (r == null) continue;
            RegionState st = regionStates.get(r);
            double c = (st != null) ? st.credit() : currentBudget.credit(r);
            if (c >= 1.0) {
                eligible.add(r);
            }
        }
        eligible.sort((a, b) -> {
            double ca = creditOf(a);
            double cb = creditOf(b);
            return Double.compare(cb, ca);
        });
        return eligible;
    }

    private double creditOf(String region) {
        RegionState st = regionStates.get(region);
        return (st != null) ? st.credit() : currentBudget.credit(region);
    }

    /**
     * Records the remainder of {@code A_cfg} left after cancellations, MiscTasks, and urgent/player-facing
     * drains, and computes the current pulse's background async allowance net of carry-forward debt {@code d_A}
     * (ADR-087 Section 4.3 item 3, Invariant I2).
     *
     * @param remainderOfConfiguredNanos remaining nanoseconds of {@code A_cfg}
     * @return available nanoseconds for background async work in this pulse (0 if exhausted or in debt)
     */
    public long beginBackgroundAsyncPhase(long remainderOfConfiguredNanos) {
        double sample = Math.max(0.0, (double) remainderOfConfiguredNanos);
        bgAsyncRemainder.recordSample(sample);
        ComputeBudget b = this.currentBudget;
        long aEff = b.backgroundAsyncNanos();
        long capped = Math.min(aEff, Math.max(0L, remainderOfConfiguredNanos));
        long debt = asyncDebtNanos.get();
        return Math.max(0L, capped - debt);
    }

    /**
     * Updates the async overrun debt {@code d_A <- max(0, d_A + spend - A_cfg)} at the end of a pulse
     * (cancellations excluded from {@code pulseSpendNanos}; ADR-087 Section 4.3 item 3, Invariant I2).
     *
     * @param pulseSpendNanos total pulse spend excluding cancellations
     * @param configuredAsyncNanos configured {@code A_cfg} in nanoseconds
     * @return updated async debt in nanoseconds
     */
    public long endAsyncPulse(long pulseSpendNanos, long configuredAsyncNanos) {
        long spend = Math.max(0L, pulseSpendNanos);
        long aCfg = Math.max(1L, configuredAsyncNanos);
        return asyncDebtNanos.updateAndGet(prev -> Math.max(0L, prev + spend - aCfg));
    }

    /**
     * Returns the current tick's background sync budget net of carry-forward debt {@code d_S}:
     * {@code max(0, S_eff - d_S)} (ADR-087 Section 4.3 item 4, Invariants I2 & I5).
     */
    public long beginBackgroundSyncSpan() {
        return beginBackgroundSyncSpan(this.currentBudget.configuredSyncNanos());
    }

    /**
     * Returns the current tick's background sync budget net of carry-forward debt {@code d_S}
     * and capped by the remaining tick budget {@code remainderOfConfiguredNanos}:
     * {@code max(0, min(S_eff, remainderOfConfiguredNanos) - d_S)} (ADR-087 Section 4.3 item 4, Invariants I2 & I5).
     *
     * @param remainderOfConfiguredNanos remaining nanoseconds of {@code S_cfg} after player-facing sync stages
     * @return available nanoseconds for background sync work in this tick (0 if exhausted or in debt)
     */
    public long beginBackgroundSyncSpan(long remainderOfConfiguredNanos) {
        ComputeBudget b = this.currentBudget;
        long sEff = b.backgroundSyncNanos();
        if (sEff <= 0L || remainderOfConfiguredNanos <= 0L) return 0L;
        long capped = Math.min(sEff, remainderOfConfiguredNanos);
        long debt = syncDebtNanos.get();
        return Math.max(0L, capped - debt);
    }

    /**
     * Updates the sync overrun debt {@code d_S <- max(0, d_S + bgSyncSpendNanos - S_cfg)} and
     * records RTP's background tick time EWMA {@code mspt_rtp^b} (ADR-087 Section 4.1 & 4.3 item 4).
     *
     * @param bgSyncSpendNanos background sync nanoseconds spent in this tick
     * @param configuredSyncNanos configured {@code S_cfg} in nanoseconds
     * @return updated sync debt in nanoseconds
     */
    public long endBackgroundSyncSpan(long bgSyncSpendNanos, long configuredSyncNanos) {
        return endBackgroundSyncSpan(bgSyncSpendNanos, bgSyncSpendNanos, configuredSyncNanos);
    }

    /**
     * Updates the sync overrun debt {@code d_S <- max(0, d_S + totalTickSpendNanos - S_cfg)} and
     * records RTP's background tick time EWMA {@code mspt_rtp^b} from {@code bgSyncSpendNanos}
     * (ADR-087 Section 4.1 & 4.3 item 4, Invariant I2).
     *
     * @param bgSyncSpendNanos    background sync nanoseconds spent in this tick
     * @param totalTickSpendNanos total sync nanoseconds spent in this tick (player-facing + background)
     * @param configuredSyncNanos configured {@code S_cfg} in nanoseconds
     * @return updated sync debt in nanoseconds
     */
    public long endBackgroundSyncSpan(long bgSyncSpendNanos, long totalTickSpendNanos, long configuredSyncNanos) {
        long bgSpend = Math.max(0L, bgSyncSpendNanos);
        long totalSpend = Math.max(bgSpend, totalTickSpendNanos);
        long sCfg = Math.max(1L, configuredSyncNanos);
        rtpBgTickMs.recordSample(bgSpend / 1_000_000.0);
        return syncDebtNanos.updateAndGet(prev -> Math.max(0L, prev + totalSpend - sCfg));
    }

    public long asyncDebtNanos() {
        return asyncDebtNanos.get();
    }

    public long syncDebtNanos() {
        return syncDebtNanos.get();
    }

    /**
     * Reports measured unit costs from a completed region turn (ADR-087 Section 4.2 & 7.3).
     *
     * @param asyncDurationNanos async duration of the turn in nanoseconds
     * @param completedUnits     background units completed ({@code max(coldFills, promotions)})
     * @param promotionsStarted  number of promotions dispatched in the turn
     */
    public void recordTurnUnitCosts(long asyncDurationNanos, int completedUnits, int promotionsStarted) {
        recordTurnUnitCosts("__global__", asyncDurationNanos, completedUnits, promotionsStarted);
    }

    /**
     * Reports measured unit costs from a completed region turn for {@code region}, carrying forward
     * async elapsed time when {@code completedUnits == 0} (ADR-087 Section 4.2 & 7.3).
     *
     * @param region             region name
     * @param asyncDurationNanos async duration of the turn in nanoseconds
     * @param completedUnits     background units completed ({@code max(coldFills, promotions)})
     * @param promotionsStarted  number of promotions dispatched in the turn
     */
    public void recordTurnUnitCosts(String region, long asyncDurationNanos, int completedUnits, int promotionsStarted) {
        if (asyncDurationNanos < 0L) return;
        String key = (region != null) ? region : "__global__";
        if (completedUnits <= 0) {
            if (asyncDurationNanos > 0L) {
                turnAsyncCarryNanos.computeIfAbsent(key, k -> new AtomicLong(0L)).addAndGet(asyncDurationNanos);
            }
            return;
        }
        AtomicLong carrySlot = turnAsyncCarryNanos.get(key);
        long carried = (carrySlot != null) ? carrySlot.getAndSet(0L) : 0L;
        long totalAsyncNanos = Math.max(0L, asyncDurationNanos + Math.max(0L, carried));
        double perUnitAsync = Math.max(1_000.0, (double) totalAsyncNanos / (double) completedUnits);
        deltaTAsync.recordSample(perUnitAsync);
        double promCost = deltaTProm.mean();
        if (!Double.isNaN(promCost) && promCost > 0.0) {
            double promShare = Math.min(1.0, (double) Math.max(0, promotionsStarted) / (double) completedUnits);
            deltaTTick.recordSample(promShare * promCost);
        }
    }

    /**
     * Reports measured tick-thread duration for a cold-to-hot promotion completion (ADR-087 Section 4.2).
     *
     * @param tickDurationNanos measured tick-thread nanoseconds for the promotion completion
     */
    public void recordPromotionTickCost(long tickDurationNanos) {
        if (tickDurationNanos < 0L) return;
        deltaTProm.recordSample((double) tickDurationNanos);
        double curTick = deltaTTick.mean();
        if (Double.isNaN(curTick) || curTick <= 0.0) {
            deltaTTick.recordSample((double) tickDurationNanos);
        } else {
            deltaTTick.recordSample(0.5 * (double) tickDurationNanos);
        }
    }

    /**
     * Directly sets measured unit costs in nanoseconds (for deterministic unit/property tests).
     */
    public void setUnitCostsForTesting(double deltaTAsyncNanos, double deltaTTickNanos, double deltaTPromNanos) {
        deltaTAsync.setMean(Math.max(1_000.0, deltaTAsyncNanos));
        deltaTTick.setMean(Math.max(0.0, deltaTTickNanos));
        deltaTProm.setMean(Math.max(1_000.0, deltaTPromNanos));
    }

    /**
     * Records a hot-cache shed or contraction event at {@code nowNanos} so post-GC memory samples
     * within the next 60 s are excluded from the per-chunk memory slope fit (ADR-087 Section 4.2).
     */
    public void recordHotCapContraction(long nowNanos) {
        chunkMemorySlope.recordExclusionEvent(nowNanos);
    }

    /**
     * Updates the per-chunk memory estimate {@code m_chunk} from a post-GC tenured heap sample and
     * current RTP-held chunk ticket count over a 10-minute window (ADR-087 Section 4.2).
     *
     * <ul>
     *   <li>Ignores samples taken within 60 s after a hot-cap contraction or heap shed.</li>
     *   <li>Replaces the prior only when the 10-minute window holds at least 20 post-GC samples and
     *       ticket count varied by at least 25% of its mean.</li>
     *   <li>Clamps {@code m_chunk} to {@code [m_prior, 4 * m_prior]}.</li>
     * </ul>
     */
    public double recordPostGcMemorySample(int heldTickets, double postGcTenuredBytes, long nowNanos) {
        if (heldTickets < 0) {
            return chunkMemoryEstimateBytes();
        }
        return chunkMemorySlope.recordSample(heldTickets, postGcTenuredBytes, nowNanos);
    }

    private void updateChunkMemoryEstimate(ComputeSignals signals, long nowNanos) {
        if (signals.postGcTenuredUsedBytes().isFresh()) {
            recordPostGcMemorySample(signals.totalHeldTickets(), signals.postGcTenuredUsedBytes().value(), nowNanos);
        }
    }

    public double chunkMemoryEstimateBytes() {
        return chunkMemorySlope.estimate();
    }

    public double chunkMemoryPriorBytes() {
        return chunkMemoryPriorBytes;
    }

    /**
     * Forces all internal AIMD limiters to their floor at {@code nowNanos} (for Invariant I12 convergence tests).
     */
    public void forceAllLimitersToFloor(long lagNanos, long nowNanos) {
        asyncLimiter.forceToFloor(lagNanos, nowNanos);
        syncLimiter.forceToFloor(lagNanos, nowNanos);
        generationLimiter.forceToFloor(lagNanos, nowNanos);
        hotScaleLimiter.forceToFloor(lagNanos, nowNanos);
        publishedAsyncNanos.set(Math.round(asyncLimiter.floor()));
        for (RegionState st : regionStates.values()) {
            if (st != null && st.hotLimiter() != null) {
                st.hotLimiter().forceToFloor(lagNanos, nowNanos);
            }
        }
    }

    /**
     * Returns the maximum duration in nanoseconds that any governed knob has been continuously held at its floor.
     */
    public long maxDurationAtFloorNanos(long nowNanos) {
        long max = Math.max(
                asyncLimiter.durationAtFloorNanos(nowNanos),
                Math.max(syncLimiter.durationAtFloorNanos(nowNanos), generationLimiter.durationAtFloorNanos(nowNanos))
        );
        for (RegionState st : regionStates.values()) {
            if (st != null && st.hotLimiter() != null) {
                max = Math.max(max, st.hotLimiter().durationAtFloorNanos(nowNanos));
            }
        }
        return max;
    }

    /**
     * Resets all governor state to configured defaults (called on reload and shutdown, ADR-087 Section 7.4).
     */
    public void reset() {
        asyncLimiter.reset();
        syncLimiter.reset();
        generationLimiter.reset();
        hotScaleLimiter.reset();
        lastEvalNanos.set(Long.MIN_VALUE);
        lastCreditUpdateNanos.set(Long.MIN_VALUE);
        lastEvalFailureLogNanos.set(Long.MIN_VALUE);
        evaluationFailures.set(0L);
        lastFailureMessage.set("");
        asyncDebtNanos.set(0L);
        syncDebtNanos.set(0L);
        deltaTAsync.reset();
        deltaTTick.reset();
        deltaTProm.reset();
        bgAsyncRemainder.reset();
        rtpBgTickMs.reset();
        chunksPerEntry.reset();
        chunkMemorySlope.reset();
        lastBCpuBits.set(Double.doubleToRawLongBits(Double.NaN));
        consecutiveProcCpuHigh.set(0);
        asyncWasIdle.set(false);
        publishedAsyncNanos.set(ComputeSignals.DEFAULT_ASYNC_NANOS);
        regionStates.clear();
        turnAsyncCarryNanos.clear();
        lastLoggedReason.clear();
        lastLogTimeNanos.clear();
        breachCountsBySignal.clear();
        evaluationsTotal.set(0L);
        constrainedEvaluationsTotal.set(0L);
        maxConsumerSkewBits.set(Double.doubleToRawLongBits(1.0));
        lastDistinctConsumers = Collections.emptyMap();
        lastWouldBeAttributedRates = Collections.emptyMap();
        consumerLedger.reset();
        useConsumerAttributionOverride = null;
        currentBudget = ComputeBudget.configuredDefault();
        lastSignals = null;
    }

    private void logTransitionsIfNeeded(ComputeBudget b, long nowNanos) {
        logKnobTransition("backgroundAsync", b.asyncReason(),
                TimeUnit.NANOSECONDS.toMillis(b.configuredAsyncNanos()) + " ms",
                TimeUnit.NANOSECONDS.toMillis(b.effectiveAsyncNanos()) + " ms", nowNanos);
        logKnobTransition("backgroundSync", b.syncReason(),
                TimeUnit.NANOSECONDS.toMillis(b.configuredSyncNanos()) + " ms",
                TimeUnit.NANOSECONDS.toMillis(b.effectiveSyncNanos()) + " ms", nowNanos);
        logKnobTransition("promotionsPerTurn", b.promotionsReason(),
                String.valueOf(b.configuredPromotionsPerTurn()),
                String.valueOf(b.effectivePromotionsPerTurn()), nowNanos);
        logKnobTransition("generationCap", b.generationReason(),
                String.valueOf(b.configuredGenerationCap()),
                String.valueOf(b.effectiveGenerationCap()), nowNanos);
        for (Map.Entry<String, GovernorReason> e : b.regionHotCapReasons().entrySet()) {
            String r = e.getKey();
            logKnobTransition("hotCap:" + r, e.getValue(),
                    String.valueOf(b.regionConfiguredHotCaps().getOrDefault(r, 0)),
                    String.valueOf(b.regionHotCaps().getOrDefault(r, 0)), nowNanos);
        }
    }

    private void logKnobTransition(String knob, GovernorReason reason, String cfgVal, String effVal, long nowNanos) {
        if (reason == null) return;
        GovernorReason prev = lastLoggedReason.getOrDefault(knob, GovernorReason.CONFIGURED);
        if (prev == reason) return;
        if (reason == GovernorReason.CONFIGURED) {
            lastLoggedReason.put(knob, GovernorReason.CONFIGURED);
            RTPRuntime.log(Level.INFO, String.format(Locale.ROOT,
                    "[RTP] Compute governor recovered %s to configured (%s).", knob, cfgVal));
            return;
        }
        Long lastTime = lastLogTimeNanos.get(knob);
        if (lastTime != null && nowNanos - lastTime < LOG_COOLDOWN_NANOS) {
            return;
        }
        lastLoggedReason.put(knob, reason);
        lastLogTimeNanos.put(knob, nowNanos);
        RTPRuntime.log(Level.INFO, String.format(Locale.ROOT,
                "[RTP] Compute governor adjusted %s (%s): %s -> %s.",
                knob, reason.name().toLowerCase(Locale.ROOT).replace('_', ' '), cfgVal, effVal));
    }

    private void incrementBreach(String signalName) {
        breachCountsBySignal.merge(signalName, 1L, Long::sum);
    }

    private double resolveDeltaTAsyncNanos(long aCfg, int promCfg) {
        double v = deltaTAsync.mean();
        if (Double.isNaN(v) || v <= 0.0) {
            return Math.max(1_000.0, (double) aCfg / (double) Math.max(1, promCfg));
        }
        return Math.max(1_000.0, v);
    }

    private double resolveDeltaTTickNanos() {
        double v = deltaTTick.mean();
        return (Double.isNaN(v) || v < 0.0) ? 0.0 : v;
    }

    private double resolveDeltaTPromNanos(long sCfg, int promCfg) {
        double v = deltaTProm.mean();
        if (Double.isNaN(v) || v <= 0.0) {
            return Math.max(1_000.0, (double) sCfg / (double) Math.max(1, promCfg));
        }
        return Math.max(1_000.0, v);
    }

    private double resolveEstimatedBgAsyncNanos(long aCfg) {
        double v = bgAsyncRemainder.mean();
        if (Double.isNaN(v) || v < 0.0) {
            return (double) aCfg;
        }
        return Math.min((double) aCfg, v);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
