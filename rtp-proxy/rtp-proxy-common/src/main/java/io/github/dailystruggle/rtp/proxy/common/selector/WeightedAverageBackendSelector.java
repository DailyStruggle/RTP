package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Reference {@link BackendSelector} implementing the weighted-average scoring
 * formula from rtp-proxy-ADR-004:
 *
 * <pre>
 * score(b) = ( Σ_i weight_i * normalize_i(metric_i(b)) ) / backendWeight(b)
 * </pre>
 *
 * <p>Lowest score wins; ties broken by {@code serverId} ascending. This v1
 * implementation uses the linear curve only; the curve catalogue
 * ({@code exponential}, {@code logarithmic}, {@code sigmoid}, {@code step},
 * {@code power}) is reserved for a later iteration and will plug in via
 * the same scoring entry point without changing the SPI.</p>
 *
 * <p><strong>Purity:</strong> {@link #choose} performs no I/O and reads no
 * external state beyond its arguments and the {@link LoadBalancerConfig}
 * supplied at construction (REQ-RTP-PROXY-COMMON-002).</p>
 *
 * <p>Candidate filtering (rtp-proxy-ADR-004 §Candidate Filtering): staleness,
 * {@link BackendHeartbeat.PluginState} = {@code READY},
 * {@link BackendHeartbeat#acceptingRequests()}, region constraint, world
 * constraint. {@code schemaVersion} negotiation and cooldown maps are left
 * to the dispatcher / reservation client (they are not pure-function inputs).</p>
 */
public final class WeightedAverageBackendSelector implements BackendSelector {

    private final LoadBalancerConfig config;

    public WeightedAverageBackendSelector(LoadBalancerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Optional<String> choose(RtpRequest request, NetworkSnapshot snapshot) {
        return choose(request, snapshot, Optional.empty());
    }

    @Override
    public Optional<String> choose(RtpRequest request,
                                   NetworkSnapshot snapshot,
                                   Optional<String> serverIdFilter) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(serverIdFilter, "serverIdFilter");

        return snapshot.all().stream()
                .filter(b -> serverIdFilter.isEmpty()
                        || serverIdFilter.get().equals(b.serverId()))
                .filter(b -> qualifies(b, request, snapshot.timestampEpochMs()))
                .min(Comparator
                        .comparingDouble((BackendHeartbeat b) -> score(b))
                        .thenComparing(BackendHeartbeat::serverId))
                .map(BackendHeartbeat::serverId);
    }

    /** Debug accessor for golden-file tests (rtp-proxy-ADR-004 §Determinism). */
    public double score(BackendHeartbeat b) {
        double msptNorm = b.mspt() / 50.0;
        double queueNorm = (double) b.queueDepth() / Math.max(1, b.softCap());
        double heapNorm = (b.heapMaxBytes() <= 0)
                ? 0.0 : (double) b.heapUsedBytes() / b.heapMaxBytes();
        double playerNorm = b.playerCount() / 100.0;

        double raw = config.msptWeight()        * msptNorm
                   + config.queueDepthWeight()  * queueNorm
                   + config.heapWeight()        * heapNorm
                   + config.playerCountWeight() * playerNorm;

        return raw / config.backendWeight(b.serverId());
    }

    private boolean qualifies(BackendHeartbeat b, RtpRequest req, long snapshotTs) {
        // Staleness filter (REQ-RTP-PROXY-COMMON-003).
        if (snapshotTs - b.lastSeenEpochMs() > config.staleAfterMs()) {
            return false;
        }
        if (b.pluginState() != BackendHeartbeat.PluginState.READY) {
            return false;
        }
        if (!b.acceptingRequests()) {
            return false;
        }
        // L6 D4: kill switch excludes backend from candidate scoring.
        if (b.killSwitch()) {
            return false;
        }
        if (req.regionKey().isPresent()) {
            String key = req.regionKey().get();
            // L6 D4 option (ii): prefer the typed Set<regions> when populated;
            // fall back to the legacy regionsAvailable list for backends that
            // have not been upgraded to publish the L6 fields yet.
            boolean regionHosted = !b.regions().isEmpty()
                    ? b.regions().contains(key)
                    : (b.regionsAvailable().isEmpty()
                            || b.regionsAvailable().contains(key));
            if (!regionHosted) {
                return false;
            }
            // L6 D4 option (ii): regionKeptCounts is scoped to
            // networkKeptLocations. Only require a positive count when the
            // backend has opted into publishing the map at all; otherwise the
            // peer is pre-L6 and we let the dispatcher's queue-vs-fail logic
            // handle "no coordinate" via the existing reservation path.
            if (!b.regionKeptCounts().isEmpty()
                    && b.regionKeptCounts().getOrDefault(key, 0) <= 0) {
                return false;
            }
        } else {
            // No region pinned: prefer backends with a warm kept pool. Skip
            // the predicate for pre-L6 peers that publish keptCount == 0
            // because they do not yet report the field (default zero).
            if (b.keptCount() < 0) {
                return false;
            }
        }
        if (req.worldKey().isPresent()
                && !b.worldsLoaded().isEmpty()
                && !b.worldsLoaded().contains(req.worldKey().get())) {
            return false;
        }
        return true;
    }
}
