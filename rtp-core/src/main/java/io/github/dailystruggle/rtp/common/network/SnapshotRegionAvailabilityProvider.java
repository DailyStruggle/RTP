package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link RegionAvailabilityProvider} backed by the live {@link NetworkSnapshot}
 * the transport layer maintains (the same cached snapshot {@link PeerRegionRegistry}
 * reads). This single implementation serves all three tiers transparently: the
 * snapshot is filled by the plugin-message gossip (lite + Pro) or by the SQL /
 * Redis bindings (Pro), and this class does not care which.
 *
 * <p>A server is "known" iff it currently appears in the snapshot with a
 * non-{@code killSwitch} heartbeat. Region availability for a known server is
 * the union of its typed {@link BackendHeartbeat#regions()} set (preferred) and
 * the legacy {@link BackendHeartbeat#regionsAvailable()} list. An absent /
 * stale server yields {@link Availability#UNKNOWN}, which the parameter accepts
 * (the load-bearing rule).</p>
 */
public final class SnapshotRegionAvailabilityProvider implements RegionAvailabilityProvider {

    private final Supplier<NetworkSnapshot> snapshotSupplier;

    public SnapshotRegionAvailabilityProvider(Supplier<NetworkSnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier == null ? () -> null : snapshotSupplier;
    }

    private NetworkSnapshot snapshot() {
        try {
            return snapshotSupplier.get();
        } catch (Throwable ignored) {
            // Defensive: a flaky transport must never crash the command path.
            return null;
        }
    }

    private static Set<String> regionsOf(BackendHeartbeat hb) {
        Set<String> out = new HashSet<>();
        if (hb == null) return out;
        Set<String> typed = hb.regions();
        if (typed != null && !typed.isEmpty()) {
            for (String r : typed) {
                if (r != null && !r.isEmpty()) out.add(r);
            }
            return out;
        }
        if (hb.regionsAvailable() != null) {
            for (String r : hb.regionsAvailable()) {
                if (r != null && !r.isEmpty()) out.add(r);
            }
        }
        return out;
    }

    @Override
    public Set<String> availableEntries() {
        NetworkSnapshot snap = snapshot();
        if (snap == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null || hb.killSwitch()) continue;
            String sid = hb.serverId();
            if (sid == null || sid.isEmpty()) continue;
            for (String r : regionsOf(hb)) {
                out.add(sid + ":" + r);
            }
        }
        return out;
    }

    @Override
    public Availability availabilityOf(String serverId, String regionKey) {
        if (regionKey == null || regionKey.isEmpty()) return Availability.UNKNOWN;
        NetworkSnapshot snap = snapshot();
        if (snap == null) return Availability.UNKNOWN;

        // "Any server": KNOWN_AVAILABLE if at least one known server advertises
        // the region, else UNKNOWN (never KNOWN_UNAVAILABLE - we cannot prove a
        // negative across servers we may not have heard from yet).
        if (serverId == null || serverId.isEmpty()) {
            for (BackendHeartbeat hb : snap.all()) {
                if (hb == null || hb.killSwitch()) continue;
                if (regionsOf(hb).contains(regionKey)) return Availability.KNOWN_AVAILABLE;
            }
            return Availability.UNKNOWN;
        }

        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null || hb.killSwitch()) continue;
            if (serverId.equals(hb.serverId())) {
                return regionsOf(hb).contains(regionKey)
                        ? Availability.KNOWN_AVAILABLE
                        : Availability.KNOWN_UNAVAILABLE;
            }
        }
        return Availability.UNKNOWN;
    }

    @Override
    public boolean isServerKnown(String serverId) {
        if (serverId == null || serverId.isEmpty()) return false;
        NetworkSnapshot snap = snapshot();
        if (snap == null) return false;
        for (BackendHeartbeat hb : snap.all()) {
            if (hb == null || hb.killSwitch()) continue;
            if (serverId.equals(hb.serverId())) return true;
        }
        return false;
    }
}
