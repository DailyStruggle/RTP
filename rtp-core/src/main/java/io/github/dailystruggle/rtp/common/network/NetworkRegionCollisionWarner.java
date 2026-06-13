package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * On post-first-heartbeat the bootstrap scans
 * the snapshot for region-name overlap across backends and logs one WARN
 * per overlap. {@code network.regionCollisionPolicy: warn} is the only
 * value; the {@code rename-local} and {@code reject-startup}
 * policies are deferred follow-ups (see Out-of-Scope in the checklist).
 */
public final class NetworkRegionCollisionWarner {

    /** The collision-policy enum. Currently only {@code WARN} is supported. */
    public enum Policy {
        WARN;

        public static Policy parse(String raw) {
            // Forwards-compatible default: anything unrecognised maps to WARN
            // rather than throwing, so a future operator's "rename-local"
            // entry logs warnings instead of failing boot.
            return WARN;
        }
    }

    /** Pure-function audit; no side effects. Returns one row per collided region. */
    public static Map<String, Set<String>> findCollisions(NetworkSnapshot snap, String localServerId) {
        Map<String, Set<String>> regionToServers = new TreeMap<>();
        if (snap == null) return regionToServers;
        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null || hb.serverId() == null) continue;
            // Prefer the typed regions set; fall back to legacy regionsAvailable list.
            Iterable<String> regions = hb.regions() != null && !hb.regions().isEmpty()
                    ? hb.regions()
                    : (hb.regionsAvailable() != null ? hb.regionsAvailable() : List.of());
            for (String region : regions) {
                if (region == null || region.isEmpty()) continue;
                regionToServers.computeIfAbsent(region, k -> new HashSet<>()).add(hb.serverId());
            }
        }
        // Drop non-collisions (regions hosted by exactly one server).
        regionToServers.entrySet().removeIf(e -> e.getValue().size() <= 1);
        // Note localServerId for log-time formatting; not used in the
        // collision math itself but kept on the signature so callers can
        // pipe it directly without a wrapper.
        Objects.toString(localServerId, "");
        return regionToServers;
    }

    /** Side-effecting audit: log one WARN per collision. */
    public static void auditAndWarn(NetworkSnapshot snap, String localServerId, Policy policy) {
        if (policy == null) policy = Policy.WARN; // forwards-compatible
        Map<String, Set<String>> collisions = findCollisions(snap, localServerId);
        if (collisions.isEmpty()) return;
        for (Map.Entry<String, Set<String>> e : collisions.entrySet()) {
            // Sorted for stable log output and test golden files.
            String servers = new java.util.TreeSet<>(e.getValue()).toString();
            RTP.log(Level.WARNING,
                    String.format(Locale.ROOT,
                            "[NETWORK] region collision: region '%s' is advertised by %d backends %s. "
                                    + "policy=%s: serving locally first when this backend is one of them.",
                            e.getKey(), e.getValue().size(), servers, policy));
        }
    }
}
