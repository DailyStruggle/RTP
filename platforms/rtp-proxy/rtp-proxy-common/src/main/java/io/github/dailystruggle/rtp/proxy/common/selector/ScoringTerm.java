package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curve;
import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curves;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;

import java.util.Objects;

/**
 * One row of the load-balancer scoring table: a normalized metric input
 * shaped by a curve and multiplied by a non-negative weight. Consumed by
 * {@link WeightedAverageBackendSelector#score(BackendHeartbeat)} per
 * rtp-proxy-ADR-004.
 *
 * <p>{@code weight} is clamped to {@code >= 0}. A weight of {@code 0} is
 * allowed and effectively disables the term while keeping it in the
 * configuration for documentation / future re-enablement.</p>
 */
public record ScoringTerm(MetricInput input, double weight, Curve curve) {

    public ScoringTerm {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(curve, "curve");
        if (Double.isNaN(weight) || weight < 0.0) weight = 0.0;
    }

    /** Convenience constructor with a {@link Curves#linear() linear} curve. */
    public static ScoringTerm linear(MetricInput input, double weight) {
        return new ScoringTerm(input, weight, Curves.linear());
    }

    /** Computes {@code weight * curve(input.normalize(b))} for one heartbeat. */
    public double contribution(BackendHeartbeat b) {
        if (weight <= 0.0) return 0.0;
        return weight * curve.apply(input.normalize(b));
    }

    /**
     * Region-aware contribution: {@code weight * curve(input.normalize(b, regionKey))}.
     * Backend-scoped inputs ignore {@code regionKey}; region-scoped inputs
     * (e.g. {@link MetricInput#KEPT_REGION}) consume it. Used by
     * {@code RegionAwareSelector} to score {@code (serverId, regionKey)} pairs.
     */
    public double contribution(BackendHeartbeat b, String regionKey) {
        if (weight <= 0.0) return 0.0;
        return weight * curve.apply(input.normalize(b, regionKey));
    }
}
