package io.github.dailystruggle.rtp.common.network;

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
        return route(playerId, regionKey, null);
    }

    /**
     * Decide routing with an optional hard-pin to a specific peer
     * {@code serverHint}. L6 Slice H2: when the player typed
     * {@code rtp region=<server>:<region>}, the {@code serverHint} is the
     * parsed server id and the semantics are <strong>hard-pin</strong> -
     * if the named server is missing from the snapshot, {@code killSwitch},
     * or does not advertise the requested {@code regionKey}, the router
     * returns {@link RoutingDecision.LocalFallback} with reason
     * {@link RoutingDecision.FallbackReason#REGION_UNAVAILABLE} so the caller
     * can emit the localized {@code networkRegionUnavailable} message.
     *
     * @param serverHint optional pinned backend server id; {@code null}/empty
     *                   for "selector picks per snapshot"
     */
    public RoutingDecision route(UUID playerId, String regionKey, String serverHint) {
        String hint = (serverHint == null || serverHint.isEmpty()) ? null : serverHint;

        // Self-pin coalesce (2026-05-23): when the player typed
        // `rtp region=<server>:<region>` and `<server>` IS the local
        // backend, the prefix is redundant. Drop it so the request
        // takes the normal local path instead of getting funnelled
        // through the cross-server pin gates (which would otherwise
        // either bounce the player off-and-back, or REJECT with
        // REGION_UNAVAILABLE if the local backend happens to advertise
        // the region under a different name in its heartbeat). Treating
        // `region=<self>:<region>` as equivalent to `region=<region>`
        // is the principle-of-least-surprise behaviour.
        if (hint != null && hint.equals(localServerId)) {
            hint = null;
        }

        // Gate 1: snapshot must be present (bootstrap finished, transport open).
        NetworkSnapshot snap = snapshotSupplier.get();
        if (snap == null) {
            return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.NETWORK_DISABLED);
        }

        // Gate 2: routing.mode locked to local (D2 default).
        // Hard-pin override (H2): when the player explicitly named a peer
        // server in `rtp region=<server>:<region>`, we cannot just silently
        // serve locally even under mode=LOCAL; the operator's intent was
        // unambiguous. Fall through to the gates below so the request
        // either routes or REJECTs with a typed reason.
        if (mode == Mode.LOCAL && hint == null) {
            return RoutingDecision.Local.INSTANCE;
        }

        // Gate 3 (D6 A: implicit local-first). When mode == AUTO and the local
        // backend can satisfy this request, do not cross the network.
        // Skipped under hard-pin: a named server hint MUST be honoured exactly.
        if (mode == Mode.AUTO && hint == null) {
            if (localCanServe(snap, regionKey) && localKeptCountSupplier.getAsInt() > 0) {
                return RoutingDecision.Local.INSTANCE;
            }
        }

        // Hard-pin gate (H2, before generic peer-liveness check): if the
        // player named a specific server, it must exist, be alive, non-
        // killSwitch, and advertise the requested region (when supplied).
        if (hint != null) {
            BackendHeartbeat pinned = snap.backend(hint).orElse(null);
            if (pinned == null || pinned.killSwitch()) {
                return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.REGION_UNAVAILABLE);
            }
            if (regionKey != null && !regionKey.isEmpty()) {
                boolean hostsRegion = (pinned.regions() != null && pinned.regions().contains(regionKey))
                        || (pinned.regionsAvailable() != null && pinned.regionsAvailable().contains(regionKey));
                if (!hostsRegion) {
                    return new RoutingDecision.LocalFallback(RoutingDecision.FallbackReason.REGION_UNAVAILABLE);
                }
            }
        }

        // Gate 4: at least one live, non-killSwitch peer in the snapshot.
        // (Hard-pin already proved one above; this is the unpinned path.)
        if (hint == null) {
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
        Optional<String> sh = (hint == null) ? Optional.empty() : Optional.of(hint);
        return new RoutingDecision.CrossServer(sh, rk);
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
     * Parsed region argument carrying an optional {@code serverHint} and a
     * {@code regionKey}. L6 Slice H2 syntax:
     *
     * <ul>
     *   <li>{@code default} -&gt; {@code (serverHint=null, regionKey="default")}</li>
     *   <li>{@code backend-a:default} -&gt; {@code (serverHint="backend-a", regionKey="default")}</li>
     * </ul>
     *
     * <p>The {@code =} character remains reserved per D7 (project-wide
     * colon-to-equals param migration); it is rejected outright. The
     * {@code :} character now splits {@code server:region} exactly once;
     * additional colons (e.g. {@code a:b:c}) are a syntax error.</p>
     */
    public record ParsedRegion(String serverHint, String regionKey) {
        public ParsedRegion {
            if (serverHint != null && serverHint.isEmpty()) serverHint = null;
            // regionKey may be null when the player just typed `region=` with
            // no value; caller treats that as "no constraint".
        }
    }

    /**
     * Backward-compat parser stub: extracts just the {@code regionKey} from
     * the argument, dropping any {@code serverHint}. Existing
     * non-network-mode callers can stay on this signature; H2 hooks should
     * prefer {@link #parseRegionArgQualified(String)}.
     *
     * @return the unqualified region key, or {@code null} when the argument
     *         is null/empty
     * @throws IllegalArgumentException when the input contains the still-
     *         disabled {@code =} separator or a malformed colon sequence
     */
    public static String parseRegionArg(String arg) {
        ParsedRegion parsed = parseRegionArgQualified(arg);
        return parsed == null ? null : parsed.regionKey();
    }

    /**
     * Parse an optionally-qualified region argument. Returns {@code null}
     * when {@code arg} is null or whitespace-only. L6 Slice H2.
     *
     * @throws IllegalArgumentException when {@code arg} contains the
     *         reserved {@code =} character (D7) or more than one colon
     */
    public static ParsedRegion parseRegionArgQualified(String arg) {
        if (arg == null) return null;
        String trimmed = arg.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.indexOf('=') >= 0) {
            throw new IllegalArgumentException(
                    "'=' is reserved (D7); use '<server>:<region>': " + trimmed);
        }
        int firstColon = trimmed.indexOf(':');
        if (firstColon < 0) {
            return new ParsedRegion(null, trimmed);
        }
        int lastColon = trimmed.lastIndexOf(':');
        if (firstColon != lastColon) {
            throw new IllegalArgumentException(
                    "malformed '<server>:<region>' (more than one ':'): " + trimmed);
        }
        String server = trimmed.substring(0, firstColon).trim();
        String region = trimmed.substring(firstColon + 1).trim();
        if (server.isEmpty() || region.isEmpty()) {
            throw new IllegalArgumentException(
                    "malformed '<server>:<region>' (empty half): " + trimmed);
        }
        return new ParsedRegion(server, region);
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
