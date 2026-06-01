package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curve;
import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curves;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration record consumed by {@link WeightedAverageBackendSelector}. A
 * stripped-down projection of the full {@code network-proxy.yml}
 * {@code loadBalancer} subtree (rtp-proxy-ADR-002) carrying only the keys the
 * reference selector needs.
 *
 * <p>The scoring formula (rtp-proxy-ADR-004) is:</p>
 *
 * <pre>
 * score(b) = ( Sigma_i  weight_i * curve_i( normalize_i( metric_i(b) ) ) ) / backendWeight(b)
 * </pre>
 *
 * <p>The {@code Sigma_i} runs over {@link #terms()}. For source compatibility
 * with the original four-weight constructor (mspt, queueDepth, heap,
 * playerCount), when {@code terms} is empty the compact constructor
 * synthesizes a linear-curve term for each of the four legacy weights with a
 * non-zero value. New code shall populate {@code terms} explicitly via the
 * YAML loader.</p>
 *
 * @param staleAfterMs        backend rows older than this relative to the
 *                            snapshot timestamp are excluded (REQ-RTP-PROXY-COMMON-003)
 * @param msptWeight          legacy weight of the {@code mspt / 50.0} term (default 1.0)
 * @param queueDepthWeight    legacy weight of the {@code queueDepth / max(1, softCap)} term (default 1.0)
 * @param heapWeight          legacy weight of the {@code heapUsed / heapMax} term (default 0.5)
 * @param playerCountWeight   legacy weight of the {@code playerCount / 100.0} term (default 0.0)
 * @param backendWeights      per-{@code serverId} preference multiplier; missing key gives 1.0
 * @param terms               configurable input/weight/curve scoring table.
 *                            When empty the legacy four weights above are
 *                            synthesized into linear terms.
 * @param regionScarcityWeight default-1.0 implicit weight applied by
 *                            {@code RegionAwareSelector} as a synthesized
 *                            {@link MetricInput#KEPT_REGION} term when the
 *                            operator has not authored their own
 *                            {@code KEPT_REGION} term. Set to {@code 0.0} to
 *                            disable the implicit region scarcity bias.
 *                            Operators who want a different curve shape
 *                            simply add an explicit {@code KEPT_REGION} entry
 *                            to {@link #terms} - that always wins over the
 *                            synthesized one. Synthesized curve is
 *                            {@code exponential(k=5)} so a region 90% empty
 *                            contributes ~0.85 and one 50% empty only ~0.07,
 *                            i.e. drastically prioritizes regions whose kept
 *                            pool is shallow.
 */
public record LoadBalancerConfig(
        long staleAfterMs,
        double msptWeight,
        double queueDepthWeight,
        double heapWeight,
        double playerCountWeight,
        Map<String, Double> backendWeights,
        List<ScoringTerm> terms,
        double regionScarcityWeight
) {
    /** Synthesized region-scarcity curve when no explicit {@code KEPT_REGION} term is configured. */
    private static final Curve REGION_SCARCITY_DEFAULT_CURVE = Curves.exponential(5.0);

    /** Bundled defaults aligned with rtp-proxy-ADR-004. */
    public static LoadBalancerConfig defaults() {
        return new LoadBalancerConfig(
                30_000L,
                1.0,
                1.0,
                0.5,
                0.0,
                Map.of(),
                List.of(),
                1.0
        );
    }

    public LoadBalancerConfig {
        if (staleAfterMs < 0) {
            throw new IllegalArgumentException("staleAfterMs must be >= 0");
        }
        Objects.requireNonNull(backendWeights, "backendWeights");
        Objects.requireNonNull(terms, "terms");
        backendWeights = Map.copyOf(backendWeights);
        if (terms.isEmpty()) {
            terms = synthesizeLegacyTerms(msptWeight, queueDepthWeight, heapWeight, playerCountWeight);
        } else {
            terms = List.copyOf(terms);
        }
        if (Double.isNaN(regionScarcityWeight) || regionScarcityWeight < 0.0) {
            regionScarcityWeight = 0.0;
        }
    }

    /**
     * Synthesizes the implicit region-scarcity scoring term consumed by
     * {@code RegionAwareSelector}, or {@code null} when disabled (weight 0
     * or an explicit {@link MetricInput#KEPT_REGION} term is already in
     * {@link #terms}). Uses {@code exponential(k=5)} so a region whose
     * kept pool is nearly empty dominates the score while a half-full one
     * barely registers. Operators who want a different shape add an
     * explicit {@code KEPT_REGION} term and the synthesized one steps
     * aside. Pure function of this config; no snapshot needed.
     */
    public ScoringTerm regionScarcityTerm() {
        if (regionScarcityWeight <= 0.0) return null;
        for (ScoringTerm t : terms) {
            if (t.input() == MetricInput.KEPT_REGION) return null;
        }
        return new ScoringTerm(MetricInput.KEPT_REGION, regionScarcityWeight, REGION_SCARCITY_DEFAULT_CURVE);
    }

    /**
     * Convenience back-compat constructor matching the pre-curve signature.
     * Callers that have not been migrated to populate {@code terms} can keep
     * using this; the compact constructor will synthesize linear terms from
     * the four legacy weights.
     */
    public LoadBalancerConfig(long staleAfterMs,
                              double msptWeight,
                              double queueDepthWeight,
                              double heapWeight,
                              double playerCountWeight,
                              Map<String, Double> backendWeights) {
        this(staleAfterMs, msptWeight, queueDepthWeight, heapWeight, playerCountWeight,
                backendWeights, List.of(), 1.0);
    }

    /**
     * Back-compat constructor matching the post-curve / pre-region signature
     * (terms but no {@code regionScarcityWeight}). Defaults
     * {@code regionScarcityWeight} to {@code 1.0} so existing call sites
     * preserve the legacy "most-kept" pickMostKept bias.
     */
    public LoadBalancerConfig(long staleAfterMs,
                              double msptWeight,
                              double queueDepthWeight,
                              double heapWeight,
                              double playerCountWeight,
                              Map<String, Double> backendWeights,
                              List<ScoringTerm> terms) {
        this(staleAfterMs, msptWeight, queueDepthWeight, heapWeight, playerCountWeight,
                backendWeights, terms, 1.0);
    }

    /** Per-backend preference multiplier, clamped to {@code >= 0.01} per ADR-004. */
    public double backendWeight(String serverId) {
        return Math.max(0.01, backendWeights.getOrDefault(serverId, 1.0));
    }

    private static List<ScoringTerm> synthesizeLegacyTerms(double mspt,
                                                           double queue,
                                                           double heap,
                                                           double players) {
        List<ScoringTerm> out = new ArrayList<>(4);
        if (mspt   > 0.0) out.add(ScoringTerm.linear(MetricInput.MSPT,         mspt));
        if (queue  > 0.0) out.add(ScoringTerm.linear(MetricInput.QUEUE_DEPTH,  queue));
        if (heap   > 0.0) out.add(ScoringTerm.linear(MetricInput.HEAP_USED,    heap));
        if (players> 0.0) out.add(ScoringTerm.linear(MetricInput.PLAYER_COUNT, players));
        return List.copyOf(out);
    }
}
