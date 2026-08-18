package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reference {@link BackendSelector} implementing the weighted-average scoring
 * formula from rtp-proxy-ADR-004:
 *
 * <pre>
 * score(b) = ( Sigma_i weight_i * curve_i( normalize_i( metric_i(b) ) ) ) / backendWeight(b)
 * </pre>
 *
 * <p>Lowest score wins; ties broken by {@code serverId} ascending. The sum
 * runs over {@link LoadBalancerConfig#terms()}, each a {@link ScoringTerm}
 * pairing one {@link MetricInput} with a non-negative weight and a curve
 * drawn from the catalogue (linear, exponential, logarithmic, sigmoid, step,
 * power). For source compatibility, a {@link LoadBalancerConfig} built from
 * the four legacy weights (mspt, queueDepth, heap, playerCount) auto-synthesizes
 * one linear term per non-zero weight.</p>
 *
 * <p><strong>Purity:</strong> {@link #choose} performs no I/O and reads no
 * external state beyond its arguments and the {@link LoadBalancerConfig}
 * supplied at construction (REQ-RTP-PROXY-COMMON-002).</p>
 *
 * <p>Candidate filtering (rtp-proxy-ADR-004 section Candidate Filtering): staleness,
 * {@link BackendHeartbeat.PluginState} = {@code READY},
 * {@link BackendHeartbeat#acceptingRequests()}, region constraint, world
 * constraint. {@code schemaVersion} negotiation and cooldown maps are left
 * to the dispatcher / reservation client (they are not pure-function inputs).</p>
 */
public final class WeightedAverageBackendSelector implements BackendSelector {

    private static final Logger LOG = Logger.getLogger(WeightedAverageBackendSelector.class.getName());

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

        List<BackendHeartbeat> qualified = new ArrayList<>();
        for (BackendHeartbeat b : snapshot.all()) {
            if (!serverIdFilter.isEmpty() && !serverIdFilter.get().equals(b.serverId())) continue;
            if (!qualifies(b, request, snapshot.timestampEpochMs())) continue;
            qualified.add(b);
        }
        Optional<BackendHeartbeat> winner = qualified.stream()
                .min(Comparator
                        .comparingDouble((BackendHeartbeat b) -> score(b))
                        .thenComparing(BackendHeartbeat::serverId));
        if (LOG.isLoggable(Level.INFO)) {
            logDecision(request, snapshot, serverIdFilter, qualified, winner);
        }
        return winner.map(BackendHeartbeat::serverId);
    }

    private void logDecision(RtpRequest request,
                             NetworkSnapshot snapshot,
                             Optional<String> serverIdFilter,
                             List<BackendHeartbeat> qualified,
                             Optional<BackendHeartbeat> winner) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("[NETWORK][selector][backend] choose(player=")
                .append(request.playerId())
                .append(", region=").append(request.regionKey().orElse("-"))
                .append(", world=").append(request.worldKey().orElse("-"))
                .append(", filter=").append(serverIdFilter.orElse("-"))
                .append(", snapshotTs=").append(snapshot.timestampEpochMs())
                .append(", candidates=").append(qualified.size())
                .append(")");
        if (qualified.isEmpty()) {
            sb.append(" -> no qualifying backend");
            LOG.log(Level.INFO, sb.toString());
            return;
        }
        for (BackendHeartbeat b : qualified) {
            double total = 0.0;
            sb.append("\n  - serverId=").append(b.serverId());
            for (ScoringTerm term : config.terms()) {
                double raw = term.input().normalize(b);
                double shaped = term.curve().apply(raw);
                double contrib = term.weight() * shaped;
                total += contrib;
                sb.append(" [").append(term.input().configId())
                        .append(" raw=").append(fmt(raw))
                        .append(" curve=").append(term.curve().getClass().getSimpleName())
                        .append("->").append(fmt(shaped))
                        .append(" *w=").append(fmt(term.weight()))
                        .append("=").append(fmt(contrib))
                        .append("]");
            }
            double bw = config.backendWeight(b.serverId());
            double finalScore = total / bw;
            sb.append(" sum=").append(fmt(total))
                    .append(" /backendWeight(").append(fmt(bw)).append(")")
                    .append(" -> score=").append(fmt(finalScore));
        }
        winner.ifPresent(w -> sb.append("\n  WINNER serverId=").append(w.serverId())
                .append(" score=").append(fmt(score(w))));
        LOG.log(Level.INFO, sb.toString());
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    /** Debug accessor for golden-file tests (rtp-proxy-ADR-004 Determinism). */
    public double score(BackendHeartbeat b) {
        double raw = 0.0;
        for (ScoringTerm term : config.terms()) {
            raw += term.contribution(b);
        }
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
        // Kill switch excludes backend from candidate scoring.
        if (b.killSwitch()) {
            return false;
        }
        if (req.regionKey().isPresent()) {
            String key = req.regionKey().get();
            // Prefer the typed Set<regions> when populated;
            // fall back to the legacy List<regionsAvailable> for older peers.
            boolean regionHosted = !b.regions().isEmpty()
                    ? b.regions().contains(key)
                    : (b.regionsAvailable().isEmpty()
                            || b.regionsAvailable().contains(key));
            if (!regionHosted) {
                return false;
            }
            // regionKeptCounts is scoped to
            // networkKeptLocations. Only require a positive count when the
            // backend has opted into publishing the map at all; otherwise the
            // peer has no regionKeptCounts and we let the dispatcher's queue-vs-fail logic
            // handle "no coordinate" via the existing reservation path.
            if (!b.regionKeptCounts().isEmpty()
                    && b.regionKeptCounts().getOrDefault(key, 0) <= 0) {
                return false;
            }
        } else {
            // No region pinned: prefer backends with a warm kept pool. Skip
            // the predicate for peers that publish keptCount == 0
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
