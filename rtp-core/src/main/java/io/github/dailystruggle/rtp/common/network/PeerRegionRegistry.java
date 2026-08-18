package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfig;
import io.github.dailystruggle.rtp.proxy.common.selector.RegionAwareSelector;
import io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.HashSet;
import java.util.logging.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Snapshot-adapter exposing peer-advertised {@code server:region} entries
 * to the local command pipeline (ADR-036).
 *
 * <p>Reads from the cached {@link NetworkSnapshot} updated by the heartbeat subscriber.
 * Surfaces remote regions in parameter completion and routing without extra I/O.</p>
 */
public final class PeerRegionRegistry {

    private final Supplier<NetworkSnapshot> snapshotSupplier;
    private final String localServerId;
    private final RegionAwareSelector selector;

    /**
     * Optimistic local decrements applied to peer {@code keptCount} between heartbeat
     * refreshes. Dispatches temporarily lower target scores until a newer heartbeat arrives.
     */
    private final ConcurrentHashMap<ServerRegion, Decrement> localDecrements =
            new ConcurrentHashMap<>();

    /**
     * Topology-seeded peer source for DB-free plugin-message transport.
     * Surfaces proxy-advertised servers when heartbeat gossip is unavailable.
     */
    private volatile Supplier<Set<String>> topologyPeerSupplier = Set::of;

    /**
     * Region names assumed for a topology-seeded peer whose real region set
     * has not been observed via a heartbeat. Defaults to {@code {"default"}}
     * - the conventional region name. Used only to materialise concrete
     * {@code server:region} tab-completion entries; validation accepts any
     * region for a topology-known server (the destination decides).
     */
    private volatile Supplier<Set<String>> assumedRegionSupplier = () -> Set.of("default");

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
     * Local backend network id, or {@code null} if not running as a network backend.
     *
     * @return the local server id, or {@code null}
     */
    public String localServerId() {
        return localServerId;
    }

    /**
     * Read a single descriptive attribute a peer backend advertised for a region.
     *
     * @param serverId  the peer backend's network id
     * @param regionKey the region name on that backend
     * @param attribute the attribute name (e.g. {@code "env"}, {@code "block"})
     * @return the advertised attribute value, or {@code null} when absent
     */
    public String peerRegionAttribute(String serverId, String regionKey, String attribute) {
        if (serverId == null || serverId.isEmpty()) return null;
        if (regionKey == null || regionKey.isEmpty()) return null;
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            NetworkSnapshot snap = snapshotSupplier.get();
            if (snap == null) return null;
            BackendHeartbeat hb = snap.backend(serverId).orElse(null);
            if (hb == null) return null;
            return hb.regionMetadata().get(regionKey + "." + attribute);
        } catch (Throwable ignored) {
            // Defensive: flaky transport must not crash callers.
            return null;
        }
    }

    /**
     * Install the topology-peer supplier for plugin-message tier discovery.
     *
     * @param supplier supplier returning peer server IDs
     */
    public void setTopologyPeerSupplier(Supplier<Set<String>> supplier) {
        this.topologyPeerSupplier = supplier == null ? Set::of : supplier;
    }

    /**
     * Override the region names assumed for topology-seeded peers (defaults to {@code {"default"}}).
     */
    public void setAssumedRegionSupplier(Supplier<Set<String>> supplier) {
        this.assumedRegionSupplier = supplier == null ? () -> Set.of("default") : supplier;
    }

    /**
     * Defensive read of the topology-peer set: never throws or returns null.
     */
    private Set<String> topologyPeers() {
        try {
            Set<String> peers = topologyPeerSupplier.get();
            return peers == null ? Set.of() : peers;
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    /**
     * Record a cross-server dispatch to {@code (serverId, regionKey)}, temporarily
     * decrementing its score until the next newer heartbeat is received.
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
     *
     * @return non-null set of available peer entries
     */
    public Set<String> peerEntries() {
        NetworkSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (Throwable ignored) {
            // Defensive: a flaky transport must not crash autocomplete.
            snap = null;
        }
        Set<String> out = new HashSet<>();
        // Track which servers a heartbeat already described so the
        // topology-seeded fallback below does not duplicate (or override
        // with a coarser assumed-region set) a server we have real data for.
        Set<String> heartbeatServers = new HashSet<>();
        if (snap != null) {
            for (BackendHeartbeat hb : snap.all()) {
                if (hb == null) continue;
                if (hb.killSwitch()) continue;
                if (hb.serverId() == null || hb.serverId().isEmpty()) continue;
                // Self is intentionally NOT excluded: surfacing
                // {@code <self>:default} alongside the unqualified {@code default}
                // lets operators hard-pin to this backend (skip load balancing).
                // Prefer the typed regions Set; fall back to legacy list.
                Set<String> regions = hb.regions();
                if (regions != null && !regions.isEmpty()) {
                    heartbeatServers.add(hb.serverId());
                    for (String r : regions) {
                        if (r != null && !r.isEmpty()) out.add(hb.serverId() + ":" + r);
                    }
                    continue;
                }
                if (hb.regionsAvailable() != null && !hb.regionsAvailable().isEmpty()) {
                    heartbeatServers.add(hb.serverId());
                    for (String r : hb.regionsAvailable()) {
                        if (r != null && !r.isEmpty()) out.add(hb.serverId() + ":" + r);
                    }
                }
            }
        }
        // Topology-seeded fallback (Option 1): for every server the proxy
        // advertised that we have NOT yet observed a heartbeat from, surface
        // assumed {@code server:region} entries (default region) so the
        // command layer can suggest and accept cross-server destinations on
        // the plugin-message tier where heartbeat gossip cannot reach a
        // player-empty backend. Availability is UNKNOWN; the destination
        // backend decides on arrival.
        Set<String> assumed = assumedRegions();
        Set<String> topology = topologyPeers();
        for (String peer : topology) {
            if (peer == null || peer.isEmpty()) continue;
            if (heartbeatServers.contains(peer)) continue;
            for (String r : assumed) {
                if (r != null && !r.isEmpty()) out.add(peer + ":" + r);
            }
        }
        RTP.log(Level.FINE, "[RTP] peerEntries: snapshot="
                + (snap == null ? "null" : snap.all().size() + " backend(s)")
                + ", heartbeatServers=" + heartbeatServers
                + ", topologyPeers=" + topology
                + ", assumedRegions=" + assumed
                + " -> " + out.size() + " entry(ies): " + out);
        return out;
    }

    /**
     * Defensive read of the assumed-region set: never {@code null}/empty,
     * never throws (falls back to {@code {"default"}}).
     */
    private Set<String> assumedRegions() {
        try {
            Set<String> r = assumedRegionSupplier.get();
            if (r == null || r.isEmpty()) return Set.of("default");
            return r;
        } catch (Throwable ignored) {
            return Set.of("default");
        }
    }

    /**
     * Lobby-mode target picker delegating to {@link RegionAwareSelector} (ADR-036).
     *
     * @return chosen peer candidate, or empty if none available
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
     * Hard-pin reachability check for {@code serverId:regionKey}.
     *
     * @param serverId  target server id
     * @param regionKey target region key
     * @return true if destination can be targeted directly
     */
    public boolean isReachableHardPin(String serverId, String regionKey) {
        if (serverId == null || serverId.isEmpty()) return false;
        if (regionKey == null || regionKey.isEmpty()) return false;
        NetworkSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (Throwable ignored) {
            snap = null;
        }
        BackendHeartbeat hb = snap == null ? null : snap.backend(serverId).orElse(null);
        if (hb != null) {
            if (hb.killSwitch()) return false;
            if (hb.regions() != null && hb.regions().contains(regionKey)) return true;
            if (hb.regionsAvailable() != null && hb.regionsAvailable().contains(regionKey)) return true;
            // Heartbeat present with a concrete region set that does not
            // include regionKey: this is KNOWN_UNAVAILABLE, not unknown.
            boolean hasConcreteRegions =
                    (hb.regions() != null && !hb.regions().isEmpty())
                            || (hb.regionsAvailable() != null && !hb.regionsAvailable().isEmpty());
            if (hasConcreteRegions) return false;
            // Heartbeat with no region info: treat as unknown -> fall through
            // to the topology acceptance below.
        }
        // No (useful) heartbeat: accept if the proxy knows this server.
        return topologyPeers().contains(serverId);
    }
}
