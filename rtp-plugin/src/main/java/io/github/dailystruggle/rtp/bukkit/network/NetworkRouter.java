package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Decides whether one {@code /rtp} invocation is served locally or routed
 * across the network. Pure-function entry: {@link #route(UUID, String)} reads
 * injected suppliers and produces a {@link RoutingDecision}; the caller acts.
 *
 * <p>L6 of {@code CHECKLIST-cross-server-rtp.md}, Slice C row C1-C4.
 * Decision matrix (PROPOSAL §3, locked D2/D6):</p>
 *
 * <ol>
 *   <li>If network is disabled or no snapshot is available -&gt; {@link RoutingDecision.Local}.</li>
 *   <li>{@code routing.mode = local} -&gt; {@link RoutingDecision.Local} (D2 default).</li>
 *   <li>Implicit local-first (D6 A): if local backend hosts {@code regionKey}
 *       and local {@code keptCount &gt; 0} and the user did not pin a
 *       {@code serverHint} -&gt; {@link RoutingDecision.Local}.</li>
 *   <li>{@code routing.mode = cross-server} or {@code auto} with no local
 *       option: enrol unless one of the {@link RoutingDecision.FallbackReason}
 *       gates rejects (queue full, token bucket exhausted, region unavailable,
 *       no live peer) -&gt; {@link RoutingDecision.LocalFallback}.</li>
 * </ol>
 *
 * <p>S-004: every degraded path returns a typed reason so the caller writes
 * a terminal status / message - never a silent return.</p>
 */
public final class NetworkRouter {

    /** Configured routing mode. {@link Mode#LOCAL} is the D2 default. */
    public enum Mode {
        LOCAL,
        CROSS_SERVER,
        AUTO;

        public static Mode parse(String raw) {
            if (raw == null) return LOCAL;
            String trimmed = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (trimmed) {
                case "cross_server", "crossserver", "cross", "remote" -> CROSS_SERVER;
                case "auto", "smart" -> AUTO;
                default -> LOCAL;
            };
        }
    }

    private final String localServerId;
    private final Mode mode;
    private final Supplier<NetworkSnapshot> snapshotSupplier;
    private final IntSupplier localKeptCountSupplier;
    private final IntSupplier queueDepthSupplier;
    private final int queueMaxDepth;
    private final TokenBucket tokenBucket;
    private final LongSupplier nowMs;

    public NetworkRouter(String localServerId,
                         Mode mode,
                         Supplier<NetworkSnapshot> snapshotSupplier,
                         IntSupplier localKeptCountSupplier,
                         IntSupplier queueDepthSupplier,
                         int queueMaxDepth,
                         int requestsPerSecond,
                         int requestsBurst,
                         LongSupplier nowMs) {
        if (localServerId == null) throw new IllegalArgumentException("localServerId");
        if (snapshotSupplier == null) throw new IllegalArgumentException("snapshotSupplier");
        if (localKeptCountSupplier == null) throw new IllegalArgumentException("localKeptCountSupplier");
        if (queueDepthSupplier == null) throw new IllegalArgumentException("queueDepthSupplier");
        if (nowMs == null) throw new IllegalArgumentException("nowMs");
        this.localServerId = localServerId;
        this.mode = mode == null ? Mode.LOCAL : mode;
        this.snapshotSupplier = snapshotSupplier;
        this.localKeptCountSupplier = localKeptCountSupplier;
        this.queueDepthSupplier = queueDepthSupplier;
        this.queueMaxDepth = Math.max(0, queueMaxDepth);
        this.tokenBucket = new TokenBucket(
                Math.max(1, requestsBurst),
                Math.max(1, requestsPerSecond),
                nowMs);
        this.nowMs = nowMs;
    }

    public Mode mode() { return mode; }
    public String localServerId() { return localServerId; }

    /**
     * Decide routing for one {@code /rtp} call.
     *
     * @param playerId  the invoking player (not used in v1 scoring; accepted
     *                  for callers that want to thread it for future per-player
     *                  rate limiting)
     * @param regionKey optional region constraint parsed from the command
     *                  arguments (see {@link #parseRegionArg(String)}); null
     *                  treated as "no constraint"
     */
    public RoutingDecision route(UUID playerId, String regionKey) {
        // Gate 1: snapshot must be present (bootstrap finished, transport open).
        NetworkSnapshot snap = snapshotSupplier.get();
        if (snap == null) {
            return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.NETWORK_DISABLED);
        }

        // Gate 2: routing.mode locked to local (D2 default).
        if (mode == Mode.LOCAL) {
            return RoutingDecision.Local.INSTANCE;
        }

        // Gate 3 (D6 A: implicit local-first). When mode == AUTO and the local
        // backend can satisfy this request, do not cross the network.
        if (mode == Mode.AUTO) {
            if (localCanServe(snap, regionKey) && localKeptCountSupplier.getAsInt() > 0) {
                return RoutingDecision.Local.INSTANCE;
            }
        }

        // Gate 4: at least one live, non-killSwitch peer in the snapshot.
        boolean anyLivePeer = false;
        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null) continue;
            if (hb.killSwitch()) continue;
            if (hb.serverId() != null && hb.serverId().equals(localServerId)) {
                // local is allowed to count as a peer only when mode==CROSS_SERVER;
                // under AUTO we already handled local above.
                if (mode == Mode.CROSS_SERVER) { anyLivePeer = true; break; }
                continue;
            }
            anyLivePeer = true;
            break;
        }
        if (!anyLivePeer) {
            return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.NO_LIVE_PEER);
        }

        // Gate 5: regionKey must be advertised somewhere reachable.
        if (regionKey != null && !regionKey.isEmpty()) {
            boolean advertised = false;
            for (BackendHeartbeat hb : snap.all()) {
                if (hb == null || hb.killSwitch()) continue;
                if (hb.regions() != null && hb.regions().contains(regionKey)) { advertised = true; break; }
                // Pre-L6 peer back-compat: legacy regionsAvailable list.
                if (hb.regionsAvailable() != null && hb.regionsAvailable().contains(regionKey)) {
                    advertised = true; break;
                }
            }
            if (!advertised) {
                return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.REGION_UNAVAILABLE);
            }
        }

        // Gate 6: queue depth.
        int depth = queueDepthSupplier.getAsInt();
        if (queueMaxDepth > 0 && depth >= queueMaxDepth) {
            return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.QUEUE_FULL);
        }

        // Gate 7: token bucket.
        if (!tokenBucket.tryAcquire()) {
            return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.TOKEN_BUCKET_EXHAUSTED);
        }

        // Cleared all gates: enrol on the cross-server queue.
        Optional<String> rk = (regionKey == null || regionKey.isEmpty())
                ? Optional.empty()
                : Optional.of(regionKey);
        return new RoutingDecision.CrossServer(Optional.empty(), rk);
    }

    private boolean localCanServe(NetworkSnapshot snap, String regionKey) {
        if (regionKey == null || regionKey.isEmpty()) return true;
        Optional<BackendHeartbeat> local = snap.backend(localServerId);
        if (local.isEmpty()) return false;
        BackendHeartbeat hb = local.get();
        if (hb.killSwitch()) return false;
        if (hb.regions() != null && hb.regions().contains(regionKey)) return true;
        if (hb.regionsAvailable() != null && hb.regionsAvailable().contains(regionKey)) return true;
        return false;
    }

    /**
     * Parser stub for the region argument. L6 ships row C4: only
     * unqualified region names are accepted; any input containing {@code =}
     * or {@code :} is rejected as a syntax error. Qualified
     * {@code <server>=<region>} (D7) is gated on the project-wide
     * colon-to-equals param migration shipping first.
     *
     * @return the unqualified region key, or {@code null} when the argument
     *         is null/empty
     * @throws IllegalArgumentException when the input contains the L6-disabled
     *         qualified-region separators
     */
    public static String parseRegionArg(String arg) {
        if (arg == null) return null;
        String trimmed = arg.trim();
        if (trimmed.isEmpty()) return null;
        // D7: qualified syntax disabled in L6. The '=' check is the future
        // separator; ':' is the pre-migration separator; both are rejected
        // here so a stray input does not silently disable the region filter.
        if (trimmed.indexOf('=') >= 0 || trimmed.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "qualified <server>=<region> syntax is not enabled in L6 (D7): " + trimmed);
        }
        return trimmed;
    }

    /** Visible for tests. */
    TokenBucket tokenBucket() { return tokenBucket; }

    /**
     * Lazy-refill token bucket. Capacity = {@code burst}; refill rate =
     * {@code refillPerSecond} tokens / second. No scheduled thread; we
     * compute the catch-up on every {@link #tryAcquire()} call.
     */
    static final class TokenBucket {
        private final long capacityMillis;
        private final long millisPerToken;
        private final LongSupplier nowMs;
        // We track "next time at which a token is available", encoded as an
        // epoch-ms watermark. When watermark <= now, at least one token is
        // available; on acquire we advance watermark by millisPerToken.
        // Initial watermark = (now - capacityMillis) so the first burst-many
        // calls all succeed.
        private final AtomicLong watermark;

        TokenBucket(int capacity, int refillPerSecond, LongSupplier nowMs) {
            if (capacity <= 0) capacity = 1;
            if (refillPerSecond <= 0) refillPerSecond = 1;
            this.millisPerToken = Math.max(1L, 1000L / refillPerSecond);
            this.capacityMillis = millisPerToken * (long) capacity;
            this.nowMs = nowMs;
            // Watermark seed: setting it to (now - capacityMillis + millisPerToken)
            // means the first `capacity` tryAcquire calls succeed instantly, and
            // the (capacity+1)-th lands strictly above `now` and fails -- a clean
            // burst-size boundary. Without the +millisPerToken term the boundary
            // is off by one and burst+1 calls pass.
            this.watermark = new AtomicLong(nowMs.getAsLong() - capacityMillis + millisPerToken);
        }

        boolean tryAcquire() {
            long now = nowMs.getAsLong();
            while (true) {
                long w = watermark.get();
                long effective = Math.max(w, now - capacityMillis);
                if (effective > now) return false;
                long next = effective + millisPerToken;
                if (watermark.compareAndSet(w, next)) return true;
            }
        }
    }
}
