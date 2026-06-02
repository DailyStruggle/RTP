package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfig;
import io.github.dailystruggle.rtp.proxy.common.selector.RegionAwareSelector;
import io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Snapshot-adapter exposing peer-advertised {@code server:region} entries
 * to the local command pipeline. L6 Slice H2 (rtp-proxy-ADR-014).
 *
 * <p>"Snapshot-adapter, not a timer" - this class reads from the already-
 * cached {@link NetworkSnapshot} that the heartbeat subscriber updates as
 * a side effect of the existing redis dirty-cache traffic. There is no new
 * thread, no new redis op, no new TTL: the registry is a view over data
 * the system already maintains for {@link NetworkRouter} gating.</p>
 *
 * <p>Used by:</p>
 * <ul>
 *   <li>{@link io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter}
 *       (via the extras supplier) to surface {@code backend-a:default} in
 *       tab-completion alongside the local region set.</li>
 *   <li>{@code RTPCmdBukkit}'s
 *       validator to accept a peer-qualified region name that the local
 *       {@code RTP.selectionAPI.regionNames()} set does not contain.</li>
 *   <li>{@code BukkitNetworkCommandHook} (indirectly via the router) to
 *       hard-pin to the named server.</li>
 * </ul>
 *
 * <p>Exclusions on read (S-004 spirit - never silently advertise a peer
 * the request could not actually land on):</p>
 * <ul>
 *   <li>{@code killSwitch} peers: operator-asserted unavailable.</li>
 *   <li>Peers with a {@code null}/empty {@code regions} set AND empty
 *       legacy {@code regionsAvailable}: nothing to advertise.</li>
 * </ul>
 *
 * <p>Self ({@code serverId == localServerId}) is intentionally INCLUDED so
 * that on a backend, {@code /rtp region=} tab-complete distinguishes the
 * load-balanced unqualified {@code default} (network dispatcher chooses a
 * peer) from the self-qualified {@code backend-a:default} (hard-pin to
 * this backend, skip load balancing). The {@code pickMostKept} lobby
 * picker still excludes self by design.</p>
 *
 * <p>Falls back to legacy {@code regionsAvailable} for pre-L6 peers that
 * predate the {@code BackendHeartbeat.regions} field.</p>
 */
public final class PeerRegionRegistry {

    private final Supplier<NetworkSnapshot> snapshotSupplier;
    private final String localServerId;
    private final RegionAwareSelector selector;

    /**
     * Optimistic local decrements applied to peer {@code keptCount} between
     * heartbeat refreshes. When the local hook dispatches a cross-server
     * {@code /rtp} to {@code (serverId, regionKey)}, we immediately bump the
     * decrement so the very next {@code pickMostKept()} call on this JVM
     * scores that target lower and the burst spreads across peers. Each
     * entry remembers the {@code lastSeenEpochMs} of the heartbeat that was
     * authoritative at dispatch time; when a strictly newer heartbeat from
     * that peer arrives, the decrement is cleared (the new ground truth
     * already reflects whatever drain we anticipated).
     */
    private final ConcurrentHashMap<ServerRegion, Decrement> localDecrements =
            new ConcurrentHashMap<>();

    /**
     * Back-compat constructor: builds a registry with the bundled
     * {@link LoadBalancerConfig#defaults()} so {@link #pickMostKept()}
     * reproduces the legacy "most-kept" pick byte-identically (default
     * 1.0 {@code regionScarcityWeight} + {@code exponential(k=5)} curve).
     */
    public PeerRegionRegistry(Supplier<NetworkSnapshot> snapshotSupplier, String localServerId) {
        this(snapshotSupplier, localServerId, LoadBalancerConfig.defaults());
    }

    /**
     * Full constructor: the operator-tuned {@code loadBalancerConfig} from
     * {@code network.yml} drives {@link #pickMostKept()}'s scoring. Lobby
     * and proxy share the exact same scoring table this way.
     */
    public PeerRegionRegistry(Supplier<NetworkSnapshot> snapshotSupplier,
                              String localServerId,
                              LoadBalancerConfig loadBalancerConfig) {
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.localServerId = localServerId; // null-tolerated; matches nothing
        Objects.requireNonNull(loadBalancerConfig, "loadBalancerConfig");
        this.selector = new RegionAwareSelector(loadBalancerConfig);
    }

    /**
     * Record that this lobby has just dispatched a cross-server
     * {@code /rtp} to {@code (serverId, regionKey)}. Bumps the local
     * decrement applied to that peer's {@code keptCount} in subsequent
     * {@link #pickMostKept()} calls until the peer publishes a fresh
     * heartbeat. Idempotent only in the sense that repeated calls before a
     * fresh heartbeat keep accumulating - that is intentional (a 5-burst
     * deserves a 5-decrement).
     *
     * <p>Anchored to the peer's currently-observed {@code lastSeenEpochMs}
     * so the next strictly newer heartbeat clears the decrement
     * automatically. If no heartbeat is observable for that peer (snapshot
     * empty or peer unknown), the decrement still records with anchor
     * {@code 0L}; any subsequent heartbeat with epoch &gt; 0 clears it.</p>
     */
    public void recordDispatch(String serverId, String regionKey) {
        if (serverId == null || serverId.isEmpty()) return;
        if (regionKey == null || regionKey.isEmpty()) return;
        long anchor = 0L;
        try {
            NetworkSnapshot snap = snapshotSupplier.get();
            if (snap != null) {
                BackendHeartbeat hb = snap.backend(serverId).orElse(null);
                if (hb != null) anchor = hb.lastSeenEpochMs();
            }
        } catch (Throwable ignored) {
            // Defensive: a flaky supplier must not block the dispatch path.
        }
        ServerRegion key = new ServerRegion(serverId, regionKey);
        final long observedAt = anchor;
        localDecrements.compute(key, (k, prev) -> {
            if (prev == null) return new Decrement(1, observedAt);
            // Same anchor (no fresh heartbeat since last dispatch) -> stack.
            // Newer anchor seen between dispatches -> reset stack to 1 with
            // the new anchor (the older drain is already reflected upstream).
            if (observedAt > prev.observedAtMs) return new Decrement(1, observedAt);
            return new Decrement(prev.count + 1, prev.observedAtMs);
        });
    }

    /**
     * Visible-for-tests: how many decrements are currently pending against
     * {@code (serverId, regionKey)} after accounting for any fresh
     * heartbeat that may have invalidated the anchor. Zero if no entry.
     */
    public int pendingDecrementFor(String serverId, String regionKey) {
        ServerRegion key = new ServerRegion(serverId, regionKey);
        Decrement d = localDecrements.get(key);
        if (d == null) return 0;
        // Anchor-clearing is lazy: evaluated on the next pickMostKept pass.
        // For inspection, recompute here.
        try {
            NetworkSnapshot snap = snapshotSupplier.get();
            if (snap != null) {
                BackendHeartbeat hb = snap.backend(serverId).orElse(null);
                if (hb != null && hb.lastSeenEpochMs() > d.observedAtMs) return 0;
            }
        } catch (Throwable ignored) {
            // Defensive.
        }
        return d.count;
    }

    /**
     * Internal value type for the decrement bookkeeping.
     * {@code observedAtMs} is the {@code lastSeenEpochMs} of the heartbeat
     * that was authoritative when {@link #recordDispatch} was called.
     */
    private record Decrement(int count, long observedAtMs) {}

    /**
     * Build the current set of peer-qualified {@code server:region} entries.
     * Returns an empty set if the snapshot is null, has no peers, or all
     * peers are excluded by the rules above. Never returns {@code null}.
     *
     * <p>Allocates a fresh set per call. The caller (typically commands-api's
     * tab-completion path) iterates once and discards; no long-lived view
     * cache is needed because the underlying {@code NetworkSnapshot} is
     * already maintained by the transport layer.</p>
     */
    public Set<String> peerEntries() {
        NetworkSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (Throwable ignored) {
            // Defensive: a flaky transport must not crash autocomplete.
            return Set.of();
        }
        if (snap == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null) continue;
            if (hb.killSwitch()) continue;
            if (hb.serverId() == null || hb.serverId().isEmpty()) continue;
            // Self is intentionally NOT excluded: surfacing
            // {@code <self>:default} alongside the unqualified {@code default}
            // lets operators hard-pin to this backend (skip load balancing).
            // Prefer the L6 typed regions Set; fall back to legacy list.
            Set<String> regions = hb.regions();
            if (regions != null && !regions.isEmpty()) {
                for (String r : regions) {
                    if (r != null && !r.isEmpty()) out.add(hb.serverId() + ":" + r);
                }
                continue;
            }
            if (hb.regionsAvailable() != null) {
                for (String r : hb.regionsAvailable()) {
                    if (r != null && !r.isEmpty()) out.add(hb.serverId() + ":" + r);
                }
            }
        }
        return out;
    }

    /**
     * Lobby-mode target picker. Delegates to the unified
     * {@link RegionAwareSelector}, which scores every qualifying
     * {@code (serverId, regionKey)} candidate with the same weighted-curve
     * formula the proxy uses for its own {@code BackendSelector} (one
     * scoring table, two call sites - rtp-proxy-ADR-004).
     *
     * <p>The synthesized {@link
     * io.github.dailystruggle.rtp.proxy.common.selector.MetricInput#KEPT_REGION}
     * scarcity term with {@code exponential(k=5)} reproduces the legacy
     * "most-kept wins" behaviour byte-identically when no other scoring
     * terms are configured: a region 90% empty contributes ~0.85, a region
     * 50% empty only ~0.07. Operators wanting MSPT/heap/queue to also
     * influence the lobby pick add explicit terms to {@code network.yml}'s
     * {@code loadBalancer.terms} block - same place the proxy reads.</p>
     *
     * <p>Filtering: self ({@code localServerId}) excluded; non-{@code READY},
     * non-{@code acceptingRequests}, and {@code killSwitch} peers excluded;
     * stale heartbeats excluded. Optimistic {@link #recordDispatch}
     * decrements applied per-pair as a post-score adjustment so a recent
     * burst spreads across peers before the next heartbeat tick.</p>
     */
    public Optional<ServerRegion> pickMostKept() {
        NetworkSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
        if (snap == null) return Optional.empty();

        // Synthetic no-region, no-world request: the lobby does not know
        // ahead of time which region the player wants, so we let the
        // scoring table choose. TriggerType.COMMAND matches the bare /rtp
        // entry path.
        RtpRequest req = new RtpRequest(
                new UUID(0L, 0L),
                TriggerType.COMMAND,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new UUID(0L, 0L));

        return selector.choose(snap, req, localServerId, this::applyLocalDecrement);
    }

    /**
     * {@link RegionAwareSelector.PostScoreAdjust} hook applying the
     * optimistic local-decrement bookkeeping. Lazily evicts entries whose
     * heartbeat anchor has been superseded (ground truth wins). Each
     * pending decrement nudges the score upward by a small constant so
     * recently-targeted pairs lose ties without overpowering MSPT/heap/
     * queue terms that operators may have configured.
     */
    private double applyLocalDecrement(ServerRegion sr, BackendHeartbeat hb, double rawScore) {
        Decrement d = localDecrements.get(sr);
        if (d == null) return rawScore;
        if (hb.lastSeenEpochMs() > d.observedAtMs) {
            localDecrements.remove(sr, d);
            return rawScore;
        }
        // Per-decrement nudge. Scaled so 1 dispatch adds ~the same penalty
        // as a half-empty kept pool under the default exponential(k=5)
        // scarcity term; this keeps the local-burst-spreading behaviour
        // from the legacy implementation while operator-tuned MSPT/heap
        // terms can still dominate if they are large.
        return rawScore + (d.count * 0.05);
    }

    /**
     * Hard-pin reachability check: does {@code serverId} currently
     * advertise {@code regionKey} in a non-{@code killSwitch} heartbeat?
     * Used by the {@code RTPCmdBukkit} validator so a player typing
     * {@code rtp region=backend-a:default} passes commands-api's
     * isRelevant gate only when the registry confirms backend-a is alive
     * and hosts {@code default}.
     */
    public boolean isReachableHardPin(String serverId, String regionKey) {
        if (serverId == null || serverId.isEmpty()) return false;
        if (regionKey == null || regionKey.isEmpty()) return false;
        // Self is intentionally reachable as a hard-pin: typing
        // {@code /rtp region=<self>:default} on a backend pins the request
        // to this backend and bypasses the lobby/network load balancer.
        NetworkSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (Throwable ignored) {
            return false;
        }
        if (snap == null) return false;
        BackendHeartbeat hb = snap.backend(serverId).orElse(null);
        if (hb == null || hb.killSwitch()) return false;
        if (hb.regions() != null && hb.regions().contains(regionKey)) return true;
        if (hb.regionsAvailable() != null && hb.regionsAvailable().contains(regionKey)) return true;
        return false;
    }
}
