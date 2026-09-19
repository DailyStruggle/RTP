package io.github.dailystruggle.rtp.proxy.common.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link LoadBalancerConfigYaml} parses both the new
 * sub-configuration {@code terms} block and the legacy flat-weight schema.
 * Errors on misconfiguration are surfaced rather than silently degrading
 * (rtp-proxy-ADR-004 section Configuration).
 */
class LoadBalancerConfigYamlTest {

    @Test
    @DisplayName("Empty / null map yields defaults")
    void empty() {
        assertEquals(LoadBalancerConfig.defaults().terms(),
                LoadBalancerConfigYaml.fromMap(null).terms());
        assertEquals(LoadBalancerConfig.defaults().terms(),
                LoadBalancerConfigYaml.fromMap(Map.of()).terms());
    }

    @Test
    @DisplayName("Legacy flat weights synthesize linear terms")
    void legacyFlatWeights() {
        Map<String, Object> lb = new LinkedHashMap<>();
        lb.put("staleAfterMs", 15_000);
        lb.put("msptWeight", 2.0);
        lb.put("queueDepthWeight", 1.0);
        lb.put("heapWeight", 0.0);
        lb.put("playerCountWeight", 0.5);
        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(lb);
        assertEquals(15_000L, cfg.staleAfterMs());
        // Three non-zero legacy weights -> three linear terms (heap=0 skipped).
        List<ScoringTerm> terms = cfg.terms();
        assertEquals(3, terms.size());
        assertEquals(MetricInput.MSPT, terms.get(0).input());
        assertEquals(2.0, terms.get(0).weight());
        assertEquals("linear", terms.get(0).curve().id());
    }

    @Test
    @DisplayName("New sub-config terms block: nested curve sub-map (no inline braces)")
    void termsSubConfigBlock() {
        Map<String, Object> sigmoidCurve = new LinkedHashMap<>();
        sigmoidCurve.put("type", "sigmoid");
        sigmoidCurve.put("k", 8.0);
        sigmoidCurve.put("x0", 0.5);
        Map<String, Object> msptTerm = new LinkedHashMap<>();
        msptTerm.put("input", "mspt");
        msptTerm.put("weight", 1.0);
        msptTerm.put("curve", sigmoidCurve);

        Map<String, Object> expCurve = new LinkedHashMap<>();
        expCurve.put("type", "exponential");
        expCurve.put("k", 3.0);
        Map<String, Object> queueTerm = new LinkedHashMap<>();
        queueTerm.put("input", "queueDepth");
        queueTerm.put("weight", 2.0);
        queueTerm.put("curve", expCurve);

        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("mspt", msptTerm);
        terms.put("queue", queueTerm);

        Map<String, Object> lb = new LinkedHashMap<>();
        lb.put("terms", terms);

        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(lb);
        assertEquals(2, cfg.terms().size());
        assertEquals(MetricInput.MSPT, cfg.terms().get(0).input());
        assertEquals("sigmoid", cfg.terms().get(0).curve().id());
        assertEquals(MetricInput.QUEUE_DEPTH, cfg.terms().get(1).input());
        assertEquals("exponential", cfg.terms().get(1).curve().id());
        assertEquals(2.0, cfg.terms().get(1).weight());
    }

    @Test
    @DisplayName("Label-as-input shorthand: omit explicit 'input' key")
    void labelImpliesInput() {
        Map<String, Object> tps = new LinkedHashMap<>();
        tps.put("weight", 1.0);
        // no 'input' key, no 'curve' key -> label "tps" supplies input, defaults to linear.
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("tps", tps);
        Map<String, Object> lb = new LinkedHashMap<>();
        lb.put("terms", terms);

        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(lb);
        assertEquals(1, cfg.terms().size());
        assertEquals(MetricInput.TPS, cfg.terms().get(0).input());
        assertEquals("linear", cfg.terms().get(0).curve().id());
    }

    @Test
    @DisplayName("curve: <string> shorthand resolves to default-parameter curve")
    void curveStringShorthand() {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("input", "mspt");
        term.put("weight", 1.0);
        term.put("curve", "logarithmic");
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("mspt", term);
        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(Map.of("terms", terms));
        assertEquals("logarithmic", cfg.terms().get(0).curve().id());
    }

    @Test
    @DisplayName("Backends map: scalar and nested-weight forms both accepted")
    void backendWeights() {
        Map<String, Object> backends = new LinkedHashMap<>();
        backends.put("backend-a", 1.5);
        backends.put("backend-b", Map.of("weight", 0.5));
        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(Map.of("backends", backends));
        assertEquals(1.5, cfg.backendWeight("backend-a"));
        assertEquals(0.5, cfg.backendWeight("backend-b"));
        assertEquals(1.0, cfg.backendWeight("unknown"));
    }

    @Test
    @DisplayName("Unknown curve type throws with descriptive message")
    void unknownCurveTypeThrows() {
        Map<String, Object> term = Map.of(
                "input", "mspt", "weight", 1.0,
                "curve", Map.of("type", "supercurve"));
        Map<String, Object> lb = Map.of("terms", Map.of("mspt", term));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LoadBalancerConfigYaml.fromMap(lb));
        assertTrue(ex.getMessage().contains("supercurve"));
    }

    @Test
    @DisplayName("Unknown input throws with descriptive message")
    void unknownInputThrows() {
        Map<String, Object> term = Map.of("input", "cpuLoad", "weight", 1.0);
        Map<String, Object> lb = Map.of("terms", Map.of("x", term));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LoadBalancerConfigYaml.fromMap(lb));
        assertTrue(ex.getMessage().contains("cpuLoad"));
    }
}
