package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pair-wise load-balancer that scores every {@code (serverId, regionKey)}
 * candidate exposed by a {@link NetworkSnapshot} using the same scoring
 * formula as {@link WeightedAverageBackendSelector} (rtp-proxy-ADR-004), plus
 * an implicit or operator-authored {@link MetricInput#KEPT_REGION} term that
 * penalises regions whose kept pool is shallow.
 *
 * <p>Used by:</p>
 * <ul>
 *   <li>The <strong>lobby-side</strong> "blank {@code /rtp}" picker
 *       (formerly {@code PeerRegionRegistry.pickMostKept()}, now a thin
 *       wrapper around this selector). The lobby has no local region pool,
 *       so it always wants {@code (serverId, regionKey)}.</li>
 *   <li>The <strong>proxy-side</strong> fallback when an
 *       {@link RtpRequest} arrives with no {@code regionKey} hint (e.g. a
 *       proxy-driven join-time RTP without a lobby pre-decision). The proxy
 *       still gets a pair back and uses {@code regionKey} for the
 *       reservation.</li>
 * </ul>
 *
 * <p><strong>Why one selector, two call sites:</strong> operators tune curves
 * and weights in one place. Lobby and proxy disagree only when they observe
 * different snapshots, which is acceptable and self-corrects on the next
 * heartbeat tick.</p>
 *
 * <p><strong>Scoring:</strong></p>
 * <pre>
 * score(b, r) = ( Sigma_i  weight_i * curve_i( input_i.normalize(b, r) ) )
 *             / backendWeight(b)
 * </pre>
 * <p>where {@code Sigma_i} runs over {@link LoadBalancerConfig#terms()} plus,
 * when no explicit {@code KEPT_REGION} term is present and
 * {@code regionScarcityWeight > 0}, the synthesized term from
 * {@link LoadBalancerConfig#regionScarcityTerm()}.</p>
 *
 * <p>Lowest score wins. Ties broken by {@code serverId} ascending, then
 * {@code regionKey} ascending.</p>
 *
 * <p><strong>Purity:</strong> {@link #choose} is a pure function of its
 * arguments plus the configuration supplied at construction
 * (REQ-RTP-PROXY-COMMON-002). The optional {@code postScoreAdjust} hook
 * exists for the lobby's {@code localDecrements} bookkeeping, which is per-JVM
 * state belonging to the caller, not to the selector.</p>
 */
public final class RegionAwareSelector {

    private static final Logger LOG = Logger.getLogger(RegionAwareSelector.class.getName());

    private final LoadBalancerConfig config;

    public RegionAwareSelector(LoadBalancerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Convenience: score and pick the best {@code (serverId, regionKey)}
     * pair, excluding {@code excludedServerId} (typically the local backend
     * on a lobby-side call so the lobby does not pick itself).
     */
    public Optional<ServerRegion> choose(NetworkSnapshot snapshot,
                                         RtpRequest request,
                                         String excludedServerId) {
        return choose(snapshot, request, excludedServerId, null);
    }

    /**
     * Full form. {@code postScoreAdjust} is an optional hook receiving each
     * candidate pair and its raw score; it returns the adjusted score that
     * participates in the {@code min}. Used by {@code PeerRegionRegistry} to
     * apply its optimistic local kept-count decrements between heartbeats.
     */
    public Optional<ServerRegion> choose(NetworkSnapshot snapshot,
                                         RtpRequest request,
                                         String excludedServerId,
                                         PostScoreAdjust postScoreAdjust) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");

        List<Scored> scored = scoreAll(snapshot, request, excludedServerId, postScoreAdjust);
        Optional<ServerRegion> winner = scored.stream()
                .min(Comparator
                        .comparingDouble((Scored s) -> s.score)
                        .thenComparing(s -> s.candidate.serverId())
                        .thenComparing(s -> s.candidate.regionKey()))
                .map(s -> s.candidate);
        if (LOG.isLoggable(Level.INFO)) {
            logDecision(request, snapshot, excludedServerId, scored, winner);
        }
        return winner;
    }

    private void logDecision(RtpRequest request,
                             NetworkSnapshot snapshot,
                             String excludedServerId,
                             List<Scored> scored,
                             Optional<ServerRegion> winner) {
        ScoringTerm scarcityTerm = config.regionScarcityTerm();
        StringBuilder sb = new StringBuilder(384);
        sb.append("[NETWORK][selector][region] choose(player=")
                .append(request.playerId())
                .append(", region=").append(request.regionKey().orElse("-"))
                .append(", world=").append(request.worldKey().orElse("-"))
                .append(", exclude=").append(excludedServerId == null ? "-" : excludedServerId)
                .append(", snapshotTs=").append(snapshot.timestampEpochMs())
                .append(", candidates=").append(scored.size())
                .append(", scarcityTerm=").append(scarcityTerm == null ? "off" : "on")
                .append(")");
        if (scored.isEmpty()) {
            sb.append(" -> no qualifying (serverId, regionKey)");
            LOG.log(Level.INFO, sb.toString());
            return;
        }
        // Re-resolve heartbeats by serverId for breakdown printing.
        for (Scored s : scored) {
            BackendHeartbeat hb = findHeartbeat(snapshot, s.candidate.serverId());
            if (hb == null) {
                sb.append("\n  - ").append(s.candidate.serverId())
                        .append(":").append(s.candidate.regionKey())
                        .append(" score=").append(fmt(s.score))
                        .append(" (no heartbeat)");
                continue;
            }
            double total = 0.0;
            sb.append("\n  - ").append(s.candidate.serverId()).append(":").append(s.candidate.regionKey());
            for (ScoringTerm term : config.terms()) {
                double raw = term.input().normalize(hb, s.candidate.regionKey());
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
            if (scarcityTerm != null) {
                double raw = scarcityTerm.input().normalize(hb, s.candidate.regionKey());
                double shaped = scarcityTerm.curve().apply(raw);
                double contrib = scarcityTerm.weight() * shaped;
                total += contrib;
                sb.append(" [scarcity:").append(scarcityTerm.input().configId())
                        .append(" raw=").append(fmt(raw))
                        .append(" curve=").append(scarcityTerm.curve().getClass().getSimpleName())
                        .append("->").append(fmt(shaped))
                        .append(" *w=").append(fmt(scarcityTerm.weight()))
                        .append("=").append(fmt(contrib))
                        .append("]");
            }
            double bw = config.backendWeight(hb.serverId());
            double pre = total / bw;
            sb.append(" sum=").append(fmt(total))
                    .append(" /backendWeight(").append(fmt(bw)).append(")")
                    .append(" =").append(fmt(pre));
            if (Math.abs(pre - s.score) > 1e-9) {
                sb.append(" postAdjust->").append(fmt(s.score));
            } else {
                sb.append(" -> score=").append(fmt(s.score));
            }
        }
        winner.ifPresent(w -> sb.append("\n  WINNER ").append(w.serverId()).append(":").append(w.regionKey()));
        LOG.log(Level.INFO, sb.toString());
    }

    private static BackendHeartbeat findHeartbeat(NetworkSnapshot snapshot, String serverId) {
        for (BackendHeartbeat hb : snapshot.all()) {
            if (serverId.equals(hb.serverId())) return hb;
        }
        return null;
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    /**
     * Debug accessor for golden-file tests: returns every qualifying
     * candidate with its score. Iteration order is snapshot order then
     * region iteration order; callers shall not rely on it (use the
     * {@link Comparator} from {@link #choose} for deterministic ordering).
     */
    public List<Scored> scoreAll(NetworkSnapshot snapshot,
                                 RtpRequest request,
                                 String excludedServerId,
                                 PostScoreAdjust postScoreAdjust) {
        List<Scored> out = new ArrayList<>();
        ScoringTerm scarcityTerm = config.regionScarcityTerm();
        long snapTs = snapshot.timestampEpochMs();
        for (BackendHeartbeat hb : snapshot.all()) {
            if (!qualifies(hb, request, snapTs, excludedServerId)) continue;
            for (String region : enumerateRegions(hb)) {
                if (region == null || region.isEmpty()) continue;
                if (request.regionKey().isPresent() && !region.equals(request.regionKey().get())) {
                    // A pinned regionKey collapses the candidate set to that region only.
                    continue;
                }
                double raw = 0.0;
                for (ScoringTerm term : config.terms()) {
                    raw += term.contribution(hb, region);
                }
                if (scarcityTerm != null) {
                    raw += scarcityTerm.contribution(hb, region);
                }
                double score = raw / config.backendWeight(hb.serverId());
                ServerRegion sr = new ServerRegion(hb.serverId(), region);
                if (postScoreAdjust != null) {
                    score = postScoreAdjust.adjust(sr, hb, score);
                }
                out.add(new Scored(sr, score));
            }
        }
        return out;
    }

    private boolean qualifies(BackendHeartbeat b,
                              RtpRequest req,
                              long snapshotTs,
                              String excludedServerId) {
        if (b == null) return false;
        if (b.serverId() == null || b.serverId().isEmpty()) return false;
        if (excludedServerId != null && excludedServerId.equals(b.serverId())) return false;
        if (snapshotTs - b.lastSeenEpochMs() > config.staleAfterMs()) return false;
        if (b.pluginState() != BackendHeartbeat.PluginState.READY) return false;
        if (!b.acceptingRequests()) return false;
        if (b.killSwitch()) return false;
        if (req.worldKey().isPresent()
                && !b.worldsLoaded().isEmpty()
                && !b.worldsLoaded().contains(req.worldKey().get())) {
            return false;
        }
        // Per-region kept-count filter is intentionally not enforced here:
        // the scarcity term handles "few coordinates" by raising the score,
        // not by hard-excluding. The dispatcher's reservation path handles
        // the empty-pool case via the existing queue-vs-fail logic.
        return true;
    }

    private static Set<String> enumerateRegions(BackendHeartbeat b) {
        Set<String> regions = b.regions();
        if (regions != null && !regions.isEmpty()) return regions;
        if (b.regionsAvailable() != null && !b.regionsAvailable().isEmpty()) {
            return new HashSet<>(b.regionsAvailable());
        }
        // Also surface any region named in regionKeptCounts even if it is
        // absent from the typed set (defensive: a peer that publishes a
        // count for a region must be able to serve it).
        Map<String, Integer> rkc = b.regionKeptCounts();
        if (rkc != null && !rkc.isEmpty()) return rkc.keySet();
        return Set.of();
    }

    /** Per-pair score row returned by {@link #scoreAll}. */
    public record Scored(ServerRegion candidate, double score) {}

    /** Optional caller hook to adjust a raw per-pair score (e.g. local-decrement bookkeeping). */
    @FunctionalInterface
    public interface PostScoreAdjust {
        double adjust(ServerRegion candidate, BackendHeartbeat backend, double rawScore);
    }
}
