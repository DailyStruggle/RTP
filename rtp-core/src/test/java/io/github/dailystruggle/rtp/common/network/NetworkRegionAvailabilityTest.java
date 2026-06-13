package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.commands.parameters.NetworkRegionParameter;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice E coverage (REQ-RTP-NET cross-server region parameter): the
 * snapshot-backed {@link RegionAvailabilityProvider} verdicts and the
 * {@link NetworkRegionParameter} suggest-vs-validate split, including the
 * load-bearing "unknown -&gt; accept" execute-time rule (proposal §6.2).
 */
class NetworkRegionAvailabilityTest {

    private static BackendHeartbeat backend(String serverId, Set<String> regions, boolean killSwitch) {
        return new BackendHeartbeat(
                serverId, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 1.0, 0, 100, 0L, 0L, 0,
                List.copyOf(regions), List.of(), killSwitch,
                0, 0, regions, Map.of());
    }

    private static NetworkSnapshot snapshotOf(BackendHeartbeat... rows) {
        java.util.LinkedHashMap<String, BackendHeartbeat> m = new java.util.LinkedHashMap<>();
        for (BackendHeartbeat hb : rows) m.put(hb.serverId(), hb);
        return new NetworkSnapshot(System.currentTimeMillis(), m);
    }

    private final UUID sender = UUID.randomUUID();

    @Test
    @DisplayName("provider: known server advertising the region -> KNOWN_AVAILABLE")
    void knownAvailable() {
        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default", "nether"), false)));
        SnapshotRegionAvailabilityProvider p = new SnapshotRegionAvailabilityProvider(snap::get);

        assertEquals(RegionAvailabilityProvider.Availability.KNOWN_AVAILABLE,
                p.availabilityOf("backend-a", "default"));
        assertTrue(p.isServerKnown("backend-a"));
        assertTrue(p.availableEntries().contains("backend-a:nether"));
    }

    @Test
    @DisplayName("provider: known server NOT advertising the region -> KNOWN_UNAVAILABLE")
    void knownUnavailable() {
        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default"), false)));
        SnapshotRegionAvailabilityProvider p = new SnapshotRegionAvailabilityProvider(snap::get);

        assertEquals(RegionAvailabilityProvider.Availability.KNOWN_UNAVAILABLE,
                p.availabilityOf("backend-a", "nether"));
    }

    @Test
    @DisplayName("provider: unknown server (or null snapshot) -> UNKNOWN")
    void unknownServer() {
        SnapshotRegionAvailabilityProvider nullSnap = new SnapshotRegionAvailabilityProvider(() -> null);
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN,
                nullSnap.availabilityOf("backend-z", "default"));
        assertFalse(nullSnap.isServerKnown("backend-z"));

        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default"), false)));
        SnapshotRegionAvailabilityProvider p = new SnapshotRegionAvailabilityProvider(snap::get);
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN,
                p.availabilityOf("backend-z", "default"));
    }

    @Test
    @DisplayName("provider: kill-switched backend is excluded from entries and verdicts")
    void killSwitchExcluded() {
        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default"), true)));
        SnapshotRegionAvailabilityProvider p = new SnapshotRegionAvailabilityProvider(snap::get);
        assertFalse(p.isServerKnown("backend-a"));
        assertTrue(p.availableEntries().isEmpty());
        assertEquals(RegionAvailabilityProvider.Availability.UNKNOWN,
                p.availabilityOf("backend-a", "default"));
    }

    @Test
    @DisplayName("parameter: known-available -> suggested AND validated")
    void parameterKnownAvailable() {
        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default"), false)));
        NetworkRegionParameter param = new NetworkRegionParameter(
                "", "", new SnapshotRegionAvailabilityProvider(snap::get));

        assertTrue(param.isRelevant.apply(sender, "backend-a:default"));
        assertTrue(param.relevantValues(sender).contains("backend-a:default"));
    }

    @Test
    @DisplayName("parameter: known-unavailable -> NOT suggested but STILL rejected on execute")
    void parameterKnownUnavailableRejected() {
        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default"), false)));
        NetworkRegionParameter param = new NetworkRegionParameter(
                "", "", new SnapshotRegionAvailabilityProvider(snap::get));

        assertFalse(param.isRelevant.apply(sender, "backend-a:nether"),
                "known-unavailable must reject at execute time");
        assertFalse(param.relevantValues(sender).contains("backend-a:nether"),
                "known-unavailable must not be suggested");
    }

    @Test
    @DisplayName("parameter: unknown server -> NOT suggested but ACCEPTED on execute (load-bearing rule)")
    void parameterUnknownAccepts() {
        AtomicReference<NetworkSnapshot> snap = new AtomicReference<>(
                snapshotOf(backend("backend-a", Set.of("default"), false)));
        NetworkRegionParameter param = new NetworkRegionParameter(
                "", "", new SnapshotRegionAvailabilityProvider(snap::get));

        // backend-z is unknown: validation must accept (destination decides),
        // but suggestion must not advertise it.
        assertTrue(param.isRelevant.apply(sender, "backend-z:default"),
                "unknown -> accept (transport lag must not become a rejection)");
        assertFalse(param.relevantValues(sender).contains("backend-z:default"));
    }

    @Test
    @DisplayName("parameter: empty snapshot accepts every server:region (pure unknown)")
    void parameterEmptySnapshotAccepts() {
        NetworkRegionParameter param = new NetworkRegionParameter(
                "", "", RegionAvailabilityProvider.UNKNOWN_ALL);
        assertTrue(param.isRelevant.apply(sender, "backend-a:default"));
        assertTrue(param.isRelevant.apply(sender, "anything:whatever"));
        assertFalse(param.isRelevant.apply(sender, "bad:"), "empty region key rejects");
    }
}
