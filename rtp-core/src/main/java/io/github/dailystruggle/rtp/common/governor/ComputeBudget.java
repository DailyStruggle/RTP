package io.github.dailystruggle.rtp.common.governor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of effective background compute budgets, per-region quotas, and per-knob
 * {@link GovernorReason} diagnostics published by {@link ComputeGovernor} (ADR-087 Section 2, 4.3, 7.3).
 *
 * <p>Actuation points read this snapshot via a {@code volatile} reference without locks (Invariant I8).
 * When {@link #autoTune()} is {@code false} (or {@link #observeOnly()} is {@code true}), actuation
 * accessors return the configured values (Invariant I3).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor)
 */
public record ComputeBudget(
        boolean autoTune,
        boolean observeOnly,
        long configuredAsyncNanos,
        long effectiveAsyncNanos,
        GovernorReason asyncReason,
        long configuredSyncNanos,
        long effectiveSyncNanos,
        GovernorReason syncReason,
        long configuredPeriodTicks,
        long effectivePeriodTicks,
        GovernorReason periodReason,
        int configuredPromotionsPerTurn,
        int effectivePromotionsPerTurn,
        GovernorReason promotionsReason,
        int configuredGenerationCap,
        int effectiveGenerationCap,
        GovernorReason generationReason,
        double softCpuTargetMs,
        double cpuHeadroomMs,
        double targetUnitRate,
        Map<String, Integer> regionUnitsPerTurn,
        Map<String, Integer> regionHotCaps,
        Map<String, Integer> regionConfiguredHotCaps,
        Map<String, GovernorReason> regionHotCapReasons,
        Map<String, Double> regionPlannedRates,
        Map<String, Double> regionCredits,
        Map<String, Double> regionCreditCaps
) {

    public static final double DEFAULT_PREFILTER_KNEE_MS = 35.0;

    public ComputeBudget {
        if (asyncReason == null) asyncReason = GovernorReason.CONFIGURED;
        if (syncReason == null) syncReason = GovernorReason.CONFIGURED;
        if (periodReason == null) periodReason = GovernorReason.CONFIGURED;
        if (promotionsReason == null) promotionsReason = GovernorReason.CONFIGURED;
        if (generationReason == null) generationReason = GovernorReason.CONFIGURED;
        regionUnitsPerTurn = copyMap(regionUnitsPerTurn);
        regionHotCaps = copyMap(regionHotCaps);
        regionConfiguredHotCaps = copyMap(regionConfiguredHotCaps);
        regionHotCapReasons = copyMap(regionHotCapReasons);
        regionPlannedRates = copyMap(regionPlannedRates);
        regionCredits = copyMap(regionCredits);
        regionCreditCaps = copyMap(regionCreditCaps);
    }

    private static <K, V> Map<K, V> copyMap(Map<K, V> map) {
        return (map == null || map.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /**
     * Creates a default configured snapshot (autoTune disabled / pre-evaluation baseline).
     */
    public static ComputeBudget configuredDefault() {
        return new ComputeBudget(
                false,
                false,
                ComputeSignals.DEFAULT_ASYNC_NANOS,
                ComputeSignals.DEFAULT_ASYNC_NANOS,
                GovernorReason.CONFIGURED,
                ComputeSignals.DEFAULT_SYNC_NANOS,
                ComputeSignals.DEFAULT_SYNC_NANOS,
                GovernorReason.CONFIGURED,
                ComputeSignals.DEFAULT_PERIOD_TICKS,
                ComputeSignals.DEFAULT_PERIOD_TICKS,
                GovernorReason.CONFIGURED,
                ComputeSignals.DEFAULT_PROMOTIONS_PER_TURN,
                ComputeSignals.DEFAULT_PROMOTIONS_PER_TURN,
                GovernorReason.CONFIGURED,
                ComputeSignals.DEFAULT_GENERATION_CAP,
                ComputeSignals.DEFAULT_GENERATION_CAP,
                GovernorReason.CONFIGURED,
                0.8 * ComputeSignals.DEFAULT_MAX_MSPT,
                Double.NaN,
                0.0,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
        );
    }

    /**
     * Returns {@code true} when governor actuation is active ({@code autoTune && !observeOnly}).
     */
    public boolean isActuating() {
        return autoTune && !observeOnly;
    }

    /**
     * Effective background async budget per pulse in nanoseconds (ADR-087 Section 4.3 item 3).
     * Returns {@link #configuredAsyncNanos()} when not actuating.
     */
    public long backgroundAsyncNanos() {
        return isActuating() ? effectiveAsyncNanos : configuredAsyncNanos;
    }

    /**
     * Effective background sync budget per tick in nanoseconds (ADR-087 Section 4.3 item 4).
     * Returns {@link #configuredSyncNanos()} when not actuating.
     */
    public long backgroundSyncNanos() {
        return isActuating() ? effectiveSyncNanos : configuredSyncNanos;
    }

    /**
     * Effective idle period in ticks (ADR-087 Section 4.3 item 1).
     * Returns {@link #configuredPeriodTicks()} when not actuating.
     */
    public long backgroundPeriodTicks() {
        return isActuating() ? effectivePeriodTicks : configuredPeriodTicks;
    }

    /**
     * Effective cold-to-hot promotions per region turn (ADR-087 Section 4.3 item 2).
     * Returns {@link #configuredPromotionsPerTurn()} when not actuating.
     */
    public int promotionsPerTurn() {
        return isActuating() ? effectivePromotionsPerTurn : configuredPromotionsPerTurn;
    }

    /**
     * Effective global chunk generation concurrency cap {@code g_eff} (ADR-087 Section 4.3 item 7, ADR-114).
     * Returns {@link #configuredGenerationCap()} when not actuating.
     */
    public int generationCap() {
        return isActuating() ? effectiveGenerationCap : configuredGenerationCap;
    }

    /**
     * Effective CPU target knee {@code min(35 ms, T_cpu)} for {@code ImmediateLoadGovernor} and {@code PregenTask}
     * (ADR-087 Section 4.3 item 7, ADR-114). Returns {@code 35.0} when not actuating.
     */
    public double cpuTarget() {
        if (!isActuating() || Double.isNaN(softCpuTargetMs) || softCpuTargetMs <= 0.0) {
            return DEFAULT_PREFILTER_KNEE_MS;
        }
        return Math.min(DEFAULT_PREFILTER_KNEE_MS, softCpuTargetMs);
    }

    /**
     * Effective background unit quota {@code u_r} for {@code region} in the current turn (ADR-087 Section 4.3 item 1).
     * When not actuating, returns {@code Integer.MAX_VALUE} (unbounded by unit credit).
     */
    public int unitsPerTurn(String region) {
        if (!isActuating()) {
            return Integer.MAX_VALUE;
        }
        if (region == null) return 0;
        Integer u = regionUnitsPerTurn.get(region);
        return u != null ? u : 0;
    }

    /**
     * Computed unit quota {@code u_r} even in observe-only mode (for telemetry and pure control-law tests).
     */
    public int observedUnitsPerTurn(String region) {
        if (region == null) return 0;
        Integer u = regionUnitsPerTurn.get(region);
        return u != null ? u : 0;
    }

    /**
     * Effective hot-cache capacity {@code activeChunkCap} for {@code region} (ADR-087 Section 4.3 item 6, ADR-110).
     */
    public int hotCap(String region) {
        if (region == null) return 0;
        if (!isActuating()) {
            return regionConfiguredHotCaps.getOrDefault(region, 0);
        }
        Integer cap = regionHotCaps.get(region);
        if (cap != null) return cap;
        return regionConfiguredHotCaps.getOrDefault(region, 0);
    }

    /**
     * Effective hot-cache capacity for {@code region}, falling back to {@code configuredFallback} when the
     * region was not present in the last signal sample or when not actuating.
     */
    public int hotCap(String region, int configuredFallback) {
        if (!isActuating() || region == null) {
            return Math.max(0, configuredFallback);
        }
        Integer cap = regionHotCaps.get(region);
        if (cap == null) {
            return Math.max(0, configuredFallback);
        }
        return Math.min(Math.max(0, configuredFallback), cap);
    }

    /**
     * Computed hot-cache capacity even in observe-only mode (for telemetry and pure control-law tests).
     */
    public int observedHotCap(String region) {
        if (region == null) return 0;
        Integer cap = regionHotCaps.get(region);
        return cap != null ? cap : regionConfiguredHotCaps.getOrDefault(region, 0);
    }

    public GovernorReason hotCapReason(String region) {
        if (region == null) return GovernorReason.CONFIGURED;
        return regionHotCapReasons.getOrDefault(region, GovernorReason.CONFIGURED);
    }

    public double plannedRate(String region) {
        if (region == null) return 0.0;
        return regionPlannedRates.getOrDefault(region, 0.0);
    }

    public double credit(String region) {
        if (region == null) return 0.0;
        return regionCredits.getOrDefault(region, 0.0);
    }

    public double creditCap(String region) {
        if (region == null) return 1.0;
        return regionCreditCaps.getOrDefault(region, 1.0);
    }
}
