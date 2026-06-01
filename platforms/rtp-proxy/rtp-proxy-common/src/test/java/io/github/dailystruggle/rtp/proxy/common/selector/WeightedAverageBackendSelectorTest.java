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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises rtp-proxy-ADR-004 §Scoring Formula and §Candidate Filtering on the
 * reference {@link WeightedAverageBackendSelector}. Covers
 * REQ-RTP-PROXY-COMMON-002 (pure-function selector) and
 * REQ-RTP-PROXY-COMMON-003 (snapshot freshness filter).
 */
class WeightedAverageBackendSelectorTest {

    private static final long NOW = 1_700_000_000_000L;

    private static BackendHeartbeat backend(String id, double mspt, int qd, int softCap, long lastSeen) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, lastSeen,
                mspt, qd, softCap, 0L, 0L, 0,
                List.of(), List.of());
    }

    private static RtpRequest req() {
        return new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND,
                Optional.empty(), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        Map<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(NOW, m);
    }

    @Test
    @DisplayName("Lowest-score backend wins (REQ-RTP-PROXY-COMMON-002)")
    void picksLowestScore() {
        BackendHeartbeat a = backend("a", 10, 5, 20, NOW); // light
        BackendHeartbeat b = backend("b", 40, 18, 20, NOW); // heavy
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("a"), sel.choose(req(), snap(a, b)));
    }

    @Test
    @DisplayName("Ties broken by serverId ascending (ADR-004)")
    void tiesBrokenByServerIdAsc() {
        BackendHeartbeat z = backend("z", 10, 5, 20, NOW);
        BackendHeartbeat a = backend("a", 10, 5, 20, NOW);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("a"), sel.choose(req(), snap(z, a)));
    }

    @Test
    @DisplayName("Stale backends excluded (REQ-RTP-PROXY-COMMON-003)")
    void staleFilter() {
        // staleAfterMs default 30_000; rowB is 60s old → excluded.
        BackendHeartbeat fresh = backend("fresh", 30, 10, 20, NOW);
        BackendHeartbeat stale = backend("stale", 1, 0, 20, NOW - 60_000);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("fresh"), sel.choose(req(), snap(fresh, stale)));
    }

    @Test
    @DisplayName("Non-READY backends excluded")
    void notReadyExcluded() {
        BackendHeartbeat ready = backend("a", 30, 10, 20, NOW);
        BackendHeartbeat starting = new BackendHeartbeat(
                "b", 1, PluginState.STARTING, true, NOW,
                1, 0, 20, 0, 0, 0, List.of(), List.of());
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertEquals(Optional.of("a"), sel.choose(req(), snap(ready, starting)));
    }

    @Test
    @DisplayName("All-stale snapshot returns empty (no candidate qualified)")
    void allStaleReturnsEmpty() {
        BackendHeartbeat s1 = backend("a", 1, 0, 20, NOW - 60_000);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        assertTrue(sel.choose(req(), snap(s1)).isEmpty());
    }

    @Test
    @DisplayName("choose() is pure: repeated calls yield identical results")
    void purity() {
        BackendHeartbeat a = backend("a", 10, 5, 20, NOW);
        BackendHeartbeat b = backend("b", 40, 18, 20, NOW);
        var sel = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
        var snap = snap(a, b);
        var req = req();
        var first = sel.choose(req, snap);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, sel.choose(req, snap));
        }
    }
}
