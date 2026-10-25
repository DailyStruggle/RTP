package io.github.dailystruggle.rtp.common.governor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Immutable telemetry snapshot of {@link ComputeGovernor} for {@code metrics-api} export and
 * {@code /rtp info} diagnostics (ADR-087 Section 6, ADR-124 Section 5 & 7, REQ-RTP-OPS-002).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor), ADR-124 (Consumer Attribution)
 */
public record GovernorSnapshot(
        boolean autoTune,
        boolean observeOnly,
        long configuredPeriodTicks,
        long effectivePeriodTicks,
        GovernorReason periodReason,
        long configuredAsyncBudgetMs,
        long effectiveAsyncBudgetMs,
        GovernorReason asyncReason,
        long configuredSyncBudgetMs,
        long effectiveSyncBudgetMs,
        GovernorReason syncReason,
        int configuredPromotionsPerTurn,
        int effectivePromotionsPerTurn,
        GovernorReason promotionsReason,
        int configuredGenerationCap,
        int effectiveGenerationCap,
        GovernorReason generationReason,
        Map<String, Integer> regionHotCaps,
        Map<String, Integer> regionConfiguredHotCaps,
        Map<String, GovernorReason> regionHotCapReasons,
        Map<String, Integer> regionUnitsPerTurn,
        Map<String, Long> breachesTotal,
        int staleSignals,
        int regionalInflightCount,
        int regionalInflightCeiling,
        double rtpBgTickMs,
        double chunkMemoryEstimateBytes,
        long evaluationsTotal,
        long constrainedEvaluationsTotal,
        double maxConsumerSkewRatio,
        Map<String, Integer> regionDistinctConsumers,
        Map<String, Double> wouldBeAttributedPlannedRates,
        double consumerCostP99Ms,
        Map<String, Double> regionRefillShares,
        List<ConsumerLedger.ConsumerCostEntry> topConsumers
) {

    public static final GovernorSnapshot EMPTY = fromBudget(ComputeBudget.configuredDefault());

    public GovernorSnapshot {
        if (periodReason == null) periodReason = GovernorReason.CONFIGURED;
        if (asyncReason == null) asyncReason = GovernorReason.CONFIGURED;
        if (syncReason == null) syncReason = GovernorReason.CONFIGURED;
        if (promotionsReason == null) promotionsReason = GovernorReason.CONFIGURED;
        if (generationReason == null) generationReason = GovernorReason.CONFIGURED;
        if (Double.isNaN(consumerCostP99Ms) || Double.isInfinite(consumerCostP99Ms) || consumerCostP99Ms < 0.0) {
            consumerCostP99Ms = 0.0;
        }
        regionHotCaps = copyMap(regionHotCaps);
        regionConfiguredHotCaps = copyMap(regionConfiguredHotCaps);
        regionHotCapReasons = copyMap(regionHotCapReasons);
        regionUnitsPerTurn = copyMap(regionUnitsPerTurn);
        breachesTotal = copyMap(breachesTotal);
        regionDistinctConsumers = copyMap(regionDistinctConsumers);
        wouldBeAttributedPlannedRates = copyMap(wouldBeAttributedPlannedRates);
        regionRefillShares = copyMap(regionRefillShares);
        topConsumers = (topConsumers == null || topConsumers.isEmpty())
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(topConsumers));
    }

    public GovernorSnapshot(
            boolean autoTune,
            boolean observeOnly,
            long configuredPeriodTicks,
            long effectivePeriodTicks,
            GovernorReason periodReason,
            long configuredAsyncBudgetMs,
            long effectiveAsyncBudgetMs,
            GovernorReason asyncReason,
            long configuredSyncBudgetMs,
            long effectiveSyncBudgetMs,
            GovernorReason syncReason,
            int configuredPromotionsPerTurn,
            int effectivePromotionsPerTurn,
            GovernorReason promotionsReason,
            int configuredGenerationCap,
            int effectiveGenerationCap,
            GovernorReason generationReason,
            Map<String, Integer> regionHotCaps,
            Map<String, Integer> regionConfiguredHotCaps,
            Map<String, GovernorReason> regionHotCapReasons,
            Map<String, Integer> regionUnitsPerTurn,
            Map<String, Long> breachesTotal,
            int staleSignals,
            int regionalInflightCount,
            int regionalInflightCeiling,
            double rtpBgTickMs,
            double chunkMemoryEstimateBytes,
            long evaluationsTotal,
            long constrainedEvaluationsTotal,
            double maxConsumerSkewRatio,
            Map<String, Integer> regionDistinctConsumers,
            Map<String, Double> wouldBeAttributedPlannedRates
    ) {
        this(
                autoTune,
                observeOnly,
                configuredPeriodTicks,
                effectivePeriodTicks,
                periodReason,
                configuredAsyncBudgetMs,
                effectiveAsyncBudgetMs,
                asyncReason,
                configuredSyncBudgetMs,
                effectiveSyncBudgetMs,
                syncReason,
                configuredPromotionsPerTurn,
                effectivePromotionsPerTurn,
                promotionsReason,
                configuredGenerationCap,
                effectiveGenerationCap,
                generationReason,
                regionHotCaps,
                regionConfiguredHotCaps,
                regionHotCapReasons,
                regionUnitsPerTurn,
                breachesTotal,
                staleSignals,
                regionalInflightCount,
                regionalInflightCeiling,
                rtpBgTickMs,
                chunkMemoryEstimateBytes,
                evaluationsTotal,
                constrainedEvaluationsTotal,
                maxConsumerSkewRatio,
                regionDistinctConsumers,
                wouldBeAttributedPlannedRates,
                0.0,
                ConsumerLedger.computeRegionRefillShares(wouldBeAttributedPlannedRates),
                Collections.emptyList()
        );
    }

    private static <K, V> Map<K, V> copyMap(Map<K, V> map) {
        return (map == null || map.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public static GovernorSnapshot fromBudget(ComputeBudget b) {
        ComputeBudget safe = (b != null) ? b : ComputeBudget.configuredDefault();
        return new GovernorSnapshot(
                safe.autoTune(),
                safe.observeOnly(),
                safe.configuredPeriodTicks(),
                safe.effectivePeriodTicks(),
                safe.periodReason(),
                TimeUnit.NANOSECONDS.toMillis(safe.configuredAsyncNanos()),
                TimeUnit.NANOSECONDS.toMillis(safe.effectiveAsyncNanos()),
                safe.asyncReason(),
                TimeUnit.NANOSECONDS.toMillis(safe.configuredSyncNanos()),
                TimeUnit.NANOSECONDS.toMillis(safe.effectiveSyncNanos()),
                safe.syncReason(),
                safe.configuredPromotionsPerTurn(),
                safe.effectivePromotionsPerTurn(),
                safe.promotionsReason(),
                safe.configuredGenerationCap(),
                safe.effectiveGenerationCap(),
                safe.generationReason(),
                safe.regionHotCaps(),
                safe.regionConfiguredHotCaps(),
                safe.regionHotCapReasons(),
                safe.regionUnitsPerTurn(),
                Collections.emptyMap(),
                0,
                0,
                ComputeSignals.DEFAULT_GENERATION_CAP,
                0.0,
                ComputeGovernor.DEFAULT_CHUNK_MEMORY_PRIOR_BYTES,
                0L,
                0L,
                1.0,
                Collections.emptyMap(),
                Collections.emptyMap(),
                0.0,
                ConsumerLedger.computeRegionRefillShares(safe.regionPlannedRates()),
                Collections.emptyList()
        );
    }

    /**
     * Share of governor evaluations in which background refill was constrained ({@code r* < sum(d_bar_r)}),
     * in {@code [0.0, 1.0]} (ADR-124 Section 7 Go/No-Go 1).
     */
    public double constrainedEvaluationShare() {
        if (evaluationsTotal <= 0L) return 0.0;
        return Math.min(1.0, Math.max(0.0, (double) constrainedEvaluationsTotal / (double) evaluationsTotal));
    }
}
