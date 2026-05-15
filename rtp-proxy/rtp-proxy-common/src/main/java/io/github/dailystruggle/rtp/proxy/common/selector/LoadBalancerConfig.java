package io.github.dailystruggle.rtp.proxy.common.selector;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration record consumed by {@link WeightedAverageBackendSelector}. A
 * stripped-down projection of the full {@code network.yml} {@code loadBalancer}
 * subtree (rtp-proxy-ADR-002) carrying only the keys the v1 reference selector
 * needs.
 *
 * <p>All weights, bounds, and defaults are pinned by rtp-proxy-ADR-004.</p>
 *
 * @param staleAfterMs        backend rows older than this relative to the
 *                            snapshot timestamp are excluded (REQ-RTP-PROXY-COMMON-003)
 * @param msptWeight          weight of the {@code mspt / 50.0} term (default 1.0)
 * @param queueDepthWeight    weight of the {@code queueDepth / max(1, softCap)} term (default 1.0)
 * @param heapWeight          weight of the {@code heapUsed / heapMax} term (default 0.5)
 * @param playerCountWeight   weight of the {@code playerCount / 100.0} term (default 0.0 — REQ-RTP-NET v1)
 * @param backendWeights      per-{@code serverId} preference multiplier; missing key ⇒ 1.0
 */
public record LoadBalancerConfig(
        long staleAfterMs,
        double msptWeight,
        double queueDepthWeight,
        double heapWeight,
        double playerCountWeight,
        Map<String, Double> backendWeights
) {
    /** Bundled defaults aligned with rtp-proxy-ADR-004. */
    public static LoadBalancerConfig defaults() {
        return new LoadBalancerConfig(
                30_000L,
                1.0,
                1.0,
                0.5,
                0.0,
                Map.of()
        );
    }

    public LoadBalancerConfig {
        if (staleAfterMs < 0) {
            throw new IllegalArgumentException("staleAfterMs must be >= 0");
        }
        Objects.requireNonNull(backendWeights, "backendWeights");
        backendWeights = Map.copyOf(backendWeights);
    }

    /** Per-backend preference multiplier, clamped to {@code >= 0.01} per ADR-004. */
    public double backendWeight(String serverId) {
        return Math.max(0.01, backendWeights.getOrDefault(serverId, 1.0));
    }
}
