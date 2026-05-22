package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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
 *   <li>{@link io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit}'s
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

    public PeerRegionRegistry(Supplier<NetworkSnapshot> snapshotSupplier, String localServerId) {
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.localServerId = localServerId; // null-tolerated; matches nothing
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
    int pendingDecrementFor(String serverId, String regionKey) {
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
     * Lobby-mode target picker (L6 Slice I). Returns the
     * {@code (serverId, regionKey)} of the peer that currently holds the
     * largest {@code regionKeptCounts} entry across the snapshot, breaking
     * ties by lexicographic {@code serverId} then {@code regionKey} for
     * determinism. Returns {@link java.util.Optional#empty()} when no peer
     * advertises any region (snapshot null/empty, all peers killSwitch'd,
     * all peers excluded as self).
     *
     * <p>v1 policy is intentionally fixed to "most kept" - the lobby
     * dispatch decision uses the same signal the {@code BackendSelector}
     * already biases on. A configurable weighted-average policy is the
     * documented next step (per Slice I sign-off "first default will be
     * most-kept with no config but this will be replaced with a more
     * complex version of weighted average per docs").</p>
     *
     * <p>Pre-L6 peers that don't ship {@code regionKeptCounts} are scored
     * as {@code 0} per region; they're still considered if no L6 peer
     * exists, so a mixed-version network degrades gracefully rather than
     * rejecting the lobby request.</p>
     */
    public java.util.Optional<ServerRegion> pickMostKept() {
        NetworkSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (Throwable ignored) {
            return java.util.Optional.empty();
        }
        if (snap == null) return java.util.Optional.empty();

        String bestServer = null;
        String bestRegion = null;
        int bestCount = -1;
        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null) continue;
            if (hb.killSwitch()) continue;
            if (hb.serverId() == null || hb.serverId().isEmpty()) continue;
            if (localServerId != null && hb.serverId().equals(localServerId)) continue;
            // L6 Slice I follow-up: peers that are not currently accepting
            // requests (warming caches, draining, pluginState != READY) MUST
            // be excluded. The proxy-side WeightedAverageBackendSelector
            // already does this; the lobby-side picker is the analog. Without
            // this filter, an unready peer with regionKeptCounts={} (which is
            // a default 0) ties against every other peer at 0 and the lex
            // tiebreak forces the lexicographically-smallest serverId
            // (commonly "backend-a") to win forever even when it is unready
            // and a ready "backend-b" exists.
            if (!hb.acceptingRequests()) continue;

            Set<String> regions = hb.regions();
            if (regions == null || regions.isEmpty()) {
                if (hb.regionsAvailable() != null) {
                    regions = new HashSet<>(hb.regionsAvailable());
                } else {
                    continue;
                }
            }
            for (String region : regions) {
                if (region == null || region.isEmpty()) continue;
                int count = 0;
                Integer mapped = (hb.regionKeptCounts() == null)
                        ? null : hb.regionKeptCounts().get(region);
                if (mapped != null) count = mapped;
                // Apply optimistic local decrement (recordDispatch). If the
                // peer has published a strictly newer heartbeat since the
                // decrement was recorded, evict it - ground truth wins.
                ServerRegion srKey = new ServerRegion(hb.serverId(), region);
                Decrement d = localDecrements.get(srKey);
                if (d != null) {
                    if (hb.lastSeenEpochMs() > d.observedAtMs) {
                        localDecrements.remove(srKey, d);
                    } else {
                        count = Math.max(0, count - d.count);
                    }
                }
                boolean win = (count > bestCount)
                        || (count == bestCount
                            && (bestServer == null
                                || hb.serverId().compareTo(bestServer) < 0
                                || (hb.serverId().equals(bestServer)
                                    && (bestRegion == null
                                        || region.compareTo(bestRegion) < 0))));
                if (win) {
                    bestServer = hb.serverId();
                    bestRegion = region;
                    bestCount = count;
                }
            }
        }
        if (bestServer == null || bestRegion == null) return java.util.Optional.empty();
        return java.util.Optional.of(new ServerRegion(bestServer, bestRegion));
    }

    /**
     * Simple value tuple returned by {@link #pickMostKept()}. Not a record
     * elsewhere because no other call site needs it; lives here so the
     * surface stays scoped to the registry.
     */
    public record ServerRegion(String serverId, String regionKey) {
        public ServerRegion {
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(regionKey, "regionKey");
        }
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
