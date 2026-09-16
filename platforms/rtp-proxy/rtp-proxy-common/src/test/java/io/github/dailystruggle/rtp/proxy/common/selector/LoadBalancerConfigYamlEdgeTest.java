package io.github.dailystruggle.rtp.proxy.common.selector;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadBalancerConfigYamlEdgeTest {

    @Test
    void fromMap_nullOrEmpty_returnsDefaults() {
        LoadBalancerConfig def1 = LoadBalancerConfigYaml.fromMap(null);
        LoadBalancerConfig def2 = LoadBalancerConfigYaml.fromMap(Collections.emptyMap());

        assertEquals(LoadBalancerConfig.defaults().staleAfterMs(), def1.staleAfterMs());
        assertEquals(LoadBalancerConfig.defaults().staleAfterMs(), def2.staleAfterMs());
    }

    @Test
    void fromMap_termsAsList() {
        Map<String, Object> map = Map.of(
                "terms", List.of(
                        Map.of("input", "mspt", "weight", 2.0, "curve", "linear"),
                        Map.of("input", "tps", "weight", 1.5, "curve", Map.of("type", "exponential", "k", 2.0))
                )
        );
        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(map);
        assertEquals(2, cfg.terms().size());
        assertEquals(MetricInput.MSPT, cfg.terms().get(0).input());
        assertEquals(2.0, cfg.terms().get(0).weight());
        assertEquals(MetricInput.TPS, cfg.terms().get(1).input());
        assertEquals(1.5, cfg.terms().get(1).weight());
    }

    @Test
    void fromMap_backendsNestedMapAndScalars() {
        Map<String, Object> map = Map.of(
                "backends", Map.of(
                        "b1", 1.5,
                        "b2", "2.5",
                        "b3", Map.of("weight", 3.0),
                        "b4", Map.of("weight", "invalid-double")
                )
        );
        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(map);
        assertEquals(1.5, cfg.backendWeight("b1"));
        assertEquals(2.5, cfg.backendWeight("b2"));
        assertEquals(3.0, cfg.backendWeight("b3"));
        assertEquals(1.0, cfg.backendWeight("b4")); // fallback to 1.0
        assertEquals(1.0, cfg.backendWeight("unregistered"));
    }

    @Test
    void fromMap_invalidTermsNode_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LoadBalancerConfigYaml.fromMap(Map.of("terms", "not-a-map-or-list")));
    }

    @Test
    void fromMap_invalidTermEntry_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LoadBalancerConfigYaml.fromMap(Map.of("terms", List.of("not-a-map"))));
    }

    @Test
    void fromMap_missingInputAndLabel_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LoadBalancerConfigYaml.fromMap(Map.of("terms", List.of(Map.of("weight", 1.0)))));
    }

    @Test
    void fromMap_invalidCurveNode_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LoadBalancerConfigYaml.fromMap(Map.of("terms", Map.of("mspt", Map.of("curve", 12345)))));
    }

    @Test
    void fromMap_invalidBackendsNode_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LoadBalancerConfigYaml.fromMap(Map.of("backends", "not-a-map")));
    }

    @Test
    void fromMap_stringCoercion_validAndInvalid() {
        Map<String, Object> map = Map.of(
                "staleAfterMs", "15000",
                "msptWeight", "2.5",
                "queueDepthWeight", "bad-number",
                "heapWeight", 10L
        );
        LoadBalancerConfig cfg = LoadBalancerConfigYaml.fromMap(map);
        assertEquals(15000L, cfg.staleAfterMs());
        assertEquals(2.5, cfg.msptWeight());
        assertEquals(LoadBalancerConfig.defaults().queueDepthWeight(), cfg.queueDepthWeight());
        assertEquals(10.0, cfg.heapWeight());
    }
}
