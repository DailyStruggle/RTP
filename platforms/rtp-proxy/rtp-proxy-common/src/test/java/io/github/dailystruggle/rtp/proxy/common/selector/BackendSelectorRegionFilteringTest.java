package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice B (L6) row B4: verifies the L6 region predicate added to
 * {@link WeightedAverageBackendSelector#choose}:
 * <ul>
 *   <li>filter peers whose {@code regions} set does not contain the requested
 *       {@code regionKey};</li>
 *   <li>require {@code regionKeptCounts.getOrDefault(regionKey, 0) > 0} when
 *       the map is populated;</li>
 *   <li>exclude killSwitched backends;</li>
 *   <li>fall through to legacy {@code regionsAvailable} list when {@code regions}
 *       is empty (pre-L6 peer back-compat).</li>
 * </ul>
 */
class BackendSelectorRegionFilteringTest {

    private static final long NOW = 1_700_000_000_000L;

    private static BackendHeartbeat l6(String id,
                                       double mspt,
                                       int qd,
                                       int softCap,
                                       Set<String> regions,
                                       Map<String, Integer> regionKeptCounts,
                                       int keptCount,
                                       boolean killSwitch) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, NOW,
                mspt, qd, softCap, 0L, 0L, 0,
                List.of(), List.of(), killSwitch,
                keptCount, 0, regions, regionKeptCounts);
    }

    private static RtpRequest req(Optional<String> regionKey) {
        return new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND,
                regionKey, Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        Map<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(NOW, m);
    }

    @Test
    @DisplayName("regionKey present: only backends whose regions set contains the key qualify")
    void regionFilterPositive() {
        BackendHeartbeat hostsDefault = l6("a", 10, 0, 20,
                Set.of("default"), Map.of("default", 5), 5, false);
        BackendHeartbeat hostsNether = l6("b", 1, 0, 20,
                Set.of("nether"), Map.of("nether", 5), 5, false);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        // "b" scores lower (lighter mspt), but only "a" hosts default.
        assertEquals(Optional.of("a"),
                sel.choose(req(Optional.of("default")), snap(hostsDefault, hostsNether)));
    }

    @Test
    @DisplayName("regionKeptCounts == 0 for the requested key excludes the backend")
    void regionKeptCountZeroExcludes() {
        BackendHeartbeat warm = l6("a", 30, 10, 20,
                Set.of("default"), Map.of("default", 3), 3, false);
        BackendHeartbeat empty = l6("b", 1, 0, 20,
                Set.of("default"), Map.of("default", 0), 0, false);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        // "b" scores lower but has zero kept; "a" wins.
        assertEquals(Optional.of("a"),
                sel.choose(req(Optional.of("default")), snap(warm, empty)));
    }

    @Test
    @DisplayName("killSwitch == true excludes the backend regardless of region")
    void killSwitchExcludes() {
        BackendHeartbeat killed = l6("a", 1, 0, 20,
                Set.of("default"), Map.of("default", 99), 99, true);
        BackendHeartbeat alive = l6("b", 30, 10, 20,
                Set.of("default"), Map.of("default", 1), 1, false);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("b"),
                sel.choose(req(Optional.of("default")), snap(killed, alive)));
    }

    @Test
    @DisplayName("Pre-L6 peer (empty regions Set) falls back to regionsAvailable list")
    void preL6FallbackToRegionsAvailable() {
        // Empty regions set + populated regionsAvailable list = pre-L6 peer.
        BackendHeartbeat preL6 = new BackendHeartbeat(
                "a", 1, PluginState.READY, true, NOW,
                10.0, 0, 20, 0L, 0L, 0,
                List.of("default"), List.of(), false,
                0, 0, Set.of(), Map.of());
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("a"),
                sel.choose(req(Optional.of("default")), snap(preL6)));
        // Region not present in the legacy list -> excluded.
        assertTrue(sel.choose(req(Optional.of("nether")), snap(preL6)).isEmpty());
    }

    @Test
    @DisplayName("No regionKey requested: candidates are not region-filtered")
    void noRegionKeyNoFilter() {
        BackendHeartbeat a = l6("a", 10, 0, 20,
                Set.of("default"), Map.of("default", 5), 5, false);
        BackendHeartbeat b = l6("b", 30, 10, 20,
                Set.of("nether"), Map.of("nether", 5), 5, false);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("a"),
                sel.choose(req(Optional.empty()), snap(a, b)));
    }

    @Test
    @DisplayName("All peers fail the region predicate -> empty result")
    void noRegionMatchReturnsEmpty() {
        BackendHeartbeat a = l6("a", 10, 0, 20,
                Set.of("default"), Map.of("default", 5), 5, false);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertTrue(sel.choose(req(Optional.of("end")), snap(a)).isEmpty());
    }
}
