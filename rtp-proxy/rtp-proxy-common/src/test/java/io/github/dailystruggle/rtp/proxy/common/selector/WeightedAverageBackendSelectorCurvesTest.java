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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link ScoringTerm}'s curve is actually wired through
 * {@link WeightedAverageBackendSelector#score} (rtp-proxy-ADR-004). Two
 * heartbeats with different load profiles are scored under two curve configs;
 * the winner shall flip when the curve changes, otherwise the curves would be
 * dead code.
 */
class WeightedAverageBackendSelectorCurvesTest {

    private static final long NOW = 1_700_000_000_000L;

    private static BackendHeartbeat hb(String id, double mspt, int qd, int softCap,
                                       long heapUsed, long heapMax, int keptCount) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, NOW,
                mspt, qd, softCap, heapUsed, heapMax, 0,
                List.of(), List.of(), false, keptCount, 0,
                java.util.Set.of(), java.util.Map.of());
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
    @DisplayName("step(0.6) on mspt: backend below threshold scores 0 and wins regardless of queue")
    void stepCurveChangesWinner() {
        // a: mspt 25ms (norm 0.5, below step 0.6 -> 0) but heavy queue.
        // b: mspt 35ms (norm 0.7, above step 0.6 -> 1)  and light queue.
        BackendHeartbeat a = hb("a", 25.0, 18, 20, 0L, 0L, 0);
        BackendHeartbeat b = hb("b", 35.0,  2, 20, 0L, 0L, 0);

        // Linear baseline: 'b' wins (lower mspt+queue total under linear).
        LoadBalancerConfig linear = new LoadBalancerConfig(
                30_000L, 1.0, 1.0, 0.0, 0.0, Map.of(),
                List.of(
                        new ScoringTerm(MetricInput.MSPT, 1.0, Curves.linear()),
                        new ScoringTerm(MetricInput.QUEUE_DEPTH, 1.0, Curves.linear())));
        // Expected linear:
        //   a: 0.5 + 18/20 = 1.4
        //   b: 0.7 +  2/20 = 0.8 -> 'b' wins.
        var linearSel = new WeightedAverageBackendSelector(linear);
        assertEquals(Optional.of("b"), linearSel.choose(req(), snap(a, b)));

        // Step curve on mspt with threshold 0.6:
        //   a: step(0.5) = 0 ; queue = 0.9 ; total 0.9
        //   b: step(0.7) = 1 ; queue = 0.1 ; total 1.1 -> 'a' wins.
        LoadBalancerConfig stepCfg = new LoadBalancerConfig(
                30_000L, 0.0, 0.0, 0.0, 0.0, Map.of(),
                List.of(
                        new ScoringTerm(MetricInput.MSPT, 1.0, Curves.step(0.6)),
                        new ScoringTerm(MetricInput.QUEUE_DEPTH, 1.0, Curves.linear())));
        var stepSel = new WeightedAverageBackendSelector(stepCfg);
        assertEquals(Optional.of("a"), stepSel.choose(req(), snap(a, b)));
    }

    @Test
    @DisplayName("Exponential vs logarithmic on the same input produce different scores")
    void curveActuallyShapesScore() {
        BackendHeartbeat a = hb("a", 25.0, 0, 20, 0L, 0L, 0); // mspt norm = 0.5

        var expSel = new WeightedAverageBackendSelector(new LoadBalancerConfig(
                30_000L, 0, 0, 0, 0, Map.of(),
                List.of(new ScoringTerm(MetricInput.MSPT, 1.0, Curves.exponential(3.0)))));
        var logSel = new WeightedAverageBackendSelector(new LoadBalancerConfig(
                30_000L, 0, 0, 0, 0, Map.of(),
                List.of(new ScoringTerm(MetricInput.MSPT, 1.0, Curves.logarithmic(9.0)))));

        double expScore = expSel.score(a);
        double logScore = logSel.score(a);
        assertNotEquals(expScore, logScore, 1e-6, "exponential and logarithmic must produce different scores at x=0.5");
        assertTrue(expScore < 0.5, "exponential is convex (f(0.5)<0.5)");
        assertTrue(logScore > 0.5, "logarithmic is concave (f(0.5)>0.5)");
    }

    @Test
    @DisplayName("keptCount input: warm backend beats cold backend")
    void keptCountFavorsWarmBackend() {
        // Same mspt; only keptCount differs. KEPT_COUNT input is inverted
        // (empty pool = worst).
        BackendHeartbeat warm = hb("warm", 25.0, 5, 20, 0L, 0L, 64); // norm -> 0
        BackendHeartbeat cold = hb("cold", 25.0, 5, 20, 0L, 0L, 0);  // norm -> 1

        var sel = new WeightedAverageBackendSelector(new LoadBalancerConfig(
                30_000L, 0, 0, 0, 0, Map.of(),
                List.of(new ScoringTerm(MetricInput.KEPT_COUNT, 1.0, Curves.linear()))));
        assertEquals(Optional.of("warm"), sel.choose(req(), snap(warm, cold)));
    }

    @Test
    @DisplayName("TPS input: derived from mspt; 20 TPS backend beats 10 TPS backend")
    void tpsInputDerivedFromMspt() {
        BackendHeartbeat fast = hb("fast", 50.0,  5, 20, 0L, 0L, 0);   // 20 tps
        BackendHeartbeat slow = hb("slow", 100.0, 5, 20, 0L, 0L, 0);   // 10 tps

        var sel = new WeightedAverageBackendSelector(new LoadBalancerConfig(
                30_000L, 0, 0, 0, 0, Map.of(),
                List.of(new ScoringTerm(MetricInput.TPS, 1.0, Curves.linear()))));
        assertEquals(Optional.of("fast"), sel.choose(req(), snap(fast, slow)));
    }

    @Test
    @DisplayName("Empty terms list falls back to legacy linear weights (back-compat)")
    void emptyTermsFallsBackToLegacy() {
        BackendHeartbeat a = hb("a", 10.0, 5, 20, 0L, 0L, 0);
        BackendHeartbeat b = hb("b", 40.0, 18, 20, 0L, 0L, 0);
        // Explicit empty terms; legacy weights drive selection.
        var sel = new WeightedAverageBackendSelector(new LoadBalancerConfig(
                30_000L, 1.0, 1.0, 0.5, 0.0, Map.of(), List.of()));
        assertEquals(Optional.of("a"), sel.choose(req(), snap(a, b)));
    }
}
