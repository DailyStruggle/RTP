package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curves;
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
 * Covers the unified pair-wise {@link RegionAwareSelector} used by lobby
 * (`PeerRegionRegistry.pickMostKept`) and proxy (no-region fallback) call
 * sites alike (rtp-proxy-ADR-004 section Region-Pair Scoring).
 */
class RegionAwareSelectorTest {

    private static final long NOW = 1_700_000_000_000L;

    private static BackendHeartbeat hb(String id,
                                       Set<String> regions,
                                       Map<String, Integer> regionKept,
                                       int keptCount,
                                       double mspt,
                                       long lastSeen) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, lastSeen,
                mspt, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                false, keptCount, 0, regions, regionKept);
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(NOW, m);
    }

    private static RtpRequest req() {
        return new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND,
                Optional.empty(), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    private static RtpRequest reqRegion(String regionKey) {
        return new RtpRequest(UUID.randomUUID(), TriggerType.COMMAND,
                Optional.of(regionKey), Optional.empty(), Optional.empty(),
                UUID.randomUUID());
    }

    @Test
    @DisplayName("Defaults pick the region with the warmest pool")
    void defaultsPickWarmestRegion() {
        // backend-a has 50 ready in default; backend-b has 5 ready in east.
        // With exponential(k=5) scarcity, backend-a's 50/64 (~22% empty)
        // scores far below backend-b's 5/64 (~92% empty).
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 50), 50, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("east"),
                Map.of("east", 5), 5, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        Optional<ServerRegion> pick = sel.choose(snap(a, b), req(), null);
        assertTrue(pick.isPresent());
        assertEquals("backend-a", pick.get().serverId());
        assertEquals("default", pick.get().regionKey());
    }

    @Test
    @DisplayName("Empty kept pool yields strongest penalty (exponential default)")
    void emptyPoolHasStrongestPenalty() {
        // backend-a: empty kept pool. backend-b: 32/64 = 50%.
        // exponential(k=5): scarcity 1.0 -> ~1.0; scarcity 0.5 -> ~0.07.
        // backend-b must win comfortably.
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 0), 0, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("default"),
                Map.of("default", 32), 32, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        Optional<ServerRegion> pick = sel.choose(snap(a, b), req(), null);
        assertEquals(Optional.of(new ServerRegion("backend-b", "default")), pick);
    }

    @Test
    @DisplayName("Pinned regionKey collapses candidate set to that region only")
    void pinnedRegionKeyCollapses() {
        BackendHeartbeat a = hb("backend-a", Set.of("default", "east"),
                Map.of("default", 50, "east", 1), 51, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("east"),
                Map.of("east", 50), 50, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        // Request region=east. backend-a:east has only 1 ready, backend-b:east has 50.
        Optional<ServerRegion> pick = sel.choose(snap(a, b), reqRegion("east"), null);
        assertTrue(pick.isPresent());
        assertEquals("backend-b", pick.get().serverId());
        assertEquals("east", pick.get().regionKey());
    }

    @Test
    @DisplayName("Excluded server (lobby self) is dropped")
    void excludedServerIsDropped() {
        BackendHeartbeat self = hb("lobby-1", Set.of("default"),
                Map.of("default", 99), 99, 5.0, NOW);
        BackendHeartbeat peer = hb("backend-a", Set.of("default"),
                Map.of("default", 1), 1, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        Optional<ServerRegion> pick = sel.choose(snap(self, peer), req(), "lobby-1");
        assertTrue(pick.isPresent());
        assertEquals("backend-a", pick.get().serverId());
    }

    @Test
    @DisplayName("killSwitch / non-READY / not-accepting peers filtered")
    void filteringFlags() {
        BackendHeartbeat killed = new BackendHeartbeat(
                "backend-a", 1, PluginState.READY, true, NOW,
                5.0, 0, 100, 0L, 1L, 0, List.of(), List.of(),
                true, 99, 0, Set.of("default"), Map.of("default", 99));
        BackendHeartbeat starting = new BackendHeartbeat(
                "backend-b", 1, PluginState.STARTING, true, NOW,
                5.0, 0, 100, 0L, 1L, 0, List.of(), List.of(),
                false, 99, 0, Set.of("default"), Map.of("default", 99));
        BackendHeartbeat draining = new BackendHeartbeat(
                "backend-c", 1, PluginState.READY, false, NOW,
                5.0, 0, 100, 0L, 1L, 0, List.of(), List.of(),
                false, 99, 0, Set.of("default"), Map.of("default", 99));
        BackendHeartbeat ok = hb("backend-z", Set.of("default"),
                Map.of("default", 1), 1, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        Optional<ServerRegion> pick = sel.choose(snap(killed, starting, draining, ok), req(), null);
        assertEquals(Optional.of(new ServerRegion("backend-z", "default")), pick);
    }

    @Test
    @DisplayName("Stale heartbeats excluded by staleAfterMs")
    void staleExcluded() {
        BackendHeartbeat stale = hb("backend-a", Set.of("default"),
                Map.of("default", 99), 99, 5.0, NOW - 60_000L);
        BackendHeartbeat fresh = hb("backend-b", Set.of("default"),
                Map.of("default", 1), 1, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        Optional<ServerRegion> pick = sel.choose(snap(stale, fresh), req(), null);
        assertEquals(Optional.of(new ServerRegion("backend-b", "default")), pick);
    }

    @Test
    @DisplayName("MSPT term can override region scarcity when weight is large enough")
    void msptOverridesScarcity() {
        // backend-a is hot but has a warm pool; backend-b has empty pool but
        // is healthy. With high MSPT weight + exponential curve, backend-b
        // should win because heavy-MSPT backend-a is penalised hard.
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 64), 64, 49.0, NOW); // pegged near 50ms
        BackendHeartbeat b = hb("backend-b", Set.of("default"),
                Map.of("default", 64), 64, 5.0, NOW);
        LoadBalancerConfig cfg = new LoadBalancerConfig(
                30_000L, 0.0, 0.0, 0.0, 0.0, Map.of(),
                List.of(new ScoringTerm(MetricInput.MSPT, 10.0, Curves.exponential(8.0))),
                1.0);
        var sel = new RegionAwareSelector(cfg);
        Optional<ServerRegion> pick = sel.choose(snap(a, b), req(), null);
        assertEquals("backend-b", pick.orElseThrow().serverId());
    }

    @Test
    @DisplayName("Explicit KEPT_REGION term replaces synthesized default")
    void explicitKeptRegionTermWins() {
        // Author a linear KEPT_REGION term with weight 1.0. The synthesized
        // exponential one is suppressed. With both peers at 32/64, linear
        // gives both a 0.5 contribution -> tie -> serverIdAsc wins.
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 32), 32, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("default"),
                Map.of("default", 32), 32, 5.0, NOW);
        LoadBalancerConfig cfg = new LoadBalancerConfig(
                30_000L, 0.0, 0.0, 0.0, 0.0, Map.of(),
                List.of(new ScoringTerm(MetricInput.KEPT_REGION, 1.0, Curves.linear())),
                1.0);
        var sel = new RegionAwareSelector(cfg);
        // Explicit KEPT_REGION term suppresses the synthesized one even
        // though regionScarcityWeight is non-zero (see
        // LoadBalancerConfig.regionScarcityTerm).
        org.junit.jupiter.api.Assertions.assertNull(cfg.regionScarcityTerm());
        assertEquals("backend-a", sel.choose(snap(a, b), req(), null).orElseThrow().serverId());
    }

    @Test
    @DisplayName("regionScarcityWeight=0 disables implicit scarcity bias")
    void regionScarcityWeightZeroDisablesBias() {
        // With scarcity disabled and no other terms, every candidate scores
        // 0.0 -> tie-break by serverId asc + regionKey asc.
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 1), 1, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("default"),
                Map.of("default", 99), 99, 5.0, NOW);
        LoadBalancerConfig cfg = new LoadBalancerConfig(
                30_000L, 0.0, 0.0, 0.0, 0.0, Map.of(),
                List.of(),
                0.0);
        var sel = new RegionAwareSelector(cfg);
        Optional<ServerRegion> pick = sel.choose(snap(a, b), req(), null);
        assertEquals("backend-a", pick.orElseThrow().serverId(),
                "with no scarcity bias and ties on every term, lex tiebreak wins");
    }

    @Test
    @DisplayName("Multi-region backend: every (server, region) pair is scored independently")
    void multiRegionPairWiseScoring() {
        // backend-a hosts default (warm) and east (empty). Both pairs are
        // scored; default wins inside backend-a, and that's the global pick
        // when no other backend exists.
        BackendHeartbeat a = hb("backend-a", Set.of("default", "east"),
                Map.of("default", 64, "east", 0), 64, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        var scored = sel.scoreAll(snap(a), req(), null, null);
        assertEquals(2, scored.size());
        Optional<ServerRegion> pick = sel.choose(snap(a), req(), null);
        assertEquals("default", pick.orElseThrow().regionKey());
    }

    @Test
    @DisplayName("backendWeight divides the score (higher weight -> preferred)")
    void backendWeightAppliesAsDivisor() {
        // Both peers identical; backendWeights pins backend-b -> 2.0. The
        // divisor halves backend-b's score so it wins despite the lex
        // tiebreak that would otherwise pick backend-a.
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 10), 10, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("default"),
                Map.of("default", 10), 10, 5.0, NOW);
        LoadBalancerConfig cfg = new LoadBalancerConfig(
                30_000L, 0.0, 0.0, 0.0, 0.0,
                Map.of("backend-b", 2.0),
                List.of(),
                1.0);
        var sel = new RegionAwareSelector(cfg);
        assertEquals("backend-b", sel.choose(snap(a, b), req(), null).orElseThrow().serverId());
    }

    @Test
    @DisplayName("PostScoreAdjust hook nudges scores (lobby localDecrements analog)")
    void postScoreAdjustHookApplied() {
        BackendHeartbeat a = hb("backend-a", Set.of("default"),
                Map.of("default", 64), 64, 5.0, NOW);
        BackendHeartbeat b = hb("backend-b", Set.of("default"),
                Map.of("default", 60), 60, 5.0, NOW);
        var sel = new RegionAwareSelector(LoadBalancerConfig.defaults());
        // Without the hook, backend-a (warmer pool) wins.
        assertEquals("backend-a", sel.choose(snap(a, b), req(), null).orElseThrow().serverId());
        // With a hook penalising backend-a, backend-b should win.
        RegionAwareSelector.PostScoreAdjust nudge = (sr, hb, raw) ->
                "backend-a".equals(sr.serverId()) ? raw + 10.0 : raw;
        assertEquals("backend-b",
                sel.choose(snap(a, b), req(), null, nudge).orElseThrow().serverId());
    }
}
